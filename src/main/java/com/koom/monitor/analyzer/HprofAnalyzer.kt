package com.koom.monitor.analyzer

import kshark.AndroidReferenceMatchers
import kshark.HeapAnalyzer
import kshark.HeapObject.HeapClass
import kshark.HeapObject.HeapInstance
import kshark.HprofHeapGraph.Companion.openHeapGraph
import kshark.HprofRecordTag
import kshark.LeakingObjectFinder
import kshark.LeakTrace
import kshark.OnAnalysisProgressListener
import kshark.SharkLog
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.text.SimpleDateFormat
import java.util.Date

/**
 * hprof泄漏分析器 - 使用KOOM Shark
 *
 * 报告逻辑：
 * - hprof_analysis.html: 只报告有GC Root泄露路径的对象（真正的内存泄露）
 * - bitmap_analysis.html: 由BitmapExtractor生成，报告所有大Bitmap和重复Bitmap
 */
class HprofAnalyzer {

    private val logger = LoggerFactory.getLogger(HprofAnalyzer::class.java)

    companion object {
        // 类名常量
        private const val ACTIVITY_CLASS_NAME = "android.app.Activity"
        private const val BITMAP_CLASS_NAME = "android.graphics.Bitmap"
        private const val NATIVE_FRAGMENT_CLASS_NAME = "android.app.Fragment"
        private const val SUPPORT_FRAGMENT_CLASS_NAME = "android.support.v4.app.Fragment"
        private const val ANDROIDX_FRAGMENT_CLASS_NAME = "androidx.fragment.app.Fragment"

        // 字段名
        private const val FINISHED_FIELD_NAME = "mFinished"
        private const val DESTROYED_FIELD_NAME = "mDestroyed"
        private const val FRAGMENT_MANAGER_FIELD_NAME = "mFragmentManager"
        private const val FRAGMENT_MCALLED_FIELD_NAME = "mCalled"

        // 阈值
        private const val SAME_CLASS_LEAK_OBJECT_PATH_THRESHOLD = 45
    }

    init {
        SharkLog.logger = object : SharkLog.Logger {
            override fun d(message: String) {
                logger.debug(message)
            }

            override fun d(throwable: Throwable, message: String) {
                logger.debug(message, throwable)
            }
        }
    }

    /**
     * 分析hprof文件 - 只检测有GC Root泄露路径的对象
     */
    fun analyze(hprofFile: File, bitmapOutputDir: Path? = null): AnalysisResult {
        val startTime = System.currentTimeMillis()

        // 使用File.openHeapGraph扩展函数
        hprofFile.openHeapGraph(
            proguardMapping = null,
            indexedGcRootTypes = setOf(
                HprofRecordTag.ROOT_JNI_GLOBAL,
                HprofRecordTag.ROOT_JNI_LOCAL,
                HprofRecordTag.ROOT_NATIVE_STACK,
                HprofRecordTag.ROOT_STICKY_CLASS,
                HprofRecordTag.ROOT_THREAD_BLOCK,
                HprofRecordTag.ROOT_THREAD_OBJECT
            )
        ).use { graph ->
            val stats = Statistics()

            // 获取关键类
            val activityClass = graph.findClassByName(ACTIVITY_CLASS_NAME)
            val fragmentClass = graph.findClassByName(ANDROIDX_FRAGMENT_CLASS_NAME)
                ?: graph.findClassByName(NATIVE_FRAGMENT_CLASS_NAME)
                ?: graph.findClassByName(SUPPORT_FRAGMENT_CLASS_NAME)
            val bitmapClass = graph.findClassByName(BITMAP_CLASS_NAME)

            // 统计信息
            stats.totalClassCount = graph.classCount
            stats.totalInstanceCount = graph.instanceCount
            stats.objectArrayCount = graph.objectArrayCount
            stats.primitiveArrayCount = graph.primitiveArrayCount

            // 统计包名分布
            analyzePackages(graph, stats)

            // 收集Bitmap信息（用于后续查找泄露对象中的Bitmap）
            val bitmapMap = collectBitmapInfo(graph, bitmapClass, stats)

            // 创建自定义的LeakingObjectFinder - 只检测真正的泄露
            val leakingObjectFinder = object : LeakingObjectFinder {
                override fun findLeakingObjectIds(graph: kshark.HeapGraph): Set<Long> {
                    val leakingIds = mutableSetOf<Long>()

                    for (instance in graph.instances) {
                        // 检查Activity泄漏 - 必须是已销毁但仍可从GC Root到达的
                        if (isActivity(activityClass, instance)) {
                            val destroyedField = instance[ACTIVITY_CLASS_NAME, DESTROYED_FIELD_NAME]
                            val finishedField = instance[ACTIVITY_CLASS_NAME, FINISHED_FIELD_NAME]

                            val isDestroyed = destroyedField?.value?.asBoolean ?: false
                            val isFinished = finishedField?.value?.asBoolean ?: false

                            if (isDestroyed || isFinished) {
                                val objectCounter = updateClassCounter(instance.instanceClassId)
                                if (objectCounter.leakCnt <= SAME_CLASS_LEAK_OBJECT_PATH_THRESHOLD) {
                                    leakingIds.add(instance.objectId)
                                    stats.leakedActivityCount++
                                    logger.debug("发现泄漏Activity: ${instance.instanceClassName}")
                                }
                            }
                            continue
                        }

                        // 检查Fragment泄漏 - 必须是已销毁但仍可从GC Root到达的
                        if (isFragment(fragmentClass, instance)) {
                            val fragmentManager = instance[fragmentClass!!.name, FRAGMENT_MANAGER_FIELD_NAME]
                            val mCalledField = instance[fragmentClass.name, FRAGMENT_MCALLED_FIELD_NAME]

                            val isNullManager = fragmentManager?.value?.asObject == null
                            val isCalled = mCalledField?.value?.asBoolean ?: false

                            if (isNullManager && isCalled) {
                                val objectCounter = updateClassCounter(instance.instanceClassId)
                                if (objectCounter.leakCnt <= SAME_CLASS_LEAK_OBJECT_PATH_THRESHOLD) {
                                    leakingIds.add(instance.objectId)
                                    stats.leakedFragmentCount++
                                    logger.debug("发现泄漏Fragment: ${instance.instanceClassName}")
                                }
                            }
                            continue
                        }

                        // 检查NativeAllocation、Window等（仅统计，不作为泄露）
                        if (instance.instanceClass.name == "libcore.util.NativeAllocationRegistry") {
                            updateClassCounter(instance.instanceClassId)
                        }
                        if (instance.instanceClass.name == "android.view.Window") {
                            updateClassCounter(instance.instanceClassId)
                        }
                    }

                    return leakingIds
                }
            }

            // 使用HeapAnalyzer分析
            val analyzer = HeapAnalyzer(
                OnAnalysisProgressListener { step ->
                    logger.debug("分析进度: ${step.name}")
                }
            )

            val heapAnalysis = analyzer.analyze(
                heapDumpFile = hprofFile,
                graph = graph,
                leakingObjectFinder = leakingObjectFinder,
                referenceMatchers = AndroidReferenceMatchers.appDefaults,
                computeRetainedHeapSize = false,
                objectInspectors = emptyList()
            )

            val leakingObjects = when (heapAnalysis) {
                is kshark.HeapAnalysisSuccess -> {
                    buildLeakingObjects(heapAnalysis, graph, stats, bitmapMap, bitmapOutputDir)
                }
                is kshark.HeapAnalysisFailure -> {
                    logger.error("分析失败: ${heapAnalysis.exception}")
                    emptyList()
                }
            }

            val elapsed = System.currentTimeMillis() - startTime

            return AnalysisResult(
                file = hprofFile,
                fileSize = hprofFile.length(),
                analyzeTime = elapsed,
                stats = stats,
                leakingObjects = leakingObjects,
                bitmapOutputDir = bitmapOutputDir
            )
        }
    }

