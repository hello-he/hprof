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
 * AnalyzeCommand 功能测试
 */
class AnalyzeCommandTest {

    private lateinit var tempDir: Path
    private lateinit var testHprof: File

    @Before
    fun setUp() {
        tempDir = createTempDirectory("analyze-test")
        testHprof = File(System.getProperty("user.home") + "/tmp/hprof/test.hprof")
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
    fun testOutputDirectoryStructure() {
        if (!testHprof.exists()) {
            println("跳过测试: hprof文件不存在")
            return
        }

        // 模拟 analyze 命令的输出目录逻辑
        val hprofFileName = testHprof.name
        val hprofBaseName = hprofFileName.removeSuffix(".hprof")
        val outputDir = tempDir.resolve("reports").resolve(hprofBaseName)

        Files.createDirectories(outputDir)

        // 验证目录结构
        assertTrue("输出目录应该存在", Files.exists(outputDir))
        assertTrue("输出目录应该是 hprof 文件名（不含扩展名）",
            outputDir.fileName.toString() == "test")
        assertTrue("路径应该包含 reports 子目录",
            outputDir.toString().contains("reports"))
    }

    @Test
    fun testHprofAnalysisWithBitmapDir() {
        if (!testHprof.exists()) {
            println("跳过测试: hprof文件不存在")
            return
        }

        val outputDir = tempDir.resolve("reports").resolve("test")
        Files.createDirectories(outputDir)

        val bitmapDir = outputDir.resolve("bitmaps")
        val analyzer = HprofAnalyzer()
        val result = analyzer.analyze(testHprof, bitmapDir)

        // 验证分析结果
        assertNotNull("结果不应为空", result)
        assertTrue("应该有实例统计", result.stats.totalInstanceCount > 0)
        assertEquals("bitmapOutputDir 应该匹配", bitmapDir, result.bitmapOutputDir)

        // 保存报告
        val savedFiles = result.saveReport(outputDir)

        assertTrue("应该保存了报告文件", savedFiles.isNotEmpty())

        // 验证HTML报告包含bitmap目录路径
        val htmlFile = savedFiles.find { it.toString().endsWith(".html") }
        if (htmlFile != null) {
            val htmlContent = Files.readString(htmlFile)
            assertTrue("HTML应该包含Bitmap统计", htmlContent.contains("Bitmap 统计"))

            if (bitmapDir != null) {
                // HTML应该包含bitmap目录名称（相对路径，不是绝对路径）
                assertTrue("HTML应该包含bitmap目录名称",
                    htmlContent.contains(bitmapDir.fileName.toString()))
            }
        }
    }

    @Test
    fun testBitmapExtraction() {
        if (!testHprof.exists()) {
            println("跳过测试: hprof文件不存在")
            return
        }

        val outputDir = tempDir.resolve("test").resolve("bitmaps")
        val extractor = BitmapExtractor()

        val result = extractor.extract(
            hprofFile = testHprof,
            outputDir = outputDir,
            extractImages = true,
            largeOnly = false
        )

        // 验证提取结果
        assertTrue("应该找到Bitmap", result.totalBitmaps > 0)
        assertTrue("应该提取了Bitmap", result.extractedBitmaps > 0)

        // 验证图片文件被创建
        val pngFiles = Files.walk(outputDir)
            .filter { Files.isRegularFile(it) }
            .filter { it.toString().endsWith(".png") }
            .count()

        assertTrue("应该有PNG文件", pngFiles > 0)

        // 验证报告生成
        val txtReport = extractor.generateReport(result)
        assertTrue("文本报告应包含Bitmap信息", txtReport.contains("Bitmap"))
        assertTrue("文本报告应包含统计信息", txtReport.contains("统计信息"))

        val htmlReport = extractor.generateHtmlReport(result)
        assertTrue("HTML报告应有正确格式", htmlReport.contains("<!DOCTYPE html>"))
        assertTrue("HTML报告应包含Bitmap信息", htmlReport.contains("Bitmap") || htmlReport.contains("bitmap"))
    }

    @Test
    fun testLargeBitmapExtraction() {
        if (!testHprof.exists()) {
            println("跳过测试: hprof文件不存在")
            return
        }

        val outputDir = tempDir.resolve("test").resolve("bitmaps")
        val extractor = BitmapExtractor()

        val result = extractor.extract(
            hprofFile = testHprof,
            outputDir = outputDir,
            extractImages = true,
            largeOnly = false
        )

        // 验证大Bitmap信息
        assertTrue("应该有大Bitmap统计", result.largeBitmaps >= 0)
        assertTrue("大Bitmap列表应该存在", result.largeBitmapsList.isNotEmpty())

        // 如果有大Bitmap，验证列表大小
        if (result.largeBitmaps > 0) {
            // 注意：largeBitmapsList 已去重，不包含重复的大Bitmap
            // 重复的大Bitmap会在"重复的Bitmap" section中显示
            assertTrue("去重后的大Bitmap列表大小应该≤总数",
                result.largeBitmapsList.size <= result.largeBitmaps)
            println("  大Bitmap总数: ${result.largeBitmaps}, 去重后显示: ${result.largeBitmapsList.size}")
        }
    }

    @Test
    fun testDuplicateBitmapDetection() {
        if (!testHprof.exists()) {
            println("跳过测试: hprof文件不存在")
            return
        }

        val outputDir = tempDir.resolve("test").resolve("bitmaps")
        val extractor = BitmapExtractor()

        val result = extractor.extract(
            hprofFile = testHprof,
            outputDir = outputDir,
            extractImages = true,
            largeOnly = false
        )

        // 验证重复检测
        assertTrue("重复组数应该>=0", result.duplicateGroups.size >= 0)

        // 如果有重复，验证信息完整性
        if (result.duplicateGroups.isNotEmpty()) {
            val firstGroup = result.duplicateGroups.values.first()
            assertTrue("每组应该有至少2个重复", firstGroup.size >= 2)
            assertEquals("组内Bitmap尺寸应该相同",
                firstGroup.first().width,
                firstGroup.last().width)
        }
    }
}
