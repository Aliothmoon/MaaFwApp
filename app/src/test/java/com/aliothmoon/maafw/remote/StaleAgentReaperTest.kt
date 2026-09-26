package com.aliothmoon.maafw.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StaleAgentReaperTest {

    @Test
    fun `parent pid is read from the PPid line`() {
        val status = "Name:\tpython3\nState:\tS (sleeping)\nTgid:\t1666\nPid:\t1666\nPPid:\t1\nTracerPid:\t0\n"
        assertEquals(1, StaleAgentReaper.parentPid(status))
        assertNull(StaleAgentReaper.parentPid("Name:\tx\n"))
    }

    @Test
    fun `marker must match a whole environ entry`() {
        fun environ(vararg entries: String) = entries.joinToString("\u0000", postfix = "\u0000").toByteArray()

        assertTrue(StaleAgentReaper.hasMarker(environ("PATH=/system/bin", "PI_CLIENT_NAME=MaaFwApp")))
        // 别的客户端注入的同名变量不算
        assertFalse(StaleAgentReaper.hasMarker(environ("PI_CLIENT_NAME=MXU")))
        assertFalse(StaleAgentReaper.hasMarker(environ("X=PI_CLIENT_NAME=MaaFwApp")))
    }
}
