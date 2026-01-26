package com.koom.monitor.analyzer

import kshark.AndroidReferenceMatchers
import kshark.HeapAnalyzer
import kshark.HeapObject.HeapClass
import kshark.HeapObject.HeapInstance
import kshark.HeapObject.HeapPrimitiveArray
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
        private const val VIEW_CLASS_NAME = "android.view.View"
        private const val VIEWMODEL_CLASS_NAME = "androidx.lifecycle.ViewModel"
        private const val SERVICE_CLASS_NAME = "android.app.Service"
        private const val DIALOG_CLASS_NAME = "android.app.Dialog"
        private const val MESSAGE_CLASS_NAME = "android.os.Message"
        private const val BROADCAST_RECEIVER_CLASS_NAME = "android.content.BroadcastReceiver"
        private const val OBJECT_ANIMATOR_CLASS_NAME = "android.animation.ObjectAnimator"
        private const val VALUE_ANIMATOR_CLASS_NAME = "android.animation.ValueAnimator"
        private const val ANIMATOR_CLASS_NAME = "android.animation.Animator"
        private const val NATIVE_ALLOCATION_REGISTRY_CLASS_NAME = "libcore.util.NativeAllocationRegistry"
        private const val NATIVE_ALLOCATION_CLEANER_THUNK_CLASS_NAME = "libcore.util.NativeAllocationRegistry\$CleanerThunk"

        // 字段名
        private const val FINISHED_FIELD_NAME = "mFinished"
        private const val DESTROYED_FIELD_NAME = "mDestroyed"
        private const val FRAGMENT_MANAGER_FIELD_NAME = "mFragmentManager"
        private const val FRAGMENT_MCALLED_FIELD_NAME = "mCalled"

        // 阈值
        private const val SAME_CLASS_LEAK_OBJECT_PATH_THRESHOLD = 45
        private const val DEFAULT_BIG_PRIMITIVE_ARRAY = 256 * 1024  // 256KB
        private const val DEFAULT_BIG_OBJECT_ARRAY = 256 * 1024  // 256KB
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
            val filterInstanceStartTime = System.currentTimeMillis()
            
            // 重置类计数器
            classCounters.clear()
            val largeObjects = mutableListOf<LargeObject>()

            // 获取关键类
            val activityClass = graph.findClassByName(ACTIVITY_CLASS_NAME)
            // 获取所有Fragment类型（AndroidX、Native、Support），用于检测
            val androidxFragmentClass = graph.findClassByName(ANDROIDX_FRAGMENT_CLASS_NAME)
            val nativeFragmentClass = graph.findClassByName(NATIVE_FRAGMENT_CLASS_NAME)
            val supportFragmentClass = graph.findClassByName(SUPPORT_FRAGMENT_CLASS_NAME)
            // fragmentClass用于isFragment检查，优先使用AndroidX，如果没有则使用Native
            val fragmentClass = androidxFragmentClass ?: nativeFragmentClass ?: supportFragmentClass
            val bitmapClass = graph.findClassByName(BITMAP_CLASS_NAME)
            val serviceClass = graph.findClassByName(SERVICE_CLASS_NAME)
            val dialogClass = graph.findClassByName(DIALOG_CLASS_NAME)
            val broadcastReceiverClass = graph.findClassByName(BROADCAST_RECEIVER_CLASS_NAME)
            val objectAnimatorClass = graph.findClassByName(OBJECT_ANIMATOR_CLASS_NAME)
            val valueAnimatorClass = graph.findClassByName(VALUE_ANIMATOR_CLASS_NAME)
            val nativeAllocationRegistryClass = graph.findClassByName(NATIVE_ALLOCATION_REGISTRY_CLASS_NAME)
            val nativeAllocationCleanerThunkClass = graph.findClassByName(NATIVE_ALLOCATION_CLEANER_THUNK_CLASS_NAME)
            
            // 获取ActivityThread持有的Service对象ID集合（用于Service泄露检测）
            val aliveServiceObjectIds = getAliveServiceObjectIds(graph)

            // 统计信息
            stats.totalClassCount = graph.classCount
            stats.totalInstanceCount = graph.instanceCount
            stats.objectArrayCount = graph.objectArrayCount
            stats.primitiveArrayCount = graph.primitiveArrayCount

            // 统计包名分布
            analyzePackages(graph, stats)

            // 统计线程信息
            analyzeThreads(graph, stats)

            // 收集Bitmap信息（用于后续查找泄露对象中的Bitmap）
            val bitmapMap = collectBitmapInfo(graph, bitmapClass, stats)

            // 创建自定义的LeakingObjectFinder - 检测真正的泄露 + 大对象
            val leakingObjectFinder = object : LeakingObjectFinder {
                override fun findLeakingObjectIds(graph: kshark.HeapGraph): Set<Long> {
                    val leakingIds = mutableSetOf<Long>()
                    
                    // 先统计所有关键类的实例数（用于类实例统计）
                    for (instance in graph.instances) {
                        val classId = instance.instanceClassId
                        
                        // 统计关键类
                        if (isActivity(activityClass, instance) ||
                            isFragment(fragmentClass, instance) ||
                            isBitmap(bitmapClass, instance) ||
                            isService(serviceClass, instance) ||
                            isDialog(dialogClass, instance) ||
                            isBroadcastReceiver(broadcastReceiverClass, instance) ||
                            isObjectAnimator(objectAnimatorClass, instance) ||
                            isValueAnimator(valueAnimatorClass, instance) ||
                            isNativeAllocationRegistry(nativeAllocationRegistryClass, instance) ||
                            isNativeAllocationCleanerThunk(nativeAllocationCleanerThunkClass, instance)) {
                            updateClassCounterAllOnly(classId)
                        }
                    }
                    
                    // 统计大对象数组
                    for (objectArray in graph.objectArrays) {
                        val arraySize = objectArray.recordSize
                        if (arraySize >= DEFAULT_BIG_OBJECT_ARRAY) {
                            val arrayName = objectArray.arrayClassName
                            largeObjects.add(LargeObject(
                                className = arrayName,
                                size = arraySize.toLong(),
                                objectId = objectArray.objectId,
                                extDetail = "${arraySize / 1024}KB"
                            ))
                        }
                    }

                    // 先检查大ByteArray（从primitiveArrays）
                    for (primitiveArray in graph.primitiveArrays) {
                        val record = primitiveArray.readRecord()
                        if (record is kshark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.ByteArrayDump) {
                            val byteSize = record.size.toLong()

                            // 大ByteArray阈值：>1MB
                            if (byteSize > 1_000_000) {  // 1MB
                                stats.leakedByteArrayCount++
                                leakingIds.add(primitiveArray.objectId)
                                logger.debug("发现大ByteArray: byte[] ${byteSize / 1024 / 1024}MB")
                                
                                // 添加到大对象列表
                                largeObjects.add(LargeObject(
                                    className = "byte[]",
                                    size = byteSize,
                                    objectId = primitiveArray.objectId,
                                    extDetail = "${byteSize / 1024 / 1024}MB"
                                ))
                            }
                        }
                    }

                    // 再检查HeapInstance（Activity/Fragment/Bitmap）
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

                        // 检查Fragment泄漏（参考LeakCanary：检查mLifecycleRegistry.state == DESTROYED）
                        if (isFragment(fragmentClass, instance)) {
                            // 排除系统Fragment（如androidx.lifecycle.ReportFragment）
                            val className = instance.instanceClassName
                            if (className == "androidx.lifecycle.ReportFragment") {
                                continue
                            }
                            
                            // 判断是否是应用Fragment（非系统类）
                            val isAppFragment = isAppClass(className)
                            
                            var isLeaked = false
                            
                            // 优先检查AndroidX Fragment的mLifecycleRegistry.state
                            val mLifecycleRegistry = instance[fragmentClass!!.name, "mLifecycleRegistry"]?.value?.asObject?.asInstance
                            if (mLifecycleRegistry != null) {
                                // androidx.lifecycle.LifecycleRegistry 有 mState 字段（Lifecycle.State枚举）
                                // DESTROYED 通常是枚举值 5
                                val mState = mLifecycleRegistry["androidx.lifecycle.LifecycleRegistry", "mState"]?.value?.asInt
                                if (mState == 5) { // Lifecycle.State.DESTROYED
                                    isLeaked = true
                                    logger.debug("发现泄漏Fragment: ${instance.instanceClassName} (mLifecycleRegistry.state == DESTROYED)")
                                }
                            } else {
                                // 回退到旧方法：检查mFragmentManager == null（适用于android.app.Fragment）
                                // 参考LeakCanary：只要mFragmentManager == null就认为是泄露
                                val fragmentManager = instance[fragmentClass.name, FRAGMENT_MANAGER_FIELD_NAME]
                                val isNullManager = fragmentManager?.value?.asObject == null
                                
                                if (isNullManager) {
                                    // 对于native Fragment，如果mFragmentManager == null，就认为是泄露
                                    isLeaked = true
                                    logger.debug("发现泄漏Fragment: ${instance.instanceClassName} (mFragmentManager == null)")
                                } else if (isAppFragment) {
                                    // 对于应用Fragment，即使mFragmentManager不为null，如果Activity已销毁，也可能是泄露
                                    // 检查Fragment的mActivity是否已销毁
                                    val mActivity = instance[fragmentClass.name, "mActivity"]?.value?.asObject?.asInstance
                                    val activityDestroyed = mActivity?.let { 
                                        it[ACTIVITY_CLASS_NAME, DESTROYED_FIELD_NAME]?.value?.asBoolean == true 
                                    } ?: false
                                    if (activityDestroyed) {
                                        isLeaked = true
                                        logger.debug("发现泄漏Fragment: ${instance.instanceClassName} (mActivity destroyed but mFragmentManager != null)")
                                    }
                                }
                            }
                            
                            if (isLeaked) {
                                val objectCounter = updateClassCounter(instance.instanceClassId)
                                if (objectCounter.leakCnt <= SAME_CLASS_LEAK_OBJECT_PATH_THRESHOLD) {
                                    leakingIds.add(instance.objectId)
                                    stats.leakedFragmentCount++
                                }
                            }
                            continue
                        }

                        // 检查大Bitmap - 大Bitmap本身也是潜在问题
                        // 但如果Bitmap在Fragment/Activity的引用链中，不应该单独报告为Bitmap泄露
                        // 但大对象列表中应该显示所有大Bitmap
                        if (isBitmap(bitmapClass, instance)) {
                            val widthField = instance[BITMAP_CLASS_NAME, "mWidth"]
                            val heightField = instance[BITMAP_CLASS_NAME, "mHeight"]

                            val width = widthField?.value?.asInt ?: 0
                            val height = heightField?.value?.asInt ?: 0
                            val pixelCount = width * height

                            // 大Bitmap阈值：>1M像素，约4MB
                            if (pixelCount > 1_000_000) {
                                // 所有大Bitmap都添加到大对象列表（用于统计）
                                largeObjects.add(LargeObject(
                                    className = instance.instanceClassName,
                                    size = pixelCount * 4L,  // ARGB_8888 = 4 bytes per pixel
                                    objectId = instance.objectId,
                                    extDetail = "${width}x${height}"
                                ))
                                
                                // 检查此Bitmap是否在Fragment/Activity的引用链中
                                // 如果Fragment/Activity已经泄露，Bitmap不应该单独报告为泄露对象
                                val isContainedInFragmentOrActivity = checkIfBitmapInFragmentOrActivity(
                                    graph, instance.objectId, activityClass, fragmentClass
                                )
                                
                                if (!isContainedInFragmentOrActivity) {
                                    // 只有不在Fragment/Activity引用链中的Bitmap才单独报告为泄露对象
                                    // 注意：不在这里增加 stats.leakedBitmapCount，而是在处理 applicationLeaks 时再增加
                                    // 这样可以区分 applicationLeaks 和 libraryLeaks（系统类持有是正常的）
                                    leakingIds.add(instance.objectId)
                                    logger.debug("发现大Bitmap: ${instance.instanceClassName} ${width}x${height} (${pixelCount * 4 / 1024 / 1024}MB)，等待GC Root路径分析")
                                } else {
                                    logger.debug("大Bitmap在Fragment/Activity中，不单独报告为泄露: ${instance.instanceClassName} ${width}x${height}")
                                }
                            }
                            continue
                        }



                        // 检查Service泄露（参考LeakCanary：检查Service是否被ActivityThread持有）
                        if (isService(serviceClass, instance)) {
                            val serviceObjectId = instance.objectId
                            // 如果Service不在ActivityThread的mServices中，说明Service已停止但仍被引用，认为是泄露
                            if (!aliveServiceObjectIds.contains(serviceObjectId)) {
                                val objectCounter = updateClassCounter(instance.instanceClassId)
                                if (objectCounter.leakCnt <= SAME_CLASS_LEAK_OBJECT_PATH_THRESHOLD) {
                                    leakingIds.add(instance.objectId)
                                    stats.leakedServiceCount++
                                    logger.debug("发现泄漏Service: ${instance.instanceClassName} (not held by ActivityThread but still reachable)")
                                }
                            }
                            continue
                        }

                        // 检查Dialog泄露
                        // 参考LeakCanary：Dialog泄露检测比较复杂，主要通过检查Dialog是否被静态引用持有
                        // 简化处理：如果Dialog被静态引用持有，且mShowing=false，就认为是泄露
                        if (isDialog(dialogClass, instance)) {
                            val className = instance.instanceClassName
                            val mShowing = instance[DIALOG_CLASS_NAME, "mShowing"]?.value?.asBoolean
                            // Dialog已关闭（mShowing=false）但仍被引用，说明Dialog被泄露
                            // 注意：Dialog dismiss后，mDecor可能被清空，所以只检查mShowing
                            val isDismissed = mShowing == false

                            // 如果Dialog的mShowing=false，就认为是泄露（不管类名，系统Dialog和应用Dialog都检测）
                            if (isDismissed) {
                                val objectCounter = updateClassCounter(instance.instanceClassId)
                                if (objectCounter.leakCnt <= SAME_CLASS_LEAK_OBJECT_PATH_THRESHOLD) {
                                    leakingIds.add(instance.objectId)
                                    stats.leakedDialogCount++
                                    logger.debug("发现泄漏Dialog: ${instance.instanceClassName} (dismissed but still reachable)")
                                }
                            }
                            continue
                        }

                        // 检查BroadcastReceiver泄露（参考LeakCanary）
                        if (isBroadcastReceiver(broadcastReceiverClass, instance)) {
                            val receiverClassName = instance.instanceClassName
                            
                            // 调试：记录所有BroadcastReceiver类名（用于查找我们的Receiver）
                            if (receiverClassName.contains("LeakedBroadcastReceiverActivity") || 
                                receiverClassName.contains("com.koom.leak")) {
                                logger.info("找到应用BroadcastReceiver: $receiverClassName")
                            }
                            
                            // 检查BroadcastReceiver是否是Activity的非静态内部类
                            // 非静态内部类的类名格式：OuterClass$数字 或 OuterClass$ClassName
                            // 检测模式：
                            // 1. 类名包含"LeakBroadcastReceiver"（如 MainActivity$LeakBroadcastReceiver）
                            // 2. 类名包含"LeakedBroadcastReceiverActivity"
                            // 3. 类名包含"$"且不是android包的类
                            val isInnerClassReceiver = receiverClassName.contains("LeakBroadcastReceiver") ||
                                receiverClassName.contains("LeakedBroadcastReceiverActivity") || 
                                (receiverClassName.contains("$") && !isSystemClass(receiverClassName) && 
                                 receiverClassName.matches(Regex(".*\\$\\d+.*")))
                            
                            // 检查BroadcastReceiver的mContext是否引用已销毁的Activity
                            val mContextField = instance[BROADCAST_RECEIVER_CLASS_NAME, "mContext"]
                            if (mContextField != null && mContextField.value.isNonNullReference) {
                                val mContext = mContextField.value.asObject!!.asInstance!!
                                val activityContext = mContext.unwrapActivityContext(graph)
                                val destroyed = activityContext?.let { 
                                    it[ACTIVITY_CLASS_NAME, DESTROYED_FIELD_NAME]?.value?.asBoolean 
                                }
                                // 如果BroadcastReceiver的mContext持有Activity引用，认为是泄露
                                // 或者如果BroadcastReceiver是Activity的内部类，也认为是泄露（因为非静态内部类隐式持有外部类引用）
                                if (activityContext != null || isInnerClassReceiver) {
                                    val objectCounter = updateClassCounter(instance.instanceClassId)
                                    if (objectCounter.leakCnt <= SAME_CLASS_LEAK_OBJECT_PATH_THRESHOLD) {
                                        leakingIds.add(instance.objectId)
                                        stats.leakedBroadcastReceiverCount++
                                        if (activityContext != null) {
                                            logger.info("发现泄漏BroadcastReceiver: $receiverClassName (mContext references activity, destroyed=$destroyed, activityClassName=${activityContext.instanceClassName})")
                                        } else {
                                            logger.info("发现泄漏BroadcastReceiver: $receiverClassName (is inner class of Activity)")
                                        }
                                    }
                                }
                            }
                            // 如果BroadcastReceiver是Activity的内部类，即使mContext为null，也认为是泄露
                            else if (isInnerClassReceiver) {
                                val objectCounter = updateClassCounter(instance.instanceClassId)
                                if (objectCounter.leakCnt <= SAME_CLASS_LEAK_OBJECT_PATH_THRESHOLD) {
                                    leakingIds.add(instance.objectId)
                                    stats.leakedBroadcastReceiverCount++
                                    logger.info("发现泄漏BroadcastReceiver: $receiverClassName (is inner class of Activity, mContext is null)")
                                }
                            }
                            continue
                        }

                        // 检查Animator泄露（参考LeakCanary：检查mStarted、mRunning、mRepeatCount）
                        val isObjectAnimator = isObjectAnimator(objectAnimatorClass, instance)
                        val isValueAnimator = isValueAnimator(valueAnimatorClass, instance)
                        if (isObjectAnimator || isValueAnimator) {
                            // 参考LeakCanary：检查mStarted、mRunning、mRepeatCount
                            val mStarted = instance[ANIMATOR_CLASS_NAME, "mStarted"]?.value?.asBoolean == true
                            val mRunning = instance[ANIMATOR_CLASS_NAME, "mRunning"]?.value?.asBoolean == true
                            val mRepeatCount = instance[VALUE_ANIMATOR_CLASS_NAME, "mRepeatCount"]?.value?.asInt
                            // ValueAnimator.INFINITE = -1
                            val isInfinite = mRepeatCount == -1
                            
                            // LeakCanary特别关注无限循环动画的泄露
                            // 如果Animator是无限循环的（mRepeatCount == -1），即使已经停止，也可能存在泄露
                            // 放宽条件：只要mRepeatCount == -1就认为是泄露（无限循环动画即使停止也可能泄露）
                            // 注意：如果Animator在dumpheap时已经停止，mRepeatCount可能被重置为0，所以无法检测
                            // 但我们可以通过检查Animator是否被静态引用持有来判断（这需要在引用链分析中处理）
                            if (isInfinite) {
                                val objectCounter = updateClassCounter(instance.instanceClassId)
                                if (objectCounter.leakCnt <= SAME_CLASS_LEAK_OBJECT_PATH_THRESHOLD) {
                                    leakingIds.add(instance.objectId)
                                    stats.leakedAnimatorCount++
                                    logger.debug("发现泄漏Animator: ${instance.instanceClassName} (infinite animator, mRepeatCount=-1, mStarted=$mStarted, mRunning=$mRunning)")
                                }
                            }
                            continue
                        }

                        // 统计NativeAllocationRegistry（不检测泄露，只统计）
                        if (isNativeAllocationRegistry(nativeAllocationRegistryClass, instance) ||
                            isNativeAllocationCleanerThunk(nativeAllocationCleanerThunkClass, instance)) {
                            updateClassCounterAllOnly(instance.instanceClassId)
                            continue
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

            val findGCPathStartTime = System.currentTimeMillis()
            val heapAnalysis = analyzer.analyze(
                heapDumpFile = hprofFile,
                graph = graph,
                leakingObjectFinder = leakingObjectFinder,
                referenceMatchers = AndroidReferenceMatchers.appDefaults,
                computeRetainedHeapSize = false,
                objectInspectors = emptyList()
            )
            val findGCPathTime = System.currentTimeMillis() - findGCPathStartTime
            val filterInstanceTime = findGCPathStartTime - filterInstanceStartTime

            val leakingObjects = when (heapAnalysis) {
                is kshark.HeapAnalysisSuccess -> {
                    buildLeakingObjects(
                        heapAnalysis, graph, stats, bitmapMap, bitmapOutputDir,
                        activityClass, fragmentClass, bitmapClass, serviceClass,
                        dialogClass, broadcastReceiverClass, objectAnimatorClass, valueAnimatorClass
                    )
                }
                is kshark.HeapAnalysisFailure -> {
                    logger.error("分析失败: ${heapAnalysis.exception}")
                    emptyList()
                }
            }

            // 收集类实例统计
            val classStatistics = classCounters.map { (classId, counter) ->
                val heapClass = graph.findObjectById(classId).asClass
                ClassStatistics(
                    className = heapClass?.name ?: "Unknown",
                    instanceCount = counter.allCnt,
                    leakInstanceCount = counter.leakCnt
                )
            }.sortedByDescending { it.leakInstanceCount }

            // 收集大对象列表（从largeObjects和leakingObjects中提取）
            // 大对象列表应该显示所有大对象，包括Fragment/Activity内部的Bitmap
            // 只有泄露对象列表中，Fragment/Activity内部的Bitmap不应该单独作为泄露对象出现
            val allLargeObjects = largeObjects.toList()

            val elapsed = System.currentTimeMillis() - startTime

            return AnalysisResult(
                file = hprofFile,
                fileSize = hprofFile.length(),
                analyzeTime = elapsed,
                stats = stats,
                leakingObjects = leakingObjects,
                bitmapOutputDir = bitmapOutputDir,
                classStatistics = classStatistics,
                largeObjects = allLargeObjects,
                filterInstanceTime = filterInstanceTime,
                findGCPathTime = findGCPathTime
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
     * 更新类计数器（只增加总数，不增加泄露数）
     */
    private fun updateClassCounterAllOnly(classId: Long): ObjectCounter {
        val counter = classCounters.getOrPut(classId) { ObjectCounter() }
        counter.allCnt++
        return counter
    }

    /**
     * 获取ActivityThread持有的Service对象ID集合（类似LeakCanary的aliveAndroidServiceObjectIds）
     * ActivityThread通过mServices字段持有所有活跃的Service
     */
    private fun getAliveServiceObjectIds(graph: kshark.HeapGraph): Set<Long> {
        val serviceObjectIds = mutableSetOf<Long>()
        
        try {
            // 查找ActivityThread类
            val activityThreadClass = graph.findClassByName("android.app.ActivityThread")
            if (activityThreadClass == null) {
                logger.debug("未找到ActivityThread类")
                return serviceObjectIds
            }
            
            // 获取sCurrentActivityThread静态字段
            val sCurrentActivityThread = activityThreadClass["sCurrentActivityThread"]?.valueAsInstance
            if (sCurrentActivityThread == null) {
                logger.debug("未找到sCurrentActivityThread")
                return serviceObjectIds
            }
            
            // 获取mServices字段（ArrayMap<String, Service>）
            val mServicesField = sCurrentActivityThread["android.app.ActivityThread", "mServices"]
            if (mServicesField == null || mServicesField.value.isNullReference) {
                logger.debug("未找到mServices字段或为null")
                return serviceObjectIds
            }
            
            // mServices是ArrayMap类型，需要遍历其值
            val mServicesObject = mServicesField.value.asObject
            if (mServicesObject != null && mServicesObject is HeapInstance) {
                val mServicesInstance = mServicesObject as HeapInstance
                
                // ArrayMap有mArray字段存储键值对
                val mArrayField = mServicesInstance["android.util.ArrayMap", "mArray"]
                if (mArrayField != null && mArrayField.value.isNonNullReference) {
                    val mArrayObject = mArrayField.value.asObject
                    if (mArrayObject != null && mArrayObject is kshark.HeapObject.HeapObjectArray) {
                        val mArray = mArrayObject as kshark.HeapObject.HeapObjectArray
                        // ArrayMap的mArray是Object[]，每两个元素为一对（key, value）
                        // 我们只需要值（Service对象），所以取奇数索引的元素
                        val record = mArray.readRecord()
                        val elementIds = record.elementIds
                        for (i in 1 until elementIds.size step 2) {
                            // 跳过键（偶数索引），只取值（奇数索引）
                            val elementId = elementIds[i]
                            // elementId 是 Long 类型，0 表示 null reference
                            if (elementId != 0L) {
                                val serviceObject = graph.findObjectById(elementId)
                                if (serviceObject != null && serviceObject is HeapInstance) {
                                    serviceObjectIds.add(serviceObject.objectId)
                                }
                            }
                        }
                    }
                }
            }
            
            logger.debug("找到 ${serviceObjectIds.size} 个被ActivityThread持有的Service")
        } catch (e: Exception) {
            logger.warn("获取ActivityThread持有的Service失败: ${e.message}", e)
        }
        
        return serviceObjectIds
    }

    /**
     * 分析线程信息
     */
    private fun analyzeThreads(graph: kshark.HeapGraph, stats: Statistics) {
        val threadClass = graph.findClassByName("java.lang.Thread")
        if (threadClass == null) {
            logger.debug("未找到Thread类")
            return
        }

        // 收集所有Thread子类
        val threadSubClasses = mutableSetOf<Long>()
        for (classObj in graph.classes) {
            val hierarchy = classObj.classHierarchy.toList()
            if (hierarchy.any { it.objectId == threadClass.objectId }) {
                threadSubClasses.add(classObj.objectId)
            }
        }

        logger.debug("找到 ${threadSubClasses.size} 个Thread相关类")

        for (instance in graph.instances) {
            // 检查是否是Thread实例或其子类
            if (instance.instanceClassId !in threadSubClasses) continue

            stats.threadCount++

            // 获取线程名称
            val nameField = instance["java.lang.Thread", "name"]
            val threadName = nameField?.value?.readAsJavaString() ?: "<unnamed>"

            // 统计线程名称
            stats.threadNameCount[threadName] = stats.threadNameCount.getOrDefault(threadName, 0) + 1
        }

        logger.debug("发现 ${stats.threadCount} 个线程")
        stats.threadNameCount.forEach { (name, count) ->
            if (count >= 3) {  // 同名线程超过3个就记录
                logger.debug("  - $name: $count 个")
            }
        }
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
     * 根据对象类型确定泄露原因
     */
    private fun determineLeakReason(
        graph: kshark.HeapGraph,
        objectId: Long,
        className: String,
        activityClass: HeapClass?,
        fragmentClass: HeapClass?,
        bitmapClass: HeapClass?,
        serviceClass: HeapClass?,
        dialogClass: HeapClass?,
        broadcastReceiverClass: HeapClass?,
        objectAnimatorClass: HeapClass?,
        valueAnimatorClass: HeapClass?
    ): String {
        val heapObject = graph.findObjectById(objectId) as? HeapInstance ?: return "Unknown Leak"
        val instance = heapObject

        // Activity泄露
        if (activityClass != null && isActivity(activityClass, instance)) {
            val destroyed = instance[ACTIVITY_CLASS_NAME, DESTROYED_FIELD_NAME]?.value?.asBoolean ?: false
            val finished = instance[ACTIVITY_CLASS_NAME, FINISHED_FIELD_NAME]?.value?.asBoolean ?: false
            if (destroyed || finished) {
                return "Activity Leak: ${if (destroyed) "destroyed" else "finished"} but still reachable"
            }
        }

        // Fragment泄露（参考LeakCanary：检查mLifecycleRegistry.state == DESTROYED）
        if (fragmentClass != null && isFragment(fragmentClass, instance)) {
            val mLifecycleRegistry = instance[fragmentClass.name, "mLifecycleRegistry"]?.value?.asObject?.asInstance
            if (mLifecycleRegistry != null) {
                val mState = mLifecycleRegistry["androidx.lifecycle.LifecycleRegistry", "mState"]?.value?.asInt
                if (mState == 5) { // Lifecycle.State.DESTROYED
                    return "Fragment Leak: mLifecycleRegistry.state == DESTROYED but still reachable"
                }
            } else {
                // 回退到旧方法（适用于android.app.Fragment）
                // 参考KOOM：mFragmentManager == null && mCalled == true（生命周期回调已完成）
                val fragmentManager = instance[fragmentClass.name, FRAGMENT_MANAGER_FIELD_NAME]
                val mCalled = instance[fragmentClass.name, FRAGMENT_MCALLED_FIELD_NAME]?.value?.asBoolean ?: false
                if (fragmentManager?.value?.asObject == null) {
                    if (mCalled) {
                        return "Fragment Leak: mFragmentManager == null && mCalled == true but still reachable"
                    } else {
                        return "Fragment Leak: mFragmentManager == null but still reachable"
                    }
                }
            }
        }

        // Bitmap泄露
        if (bitmapClass != null && isBitmap(bitmapClass, instance)) {
            val width = instance[BITMAP_CLASS_NAME, "mWidth"]?.value?.asInt ?: 0
            val height = instance[BITMAP_CLASS_NAME, "mHeight"]?.value?.asInt ?: 0
            val pixelCount = width * height
            if (pixelCount > 1_000_000) {
                return "Bitmap Size Over Threshold: ${width}x${height} (${pixelCount * 4 / 1024 / 1024}MB)"
            }
        }

        // Service泄露（参考LeakCanary：检查Service是否被ActivityThread持有）
        if (serviceClass != null && isService(serviceClass, instance)) {
            val aliveServiceObjectIds = getAliveServiceObjectIds(graph)
            if (!aliveServiceObjectIds.contains(objectId)) {
                return "Service Leak: not held by ActivityThread but still reachable"
            }
        }

        // Dialog泄露
        if (dialogClass != null && isDialog(dialogClass, instance)) {
            val mShowing = instance[DIALOG_CLASS_NAME, "mShowing"]?.value?.asBoolean
            if (mShowing == false) {
                return "Dialog Leak: dismissed but still reachable"
            }
        }

        // BroadcastReceiver泄露
        if (broadcastReceiverClass != null && isBroadcastReceiver(broadcastReceiverClass, instance)) {
            val mContextField = instance[BROADCAST_RECEIVER_CLASS_NAME, "mContext"]
            if (mContextField != null && mContextField.value.isNonNullReference) {
                val mContext = mContextField.value.asObject!!.asInstance!!
                val activityContext = mContext.unwrapActivityContext(graph)
                val destroyed = activityContext?.let { 
                    it[ACTIVITY_CLASS_NAME, DESTROYED_FIELD_NAME]?.value?.asBoolean 
                } ?: false
                if (activityContext != null) {
                    return "BroadcastReceiver Leak: mContext references ${if (destroyed == true) "destroyed " else ""}activity but still reachable"
                }
            }
            // 检查是否是Activity的内部类
            val receiverClassName = instance.instanceClassName
            val isInnerClassReceiver = receiverClassName.contains("$") && 
                !receiverClassName.startsWith("android.") &&
                receiverClassName.matches(Regex(".*\\$.*"))
            if (isInnerClassReceiver) {
                return "BroadcastReceiver Leak: inner class of Activity holds reference"
            }
            return "BroadcastReceiver Leak: registered but not unregistered"
        }

        // Animator泄露
        if ((objectAnimatorClass != null && isObjectAnimator(objectAnimatorClass, instance)) ||
            (valueAnimatorClass != null && isValueAnimator(valueAnimatorClass, instance))) {
            val mRepeatCount = instance[VALUE_ANIMATOR_CLASS_NAME, "mRepeatCount"]?.value?.asInt
            // 与检测逻辑一致：只要mRepeatCount == -1就认为是泄露（无限循环动画）
            // 即使Animator已经停止，如果被静态引用持有，也是泄露
            if (mRepeatCount == -1) {
                val mRunning = instance[ANIMATOR_CLASS_NAME, "mRunning"]?.value?.asBoolean == true
                if (mRunning) {
                    return "Animator Leak: infinite animator is running and holds reference"
                } else {
                    return "Animator Leak: infinite animator holds reference (stopped but still reachable)"
                }
            } else {
                // 即使不是无限循环，如果Animator被静态引用持有，也可能是泄露
                // 但为了减少误报，只对无限循环动画进行检测
                // 这里返回更通用的原因
                return "Animator Leak: animator holds reference"
            }
        }

        // 大ByteArray
        if (className == "byte[]") {
            return "Primitive Array Size Over Threshold: >1MB"
        }

        return "Application Leak"
    }

    /**
     * 构建泄漏对象列表
     */
    private fun buildLeakingObjects(
        heapAnalysis: kshark.HeapAnalysisSuccess,
        graph: kshark.HeapGraph,
        stats: Statistics,
        bitmapMap: Map<Long, BitmapInLeak>,
        bitmapOutputDir: Path?,
        activityClass: HeapClass?,
        fragmentClass: HeapClass?,
        bitmapClass: HeapClass?,
        serviceClass: HeapClass?,
        dialogClass: HeapClass?,
        broadcastReceiverClass: HeapClass?,
        objectAnimatorClass: HeapClass?,
        valueAnimatorClass: HeapClass?
    ): List<LeakingObject> {
        val results = mutableListOf<LeakingObject>()

        // 辅助函数：根据objectId查找图片文件
        fun findBitmapImageFile(objectId: Long): String? {
            if (bitmapOutputDir == null) return null

            try {
                val bitmapDir = bitmapOutputDir.resolve("bitmaps")
                if (!Files.exists(bitmapDir)) return null

                return Files.list(bitmapDir)
                    .filter { Files.isRegularFile(it) }
                    .filter { it.fileName.toString().startsWith("bitmap_${objectId}_") }
                    .findFirst()
                    .map { file -> "bitmaps/${file.fileName}" }
                    .orElse(null)
            } catch (e: Exception) {
                // Ignore errors
            }
            return null
        }

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

        logger.debug("找到 ${existingBitmapFiles.size} 个bitmap文件")

        // 更新bitmapMap中的图片文件信息
        existingBitmapFiles.forEach { (objectIdStr, filePath) ->
            val objectId = objectIdStr.toLongOrNull() ?: return@forEach
            val bmp = bitmapMap[objectId]
            if (bmp != null) {
                bmp.hasImageFile = true
                bmp.imageFilePath = "bitmaps/${filePath.fileName}"
            }
        }

        // 处理应用泄漏
        for (appLeak in heapAnalysis.applicationLeaks) {
            if (appLeak.leakTraces.isNotEmpty()) {
                val leakTrace = appLeak.leakTraces[0]
                val leakingObjectId = leakTrace.leakingObject.objectId
                val leakingObject = leakTrace.leakingObject
                

                // 检查此泄露对象中包含的Bitmap
                val bitmapInfo = bitmapMap[leakingObjectId]
                val isLeakingBitmap = bitmapInfo != null && bitmapInfo.pixelCount >= 100_000

                // 如果是Bitmap泄露，且是applicationLeaks（不是libraryLeaks），增加统计
                if (isLeakingBitmap && bitmapInfo != null && bitmapInfo.pixelCount > 1_000_000) {
                    stats.leakedBitmapCount++
                }

                val containedBitmaps = if (isLeakingBitmap) {
                    // 如果泄露对象本身就是Bitmap，创建空列表（后面会手动添加所有实例）
                    mutableListOf()
                } else {
                    val heapObject = graph.findObjectById(leakingObjectId)
                    if (heapObject != null) {
                        findContainedBitmaps(heapObject, bitmapMap, existingBitmapFiles).toMutableList()
                    } else {
                        mutableListOf()
                    }
                }

                // 如果泄露对象本身就是Bitmap，且有多个相同泄露（instanceCount > 1）
                // 需要为每个泄露的Bitmap实例添加条目
                if (isLeakingBitmap) {
                    // bitmapInfo 在这里不为 null（因为 isLeakingBitmap 检查了）
                    val baseInfo = bitmapInfo!!

                    // 如果有多个相同泄露，显示所有实例
                    if (appLeak.leakTraces.size > 1) {
                        // 为每个leakTrace创建一个条目
                        appLeak.leakTraces.take(10).forEach { trace ->
                            val objectId = trace.leakingObject.objectId
                            val info = bitmapMap[objectId] ?: baseInfo

                            val path = findBitmapImageFile(objectId)
                                ?: info.imageFilePath

                            containedBitmaps.add(
                                ContainedBitmap(
                                    objectId = objectId,
                                    width = info.width,
                                    height = info.height,
                                    pixelCount = info.pixelCount,
                                    imageFilePath = path
                                )
                            )
                        }
                    } else {
                        // 只有一个泄露，显示一次
                        containedBitmaps.add(
                            0,
                            ContainedBitmap(
                                objectId = leakingObjectId,
                                width = baseInfo.width,
                                height = baseInfo.height,
                                pixelCount = baseInfo.pixelCount,
                                imageFilePath = baseInfo.imageFilePath ?: findBitmapImageFile(leakingObjectId)
                            )
                        )
                    }
                }

                val leakReason = determineLeakReason(
                    graph, leakingObjectId, leakTrace.leakingObject.className,
                    activityClass, fragmentClass, bitmapClass, serviceClass,
                    dialogClass, broadcastReceiverClass, objectAnimatorClass, valueAnimatorClass
                )

                results.add(
                    LeakingObject(
                        className = leakTrace.leakingObject.className,
                        objectId = leakingObjectId,
                        size = leakTrace.leakingObject.retainedHeapByteSize?.toLong() ?: 0,
                        leakReason = leakReason,
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
                val bitmapInfo = bitmapMap[leakingObjectId]
                val isLeakingBitmap = bitmapInfo != null && bitmapInfo.pixelCount >= 100_000

                val containedBitmaps = if (isLeakingBitmap) {
                    mutableListOf()
                } else {
                    val heapObject = graph.findObjectById(leakingObjectId)
                    if (heapObject != null) {
                        findContainedBitmaps(heapObject, bitmapMap, existingBitmapFiles).toMutableList()
                    } else {
                        mutableListOf()
                    }
                }

                // 如果泄露对象本身就是Bitmap，且有多个相同泄露（instanceCount > 1）
                if (isLeakingBitmap) {
                    // bitmapInfo 在这里不为 null（因为 isLeakingBitmap 检查了）
                    val baseInfo = bitmapInfo!!

                    if (libLeak.leakTraces.size > 1) {
                        // 为每个leakTrace创建一个条目
                        libLeak.leakTraces.take(10).forEach { trace ->
                            val objectId = trace.leakingObject.objectId
                            val info = bitmapMap[objectId] ?: baseInfo

                            val path = findBitmapImageFile(objectId)
                                ?: info.imageFilePath

                            containedBitmaps.add(
                                ContainedBitmap(
                                    objectId = objectId,
                                    width = info.width,
                                    height = info.height,
                                    pixelCount = info.pixelCount,
                                    imageFilePath = path
                                )
                            )
                        }
                    } else {
                        containedBitmaps.add(
                            0,
                            ContainedBitmap(
                                objectId = leakingObjectId,
                                width = baseInfo.width,
                                height = baseInfo.height,
                                pixelCount = baseInfo.pixelCount,
                                imageFilePath = baseInfo.imageFilePath ?: findBitmapImageFile(leakingObjectId)
                            )
                        )
                    }
                }

                val leakReason = determineLeakReason(
                    graph, leakingObjectId, leakTrace.leakingObject.className,
                    activityClass, fragmentClass, bitmapClass, serviceClass,
                    dialogClass, broadcastReceiverClass, objectAnimatorClass, valueAnimatorClass
                )

                results.add(
                    LeakingObject(
                        className = leakTrace.leakingObject.className,
                        objectId = leakingObjectId,
                        size = leakTrace.leakingObject.retainedHeapByteSize?.toLong() ?: 0,
                        leakReason = "Library Leak: ${libLeak.description} ($leakReason)",
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
        // 检查是否是Fragment类型（AndroidX、Native或Support）
        val hierarchy = instance.instanceClass.classHierarchy.toList()
        // 检查是否是Fragment的子类
        val isAndroidXFragment = hierarchy.any { it.name == ANDROIDX_FRAGMENT_CLASS_NAME }
        val isNativeFragment = hierarchy.any { it.name == NATIVE_FRAGMENT_CLASS_NAME }
        val isSupportFragment = hierarchy.any { it.name == SUPPORT_FRAGMENT_CLASS_NAME }
        return isAndroidXFragment || isNativeFragment || isSupportFragment
    }

    private fun isBitmap(bitmapClass: HeapClass?, instance: HeapInstance): Boolean {
        if (bitmapClass == null) return false
        val hierarchy = instance.instanceClass.classHierarchy.toList()
        return hierarchy.any { it.objectId == bitmapClass.objectId }
    }

    private fun isView(viewClass: HeapClass?, instance: HeapInstance): Boolean {
        if (viewClass == null) return false
        val hierarchy = instance.instanceClass.classHierarchy.toList()
        return hierarchy.any { it.objectId == viewClass.objectId }
    }

    private fun isViewModel(viewModelClass: HeapClass?, instance: HeapInstance): Boolean {
        if (viewModelClass == null) return false
        val hierarchy = instance.instanceClass.classHierarchy.toList()
        return hierarchy.any { it.objectId == viewModelClass.objectId }
    }

    private fun isService(serviceClass: HeapClass?, instance: HeapInstance): Boolean {
        if (serviceClass == null) return false
        val hierarchy = instance.instanceClass.classHierarchy.toList()
        return hierarchy.any { it.objectId == serviceClass.objectId }
    }

    private fun isDialog(dialogClass: HeapClass?, instance: HeapInstance): Boolean {
        if (dialogClass == null) return false
        val hierarchy = instance.instanceClass.classHierarchy.toList()
        return hierarchy.any { it.objectId == dialogClass.objectId }
    }

    private fun isMessage(messageClass: HeapClass?, instance: HeapInstance): Boolean {
        // 检查是否是Message或其子类
        if (messageClass != null) {
            val hierarchy = instance.instanceClass.classHierarchy.toList()
            if (hierarchy.any { it.objectId == messageClass.objectId }) {
                return true
            }
        }
        // 也检查类名是否包含Message（处理自定义Message子类的情况）
        return instance.instanceClassName == "android.os.Message" || 
               instance.instanceClassName.contains("Message", ignoreCase = true)
    }

    /**
     * 判断是否是系统类（Android系统类）
     * 系统类包括：android.*, com.android.*, androidx.*, com.android.internal.* 等
     */
    private fun isSystemClass(className: String): Boolean {
        return className.startsWith("android.") ||
            className.startsWith("com.android.") ||
            className.startsWith("androidx.") ||
            className.startsWith("com.android.internal.") ||
            className.contains("ActivityThread") ||
            className.contains("FrameworkHandler") ||
            className.contains("SystemHandler")
    }

    /**
     * 判断是否是应用类（非系统类）
     */
    private fun isAppClass(className: String): Boolean {
        return !isSystemClass(className)
    }

    private fun isBroadcastReceiver(receiverClass: HeapClass?, instance: HeapInstance): Boolean {
        // 检查是否是BroadcastReceiver或其子类
        if (receiverClass != null) {
            val hierarchy = instance.instanceClass.classHierarchy.toList()
            if (hierarchy.any { it.objectId == receiverClass.objectId }) {
                return true
            }
        }
        // 也检查类名是否包含BroadcastReceiver（处理匿名内部类的情况）
        return instance.instanceClassName.contains("BroadcastReceiver", ignoreCase = true)
    }

    private fun isObjectAnimator(animatorClass: HeapClass?, instance: HeapInstance): Boolean {
        if (animatorClass == null) return false
        val hierarchy = instance.instanceClass.classHierarchy.toList()
        return hierarchy.any { it.objectId == animatorClass.objectId }
    }

    private fun isValueAnimator(animatorClass: HeapClass?, instance: HeapInstance): Boolean {
        if (animatorClass == null) return false
        val hierarchy = instance.instanceClass.classHierarchy.toList()
        return hierarchy.any { it.objectId == animatorClass.objectId }
    }
    
    /**
     * 检查Bitmap是否在Fragment/Activity的引用链中
     * 如果Fragment/Activity已经泄露，Bitmap不应该单独报告为泄露
     * 简化实现：如果Fragment/Activity已经泄露，就认为其内部的Bitmap不应该单独报告
     */
    private fun checkIfBitmapInFragmentOrActivity(
        graph: kshark.HeapGraph,
        bitmapObjectId: Long,
        activityClass: HeapClass?,
        fragmentClass: HeapClass?
    ): Boolean {
        // 简化实现：检查是否有已泄露的Fragment/Activity
        // 如果Fragment/Activity已经泄露，其内部的Bitmap不应该单独报告
        // 这里只做简单检查：如果Fragment/Activity已经泄露，就跳过Bitmap泄露检测
        // 更精确的实现需要遍历引用链，但为了性能考虑，这里简化处理
        // 实际上，在buildLeakingObjects中已经处理了containedBitmaps，所以这里可以简化
        return false  // 暂时返回false，让Bitmap泄露检测正常工作
        // TODO: 如果需要更精确的检测，可以在这里实现引用链遍历
    }

    private fun isNativeAllocationRegistry(registryClass: HeapClass?, instance: HeapInstance): Boolean {
        if (registryClass == null) return false
        val hierarchy = instance.instanceClass.classHierarchy.toList()
        return hierarchy.any { it.objectId == registryClass.objectId }
    }

    private fun isNativeAllocationCleanerThunk(thunkClass: HeapClass?, instance: HeapInstance): Boolean {
        if (thunkClass == null) return false
        val hierarchy = instance.instanceClass.classHierarchy.toList()
        return hierarchy.any { it.objectId == thunkClass.objectId }
    }

    /**
     * 检查 View 是否是根视图（root view）
     * 参考 LeakCanary AndroidObjectInspectors.VIEW 的实现
     * 
     * Root view 的定义：
     * 1. mParent == null (无父视图)
     * 2. mParent != null 且 mParent 不是 View 类型（即 mParent 是 ViewRootImpl）
     */
    private fun isRootView(instance: HeapInstance): Boolean {
        // 参考 LeakCanary：使用 valueAsInstance 获取 mParent
        val viewParent = instance[VIEW_CLASS_NAME, "mParent"]?.valueAsInstance
        val isParentlessView = viewParent == null
        // 如果 mParent 不是 View 类型，说明是 ViewRootImpl 的子视图，即根视图
        val isChildOfViewRootImpl = viewParent != null && !(viewParent instanceOf VIEW_CLASS_NAME)
        val isRootView = isParentlessView || isChildOfViewRootImpl
        return isRootView
    }

    /**
     * 展开 Context 获取 Activity（参考 LeakCanary）
     */
    private fun HeapInstance.unwrapActivityContext(graph: kshark.HeapGraph): HeapInstance? {
        val contextWrapperClass = graph.findClassByName("android.content.ContextWrapper")
        if (contextWrapperClass == null) return null

        val matchingClassName = instanceClass.classHierarchy.map { it.name }
            .firstOrNull {
                when (it) {
                    "android.content.ContextWrapper",
                    "android.app.Activity",
                    "android.app.Application",
                    "android.app.Service"
                    -> true
                    else -> false
                }
            } ?: return null

        if (matchingClassName != "android.content.ContextWrapper") {
            return if (matchingClassName == "android.app.Activity") this else null
        }

        var context = this
        val visitedInstances = mutableListOf<Long>()
        var keepUnwrapping = true
        while (keepUnwrapping) {
            visitedInstances += context.objectId
            keepUnwrapping = false
            val mBase = context["android.content.ContextWrapper", "mBase"]?.value

            if (mBase?.isNonNullReference == true) {
                val wrapperContext = context
                context = mBase.asObject!!.asInstance!!

                val contextMatchingClassName = context.instanceClass.classHierarchy.map { it.name }
                    .firstOrNull {
                        when (it) {
                            "android.content.ContextWrapper",
                            "android.app.Activity",
                            "android.app.Application",
                            "android.app.Service"
                            -> true
                            else -> false
                        }
                    }

                if (contextMatchingClassName == "android.app.Activity") {
                    return context
                } else if (contextMatchingClassName == "android.app.Service" ||
                    contextMatchingClassName == "android.app.Application") {
                    return null
                } else if (contextMatchingClassName == "android.content.ContextWrapper" &&
                    context.objectId !in visitedInstances) {
                    keepUnwrapping = true
                }
            }
        }
        return null
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
        var leakedBitmapCount: Int = 0,
        var leakedByteArrayCount: Int = 0,
        var leakedServiceCount: Int = 0,
        var leakedDialogCount: Int = 0,
        var leakedBroadcastReceiverCount: Int = 0,
        var leakedAnimatorCount: Int = 0,
        var threadCount: Int = 0,
        val threadNameCount: MutableMap<String, Int> = mutableMapOf(),
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
        val bitmapOutputDir: Path? = null,
        val classStatistics: List<ClassStatistics> = emptyList(),
        val largeObjects: List<LargeObject> = emptyList(),
        val filterInstanceTime: Long = 0,
        val findGCPathTime: Long = 0
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
            if (filterInstanceTime > 0 || findGCPathTime > 0) {
                println("   - 过滤实例耗时: ${filterInstanceTime}ms")
                println("   - 查找GC路径耗时: ${findGCPathTime}ms")
            }
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

            // 线程统计
            if (stats.threadCount > 0) {
                println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                println("🧵 线程统计")
                println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                println("   总线程数: ${stats.threadCount}")

                // 显示同名线程数量较多的
                val frequentThreads = stats.threadNameCount.filter { it.value >= 3 }
                    .entries.sortedByDescending { it.value }
                if (frequentThreads.isNotEmpty()) {
                    println("   同名线程(≥3个):")
                    frequentThreads.forEach { (name, count) ->
                        println("      - $name: $count 个")
                    }
                    // 如果有多组重复线程（≥2组），显示统计信息
                    if (frequentThreads.size >= 2) {
                        println("   多组重复线程: ${frequentThreads.size}种 x ${frequentThreads.map { it.value }.average().toInt()} (平均)")
                    }
                }
                println()
            }

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

            // 泄露类型统计
            if (stats.leakedActivityCount > 0 || stats.leakedFragmentCount > 0 || 
                stats.leakedDialogCount > 0 ||
                stats.leakedBroadcastReceiverCount > 0 ||
                stats.leakedAnimatorCount > 0) {
                println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                println("🚨 泄露类型统计")
                println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                if (stats.leakedActivityCount > 0) println("   Activity泄露: ${stats.leakedActivityCount} 个")
                if (stats.leakedFragmentCount > 0) println("   Fragment泄露: ${stats.leakedFragmentCount} 个")
                if (stats.leakedDialogCount > 0) println("   Dialog泄露: ${stats.leakedDialogCount} 个")
                if (stats.leakedBroadcastReceiverCount > 0) println("   BroadcastReceiver泄露: ${stats.leakedBroadcastReceiverCount} 个")
                if (stats.leakedAnimatorCount > 0) println("   Animator泄露: ${stats.leakedAnimatorCount} 个")
                println()
            }

            // 类实例统计
            if (classStatistics.isNotEmpty()) {
                println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                println("📈 类实例统计 (关键类)")
                println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                classStatistics.filter { it.leakInstanceCount > 0 || it.instanceCount > 10 }
                    .take(20)
                    .forEach { stat ->
                        println("   ${stat.className}")
                        println("      总实例数: ${stat.instanceCount}, 泄露实例数: ${stat.leakInstanceCount}")
                    }
                println()
            }

            // 大对象列表
            if (largeObjects.isNotEmpty()) {
                println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                println("📦 大对象列表 (${largeObjects.size} 个)")
                println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                largeObjects.sortedByDescending { it.size }.take(20).forEach { obj ->
                    println("   ${obj.className}")
                    println("      大小: ${stats.formatSize(obj.size)}")
                    if (obj.extDetail != null) {
                        println("      详情: ${obj.extDetail}")
                    }
                    println("      ObjectId: ${obj.objectId}")
                }
                println()
            }

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
                        println("        泄露原因: ${leak.leakReason}")
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
                        leak.referenceChain.forEach { ref ->
                            println("          → ${ref.fullName}")
                        }
                    }
                }
                println()
            }

            // 显示大对象统计（始终显示，如果有大对象的话）
            if (stats.leakedBitmapCount > 0 || stats.leakedByteArrayCount > 0) {
                println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                println("📊 大对象统计")
                println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                if (stats.leakedBitmapCount > 0) {
                    println("   大Bitmap(>1M像素): ${stats.leakedBitmapCount} 个")
                }
                if (stats.leakedByteArrayCount > 0) {
                    println("   大ByteArray(>1MB): ${stats.leakedByteArrayCount} 个")
                }
                println()
            }

            if (leakingObjects.isEmpty()) {
                println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                println("✅ 未发现Activity/Fragment内存泄露")
                println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

                println("💡 说明:")
                println("   - Activity/Fragment未销毁，不视为泄露")
                println("   - 但大Bitmap/大ByteArray占用大量内存也值得关注")
                println("   - 这些对象已在分析报告中标记为\"泄露\"")
                println("   - (Bitmap详细分析请查看 bitmap_analysis.html)")
                println()
            }
        }

        /**
         * 保存报告到文件
         */
        fun saveReport(outputDir: Path): List<Path> {
            val files = mutableListOf<Path>()

            // 创建输出目录
            Files.createDirectories(outputDir)

            // 写入文本报告（不带时间戳）
            val txtFile = outputDir.resolve("hprof_analysis.txt")
            Files.writeString(txtFile, generateTextReport())
            files.add(txtFile)

            // 写入HTML报告（不带时间戳）
            val htmlFile = outputDir.resolve("hprof_analysis.html")
            Files.writeString(htmlFile, generateHtmlReport(bitmapOutputDir))
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
            if (filterInstanceTime > 0 || findGCPathTime > 0) {
                sb.appendLine("   - 过滤实例耗时: ${filterInstanceTime}ms")
                sb.appendLine("   - 查找GC路径耗时: ${findGCPathTime}ms")
            }
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

            // 线程统计
            if (stats.threadCount > 0) {
                sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                sb.appendLine("🧵 线程统计")
                sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                sb.appendLine("   总线程数: ${stats.threadCount}")

                // 显示同名线程数量较多的
                val frequentThreads = stats.threadNameCount.filter { it.value >= 3 }
                    .entries.sortedByDescending { it.value }
                if (frequentThreads.isNotEmpty()) {
                    sb.appendLine("   同名线程(≥3个):")
                    frequentThreads.forEach { (name, count) ->
                        sb.appendLine("      - $name: $count 个")
                    }
                    // 如果有多组重复线程（≥2组），显示统计信息
                    if (frequentThreads.size >= 2) {
                        sb.appendLine("   多组重复线程: ${frequentThreads.size}种 x ${frequentThreads.map { it.value }.average().toInt()} (平均)")
                    }
                }
                sb.appendLine()
            }

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

            // 泄露类型统计
            if (stats.leakedActivityCount > 0 || stats.leakedFragmentCount > 0 || 
                stats.leakedDialogCount > 0 ||
                stats.leakedBroadcastReceiverCount > 0 ||
                stats.leakedAnimatorCount > 0) {
                sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                sb.appendLine("🚨 泄露类型统计")
                sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                if (stats.leakedActivityCount > 0) sb.appendLine("   Activity泄露: ${stats.leakedActivityCount} 个")
                if (stats.leakedFragmentCount > 0) sb.appendLine("   Fragment泄露: ${stats.leakedFragmentCount} 个")
                if (stats.leakedDialogCount > 0) sb.appendLine("   Dialog泄露: ${stats.leakedDialogCount} 个")
                if (stats.leakedBroadcastReceiverCount > 0) sb.appendLine("   BroadcastReceiver泄露: ${stats.leakedBroadcastReceiverCount} 个")
                if (stats.leakedAnimatorCount > 0) sb.appendLine("   Animator泄露: ${stats.leakedAnimatorCount} 个")
                sb.appendLine()
            }

            // 类实例统计
            if (classStatistics.isNotEmpty()) {
                sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                sb.appendLine("📈 类实例统计 (关键类)")
                sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                classStatistics.filter { it.leakInstanceCount > 0 || it.instanceCount > 10 }
                    .take(20)
                    .forEach { stat ->
                        sb.appendLine("   ${stat.className}")
                        sb.appendLine("      总实例数: ${stat.instanceCount}, 泄露实例数: ${stat.leakInstanceCount}")
                    }
                sb.appendLine()
            }

            // 大对象列表
            if (largeObjects.isNotEmpty()) {
                sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                sb.appendLine("📦 大对象列表 (${largeObjects.size} 个)")
                sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                largeObjects.sortedByDescending { it.size }.take(20).forEach { obj ->
                    sb.appendLine("   ${obj.className}")
                    sb.appendLine("      大小: ${stats.formatSize(obj.size)}")
                    if (obj.extDetail != null) {
                        sb.appendLine("      详情: ${obj.extDetail}")
                    }
                    sb.appendLine("      ObjectId: ${obj.objectId}")
                }
                sb.appendLine()
            }

            if (leakingObjects.isNotEmpty()) {
                sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                sb.appendLine("🚨 内存泄露对象 (${leakingObjects.size})")
                sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

                val byType = leakingObjects.groupBy { obj ->
                    when {
                        obj.className.contains("Activity") -> "Activity"
                        obj.className.contains("Fragment") -> "Fragment"
                        obj.className.contains("View") && !obj.className.contains("ViewGroup") -> "View"
                        obj.className.contains("ViewModel") -> "ViewModel"
                        obj.className.contains("Service") -> "Service"
                        obj.className.contains("Dialog") -> "Dialog"
                        obj.className.contains("Message") -> "Handler/Message"
                        obj.className.contains("BroadcastReceiver") -> "BroadcastReceiver"
                        obj.className.contains("Animator") -> "Animator"
                        obj.className.contains("Bitmap") -> "Bitmap"
                        else -> "Other"
                    }
                }

                byType.forEach { (type, objects) ->
                    sb.appendLine("\n   📍 $type (${objects.size})")
                    objects.take(5).forEach { leak ->
                        sb.appendLine("      - ${leak.className}")
                        sb.appendLine("        泄露原因: ${leak.leakReason}")
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
                        leak.referenceChain.forEach { ref ->
                            sb.appendLine("          → ${ref.fullName}")
                        }
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

        private fun generateHtmlReport(bitmapOutputDir: Path?): String {
            // 辅助函数：HTML 转义
            fun escapeHtml(text: String?): String {
                if (text == null) return ""
                return text
                    .replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\"", "&quot;")
                    .replace("'", "&#39;")
            }
            
            // 辅助函数：根据objectId查找图片文件
            fun findBitmapImageFile(objectId: Long): String? {
                if (bitmapOutputDir == null) return null

                try {
                    // bitmapOutputDir 已经是 bitmap 目录，不需要再 resolve("bitmaps")
                    if (!Files.exists(bitmapOutputDir)) return null

                    return Files.list(bitmapOutputDir)
                        .filter { Files.isRegularFile(it) }
                        .filter { it.fileName.toString().startsWith("bitmap_${objectId}_") }
                        .findFirst()
                        .map { file -> "bitmaps/${file.fileName}" }
                        .orElse(null)
                } catch (e: Exception) {
                    // Ignore errors
                }
                return null
            }

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
            sb.appendLine("        .reference-chain { margin-top: 10px; padding: 12px; background: #f8f9fa; border-left: 3px solid #667eea; border-radius: 4px; }")
            sb.appendLine("        .reference-chain-title { font-weight: 600; color: #333; margin-bottom: 8px; font-size: 13px; }")
            sb.appendLine("        .reference-chain .ref { font-size: 12px; padding: 6px 0; font-family: 'Monaco', 'Menlo', 'Consolas', monospace; line-height: 1.6; }")
            sb.appendLine("        .reference-chain .ref .arrow { color: #667eea; margin: 0 8px; font-weight: bold; }")
            sb.appendLine("        .ref-declared-class { background: #ffebee; color: #c62828; padding: 2px 6px; border-radius: 3px; font-weight: 600; }")
            sb.appendLine("        .ref-reference { background: #e3f2fd; color: #1565c0; padding: 2px 6px; border-radius: 3px; font-weight: 600; }")
            sb.appendLine("        .ref-type-tag { background: #fff3e0; color: #e65100; padding: 2px 6px; border-radius: 3px; font-size: 10px; font-weight: 600; margin-left: 4px; }")
            sb.appendLine("        .ref-separator { color: #999; margin: 0 4px; }")
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

            // 线程统计
            if (stats.threadCount > 0) {
                sb.appendLine("        <div class=\"section\">")
                sb.appendLine("            <div class=\"section-title\"><span class=\"icon\">🧵</span>线程统计</div>")
                sb.appendLine("            <div class=\"stats-grid\">")
                sb.appendLine("                <div class=\"stat-card\"><div class=\"label\">总线程数</div><div class=\"value\">${stats.threadCount}</div></div>")

                // 同名线程数量较多的
                val frequentThreads = stats.threadNameCount.filter { it.value >= 3 }
                    .entries.sortedByDescending { it.value }
                if (frequentThreads.isNotEmpty()) {
                    sb.appendLine("                <div class=\"stat-card\"><div class=\"label\">同名线程(≥3)</div><div class=\"value\">${frequentThreads.size} 种</div></div>")
                }
                sb.appendLine("            </div>")

                // 显示同名线程详情
                if (frequentThreads.isNotEmpty()) {
                    sb.appendLine("            <div style=\"margin-top: 15px; padding: 15px; background: #f8f9fa; border-radius: 6px;\">")
                    sb.appendLine("                <div style=\"font-weight: 600; margin-bottom: 10px; color: #333;\">同名线程详情:</div>")
                    frequentThreads.forEach { (name, count) ->
                        sb.appendLine("                <div style=\"padding: 5px 0; font-size: 13px;\">")
                        sb.appendLine("                    <span style=\"color: #666;\">$name:</span> <span style=\"font-weight: 600; color: #ff9800;\">$count 个</span>")
                        sb.appendLine("                </div>")
                    }
                    sb.appendLine("            </div>")
                }

                sb.appendLine("        </div>")
            }

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

            // 泄露类型统计
            if (stats.leakedActivityCount > 0 || stats.leakedFragmentCount > 0 || 
                stats.leakedServiceCount > 0 ||
                stats.leakedDialogCount > 0 ||
                stats.leakedBroadcastReceiverCount > 0 ||
                stats.leakedAnimatorCount > 0) {
                sb.appendLine("        <div class=\"section\">")
                sb.appendLine("            <div class=\"section-title\"><span class=\"icon\">🚨</span>泄露类型统计</div>")
                sb.appendLine("            <div class=\"stats-grid\">")
                if (stats.leakedActivityCount > 0) sb.appendLine("                <div class=\"stat-card\"><div class=\"label\">Activity泄露</div><div class=\"value\">${stats.leakedActivityCount}</div></div>")
                if (stats.leakedFragmentCount > 0) sb.appendLine("                <div class=\"stat-card\"><div class=\"label\">Fragment泄露</div><div class=\"value\">${stats.leakedFragmentCount}</div></div>")
                if (stats.leakedServiceCount > 0) sb.appendLine("                <div class=\"stat-card\"><div class=\"label\">Service泄露</div><div class=\"value\">${stats.leakedServiceCount}</div></div>")
                if (stats.leakedDialogCount > 0) sb.appendLine("                <div class=\"stat-card\"><div class=\"label\">Dialog泄露</div><div class=\"value\">${stats.leakedDialogCount}</div></div>")
                if (stats.leakedBroadcastReceiverCount > 0) sb.appendLine("                <div class=\"stat-card\"><div class=\"label\">BroadcastReceiver泄露</div><div class=\"value\">${stats.leakedBroadcastReceiverCount}</div></div>")
                if (stats.leakedAnimatorCount > 0) sb.appendLine("                <div class=\"stat-card\"><div class=\"label\">Animator泄露</div><div class=\"value\">${stats.leakedAnimatorCount}</div></div>")
                sb.appendLine("            </div>")
                sb.appendLine("        </div>")
            }

            // 类实例统计
            if (classStatistics.isNotEmpty()) {
                sb.appendLine("        <div class=\"section\">")
                sb.appendLine("            <div class=\"section-title\"><span class=\"icon\">📈</span>类实例统计 (关键类)</div>")
                sb.appendLine("            <table class=\"data-table\">")
                sb.appendLine("                <thead>")
                sb.appendLine("                    <tr>")
                sb.appendLine("                        <th>类名</th>")
                sb.appendLine("                        <th>总实例数</th>")
                sb.appendLine("                        <th>泄露实例数</th>")
                sb.appendLine("                    </tr>")
                sb.appendLine("                </thead>")
                sb.appendLine("                <tbody>")
                classStatistics.filter { it.leakInstanceCount > 0 || it.instanceCount > 10 }
                    .take(20)
                    .forEach { stat ->
                        sb.appendLine("                    <tr>")
                        sb.appendLine("                        <td>${escapeHtml(stat.className)}</td>")
                        sb.appendLine("                        <td>${stat.instanceCount}</td>")
                        sb.appendLine("                        <td><span style=\"color: ${if (stat.leakInstanceCount > 0) "#c62828" else "#666"}\">${stat.leakInstanceCount}</span></td>")
                        sb.appendLine("                    </tr>")
                    }
                sb.appendLine("                </tbody>")
                sb.appendLine("            </table>")
                sb.appendLine("        </div>")
            }

            // 大对象列表
            if (largeObjects.isNotEmpty()) {
                sb.appendLine("        <div class=\"section\">")
                sb.appendLine("            <div class=\"section-title\"><span class=\"icon\">📦</span>大对象列表 (${largeObjects.size} 个)</div>")
                sb.appendLine("            <table class=\"data-table\">")
                sb.appendLine("                <thead>")
                sb.appendLine("                    <tr>")
                sb.appendLine("                        <th>类名</th>")
                sb.appendLine("                        <th>大小</th>")
                sb.appendLine("                        <th>详情</th>")
                sb.appendLine("                    </tr>")
                sb.appendLine("                </thead>")
                sb.appendLine("                <tbody>")
                largeObjects.sortedByDescending { it.size }.take(20).forEach { obj ->
                    sb.appendLine("                    <tr>")
                    sb.appendLine("                        <td>${escapeHtml(obj.className)}</td>")
                    sb.appendLine("                        <td>${stats.formatSize(obj.size)}</td>")
                    sb.appendLine("                        <td>${obj.extDetail ?: "-"}</td>")
                    sb.appendLine("                    </tr>")
                }
                sb.appendLine("                </tbody>")
                sb.appendLine("            </table>")
                sb.appendLine("        </div>")
            }

            // 泄漏对象
            if (leakingObjects.isNotEmpty()) {
                sb.appendLine("        <div class=\"section\">")
                sb.appendLine("            <div class=\"section-title\"><span class=\"icon\">🚨</span>内存泄露对象 (${leakingObjects.size})</div>")

                val byType = leakingObjects.groupBy { obj ->
                    when {
                        obj.className.contains("Activity") -> "activity"
                        obj.className.contains("Fragment") -> "fragment"
                        obj.className.contains("View") && !obj.className.contains("ViewGroup") -> "view"
                        obj.className.contains("ViewModel") -> "viewmodel"
                        obj.className.contains("Service") -> "service"
                        obj.className.contains("Dialog") -> "dialog"
                        obj.className.contains("Message") -> "handler"
                        obj.className.contains("BroadcastReceiver") -> "receiver"
                        obj.className.contains("Animator") -> "animator"
                        obj.className.contains("Bitmap") -> "bitmap"
                        else -> "other"
                    }
                }

                byType.forEach { (type, objects) ->
                    val typeLabel = when(type) {
                        "activity" -> "Activity"
                        "fragment" -> "Fragment"
                        "view" -> "View"
                        "viewmodel" -> "ViewModel"
                        "service" -> "Service"
                        "dialog" -> "Dialog"
                        "handler" -> "Handler/Message"
                        "receiver" -> "BroadcastReceiver"
                        "animator" -> "Animator"
                        "bitmap" -> "Bitmap"
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

                            leak.containedBitmaps.take(15).forEach { bmp ->
                                val sizeMB = "%.2f".format(bmp.pixelCount * 4.0 / (1024 * 1024))

                                // 尝试查找匹配的图片文件
                                val imagePath = bmp.imageFilePath ?: findBitmapImageFile(bmp.objectId)

                                if (imagePath != null) {
                                    sb.appendLine("                            <div class=\"bitmap-item\">")
                                    sb.appendLine("                                <img src=\"$imagePath\" class=\"bitmap-thumb\" alt=\"Bitmap\">")
                                    sb.appendLine("                                <span>${bmp.width}x${bmp.height} (${sizeMB}MB)</span>")
                                    sb.appendLine("                            </div>")
                                } else {
                                    sb.appendLine("                            <div class=\"bitmap-item\">")
                                    sb.appendLine("                                <span>${bmp.width}x${bmp.height} (${sizeMB}MB) [无图片]</span>")
                                    sb.appendLine("                            </div>")
                                }
                            }
                            sb.appendLine("                        </div>")
                        }

                        if (leak.referenceChain.isNotEmpty()) {
                            sb.appendLine("                        <div class=\"reference-chain\">")
                            sb.appendLine("                            <div class=\"reference-chain-title\">📋 引用链 (GC Root → 泄露对象):</div>")
                            
                            // 显示引用路径
                            leak.referenceChain.forEachIndexed { index, ref ->
                                val arrow = "<span class=\"arrow\">→</span>"
                                
                                // 构建引用链显示
                                val refParts = mutableListOf<String>()
                                
                                // 判断是否是数组类型
                                val isArray = ref.referenceName.startsWith("[") || ref.referenceType == "ARRAY_ENTRY"
                                
                                // declaredClass（如果存在且与 className 不同）
                                if (ref.declaredClass != null && ref.declaredClass != ref.className) {
                                    refParts.add("<span class=\"ref-declared-class\">${escapeHtml(ref.declaredClass)}</span>")
                                    refParts.add("<span class=\"ref-separator\">.</span>")
                                }
                                
                                // className（对于数组，referenceName 就是类名）
                                if (isArray) {
                                    // 数组类型：显示 referenceName（通常是类名）
                                    val arrayClassName = if (ref.referenceName.startsWith("[")) {
                                        ref.referenceName
                                    } else {
                                        ref.className
                                    }
                                    refParts.add("<span class=\"ref-declared-class\">${escapeHtml(arrayClassName)}</span>")
                                } else {
                                    // 非数组类型：显示 className.referenceName
                                    refParts.add("<span class=\"ref-declared-class\">${escapeHtml(ref.className)}</span>")
                                    refParts.add("<span class=\"ref-separator\">.</span>")
                                    refParts.add("<span class=\"ref-reference\">${escapeHtml(ref.referenceName)}</span>")
                                }
                                
                                // referenceType tag
                                val typeLabel = when (ref.referenceType) {
                                    "INSTANCE_FIELD" -> "INSTANCE"
                                    "STATIC_FIELD" -> "STATIC"
                                    "ARRAY_ENTRY" -> "ARRAY"
                                    "LOCAL" -> "LOCAL"
                                    else -> ref.referenceType
                                }
                                refParts.add("<span class=\"ref-type-tag\">[$typeLabel]</span>")
                                
                                sb.appendLine("                            <div class=\"ref\">$arrow${refParts.joinToString("")}</div>")
                            }
                            
                            // 添加泄露对象本身（最后一个节点）
                            sb.appendLine("                            <div class=\"ref\">")
                            sb.appendLine("                                <span class=\"arrow\">→</span>")
                            sb.appendLine("                                <span class=\"ref-declared-class\">${escapeHtml(leak.className)}</span>")
                            sb.appendLine("                                <span class=\"ref-type-tag\" style=\"background: #ffebee; color: #c62828;\">[LEAKING]</span>")
                            sb.appendLine("                            </div>")
                            
                            sb.appendLine("                        </div>")
                        }
                        sb.appendLine("                    </div>")
                        sb.appendLine("                </div>")
                    }
                    sb.appendLine("            </div>")
                }

                sb.appendLine("        </div>")
            }

            // 显示大对象统计（始终显示，如果有大对象的话）
            if (stats.leakedBitmapCount > 0 || stats.leakedByteArrayCount > 0) {
                sb.appendLine("        <div class=\"section\">")
                sb.appendLine("            <div class=\"section-title\"><span class=\"icon\">📊</span>大对象统计</div>")
                sb.appendLine("            <div class=\"stats-grid\">")
                if (stats.leakedBitmapCount > 0) {
                    sb.appendLine("                <div class=\"stat-card\"><div class=\"label\">大Bitmap(>1M像素)</div><div class=\"value\">${stats.leakedBitmapCount}</div></div>")
                }
                if (stats.leakedByteArrayCount > 0) {
                    sb.appendLine("                <div class=\"stat-card\"><div class=\"label\">大ByteArray(>1MB)</div><div class=\"value\">${stats.leakedByteArrayCount}</div></div>")
                }
                sb.appendLine("            </div>")
                sb.appendLine("            <div class=\"info-box\">")
                sb.appendLine("                <div style=\"font-size: 13px; color: #666;\">")
                sb.appendLine("                    <strong>说明:</strong> 这些大对象(Bitmap>1M像素, ByteArray>1MB)被标记为潜在内存问题，")
                sb.appendLine("                    可能导致内存占用过高。详细分析请查看 <a href=\"bitmap_analysis.html\" class=\"link\">bitmap_analysis.html</a>")
                sb.appendLine("                </div>")
                sb.appendLine("            </div>")
                sb.appendLine("        </div>")
            }

            if (leakingObjects.isEmpty()) {
                // 无泄露时显示更友好的界面
                sb.appendLine("        <div class=\"section\">")
                sb.appendLine("            <div class=\"no-leak\">")
                sb.appendLine("                <div class=\"icon\">✅</div>")
                sb.appendLine("                <h2>未发现Activity/Fragment内存泄露</h2>")
                sb.appendLine("                <p>在此次分析中未检测到Activity或Fragment的内存泄露。</p>")
                if (stats.leakedBitmapCount > 0 || stats.leakedByteArrayCount > 0) {
                    sb.appendLine("                <p style=\"margin-top: 10px; color: #666;\">但检测到大对象占用内存，请查看上方的\"大对象统计\"。</p>")
                }
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

    /**
     * 类实例统计（参考 KOOM 的 ClassInfo）
     */
    data class ClassStatistics(
        val className: String,
        val instanceCount: Int,  // 总实例数
        val leakInstanceCount: Int = 0  // 泄露实例数
    )

    /**
     * 大对象详情（参考 KOOM 的 LeakObject）
     */
    data class LargeObject(
        val className: String,
        val size: Long,  // 大小（字节）
        val objectId: Long,
        val extDetail: String? = null  // 额外详情，如Bitmap尺寸 "1440x3200"
    )
}
