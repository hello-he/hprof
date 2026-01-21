package com.koom.monitor.analyzer

import kshark.HeapGraph
import kshark.HeapObject.HeapClass
import kshark.HeapObject.HeapInstance
import kshark.HeapObject.HeapPrimitiveArray
import kshark.HprofHeapGraph.Companion.openHeapGraph
import kshark.HprofRecordTag
import org.slf4j.LoggerFactory
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import javax.imageio.ImageIO

/**
 * Bitmap提取器 - 从hprof中提取Bitmap像素数据并保存为PNG
 *
 * 报告逻辑：
 * - bitmap_analysis.html: 报告所有大Bitmap和重复Bitmap（无论是否泄露）
 * - hprof_analysis.html: 由HprofAnalyzer生成，报告有GC Root泄露路径的对象
 */
class BitmapExtractor {

    private val logger = LoggerFactory.getLogger(BitmapExtractor::class.java)

    companion object {
        private const val BITMAP_CLASS_NAME = "android.graphics.Bitmap"
        private const val LARGE_BITMAP_THRESHOLD = 768 * 1366 + 1  // 约1M像素

        // Bitmap Config 常量
        private const val ALPHA_8 = 0
        private const val RGB_565 = 3
        private const val ARGB_4444 = 4
        private const val ARGB_8888 = 5
    }

    /**
     * 引用链节点
     */
    data class ReferenceNode(
        val className: String,
        val fieldName: String? = null,
        val fullName: String = if (fieldName != null) "$className.$fieldName" else className
    )

    /**
     * Bitmap信息
     */
    data class BitmapInfo(
        val objectId: Long,
        val width: Int,
        val height: Int,
        val pixelCount: Int,
        val config: String,
        val configValue: Int,
        val byteCount: Int = pixelCount * 4,  // 默认按ARGB_8888计算
        val imageHash: String? = null,
        val hasData: Boolean = false,
        val isLarge: Boolean = pixelCount >= LARGE_BITMAP_THRESHOLD,
        val referenceChain: List<ReferenceNode> = emptyList()  // 到GC Root的引用链
    )

    /**
     * 提取结果
     */
    data class ExtractionResult(
        val totalBitmaps: Int,
        val largeBitmaps: Int,
        val largeBitmapsList: List<BitmapInfo> = emptyList(),
        val extractedBitmaps: Int,
        val duplicateGroups: Map<String, List<BitmapInfo>>,
        val outputDir: Path,
        val extractedFiles: List<Path> = emptyList()
    )

    /**
     * 从hprof文件提取Bitmap
     *
     * 注意: 这里提取的是所有大Bitmap，不限于泄露的Bitmap
     * 泄露检测由HprofAnalyzer负责
     */
    fun extract(
        hprofFile: File,
        outputDir: Path = Paths.get("./reports/bitmaps"),
        extractImages: Boolean = true,
        largeOnly: Boolean = false
    ): ExtractionResult {
        val startTime = System.currentTimeMillis()

        Files.createDirectories(outputDir)

        hprofFile.openHeapGraph(
            proguardMapping = null,
            indexedGcRootTypes = setOf(
                HprofRecordTag.ROOT_JNI_GLOBAL,
                HprofRecordTag.ROOT_JNI_LOCAL
            )
        ).use { graph ->
            val bitmapClass = graph.findClassByName(BITMAP_CLASS_NAME)

            if (bitmapClass == null) {
                logger.warn("未找到Bitmap类")
                return ExtractionResult(0, 0, emptyList(), 0, emptyMap(), outputDir)
            }

            // 首先尝试读取 Android 14+ 的 Bitmap.dumpData (am dumpheap -b png)
            val dumpDataMap = readBitmapDumpData(graph)

            val allBitmaps = mutableListOf<BitmapInfo>()
            val largeBitmaps = mutableListOf<BitmapInfo>()
            val extracted = mutableListOf<BitmapInfo>()
            val hashToBitmaps = mutableMapOf<String, MutableList<BitmapInfo>>()
            val extractedFiles = mutableListOf<Path>()

            // 遍历所有Bitmap实例
            for (instance in graph.instances) {
                if (!isBitmap(bitmapClass, instance)) continue

                val widthField = instance[BITMAP_CLASS_NAME, "mWidth"]
                val heightField = instance[BITMAP_CLASS_NAME, "mHeight"]
                val configField = instance[BITMAP_CLASS_NAME, "mConfig"]

                val width = widthField?.value?.asInt ?: 0
                val height = heightField?.value?.asInt ?: 0
                val pixelCount = width * height

                val configValue = configField?.value?.asInt ?: ARGB_8888
                val configName = configToString(configValue)

                if (width <= 0 || height <= 0) continue

                // 获取 mNativePtr 用于匹配 dumpData
                val nativePtrField = instance[BITMAP_CLASS_NAME, "mNativePtr"]
                val nativePtr = nativePtrField?.value?.asLong ?: 0

                val bitmapInfo = BitmapInfo(
                    objectId = instance.objectId,
                    width = width,
                    height = height,
                    pixelCount = pixelCount,
                    config = configName,
                    configValue = configValue,
                    isLarge = pixelCount >= LARGE_BITMAP_THRESHOLD
                )

                allBitmaps.add(bitmapInfo)
                if (bitmapInfo.isLarge) {
                    largeBitmaps.add(bitmapInfo)
                }

                // 根据参数决定处理哪些Bitmap
                val shouldProcess = if (largeOnly) bitmapInfo.isLarge else true

                if (shouldProcess) {
                    // 提取像素数据 - 优先使用 Android 14+ dumpData 中的压缩数据
                    val imageData = extractBitmapPixels(graph, instance, width, height, configValue, dumpDataMap, nativePtr)

                    if (imageData != null && extractImages) {
                        val hash = calculateHash(imageData)

                        // 保存为PNG
                        val outputFile = saveBitmapAsPng(imageData, width, height, outputDir, instance.objectId, hash)

                        val updatedInfo = bitmapInfo.copy(
                            imageHash = hash,
                            hasData = true
                        )

                        hashToBitmaps.getOrPut(hash) { mutableListOf() }.add(updatedInfo)
                        extracted.add(updatedInfo)
                        extractedFiles.add(outputFile)

                        logger.debug("提取Bitmap: ${width}x${height} hash=${hash.take(16)}")
                    } else if (extractImages) {
                        // 像素数据在native内存中，创建占位图
                        val metadataHash = calculateMetadataHash(width, height, configValue)

                        // 创建占位图
                        val placeholderImage = createPlaceholderImage(width, height, configName, instance.objectId)
                        val outputFile = saveBitmapAsPng(placeholderImage, width, height, outputDir, instance.objectId, metadataHash)

                        val updatedInfo = bitmapInfo.copy(
                            imageHash = metadataHash,
                            hasData = false  // 标记为占位图
                        )

                        hashToBitmaps.getOrPut(metadataHash) { mutableListOf() }.add(updatedInfo)
                        extracted.add(updatedInfo)
                        extractedFiles.add(outputFile)

                        logger.debug("创建占位图: ${width}x${height} config=$configName")
                    }
                }
            }

            // 找出重复的Bitmap
            val duplicates = hashToBitmaps.filterValues { it.size > 1 }

            // 从已提取的Bitmap中筛选大Bitmap（此时已有imageHash）
            val extractedLargeBitmaps = extracted.filter { it.isLarge }

            // 去重：从大Bitmap列表中排除重复的Bitmap
            val duplicateBitmapIds = duplicates.values.flatten().map { it.objectId }.toSet()
            val uniqueLargeBitmaps = extractedLargeBitmaps.filter { it.objectId !in duplicateBitmapIds }

            logger.info("大Bitmap: ${extractedLargeBitmaps.size}, 去重后: ${uniqueLargeBitmaps.size}, 重复组: ${duplicates.size}")

            // 为大Bitmap（仅非重复）和重复Bitmap查找引用链
            val bitmapsWithChains = findReferenceChains(graph, uniqueLargeBitmaps, duplicates)

            val elapsed = System.currentTimeMillis() - startTime
            logger.info("提取完成: ${allBitmaps.size}个Bitmap, ${extracted.size}个已提取, 耗时${elapsed}ms")

            return ExtractionResult(
                totalBitmaps = allBitmaps.size,
                largeBitmaps = largeBitmaps.size,
                largeBitmapsList = bitmapsWithChains.first,  // 带引用链的非重复大Bitmap列表
                extractedBitmaps = extracted.size,
                duplicateGroups = bitmapsWithChains.second,  // 带引用链的重复Bitmap组
                outputDir = outputDir,
                extractedFiles = extractedFiles
            )
        }
    }