    private val classCounters = mutableMapOf<Long, ObjectCounter>()

    private fun updateClassCounter(classId: Long): ObjectCounter {
        val counter = classCounters.getOrPut(classId) { ObjectCounter() }
        counter.allCnt++
        counter.leakCnt++
        return counter
    }

    /**
     * 收集Bitmap信息（objectId -> BitmapInfo）
     */
    private fun collectBitmapInfo(
        graph: kshark.HeapGraph,
        bitmapClass: HeapClass?,
        stats: Statistics
    ): Map<Long, BitmapInLeak> {
        val bitmapMap = mutableMapOf<Long, BitmapInLeak>()

        if (bitmapClass == null) return bitmapMap

        for (instance in graph.instances) {
            if (!isBitmap(bitmapClass, instance)) continue

            val widthField = instance[BITMAP_CLASS_NAME, "mWidth"]
            val heightField = instance[BITMAP_CLASS_NAME, "mHeight"]

            val width = widthField?.value?.asInt ?: 0
            val height = heightField?.value?.asInt ?: 0
            val pixelCount = width * height

            stats.bitmapCount++
            if (pixelCount >= 1_000_000) {
                stats.largeBitmapCount++
            }

            bitmapMap[instance.objectId] = BitmapInLeak(
                objectId = instance.objectId,
                width = width,
                height = height,
                pixelCount = pixelCount,
                hasImageFile = false,
                imageFilePath = null
            )
        }

        return bitmapMap
    }

