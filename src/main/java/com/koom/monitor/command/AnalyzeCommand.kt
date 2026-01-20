package com.koom.monitor.command

import com.koom.monitor.adb.AdbClient
import com.koom.monitor.model.MonitorConfig
import org.slf4j.LoggerFactory
import picocli.CommandLine
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.exists

/**
 * 离线分析命令
 */
@CommandLine.Command(
    name = "analyze",
    description = ["离线分析 - 分析已有hprof文件"]
)
class AnalyzeCommand : Runnable {
    private val logger = LoggerFactory.getLogger(AnalyzeCommand::class.java)

    @CommandLine.Option(
        names = ["-f", "--hprof"],
        description = ["hprof文件路径"],
        required = true
    )
    private lateinit var hprofPath: String

    @CommandLine.Option(
        names = ["-p", "--package"],
        description = ["应用包名"],
        required = true
    )
    private lateinit var packageName: String

    @CommandLine.Option(
        names = ["-o", "--output"],
        description = ["输出目录"],
        defaultValue = ""
    )
    private var outputDir: String = ""

    @CommandLine.Option(
        names = ["--adb"],
        description = ["ADB路径"]
    )
    private var adbPath: String? = null

    override fun run() {
        println("\n╔══════════════════════════════════════════════════════════════╗")
        println("║           Android Memory Monitor - 离线分析                  ║")
        println("╚══════════════════════════════════════════════════════════════╝\n")

        val actualHprofPath = Paths.get(hprofPath)
        if (!actualHprofPath.exists()) {
            println("❌ hprof文件不存在: $hprofPath")
            return
        }

        val actualOutputDir = if (outputDir.isEmpty()) {
            actualHprofPath.parent ?: Paths.get("./reports")
        } else {
            Paths.get(outputDir)
        }

        Files.createDirectories(actualOutputDir)

        println("📄 hprof文件: $actualHprofPath")
        println("📱 应用包名: $packageName")
        println()
        println("📊 输出目录: $actualOutputDir")
        println()
        println("ℹ️  注意: 完整的hprof分析功能需要集成Shark库")
        println("    当前版本提供基础的dump和截图功能")
        println()
    }
}