    /**
     * 从Bitmap实例提取像素数据
     * 优先使用 Android 14+ dumpData 中的压缩图片数据
     */
    private fun extractBitmapPixels(
        graph: kshark.HeapGraph,
        bitmapInstance: HeapInstance,
        width: Int,
        height: Int,
        config: Int,
        dumpDataMap: Map<Long, ByteArray>?,
        nativePtr: Long
    ): BufferedImage? {
        try {
            // 首先检查是否有 Android 14+ 的 dumpData (来自 am dumpheap -b png)
            if (dumpDataMap != null && nativePtr != 0L) {
                val compressedBytes = dumpDataMap[nativePtr]
                if (compressedBytes != null) {
                    // 从压缩的图片数据 (PNG/JPEG/WEBP) 解码
                    return decodeCompressedImage(compressedBytes)
                }
            }

            // 检查是否有mBuffer字段 (旧版Android，数据在Java heap)
            val bufferField = bitmapInstance[BITMAP_CLASS_NAME, "mBuffer"]
            val bufferObj = bufferField?.value?.asObject

            if (bufferObj != null) {
                // 旧版Bitmap，数据在Java heap
                val byteBuffer = bufferObj.asInstance ?: return null

                // 获取Buffer中的byte数组
                val hbField = byteBuffer["java.nio.HeapByteBuffer", "hb"]
                val hbObj = hbField?.value?.asObject

                if (hbObj is kshark.HeapObject.HeapPrimitiveArray) {
                    // 尝试使用反射读取字节数据
                    val bytes = readPrimitiveArrayBytesReflective(hbObj)

                    if (bytes != null && bytes.size >= width * height * getBytesPerPixel(config)) {
                        return createImageFromBytes(bytes, width, height, config)
                    }
                }

                // 尝试直接从Buffer读取
                val arrayOffset = byteBuffer["java.nio.Buffer", "arrayOffset"]?.value?.asInt ?: 0
                val capacity = byteBuffer["java.nio.Buffer", "capacity"]?.value?.asInt ?: 0
                logger.debug("Buffer: arrayOffset=$arrayOffset, capacity=$capacity")
            } else {
                // 检查是否有mNativePtr字段 (新版Android，数据在native heap)
                val nativePtrField = bitmapInstance[BITMAP_CLASS_NAME, "mNativePtr"]
                if (nativePtrField != null) {
                    // 新版Bitmap，像素数据在native内存中，hprof文件不包含
                    logger.debug("Bitmap使用native内存分配，无法从hprof提取像素数据")
                    return null
                }
            }

        } catch (e: Exception) {
            logger.warn("提取Bitmap像素失败: ${e.message}")
        }

        return null
    }