    /**
     * 构建泄漏对象列表
     */
    private fun buildLeakingObjects(
        heapAnalysis: kshark.HeapAnalysisSuccess,
        graph: kshark.HeapGraph,
        stats: Statistics,
        bitmapMap: Map<Long, BitmapInLeak>,
        bitmapOutputDir: Path?
    ): List<LeakingObject> {
        val results = mutableListOf<LeakingObject>()

        // 检查Bitmap输出目录中已有的图片文件
        val existingBitmapFiles = mutableMapOf<String, Path>()
        if (bitmapOutputDir != null && Files.exists(bitmapOutputDir)) {
            Files.walk(bitmapOutputDir)
                .filter { Files.isRegularFile(it) }
                .filter { it.fileName.toString().startsWith("bitmap_") && it.fileName.toString().endsWith(".png") }
                .forEach { file ->
                    // 从文件名提取objectId: bitmap_<objectId>_<hash>_<width>x<height>.png
                    val match = Regex("bitmap_(\\d+)_").find(file.fileName.toString())
                    if (match != null) {
                        val objectId = match.groupValues[1].toLongOrNull()
                        if (objectId != null) {
                            existingBitmapFiles[objectId.toString()] = file
                        }
                    }
                }
        }

        // 更新bitmapMap中的图片文件信息
        existingBitmapFiles.forEach { (objectIdStr, filePath) ->
            val objectId = objectIdStr.toLongOrNull() ?: return@forEach
            bitmapMap[objectId]?.let { bmp ->
                bmp.hasImageFile = true
                bmp.imageFilePath = "bitmaps/${filePath.fileName}"
            }
        }

        // 处理应用泄漏
        for (appLeak in heapAnalysis.applicationLeaks) {
            if (appLeak.leakTraces.isNotEmpty()) {
                val leakTrace = appLeak.leakTraces[0]
                val leakingObjectId = leakTrace.leakingObject.objectId

                // 检查此泄露对象中包含的Bitmap
                val heapObject = graph.findObjectById(leakingObjectId)
                val containedBitmaps = if (heapObject != null) {
                    val bitmaps = findContainedBitmaps(heapObject, bitmapMap, existingBitmapFiles).toMutableList()

                    // 如果泄露对象本身就是Bitmap，添加到containedBitmaps中
                    val bitmapInfo = bitmapMap[leakingObjectId]
                    if (bitmapInfo != null && bitmapInfo.pixelCount >= 100_000) {
                        bitmaps.add(
                            0,
                            ContainedBitmap(
                                objectId = leakingObjectId,
                                width = bitmapInfo.width,
                                height = bitmapInfo.height,
                                pixelCount = bitmapInfo.pixelCount,
                                imageFilePath = bitmapInfo.imageFilePath
                            )
                        )
                    }

                    bitmaps
                } else {
                    emptyList()
                }

                results.add(
                    LeakingObject(
                        className = leakTrace.leakingObject.className,
                        objectId = leakingObjectId,
                        size = leakTrace.leakingObject.retainedHeapByteSize?.toLong() ?: 0,
                        leakReason = "Application Leak",
                        referenceChain = buildReferenceChain(leakTrace.referencePath),
                        gcRoot = leakTrace.gcRootType.description,
                        signature = appLeak.signature,
                        instanceCount = appLeak.leakTraces.size,
                        containedBitmaps = containedBitmaps
                    )
                )
            }
        }

        // 处理库泄漏
        for (libLeak in heapAnalysis.libraryLeaks) {
            if (libLeak.leakTraces.isNotEmpty()) {
                val leakTrace = libLeak.leakTraces[0]
                val leakingObjectId = leakTrace.leakingObject.objectId

                // 检查此泄露对象中包含的Bitmap
                val heapObject = graph.findObjectById(leakingObjectId)
                val containedBitmaps = if (heapObject != null) {
                    val bitmaps = findContainedBitmaps(heapObject, bitmapMap, existingBitmapFiles).toMutableList()

                    // 如果泄露对象本身就是Bitmap，添加到containedBitmaps中
                    val bitmapInfo = bitmapMap[leakingObjectId]
                    if (bitmapInfo != null && bitmapInfo.pixelCount >= 100_000) {
                        bitmaps.add(
                            0,
                            ContainedBitmap(
                                objectId = leakingObjectId,
                                width = bitmapInfo.width,
                                height = bitmapInfo.height,
                                pixelCount = bitmapInfo.pixelCount,
                                imageFilePath = bitmapInfo.imageFilePath
                            )
                        )
                    }

                    bitmaps
                } else {
                    emptyList()
                }

                results.add(
                    LeakingObject(
                        className = leakTrace.leakingObject.className,
                        objectId = leakingObjectId,
                        size = leakTrace.leakingObject.retainedHeapByteSize?.toLong() ?: 0,
                        leakReason = "Library Leak: ${libLeak.description}",
                        referenceChain = buildReferenceChain(leakTrace.referencePath),
                        gcRoot = leakTrace.gcRootType.description,
                        signature = libLeak.signature,
                        instanceCount = libLeak.leakTraces.size,
                        containedBitmaps = containedBitmaps
                    )
                )
            }
        }

        return results
    }

    /**
     * 查找泄露对象中包含的Bitmap
     * 递归遍历对象的引用，找到所有Bitmap对象
     */
    private fun findContainedBitmaps(
        heapObject: kshark.HeapObject,
        bitmapMap: Map<Long, BitmapInLeak>,
        existingBitmapFiles: Map<String, Path>
    ): List<ContainedBitmap> {
        val result = mutableListOf<ContainedBitmap>()
        val visited = mutableSetOf<Long>()

        fun findBitmapsInObject(obj: kshark.HeapObject, depth: Int) {
            if (depth > 10) return  // 限制递归深度
            if (obj.objectId in visited) return
            visited.add(obj.objectId)

            if (obj !is kshark.HeapObject.HeapInstance) return

            // 检查当前对象是否是Bitmap
            val bitmapInfo = bitmapMap[obj.objectId]
            if (bitmapInfo != null && bitmapInfo.pixelCount >= 100_000) {  // 只显示较大的Bitmap
                result.add(
                    ContainedBitmap(
                        objectId = obj.objectId,
                        width = bitmapInfo.width,
                        height = bitmapInfo.height,
                        pixelCount = bitmapInfo.pixelCount,
                        imageFilePath = bitmapInfo.imageFilePath
                    )
                )
            }

            // 递归检查引用的对象
            try {
                obj.readFields().forEach { field ->
                    val value = field.value.asObject
                    if (value != null) {
                        findBitmapsInObject(value, depth + 1)
                    }
                }
            } catch (e: Exception) {
                // 忽略无法读取字段的对象
            }
        }

        findBitmapsInObject(heapObject, 0)
        return result.sortedByDescending { it.pixelCount }
    }

    private fun buildReferenceChain(referencePath: List<kshark.LeakTraceReference>): List<ReferenceNode> {
        return referencePath.map { ref ->
            ReferenceNode(
                className = ref.originObject.className,
                referenceName = ref.referenceName,
                referenceType = ref.referenceType.name,
                declaredClass = ref.owningClassName
            )
        }
    }

    private fun analyzePackages(heapGraph: kshark.HeapGraph, stats: Statistics) {
        for (instance in heapGraph.instances) {
            val className = instance.instanceClass.name
            val packageName = getPackageName(className)

            when {
                packageName.startsWith("android.") -> stats.androidCount++
                packageName.startsWith("androidx.") -> stats.androidxCount++
                packageName.startsWith("java.") -> stats.javaCount++
                packageName.startsWith("kotlin.") -> stats.kotlinCount++
                packageName.startsWith("dalvik.") || packageName.startsWith("libcore.") -> stats.dalvikCount++
                else -> {
                    if (!packageName.startsWith("com.google.") &&
                        !packageName.startsWith("com.android.")) {
                        stats.appClassCount++
                        detectAppPackage(packageName, stats)
                    }
                }
            }
        }
    }

