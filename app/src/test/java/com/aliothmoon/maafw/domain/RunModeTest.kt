package com.aliothmoon.maafw.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class RunModeTest {

    @Test
    fun `允许前台时按存的值取`() {
        assertEquals(RunMode.FOREGROUND, RunMode.resolve("FOREGROUND", foregroundAllowed = true))
        assertEquals(RunMode.BACKGROUND, RunMode.resolve("BACKGROUND", foregroundAllowed = true))
    }

    @Test
    fun `配方关了前台时一律后台`() {
        assertEquals(RunMode.BACKGROUND, RunMode.resolve("FOREGROUND", foregroundAllowed = false))
        assertEquals(RunMode.BACKGROUND, RunMode.resolve("BACKGROUND", foregroundAllowed = false))
    }

    @Test
    fun `读不出来的值回到后台`() {
        assertEquals(RunMode.BACKGROUND, RunMode.resolve("", foregroundAllowed = true))
        assertEquals(RunMode.BACKGROUND, RunMode.resolve("PRIMARY", foregroundAllowed = true))
    }
}
