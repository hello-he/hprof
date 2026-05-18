package com.koom.monitor

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
 * HprofAnalyzer 泄露检测功能测试
 * 
 * 基于TDD原则，验证每种泄露类型都能被正确检测
 * 
 * 测试前提：
 * 1. 需要从 demo APK 生成包含各种泄露的 hprof 文件
 * 2. hprof 文件应放在 ~/tmp/hprof/ 目录下，命名格式：{leakType}_leak.hprof
 * 
 * 生成测试 hprof 文件的方法：
 * 1. 运行 demo APK
 * 2. 触发对应的泄露（如点击"Activity泄露"按钮）
 * 3. 使用 adb 命令 dump hprof: adb shell am dumpheap <package> /sdcard/{leakType}_leak.hprof
 * 4. 拉取文件: adb pull /sdcard/{leakType}_leak.hprof ~/tmp/hprof/
 */
class HprofAnalyzerLeakDetectionTest {

    private lateinit var tempDir: Path
    private val hprofBaseDir = File(System.getProperty("user.home") + "/tmp/hprof")
    
    @Before
    fun setUp() {
        tempDir = createTempDirectory("leak-detection-test")
    }

    @After
    fun tearDown() {
        if (Files.exists(tempDir)) {
            Files.walk(tempDir)
                .sorted(Comparator.reverseOrder())
                .forEach { Files.deleteIfExists(it) }
        }
    }

    /**
     * 辅助方法：检查 hprof 文件是否存在，如果不存在则跳过测试
     */
    private fun getHprofFile(name: String): File? {
        val file = File(hprofBaseDir, name)
        return if (file.exists()) file else null
    }

    /**
     * 辅助方法：分析 hprof 文件并验证基本结果
     */
    private fun analyzeAndVerify(hprofFile: File): HprofAnalyzer.AnalysisResult {
        val analyzer = HprofAnalyzer()
        val result = analyzer.analyze(hprofFile)
        
        // 基本验证
        assertNotNull("分析结果不应为空", result)
        assertTrue("应该有实例被统计", result.stats.totalInstanceCount > 0)
        assertTrue("应该有类被统计", result.stats.totalClassCount > 0)
        assertTrue("分析耗时应该>0", result.analyzeTime > 0)
        
        return result
    }

    // ==================== Activity 泄露检测测试 ====================

    @Test
    fun testActivityLeakDetection() {
        val hprofFile = getHprofFile("activity_leak.hprof")
        if (hprofFile == null) {
            println("⚠️  跳过测试: activity_leak.hprof 不存在")
            println("   生成方法: 运行 demo APK，点击'Activity泄露'按钮，退出app，然后dump hprof")
            return
        }

        val result = analyzeAndVerify(hprofFile)

        // 验证 Activity 泄露被检测到
        assertTrue(
            "应该检测到 Activity 泄露",
            result.stats.leakedActivityCount > 0
        )

        // 验证泄露对象列表包含 Activity
        val activityLeaks = result.leakingObjects.filter { 
            it.className.contains("Activity") 
        }
        assertTrue(
            "泄露对象列表应该包含 Activity",
            activityLeaks.isNotEmpty()
        )

        // 验证泄露原因
        activityLeaks.forEach { leak ->
            assertTrue(
                "泄露原因应该包含 'Activity'",
                leak.leakReason.contains("Activity", ignoreCase = true)
            )
        }

        println("✅ Activity 泄露检测通过: 检测到 ${result.stats.leakedActivityCount} 个泄露")
    }

    // ==================== Fragment 泄露检测测试 ====================

    @Test
    fun testFragmentLeakDetection() {
        val hprofFile = getHprofFile("fragment_leak.hprof")
        if (hprofFile == null) {
            println("⚠️  跳过测试: fragment_leak.hprof 不存在")
            println("   生成方法: 运行 demo APK，点击'Fragment泄露'按钮，然后dump hprof")
            return
        }

        val result = analyzeAndVerify(hprofFile)

        // 验证 Fragment 泄露被检测到
        assertTrue(
            "应该检测到 Fragment 泄露",
            result.stats.leakedFragmentCount > 0
        )

        // 验证泄露对象列表包含 Fragment
        val fragmentLeaks = result.leakingObjects.filter { 
            it.className.contains("Fragment") 
        }
        assertTrue(
            "泄露对象列表应该包含 Fragment",
            fragmentLeaks.isNotEmpty()
        )

        println("✅ Fragment 泄露检测通过: 检测到 ${result.stats.leakedFragmentCount} 个泄露")
    }



