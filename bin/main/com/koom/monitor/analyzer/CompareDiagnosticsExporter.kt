package com.koom.monitor.analyzer

import com.google.gson.GsonBuilder
import kshark.HprofHeapGraph.Companion.openHeapGraph
import kshark.HprofRecordTag
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Exports global diagnostics metrics as JSON for cross-validation with hprof_parser.py.
 */
object CompareDiagnosticsExporter {

    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun export(hprofFile: File, outputJson: Path, topIdentitySamples: Int = 20) {
        hprofFile.openHeapGraph(
            proguardMapping = null,
            indexedGcRootTypes = HprofRecordTag.rootTags
        ).use { graph ->
            val raw = GlobalDiagnosticsAnalyzer(topN = topIdentitySamples).analyzeRaw(graph)
            val payload = buildPayload(hprofFile, raw, topIdentitySamples)
            Files.createDirectories(outputJson.parent)
            Files.writeString(outputJson, gson.toJson(payload))
        }
    }

    private fun buildPayload(
        hprofFile: File,
        raw: RawGlobalDiagnostics,
        topIdentitySamples: Int
    ): CompareDiagnosticsPayload {
        val dupWithWaste = raw.duplicateStrings.filter { it.count > 1 }
        return CompareDiagnosticsPayload(
            source = "mem-analyze",
            hprofPath = hprofFile.absolutePath,
            gcRootTotal = raw.gcRootStats.totalCount,
            gcRootByType = raw.gcRootStats.typeCounts,
            accumulationPointCount = raw.accumulationPoints.size,
            accumulationPointsTop = raw.accumulationPoints
                .sortedByDescending { it.estimatedRetainedBytes }
                .take(topIdentitySamples)
                .map { it.toCompareRow() },
            staticHoldingCount = raw.staticHoldings.size,
            staticHoldingsTop = raw.staticHoldings
                .sortedByDescending { it.retainedSizeBytes }
                .take(topIdentitySamples)
                .map { it.toCompareRow() },
            largeArrayCount = raw.largeArrays.size,
            largeArraysTop = raw.largeArrays
                .sortedByDescending { it.sizeBytes }
                .take(topIdentitySamples)
                .map { it.toCompareRow() },
            duplicateStringGroupCount = dupWithWaste.size,
            collectionRiskCount = raw.collectionInfos.count { it.riskLevel != RiskLevel.NONE },
            lruCacheListedCount = raw.lruCacheInfos.size
        )
    }

    private fun AccumulationPointInfo.toCompareRow() = CompareAccumulationRow(
        className = className,
        objectId = objectId,
        retainedBytes = estimatedRetainedBytes,
        shallowBytes = shallowSizeBytes,
        retainedToShallowRatio = retainedToShallowRatio,
        directDominatedCount = dominatedObjectCount
    )

    private fun StaticHoldingInfo.toCompareRow() = CompareStaticHoldingRow(
        category = category.name,
        declaringClass = className,
        fieldName = fieldName,
        targetClassName = targetClassName,
        targetObjectId = targetObjectId,
        retainedBytes = retainedSizeBytes,
        shallowBytes = shallowSizeBytes
    )

    private fun LargeArrayInfo.toCompareRow() = CompareLargeArrayRow(
        objectId = objectId,
        arrayType = arrayType,
        sizeBytes = sizeBytes,
        primaryHolder = holderChain.firstOrNull()?.let { "${it.className}.${it.referenceName}" }
    )

    data class CompareDiagnosticsPayload(
        val source: String,
        val hprofPath: String,
        val gcRootTotal: Int,
        val gcRootByType: Map<String, Int>,
        val accumulationPointCount: Int,
        val accumulationPointsTop: List<CompareAccumulationRow>,
        val staticHoldingCount: Int,
        val staticHoldingsTop: List<CompareStaticHoldingRow>,
        val largeArrayCount: Int,
        val largeArraysTop: List<CompareLargeArrayRow>,
        val duplicateStringGroupCount: Int,
        val collectionRiskCount: Int,
        val lruCacheListedCount: Int
    )

    data class CompareAccumulationRow(
        val className: String,
        val objectId: Long,
        val retainedBytes: Long,
        val shallowBytes: Long,
        val retainedToShallowRatio: Double,
        val directDominatedCount: Int
    )

    data class CompareStaticHoldingRow(
        val category: String,
        val declaringClass: String,
        val fieldName: String,
        val targetClassName: String,
        val targetObjectId: Long,
        val retainedBytes: Long,
        val shallowBytes: Long
    )

    data class CompareLargeArrayRow(
        val objectId: Long,
        val arrayType: String,
        val sizeBytes: Long,
        val primaryHolder: String?
    )
}
