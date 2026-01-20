package com.koom.monitor.adb

import com.koom.monitor.model.MetricsSnapshot
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.exists

/**
 * ADB客户端
 */
class AdbClient(
    private val adbPath: String = "adb"
) {
    private val logger = LoggerFactory.getLogger(AdbClient::class.java)

    /**
     * 执行ADB命令
     */
    fun execute(command: String, vararg args: String): ProcessResult {
        val fullCommand = listOf(adbPath, command) + args
        logger.debug("执行: ${fullCommand.joinToString(" ")}")

        return try {
            val process = ProcessBuilder(fullCommand)
                .redirectErrorStream(true)
                .start()

            val output = process.inputReader().use { it.readText() }
            val exitCode = process.waitFor()

            ProcessResult(exitCode, output.trim())
        } catch (e: Exception) {
            logger.error("ADB命令执行失败: ${e.message}")
            ProcessResult(-1, "", e)
        }
    }

    /**
     * 执行ADB shell命令
     */
    fun shell(command: String): ProcessResult {
        return execute("shell", command)
    }

    /**
     * 获取设备列表
     */
    fun getDevices(): List<DeviceInfo> {
        val result = execute("devices", "-l")
        if (result.exitCode != 0) {
            logger.error("获取设备列表失败: ${result.output}")
            return emptyList()
        }

        return result.output.lines()
            .drop(1) // 跳过标题行
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split(Regex("\\s+"))
                if (parts.size >= 2 && parts[1] == "device") {
                    DeviceInfo(
                        serial = parts[0],
                        model = parts.find { it.startsWith("model:") }?.substring(6) ?: "Unknown",
                        product = parts.find { it.startsWith("product:") }?.substring(8) ?: "Unknown",
                        device = parts.find { it.startsWith("device:") }?.substring(7) ?: "Unknown"
                    )
                } else null
            }
    }

    /**
     * 根据包名获取进程ID
     */
    fun getPid(packageName: String): Int? {
        // 尝试1: 使用pidof
        val pidofResult = shell("pidof $packageName")
        if (pidofResult.exitCode == 0 && pidofResult.output.isNotBlank()) {
            return pidofResult.output.toIntOrNull()
        }

        // 尝试2: 使用ps
        val psResult = shell("ps | grep $packageName | grep -v grep")
        if (psResult.exitCode == 0 && psResult.output.isNotBlank()) {
            val line = psResult.output.lines().firstOrNull()
            if (line != null) {
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 2) {
                    return parts[1].toIntOrNull()
                }
            }
        }

        // 尝试3: 使用dumpsys activity
        val activityResult = shell("dumpsys activity top | grep pid=")
        if (activityResult.exitCode == 0 && activityResult.output.isNotBlank()) {
            val match = Regex("pid=(\\d+)").find(activityResult.output)
            if (match != null) {
                val pid = match.groupValues[1].toIntOrNull()
                // 验证该进程是否属于目标包
                if (pid != null) {
                    val packageCheck = shell("cat /proc/$pid/cmdline | tr '\\0' ' '")
                    if (packageCheck.output.contains(packageName)) {
                        return pid
                    }
                }
            }
        }

        logger.warn("无法找到包 $packageName 的进程ID")
        return null
    }

    /**
     * 获取内存信息
     */
    fun getMemoryInfo(pid: Int): MemoryInfo? {
        val result = shell("dumpsys meminfo $pid")
        if (result.exitCode != 0) {
            logger.error("获取内存信息失败: ${result.output}")
            return null
        }

        var heapUsed = 0L
        var heapMax = 0L
        var vss: Long? = null
        var rss: Long? = null

        result.output.lines().forEach { line ->
            when {
                line.contains("Java Heap:") && line.contains("kB") -> {
                    val parts = line.split(Regex("\\s+"))
                    // Java Heap: xxx kB
                    if (parts.size >= 3) {
                        heapUsed = (parts[1].toLongOrNull() ?: 0) * 1024
                    }
                }
                line.trim().startsWith("TOTAL:") && line.contains("kB") -> {
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size >= 3) {
                        heapMax = (parts[1].toLongOrNull() ?: 0) * 1024
                    }
                }
            }
        }

        // 如果无法从meminfo获取，尝试从/proc/status
        if (heapMax == 0L) {
            val statusResult = shell("cat /proc/$pid/status | grep -E 'VmPeak|VmSize|VmRSS'")
            statusResult.output.lines().forEach { line ->
                val parts = line.split(Regex("\\s+"))
                when {
                    line.startsWith("VmPeak:") -> vss = parts[1].toLongOrNull()?.times(1024)
                    line.startsWith("VmRSS:") -> rss = parts[1].toLongOrNull()?.times(1024)
                }
            }
            heapMax = vss ?: 0L
        }

        return MemoryInfo(heapUsed, heapMax, vss, rss)
    }

    /**
     * 获取线程数
     */
    fun getThreadCount(pid: Int): Int {
        val result = shell("ls /proc/$pid/task 2>/dev/null | wc -l")
        return result.output.trim().toIntOrNull() ?: 0
    }

    /**
     * 获取文件句柄数
     */
    fun getFdCount(pid: Int): Int {
        val result = shell("ls /proc/$pid/fd 2>/dev/null | wc -l")
        return result.output.trim().toIntOrNull() ?: 0
    }

    /**
     * 采集指标快照
     */
    fun collectSnapshot(packageName: String): MetricsSnapshot? {
        val pid = getPid(packageName) ?: return null
        val memInfo = getMemoryInfo(pid) ?: return null
        val threadCount = getThreadCount(pid)
        val fdCount = getFdCount(pid)

        return MetricsSnapshot(
            timestamp = Instant.now(),
            packageName = packageName,
            pid = pid,
            heapUsed = memInfo.heapUsed,
            heapMax = memInfo.heapMax,
            threadCount = threadCount,
            fdCount = fdCount,
            vss = memInfo.vss,
            rss = memInfo.rss
        )
    }

    /**
     * 执行dump heap
     * @param includeBitmaps 是否包含Bitmap数据 (Android 14+, 使用 -b png)
     */
    fun dumpHeap(packageName: String, outputPath: String, includeBitmaps: Boolean = true): ProcessResult {
        // 确保输出目录存在
        shell("mkdir -p ${outputPath.substringBeforeLast('/')}")

        val bitmapOption = if (includeBitmaps) "-b png " else ""
        val cmd = "am dumpheap $bitmapOption$packageName $outputPath"
        logger.info("执行: $cmd")
        return shell(cmd)
    }

    /**
     * 截屏
     */
    fun takeScreenshot(outputPath: String): ProcessResult {
        // 确保输出目录存在
        shell("mkdir -p ${outputPath.substringBeforeLast('/')}")

        val cmd = "screencap -p $outputPath"
        logger.info("执行: $cmd")
        return shell(cmd)
    }

    /**
     * 从设备拉取文件
     */
    fun pull(remotePath: String, localPath: Path): Boolean {
        val result = execute("pull", remotePath, localPath.toString())
        if (result.exitCode == 0) {
            logger.info("已拉取: $remotePath -> $localPath")
            return true
        }
        logger.error("拉取失败: $remotePath -> ${result.output}")
        return false
    }

    /**
     * 删除设备上的文件
     */
    fun removeRemote(remotePath: String): ProcessResult {
        return shell("rm -f $remotePath")
    }

    /**
     * 等待dump完成
     */
    suspend fun waitForDump(remotePath: String, timeoutMs: Long = 300000): Boolean {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val result = shell("test -f $remotePath && echo exists")
            if (result.output.contains("exists")) {
                // 检查文件大小是否稳定 (不再是0且正在写入)
                delay(1000)
                return true
            }
            delay(1000)
        }
        return false
    }

    /**
     * 数据类
     */
    data class ProcessResult(
        val exitCode: Int,
        val output: String,
        val exception: Exception? = null
    ) {
        val isSuccess: Boolean get() = exitCode == 0
    }

    data class DeviceInfo(
        val serial: String,
        val model: String,
        val product: String,
        val device: String
    )

    data class MemoryInfo(
        val heapUsed: Long,
        val heapMax: Long,
        val vss: Long?,
        val rss: Long?
    )

    companion object {
        /**
         * 查找ADB可执行文件
         */
        fun findAdb(): String {
            // 检查环境变量
            val androidHome = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
            if (androidHome != null) {
                val adbPath = listOf(
                    "$androidHome/platform-tools/adb",
                    "$androidHome/platform-tools/adb.exe",
                    "$androidHome/cmdline-tools/latest/bin/adb",
                    "$androidHome/cmdline-tools/latest/bin/adb.exe"
                ).firstOrNull { java.io.File(it).exists() }

                if (adbPath != null) {
                    return adbPath
                }
            }

            // 检查PATH
            val pathResult = Runtime.getRuntime().exec("which adb")
            val output = BufferedReader(InputStreamReader(pathResult.inputStream)).use { it.readText() }
            if (pathResult.waitFor() == 0 && output.isNotBlank()) {
                return output.trim()
            }

            // 返回默认值
            return "adb"
        }
    }
}