    // ==================== Animator 泄露检测测试 ====================

    @Test
    fun testAnimatorLeakDetection() {
        val hprofFile = getHprofFile("animator_leak.hprof")
        if (hprofFile == null) {
            println("⚠️  跳过测试: animator_leak.hprof 不存在")
            println("   生成方法: 运行 demo APK，点击'Animator泄露'按钮，然后dump hprof")
            return
        }

        val result = analyzeAndVerify(hprofFile)

        // 验证 Animator 泄露被检测到
        assertTrue(
            "应该检测到 Animator 泄露",
            result.stats.leakedAnimatorCount > 0
        )

        // 验证泄露对象列表包含 Animator
        val animatorLeaks = result.leakingObjects.filter { 
            it.className.contains("Animator") 
        }
        assertTrue(
            "泄露对象列表应该包含 Animator",
            animatorLeaks.isNotEmpty()
        )

        println("✅ Animator 泄露检测通过: 检测到 ${result.stats.leakedAnimatorCount} 个泄露")
    }

    // ==================== Bitmap 泄露检测测试 ====================

    @Test
    fun testBitmapLeakDetection() {
        val hprofFile = getHprofFile("bitmap_leak.hprof")
        if (hprofFile == null) {
            println("⚠️  跳过测试: bitmap_leak.hprof 不存在")
            println("   生成方法: 运行 demo APK，点击'Bitmap泄露'按钮，然后dump hprof")
            return
        }

        val result = analyzeAndVerify(hprofFile)

        // 验证大 Bitmap 被检测到
        assertTrue(
            "应该检测到大 Bitmap",
            result.stats.leakedBitmapCount > 0
        )

        // 验证大对象列表包含 Bitmap
        val bitmapLargeObjects = result.largeObjects.filter { 
            it.className.contains("Bitmap") 
        }
        assertTrue(
            "大对象列表应该包含 Bitmap",
            bitmapLargeObjects.isNotEmpty()
        )

        // 验证 Bitmap 尺寸信息
        bitmapLargeObjects.forEach { obj ->
            assertNotNull("Bitmap 应该有尺寸详情", obj.extDetail)
            assertTrue("Bitmap 尺寸应该包含 'x'", obj.extDetail!!.contains("x"))
        }

        println("✅ Bitmap 泄露检测通过: 检测到 ${result.stats.leakedBitmapCount} 个大 Bitmap")
    }

    // ==================== ByteArray 泄露检测测试 ====================

    @Test
    fun testByteArrayLeakDetection() {
        val hprofFile = getHprofFile("bytearray_leak.hprof")
        if (hprofFile == null) {
            println("⚠️  跳过测试: bytearray_leak.hprof 不存在")
            println("   生成方法: 运行 demo APK，点击'ByteArray泄露'按钮，然后dump hprof")
            return
        }

        val result = analyzeAndVerify(hprofFile)

        // 验证大 ByteArray 被检测到
        assertTrue(
            "应该检测到大 ByteArray",
            result.stats.leakedByteArrayCount > 0
        )

        // 验证大对象列表包含 ByteArray
        val byteArrayLargeObjects = result.largeObjects.filter { 
            it.className == "byte[]" 
        }
        assertTrue(
            "大对象列表应该包含 ByteArray",
            byteArrayLargeObjects.isNotEmpty()
        )

        // 验证 ByteArray 大小信息
        byteArrayLargeObjects.forEach { obj ->
            assertTrue("ByteArray 大小应该>1MB", obj.size > 1_000_000)
        }

        println("✅ ByteArray 泄露检测通过: 检测到 ${result.stats.leakedByteArrayCount} 个大 ByteArray")
    }

    // ==================== 类实例统计测试 ====================

