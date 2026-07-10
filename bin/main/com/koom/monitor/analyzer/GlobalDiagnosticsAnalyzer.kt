package com.koom.monitor.analyzer

import kshark.GcRoot
import kshark.HeapGraph
import kshark.HeapObject
import kshark.HeapObject.HeapClass
import kshark.HeapObject.HeapInstance
import kshark.HeapObject.HeapObjectArray
import kshark.HeapObject.HeapPrimitiveArray
import kshark.HprofRecord
import kshark.ValueHolder
import kshark.internal.ObjectDominators
import java.util.Locale

/**
 * Heap-wide diagnostics that complement confirmed leak traces.
 *
 * These findings are heuristic risk signals. They should not be mixed with objects confirmed as
 * leaking by Shark GC-root path analysis.
 */
class GlobalDiagnosticsAnalyzer(
    private val topN: Int = DEFAULT_TOP_N,
    private val largeArrayThresholdBytes: Long = DEFAULT_LARGE_ARRAY_THRESHOLD_BYTES,
    private val highRiskArrayThresholdBytes: Long = DEFAULT_HIGH_RISK_ARRAY_THRESHOLD_BYTES
) {

    /**
     * Full diagnostic lists before [buildResult] top-N truncation (for compare / validation export).
     */
    fun analyzeRaw(graph: HeapGraph): RawGlobalDiagnostics {
        val dominatorMap = ObjectDominators().buildFullDominatorTreeMap(graph)
        val duplicateStrings = analyzeDuplicateStrings(graph)
        val largeArraysRaw = analyzeLargeArrays(graph)
        val gcRootStats = analyzeGcRoots(graph)
        val packageClassDistribution = analyzePackageClassDistribution(graph)
        val collectionInfos = analyzeCollections(graph)
        val lruCacheInfos = analyzeLruCaches(graph)
        val staticHoldings = analyzeStaticAndSingletonHoldings(graph, dominatorMap)
        val holderChains = buildHolderChains(graph, largeArraysRaw.map { it.objectId }.toSet())
        val largeArrays = largeArraysRaw.filterNot { info ->
            isExcludedSystemLargePrimitiveArray(holderChains[info.objectId].orEmpty())
        }
        val accumulationPoints = buildDominatorAccumulationPoints(graph, dominatorMap)
        return RawGlobalDiagnostics(
            duplicateStrings = duplicateStrings,
            largeArrays = largeArrays.map { info ->
                info.copy(holderChain = holderChains[info.objectId].orEmpty())
            },
            collectionInfos = collectionInfos,
            lruCacheInfos = lruCacheInfos,
            staticHoldings = staticHoldings,
            gcRootStats = gcRootStats,
            accumulationPoints = accumulationPoints,
            packageClassDistribution = packageClassDistribution
        )
    }

    fun analyze(graph: HeapGraph): GlobalDiagnosticsResult {
        val raw = analyzeRaw(graph)
        return buildResult(
            duplicateStrings = raw.duplicateStrings,
            largeArrays = raw.largeArrays,
            collectionInfos = raw.collectionInfos,
            lruCacheInfos = raw.lruCacheInfos,
            staticHoldings = raw.staticHoldings,
            gcRootStats = raw.gcRootStats,
            accumulationPoints = raw.accumulationPoints,
            packageClassDistribution = raw.packageClassDistribution
        )
    }

    fun buildResult(
        duplicateStrings: List<DuplicateStringInfo> = emptyList(),
        largeArrays: List<LargeArrayInfo> = emptyList(),
        collectionInfos: List<CollectionInfo> = emptyList(),
        lruCacheInfos: List<LruCacheInfo> = emptyList(),
        staticHoldings: List<StaticHoldingInfo> = emptyList(),
        gcRootStats: GcRootStats = GcRootStats(),
        accumulationPoints: List<AccumulationPointInfo> = emptyList(),
        packageClassDistribution: PackageClassDistribution = PackageClassDistribution()
    ): GlobalDiagnosticsResult {
        val sortedDuplicateStrings = duplicateStrings
            .filter { it.count > 1 }
            .sortedByDescending { it.estimatedWasteBytes }
            .take(topN)
        val sortedLargeArrays = largeArrays
            .sortedByDescending { it.sizeBytes }
            .take(topN)
        val sortedCollections = collectionInfos
            .sortedWith(compareByDescending<CollectionInfo> { it.riskLevel.rank }.thenByDescending { it.wastedCapacity })
            .take(topN)
        val sortedLruCaches = lruCacheInfos
            .sortedWith(compareByDescending<LruCacheInfo> { it.riskLevel.rank }.thenBy { it.hitRate ?: 1.0 })
            .take(topN)
        val sortedStaticHoldings = staticHoldings
            .sortedWith(
                compareByDescending<StaticHoldingInfo> { it.riskLevel.rank }
                    .thenByDescending { it.retainedSizeBytes }
            )
            .take(topN)
        val sortedAccumulationPoints = accumulationPoints
            .sortedWith(compareByDescending<AccumulationPointInfo> { it.riskLevel.rank }.thenByDescending { it.estimatedRetainedBytes })
            .take(topN)
        val normalizedRootStats = gcRootStats.sorted()
        val normalizedDistribution = packageClassDistribution.sorted(topN)

        val resultWithoutSuggestions = GlobalDiagnosticsResult(
            duplicateStrings = sortedDuplicateStrings,
            largeArrays = sortedLargeArrays,
            collectionInfos = sortedCollections,
            lruCacheInfos = sortedLruCaches,
            staticHoldings = sortedStaticHoldings,
            gcRootStats = normalizedRootStats,
            accumulationPoints = sortedAccumulationPoints,
            packageClassDistribution = normalizedDistribution,
            optimizationSuggestions = emptyList()
        )

        return resultWithoutSuggestions.copy(
            optimizationSuggestions = buildOptimizationSuggestions(resultWithoutSuggestions)
        )
    }

    fun buildOptimizationSuggestions(result: GlobalDiagnosticsResult): List<OptimizationSuggestion> {
        val suggestions = mutableListOf<OptimizationSuggestion>()

        if (result.largeArrays.any { it.sizeBytes >= highRiskArrayThresholdBytes }) {
            suggestions += OptimizationSuggestion(
                priority = SuggestionPriority.HIGH,
                title = "大数组占用较高",
                detail = "发现 ${result.largeArrays.size} 个大 primitive array，最大 ${formatSize(result.largeArrays.first().sizeBytes)}。",
                suggestion = "结合持有者链检查图片、网络缓存、序列化 Buffer 是否可分片、复用或及时释放。"
            )
        } else if (result.largeArrays.isNotEmpty()) {
            suggestions += OptimizationSuggestion(
                priority = SuggestionPriority.MEDIUM,
                title = "存在大数组",
                detail = "发现 ${result.largeArrays.size} 个超过 ${formatSize(largeArrayThresholdBytes)} 的 primitive array。",
                suggestion = "关注这些数组是否来自缓存、图片或一次性临时数据。"
            )
        }

        if (result.duplicateStrings.isNotEmpty()) {
            val top = result.duplicateStrings.first()
            suggestions += OptimizationSuggestion(
                priority = SuggestionPriority.MEDIUM,
                title = "重复 String 较多",
                detail = "\"${top.preview}\" 重复 ${top.count} 次，估算浪费 ${formatSize(top.estimatedWasteBytes)}。",
                suggestion = "检查 JSON 解析、日志标签、重复 key 或可枚举字符串，必要时复用常量或优化缓存。"
            )
        }

        if (result.collectionInfos.any { it.riskLevel >= RiskLevel.MEDIUM }) {
            suggestions += OptimizationSuggestion(
                priority = SuggestionPriority.MEDIUM,
                title = "集合容量可能浪费",
                detail = "发现 ${result.collectionInfos.size} 个空集合、大集合或容量明显大于元素数的集合。",
                suggestion = "考虑延迟初始化、使用 emptyList/emptyMap，或为大集合设置合理初始容量和清理策略。"
            )
        }

        if (result.lruCacheInfos.any { it.riskLevel >= RiskLevel.MEDIUM }) {
            suggestions += OptimizationSuggestion(
                priority = SuggestionPriority.MEDIUM,
                title = "LruCache 使用效率异常",
                detail = "发现 ${result.lruCacheInfos.size} 个命中率低或利用率异常的 LruCache。",
                suggestion = "检查缓存 key、maxSize、预加载策略，以及是否存在长期不用的缓存。"
            )
        }

        if (result.staticHoldings.isNotEmpty()) {
            suggestions += OptimizationSuggestion(
                priority = SuggestionPriority.HIGH,
                title = "静态/单例持有风险（retained 阈值）",
                detail = "发现 ${result.staticHoldings.size} 条可疑持有（静态 retained>100KB 或单例模式 retained>500KB，已跳过 \$class\$ 与系统/库声明类）。",
                suggestion = "重点检查 singleton/static cache 是否持有 Activity、Fragment、View、Bitmap 或大对象子图。"
            )
        }

        if (result.gcRootStats.topTypes.isNotEmpty()) {
            val rootType = result.gcRootStats.topTypes.first().type
            suggestions += OptimizationSuggestion(
                priority = SuggestionPriority.LOW,
                title = "GC Root 分布提示",
                detail = "最多的 GC Root 类型是 $rootType，共 ${result.gcRootStats.topTypes.first().count} 个。",
                suggestion = gcRootHint(rootType)
            )
        }

        if (result.accumulationPoints.isNotEmpty()) {
            suggestions += OptimizationSuggestion(
                priority = SuggestionPriority.MEDIUM,
                title = "存在疑似内存积累点（支配树）",
                detail = "发现 ${result.accumulationPoints.size} 个节点满足 retained>1MB 且 retained/shallow ratio>10。",
                suggestion = "优先排查这些对象是否作为缓存根或长期存活集合，是否具备生命周期清理与容量上限。"
            )
        }

        if (result.packageClassDistribution.topPackages.isNotEmpty()) {
            suggestions += OptimizationSuggestion(
                priority = SuggestionPriority.LOW,
                title = "包/类分布摘要",
                detail = "实例最多的包是 ${result.packageClassDistribution.topPackages.first().name}。",
                suggestion = "结合业务操作路径观察 Top 包/类是否符合预期，异常增长的 SDK 或业务包可作为下一步排查入口。"
            )
        }

        return suggestions.sortedByDescending { it.priority.rank }
    }

    private fun analyzeDuplicateStrings(graph: HeapGraph): List<DuplicateStringInfo> {
        val strings = mutableMapOf<String, MutableList<Long>>()
        graph.instances.forEach { instance ->
            if (instance.instanceClassName == "java.lang.String") {
                val content = runCatching { instance.readAsJavaString() }.getOrNull()
                if (!content.isNullOrEmpty() && content.length <= MAX_STRING_CONTENT_LENGTH) {
                    strings.getOrPut(content) { mutableListOf() } += instance.objectId
                }
            }
        }
        return strings.mapNotNull { (content, objectIds) ->
            if (objectIds.size <= 1) {
                null
            } else {
                DuplicateStringInfo(
                    content = content,
                    count = objectIds.size,
                    estimatedWasteBytes = estimateStringWaste(content, objectIds.size),
                    objectIds = objectIds
                )
            }
        }
    }

    private fun analyzeLargeArrays(graph: HeapGraph): List<LargeArrayInfo> {
        return graph.primitiveArrays.mapNotNull { array ->
            val sizeBytes = runCatching { array.readByteSize().toLong() }.getOrElse { array.recordSize.toLong() }
            if (sizeBytes < largeArrayThresholdBytes) {
                null
            } else {
                LargeArrayInfo(
                    objectId = array.objectId,
                    arrayType = array.arrayClassName,
                    sizeBytes = sizeBytes,
                    inferredUsage = inferArrayUsage(array)
                )
            }
        }.toList()
    }

    private fun analyzeGcRoots(graph: HeapGraph): GcRootStats {
        val counts = graph.gcRoots.groupingBy { gcRootTypeName(it) }.eachCount()
        return GcRootStats(
            totalCount = graph.gcRoots.size,
            typeCounts = counts
        )
    }

    private fun analyzePackageClassDistribution(graph: HeapGraph): PackageClassDistribution {
        val packages = mutableMapOf<String, MutableDistributionCounter>()
        val classes = mutableMapOf<String, MutableDistributionCounter>()

        graph.instances.forEach { instance ->
            val className = instance.instanceClassName
            val shallowSize = runCatching { instance.byteSize.toLong() }.getOrDefault(0L)
            classes.getOrPut(className) { MutableDistributionCounter(classifyName(className)) }
                .add(shallowSize)
            val packageName = packageNameOf(className)
            packages.getOrPut(packageName) { MutableDistributionCounter(classifyName(packageName)) }
                .add(shallowSize)
        }

        return PackageClassDistribution(
            topPackages = packages.map { (name, counter) ->
                PackageClassDistribution.Entry(name, counter.instanceCount, counter.totalShallowBytes, counter.category)
            },
            topClasses = classes.map { (name, counter) ->
                PackageClassDistribution.Entry(name, counter.instanceCount, counter.totalShallowBytes, counter.category)
            }
        )
    }

    private fun analyzeCollections(graph: HeapGraph): List<CollectionInfo> {
        val results = mutableListOf<CollectionInfo>()
        graph.instances.forEach { instance ->
            val className = instance.instanceClassName
            if (!isCollectionClass(className)) return@forEach

            val size = firstIntField(instance, "size", "mSize", "_size") ?: return@forEach
            val capacity = collectionCapacity(instance, className)
            val wastedCapacity = if (capacity != null) (capacity - size).coerceAtLeast(0) else 0
            val riskLevel = collectionRiskLevel(className, size, capacity, wastedCapacity)

            if (riskLevel != RiskLevel.NONE) {
                results += CollectionInfo(
                    objectId = instance.objectId,
                    className = className,
                    size = size,
                    capacity = capacity,
                    wastedCapacity = wastedCapacity,
                    riskLevel = riskLevel
                )
            }
        }
        return results
    }

    /**
     * 对齐 hprof_parser 集合诊断：空集合、元素数>1000、以及大容量且利用率<0.5 且浪费槽位>100
     * （HashMap/LinkedHashMap 容量阈值 500000，其它 10000）。
     */
    private fun collectionRiskLevel(
        className: String,
        size: Int,
        capacity: Int?,
        wastedCapacity: Int
    ): RiskLevel {
        if (size == 0 && (capacity ?: 0) > 16) return RiskLevel.MEDIUM
        if (size >= 1_000) return RiskLevel.MEDIUM
        val cap = capacity ?: return RiskLevel.NONE
        if (cap <= 0) return RiskLevel.NONE
        val threshold = if (className.contains("HashMap")) 500_000 else 10_000
        if (cap <= threshold) return RiskLevel.NONE
        val utilization = size.toDouble() / cap.toDouble()
        if (utilization < 0.5 && wastedCapacity > 100) return RiskLevel.MEDIUM
        return RiskLevel.NONE
    }

    private fun analyzeLruCaches(graph: HeapGraph): List<LruCacheInfo> {
        val results = mutableListOf<LruCacheInfo>()
        graph.instances.forEach { instance ->
            val className = instance.instanceClassName
            if (!isLruCacheClassName(className)) return@forEach

            val size = firstIntField(instance, "size") ?: 0
            val maxSize = firstIntField(instance, "maxSize") ?: 0
            if (maxSize <= 0 && size <= 0) return@forEach

            val hitCount = firstIntField(instance, "hitCount")
            val missCount = firstIntField(instance, "missCount")
            val requestCount = (hitCount ?: 0) + (missCount ?: 0)
            val hitRate = if (requestCount > 0 && hitCount != null) hitCount.toDouble() / requestCount else null
            val utilization = if (maxSize > 0) size.toDouble() / maxSize else null
            // 空缓存且无任何 hit/miss 统计时，0% 利用率不代表「命中率低」，不标风险；仅在有元素时看利用率
            val riskLevel = when {
                hitRate != null && requestCount >= 10 && hitRate < 0.5 -> RiskLevel.MEDIUM
                utilization != null && maxSize > 0 && size > 0 && utilization < 0.3 -> RiskLevel.LOW
                utilization != null && maxSize > 0 && size > 0 && utilization > 0.95 -> RiskLevel.LOW
                else -> RiskLevel.NONE
            }

            if (size == 0 && hitRate == null) return@forEach

            results += LruCacheInfo(
                objectId = instance.objectId,
                className = className,
                size = size,
                maxSize = maxSize,
                hitCount = hitCount,
                missCount = missCount,
                hitRate = hitRate,
                utilization = utilization,
                riskLevel = riskLevel
            )
        }
        return results
    }

    /**
     * 对齐 hprof_parser.analyze_suspicious_holdings：非系统声明类、跳过字段名含 `$class$`、
     * 静态 retained>100KB；单例命名字段 retained>500KB；Activity/Fragment 标记为 LEAKED_COMPONENT。
     */
    private fun analyzeStaticAndSingletonHoldings(
        graph: HeapGraph,
        dominatorMap: Map<Long, ObjectDominators.DominatorNode>
    ): List<StaticHoldingInfo> {
        val results = mutableListOf<StaticHoldingInfo>()
        val highRetainedCutoff = highRiskArrayThresholdBytes.toInt()

        fun retainedBytes(objectId: Long): Int = dominatorMap[objectId]?.retainedSize ?: 0

        fun shallowBytes(objectId: Long): Long = (dominatorMap[objectId]?.shallowSize ?: 0).toLong()

        graph.classes.forEach classLoop@{ heapClass ->
            val declaringClass = heapClass.name
            if (isDeclaringClassSystemOrLibrary(declaringClass)) return@classLoop
            runCatching {
                heapClass.readStaticFields().forEach fieldLoop@{ field ->
                    val objectId = field.value.asNonNullObjectId ?: return@fieldLoop
                    val fieldName = field.name
                    if (fieldName.contains("\$class\$")) return@fieldLoop
                    val target = graph.findObjectByIdOrNull(objectId) ?: return@fieldLoop
                    val targetClassName = objectClassName(target)
                    val retained = retainedBytes(objectId)
                    val shallow = shallowBytes(objectId)

                    if (retained > STATIC_FIELD_RETAINED_MIN_BYTES) {
                        results += StaticHoldingInfo(
                            className = declaringClass,
                            fieldName = fieldName,
                            targetObjectId = objectId,
                            targetClassName = targetClassName,
                            shallowSizeBytes = shallow,
                            retainedSizeBytes = retained.toLong(),
                            category = StaticHoldingCategory.STATIC_FIELD,
                            riskLevel = if (retained >= highRetainedCutoff) RiskLevel.HIGH else RiskLevel.MEDIUM
                        )
                    }

                    val isSingletonField = SINGLETON_FIELD_MARKERS.any { marker -> fieldName.contains(marker) }
                    if (isSingletonField && retained > SINGLETON_RETAINED_MIN_BYTES) {
                        results += StaticHoldingInfo(
                            className = declaringClass,
                            fieldName = fieldName,
                            targetObjectId = objectId,
                            targetClassName = targetClassName,
                            shallowSizeBytes = shallow,
                            retainedSizeBytes = retained.toLong(),
                            category = StaticHoldingCategory.SINGLETON,
                            riskLevel = if (retained >= highRetainedCutoff) RiskLevel.HIGH else RiskLevel.MEDIUM
                        )
                    }
                }
            }
        }

        return results.map { info ->
            val leaked = info.targetClassName.contains("Activity") || info.targetClassName.contains("Fragment")
            if (leaked) {
                info.copy(category = StaticHoldingCategory.LEAKED_COMPONENT, riskLevel = RiskLevel.HIGH)
            } else {
                info
            }
        }
    }

    /** 对齐 hprof_parser.detect_accumulation_points：retained>1MB 且 retained/shallow>10 */
    private fun buildDominatorAccumulationPoints(
        graph: HeapGraph,
        dominatorMap: Map<Long, ObjectDominators.DominatorNode>
    ): List<AccumulationPointInfo> {
        val points = mutableListOf<AccumulationPointInfo>()
        val minRetained = ACCUMULATION_MIN_RETAINED_BYTES
        for ((objectId, node) in dominatorMap) {
            if (objectId == ValueHolder.NULL_REFERENCE) continue
            val shallow = node.shallowSize
            if (shallow == 0) continue
            val retained = node.retainedSize.toLong()
            if (retained <= minRetained) continue
            val ratio = retained.toDouble() / shallow.toDouble()
            if (ratio <= ACCUMULATION_MIN_RETAINED_TO_SHALLOW_RATIO) continue
            val heapObject = graph.findObjectByIdOrNull(objectId) ?: continue
            val className = objectClassName(heapObject)
            val dominated = node.dominatedObjectIds.size
            val reason =
                "Dominator tree: retained ${formatSize(retained)}, shallow ${formatSize(shallow.toLong())}, retained/shallow ratio ${"%.1f".format(Locale.US, ratio)}, directDominatedCount $dominated"
            points += AccumulationPointInfo(
                objectId = objectId,
                className = className,
                reason = reason,
                estimatedRetainedBytes = retained,
                shallowSizeBytes = shallow.toLong(),
                retainedToShallowRatio = ratio,
                dominatedObjectCount = dominated,
                riskLevel = RiskLevel.HIGH
            )
        }
        return points
    }

    private fun buildHolderChains(
        graph: HeapGraph,
        targetIds: Set<Long>
    ): Map<Long, List<HolderChainNode>> {
        if (targetIds.isEmpty()) return emptyMap()

        val incoming = mutableMapOf<Long, MutableList<HolderChainNode>>()
        fun addIncoming(targetId: Long, holder: HolderChainNode) {
            if (targetId in targetIds) {
                incoming.getOrPut(targetId) { mutableListOf() } += holder
            }
        }

        graph.instances.forEach { instance ->
            runCatching {
                instance.readFields().forEach fieldLoop@{ field ->
                    val objectId = field.value.asNonNullObjectId ?: return@fieldLoop
                    addIncoming(
                        objectId,
                        HolderChainNode(
                            objectId = instance.objectId,
                            className = instance.instanceClassName,
                            referenceName = field.name,
                            referenceType = "field"
                        )
                    )
                }
            }
        }

        graph.classes.forEach { heapClass ->
            runCatching {
                heapClass.readStaticFields().forEach fieldLoop@{ field ->
                    val objectId = field.value.asNonNullObjectId ?: return@fieldLoop
                    addIncoming(
                        objectId,
                        HolderChainNode(
                            objectId = heapClass.objectId,
                            className = heapClass.name,
                            referenceName = field.name,
                            referenceType = "static"
                        )
                    )
                }
            }
        }

        graph.objectArrays.forEach { array ->
            runCatching {
                array.readRecord().elementIds.forEachIndexed { index, objectId ->
                    addIncoming(
                        objectId,
                        HolderChainNode(
                            objectId = array.objectId,
                            className = array.arrayClassName,
                            referenceName = "[$index]",
                            referenceType = "array"
                        )
                    )
                }
            }
        }

        return incoming.mapValues { (_, holders) ->
            holders.sortedWith(
                compareByDescending<HolderChainNode> { isInterestingHolder(it.className) }
                    .thenBy { isSystemClass(it.className) }
            ).take(MAX_HOLDER_CHAIN_LENGTH)
        }
    }

    /**
     * 框架 ICU 内部数据表持有的 primitive 数组，属系统正常占用，不参与「大数组」诊断统计。
     * 主持有者取 [buildHolderChains] 排序后的第一个（与报告展示一致）。
     */
    private fun isExcludedSystemLargePrimitiveArray(holderChain: List<HolderChainNode>): Boolean {
        val primary = holderChain.firstOrNull() ?: return false
        if (primary.referenceType != "field" && primary.referenceType != "static") return false
        val c = primary.className
        val r = primary.referenceName
        return (c.startsWith("android.icu.impl.Trie2_") && r == "data32") ||
            (c == "android.icu.impl.UCharacterName" && r == "m_groupstring_")
    }

    private fun estimateStringWaste(content: String, count: Int): Long {
        val shallowStringOverhead = 24L
        val backingArrayBytes = content.length * 2L
        return (count - 1).coerceAtLeast(0) * (shallowStringOverhead + backingArrayBytes)
    }

    private fun inferArrayUsage(array: HeapPrimitiveArray): String {
        return when (array.arrayClassName) {
            "byte[]" -> "可能是图片、网络响应、序列化 Buffer 或 String 存储"
            "char[]" -> "可能是 String 或文本缓冲"
            "int[]" -> "可能是 Bitmap 像素、索引表或集合内部数组"
            "long[]" -> "可能是 native 指针表、时间戳或 ID 缓存"
            else -> "primitive array"
        }
    }

    private fun collectionCapacity(instance: HeapInstance, className: String): Int? {
        return when {
            className == "java.util.ArrayList" -> firstObjectArraySize(instance, "elementData", "array")
            className == "java.util.HashMap" || className == "java.util.LinkedHashMap" ->
                firstObjectArraySize(instance, "table")
            className == "java.util.HashSet" -> hashSetBackingTableCapacity(instance)
            className == "java.util.concurrent.ConcurrentHashMap" ->
                firstObjectArraySize(instance, "table")
            className == "java.util.Vector" || className == "java.util.Stack" ->
                firstObjectArraySize(instance, "elementData")
            className == "android.util.ArrayMap" || className == "androidx.collection.ArrayMap" ->
                firstPrimitiveArrayLength(instance, "mHashes")
            className == "android.util.SparseArray" || className == "androidx.collection.SparseArrayCompat" ->
                firstPrimitiveArrayLength(instance, "mKeys")
            className == "android.util.LongSparseArray" || className == "androidx.collection.LongSparseArray" ->
                firstPrimitiveArrayLength(instance, "mKeys")
            else -> null
        }
    }

    /** OpenJDK HashSet 委托给内部 HashMap，table 在 map 字段上 */
    private fun hashSetBackingTableCapacity(instance: HeapInstance): Int? {
        return runCatching {
            val map = instance.readFields()
                .firstOrNull { field -> field.name == "map" }
                ?.value
                ?.asObject
                ?.asInstance
                ?: return@runCatching null
            firstObjectArraySize(map, "table")
        }.getOrNull()
    }

    private fun firstIntField(instance: HeapInstance, vararg names: String): Int? {
        return runCatching {
            instance.readFields()
                .firstOrNull { field -> field.name in names }
                ?.value
                ?.asInt
        }.getOrNull()
    }

    private fun firstObjectArraySize(instance: HeapInstance, vararg names: String): Int? {
        return runCatching {
            instance.readFields()
                .firstOrNull { field -> field.name in names }
                ?.value
                ?.asObject
                ?.asObjectArray
                ?.readRecord()
                ?.elementIds
                ?.size
        }.getOrNull()
    }

    private fun firstPrimitiveArrayLength(instance: HeapInstance, vararg names: String): Int? {
        return runCatching {
            val array = instance.readFields()
                .firstOrNull { field -> field.name in names }
                ?.value
                ?.asObject
                ?.asPrimitiveArray
                ?: return@runCatching null
            val bytes = array.readByteSize()
            bytes / array.primitiveType.byteSize
        }.getOrNull()
    }

    private fun isCollectionClass(className: String): Boolean {
        return className == "java.util.ArrayList" ||
            className == "java.util.LinkedList" ||
            className == "java.util.HashMap" ||
            className == "java.util.LinkedHashMap" ||
            className == "java.util.HashSet" ||
            className == "java.util.concurrent.ConcurrentHashMap" ||
            className == "java.util.Vector" ||
            className == "java.util.Stack" ||
            className == "android.util.ArrayMap" ||
            className == "androidx.collection.ArrayMap" ||
            className == "android.util.SparseArray" ||
            className == "androidx.collection.SparseArrayCompat" ||
            className == "android.util.LongSparseArray" ||
            className == "androidx.collection.LongSparseArray"
    }

    private fun objectClassName(heapObject: HeapObject): String {
        return when (heapObject) {
            is HeapClass -> heapObject.name
            is HeapInstance -> heapObject.instanceClassName
            is HeapObjectArray -> heapObject.arrayClassName
            is HeapPrimitiveArray -> heapObject.arrayClassName
        }
    }

    private fun objectSize(heapObject: HeapObject): Long {
        return when (heapObject) {
            is HeapPrimitiveArray -> runCatching { heapObject.readByteSize().toLong() }.getOrDefault(heapObject.recordSize.toLong())
            is HeapInstance -> runCatching { heapObject.byteSize.toLong() }.getOrDefault(heapObject.recordSize.toLong())
            else -> heapObject.recordSize.toLong()
        }
    }

    private fun gcRootTypeName(gcRoot: GcRoot): String {
        return when (gcRoot) {
            is GcRoot.Unknown -> "UNKNOWN"
            is GcRoot.JniGlobal -> "JNI_GLOBAL"
            is GcRoot.JniLocal -> "JNI_LOCAL"
            is GcRoot.JavaFrame -> "JAVA_FRAME"
            is GcRoot.NativeStack -> "NATIVE_STACK"
            is GcRoot.StickyClass -> "STICKY_CLASS"
            is GcRoot.ThreadBlock -> "THREAD_BLOCK"
            is GcRoot.MonitorUsed -> "MONITOR_USED"
            is GcRoot.ThreadObject -> "THREAD_OBJ"
            is GcRoot.ReferenceCleanup -> "REFERENCE_CLEANUP"
            is GcRoot.VmInternal -> "VM_INTERNAL"
            is GcRoot.JniMonitor -> "JNI_MONITOR"
            is GcRoot.InternedString -> "INTERNED_STRING"
            is GcRoot.Finalizing -> "FINALIZING"
            is GcRoot.Debugger -> "DEBUGGER"
            is GcRoot.Unreachable -> "UNREACHABLE"
        }
    }

    private fun gcRootHint(type: String): String {
        return when (type) {
            "THREAD_OBJ", "JAVA_FRAME", "NATIVE_STACK" -> "优先检查线程、Handler、协程或长生命周期任务是否持有对象。"
            "JNI_GLOBAL", "JNI_LOCAL", "JNI_MONITOR" -> "优先检查 native 层、三方 SDK 或 JNI 全局引用。"
            "STICKY_CLASS" -> "优先检查 static 字段、单例和进程级缓存。"
            "INTERNED_STRING" -> "关注大量 intern 字符串或重复常量。"
            else -> "结合泄漏路径和静态持有信息继续排查。"
        }
    }

    private fun packageNameOf(className: String): String {
        val index = className.lastIndexOf('.')
        return if (index > 0) className.substring(0, index) else className
    }

    private fun classifyName(name: String): String {
        return when {
            isSystemClass(name) -> "system"
            name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("kotlin.") -> "runtime"
            name.startsWith("com.google.") ||
                name.startsWith("com.squareup.") ||
                name.startsWith("okhttp3.") ||
                name.startsWith("retrofit2.") ||
                name.startsWith("io.reactivex.") -> "third-party"
            else -> "app"
        }
    }

    private fun isSystemClass(className: String): Boolean {
        return className.startsWith("android.") ||
            className.startsWith("androidx.") ||
            className.startsWith("com.android.") ||
            className.startsWith("dalvik.") ||
            className.startsWith("libcore.")
    }

    /** 对齐 hprof_parser.SYSTEM_PREFIXES：静态持有扫描时跳过这些声明类 */
    private fun isDeclaringClassSystemOrLibrary(className: String): Boolean {
        if (className.isEmpty()) return true
        return DECLARING_CLASS_SYSTEM_PREFIXES.any { className.startsWith(it) }
    }

    /** 对齐 hprof_parser 中 LruCache 类名匹配逻辑 */
    private fun isLruCacheClassName(className: String): Boolean {
        return KNOWN_LRU_CACHE_CLASS_NAMES.any { known ->
            className == known || className.contains("LruCache")
        }
    }

    private fun isComponentOrBitmap(className: String): Boolean {
        return className.contains("Activity") ||
            className.contains("Fragment") ||
            className.contains("View") ||
            className == "android.graphics.Bitmap" ||
            className.endsWith("Bitmap")
    }

    private fun isInterestingHolder(className: String): Boolean {
        return !isSystemClass(className) || INTERESTING_HOLDER_KEYWORDS.any { className.contains(it) }
    }

    private class MutableDistributionCounter(val category: String) {
        var instanceCount: Int = 0
        var totalShallowBytes: Long = 0

        fun add(shallowSize: Long) {
            instanceCount++
            totalShallowBytes += shallowSize
        }
    }

    companion object {
        private const val DEFAULT_TOP_N = 10
        private const val DEFAULT_LARGE_ARRAY_THRESHOLD_BYTES = 256L * 1024L
        private const val DEFAULT_HIGH_RISK_ARRAY_THRESHOLD_BYTES = 1024L * 1024L
        private const val STATIC_FIELD_RETAINED_MIN_BYTES = 100 * 1024
        private const val SINGLETON_RETAINED_MIN_BYTES = 500 * 1024
        private const val ACCUMULATION_MIN_RETAINED_BYTES = 1024L * 1024L
        private const val ACCUMULATION_MIN_RETAINED_TO_SHALLOW_RATIO = 10.0
        private val SINGLETON_FIELD_MARKERS = listOf("\$Companion", "INSTANCE", "sInstance", "mInstance")
        private val KNOWN_LRU_CACHE_CLASS_NAMES = listOf(
            "android.util.LruCache",
            "androidx.collection.LruCache",
            "android.support.v4.util.LruCache"
        )
        private val DECLARING_CLASS_SYSTEM_PREFIXES = listOf(
            "android.",
            "androidx.",
            "com.android.",
            "com.google.android.",
            "java.",
            "javax.",
            "kotlin.",
            "kotlinx.",
            "dalvik.",
            "libcore.",
            "sun.",
            "org.apache.",
            "com.google.gson.",
            "com.squareup.",
            "okhttp3.",
            "retrofit2.",
            "io.reactivex.",
            "rx.",
            "dagger.",
            "org.greenrobot."
        )
        private const val MAX_STRING_CONTENT_LENGTH = 1_000
        private const val MAX_HOLDER_CHAIN_LENGTH = 4
        private val INTERESTING_HOLDER_KEYWORDS = listOf(
            "Activity", "Fragment", "View", "Adapter", "Holder", "Manager",
            "Cache", "Repository", "Controller", "Presenter", "ViewModel",
            "Bitmap", "Drawable", "Image", "Buffer"
        )

        fun formatSize(bytes: Long): String {
            return when {
                bytes >= 1024L * 1024L * 1024L -> "%.2f GB".format(Locale.US, bytes / (1024.0 * 1024.0 * 1024.0))
                bytes >= 1024L * 1024L -> "%.2f MB".format(Locale.US, bytes / (1024.0 * 1024.0))
                bytes >= 1024L -> "%.2f KB".format(Locale.US, bytes / 1024.0)
                else -> "$bytes B"
            }
        }
    }
}