    private fun detectAppPackage(className: String, stats: Statistics) {
        val parts = className.split(".")
        if (parts.size >= 2) {
            val possiblePackage = "${parts[0]}.${parts[1]}"
            if (!stats.detectedPackages.contains(possiblePackage) &&
                !stats.detectedPackages.any { possiblePackage.startsWith(it) }) {
                stats.detectedPackages.add(possiblePackage)
            }
        }
    }

    private fun getPackageName(className: String): String {
        val lastDot = className.lastIndexOf('.')
        return if (lastDot > 0) className.substring(0, lastDot) else className
    }

    // 辅助方法
    private fun isActivity(activityClass: HeapClass?, instance: HeapInstance): Boolean {
        if (activityClass == null) return false
        val hierarchy = instance.instanceClass.classHierarchy.toList()
        return hierarchy.any { it.objectId == activityClass.objectId }
    }

    private fun isFragment(fragmentClass: HeapClass?, instance: HeapInstance): Boolean {
        if (fragmentClass == null) return false
        val hierarchy = instance.instanceClass.classHierarchy.toList()
        return hierarchy.any { it.objectId == fragmentClass.objectId }
    }

    private fun isBitmap(bitmapClass: HeapClass?, instance: HeapInstance): Boolean {
        if (bitmapClass == null) return false
        val hierarchy = instance.instanceClass.classHierarchy.toList()
        return hierarchy.any { it.objectId == bitmapClass.objectId }
    }

    private class ObjectCounter {
        var allCnt = 0
        var leakCnt = 0
    }

    /**
     * 统计信息
     */
    data class Statistics(
        var totalClassCount: Int = 0,
        var totalInstanceCount: Int = 0,
        var objectArrayCount: Int = 0,
        var primitiveArrayCount: Int = 0,
        var androidCount: Long = 0,
        var androidxCount: Long = 0,
        var javaCount: Long = 0,
        var kotlinCount: Long = 0,
        var dalvikCount: Long = 0,
        var appClassCount: Long = 0,
        var bitmapCount: Int = 0,
        var largeBitmapCount: Int = 0,
        var leakedActivityCount: Int = 0,
        var leakedFragmentCount: Int = 0,
        val detectedPackages: MutableList<String> = mutableListOf()
    ) {
        fun formatSize(bytes: Long): String {
            return when {
                bytes >= 1024 * 1024 -> "%.2f MB".format(bytes / (1024.0 * 1024))
                bytes >= 1024 -> "%.2f KB".format(bytes / 1024.0)
                else -> "$bytes B"
            }
        }
    }