    /**
     * 根据Bitmap配置获取每像素字节数
     */
    private fun getBytesPerPixel(config: Int): Int {
        return when (config) {
            ALPHA_8 -> 1
            RGB_565, ARGB_4444 -> 2
            ARGB_8888 -> 4
            else -> 4
        }
    }

    /**
     * 读取 Android 14+ 的 Bitmap.dumpData 静态字段
     * 返回一个从 nativePtr 到压缩图片字节数据的映射
     */
    private fun readBitmapDumpData(graph: kshark.HeapGraph): Map<Long, ByteArray>? {
        try {
            val bitmapClass = graph.findClassByName(BITMAP_CLASS_NAME) ?: return null

            // 查找 Bitmap.dumpData 静态字段
            val dumpDataField = bitmapClass["dumpData"]
            if (dumpDataField == null) {
                logger.debug("未找到 dumpData 静态字段")
                return null
            }

            val dumpDataObj = dumpDataField.value?.asObject
            if (dumpDataObj == null) {
                logger.debug("dumpData 字段值为 null")
                return null
            }

            val dumpDataInstance = dumpDataObj.asInstance
            if (dumpDataInstance == null) {
                logger.debug("dumpData 无法转换为 HeapInstance")
                return null
            }

            // 获取 DumpData 实例的类名
            val dumpDataClassName = dumpDataInstance.instanceClass.name

            logger.debug("DumpData实例的类: $dumpDataClassName")

            // DumpData 类的结构:
            // private int count;
            // private int format;
            // private long[] natives;
            // private byte[][] buffers;

            // 读取 count
            val countField = dumpDataInstance[dumpDataClassName, "count"]
            val count = countField?.value?.asInt ?: 0

            logger.debug("dumpData count = $count")

            if (count == 0) {
                logger.info("Bitmap.dumpData 存在但无数据 (count=0)")
                return null
            }

            // 读取 natives 数组
            val nativesField = dumpDataInstance[dumpDataClassName, "natives"]
            val nativesArray = nativesField?.value?.asObject

            // 读取 buffers 数组
            val buffersField = dumpDataInstance[dumpDataClassName, "buffers"]
            val buffersArray = buffersField?.value?.asObject

            logger.debug("natives类型: ${nativesArray?.javaClass?.simpleName}")
            logger.debug("buffers类型: ${buffersArray?.javaClass?.simpleName}")

            if (nativesArray !is kshark.HeapObject.HeapPrimitiveArray ||
                buffersArray !is kshark.HeapObject.HeapObjectArray) {
                logger.warn("Bitmap.dumpData 格式不符合预期: natives=${nativesArray?.javaClass?.simpleName}, buffers=${buffersArray?.javaClass?.simpleName}")
                return null
            }

            // natives 是 long[] 的 primitive array
            val nativesRecord = nativesArray.readRecord()
            val natives = when (nativesRecord) {
                is kshark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.LongArrayDump -> {
                    nativesRecord.array
                }
                else -> longArrayOf()
            }

            val buffers = buffersArray.readRecord().elementIds

            logger.debug("natives数组大小: ${natives.size}, buffers数组大小: ${buffers.size}")

            // 构建映射表
            val result = mutableMapOf<Long, ByteArray>()

            var actualCount = 0
            val maxCount = minOf(natives.size, buffers.size)
            for (i in 0 until maxCount) {
                val nativePtr = natives[i]
                val bufferObjectId = buffers[i]

                logger.debug("[$i] nativePtr=$nativePtr, bufferObjectId=$bufferObjectId")

                if (bufferObjectId == 0L) continue

                // 读取 byte[] 数组内容
                val bufferObj = graph.findObjectById(bufferObjectId)
                logger.debug("[$i] bufferObj类型: ${bufferObj?.javaClass?.simpleName}")

                if (bufferObj is kshark.HeapObject.HeapPrimitiveArray) {
                    val bytes = readPrimitiveArrayBytesReflective(bufferObj)
                    logger.debug("[$i] 读取到 ${bytes?.size} 字节")
                    if (bytes != null && bytes.size > 0) {
                        result[nativePtr] = bytes
                        actualCount++
                    }
                } else if (bufferObj == null) {
                    logger.debug("[$i] bufferObj 为 null")
                }
            }

            logger.info("从 Bitmap.dumpData 读取到 ${result.size}/${count} 个压缩图片")
            return result

        } catch (e: Exception) {
            logger.debug("未找到 Bitmap.dumpData (可能未使用 -b png 参数): ${e.message}, ${e::class.java.simpleName}")
            e.printStackTrace()
            return null
        }
    }

