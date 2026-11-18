package com.aliothmoon.maafw.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 死者自己读不了系统日志，下一个进程连上时替它取，只取一次 */
class PrivilegedDeathForensicsTest {

    private class MemoryStore : PrivilegedDeathForensics.Store {
        var pending: PrivilegedDeathForensics.Pending? = null
        override fun load() = pending
        override fun save(pending: PrivilegedDeathForensics.Pending) {
            this.pending = pending
        }

        override fun clear() {
            pending = null
        }
    }

    private val store = MemoryStore()
    private var now = 1_000_000L
    private val forensics = PrivilegedDeathForensics(store, clock = { now })

    private val death = PrivilegedDeathForensics.Pending(diedAtMs = 1_000_000L, pid = 111)

    @Test
    fun anotherProcess_takesOnce() {
        forensics.remember(death)

        assertEquals(death, forensics.take(connectedPid = 222))
        assertNull(forensics.take(connectedPid = 222))
    }

    @Test
    fun sameProcess_wasNotKilled() {
        forensics.remember(death)

        assertNull(forensics.take(connectedPid = 111))
        assertNull(store.pending)
    }

    @Test
    fun unknownPid_keepsItForLater() {
        forensics.remember(death)

        assertNull(forensics.take(connectedPid = null))
        assertEquals(death, store.pending)
    }

    @Test
    fun tooOld_logsAreGoneAnyway() {
        forensics.remember(death)
        now += 25 * 60 * 60_000L

        assertNull(forensics.take(connectedPid = 222))
        assertNull(store.pending)
    }

    @Test
    fun window() {
        assertEquals(940_000L, death.sinceMs)
        assertEquals(1_015_000L, death.untilMs)
    }
}