    /**
     * 分析结果
     */
    data class AnalysisResult(
        val file: File,
        val fileSize: Long,
        val analyzeTime: Long,
        val stats: Statistics,
        val leakingObjects: List<LeakingObject> = emptyList(),
        val bitmapOutputDir: Path? = null
    ) {
        fun printReport() {
            println()
            println("╔══════════════════════════════════════════════════════════════╗")
            println("║              Hprof 内存泄露分析报告 (仅泄露对象)                ║")
            println("╚══════════════════════════════════════════════════════════════╝")
            println()
            println("📄 文件: ${file.name}")
            println("📦 大小: ${stats.formatSize(fileSize)}")
            println("⏱️  分析耗时: ${analyzeTime}ms")
            println()
            println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            println("📊 对象统计")
            println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            println("   总类数: ${"%,d".format(stats.totalClassCount)}")
            println("   总实例数: ${"%,d".format(stats.totalInstanceCount)}")
            println("   对象数组: ${"%,d".format(stats.objectArrayCount)}")
            println("   基本类型数组: ${"%,d".format(stats.primitiveArrayCount)}")
            println()
            println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            println("📱 按包分类")
            println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            println("   Android: ${"%,d".format(stats.androidCount)}")
            println("   AndroidX: ${"%,d".format(stats.androidxCount)}")
            println("   Java: ${"%,d".format(stats.javaCount)}")
            println("   Kotlin: ${"%,d".format(stats.kotlinCount)}")
            println("   Dalvik/Libcore: ${"%,d".format(stats.dalvikCount)}")
            println("   应用类: ${"%,d".format(stats.appClassCount)}")
            println()

            if (stats.detectedPackages.isNotEmpty()) {
                println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                println("📦 检测到的应用包")
                println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                stats.detectedPackages.forEach {
                    println("   - $it")
                }
                println()
            }

            println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            println("🖼️  Bitmap 统计")
            println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            println("   Bitmap数量: ${stats.bitmapCount}")
            println("   大Bitmap(>1M像素): ${stats.largeBitmapCount}")
            println("   (Bitmap详细分析请查看 bitmap_analysis.html)")
            println()

            if (leakingObjects.isNotEmpty()) {
                println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                println("🚨 内存泄露对象 (${leakingObjects.size})")
                println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

                val byType = leakingObjects.groupBy { obj ->
                    when {
                        obj.className.contains("Activity") -> "Activity"
                        obj.className.contains("Fragment") -> "Fragment"
                        else -> "Other"
                    }
                }

                byType.forEach { (type, objects) ->
                    println("\n   📍 $type (${objects.size})")
                    objects.take(5).forEach { leak ->
                        println("      - ${leak.className}")
                        println("        GC Root: ${leak.gcRoot}")
                        println("        签名: ${leak.signature.take(100)}")
                        if (leak.containedBitmaps.isNotEmpty()) {
                            val isSelfBitmap = leak.containedBitmaps.first().objectId == leak.objectId
                            if (isSelfBitmap) {
                                println("        此对象是Bitmap:")
                            } else {
                                println("        包含Bitmap: ${leak.containedBitmaps.size} 个")
                            }
                            leak.containedBitmaps.take(3).forEach { bmp ->
                                println("          - ${bmp.width}x${bmp.height}")
                                if (bmp.imageFilePath != null) {
                                    println("            图片: ${bmp.imageFilePath}")
                                }
                            }
                        }
                        if (leak.instanceCount > 1) {
                            println("        相同泄露: ${leak.instanceCount} 个")
                        }
                        // 打印引用链
                        leak.referenceChain.take(5).forEach { ref ->
                            println("          → ${ref.fullName}")
                        }
                        if (leak.referenceChain.size > 5) {
                            println("          ... (还有 ${leak.referenceChain.size - 5} 层)")
                        }
                    }
                    if (objects.size > 5) {
                        println("      ... 还有 ${objects.size - 5} 个")
                    }
                }
                println()
            } else {
                println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                println("✅ 未发现内存泄露")
                println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                println("   没有检测到Activity或Fragment的内存泄露。")
                println("   (Bitmap分析请查看 bitmap_analysis.html)")
                println()
            }
        }

        /**
         * 保存报告到文件
         */
        fun saveReport(outputDir: Path): List<Path> {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
            val files = mutableListOf<Path>()

            // 创建输出目录
            Files.createDirectories(outputDir)

            // 写入文本报告
            val txtFile = outputDir.resolve("hprof_analysis_${timestamp}.txt")
            Files.writeString(txtFile, generateTextReport())
            files.add(txtFile)

            // 写入HTML报告
            val htmlFile = outputDir.resolve("hprof_analysis_${timestamp}.html")
            Files.writeString(htmlFile, generateHtmlReport())
            files.add(htmlFile)

            return files
        }

        private fun generateTextReport(): String {
            val sb = StringBuilder()
            sb.appendLine("\n╔══════════════════════════════════════════════════════════════╗")
            sb.appendLine("║              Hprof 内存泄露分析报告 (仅泄露对象)                ║")
            sb.appendLine("╚══════════════════════════════════════════════════════════════╝")
            sb.appendLine()
            sb.appendLine("📄 文件: ${file.name}")
            sb.appendLine("📦 大小: ${stats.formatSize(fileSize)}")
            sb.appendLine("⏱️  分析耗时: ${analyzeTime}ms")
            sb.appendLine()
            sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            sb.appendLine("📊 对象统计")
            sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            sb.appendLine("   总类数: ${"%,d".format(stats.totalClassCount)}")
            sb.appendLine("   总实例数: ${"%,d".format(stats.totalInstanceCount)}")
            sb.appendLine("   对象数组: ${"%,d".format(stats.objectArrayCount)}")
            sb.appendLine("   基本类型数组: ${"%,d".format(stats.primitiveArrayCount)}")
            sb.appendLine()
            sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            sb.appendLine("📱 按包分类")
            sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            sb.appendLine("   Android: ${"%,d".format(stats.androidCount)}")
            sb.appendLine("   AndroidX: ${"%,d".format(stats.androidxCount)}")
            sb.appendLine("   Java: ${"%,d".format(stats.javaCount)}")
            sb.appendLine("   Kotlin: ${"%,d".format(stats.kotlinCount)}")
            sb.appendLine("   Dalvik/Libcore: ${"%,d".format(stats.dalvikCount)}")
            sb.appendLine("   应用类: ${"%,d".format(stats.appClassCount)}")
            sb.appendLine()

            if (stats.detectedPackages.isNotEmpty()) {
                sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                sb.appendLine("📦 检测到的应用包")
                sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                stats.detectedPackages.forEach {
                    sb.appendLine("   - $it")
                }
                sb.appendLine()
            }

            sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            sb.appendLine("🖼️  Bitmap 统计")
            sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            sb.appendLine("   Bitmap数量: ${stats.bitmapCount}")
            sb.appendLine("   大Bitmap(>1M像素): ${stats.largeBitmapCount}")
            sb.appendLine("   (Bitmap详细分析请查看 bitmap_analysis.html)")
            sb.appendLine()

            if (leakingObjects.isNotEmpty()) {
                sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                sb.appendLine("🚨 内存泄露对象 (${leakingObjects.size})")
                sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

                val byType = leakingObjects.groupBy { obj ->
                    when {
                        obj.className.contains("Activity") -> "Activity"
                        obj.className.contains("Fragment") -> "Fragment"
                        else -> "Other"
                    }
                }

                byType.forEach { (type, objects) ->
                    sb.appendLine("\n   📍 $type (${objects.size})")
                    objects.take(5).forEach { leak ->
                        sb.appendLine("      - ${leak.className}")
                        sb.appendLine("        GC Root: ${leak.gcRoot}")
                        sb.appendLine("        签名: ${leak.signature.take(100)}")
                        if (leak.containedBitmaps.isNotEmpty()) {
                            val isSelfBitmap = leak.containedBitmaps.first().objectId == leak.objectId
                            if (isSelfBitmap) {
                                sb.appendLine("        此对象是Bitmap:")
                            } else {
                                sb.appendLine("        包含Bitmap: ${leak.containedBitmaps.size} 个")
                            }
                            leak.containedBitmaps.take(3).forEach { bmp ->
                                sb.appendLine("          - ${bmp.width}x${bmp.height} (${bmp.pixelCount * 4 / 1024}KB)")
                                if (bmp.imageFilePath != null) {
                                    sb.appendLine("            图片: ${bmp.imageFilePath}")
                                }
                            }
                        }
                        if (leak.instanceCount > 1) {
                            sb.appendLine("        相同泄露: ${leak.instanceCount} 个")
                        }
                        leak.referenceChain.take(5).forEach { ref ->
                            sb.appendLine("          → ${ref.fullName}")
                        }
                        if (leak.referenceChain.size > 5) {
                            sb.appendLine("          ... (还有 ${leak.referenceChain.size - 5} 层)")
                        }
                    }
                    if (objects.size > 5) {
                        sb.appendLine("      ... 还有 ${objects.size - 5} 个")
                    }
                }
                sb.appendLine()
            } else {
                sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                sb.appendLine("✅ 未发现内存泄露")
                sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                sb.appendLine("   没有检测到Activity或Fragment的内存泄露。")
                sb.appendLine("   (Bitmap分析请查看 bitmap_analysis.html)")
                sb.appendLine()
            }

            return sb.toString()
        }

        private fun generateHtmlReport(): String {
            val sb = StringBuilder()
            sb.appendLine("<!DOCTYPE html>")
            sb.appendLine("<html lang=\"zh-CN\">")
            sb.appendLine("<head>")
            sb.appendLine("    <meta charset=\"UTF-8\">")
            sb.appendLine("    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">")
            sb.appendLine("    <title>Hprof 内存泄露分析 - ${file.name}</title>")
            sb.appendLine("    <style>")
            sb.appendLine("        * { margin: 0; padding: 0; box-sizing: border-box; }")
            sb.appendLine("        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: #f5f5f5; padding: 20px; }")
            sb.appendLine("        .container { max-width: 1200px; margin: 0 auto; background: white; border-radius: 8px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); }")
            sb.appendLine("        .header { background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); color: white; padding: 30px; border-radius: 8px 8px 0 0; }")
            sb.appendLine("        .header h1 { font-size: 24px; margin-bottom: 10px; }")
            sb.appendLine("        .header .meta { opacity: 0.9; font-size: 14px; }")
            sb.appendLine("        .section { padding: 25px; border-bottom: 1px solid #eee; }")
            sb.appendLine("        .section:last-child { border-bottom: none; }")
            sb.appendLine("        .section-title { font-size: 18px; font-weight: 600; color: #333; margin-bottom: 20px; display: flex; align-items: center; }")
            sb.appendLine("        .section-title .icon { margin-right: 10px; font-size: 20px; }")
            sb.appendLine("        .stats-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 15px; }")
            sb.appendLine("        .stat-card { background: #f8f9fa; padding: 15px; border-radius: 6px; border-left: 4px solid #667eea; }")
            sb.appendLine("        .stat-card .label { font-size: 12px; color: #666; margin-bottom: 5px; }")
            sb.appendLine("        .stat-card .value { font-size: 24px; font-weight: 700; color: #333; }")
            sb.appendLine("        .package-list { display: flex; flex-wrap: wrap; gap: 10px; }")
            sb.appendLine("        .package-tag { background: #e3f2fd; color: #1976d2; padding: 6px 12px; border-radius: 16px; font-size: 13px; }")
            sb.appendLine("        .leak-list { display: flex; flex-direction: column; gap: 15px; }")
            sb.appendLine("        .leak-item { background: #fff3e0; border-left: 4px solid #ff9800; padding: 15px; border-radius: 6px; }")
            sb.appendLine("        .leak-item.library { background: #ffebee; border-left-color: #f44336; }")
            sb.appendLine("        .leak-header { display: flex; justify-content: space-between; align-items: start; margin-bottom: 10px; }")
            sb.appendLine("        .leak-class { font-weight: 600; color: #333; font-size: 14px; }")
            sb.appendLine("        .leak-count { background: #ff9800; color: white; padding: 2px 8px; border-radius: 10px; font-size: 11px; }")
            sb.appendLine("        .leak-details { font-size: 13px; color: #666; }")
            sb.appendLine("        .leak-details div { margin: 4px 0; }")
            sb.appendLine("        .reference-chain { margin-top: 10px; padding-left: 15px; border-left: 2px solid #ddd; }")
            sb.appendLine("        .reference-chain .ref { font-size: 12px; color: #888; padding: 3px 0; }")
            sb.appendLine("        .reference-chain .ref .arrow { color: #667eea; margin-right: 5px; }")
            sb.appendLine("        .leak-type { display: inline-block; padding: 4px 12px; border-radius: 4px; font-size: 12px; font-weight: 600; margin-bottom: 10px; }")
            sb.appendLine("        .leak-type.activity { background: #ffebee; color: #c62828; }")
            sb.appendLine("        .leak-type.fragment { background: #fff3e0; color: #ef6c00; }")
            sb.appendLine("        .leak-type.other { background: #f3e5f5; color: #7b1fa2; }")
            sb.appendLine("        .bitmap-list { margin-top: 10px; padding: 10px; background: #e8f5e9; border-radius: 4px; }")
            sb.appendLine("        .bitmap-item { display: inline-flex; align-items: center; margin: 5px; padding: 5px 10px; background: white; border-radius: 4px; font-size: 12px; }")
            sb.appendLine("        .bitmap-thumb { width: 40px; height: 40px; object-fit: contain; margin-right: 8px; border-radius: 4px; }")
            sb.appendLine("        .no-leak { text-align: center; padding: 40px; color: #4caf50; }")
            sb.appendLine("        .no-leak .icon { font-size: 48px; }")
            sb.appendLine("        .no-leak h2 { margin-top: 10px; color: #2e7d32; }")
            sb.appendLine("        .no-leak p { color: #666; margin-top: 10px; }")
            sb.appendLine("        .info-box { background: #e3f2fd; padding: 15px; border-radius: 6px; margin-top: 15px; }")
            sb.appendLine("        .info-box .title { font-weight: 600; color: #1976d2; margin-bottom: 10px; }")
            sb.appendLine("        .info-box .link { color: #1976d2; text-decoration: none; }")
            sb.appendLine("    </style>")
            sb.appendLine("</head>")
            sb.appendLine("<body>")
            sb.appendLine("    <div class=\"container\">")
            sb.appendLine("        <div class=\"header\">")
            sb.appendLine("            <h1>🔍 Hprof 内存泄露分析报告</h1>")
            sb.appendLine("            <div class=\"meta\">")
            sb.appendLine("                文件: ${file.name} | 大小: ${stats.formatSize(fileSize)} | 分析耗时: ${analyzeTime}ms")
            sb.appendLine("            </div>")
            sb.appendLine("        </div>")

            // 对象统计
            sb.appendLine("        <div class=\"section\">")
            sb.appendLine("            <div class=\"section-title\"><span class=\"icon\">📊</span>对象统计</div>")
            sb.appendLine("            <div class=\"stats-grid\">")
            sb.appendLine("                <div class=\"stat-card\"><div class=\"label\">总类数</div><div class=\"value\">${"%,d".format(stats.totalClassCount)}</div></div>")
            sb.appendLine("                <div class=\"stat-card\"><div class=\"label\">总实例数</div><div class=\"value\">${"%,d".format(stats.totalInstanceCount)}</div></div>")
            sb.appendLine("                <div class=\"stat-card\"><div class=\"label\">对象数组</div><div class=\"value\">${"%,d".format(stats.objectArrayCount)}</div></div>")
            sb.appendLine("                <div class=\"stat-card\"><div class=\"label\">基本类型数组</div><div class=\"value\">${"%,d".format(stats.primitiveArrayCount)}</div></div>")
            sb.appendLine("            </div>")
            sb.appendLine("        </div>")

            // 按包分类
            sb.appendLine("        <div class=\"section\">")
            sb.appendLine("            <div class=\"section-title\"><span class=\"icon\">📱</span>按包分类</div>")
            sb.appendLine("            <div class=\"stats-grid\">")
            sb.appendLine("                <div class=\"stat-card\"><div class=\"label\">Android</div><div class=\"value\">${"%,d".format(stats.androidCount)}</div></div>")
            sb.appendLine("                <div class=\"stat-card\"><div class=\"label\">AndroidX</div><div class=\"value\">${"%,d".format(stats.androidxCount)}</div></div>")
            sb.appendLine("                <div class=\"stat-card\"><div class=\"label\">Java</div><div class=\"value\">${"%,d".format(stats.javaCount)}</div></div>")
            sb.appendLine("                <div class=\"stat-card\"><div class=\"label\">Kotlin</div><div class=\"value\">${"%,d".format(stats.kotlinCount)}</div></div>")
            sb.appendLine("                <div class=\"stat-card\"><div class=\"label\">应用类</div><div class=\"value\">${"%,d".format(stats.appClassCount)}</div></div>")
            sb.appendLine("            </div>")
            sb.appendLine("        </div>")

            // 检测到的应用包
            if (stats.detectedPackages.isNotEmpty()) {
                sb.appendLine("        <div class=\"section\">")
                sb.appendLine("            <div class=\"section-title\"><span class=\"icon\">📦</span>检测到的应用包</div>")
                sb.appendLine("            <div class=\"package-list\">")
                stats.detectedPackages.forEach {
                    sb.appendLine("                <span class=\"package-tag\">$it</span>")
                }
                sb.appendLine("            </div>")
                sb.appendLine("        </div>")
            }

            // Bitmap统计 (简要，详细在bitmap_analysis.html)
            sb.appendLine("        <div class=\"section\">")
            sb.appendLine("            <div class=\"section-title\"><span class=\"icon\">🖼️</span>Bitmap 统计</div>")
            sb.appendLine("            <div class=\"stats-grid\">")
            sb.appendLine("                <div class=\"stat-card\"><div class=\"label\">Bitmap数量</div><div class=\"value\">${stats.bitmapCount}</div></div>")
            sb.appendLine("                <div class=\"stat-card\"><div class=\"label\">大Bitmap(>1M像素)</div><div class=\"value\">${stats.largeBitmapCount}</div></div>")
            sb.appendLine("            </div>")
            // 显示Bitmap详细分析链接
            sb.appendLine("            <div class=\"info-box\">")
            sb.appendLine("                <div class=\"title\">📁 Bitmap详细分析</div>")
            sb.appendLine("                <div>查看 <a href=\"bitmap_analysis.html\" class=\"link\">bitmap_analysis.html</a> 获取完整的Bitmap分析报告</div>")
            if (bitmapOutputDir != null) {
                sb.appendLine("                <div style=\"margin-top: 5px; font-size: 12px; color: #666;\">Bitmap图片目录: ${bitmapOutputDir.fileName}/</div>")
            }
            sb.appendLine("            </div>")
            sb.appendLine("        </div>")

            // 泄漏对象
            if (leakingObjects.isNotEmpty()) {
                sb.appendLine("        <div class=\"section\">")
                sb.appendLine("            <div class=\"section-title\"><span class=\"icon\">🚨</span>内存泄露对象 (${leakingObjects.size})</div>")

                val byType = leakingObjects.groupBy { obj ->
                    when {
                        obj.className.contains("Activity") -> "activity"
                        obj.className.contains("Fragment") -> "fragment"
                        else -> "other"
                    }
                }

                byType.forEach { (type, objects) ->
                    val typeLabel = when(type) {
                        "activity" -> "Activity"
                        "fragment" -> "Fragment"
                        else -> "Other"
                    }
                    sb.appendLine("            <div style=\"margin-top: 20px;\">")
                    sb.appendLine("                <span class=\"leak-type $type\">📍 $typeLabel (${objects.size})</span>")
                    objects.take(10).forEach { leak ->
                        val isLibrary = leak.leakReason.startsWith("Library")
                        sb.appendLine("                <div class=\"leak-item ${if(isLibrary) "library" else ""}\">")
                        sb.appendLine("                    <div class=\"leak-header\">")
                        sb.appendLine("                        <span class=\"leak-class\">${leak.className}</span>")
                        if (leak.instanceCount > 1) {
                            sb.appendLine("                        <span class=\"leak-count\">${leak.instanceCount}个</span>")
                        }
                        sb.appendLine("                    </div>")
                        sb.appendLine("                    <div class=\"leak-details\">")
                        sb.appendLine("                        <div><strong>原因:</strong> ${leak.leakReason}</div>")
                        sb.appendLine("                        <div><strong>GC Root:</strong> ${leak.gcRoot}</div>")
                        sb.appendLine("                        <div><strong>签名:</strong> ${leak.signature.take(100)}</div>")

                        // 显示包含的Bitmap
                        if (leak.containedBitmaps.isNotEmpty()) {
                            sb.appendLine("                        <div class=\"bitmap-list\">")

                            // 检查泄露对象本身是否是Bitmap
                            val isSelfBitmap = leak.containedBitmaps.first().objectId == leak.objectId
                            if (isSelfBitmap) {
                                sb.appendLine("                            <div><strong>此对象是Bitmap:</strong></div>")
                            } else {
                                sb.appendLine("                            <div><strong>包含的Bitmap (${leak.containedBitmaps.size}个):</strong></div>")
                            }

                            leak.containedBitmaps.take(5).forEach { bmp ->
                                val sizeMB = "%.2f".format(bmp.pixelCount * 4.0 / (1024 * 1024))
                                if (bmp.imageFilePath != null) {
                                    sb.appendLine("                            <div class=\"bitmap-item\">")
                                    sb.appendLine("                                <img src=\"${bmp.imageFilePath}\" class=\"bitmap-thumb\" alt=\"Bitmap\">")
                                    sb.appendLine("                                <span>${bmp.width}x${bmp.height} (${sizeMB}MB)</span>")
                                    sb.appendLine("                            </div>")
                                } else {
                                    sb.appendLine("                            <div class=\"bitmap-item\">")
                                    sb.appendLine("                                <span>${bmp.width}x${bmp.height} (${sizeMB}MB) [无图片]</span>")
                                    sb.appendLine("                            </div>")
                                }
                            }
                            if (leak.containedBitmaps.size > 5) {
                                sb.appendLine("                            <div style=\"font-size: 11px; color: #888;\">... 还有 ${leak.containedBitmaps.size - 5} 个Bitmap</div>")
                            }
                            sb.appendLine("                        </div>")
                        }

                        if (leak.referenceChain.isNotEmpty()) {
                            sb.appendLine("                        <div class=\"reference-chain\">")
                            sb.appendLine("                            <div><strong>引用链:</strong></div>")
                            leak.referenceChain.take(8).forEach { ref ->
                                sb.appendLine("                            <div class=\"ref\"><span class=\"arrow\">→</span>${ref.fullName}</div>")
                            }
                            if (leak.referenceChain.size > 8) {
                                sb.appendLine("                            <div class=\"ref\">... 还有 ${leak.referenceChain.size - 8} 层</div>")
                            }
                            sb.appendLine("                        </div>")
                        }
                        sb.appendLine("                    </div>")
                        sb.appendLine("                </div>")
                    }
                    if (objects.size > 10) {
                        sb.appendLine("                <div style=\"color: #666; padding: 10px;\">... 还有 ${objects.size - 10} 个</div>")
                    }
                    sb.appendLine("            </div>")
                }

                sb.appendLine("        </div>")
            } else {
                // 无泄露时显示更友好的界面
                sb.appendLine("        <div class=\"section\">")
                sb.appendLine("            <div class=\"no-leak\">")
                sb.appendLine("                <div class=\"icon\">✅</div>")
                sb.appendLine("                <h2>未发现内存泄露</h2>")
                sb.appendLine("                <p>在此次分析中未检测到Activity或Fragment的内存泄露。</p>")
                sb.appendLine("                <div class=\"info-box\" style=\"display: inline-block; margin-top: 20px;\">")
                sb.appendLine("                    <div class=\"title\">📁 查看Bitmap分析</div>")
                sb.appendLine("                    <a href=\"bitmap_analysis.html\" class=\"link\">bitmap_analysis.html</a> - 包含所有大Bitmap和重复Bitmap的分析")
                sb.appendLine("                </div>")
                sb.appendLine("            </div>")
                sb.appendLine("        </div>")
            }

            sb.appendLine("    </div>")
            sb.appendLine("</body>")
            sb.appendLine("</html>")

            return sb.toString()
        }
    }

    /**
     * 泄漏对象
     */
    data class LeakingObject(
        val className: String,
        val objectId: Long,
        val size: Long,
        val leakReason: String,
        val referenceChain: List<ReferenceNode>,
        val gcRoot: String,
        val signature: String,
        val instanceCount: Int,
        val containedBitmaps: List<ContainedBitmap> = emptyList()  // 此泄露对象中包含的Bitmap
    )

    /**
     * 引用节点
     */
    data class ReferenceNode(
        val className: String,
        val referenceName: String,
        val referenceType: String,
        val declaredClass: String? = null
    ) {
        val fullName: String
            get() = if (referenceName.startsWith("[")) {
                className
            } else {
                "$className.$referenceName"
            }
    }

    /**
     * 泄露对象中包含的Bitmap
     */
    data class ContainedBitmap(
        val objectId: Long,
        val width: Int,
        val height: Int,
        val pixelCount: Int,
        val imageFilePath: String?  // 相对于报告目录的图片路径
    )

    /**
     * 用于追踪的Bitmap信息
     */
    data class BitmapInLeak(
        val objectId: Long,
        val width: Int,
        val height: Int,
        val pixelCount: Int,
        var hasImageFile: Boolean,
        var imageFilePath: String?
    )
}