/** Unbounded global diagnostics (before report top-N). */
data class RawGlobalDiagnostics(
    val duplicateStrings: List<DuplicateStringInfo> = emptyList(),
    val largeArrays: List<LargeArrayInfo> = emptyList(),
    val collectionInfos: List<CollectionInfo> = emptyList(),
    val lruCacheInfos: List<LruCacheInfo> = emptyList(),
    val staticHoldings: List<StaticHoldingInfo> = emptyList(),
    val gcRootStats: GcRootStats = GcRootStats(),
    val accumulationPoints: List<AccumulationPointInfo> = emptyList(),
    val packageClassDistribution: PackageClassDistribution = PackageClassDistribution()
)

data class GlobalDiagnosticsResult(
    val duplicateStrings: List<DuplicateStringInfo> = emptyList(),
    val largeArrays: List<LargeArrayInfo> = emptyList(),
    val collectionInfos: List<CollectionInfo> = emptyList(),
    val lruCacheInfos: List<LruCacheInfo> = emptyList(),
    val staticHoldings: List<StaticHoldingInfo> = emptyList(),
    val gcRootStats: GcRootStats = GcRootStats(),
    val accumulationPoints: List<AccumulationPointInfo> = emptyList(),
    val packageClassDistribution: PackageClassDistribution = PackageClassDistribution(),
    val optimizationSuggestions: List<OptimizationSuggestion> = emptyList()
) {
    val hasFindings: Boolean
        get() = duplicateStrings.isNotEmpty() ||
            largeArrays.isNotEmpty() ||
            collectionInfos.isNotEmpty() ||
            lruCacheInfos.isNotEmpty() ||
            staticHoldings.isNotEmpty() ||
            gcRootStats.totalCount > 0 ||
            accumulationPoints.isNotEmpty() ||
            packageClassDistribution.topPackages.isNotEmpty() ||
            packageClassDistribution.topClasses.isNotEmpty()

    companion object {
        fun empty() = GlobalDiagnosticsResult()
    }
}