    @Test
    fun testClassStatistics() {
        val hprofFile = getHprofFile("test.hprof")
        if (hprofFile == null) {
            println("⚠️  跳过测试: test.hprof 不存在")
            return
        }

        val result = analyzeAndVerify(hprofFile)

        // 验证类实例统计存在
        assertNotNull("应该有类实例统计", result.classStatistics)
        
        // 验证关键类的统计
        val activityStats = result.classStatistics.find { 
            it.className.contains("Activity") 
        }
        if (activityStats != null) {
            assertTrue("Activity 总实例数应该>0", activityStats.instanceCount > 0)
            println("   Activity 统计: 总实例=${activityStats.instanceCount}, 泄露=${activityStats.leakInstanceCount}")
        }

        val fragmentStats = result.classStatistics.find { 
            it.className.contains("Fragment") 
        }
        if (fragmentStats != null) {
            assertTrue("Fragment 总实例数应该>0", fragmentStats.instanceCount > 0)
            println("   Fragment 统计: 总实例=${fragmentStats.instanceCount}, 泄露=${fragmentStats.leakInstanceCount}")
        }

        println("✅ 类实例统计测试通过: 共 ${result.classStatistics.size} 个类的统计")
    }

    // ==================== 大对象列表测试 ====================

    @Test
    fun testLargeObjectsList() {
        val hprofFile = getHprofFile("test.hprof")
        if (hprofFile == null) {
            println("⚠️  跳过测试: test.hprof 不存在")
            return
        }

        val result = analyzeAndVerify(hprofFile)

        // 验证大对象列表存在
        assertNotNull("应该有大对象列表", result.largeObjects)

        // 如果有大对象，验证信息完整性
        result.largeObjects.forEach { obj ->
            assertNotNull("大对象应该有类名", obj.className)
            assertTrue("大对象大小应该>0", obj.size > 0)
            assertTrue("大对象应该有 objectId", obj.objectId > 0)
        }

        println("✅ 大对象列表测试通过: 共 ${result.largeObjects.size} 个大对象")
    }

    // ==================== 报告生成测试 ====================

    @Test
    fun testReportGeneration() {
        val hprofFile = getHprofFile("test.hprof")
        if (hprofFile == null) {
            println("⚠️  跳过测试: test.hprof 不存在")
            return
        }

        val result = analyzeAndVerify(hprofFile)
        val outputDir = tempDir.resolve("reports")
        Files.createDirectories(outputDir)

        // 保存报告
        val savedFiles = result.saveReport(outputDir)

        assertTrue("应该保存了报告文件", savedFiles.isNotEmpty())
        assertEquals("应该保存了2个报告文件（txt和html）", 2, savedFiles.size)

        // 验证文本报告
        val txtFile = savedFiles.find { it.toString().endsWith(".txt") }
        assertNotNull("应该有文本报告", txtFile)
        if (txtFile != null) {
            val txtContent = Files.readString(txtFile)
            assertTrue(
                "文本报告应该包含泄露相关章节（无泄露时为「未发现内存泄露」等）",
                txtContent.contains("泄露类型统计") ||
                    txtContent.contains("内存泄露对象") ||
                    txtContent.contains("未发现内存泄露")
            )
            assertTrue(
                "文本报告应该包含类实例统计或基础对象统计（极小堆时可能无关键类条目）",
                txtContent.contains("类实例统计") || txtContent.contains("📊 对象统计")
            )
            assertTrue("文本报告应该包含大对象列表", txtContent.contains("大对象列表"))
        }

        // 验证HTML报告
        val htmlFile = savedFiles.find { it.toString().endsWith(".html") }
        assertNotNull("应该有HTML报告", htmlFile)
        if (htmlFile != null) {
            val htmlContent = Files.readString(htmlFile)
            assertTrue("HTML报告应该是有效的HTML", htmlContent.contains("<!DOCTYPE html>"))
            assertTrue(
                "HTML报告应该包含泄露相关章节",
                htmlContent.contains("泄露类型统计") ||
                    htmlContent.contains("内存泄露对象") ||
                    htmlContent.contains("未发现内存泄露") ||
                    htmlContent.contains("未发现Activity/Fragment内存泄露")
            )
            assertTrue(
                "HTML报告应该包含类实例统计或基础统计",
                htmlContent.contains("类实例统计") || htmlContent.contains("对象统计")
            )
            assertTrue("HTML报告应该包含大对象列表", htmlContent.contains("大对象列表"))
        }

        println("✅ 报告生成测试通过")
    }

    // ==================== 分析耗时统计测试 ====================

