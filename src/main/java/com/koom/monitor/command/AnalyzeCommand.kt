package com.koom.monitor.command

import com.koom.monitor.analyzer.BitmapExtractor
import com.koom.monitor.analyzer.HprofAnalyzer
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
        names = ["-o", "--output"],
        description = ["输出目录"],
        defaultValue = "./reports"
    )
    private var outputDir: String = "./reports"

    @CommandLine.Option(
        names = ["--extract-bitmaps"],
        description = ["提取Bitmap并检测重复"]
    )
    private var extractBitmaps: Boolean = false

    @CommandLine.Option(
        names = ["--large-only"],
        description = ["只提取大Bitmap(>1M像素)"]
    )
    private var largeOnly: Boolean = false

    override fun run() {
        val actualHprofPath = Paths.get(hprofPath)
        if (!actualHprofPath.exists()) {
            println("❌ hprof文件不存在: $hprofPath")
            return
        }

        println("\n╔══════════════════════════════════════════════════════════════╗")
        println("║           Android Memory Monitor - 离线分析                  ║")
        println("╚══════════════════════════════════════════════════════════════╝\n")

        println("🔍 正在分析hprof文件，请稍候...")
        println()

        // 使用hprof文件名（不含扩展名）作为输出子目录
        val hprofBaseName = actualHprofPath.fileName.toString().removeSuffix(".hprof")
        // 规范化路径，去除 ./ 前缀
        val baseOutputDir = Paths.get(outputDir).toAbsolutePath().normalize()
        val actualOutputDir = baseOutputDir.resolve(hprofBaseName)
        Files.createDirectories(actualOutputDir)

        try {
            // 先创建bitmap目录（如果需要提取）
            val bitmapDir = if (extractBitmaps) {
                actualOutputDir.resolve("bitmaps")
            } else {
                null
            }

            val analyzer = HprofAnalyzer()
            val result = analyzer.analyze(actualHprofPath.toFile(), bitmapDir)

            // 打印到控制台
            result.printReport()

            // 保存报告到文件（在bitmap提取之后，以便包含bitmap路径信息）
            val savedFiles = result.saveReport(actualOutputDir).toMutableList()

            // 提取Bitmap
            if (extractBitmaps && bitmapDir != null) {
                println("\n🖼️  正在提取Bitmap...")
                val extractor = BitmapExtractor()
                val bitmapResult = extractor.extract(
                    actualHprofPath.toFile(),
                    bitmapDir,
                    extractImages = true,
                    largeOnly = largeOnly
                )

                println(extractor.generateReport(bitmapResult))

                // 保存Bitmap报告
                val bitmapTxtFile = actualOutputDir.resolve("bitmap_analysis.txt")
                val bitmapHtmlFile = actualOutputDir.resolve("bitmap_analysis.html")
                Files.writeString(bitmapTxtFile, extractor.generateReport(bitmapResult))
                Files.writeString(bitmapHtmlFile, extractor.generateHtmlReport(bitmapResult))
                savedFiles.add(bitmapTxtFile)
                savedFiles.add(bitmapHtmlFile)
            }

            println("✅ 分析完成!")
            println()
            println("📁 报告已保存到:")
            savedFiles.forEach { file ->
                println("   - ${file.toAbsolutePath()}")
            }
            println()

        } catch (e: Exception) {
            logger.error("分析失败", e)
            println("❌ 分析失败: ${e.message}")
            e.printStackTrace()
        }
    }
}
