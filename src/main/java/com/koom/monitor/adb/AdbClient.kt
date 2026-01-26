package com.koom.monitor.adb

import com.koom.monitor.model.MetricsSnapshot
import com.koom.monitor.model.ThreadInfo as ModelThreadInfo
import com.koom.monitor.model.DuplicateThreadInfo
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
     * 参考 KOOM：使用 Runtime.maxMemory() 作为 heapMax
     * 通过 ADB 无法直接获取，使用系统属性 dalvik.vm.heapgrowthlimit 或 dalvik.vm.heapsize 作为近似值
     */
    fun getMemoryInfo(pid: Int): MemoryInfo? {
        // 首先尝试从系统属性获取 heapMax（更接近 Runtime.maxMemory()）
        // dalvik.vm.heapgrowthlimit 是大多数应用的默认最大堆内存
        // dalvik.vm.heapsize 是 largeHeap 应用的最大堆内存
        var heapMax = getHeapMaxFromSystemProperties()
        
        val result = shell("dumpsys meminfo $pid")
        if (result.exitCode != 0) {
            logger.error("获取内存信息失败: ${result.output}")
            return null
        }

        var heapUsed = 0L
        var vss: Long? = null
        var rss: Long? = null

        // 首先找到包含 "Heap" "Size" "Alloc" 的标题行，确定列位置
        var heapSizeIndex = -1
        var heapAllocIndex = -1
        val lines = result.output.lines().toList()
        
        for (i in lines.indices) {
            val line = lines[i]
            if (line.contains("Heap") && line.contains("Size") && line.contains("Alloc")) {
                // 这是标题行，解析列位置
                val headerParts = line.trim().split(Regex("\\s+"))
                heapSizeIndex = headerParts.indexOf("Size")
                heapAllocIndex = headerParts.indexOf("Alloc")
                // 如果找不到，尝试从后往前查找（Heap Size, Heap Alloc, Heap Free）
                if (heapSizeIndex == -1 || heapAllocIndex == -1) {
                    // 从后往前：最后一列是 Heap Free，倒数第二列是 Heap Alloc，倒数第三列是 Heap Size
                    heapSizeIndex = headerParts.size - 3
                    heapAllocIndex = headerParts.size - 2
                }
                break
            }
        }

        // 从 TOTAL 行解析 Heap Alloc（作为 heapUsed）
        // TOTAL 行的格式: "        TOTAL   455995   437937     1516      144   613605   481194   363288   113705"
        // 列顺序: TOTAL Pss Private Private SwapPss Rss Heap Size Heap Alloc Heap Free
        // 使用 TOTAL Heap Alloc 作为 heapUsed（包括 Native Heap 和 Dalvik Heap）
        if (heapAllocIndex >= 0) {
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.startsWith("TOTAL") && !trimmed.contains("PSS") && !trimmed.contains("RSS") && !trimmed.contains("SWAP")) {
                    val parts = trimmed.split(Regex("\\s+"))
                    if (parts.size > heapAllocIndex) {
                        val heapAllocStr = parts[heapAllocIndex].replace(",", "")
                        heapUsed = heapAllocStr.toLongOrNull()?.times(1024) ?: 0L
                    }
                    break
                }
            }
        }

        // 如果无法从 TOTAL 行获取，尝试从 Java Heap 行获取（作为后备方案）
        if (heapUsed == 0L) {
            result.output.lines().forEach { line ->
                when {
                    // 匹配格式: "           Java Heap:    18452                          48196"
                    // 或: "Java Heap: xxx kB"
                    line.contains("Java Heap:") -> {
                        val parts = line.trim().split(Regex("\\s+"))
                        // 查找数字（可能是 Pss 或 Rss 值）
                        for (part in parts) {
                            val value = part.replace(",", "").toLongOrNull()
                            if (value != null && value > 0) {
                                // 使用第一个找到的数字作为 heapUsed（通常是 Pss 值）
                                heapUsed = value * 1024
                                break
                            }
                        }
                    }
                }
            }
        }

        // 如果无法从系统属性获取 heapMax，尝试从 dumpsys meminfo 获取（作为后备方案）
        // 注意：dumpsys meminfo 中的 Heap Size 是当前已分配的堆内存，不是最大堆内存
        // 所以这个值可能比 Runtime.maxMemory() 小
        if (heapMax == 0L) {
            // 从 dumpsys meminfo 的 TOTAL 行获取 Heap Size（当前已分配的堆内存）
            val lines = result.output.lines().toList()
            var heapSizeIndex = -1
            
            for (i in lines.indices) {
                val line = lines[i]
                if (line.contains("Heap") && line.contains("Size") && line.contains("Alloc")) {
                    val headerParts = line.trim().split(Regex("\\s+"))
                    heapSizeIndex = headerParts.indexOf("Size")
                    if (heapSizeIndex == -1) {
                        heapSizeIndex = headerParts.size - 3
                    }
                    break
                }
            }
            
            if (heapSizeIndex >= 0) {
                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("TOTAL") && !trimmed.contains("PSS") && !trimmed.contains("RSS") && !trimmed.contains("SWAP")) {
                        val parts = trimmed.split(Regex("\\s+"))
                        if (parts.size > heapSizeIndex) {
                            val heapSizeStr = parts[heapSizeIndex].replace(",", "")
                            heapMax = heapSizeStr.toLongOrNull()?.times(1024) ?: 0L
                        }
                        break
                    }
                }
            }
        }

        // 如果还是无法获取，尝试从 /proc/status 获取 VSS（作为最后的后备方案）
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
     * 从系统属性获取最大堆内存（参考 KOOM 使用 Runtime.maxMemory()）
     * 
     * 优先级：
     * 1. dalvik.vm.heapgrowthlimit - 大多数应用的默认最大堆内存（对应 Runtime.maxMemory()）
     * 2. dalvik.vm.heapsize - largeHeap 应用的最大堆内存
     * 
     * @return 最大堆内存（bytes），如果获取失败返回 0
     */
    private fun getHeapMaxFromSystemProperties(): Long {
        // 首先尝试获取 heapgrowthlimit（大多数应用的默认值）
        val heapgrowthlimitResult = shell("getprop dalvik.vm.heapgrowthlimit")
        if (heapgrowthlimitResult.exitCode == 0) {
            val value = parseMemorySize(heapgrowthlimitResult.output.trim())
            if (value > 0) {
                logger.debug("从 dalvik.vm.heapgrowthlimit 获取 heapMax: ${value / (1024 * 1024)}MB")
                return value
            }
        }

        // 如果 heapgrowthlimit 不可用，尝试获取 heapsize（largeHeap 应用）
        val heapsizeResult = shell("getprop dalvik.vm.heapsize")
        if (heapsizeResult.exitCode == 0) {
            val value = parseMemorySize(heapsizeResult.output.trim())
            if (value > 0) {
                logger.debug("从 dalvik.vm.heapsize 获取 heapMax: ${value / (1024 * 1024)}MB")
                return value
            }
        }

        logger.warn("无法从系统属性获取 heapMax，将使用 dumpsys meminfo 中的值")
        return 0L
    }

    /**
     * 解析内存大小字符串（例如 "512m", "256m", "1g"）
     * 
     * @param sizeStr 内存大小字符串，例如 "512m", "256m", "1g"
     * @return 内存大小（bytes）
     */
    private fun parseMemorySize(sizeStr: String): Long {
        if (sizeStr.isEmpty()) return 0L
        
        val regex = Regex("^(\\d+)([kmgKMG])?$")
        val match = regex.find(sizeStr.trim())
        if (match != null) {
            val value = match.groupValues[1].toLongOrNull() ?: return 0L
            val unit = match.groupValues[2].lowercase()
            
            return when (unit) {
                "k" -> value * 1024
                "m" -> value * 1024 * 1024
                "g" -> value * 1024 * 1024 * 1024
                else -> value * 1024 * 1024 // 默认当作 MB
            }
        }
        
        return 0L
    }

    /**
     * 获取线程数
     */
    fun getThreadCount(pid: Int): Int {
        val result = shell("ls /proc/$pid/task 2>/dev/null | wc -l")
        return result.output.trim().toIntOrNull() ?: 0
    }

    /**
     * 获取线程信息列表 (包括线程ID和名字)
     */
    fun getThreads(pid: Int): List<ThreadInfo> {
        val result = shell("ls /proc/$pid/task 2>/dev/null")
        if (result.exitCode != 0) {
            return emptyList()
        }

        return result.output.lines()
            .filter { it.isNotBlank() }
            .mapNotNull { tid ->
                val tidNum = tid.toIntOrNull() ?: return@mapNotNull null
                // 读取线程名从 /proc/[pid]/task/[tid]/comm
                val commResult = shell("cat /proc/$pid/task/$tid/comm 2>/dev/null")
                val name = commResult.output.trim().takeIf { it.isNotBlank() } ?: "unknown"
                ThreadInfo(tid = tidNum, name = name)
            }
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

        // 获取线程信息
        val threads = getThreads(pid)
        val threadInfos = threads.map { ModelThreadInfo(it.tid, it.name) }

        // 分析重复的线程名字
        val duplicateThreads = MetricsSnapshot.analyzeThreads(threadInfos)

        return MetricsSnapshot(
            timestamp = Instant.now(),
            packageName = packageName,
            pid = pid,
            heapUsed = memInfo.heapUsed,
            heapMax = memInfo.heapMax,
            threadCount = threadCount,
            fdCount = fdCount,
            vss = memInfo.vss,
            rss = memInfo.rss,
            threads = threadInfos,
            duplicateThreads = duplicateThreads
        )
    }

    /**
     * 执行dump heap
     * @param includeBitmaps 是否包含Bitmap数据 (Android 14+, 使用 -b png)
     * @param triggerGc 是否在dump前触发GC (使用 -g)
     */
    fun dumpHeap(packageName: String, outputPath: String, includeBitmaps: Boolean = true, triggerGc: Boolean = true): ProcessResult {
        // 确保输出目录存在
        shell("mkdir -p ${outputPath.substringBeforeLast('/')}")

        val options = mutableListOf<String>()
        if (triggerGc) {
            options.add("-g")
        }
        if (includeBitmaps) {
            options.add("-b")
            options.add("png")
        }
        val optionsStr = if (options.isNotEmpty()) "${options.joinToString(" ")} " else ""
        val cmd = "am dumpheap $optionsStr$packageName $outputPath"
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

    /**
     * 线程信息
     */
    data class ThreadInfo(
        val tid: Int,
        val name: String
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
