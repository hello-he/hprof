package com.koom.monitor.model

/**
 * 阈值跟踪器（参考 KOOM 的实现）
 * 用于跟踪连续超过阈值的次数，避免因临时波动导致的误判
 */
class ThresholdTracker {
    companion object {
        // 阈值浮动范围（参考 KOOM）
        private const val HEAP_RATIO_THRESHOLD_GAP = 0.05 // 堆内存比率允许 5% 的浮动
        private const val THREAD_COUNT_THRESHOLD_GAP = 50 // 线程数允许 50 的浮动
        private const val FD_COUNT_THRESHOLD_GAP = 50 // 文件句柄数允许 50 的浮动
    }

    // 上次的指标值
    private var lastHeapRatio = 0.0
    private var lastThreadCount = 0
    private var lastFdCount = 0

    // 连续超过阈值的次数
    var heapOverThresholdCount = 0
        private set
    private var threadOverThresholdCount = 0
    private var fdOverThresholdCount = 0

    // 快速内存增长检测
    private var lastHeapUsed = 0L
    private var fastMemoryGrowthDetected = false

    /**
     * 检查堆内存是否连续超过阈值
     * @param heapRatio 当前堆内存使用率
     * @param threshold 阈值
     * @param maxOverThresholdCount 需要连续超过阈值的次数
     * @return true 表示应该触发 dump
     */
    fun checkHeapThreshold(
        heapRatio: Double,
        threshold: Double,
        maxOverThresholdCount: Int = 3
    ): Boolean {
        // 检查是否超过阈值，并且允许一定的浮动范围（避免 GC 导致的误判）
        if (heapRatio > threshold && heapRatio >= lastHeapRatio - HEAP_RATIO_THRESHOLD_GAP) {
            heapOverThresholdCount++
        } else {
            // 如果不再超过阈值，重置计数
            heapOverThresholdCount = 0
        }

        lastHeapRatio = heapRatio
        return heapOverThresholdCount >= maxOverThresholdCount
    }

    /**
     * 检查线程数是否连续超过阈值
     * @param threadCount 当前线程数
     * @param threshold 阈值
     * @param maxOverThresholdCount 需要连续超过阈值的次数
     * @return true 表示应该触发 dump
     */
    fun checkThreadThreshold(
        threadCount: Int,
        threshold: Int,
        maxOverThresholdCount: Int = 3
    ): Boolean {
        // 检查是否超过阈值，并且允许一定的浮动范围
        if (threadCount > threshold && threadCount >= lastThreadCount - THREAD_COUNT_THRESHOLD_GAP) {
            threadOverThresholdCount++
        } else {
            threadOverThresholdCount = 0
        }

        lastThreadCount = threadCount
        return threadOverThresholdCount >= maxOverThresholdCount
    }

    /**
     * 检查文件句柄数是否连续超过阈值
     * @param fdCount 当前文件句柄数
     * @param threshold 阈值
     * @param maxOverThresholdCount 需要连续超过阈值的次数
     * @return true 表示应该触发 dump
     */
    fun checkFdThreshold(
        fdCount: Int,
        threshold: Int,
        maxOverThresholdCount: Int = 3
    ): Boolean {
        // 检查是否超过阈值，并且允许一定的浮动范围
        if (fdCount > threshold && fdCount >= lastFdCount - FD_COUNT_THRESHOLD_GAP) {
            fdOverThresholdCount++
        } else {
            fdOverThresholdCount = 0
        }

        lastFdCount = fdCount
        return fdOverThresholdCount >= maxOverThresholdCount
    }

    /**
     * 检查快速内存增长（参考 KOOM 的 FastHugeMemoryOOMTracker）
     * @param heapUsed 当前堆内存使用量
     * @param heapMax 堆内存最大值
     * @param highWatermarkThreshold 高水位线阈值（例如 0.9 表示 90%）
     * @param deltaThresholdKB 内存增量阈值（KB），例如 50MB = 51200 KB
     * @return true 表示检测到快速内存增长
     */
    fun checkFastMemoryGrowth(
        heapUsed: Long,
        heapMax: Long,
        highWatermarkThreshold: Double = 0.9,
        deltaThresholdKB: Long = 51200 // 50MB
    ): Boolean {
        // 高水位线直接触发
        if (heapMax > 0 && heapUsed.toDouble() / heapMax > highWatermarkThreshold) {
            fastMemoryGrowthDetected = true
            lastHeapUsed = heapUsed
            return true
        }

        // 检查内存增量
        if (lastHeapUsed > 0) {
            val delta = heapUsed - lastHeapUsed
            if (delta > deltaThresholdKB * 1024) {
                fastMemoryGrowthDetected = true
                lastHeapUsed = heapUsed
                return true
            }
        }

        lastHeapUsed = heapUsed
        fastMemoryGrowthDetected = false
        return false
    }

    /**
     * 重置所有跟踪状态
     */
    fun reset() {
        heapOverThresholdCount = 0
        threadOverThresholdCount = 0
        fdOverThresholdCount = 0
        lastHeapRatio = 0.0
        lastThreadCount = 0
        lastFdCount = 0
        lastHeapUsed = 0L
        fastMemoryGrowthDetected = false
    }

    /**
     * 获取当前状态信息
     */
    fun getStatus(): String {
        return buildString {
            append("堆内存连续超过阈值: $heapOverThresholdCount 次\n")
            append("线程数连续超过阈值: $threadOverThresholdCount 次\n")
            append("文件句柄连续超过阈值: $fdOverThresholdCount 次\n")
            if (fastMemoryGrowthDetected) {
                append("快速内存增长: 已检测")
            }
        }
    }
}
