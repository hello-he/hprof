package com.koom.monitor

import com.koom.monitor.analyzer.DuplicateStringInfo
import com.koom.monitor.analyzer.GcRootStats
import com.koom.monitor.analyzer.GlobalDiagnosticsAnalyzer
import com.koom.monitor.analyzer.GlobalDiagnosticsResult
import com.koom.monitor.analyzer.HprofAnalyzer
import com.koom.monitor.analyzer.LargeArrayInfo
import com.koom.monitor.analyzer.PackageClassDistribution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class GlobalDiagnosticsAnalyzerTest {

    @Test
    fun emptyResultHasNoFindingsOrSuggestions() {
        val result = GlobalDiagnosticsResult.empty()

        assertFalse(result.hasFindings)
        assertTrue(result.optimizationSuggestions.isEmpty())
    }

    @Test
    fun buildResultRanksDiagnosticsByImpact() {
        val result = GlobalDiagnosticsAnalyzer().buildResult(
            duplicateStrings = listOf(
                DuplicateStringInfo("small", 2, 16),
                DuplicateStringInfo("large", 5, 4096)
            ),
            largeArrays = listOf(
                LargeArrayInfo(1L, "byte[]", 512 * 1024, "网络缓存"),
                LargeArrayInfo(2L, "byte[]", 2 * 1024 * 1024, "图片数据")
            ),
            gcRootStats = GcRootStats(
                totalCount = 12,
                typeCounts = mapOf("THREAD_OBJ" to 8, "JNI_GLOBAL" to 4)
            ),
            packageClassDistribution = PackageClassDistribution(
                topPackages = listOf(
                    PackageClassDistribution.Entry("com.demo.feature", 100, 4 * 1024 * 1024, "app"),
                    PackageClassDistribution.Entry("android.view", 200, 2 * 1024 * 1024, "system")
                ),
                topClasses = listOf(
                    PackageClassDistribution.Entry("com.demo.feature.ImageCache", 40, 3 * 1024 * 1024, "app")
                )
            )
        )

        assertTrue(result.hasFindings)
        assertEquals("large", result.duplicateStrings.first().content)
        assertEquals(2L, result.largeArrays.first().objectId)
        assertEquals("THREAD_OBJ", result.gcRootStats.topTypes.first().type)
        assertEquals("com.demo.feature", result.packageClassDistribution.topPackages.first().name)
        assertTrue(result.optimizationSuggestions.any { it.title.contains("大数组") })
        assertTrue(result.optimizationSuggestions.any { it.title.contains("GC Root") })
        assertTrue(result.optimizationSuggestions.any { it.title.contains("包/类") })
    }

    @Test
    fun savedReportIncludesGlobalDiagnosticsSections() {
        val result = HprofAnalyzer.AnalysisResult(
            file = File("sample.hprof"),
            fileSize = 1024,
            analyzeTime = 1,
            stats = HprofAnalyzer.Statistics(totalClassCount = 1, totalInstanceCount = 1),
            globalDiagnostics = GlobalDiagnosticsAnalyzer().buildResult(
                largeArrays = listOf(LargeArrayInfo(7L, "byte[]", 2 * 1024 * 1024, "图片数据")),
                gcRootStats = GcRootStats(totalCount = 1, typeCounts = mapOf("THREAD_OBJ" to 1))
            )
        )
        val outputDir = Files.createTempDirectory("global-diagnostics-report")

        try {
            val files = result.saveReport(outputDir)
            val html = Files.readString(files.first { it.fileName.toString() == "hprof_analysis.html" })
            val text = Files.readString(files.first { it.fileName.toString() == "hprof_analysis.txt" })

            assertTrue(html.contains("全局诊断"))
            assertTrue(html.contains("优化建议"))
            assertTrue(html.contains("GC Root 分布"))
            assertTrue(text.contains("全局诊断"))
            assertTrue(text.contains("优化建议"))
        } finally {
            Files.walk(outputDir)
                .sorted(Comparator.reverseOrder())
                .forEach { Files.deleteIfExists(it) }
        }
    }
}
