package com.aliothmoon.maafw.telemetry

import com.aliothmoon.maafw.util.JsonUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 死者自己读不了系统日志，下一个进程连上时替它取，只取一次 */
class ServiceDeathForensicsTest {

    private class MemoryStore : ServiceDeathForensics.Store {
        var pending: ServiceDeathForensics.Pending? = null

        override fun load() = pending

        override fun save(pending: ServiceDeathForensics.Pending) {
            this.pending = pending
        }

        override fun clear() {
            pending = null
        }
    }

    private val store = MemoryStore()
    private var now = 1_000_000L
    private val forensics = ServiceDeathForensics(store, clock = { now })

    private val death = ServiceDeathForensics.Pending(
        eventId = "e1",
        runId = "r1",
        backend = "shizuku",
        diedAtMs = 1_000_000L,
        pid = 111,
    )

    @Test
    fun `another process peeks the pending record without consuming it`() {
        forensics.remember(death)

        assertEquals(death, forensics.peek(connectedPid = 222))
        assertEquals(death, forensics.peek(connectedPid = 222))

        forensics.complete(death)
        assertNull(forensics.peek(connectedPid = 222))
    }

    @Test
    fun `same process was replaced rather than killed`() {
        forensics.remember(death)

        assertNull(forensics.peek(connectedPid = 111))
        assertNull(store.pending)
    }

    @Test
    fun `unknown pid keeps it for the next connection`() {
        forensics.remember(death)

        assertNull(forensics.peek(connectedPid = null))
        assertEquals(death, store.pending)
    }

    @Test
    fun `too old records are discarded`() {
        forensics.remember(death)
        now += 25 * 60 * 60_000L

        assertNull(forensics.peek(connectedPid = 222))
        assertNull(store.pending)
    }

    @Test
    fun `completing an old record does not clear a newer death`() {
        val newer = death.copy(eventId = "e2", diedAtMs = death.diedAtMs + 1_000L, pid = 222)
        forensics.remember(death)
        assertEquals(death, forensics.peek(connectedPid = 333))

        forensics.remember(newer)
        forensics.complete(death)

        assertEquals(newer, store.pending)
    }

    @Test
    fun `an older death cannot replace a newer pending record`() {
        val newer = death.copy(eventId = "e2", diedAtMs = death.diedAtMs + 1_000L, pid = 222)
        forensics.remember(newer)

        forensics.remember(death)

        assertEquals(newer, store.pending)
    }

    @Test
    fun `window covers one minute before and fifteen seconds after death`() {
        assertEquals(940_000L, death.sinceMs)
        assertEquals(1_015_000L, death.untilMs)
    }

    @Test
    fun `pending survives json`() {
        val json = JsonUtils.common.encodeToString(ServiceDeathForensics.Pending.serializer(), death)

        assertEquals(
            death,
            JsonUtils.common.decodeFromString(ServiceDeathForensics.Pending.serializer(), json),
        )
    }
}
