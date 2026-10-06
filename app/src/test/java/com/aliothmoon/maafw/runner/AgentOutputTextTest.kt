package com.aliothmoon.maafw.runner

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentOutputTextTest {

    private fun output(vararg lines: String) = RunnerEvent.AgentOutput(lines.joinToString("\n"), fromStderr = false)

    @Test
    fun `a batch without html stays whole`() {
        val batch = output("first log", "second log")
        assertEquals(listOf(batch), batch.splitUserFacing())
    }

    /** go-service 的 ERR 日志与紧跟着的 HTML 警告常被攒进同一批 */
    @Test
    fun `html lines are split out of a mixed batch`() {
        val batch = output("ERR resolution check failed", "<span>警告</span> <br/>已停止", "next log", "another log")
        assertEquals(
            listOf(
                output("ERR resolution check failed"),
                output("<span>警告</span> <br/>已停止"),
                output("next log", "another log"),
            ),
            batch.splitUserFacing(),
        )
    }

    @Test
    fun `adjacent html lines stay together`() {
        val batch = output("<b>one</b>", "<b>two</b>")
        assertEquals(listOf(batch), batch.splitUserFacing())
    }

    /** go-service 的模板除了 span / div / br，还有表格和内联样式 */
    @Test
    fun `table and style tags count as html`() {
        assertEquals(true, isUserFacingAgentLine("""<table style="width: 100%;"><tr><td>武器</td></tr></table>"""))
        assertEquals(true, isUserFacingAgentLine("<style>.card { color: red; }</style><div>卡片</div>"))
        assertEquals(false, isUserFacingAgentLine("map<string, int> table"))
    }

    @Test
    fun `the stream is kept on every part`() {
        val batch = RunnerEvent.AgentOutput("log\n<b>html</b>", fromStderr = true)
        assertEquals(listOf(true, true), batch.splitUserFacing().map { it.fromStderr })
    }
}
