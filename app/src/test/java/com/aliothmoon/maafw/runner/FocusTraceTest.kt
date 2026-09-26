package com.aliothmoon.maafw.runner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val FAILED = "Node.PipelineNode.Failed"
private const val SUCCEEDED = "Node.PipelineNode.Succeeded"

class FocusTraceTest {

    @Test
    fun `缺省 trace 只有 Failed 算真`() {
        val failed = FocusParser.parseAll(FAILED, """{"focus":{"$FAILED":"炸了"}}""").single()
        assertTrue(failed.trace)

        val succeeded = FocusParser.parseAll(SUCCEEDED, """{"focus":{"$SUCCEEDED":"好了"}}""").single()
        assertFalse(succeeded.trace)
    }

    @Test
    fun `显式 trace 压过默认值`() {
        val off = FocusParser.parseAll(
            FAILED,
            """{"focus":{"$FAILED":{"content":"炸了","trace":false}}}""",
        ).single()
        assertFalse(off.trace)

        val on = FocusParser.parseAll(
            SUCCEEDED,
            """{"focus":{"$SUCCEEDED":{"content":"好了","trace":true}}}""",
        ).single()
        assertTrue(on.trace)
    }

    /** 只配 trace 不配 content 是协议允许的写法，不能整条丢掉 */
    @Test
    fun `没有正文的 trace 条目照样产出`() {
        val entry = FocusParser.parseAll(SUCCEEDED, """{"focus":{"$SUCCEEDED":{"trace":true}}}""").single()
        assertTrue(entry.trace)
        assertFalse(entry.displayable)
        assertEquals("", entry.content)
    }

    @Test
    fun `既无正文又不上报的条目不往下游发`() {
        assertTrue(FocusParser.parseAll(SUCCEEDED, """{"focus":{"$SUCCEEDED":{"display":"log"}}}""").isEmpty())
    }

    @Test
    fun `事件名带进消息体`() {
        val entry = FocusParser.parseAll(FAILED, """{"focus":{"$FAILED":"炸了"}}""").single()
        assertEquals(FAILED, entry.message)
        assertTrue(entry.displayable)
    }
}
