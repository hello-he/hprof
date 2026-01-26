package com.koom.monitor.model

import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.Path

/**
 * 监控配置
 */
data class MonitorConfig(
    /** 应用包名列表 */
    val packageNames: List<String>,

    /** Java堆内存使用率阈值 (0.0-1.0) */
    val heapThreshold: Double = 0.8,

    /** 线程数阈值 */
    val threadThreshold: Int = 500,

    /** 文件句柄数阈值 */
    val fdThreshold: Int = 1000,

    /** 输出目录 */
    val outputDir: Path = Paths.get("./reports"),

    /** 是否提取Bitmap */
    val extractBitmap: Boolean = true,

    /** Bitmap像素阈值 (宽*高) */
    val bitmapPixelThreshold: Int = 768 * 1366 + 1,

    /** Bitmap字节阈值 */
    val bitmapByteThreshold: Long = 2L * 1024 * 1024,

    /** 是否检测重复Bitmap */
    val detectDuplicates: Boolean = true,

    /** 是否检测重复线程名字 (线程泄露) */
    val detectDuplicateThreads: Boolean = true,

    /** 是否自动dump (超阈值时) */
    val autoDump: Boolean = true,

    /** 最大连续检测次数 (0表示无限) */
    val maxIterations: Int = 0,

    /** 监控间隔(秒) */
    val intervalSeconds: Long = 10L,

    /** 连续超过阈值的次数才触发dump (参考 KOOM) */
    val maxOverThresholdCount: Int = 3,

    /** 快速内存增长检测：高水位线阈值 (0.0-1.0) */
    val fastMemoryHighWatermarkThreshold: Double = 0.9,

    /** 快速内存增长检测：内存增量阈值 (KB) */
    val fastMemoryDeltaThresholdKB: Long = 51200, // 50MB

    /** ADB路径 */
    val adbPath: String = "adb"
) {
    companion object {
        /**
         * 从文件读取包名列表
         */
        fun fromPackageFile(filePath: String): List<String> {
            val file = java.io.File(filePath)
            if (!file.exists()) {
                throw IllegalArgumentException("Package file not found: $filePath")
            }

            return file.readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
        }
    }

    /**
     * 检查配置是否有效
     */
    fun validate(): List<String> {
        val errors = mutableListOf<String>()

        if (packageNames.isEmpty()) {
            errors.add("至少需要一个包名")
        }

        if (heapThreshold !in 0.0..1.0) {
            errors.add("heapThreshold 必须在 0.0-1.0 之间")
        }

        if (threadThreshold <= 0) {
            errors.add("threadThreshold 必须大于 0")
        }

        if (fdThreshold <= 0) {
            errors.add("fdThreshold 必须大于 0")
        }

        return errors
    }
}
