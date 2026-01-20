package com.koom.monitor.command

import com.koom.monitor.adb.AdbClient
import com.koom.monitor.model.MetricsSnapshot
import com.koom.monitor.model.MonitorConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import picocli.CommandLine
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.exists

/**
 * 单次扫描命令
 */
@CommandLine.Command(
    name = "scan",
    description = ["单次扫描 - 检测当前内存状态并报告"]
)
class ScanCommand : Runnable {
    private val logger = LoggerFactory.getLogger(ScanCommand::class.java)

    @CommandLine.Option(
        names = ["-p", "--package"],
        description = ["应用包名 (可指定多个，或使用文件路径 @packages.txt)"],
        required = true
    )
    private lateinit var packages: List<String>

    @CommandLine.Option(
        names = ["-t", "--heap-threshold"],
        description = ["堆内存使用率阈值 (0.0-1.0, 默认: \${DEFAULT-VALUE})"],
        defaultValue = "0.8"
    )
    private var heapThreshold: Double = 0.8

    @CommandLine.Option(
        names = ["--thread-threshold"],
        description = ["线程数阈值 (默认: \${DEFAULT-VALUE})"],
        defaultValue = "500"
    )
    private var threadThreshold: Int = 500

    @CommandLine.Option(
        names = ["--fd-threshold"],
        description = ["文件句柄数阈值 (默认: \${DEFAULT-VALUE})"],
        defaultValue = "1000"
    )
    private var fdThreshold: Int = 1000

    @CommandLine.Option(
        names = ["-o", "--output"],
        description = ["输出目录 (默认: \${DEFAULT-VALUE})"],
        defaultValue = "./reports"
    )
    private var outputDir: String = "./reports"

    @CommandLine.Option(
        names = ["--adb"],
        description = ["ADB路径 (默认: 自动查找)"]
    )
    private var adbPath: String? = null

    override fun run() {
        val config = buildConfig()
        executeScan(config)
    }

    private fun buildConfig(): MonitorConfig {
        // 解析包名列表 (支持文件)
        val packageNames = packages.flatMap { pkg ->
            if (pkg.startsWith("@")) {
                val filePath = pkg.substring(1)
                MonitorConfig.fromPackageFile(filePath)
            } else {
                listOf(pkg)
            }
        }

        // 确定ADB路径
        val actualAdbPath = adbPath ?: AdbClient.findAdb()

        // 创建输出目录
        val output = Paths.get(outputDir)
        if (!output.exists()) {
            Files.createDirectories(output)
        }

        return MonitorConfig(
            packageNames = packageNames,
            heapThreshold = heapThreshold,
            threadThreshold = threadThreshold,
            fdThreshold = fdThreshold,
            outputDir = output,
            adbPath = actualAdbPath
        )
    }

    private fun executeScan(config: MonitorConfig) {
        println("\n╔══════════════════════════════════════════════════════════════╗")
        println("║           Android Memory Monitor - 单次扫描                  ║")
        println("╚══════════════════════════════════════════════════════════════╝\n")

        val adb = AdbClient(config.adbPath)

        // 检查设备连接
        val devices = adb.getDevices()
        if (devices.isEmpty()) {
            logger.error("未检测到已连接的设备")
            println("❌ 未检测到已连接的设备，请确认:")
            println("   1. 设备已通过USB连接")
            println("   2. 已开启USB调试模式")
            println("   3. 已授权此电脑进行调试")
            return
        }

        println("📱 已连接设备:")
        devices.forEach { device ->
            println("   - ${device.model} (${device.serial})")
        }
        println()

        // 扫描所有包
        val results = mutableListOf<ScanResult>()

        for (packageName in config.packageNames) {
            println("🔍 正在扫描: $packageName")
            val snapshot = adb.collectSnapshot(packageName)

            if (snapshot != null) {
                printSnapshot(snapshot)
                results.add(ScanResult(packageName, snapshot))

                // 检查是否超阈值
                if (snapshot.isOverThreshold(config)) {
                    println("   ⚠️  超过阈值!")
                    snapshot.getOverThresholdReasons(config).forEach { reason ->
                        println("      - $reason")
                    }
                }
            } else {
                println("   ❌ 无法获取进程信息 (应用可能未运行)")
            }
            println()
        }

        // 输出汇总
        printSummary(results, config)

        if (results.any { it.snapshot.isOverThreshold(config) }) {
            println("\n💡 提示: 使用 'watch' 命令进行持续监控，超阈值时自动dump")
        }
    }

    private fun printSnapshot(snapshot: MetricsSnapshot) {
        println("   PID: ${snapshot.pid}")
        println("   堆内存: ${snapshot.heapInfo}")
        println("   线程数: ${snapshot.threadCount}")
        println("   文件句柄: ${snapshot.fdCount}")

        snapshot.vss?.let { println("   VSS: ${it / 1024 / 1024} MB") }
        snapshot.rss?.let { println("   RSS: ${it / 1024 / 1024} MB") }
    }

    private fun printSummary(results: List<ScanResult>, config: MonitorConfig) {
        if (results.isEmpty()) {
            println("📊 汇总: 没有可显示的结果")
            return
        }

        println("╔══════════════════════════════════════════════════════════════╗")
        println("║                         扫描汇总                              ║")
        println("╚══════════════════════════════════════════════════════════════╝\n")

        println("┌─────────────────────────────────────────────────────────────┐")
        println("│ 包名                  │ 堆内存  │ 线程  │ 句柄  │ 状态      │")
        println("├─────────────────────────────────────────────────────────────┤")

        results.forEach { result ->
            val pkg = result.packageName.take(20).padEnd(20)
            val heap = "${result.snapshot.heapUsagePercent}%".padEnd(7)
            val threads = result.snapshot.threadCount.toString().padEnd(5)
            val fds = result.snapshot.fdCount.toString().padEnd(5)
            val status = if (result.snapshot.isOverThreshold(config)) {
                "⚠️  超阈值"
            } else {
                "✓ 正常"
            }

            println("│ $pkg │ $heap │ $threads │ $fds │ $status │")
        }

        println("└─────────────────────────────────────────────────────────────┘")
        println()
    }

    data class ScanResult(
        val packageName: String,
        val snapshot: MetricsSnapshot
    )
}
