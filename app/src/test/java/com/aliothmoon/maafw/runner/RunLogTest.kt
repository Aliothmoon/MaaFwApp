package com.aliothmoon.maafw.runner

import org.junit.Assert.assertEquals
import org.junit.Test

class RunLogTest {
    @Test
    fun `progress log text prefers the display label`() {
        assertEquals("每日启动 1/3", RunnerEvent.Progress("internal", 1, 3, "每日启动").toLogText())
    }

    @Test
    fun `progress log text falls back to the task name`() {
        assertEquals("internal 1/3", RunnerEvent.Progress("internal", 1, 3).toLogText())
    }
}
