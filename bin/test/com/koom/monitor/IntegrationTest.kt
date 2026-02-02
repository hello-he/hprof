package com.koom.monitor

import com.koom.monitor.analyzer.BitmapExtractor
import com.koom.monitor.analyzer.HprofAnalyzer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory

/**
 * 集成测试 - 使用真实的hprof文件测试分析功能
 */
class IntegrationTest {

    private lateinit var tempDir: Path

    @Before
    fun setUp() {
        tempDir = createTempDirectory("mem-analyze-test")
    }

    @After
    fun tearDown() {
        if (Files.exists(tempDir)) {
            Files.walk(tempDir)
                .sorted(Comparator.reverseOrder())
                .forEach { Files.deleteIfExists(it) }
        }
    }

    @Test
    fun testAnalyzeHprofFile() {
        val sampleHprof = File(System.getProperty("user.home") + "/tmp/hprof/test.hprof")
        if (!sampleHprof.exists()) {
            println("跳过测试: 样例hprof文件不存在: ${sampleHprof.absolutePath}")
            return
        }

        val analyzer = HprofAnalyzer()
        val result = analyzer.analyze(sampleHprof)

        // 验证基本结果
        assertTrue("应该有实例被统计", result.stats.totalInstanceCount > 0)
        assertTrue("应该有类被统计", result.stats.totalClassCount > 0)

        println("分析结果: ${result.stats.totalInstanceCount} 个实例, ${result.stats.totalClassCount} 个类")
        println("Bitmap数量: ${result.stats.bitmapCount}")
    }

    @Test
    fun testExtractBitmapsFromHprof() {
        val sampleHprof = File(System.getProperty("user.home") + "/tmp/hprof/test.hprof")
        if (!sampleHprof.exists()) {
            println("跳过测试: 样例hprof文件不存在: ${sampleHprof.absolutePath}")
            return
        }

        val outputDir = tempDir.resolve("bitmaps")
        val extractor = BitmapExtractor()

        val result = extractor.extract(
            hprofFile = sampleHprof,
            outputDir = outputDir,
            extractImages = true,
            largeOnly = false
        )

        // 验证提取结果
        assertTrue("应该找到Bitmap对象", result.totalBitmaps > 0)
        println("找到 ${result.totalBitmaps} 个Bitmap, 提取了 ${result.extractedBitmaps} 个")

        if (result.extractedBitmaps > 0) {
            // 验证图片文件被创建
            val bitmapFiles = Files.walk(outputDir)
                .filter { Files.isRegularFile(it) }
                .filter { it.toString().endsWith(".png") }
                .count()

            assertTrue("应该有PNG文件被创建", bitmapFiles > 0)
            println("创建了 $bitmapFiles 个PNG文件")

            // 验证报告生成
            val report = extractor.generateReport(result)
            assertNotNull(report, "报告应该不为空")
            assertTrue("报告应该包含Bitmap信息", report.contains("Bitmap"))

            val htmlReport = extractor.generateHtmlReport(result)
            assertNotNull(htmlReport, "HTML报告应该不为空")
            assertTrue("应该是有效的HTML", htmlReport.contains("<!DOCTYPE html>"))
        }
    }

    @Test
    fun testGenerateReportsWithBitmapDir() {
        val sampleHprof = File(System.getProperty("user.home") + "/tmp/hprof/test.hprof")
        if (!sampleHprof.exists()) {
            println("跳过测试: 样例hprof文件不存在: ${sampleHprof.absolutePath}")
            return
        }

        val outputDir = tempDir.resolve("reports")
        Files.createDirectories(outputDir)

        val bitmapDir = outputDir.resolve("bitmaps")
        val analyzer = HprofAnalyzer()
        val result = analyzer.analyze(sampleHprof, bitmapDir)

        // 保存报告
        val savedFiles = result.saveReport(outputDir)

        assertTrue("应该有报告文件被保存", savedFiles.isNotEmpty())
        savedFiles.forEach { file ->
            assertTrue("报告文件应该存在: ${file}", Files.exists(file))
            println("报告已保存: ${file.fileName}")
        }

        // 验证HTML包含bitmap目录路径
        val htmlFile = savedFiles.find { it.toString().endsWith(".html") }
        if (htmlFile != null) {
            val htmlContent = Files.readString(htmlFile)
            assertTrue("HTML应该包含Bitmap统计", htmlContent.contains("Bitmap 统计"))
            println("HTML报告大小: ${Files.size(htmlFile)} 字节")
        }
    }
}
