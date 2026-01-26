package com.koom.monitor.command

import com.koom.monitor.adb.AdbClient
import com.koom.monitor.model.MetricsSnapshot
import com.koom.monitor.model.MonitorConfig
import com.koom.monitor.model.ThresholdTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import picocli.CommandLine
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.exists

/**
 * 持续监控命令
 */
@CommandLine.Command(
    name = "watch",
    description = ["持续监控 - 超阈值自动dump并分析"]
)
class WatchCommand : Runnable {
    private val logger = LoggerFactory.getLogger(WatchCommand::class.java)

    @CommandLine.Option(
        names = ["-p", "--package"],
        description = ["应用包名"],
        required = true
    )
    private lateinit var packages: List<String>

    @CommandLine.Option(
        names = ["-t", "--heap-threshold"],
        description = ["堆内存使用率阈值"],
        defaultValue = "0.8"
    )
    private var heapThreshold: Double = 0.8

    @CommandLine.Option(
        names = ["--thread-threshold"],
        description = ["线程数阈值"],
        defaultValue = "500"
    )
    private var threadThreshold: Int = 500

    @CommandLine.Option(
        names = ["--fd-threshold"],
        description = ["文件句柄数阈值"],
        defaultValue = "1000"
    )
    private var fdThreshold: Int = 1000

    @CommandLine.Option(
        names = ["-i", "--interval"],
        description = ["监控间隔(秒)"],
        defaultValue = "10"
    )
    private var intervalSeconds: Long = 10L

    @CommandLine.Option(
        names = ["--max-iterations"],
        description = ["最大迭代次数"]
    )
    private var maxIterations: Int? = null

    @CommandLine.Option(
        names = ["-o", "--output"],
        description = ["输出目录"],
        defaultValue = "./reports"
    )
    private var outputDir: String = "./reports"

    @CommandLine.Option(
        names = ["--no-auto-dump"],
        description = ["不自动dump"]
    )
    private var noAutoDump: Boolean = false

    @CommandLine.Option(
        names = ["--adb"],
        description = ["ADB路径"]
    )
    private var adbPath: String? = null

    override fun run() {
        val config = buildConfig()
        executeWatch(config)
    }

