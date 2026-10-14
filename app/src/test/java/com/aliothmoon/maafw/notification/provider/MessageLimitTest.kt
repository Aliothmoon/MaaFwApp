package com.aliothmoon.maafw.notification.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageLimitTest {

    private val body = "执行失败: 连接 Shizuku 特权进程失败"
    private val logs = (1..50).joinToString("\n") { "[10:00:%02d] 第 $it 行日志".format(it) }
    private val message = "$body\n\n$logs"

    @Test
    fun `short messages pass through untouched`() {
        assertEquals(message, message.fitChars(message.length))
        assertEquals(message, message.fitUtf8(message.encodeToByteArray().size))
    }

    /** 外壳是正文在前、日志在后：失败原因必须留住，裁掉的是最旧的日志 */
    @Test
    fun `the body and the newest log lines survive`() {
        val fitted = message.fitChars(200)

        assertTrue(fitted.length <= 200)
        assertTrue(fitted.startsWith("$body\n\n[...]"))
        assertTrue(fitted.endsWith("第 50 行日志"))
        assertFalse(fitted.contains("第 1 行日志"))
    }

    @Test
    fun `byte limits count utf8 bytes`() {
        val fitted = message.fitUtf8(300)

        assertTrue(fitted.encodeToByteArray().size <= 300)
        assertTrue(fitted.startsWith(body))
        assertTrue(fitted.endsWith("第 50 行日志"))
    }

    @Test
    fun `an oversized body keeps its head`() {
        val long = "失败".repeat(100)

        val fitted = long.fitChars(50)

        assertTrue(fitted.length <= 50)
        assertTrue(fitted.startsWith("失败失败"))
        assertTrue(fitted.endsWith("[...]"))
    }

    /** 切在 emoji 中间时不能留下半个代理项 */
    @Test
    fun `a cut never leaves half a surrogate pair`() {
        val fitted = "😀".repeat(20).fitChars(16)

        assertTrue(fitted.length <= 16)
        val loneHigh = fitted.indices.any { i ->
            fitted[i].isHighSurrogate() && fitted.getOrNull(i + 1)?.isLowSurrogate() != true
        }
        assertFalse(loneHigh)
    }
}