    /**
     * 解码压缩的图片数据 (PNG/JPEG/WEBP)
     */
    private fun decodeCompressedImage(bytes: ByteArray): BufferedImage? {
        return try {
            javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(bytes))
        } catch (e: Exception) {
            logger.warn("解码压缩图片失败: ${e.message}")
            null
        }
    }

    /**
     * 从hprof的primitive array读取字节数据
     * 使用 Shark 的 readRecord() 方法直接读取
     */
    private fun readPrimitiveArrayBytesReflective(array: kshark.HeapObject.HeapPrimitiveArray): ByteArray? {
        try {
            // 使用 Shark 的 readRecord 方法获取记录
            val record = array.readRecord()

            // 根据数组类型提取字节数据
            val bytes = when (record) {
                is kshark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.ByteArrayDump -> {
                    record.array
                }
                else -> {
                    logger.debug("不是 ByteArray 类型: ${record::class.simpleName}")
                    return null
                }
            }

            logger.debug("成功读取${bytes.size}字节")
            return bytes

        } catch (e: Exception) {
            logger.warn("读取primitive array失败: ${e.message}, ${e::class.java.simpleName}")
            e.printStackTrace()
        }
        return null
    }

    /**
     * 创建BufferedImage
     */
    private fun createImageFromBytes(bytes: ByteArray, width: Int, height: Int, config: Int): BufferedImage {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)

        when (config) {
            ARGB_8888 -> {
                // 每个像素4字节: A, R, G, B
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val offset = (y * width + x) * 4
                        if (offset + 3 < bytes.size) {
                            val b = bytes[offset].toInt() and 0xFF
                            val g = bytes[offset + 1].toInt() and 0xFF
                            val r = bytes[offset + 2].toInt() and 0xFF
                            val a = bytes[offset + 3].toInt() and 0xFF
                            val argb = (a shl 24) or (r shl 16) or (g shl 8) or b
                            image.setRGB(x, y, argb)
                        }
                    }
                }
            }
            RGB_565 -> {
                // 每个像素2字节
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val offset = (y * width + x) * 2
                        if (offset + 1 < bytes.size) {
                            val byte1 = bytes[offset].toInt() and 0xFF
                            val byte2 = bytes[offset + 1].toInt() and 0xFF
                            val value = (byte1 shl 8) or byte2

                            val r = ((value shr 11) and 0x1F) * 255 / 31
                            val g = ((value shr 5) and 0x3F) * 255 / 63
                            val b = (value and 0x1F) * 255 / 31

                            image.setRGB(x, y, (255 shl 24) or (r shl 16) or (g shl 8) or b)
                        }
                    }
                }
            }
            ARGB_4444 -> {
                // 每个像素2字节
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val offset = (y * width + x) * 2
                        if (offset + 1 < bytes.size) {
                            val byte1 = bytes[offset].toInt() and 0xFF
                            val byte2 = bytes[offset + 1].toInt() and 0xFF

                            val a = ((byte1 shr 4) and 0x0F) * 255 / 15
                            val r = ((byte1 and 0x0F) * 255 / 15)
                            val g = ((byte2 shr 4) and 0x0F) * 255 / 15
                            val b = (byte2 and 0x0F) * 255 / 15

                            image.setRGB(x, y, (a shl 24) or (r shl 16) or (g shl 8) or b)
                        }
                    }
                }
            }
            ALPHA_8 -> {
                // 每个像素1字节 (只有alpha)
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val offset = y * width + x
                        if (offset < bytes.size) {
                            val a = bytes[offset].toInt() and 0xFF
                            image.setRGB(x, y, (a shl 24) or 0x00FFFFFF)
                        }
                    }
                }
            }
        }

        return image
    }

    /**
     * 保存BufferedImage为PNG
     */
    private fun saveBitmapAsPng(
        image: BufferedImage,
        width: Int,
        height: Int,
        outputDir: Path,
        objectId: Long,
        hash: String
    ): Path {
        val filename = "bitmap_${objectId}_${hash.take(8)}_${width}x${height}.png"
        val outputFile = outputDir.resolve(filename)

        ImageIO.write(image, "PNG", outputFile.toFile())

        return outputFile
    }

    /**
     * 基于Bitmap元数据计算哈希值（用于native内存Bitmap的重复检测）
     */
    private fun calculateMetadataHash(width: Int, height: Int, config: Int): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("native_bitmap".toByteArray())
        digest.update(width.toByteArray())
        digest.update(height.toByteArray())
        digest.update(config.toByteArray())
        val hash = digest.digest()
        return "native_${hash.joinToString("") { "%02x".format(it) }}"
    }

    private fun Int.toByteArray(): ByteArray {
        return byteArrayOf(
            (this shr 24).toByte(),
            (this shr 16).toByte(),
            (this shr 8).toByte(),
            this.toByte()
        )
    }

    /**
     * 创建占位图（用于没有像素数据的Bitmap）
     */
    private fun createPlaceholderImage(width: Int, height: Int, config: String, objectId: Long): BufferedImage {
        // 限制占位图的最大尺寸以避免内存问题
        val scale = if (width > 2000 || height > 2000) {
            minOf(2000.0 / width, 2000.0 / height)
        } else {
            1.0
        }
        val scaledWidth = (width * scale).toInt()
        val scaledHeight = (height * scale).toInt()

        val image = BufferedImage(scaledWidth, scaledHeight, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()

        // 绘制背景 - 根据尺寸生成不同的颜色
        val hue = ((width * height) % 360).toFloat()
        val color1 = java.awt.Color.getHSBColor(hue / 360f, 0.7f, 0.9f)
        val color2 = java.awt.Color.getHSBColor((hue + 180) % 360 / 360f, 0.7f, 0.7f)

        val gradient = java.awt.GradientPaint(
            0f, 0f, color1,
            scaledWidth.toFloat(), scaledHeight.toFloat(), color2
        )
        g.paint = gradient
        g.fillRect(0, 0, scaledWidth, scaledHeight)

        // 绘制边框
        g.paint = java.awt.Color.BLACK
        g.stroke = java.awt.BasicStroke(2f)
        g.drawRect(2, 2, scaledWidth - 4, scaledHeight - 4)

        // 绘制文本信息
        g.paint = java.awt.Color.WHITE
        g.font = java.awt.Font("Monospaced", java.awt.Font.BOLD, 14)

        val lines = listOf(
            "Bitmap (Native Memory)",
            "ID: ${objectId.toString().take(16)}",
            "Size: ${width} x ${height}",
            "Config: $config",
            "Pixel data not in hprof"
        )

        var y = scaledHeight / 2 - (lines.size * 20) / 2
        lines.forEach { line ->
            val fm = g.fontMetrics
            val x = (scaledWidth - fm.stringWidth(line)) / 2
            g.drawString(line, x, y)
            y += 20
        }

        g.dispose()
        return image
    }

    /**
     * 计算数据的哈希值
     */
    private fun calculateHash(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(data)
        return hash.joinToString("") { "%02x".format(it) }
    }

    /**
     * 计算BufferedImage的哈希值
     */
    private fun calculateHash(image: BufferedImage): String {
        val digest = MessageDigest.getInstance("SHA-256")

        // 读取所有像素计算hash
        val width = image.width
        val height = image.height

        for (y in 0 until height) {
            for (x in 0 until width) {
                val rgb = image.getRGB(x, y)
                digest.update((rgb shr 24).toByte())
                digest.update((rgb shr 16).toByte())
                digest.update((rgb shr 8).toByte())
                digest.update(rgb.toByte())
            }
        }

        val hash = digest.digest()
        return hash.joinToString("") { "%02x".format(it) }
    }

    private fun isBitmap(bitmapClass: HeapClass?, instance: HeapInstance): Boolean {
        if (bitmapClass == null) return false
        val hierarchy = instance.instanceClass.classHierarchy.toList()
        return hierarchy.any { it.objectId == bitmapClass.objectId }
    }

    private fun configToString(config: Int): String {
        return when (config) {
            1 -> "ALPHA_8"
            2 -> "RGB_565"
            3 -> "RGB_565"
            4 -> "ARGB_4444"
            5 -> "ARGB_8888"
            else -> "UNKNOWN"
        }
    }

    /**
     * 生成提取报告（文本）
     */
    fun generateReport(result: ExtractionResult): String {
        val sb = StringBuilder()
        sb.appendLine("\n╔══════════════════════════════════════════════════════════════╗")
        sb.appendLine("║                    Bitmap 分析报告                            ║")
        sb.appendLine("╚══════════════════════════════════════════════════════════════╝")
        sb.appendLine()
        sb.appendLine("📊 统计信息")
        sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        sb.appendLine("   总Bitmap数: ${result.totalBitmaps}")
        sb.appendLine("   大Bitmap数(>1M像素): ${result.largeBitmaps}")
        sb.appendLine("   已提取: ${result.extractedBitmaps}")
        sb.appendLine("   重复组数: ${result.duplicateGroups.size}")
        sb.appendLine()

        if (result.duplicateGroups.isNotEmpty()) {
            sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            sb.appendLine("🔄 重复的Bitmap")
            sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

            var totalWaste = 0L
            result.duplicateGroups.forEach { (hash, bitmaps) ->
                val singleSize = bitmaps.first().pixelCount * 4
                val waste = (bitmaps.size - 1) * singleSize
                totalWaste += waste

                sb.appendLine("\n   Hash: ${hash.take(16)}... (${bitmaps.size}个重复)")
                sb.appendLine("   大小: ${bitmaps.first().width}x${bitmaps.first().height}")
                sb.appendLine("   内存浪费: ${formatSize(waste.toLong())}")
                sb.appendLine("   文件:")
                bitmaps.forEach { bitmap ->
                    val file = result.outputDir.resolve("bitmap_${bitmap.objectId}_${hash.take(8)}_${bitmap.width}x${bitmap.height}.png")
                    if (Files.exists(file)) {
                        sb.appendLine("      - ${file.fileName}")
                    }
                }
            }

            sb.appendLine("\n   💰 总内存浪费: ${formatSize(totalWaste)}")
            sb.appendLine()
        }

        if (result.extractedBitmaps > 0) {
            sb.appendLine("📁 图片已保存到: ${result.outputDir.toAbsolutePath()}")
            sb.appendLine()
        }

        return sb.toString()
    }

    /**
     * 生成HTML报告 - bitmap_analysis.html
     *
     * 此报告专注于Bitmap分析，包含所有大Bitmap和重复Bitmap
     * 无论这些Bitmap是否是泄露的都会在此报告中显示
     */
    fun generateHtmlReport(result: ExtractionResult): String {
        val sb = StringBuilder()
        sb.appendLine("<!DOCTYPE html>")
        sb.appendLine("<html lang=\"zh-CN\">")
        sb.appendLine("<head>")
        sb.appendLine("    <meta charset=\"UTF-8\">")
        sb.appendLine("    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">")
        sb.appendLine("    <title>Bitmap 分析报告</title>")
        sb.appendLine("    <style>")
        sb.appendLine("        * { margin: 0; padding: 0; box-sizing: border-box; }")
        sb.appendLine("        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: #f5f5f5; padding: 20px; }")
        sb.appendLine("        .container { max-width: 1200px; margin: 0 auto; background: white; border-radius: 8px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); }")
        sb.appendLine("        .header { background: linear-gradient(135deg, #f093fb 0%, #f5576c 100%); color: white; padding: 30px; border-radius: 8px 8px 0 0; }")
        sb.appendLine("        .header h1 { font-size: 24px; margin-bottom: 10px; }")
        sb.appendLine("        .header .subtitle { opacity: 0.9; font-size: 14px; }")
        sb.appendLine("        .section { padding: 25px; border-bottom: 1px solid #eee; }")
        sb.appendLine("        .section:last-child { border-bottom: none; }")
        sb.appendLine("        .section-title { font-size: 18px; font-weight: 600; color: #333; margin-bottom: 20px; display: flex; align-items: center; }")
        sb.appendLine("        .section-title .icon { margin-right: 10px; font-size: 20px; }")
        sb.appendLine("        .stats-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 15px; }")
        sb.appendLine("        .stat-card { background: #f8f9fa; padding: 15px; border-radius: 6px; border-left: 4px solid #f5576c; }")
        sb.appendLine("        .stat-card .label { font-size: 12px; color: #666; margin-bottom: 5px; }")
        sb.appendLine("        .stat-card .value { font-size: 24px; font-weight: 700; color: #333; }")
        sb.appendLine("        .duplicate-group { background: #fff3e0; padding: 15px; border-radius: 6px; margin-bottom: 15px; }")
        sb.appendLine("        .duplicate-group .hash { font-family: monospace; color: #666; font-size: 12px; word-break: break-all; }")
        sb.appendLine("        .bitmap-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(100px, 1fr)); gap: 10px; margin-top: 15px; }")
        sb.appendLine("        .bitmap-item { background: white; border-radius: 4px; overflow: hidden; box-shadow: 0 1px 3px rgba(0,0,0,0.1); }")
        sb.appendLine("        .bitmap-item img { max-width: 100%; max-height: 80px; object-fit: contain; display: block; margin: 0 auto; }")
        sb.appendLine("        .bitmap-info { padding: 10px; font-size: 12px; color: #666; text-align: center; }")
        sb.appendLine("        .warning { background: #ffebee; border-left: 4px solid #f44336; padding: 15px; border-radius: 6px; margin: 15px 0; }")
        sb.appendLine("        .warning .title { font-weight: 600; color: #c62828; margin-bottom: 10px; }")
        sb.appendLine("        .gallery { display: grid; grid-template-columns: repeat(auto-fill, minmax(120px, 1fr)); gap: 10px; margin-top: 20px; }")
        sb.appendLine("        .gallery-item { position: relative; height: 120px; display: flex; align-items: center; justify-content: center; background: #f5f5f5; border-radius: 4px; overflow: hidden; }")
        sb.appendLine("        .gallery-item img { max-width: 100%; max-height: 100px; object-fit: contain; border-radius: 4px; }")
        sb.appendLine("        .gallery-item .overlay { position: absolute; bottom: 0; left: 0; right: 0; background: rgba(0,0,0,0.6); color: white; padding: 5px; font-size: 11px; border-radius: 0 0 4px 4px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }")
        sb.appendLine("        .info-note { background: #e3f2fd; padding: 15px; border-radius: 6px; margin: 15px 0; font-size: 13px; }")
        sb.appendLine("        .info-note .title { font-weight: 600; color: #1976d2; margin-bottom: 5px; }")
        sb.appendLine("        .info-note .link { color: #1976d2; text-decoration: none; }")
        sb.appendLine("    </style>")
        sb.appendLine("</head>")
        sb.appendLine("<body>")
        sb.appendLine("    <div class=\"container\">")
        sb.appendLine("        <div class=\"header\">")
        sb.appendLine("            <h1>🖼️ Bitmap 分析报告</h1>")
        sb.appendLine("            <div class=\"subtitle\">所有大Bitmap和重复Bitmap分析 (不限于泄露对象)</div>")
        sb.appendLine("        </div>")

        sb.appendLine("        <div class=\"section\">")
        sb.appendLine("            <div class=\"section-title\"><span class=\"icon\">📊</span>统计信息</div>")
        sb.appendLine("            <div class=\"stats-grid\">")
        sb.appendLine("                <div class=\"stat-card\"><div class=\"label\">总Bitmap数</div><div class=\"value\">${result.totalBitmaps}</div></div>")
        sb.appendLine("                <div class=\"stat-card\"><div class=\"label\">大Bitmap(>1M像素)</div><div class=\"value\">${result.largeBitmaps}</div></div>")
        sb.appendLine("                <div class=\"stat-card\"><div class=\"label\">已提取</div><div class=\"value\">${result.extractedBitmaps}</div></div>")
        sb.appendLine("                <div class=\"stat-card\"><div class=\"label\">重复组数</div><div class=\"value\">${result.duplicateGroups.size}</div></div>")
        sb.appendLine("            </div>")

        // 添加说明
        sb.appendLine("            <div class=\"info-note\">")
        sb.appendLine("                <div class=\"title\">📄 报告说明</div>")
        sb.appendLine("                <div>此报告包含<strong>所有</strong>大Bitmap和重复Bitmap，无论它们是否泄露。</div>")
        sb.appendLine("                <div style=\"margin-top: 5px;\">查看 <a href=\"hprof_analysis.html\" class=\"link\">hprof_analysis.html</a> 获取内存泄露分析报告。</div>")
        sb.appendLine("            </div>")
        sb.appendLine("        </div>")

        // 大Bitmap展示
        if (result.largeBitmaps > 0 && result.largeBitmapsList.isNotEmpty()) {
            sb.appendLine("        <div class=\"section\">")
            sb.appendLine("            <div class=\"section-title\"><span class=\"icon\">🔴</span>大Bitmap (>1M像素, ${result.largeBitmaps}张)</div>")

            // 按大小分组
            val sortedBitmaps = result.largeBitmapsList.sortedByDescending { it.pixelCount }

            sortedBitmaps.forEach { bitmap ->
                val hash = bitmap.imageHash ?: "unknown"
                val fileName = "bitmap_${bitmap.objectId}_${hash.take(8)}_${bitmap.width}x${bitmap.height}.png"
                val file = result.outputDir.resolve(fileName)
                val sizeMB = String.format("%.2f", bitmap.pixelCount * 4.0 / (1024 * 1024))

                sb.appendLine("            <div style=\"margin-bottom: 20px; padding: 15px; background: #f8f9fa; border-radius: 6px;\">")

                // 显示图片
                if (Files.exists(file)) {
                    sb.appendLine("                <div style=\"display: flex; align-items: flex-start; gap: 15px;\">")
                    sb.appendLine("                    <img src=\"bitmaps/${fileName}\" alt=\"Bitmap\" style=\"max-width: 150px; max-height: 150px; object-fit: contain; border-radius: 4px;\">")
                    sb.appendLine("                    <div style=\"flex: 1;\">")
                    sb.appendLine("                        <div style=\"font-weight: 600; margin-bottom: 5px;\">${bitmap.width}x${bitmap.height} (${sizeMB}MB)</div>")
                    sb.appendLine("                        <div style=\"font-size: 12px; color: #666;\">${fileName}</div>")
                    sb.appendLine("                    </div>")
                    sb.appendLine("                </div>")
                }

                // 显示引用链
                if (bitmap.referenceChain.isNotEmpty()) {
                    sb.appendLine("                <div style=\"margin-top: 10px;\">")
                    sb.appendLine("                    <div style=\"font-size: 12px; font-weight: 600; color: #666; margin-bottom: 5px;\">引用链 (GC Root → Bitmap):</div>")
                    sb.appendLine("                    <div style=\"font-size: 11px; color: #888; line-height: 1.6;\">")

                    // 对于短引用链（≤15个节点）全部显示，长引用链截断
                    val displayLimit = if (bitmap.referenceChain.size <= 15) {
                        bitmap.referenceChain.size
                    } else {
                        15
                    }

                    bitmap.referenceChain.take(displayLimit).forEachIndexed { index, node ->
                        val arrow = if (index < bitmap.referenceChain.size - 1) " → " else ""
                        sb.appendLine("                        ${node.fullName}$arrow<br>")
                    }
                    if (bitmap.referenceChain.size > displayLimit) {
                        sb.appendLine("                        ... (还有${bitmap.referenceChain.size - displayLimit}个节点)")
                    }
                    sb.appendLine("                    </div>")
                    sb.appendLine("                </div>")
                }

                sb.appendLine("            </div>")
            }
            sb.appendLine("        </div>")
        }

        // 重复Bitmap组 (放在已提取Bitmap之前)
        if (result.duplicateGroups.isNotEmpty()) {
            sb.appendLine("        <div class=\"section\">")
            sb.appendLine("            <div class=\"section-title\"><span class=\"icon\">🔄</span>重复的Bitmap (${result.duplicateGroups.size}组)</div>")

            var totalWaste = 0L
            result.duplicateGroups.forEach { (hash, bitmaps) ->
                val singleSize = bitmaps.first().pixelCount * 4
                val waste = (bitmaps.size - 1) * singleSize
                totalWaste += waste

                sb.appendLine("            <div class=\"duplicate-group\">")
                sb.appendLine("                <div class=\"hash\">SHA-256: $hash</div>")
                sb.appendLine("                <div><strong>尺寸:</strong> ${bitmaps.first().width} × ${bitmaps.first().height} (${bitmaps.first().config})</div>")
                sb.appendLine("                <div><strong>重复次数:</strong> ${bitmaps.size}</div>")
                sb.appendLine("                <div><strong>浪费:</strong> ${formatSize(waste.toLong())}</div>")

                // 显示该组的图片和引用链
                bitmaps.forEach { bitmap ->
                    val fileName = "bitmap_${bitmap.objectId}_${hash.take(8)}_${bitmap.width}x${bitmap.height}.png"
                    val file = result.outputDir.resolve(fileName)

                    sb.appendLine("                <div style=\"margin-top: 10px; padding: 10px; background: white; border-radius: 4px;\">")

                    // 显示图片
                    if (Files.exists(file)) {
                        sb.appendLine("                    <div style=\"display: flex; align-items: center; gap: 10px; margin-bottom: 8px;\">")
                        sb.appendLine("                        <img src=\"bitmaps/$fileName\" alt=\"Bitmap\" style=\"max-width: 80px; max-height: 80px; object-fit: contain;\">")
                        sb.appendLine("                        <div style=\"flex: 1; font-size: 12px;\">")
                        sb.appendLine("                            <div><strong>文件:</strong> $fileName</div>")
                        sb.appendLine("                        </div>")
                        sb.appendLine("                    </div>")
                    }

                    // 显示引用链
                    if (bitmap.referenceChain.isNotEmpty()) {
                        sb.appendLine("                    <div style=\"font-size: 11px; color: #666; background: #f5f5f5; padding: 8px; border-radius: 4px;\">")
                        sb.appendLine("                        <div style=\"font-weight: 600; margin-bottom: 4px;\">引用链:</div>")

                        // 对于短引用链（≤15个节点）全部显示，长引用链截断
                        val displayLimit = if (bitmap.referenceChain.size <= 15) {
                            bitmap.referenceChain.size
                        } else {
                            15
                        }

                        bitmap.referenceChain.take(displayLimit).forEachIndexed { index, node ->
                            val arrow = if (index < bitmap.referenceChain.size - 1) " → " else ""
                            sb.appendLine("                        ${node.fullName}$arrow<br>")
                        }
                        if (bitmap.referenceChain.size > displayLimit) {
                            sb.appendLine("                        ... (还有${bitmap.referenceChain.size - displayLimit}个)")
                        }
                        sb.appendLine("                    </div>")
                    }

                    sb.appendLine("                </div>")
                }

                sb.appendLine("            </div>")
            }

            sb.appendLine("        </div>")

            if (totalWaste > 0) {
                sb.appendLine("        <div class=\"warning\">")
                sb.appendLine("            <div class=\"title\">⚠️ 内存浪费:</div>")
                sb.appendLine("            重复Bitmap导致约 ${formatSize(totalWaste)} 的内存浪费")
                sb.appendLine("        </div>")
            }
        }

        // 所有Bitmap画廊 (放在重复Bitmap之后)
        if (result.extractedBitmaps > 0) {
            sb.appendLine("        <div class=\"section\">")
            sb.appendLine("            <div class=\"section-title\"><span class=\"icon\">🖼️</span>所有已提取Bitmap (${result.extractedBitmaps}张)</div>")

            sb.appendLine("            <div class=\"gallery\">")

            // 按hash分组显示，每个hash只显示一个
            val seenHashes = mutableSetOf<String>()
            result.extractedFiles.forEach { filePath ->
                val fileName = filePath.fileName.toString()
                // 从文件名提取hash
                val hashMatch = Regex("bitmap_\\d+_([0-9a-f]+)_\\d+x\\d+\\.png").find(fileName)
                val hash = hashMatch?.groupValues?.get(1) ?: ""

                val isDuplicate = result.duplicateGroups.containsKey(hash) && seenHashes.add(hash).not()
                val badge = if (isDuplicate) " 🔄" else ""

                sb.appendLine("                <div class=\"gallery-item\">")
                sb.appendLine("                    <img src=\"bitmaps/${fileName}\" alt=\"Bitmap\">")
                sb.appendLine("                    <div class=\"overlay\">${fileName}$badge</div>")
                sb.appendLine("                </div>")
            }

            sb.appendLine("            </div>")
            sb.appendLine("        </div>")
        }

        sb.appendLine("    </div>")
        sb.appendLine("</body>")
        sb.appendLine("</html>")

        return sb.toString()
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 -> "%.2f MB".format(bytes / (1024.0 * 1024))
            bytes >= 1024 -> "%.2f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    /**
     * 为大Bitmap和重复Bitmap查找到GC Root的引用链
     */
    private fun findReferenceChains(
        graph: kshark.HeapGraph,
        largeBitmaps: List<BitmapInfo>,
        duplicateGroups: Map<String, List<BitmapInfo>>
    ): Pair<List<BitmapInfo>, Map<String, List<BitmapInfo>>> {
        // 需要查找引用链的Bitmap集合
        val bitmapsWithoutChain = mutableSetOf<BitmapInfo>()
        bitmapsWithoutChain.addAll(largeBitmaps)
        duplicateGroups.values.forEach { bitmapsWithoutChain.addAll(it) }

        // 为每个Bitmap查找引用链
        val updatedBitmaps = mutableMapOf<Long, BitmapInfo>()
        bitmapsWithoutChain.forEach { bitmap ->
            val chain = findReferenceChainForBitmap(graph, bitmap.objectId)
            updatedBitmaps[bitmap.objectId] = bitmap.copy(referenceChain = chain)
        }

        // 更新大Bitmap列表
        val updatedLargeBitmaps = largeBitmaps.map { updatedBitmaps[it.objectId] ?: it }

        // 更新重复Bitmap组
        val updatedDuplicates = duplicateGroups.mapValues { (_, bitmaps) ->
            bitmaps.map { updatedBitmaps[it.objectId] ?: it }
        }

        return Pair(updatedLargeBitmaps, updatedDuplicates)
    }

    /**
     * 查找单个Bitmap的引用链
     * 使用HeapAnalyzer查找路径
     */
    private fun findReferenceChainForBitmap(
        graph: kshark.HeapGraph,
        bitmapObjectId: Long
    ): List<ReferenceNode> {
        val result = mutableListOf<ReferenceNode>()

        try {
            val bitmapObject = graph.findObjectById(bitmapObjectId)
            if (bitmapObject == null) {
                logger.warn("无法找到Bitmap对象: $bitmapObjectId")
                return emptyList()
            }

            // 使用HeapAnalyzer查找引用链
            val leakingObjectFinder = object : kshark.LeakingObjectFinder {
                override fun findLeakingObjectIds(graph: kshark.HeapGraph): Set<Long> {
                    return setOf(bitmapObjectId)
                }
            }

            val analyzer = kshark.HeapAnalyzer(
                listener = kshark.OnAnalysisProgressListener { _ -> }
            )

            // 执行分析
            val analysis = analyzer.analyze(
                heapDumpFile = java.io.File("dummy.hprof"),  // 实际文件不重要，因为我们有graph
                graph = graph,
                leakingObjectFinder = leakingObjectFinder,
                referenceMatchers = kshark.AndroidReferenceMatchers.appDefaults,
                computeRetainedHeapSize = false,
                objectInspectors = emptyList()
            )

            if (analysis is kshark.HeapAnalysisSuccess) {
                // 尝试从applicationLeaks获取
                val leakTrace = analysis.applicationLeaks.firstOrNull()?.leakTraces?.firstOrNull()
                if (leakTrace != null) {
                    result.addAll(buildReferenceChain(leakTrace.referencePath))
                } else {
                    // 尝试从libraryLeaks获取
                    val libLeakTrace = analysis.libraryLeaks.firstOrNull()?.leakTraces?.firstOrNull()
                    if (libLeakTrace != null) {
                        result.addAll(buildReferenceChain(libLeakTrace.referencePath))
                    } else {
                        logger.debug("未找到Bitmap $bitmapObjectId 的引用链 (applicationLeaks=${analysis.applicationLeaks.size}, libraryLeaks=${analysis.libraryLeaks.size})")
                    }
                }
            } else {
                logger.warn("Bitmap $bitmapObjectId 分析失败")
            }
        } catch (e: Exception) {
            logger.warn("查找引用链失败: $bitmapObjectId, ${e.message}")
        }

        return result
    }

    /**
     * 从LeakTraceReference构建引用链
     */
    private fun buildReferenceChain(referencePath: List<kshark.LeakTraceReference>): List<ReferenceNode> {
        return referencePath.map { ref ->
            ReferenceNode(
                className = ref.originObject.className,
                fieldName = ref.referenceName
            )
        }
    }
}
