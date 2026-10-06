package com.aliothmoon.maafw.runner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentExitCodeTest {

    @Test
    fun `exit codes above 128 are signals`() {
        assertEquals(11, AgentExitCode.signalOf(139))
        assertEquals(6, AgentExitCode.signalOf(134))
        assertEquals(9, AgentExitCode.signalOf(137))
    }

    @Test
    fun `plain exit codes are not signals`() {
        assertNull(AgentExitCode.signalOf(0))
        assertNull(AgentExitCode.signalOf(1))
        assertNull(AgentExitCode.signalOf(127))
        assertNull(AgentExitCode.signalOf(128))
    }

    @Test
    fun `unknown signals fall back to the number`() {
        assertEquals("SIGSEGV", AgentExitCode.signalName(11))
        assertEquals("signal 40", AgentExitCode.signalName(40))
    }

    /** SIGKILL、SIGTERM 不经过 debuggerd，去 crash 缓冲区里找只是白等 */
    @Test
    fun `only signals handled by debuggerd leave a dump`() {
        assertTrue(AgentExitCode.leavesCrashDump(11))
        assertTrue(AgentExitCode.leavesCrashDump(6))
        assertFalse(AgentExitCode.leavesCrashDump(9))
        assertFalse(AgentExitCode.leavesCrashDump(15))
    }
}
