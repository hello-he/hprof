package com.koom.monitor.util

import org.junit.Test
import org.junit.Assert.*

/**
 * 工具类测试
 */
class FormatUtilsTest {

    @Test
    fun testFormatSize() {
        // 测试字节格式化
        assertEquals("1023B", formatSize(1023))
        assertEquals("1.00KB", formatSize(1024))
        assertEquals("1.50KB", formatSize(1536))
        assertEquals("1.00MB", formatSize(1024 * 1024))
        assertEquals("1.00GB", formatSize(1024 * 1024 * 1024))
    }

    @Test
    fun testFormatCount() {
        assertEquals("1,000", formatCount(1000))
        assertEquals("1,234,567", formatCount(1234567))
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "${bytes}B"
            bytes < 1024 * 1024 -> String.format("%.2fKB", bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> String.format("%.2fMB", bytes / (1024.0 * 1024))
            else -> String.format("%.2fGB", bytes / (1024.0 * 1024 * 1024))
        }
    }

    private fun formatCount(count: Int): String {
        return "%,d".format(count)
    }
}