    private fun buildConfig(): MonitorConfig {
        val packageNames = packages.flatMap { pkg ->
            if (pkg.startsWith("@")) {
                MonitorConfig.fromPackageFile(pkg.substring(1))
            } else {
                listOf(pkg)
            }
        }

        val actualAdbPath = adbPath ?: AdbClient.findAdb()
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
            intervalSeconds = intervalSeconds,
            autoDump = !noAutoDump,
            maxIterations = maxIterations ?: 0,
            maxOverThresholdCount = 3, // 默认连续3次超过阈值才触发
            fastMemoryHighWatermarkThreshold = 0.9, // 90% 高水位线
            fastMemoryDeltaThresholdKB = 51200, // 50MB 增量阈值
            adbPath = actualAdbPath
        )
    }

    private fun executeWatch(config: MonitorConfig) {
        println("\n╔══════════════════════════════════════════════════════════════╗")
        println("║           Android Memory Monitor - 持续监控                  ║")
        println("╚══════════════════════════════════════════════════════════════╝\n")

        val adb = AdbClient(config.adbPath)

        val devices = adb.getDevices()
        if (devices.isEmpty()) {
            println("❌ 未检测到已连接的设备")
            return
        }

        println("📱 已连接设备: ${devices.first().model}")
        println("🎯 监控包名:")
        config.packageNames.forEach { println("   - $it") }
        println()
        println("⚙️  监控配置:")
        println("   堆内存阈值: ${(config.heapThreshold * 100).toInt()}%")
        println("   线程数阈值: ${config.threadThreshold}")
        println("   文件句柄阈值: ${config.fdThreshold}")
        println("   监控间隔: ${config.intervalSeconds}秒")
        println("   自动dump: ${if (config.autoDump) "是" else "否"}")
        println()
        println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n")

        val previousSnapshots = mutableMapOf<String, MetricsSnapshot>()
        // 记录每个包名是否已经dump过（参考KOOM，防止频繁dump）
        val hasDumped = mutableMapOf<String, Boolean>()
        // 为每个包名创建阈值跟踪器（参考 KOOM）
        val trackers = mutableMapOf<String, ThresholdTracker>()
        var iteration = 0

        while (config.maxIterations == 0 || iteration < config.maxIterations) {
            iteration++

            for (packageName in config.packageNames) {
                val snapshot = adb.collectSnapshot(packageName) ?: continue

                // 获取或创建阈值跟踪器
                val tracker = trackers.getOrPut(packageName) { ThresholdTracker() }

                val previous = previousSnapshots[packageName]
                val prefix = if (previous != null) {
                    val diff = snapshot.diff(previous)
                    val deltaHeap = if (diff.heapUsedDelta > 0) "+" else ""
                    val deltaThreads = if (diff.threadCountDelta > 0) "+" else ""
                    val deltaFds = if (diff.fdCountDelta > 0) "+" else ""

                    "[$iteration] ${formatTime(snapshot.timestamp)} | " +
                    "$packageName | 堆: ${snapshot.heapUsagePercent}% ($deltaHeap${formatBytes(diff.heapUsedDelta)}) | " +
                    "线程: ${snapshot.threadCount} ($deltaThreads${diff.threadCountDelta}) | " +
                    "句柄: ${snapshot.fdCount} ($deltaFds${diff.fdCountDelta})"
                } else {
                    "[$iteration] ${formatTime(snapshot.timestamp)} | " +
                    "$packageName | 堆: ${snapshot.heapUsagePercent}% | " +
                    "线程: ${snapshot.threadCount} | 句柄: ${snapshot.fdCount}"
                }

                println(prefix)

                // 显示线程泄露信息
                if (snapshot.hasDuplicateThreads && config.detectDuplicateThreads) {
                    val dupCount = snapshot.duplicateThreads.size
                    val dupThreads = snapshot.duplicateThreads.take(3).joinToString(", ") { it.description }
                    println("   🚨 线程泄露: $dupCount 种重复线程名 ($dupThreads)")
                }

                // 参考 KOOM：使用连续超过阈值检测 + 快速内存增长检测
                var shouldDump = false
                val dumpReasons = mutableListOf<String>()

                // 1. 检查快速内存增长（优先级最高，立即触发）
                if (tracker.checkFastMemoryGrowth(
                        snapshot.heapUsed,
                        snapshot.heapMax,
                        config.fastMemoryHighWatermarkThreshold,
                        config.fastMemoryDeltaThresholdKB
                    )) {
                    shouldDump = true
                    val heapRatio = snapshot.heapUsageRatio
                    if (heapRatio > config.fastMemoryHighWatermarkThreshold) {
                        dumpReasons.add("快速内存增长：高水位线 ${(heapRatio * 100).toInt()}% >= ${(config.fastMemoryHighWatermarkThreshold * 100).toInt()}%")
                    } else {
                        val previous = previousSnapshots[packageName]
                        if (previous != null) {
                            val deltaMB = (snapshot.heapUsed - previous.heapUsed) / (1024.0 * 1024)
                            dumpReasons.add("快速内存增长：内存增量 ${"%.1f".format(deltaMB)}MB >= ${config.fastMemoryDeltaThresholdKB / 1024}MB")
                        } else {
                            dumpReasons.add("快速内存增长：内存增量 >= ${config.fastMemoryDeltaThresholdKB / 1024}MB")
                        }
                    }
                }

                // 2. 检查连续超过阈值（需要连续多次）
                val heapTriggered = tracker.checkHeapThreshold(
                    snapshot.heapUsageRatio,
                    config.heapThreshold,
                    config.maxOverThresholdCount
                )
                val threadTriggered = tracker.checkThreadThreshold(
                    snapshot.threadCount,
                    config.threadThreshold,
                    config.maxOverThresholdCount
                )
                val fdTriggered = tracker.checkFdThreshold(
                    snapshot.fdCount,
                    config.fdThreshold,
                    config.maxOverThresholdCount
                )

                if (heapTriggered) {
                    shouldDump = true
                    dumpReasons.add("堆内存连续 ${config.maxOverThresholdCount} 次超过阈值 ${(config.heapThreshold * 100).toInt()}%")
                }
                if (threadTriggered) {
                    shouldDump = true
                    dumpReasons.add("线程数连续 ${config.maxOverThresholdCount} 次超过阈值 ${config.threadThreshold}")
                }
                if (fdTriggered) {
                    shouldDump = true
                    dumpReasons.add("文件句柄连续 ${config.maxOverThresholdCount} 次超过阈值 ${config.fdThreshold}")
                }

                // 3. 检查重复线程名（立即触发，不需要连续）
                if (snapshot.hasDuplicateThreads && config.detectDuplicateThreads) {
                    shouldDump = true
                    val dupInfo = snapshot.duplicateThreads.take(3).joinToString(", ") { it.description }
                    val more = if (snapshot.duplicateThreads.size > 3) "..." else ""
                    dumpReasons.add("线程名字重复: $dupInfo$more")
                }

                // 显示超过阈值的信息（但不一定触发dump）
                if (snapshot.isOverThreshold(config) && !shouldDump) {
                    val reasons = snapshot.getOverThresholdReasons(config)
                    println("   ⚠️  超过阈值: ${reasons.joinToString(", ")} (连续 ${tracker.heapOverThresholdCount}/${config.maxOverThresholdCount} 次)")
                }

                // 触发 dump
                if (shouldDump && config.autoDump) {
                    println("   ⚠️  触发条件: ${dumpReasons.joinToString(", ")}")
                    // 参考KOOM，每个包名只dump一次，防止频繁dump
                    if (hasDumped[packageName] != true) {
                        println("   🔔 触发dump...")
                        performDump(snapshot, adb, config)
                        hasDumped[packageName] = true
                        // dump 后重置跟踪器
                        tracker.reset()
                    } else {
                        println("   ℹ️  已dump过，跳过（防止频繁dump）")
                    }
                }

                previousSnapshots[packageName] = snapshot
            }

            println()
            Thread.sleep(config.intervalSeconds * 1000)
        }

        println("\n✅ 监控结束")
    }

    private fun performDump(snapshot: MetricsSnapshot, adb: AdbClient, config: MonitorConfig) {
        try {
            val timestamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
            val reportDir = config.outputDir.resolve("${snapshot.packageName}_$timestamp")
            Files.createDirectories(reportDir)

            val screenshotPath = "/data/local/tmp/screenshot_${snapshot.pid}.png"
            println("   📸 正在截屏...")
            adb.takeScreenshot(screenshotPath)

            val hprofPath = "/data/local/tmp/heap_${snapshot.pid}_$timestamp.hprof"
            println("   💾 正在dump堆内存...")
            adb.dumpHeap(snapshot.packageName, hprofPath)

            println("   ⏳ 等待dump完成...")
            Thread.sleep(5000)

            val localHprof = reportDir.resolve("heap.hprof")
            val localScreenshot = reportDir.resolve("screenshot.png")

            println("   📥 正在拉取文件...")
            adb.pull(hprofPath, localHprof)
            adb.pull(screenshotPath, localScreenshot)

            adb.removeRemote(hprofPath)
            adb.removeRemote(screenshotPath)

            println("   📊 报告目录: $reportDir")

        } catch (e: Exception) {
            logger.error("dump失败", e)
            println("   ❌ dump失败: ${e.message}")
        }
    }

    private fun formatTime(instant: java.time.Instant): String {
        return java.time.LocalDateTime.ofInstant(instant, java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 -> "%.1fMB".format(bytes / (1024.0 * 1024))
            bytes >= 1024 -> "%.1fKB".format(bytes / 1024.0)
            else -> "${bytes}B"
        }
    }
}
