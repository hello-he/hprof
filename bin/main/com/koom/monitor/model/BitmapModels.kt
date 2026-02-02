package com.koom.monitor.model

/**
 * Bitmap信息
 */
data class BitmapInfo(
    /** 对象ID */
    val objectId: Long,

    /** 宽度 */
    val width: Int,

    /** 高度 */
    val height: Int,

    /** 配置 (ARGB_8888, RGB_565, etc.) */
    val config: String,

    /** 字节数 */
    val byteCount: Long,

    /** 内容hash (用于去重) */
    val contentHash: String? = null,

    /** 提取的PNG路径 */
    val pngPath: String? = null,

    /** 泄漏原因 */
    val leakReason: LeakReason? = null,

    /** 引用链 (GC Root -> Bitmap) */
    val referenceChain: List<ReferenceNode>? = null,

    /** 所属类名 */
    val className: String = "android.graphics.Bitmap",

    /** 是否已回收 */
    val isRecycled: Boolean = false
) {
    /** 像素数 */
    val pixelCount: Int
        get() = width * height

    /** 每像素字节数 */
    val bytesPerPixel: Double
        get() = if (pixelCount > 0) byteCount.toDouble() / pixelCount else 0.0

    /** 是否是大图 */
    val isLarge: Boolean
        get() = pixelCount >= 768 * 1366 + 1 || byteCount >= 2L * 1024 * 1024

    /** 格式化的尺寸信息 */
    val sizeInfo: String
        get() = "${width}x${height} (${formatBytes(byteCount)})"

    /** 格式化的配置信息 */
    val configInfo: String
        get() = "$config (${bytesPerPixel.toFixed(2)} bytes/px)"

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 -> "%.2f MB".format(bytes / (1024.0 * 1024))
            bytes >= 1024 -> "%.2f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    private fun Double.toFixed(digits: Int): String {
        return "%.${digits}f".format(this)
    }
}

/**
 * 泄漏原因
 */
sealed class LeakReason {
    /** 被引用导致泄漏 */
    data class ReferencedLeak(
        val gcRoot: String,
        val leakType: LeakType
    ) : LeakReason()

    /** 超过尺寸阈值 */
    data class OverSizeThreshold(
        val thresholdPixels: Int,
        val thresholdBytes: Long
    ) : LeakReason()

    /** 重复图片 */
    data class Duplicate(
        val hash: String,
        val duplicateCount: Int,
        val totalBytes: Long
    ) : LeakReason()

    enum class LeakType {
        ACTIVITY_LEAK,
        FRAGMENT_LEAK,
        STATIC_LEAK,
        SINGLETON_LEAK,
        UNKNOWN_LEAK
    }
}

/**
 * 引用节点
 */
data class ReferenceNode(
    /** 类名 */
    val className: String,

    /** 字段名或引用名 */
    val referenceName: String,

    /** 引用类型 */
    val referenceType: String,

    /** 声明类 */
    val declaredClass: String? = null
) {
    /** 完整引用路径 */
    val fullName: String
        get() = if (referenceName.startsWith("[")) {
            className
        } else {
            "$className.$referenceName"
        }
}

/**
 * 重复Bitmap组
 */
data class DuplicateBitmapGroup(
    /** 内容hash */
    val hash: String,

    /** Bitmap列表 */
    val bitmaps: List<BitmapInfo>,

    /** 总数量 */
    val count: Int
) {
    /** 总字节数 */
    val totalBytes: Long
        get() = bitmaps.sumOf { it.byteCount }

    /** 可节省字节数 (保留一个) */
    val savableBytes: Long
        get() = bitmaps.drop(1).sumOf { it.byteCount }
}

/**
 * 泄漏报告（用于 Bitmap 分析等场景）
 */
data class LeakReport(
    /** 报告生成时间 */
    val timestamp: java.time.Instant = java.time.Instant.now(),

    /** 目标应用包名 */
    val packageName: String,

    /** 进程ID */
    val pid: Int,

    /** 泄漏的Bitmap列表 */
    val leakedBitmaps: List<BitmapInfo> = emptyList(),

    /** 过大的Bitmap列表 */
    val oversizedBitmaps: List<BitmapInfo> = emptyList(),

    /** 重复Bitmap组 */
    val duplicateBitmaps: List<DuplicateBitmapGroup> = emptyList(),

    /** 所有泄漏对象 */
    val allLeakingObjects: List<LeakingObject> = emptyList(),

    /** 截图路径 */
    val screenshotPath: String? = null,

    /** hprof文件路径 */
    val hprofPath: String? = null
) {
    /** 总泄漏数量 */
    val totalLeakCount: Int
        get() = leakedBitmaps.size + oversizedBitmaps.size +
                 duplicateBitmaps.sumOf { it.count - 1 }

    /** 总泄漏字节数 */
    val totalLeakBytes: Long
        get() = leakedBitmaps.sumOf { it.byteCount } +
                 oversizedBitmaps.sumOf { it.byteCount } +
                 duplicateBitmaps.sumOf { it.savableBytes }
}

/**
 * 泄漏对象
 */
data class LeakingObject(
    /** 类名 */
    val className: String,

    /** 对象ID */
    val objectId: Long,

    /** 大小 */
    val size: Long,

    /** 泄漏原因 */
    val leakReason: String,

    /** 引用链 */
    val referenceChain: List<ReferenceNode>,

    /** GC Root */
    val gcRoot: String,

    /** 签名 (用于归类相同泄漏) */
    val signature: String,

    /** 相同泄漏的数量 */
    val instanceCount: Int
)
