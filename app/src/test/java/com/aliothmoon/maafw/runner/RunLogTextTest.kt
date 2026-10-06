package com.aliothmoon.maafw.runner

import org.junit.Assert.assertEquals
import org.junit.Test

/** 屏保那一行用的 [toLogText] */
class RunLogTextTest {

    @Test
    fun `focus markup is stripped`() {
        assertEquals("体力不足，已停止", focus("""<span style="color: red">体力不足</span>，**已停止**""").toLogText())
    }

    /** 屏保据此不拿空行盖掉上一句 */
    @Test
    fun `an image-only focus leaves nothing to show`() {
        assertEquals("", focus("![](file:///sdcard/a.png)").toLogText())
    }

    @Test
    fun `progress prefers the frozen task label`() {
        val progress = RunnerEvent.Progress("StartUp", 1, 3)

        assertEquals("启动游戏 1/3", progress.toLogText("启动游戏"))
        assertEquals("StartUp 1/3", progress.toLogText(" "))
        assertEquals("StartUp 1/3", progress.toLogText())
    }

    private fun focus(content: String) = RunnerEvent.Focus(
        FocusMessage(
            message = "Node.PipelineNode.Succeeded",
            content = content,
            channels = setOf(FocusChannel.Log),
            trace = false,
        ),
    )
}