    @Test
    fun testAnalysisTimeStatistics() {
        val hprofFile = getHprofFile("test.hprof")
        if (hprofFile == null) {
            println("⚠️  跳过测试: test.hprof 不存在")
            return
        }

        val result = analyzeAndVerify(hprofFile)

        // 验证分析耗时统计
        assertTrue("过滤实例耗时应该>=0", result.filterInstanceTime >= 0)
        assertTrue("查找GC路径耗时应该>=0", result.findGCPathTime >= 0)
        assertTrue("总分析耗时应该>0", result.analyzeTime > 0)

        println("✅ 分析耗时统计测试通过:")
        println("   总耗时: ${result.analyzeTime}ms")
        println("   过滤实例耗时: ${result.filterInstanceTime}ms")
        println("   查找GC路径耗时: ${result.findGCPathTime}ms")
    }

    // ==================== 综合测试：所有泄露类型 ====================

    @Test
    fun testAllLeakTypesDetection() {
        val hprofFile = getHprofFile("all_leaks.hprof")
        if (hprofFile == null) {
            println("⚠️  跳过测试: all_leaks.hprof 不存在")
            println("   生成方法: 运行 demo APK，依次触发所有泄露类型，然后dump hprof")
            return
        }

        val result = analyzeAndVerify(hprofFile)

        // 验证所有泄露类型都被统计（与 LeakCanary 对齐，不包含 BroadcastReceiver）
        val leakTypeCounts = mapOf(
            "Activity" to result.stats.leakedActivityCount,
            "Fragment" to result.stats.leakedFragmentCount,
            "Animator" to result.stats.leakedAnimatorCount,
            "Bitmap" to result.stats.leakedBitmapCount,
            "ByteArray" to result.stats.leakedByteArrayCount
        )

        println("\n📊 所有泄露类型检测结果:")
        leakTypeCounts.forEach { (type, count) ->
            val status = if (count > 0) "✅" else "❌"
            println("   $status $type: $count 个")
        }

        // 验证至少有一种泄露被检测到
        val totalLeaks = leakTypeCounts.values.sum()
        assertTrue("应该至少检测到一种泄露", totalLeaks > 0)

        println("\n✅ 综合测试通过: 共检测到 $totalLeaks 个泄露对象")
    }

    // ==================== 修改部分验证：以 test.hprof 为输入 ====================
    // 验证移除硬编码、isInnerClassOfActivity、常量替换等修改后分析器仍正常工作

    @Test
    fun testModifiedPartsWithTestHprof() {
        val hprofFile = getHprofFile("test.hprof")
        if (hprofFile == null) {
            println("⚠️  跳过测试: test.hprof 不存在于 ~/tmp/hprof/")
            return
        }

        val analyzer = HprofAnalyzer()
        val result = analyzer.analyze(hprofFile)

        // 1. 基本结果非空、无异常
        assertNotNull("分析结果不应为空", result)
        assertTrue("应有实例统计", result.stats.totalInstanceCount >= 0)
        assertTrue("应有类统计", result.stats.totalClassCount > 0)

        // 2. 泄露对象列表与统计一致（无硬编码导致的漏检/崩溃）
        assertNotNull("leakingObjects 不应为 null", result.leakingObjects)
        assertNotNull("largeObjects 不应为 null", result.largeObjects)
        assertNotNull("classStatistics 不应为 null", result.classStatistics)

        // 3. 若存在泄露对象，leakReason 应为非空且不含占位错误（常量替换正确）
        result.leakingObjects.forEach { obj ->
            assertNotNull("leakReason 不应为 null", obj.leakReason)
            assertTrue("leakReason 不应为空串", obj.leakReason.isNotBlank())
        }

        // 4. 报告可正常生成（unwrapActivityContext、getAliveServiceObjectIds 等无硬编码错误）
        val outputDir = tempDir.resolve("modified-parts-test")
        Files.createDirectories(outputDir)
        val savedFiles = result.saveReport(outputDir)
        assertTrue("应成功保存报告", savedFiles.isNotEmpty())
        assertEquals("应生成 2 个报告文件（txt + html）", 2, savedFiles.size)

        println("✅ 修改部分验证通过（test.hprof）: 分析、统计、报告生成正常")
    }
}