data class DuplicateStringInfo(
    val content: String,
    val count: Int,
    val estimatedWasteBytes: Long,
    val objectIds: List<Long> = emptyList()
) {
    val preview: String
        get() = content.replace("\n", "\\n").take(80)
}

data class LargeArrayInfo(
    val objectId: Long,
    val arrayType: String,
    val sizeBytes: Long,
    val inferredUsage: String,
    val holderChain: List<HolderChainNode> = emptyList()
)

data class HolderChainNode(
    val objectId: Long,
    val className: String,
    val referenceName: String,
    val referenceType: String
)

data class CollectionInfo(
    val objectId: Long,
    val className: String,
    val size: Int,
    val capacity: Int?,
    val wastedCapacity: Int,
    val riskLevel: RiskLevel
)

data class LruCacheInfo(
    val objectId: Long,
    val className: String,
    val size: Int,
    val maxSize: Int,
    val hitCount: Int?,
    val missCount: Int?,
    val hitRate: Double?,
    val utilization: Double?,
    val riskLevel: RiskLevel
)

enum class StaticHoldingCategory {
    STATIC_FIELD,
    SINGLETON,
    LEAKED_COMPONENT
}

data class StaticHoldingInfo(
    val className: String,
    val fieldName: String,
    val targetObjectId: Long,
    val targetClassName: String,
    val shallowSizeBytes: Long,
    val retainedSizeBytes: Long,
    val category: StaticHoldingCategory,
    val riskLevel: RiskLevel
)

