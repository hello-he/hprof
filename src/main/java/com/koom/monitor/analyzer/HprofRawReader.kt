package com.koom.monitor.analyzer

import kshark.HeapGraph
import kshark.HeapObject.HeapInstance
import kshark.HeapObject.HeapPrimitiveArray
import kshark.HprofHeapGraph
import org.slf4j.LoggerFactory
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Hprof原始数据读取器 - 直接从hprof文件读取字节数据
 */
class HprofRawReader(
    private val hprofFile: java.io.File,
    private val heapGraph: HprofHeapGraph
) {

    private val logger = LoggerFactory.getLogger(HprofRawReader::class.java)

    // 保存hprof文件header信息
    private val headerSize: Int
    private val identifierSize: Int

    init {
        // 读取hprof文件头获取基本信息
        val raf = RandomAccessFile(hprofFile, "r")
        headerSize = readHprofHeader(raf)
        identifierSize = readIdentifierSize(raf)
        raf.close()
    }

    /**
     * 读取primitive array的原始字节数据
     */
    fun readPrimitiveArrayBytes(array: HeapPrimitiveArray): ByteArray? {
        try {
            val objectId = array.objectId

            // 通过HprofInMemoryIndex获取array的position
            // 这需要访问Shark的内部API
            val record = array.readRecord()
            logger.debug("尝试读取primitive array: objectId=$objectId, size=${record.size}")

            // 由于Shark不暴露内部API，我们需要直接读取hprof文件
            val raf = RandomAccessFile(hprofFile, "r")

            // 这是一个简化实现，需要实际解析hprof格式
            // 实际项目中可能需要修改Shark来暴露读取接口

            raf.close()

        } catch (e: Exception) {
            logger.warn("读取primitive array失败: ${e.message}")
        }
        return null
    }

    /**
     * 尝试通过反射读取primitive array的字节数据
     */
    fun readPrimitiveArrayBytesReflective(array: HeapPrimitiveArray): ByteArray? {
        try {
            // 获取IndexedObject
            val indexedObjectField = array.javaClass.getDeclaredField("indexedObject")
            indexedObjectField.isAccessible = true
            val indexedObject = indexedObjectField.get(array)

            if (indexedObject != null) {
                val indexedClass = indexedObject.javaClass

                // 读取position和recordSize
                val positionField = indexedClass.getDeclaredField("position")
                positionField.isAccessible = true
                val position = positionField.getLong(indexedObject)

                val recordSizeField = indexedClass.getDeclaredField("recordSize")
                recordSizeField.isAccessible = true
                val recordSize = recordSizeField.getInt(indexedObject)

                // 获取HprofHeapGraph的reader
                val graphField = array.javaClass.getDeclaredField("graph")
                graphField.isAccessible = true
                val graph = graphField.get(array)

                if (graph != null) {
                    // 获取HprofHeapGraph的reader
                    val graphClass = Class.forName("kshark.HprofHeapGraph")
                    val readerField = graphClass.getDeclaredField("reader")
                    readerField.isAccessible = true
                    val reader = readerField.get(graph)

                    if (reader != null) {
                        // 读取record
                        val readMethod = reader.javaClass.getMethod(
                            "readRecord",
                            Long::class.java,
                            Long::class.java,
                            Class.forName("kshark.internal.HprofRecordReader")
                        )

                        val wrapper = Class.forName("kshark.internal.HprofRecordReader")
                        val wrapMethod = wrapper.getDeclaredMethod("readPrimitiveArrayDumpRecord")

                        val recordReader = wrapMethod.invoke(readMethod.invoke(
                            reader,
                            position,
                            recordSize.toLong()
                        ))

                        // 获取bytes
                        val bytesField = recordReader.javaClass.getDeclaredField("array")
                        bytesField.isAccessible = true
                        val bytes = bytesField.get(recordReader) as? ByteArray

                        if (bytes != null) {
                            logger.debug("成功读取${bytes.size}字节")
                            return bytes
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn("反射读取失败: ${e.message}")
        }
        return null
    }

    /**
     * 从hprof文件直接读取Bitmap的像素数据
     */
    fun readBitmapPixels(bitmapInstance: HeapInstance): Pair<ByteArray, Int>? {
        try {
            // 获取mBuffer
            val bufferField = bitmapInstance["android.graphics.Bitmap", "mBuffer"]
                ?: return null
            val bufferObj = bufferField.value?.asObject ?: return null
            val byteBuffer = bufferObj.asInstance ?: return null

            // 获取HeapByteBuffer的hb字段
            val hbField = byteBuffer["java.nio.HeapByteBuffer", "hb"]
                ?: return null
            val hbObj = hbField.value?.asObject ?: return null

            if (hbObj !is HeapPrimitiveArray) return null

            // 读取字节数据
            val bytes = readPrimitiveArrayBytesReflective(hbObj)

            if (bytes != null) {
                val widthField = bitmapInstance["android.graphics.Bitmap", "mWidth"]
                    ?: return null
                val heightField = bitmapInstance["android.graphics.Bitmap", "mHeight"]
                    ?: return null
                val width = widthField.value.asInt ?: 0
                val height = heightField.value.asInt ?: 0
                val configField = bitmapInstance["android.graphics.Bitmap", "mConfig"]
                    ?: return null
                val config = configField.value.asInt ?: 5

                return Pair(bytes, config)
            }
        } catch (e: Exception) {
            logger.warn("读取Bitmap像素失败: ${e.message}")
        }
        return null
    }

    private fun readHprofHeader(raf: RandomAccessFile): Int {
        // hprof header格式:
        // MAGIC (4 bytes) + version number (4 bytes) + identifier size (4 bytes)
        raf.seek(0)
        val magic = ByteArray(4)
        raf.read(magic)
        val version = ByteArray(4)
        raf.read(version)
        return 12 // header size is fixed
    }

    private fun readIdentifierSize(raf: RandomAccessFile): Int {
        raf.seek(12)
        val idSize = ByteArray(4)
        raf.read(idSize)
        return ByteBuffer.wrap(idSize).order(ByteOrder.BIG_ENDIAN).int
    }
}
