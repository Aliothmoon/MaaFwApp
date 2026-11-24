package com.aliothmoon.maafw.remote

import com.aliothmoon.maafw.maa.MaaAgentClientLibrary
import com.sun.jna.Pointer
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class MaaRunnerConnectTest {

    private val client = Pointer.createConstant(1L)

    private class FakeSession(var alive: Boolean = true) : AgentSession {
        override val executable: String = "/x/libgo-service.so"
        var exitReportAwaited = false

        override fun isAlive(): Boolean = alive
        override fun expectExit() {}
        override fun close() {}
        override fun awaitExitReport() {
            exitReportAwaited = true
        }
    }

    @Test
    fun `peer becoming alive completes connect handshake`() {
        val polls = AtomicInteger(0)
        val agentLib = mockk<MaaAgentClientLibrary>(relaxed = true) {
            every { MaaAgentClientAlive(client) } answers { if (polls.incrementAndGet() >= 3) 1 else 0 }
            every { MaaAgentClientConnect(client) } returns 1
        }
        val session = FakeSession(alive = true)

        val outcome = awaitAgentConnect(
            agentLib = agentLib,
            client = client,
            session = session,
            timeoutMillis = 5_000L,
            stopRequested = { false },
            pollMillis = 5L,
        )

        assertEquals(AgentConnectOutcome.CONNECTED, outcome)
        verify(exactly = 1) { agentLib.MaaAgentClientSetTimeout(client, any()) }
        verify(exactly = 1) { agentLib.MaaAgentClientConnect(client) }
    }

    @Test
    fun `early child exit fails fast and drains exit report before close`() {
        val agentLib = mockk<MaaAgentClientLibrary>(relaxed = true) {
            every { MaaAgentClientAlive(client) } returns 0
        }
        val session = FakeSession(alive = false)

        val start = System.nanoTime()
        val outcome = awaitAgentConnect(
            agentLib = agentLib,
            client = client,
            session = session,
            timeoutMillis = 30_000L,
            stopRequested = { false },
            pollMillis = 5L,
        )
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        assertEquals(AgentConnectOutcome.EXITED_EARLY, outcome)
        assertTrue("must wait for stderr + onAgentExited before close", session.exitReportAwaited)
        assertTrue("must not wait for 30s timeout (elapsed=${elapsedMs}ms)", elapsedMs < 1_000L)
        verify(exactly = 0) { agentLib.MaaAgentClientConnect(any()) }
    }

    @Test
    fun `stop request cancels connect wait immediately without awaiting unexpected exit`() {
        val stop = AtomicBoolean(false)
        val polls = AtomicInteger(0)
        val agentLib = mockk<MaaAgentClientLibrary>(relaxed = true) {
            every { MaaAgentClientAlive(client) } answers {
                if (polls.incrementAndGet() >= 2) stop.set(true)
                0
            }
        }
        val session = FakeSession(alive = true)

        val outcome = awaitAgentConnect(
            agentLib = agentLib,
            client = client,
            session = session,
            timeoutMillis = 30_000L,
            stopRequested = stop::get,
            pollMillis = 5L,
        )

        assertEquals(AgentConnectOutcome.CANCELLED, outcome)
        assertEquals(false, session.exitReportAwaited)
        verify(exactly = 0) { agentLib.MaaAgentClientConnect(any()) }
    }

    @Test
    fun `alive child that never connects times out`() {
        val agentLib = mockk<MaaAgentClientLibrary>(relaxed = true) {
            every { MaaAgentClientAlive(client) } returns 0
        }
        val session = FakeSession(alive = true)

        val outcome = awaitAgentConnect(
            agentLib = agentLib,
            client = client,
            session = session,
            timeoutMillis = 30L,
            stopRequested = { false },
            pollMillis = 5L,
        )

        assertEquals(AgentConnectOutcome.TIMED_OUT, outcome)
        verify(exactly = 0) { agentLib.MaaAgentClientConnect(any()) }
    }
}
