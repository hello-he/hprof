package com.koom.monitor.model

import java.time.Instant

/**
 * 指标快照
 */
data class MetricsSnapshot(
    /** 采集时间 */
    val timestamp: Instant = Instant.now(),

    /** 应用包名 */
    val packageName: String,

    /** 进程ID */
    val pid: Int,

    /** Java堆已用内存 (bytes) */
    val heapUsed: Long,

    /** Java堆最大内存 (bytes) */
    val heapMax: Long,

    /** 线程数 */
    val threadCount: Int,

    /** 文件句柄数 */
    val fdCount: Int,

    /** VSS (Virtual Set Size) */
    val vss: Long? = null,

    /** RSS (Resident Set Size) */
    val rss: Long? = null
) {
    /**
     * 堆内存使用率
     */
    val heapUsageRatio: Double
        get() = if (heapMax > 0) heapUsed.toDouble() / heapMax else 0.0

    /**
     * 堆内存使用率百分比
     */
    val heapUsagePercent: Int
        get() = (heapUsageRatio * 100).toInt()

    /**
     * 格式化的内存信息
     */
    val heapInfo: String
        get() = "${formatBytes(heapUsed)} / ${formatBytes(heapMax)} ($heapUsagePercent%)"

    /**
     * 是否超过配置的阈值
     */
    fun isOverThreshold(config: MonitorConfig): Boolean {
        return heapUsed >= heapMax * config.heapThreshold
                || threadCount >= config.threadThreshold
                || fdCount >= config.fdThreshold
    }

    /**
     * 获取超过阈值的指标描述
     */
    fun getOverThresholdReasons(config: MonitorConfig): List<String> {
        val reasons = mutableListOf<String>()

        if (heapUsed >= heapMax * config.heapThreshold) {
            reasons.add("堆内存使用率 ${heapUsagePercent}% >= ${(config.heapThreshold * 100).toInt()}%")
        }

        if (threadCount >= config.threadThreshold) {
            reasons.add("线程数 $threadCount >= ${config.threadThreshold}")
        }

        if (fdCount >= config.fdThreshold) {
            reasons.add("文件句柄数 $fdCount >= ${config.fdThreshold}")
        }

        return reasons
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 * 1024 -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
            bytes >= 1024 * 1024 -> "%.2f MB".format(bytes / (1024.0 * 1024))
            bytes >= 1024 -> "%.2f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    /**
     * 快照差异
     */
    data class Diff(
        val heapUsedDelta: Long,
        val threadCountDelta: Int,
        val fdCountDelta: Int,
        val durationMs: Long
    )

    fun diff(other: MetricsSnapshot): Diff {
        val duration = java.time.Duration.between(other.timestamp, timestamp).toMillis()
        return Diff(
            heapUsedDelta = heapUsed - other.heapUsed,
            threadCountDelta = threadCount - other.threadCount,
            fdCountDelta = fdCount - other.fdCount,
            durationMs = duration
        )
    }
}
