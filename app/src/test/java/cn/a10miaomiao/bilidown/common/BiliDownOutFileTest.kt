package cn.a10miaomiao.bilidown.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BiliDownOutFileTest {

    @Test
    fun `sanitize strips reserved windows chars`() {
        // 反斜杠、斜杠、冒号、星号、问号、引号、尖括号、竖线均应剔除
        assertEquals(
            "abcdefghij",
            BiliDownOutFile.sanitizeFileName(
                """a\b/c:d*e?f"g<h>i|j"""
            )
        )
    }

    @Test
    fun `sanitize strips all whitespace including spaces`() {
        // 空格 / 制表符 / 换行均应剔除（文件名自动去空格）
        assertEquals("中文标题123", BiliDownOutFile.sanitizeFileName(" 中文 \t标题 123 \n"))
    }

    @Test
    fun `sanitize keeps normal and full-width content`() {
        // 合法字符与中文应完整保留，包括全角标点
        assertEquals("测试-视频.第1期", BiliDownOutFile.sanitizeFileName("测试-视频.第1期"))
    }

    @Test
    fun `sanitize all-invalid produces empty`() {
        assertTrue(BiliDownOutFile.sanitizeFileName("   ").isEmpty())
        assertTrue(BiliDownOutFile.sanitizeFileName("***").isEmpty())
    }
}