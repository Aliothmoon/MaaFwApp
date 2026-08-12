package com.aliothmoon.maafw.ui

import androidx.compose.ui.graphics.Color
import com.aliothmoon.maafw.ui.components.ansiAnnotated
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 映射得上的才上色，映射不上的普通展示；无论如何转义符都不能落到正文里 */
class AnsiAnnotatedTextTest {

    private val warning = Color.Yellow

    /** 只认 33，其余一律没有相似档 */
    private val resolver: (Int) -> Color? = { if (it == 33) warning else null }

    @Test
    fun `没有转义符时原样返回`() {
        val out = ansiAnnotated("plain text", resolver)
        assertEquals("plain text", out.text)
        assertTrue(out.spanStyles.isEmpty())
    }

    @Test
    fun `映射得上的段落上色，转义符不进正文`() {
        val out = ansiAnnotated("\u001B[33m掉落上报已禁用\u001B[0m 后续", resolver)
        assertEquals("掉落上报已禁用 后续", out.text)
        assertEquals(1, out.spanStyles.size)
        val span = out.spanStyles.single()
        assertEquals(warning, span.item.color)
        assertEquals(0, span.start)
        assertEquals("掉落上报已禁用".length, span.end)
    }

    /** M9A 的 CRITICAL 是 `41m` + `37m`：背景色与白色都没有相似档，但转义仍要吃掉 */
    @Test
    fun `映射不上的档只吃转义不上色`() {
        val out = ansiAnnotated("\u001B[41m\u001B[37m严重\u001B[0m", resolver)
        assertEquals("严重", out.text)
        assertTrue("没有相似档就该普通展示", out.spanStyles.isEmpty())
    }

    @Test
    fun `重置之后的文本不再带色`() {
        val out = ansiAnnotated("\u001B[33m黄\u001B[0m白", resolver)
        assertEquals("黄白", out.text)
        assertEquals(1, out.spanStyles.size)
        assertEquals(1, out.spanStyles.single().end)
    }

    /** 光标移动之类的 CSI 在 Android 上没有对应语义，吃掉即可 */
    @Test
    fun `非 SGR 的 CSI 被吃掉`() {
        val out = ansiAnnotated("\u001B[2J\u001B[1;1H清屏后", resolver)
        assertEquals("清屏后", out.text)
        assertTrue(out.spanStyles.isEmpty())
    }

    /** 攒批可能把一行从转义序列中间切开，剩下的宁可原样露出也不吞掉正文 */
    @Test
    fun `截断的序列原样保留`() {
        val out = ansiAnnotated("尾部\u001B[33", resolver)
        assertEquals("尾部\u001B[33", out.text)
    }
}
