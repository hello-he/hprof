package com.koom.monitor

import com.koom.monitor.analyzer.BitmapExtractor
import com.koom.monitor.analyzer.CompareDiagnosticsExporter
import com.koom.monitor.analyzer.HprofAnalyzer
import org.slf4j.LoggerFactory
import picocli.CommandLine
import java.nio.file.Files
import java.nio.file.Paths
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.io.path.exists

/**
 * 主入口
 */
fun main(args: Array<String>) {
    val exitCode = CommandLine(MemMonitorCommand())
        .setCaseInsensitiveEnumValuesAllowed(true)
        .execute(*args)
    kotlin.system.exitProcess(exitCode)
}

/**
 * 命令行入口（唯一模式：离线分析 hprof）
 */
@CommandLine.Command(
    name = "mem-analyze",
    mixinStandardHelpOptions = true,
    version = ["1.0.0"],
    description = ["Android 内存泄露分析工具 - 离线分析 hprof 文件，检测 Activity/Fragment/Animator 等泄露"]
)
class MemMonitorCommand : Runnable {
    private val logger = LoggerFactory.getLogger(MemMonitorCommand::class.java)

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
        names = ["--large-only"],
        description = ["只提取大Bitmap(>1M像素)"]
    )
    private var largeOnly: Boolean = false

    @CommandLine.Option(
        names = ["--export-compare-json"],
        description = ["仅导出全局诊断对比 JSON（与 hprof_parser.py 校验用），不生成完整报告"]
    )
    private var exportCompareJson: String? = null

    override fun run() {
        val actualHprofPath = Paths.get(hprofPath)
        if (!actualHprofPath.exists()) {
            println("❌ hprof文件不存在: $hprofPath")
            return
        }

        if (!exportCompareJson.isNullOrBlank()) {
            runExportCompareJson(actualHprofPath.toFile(), Paths.get(exportCompareJson!!))
            return
        }

        println("\n╔══════════════════════════════════════════════════════════════╗")
        println("║           Android Memory Monitor - 离线分析                  ║")
        println("╚══════════════════════════════════════════════════════════════╝\n")

        println("🔍 正在分析hprof文件，请稍候...")
        println()

        println("🔍 检测hprof文件是否包含Bitmap数据（dumpheap -b）...")
        val shouldExtractBitmaps = BitmapExtractor().hasBitmapDumpData(actualHprofPath.toFile())
        if (shouldExtractBitmaps) {
            println("✅ 检测到Bitmap dumpData，自动启用Bitmap提取和分析")
        } else {
            println("ℹ️  未检测到Bitmap dumpData（如需提取Bitmap，请使用 am dumpheap -b png 获取hprof）")
        }
        println()

        val hprofBaseName = actualHprofPath.fileName.toString().removeSuffix(".hprof")
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
        val outputDirName = "${hprofBaseName}_${timestamp}"

        val baseOutputDir = Paths.get(outputDir).toAbsolutePath().normalize()
        val actualOutputDir = baseOutputDir.resolve(outputDirName)
        Files.createDirectories(actualOutputDir)

        try {
            val bitmapDir = if (shouldExtractBitmaps) {
                actualOutputDir.resolve("bitmaps")
            } else {
                null
            }

            val analyzer = HprofAnalyzer()
            val result = analyzer.analyze(actualHprofPath.toFile(), bitmapDir)

            var hasBitmapAnalysis = false
            if (shouldExtractBitmaps && bitmapDir != null) {
                println("\n🖼️  正在提取Bitmap...")
                val extractor = BitmapExtractor()
                val bitmapResult = extractor.extract(
                    actualHprofPath.toFile(),
                    bitmapDir,
                    extractImages = true,
                    largeOnly = largeOnly
                )

                println(extractor.generateReport(bitmapResult))

                val bitmapTxtFile = actualOutputDir.resolve("bitmap_analysis.txt")
                val bitmapHtmlFile = actualOutputDir.resolve("bitmap_analysis.html")
                Files.writeString(bitmapTxtFile, extractor.generateReport(bitmapResult))
                Files.writeString(bitmapHtmlFile, extractor.generateHtmlReport(bitmapResult))
                hasBitmapAnalysis = true
            }

            result.printReport(hasBitmapAnalysis)

            val savedFiles = result.saveReport(actualOutputDir, hasBitmapAnalysis).toMutableList()

            if (shouldExtractBitmaps && bitmapDir != null) {
                val bitmapTxtFile = actualOutputDir.resolve("bitmap_analysis.txt")
                val bitmapHtmlFile = actualOutputDir.resolve("bitmap_analysis.html")
                if (Files.exists(bitmapTxtFile)) savedFiles.add(bitmapTxtFile)
                if (Files.exists(bitmapHtmlFile)) savedFiles.add(bitmapHtmlFile)
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

    private fun runExportCompareJson(hprofFile: java.io.File, outputJson: java.nio.file.Path) {
        println("📤 导出全局诊断对比 JSON（mem-analyze / Shark）...")
        val start = System.currentTimeMillis()
        try {
            CompareDiagnosticsExporter.export(hprofFile, outputJson)
            println("✅ 已写入: ${outputJson.toAbsolutePath()} (${System.currentTimeMillis() - start}ms)")
        } catch (e: Exception) {
            logger.error("导出对比 JSON 失败", e)
            println("❌ 导出失败: ${e.message}")
            e.printStackTrace()
            kotlin.system.exitProcess(1)
        }
    }
}