data class GcRootStats(
    val totalCount: Int = 0,
    val typeCounts: Map<String, Int> = emptyMap()
) {
    val topTypes: List<Entry>
        get() = typeCounts.map { Entry(it.key, it.value) }.sortedByDescending { it.count }

    fun sorted(): GcRootStats {
        return copy(typeCounts = topTypes.associate { it.type to it.count })
    }

    data class Entry(
        val type: String,
        val count: Int
    )
}

data class AccumulationPointInfo(
    val objectId: Long,
    val className: String,
    val reason: String,
    val estimatedRetainedBytes: Long,
    val riskLevel: RiskLevel,
    val shallowSizeBytes: Long = 0L,
    val retainedToShallowRatio: Double = 0.0,
    val dominatedObjectCount: Int = 0
)

data class PackageClassDistribution(
    val topPackages: List<Entry> = emptyList(),
    val topClasses: List<Entry> = emptyList()
) {
    fun sorted(topN: Int): PackageClassDistribution {
        return copy(
            topPackages = topPackages.sortedWith(entryComparator).take(topN),
            topClasses = topClasses.sortedWith(entryComparator).take(topN)
        )
    }

    data class Entry(
        val name: String,
        val instanceCount: Int,
        val totalShallowBytes: Long,
        val category: String
    )

    companion object {
        private val entryComparator = compareByDescending<Entry> { it.totalShallowBytes }
            .thenByDescending { it.instanceCount }
    }
}

data class OptimizationSuggestion(
    val priority: SuggestionPriority,
    val title: String,
    val detail: String,
    val suggestion: String
)

enum class SuggestionPriority(val rank: Int) {
    LOW(1),
    MEDIUM(2),
    HIGH(3)
}

enum class RiskLevel(val rank: Int) {
    NONE(0),
    LOW(1),
    MEDIUM(2),
    HIGH(3)
}
