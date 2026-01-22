package com.koom.monitor.model

import java.time.Instant

/**
 * 线程信息
 */
data class ThreadInfo(
    val tid: Int,
    val name: String
)

/**
 * 重复的线程名字信息
 */
data class DuplicateThreadInfo(
    val name: String,
    val count: Int,
    val tids: List<Int>
) {
    val description: String
        get() = "$name (x$count)"
}

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
    val rss: Long? = null,

    /** 线程信息列表 */
    val threads: List<ThreadInfo> = emptyList(),

    /** 重复的线程名字 */
    val duplicateThreads: List<DuplicateThreadInfo> = emptyList()
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
     * 是否有重复的线程名字
     */
    val hasDuplicateThreads: Boolean
        get() = duplicateThreads.isNotEmpty()

    /**
     * 重复线程总数
     */
    val totalDuplicateThreadCount: Int
        get() = duplicateThreads.sumOf { it.count - 1 }

    /**
     * 是否超过配置的阈值 (包括线程泄露)
     */
    fun isOverThreshold(config: MonitorConfig): Boolean {
        return heapUsed >= heapMax * config.heapThreshold
                || threadCount >= config.threadThreshold
                || fdCount >= config.fdThreshold
                || (hasDuplicateThreads && config.detectDuplicateThreads)
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

        if (hasDuplicateThreads && config.detectDuplicateThreads) {
            val dupInfo = duplicateThreads.take(3).joinToString(", ") { it.description }
            val more = if (duplicateThreads.size > 3) "..." else ""
            reasons.add("线程名字重复: $dupInfo$more")
        }

        return reasons
    }

    /**
     * 是否有线程泄露 (重复名字的线程)
     */
    fun hasThreadLeak(): Boolean {
        return hasDuplicateThreads
    }

    /**
     * 获取线程泄露描述
     */
    fun getThreadLeakDescription(): String {
        if (!hasDuplicateThreads) return "无"

        val topDuplicates = duplicateThreads
            .sortedByDescending { it.count }
            .take(5)

        val sb = StringBuilder()
        sb.append("发现 ${duplicateThreads.size} 种重复线程名:\n")
        topDuplicates.forEach { dup ->
            sb.append("  - ${dup.name}: ${dup.count} 个线程 (TIDs: ${dup.tids.take(5).joinToString(", ")}")
            if (dup.tids.size > 5) sb.append("...")
            sb.append(")\n")
        }
        if (duplicateThreads.size > 5) {
            sb.append("  ... 还有 ${duplicateThreads.size - 5} 种\n")
        }
        return sb.toString().trim()
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

    companion object {
        /**
         * 分析线程列表，找出重复的线程名字
         */
        fun analyzeThreads(threads: List<ThreadInfo>): List<DuplicateThreadInfo> {
            val nameToTids = threads.groupBy { it.name }
            return nameToTids
                .filter { it.value.size > 1 }
                .map { (name, threadInfos) ->
                    DuplicateThreadInfo(
                        name = name,
                        count = threadInfos.size,
                        tids = threadInfos.map { it.tid }.sorted()
                    )
                }
                .sortedByDescending { it.count }
        }
    }
}
