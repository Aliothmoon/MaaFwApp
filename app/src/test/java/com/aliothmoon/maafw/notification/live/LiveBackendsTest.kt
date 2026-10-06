package com.aliothmoon.maafw.notification.live

import com.aliothmoon.maafw.notification.live.LiveBackend.HYPER_ISLAND
import com.aliothmoon.maafw.notification.live.LiveBackend.LIVE_UPDATE
import com.aliothmoon.maafw.notification.live.LiveBackend.PLAIN
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveBackendsTest {

    private fun resolve(preferred: LiveBackend?, island: Boolean, liveUpdate: Boolean) =
        LiveBackends.resolve(preferred, islandAvailable = island, liveUpdateGranted = liveUpdate)

    @Test
    fun `unset preference starts from the island and walks down`() {
        assertEquals(HYPER_ISLAND, resolve(null, island = true, liveUpdate = true))
        assertEquals(LIVE_UPDATE, resolve(null, island = false, liveUpdate = true))
        assertEquals(PLAIN, resolve(null, island = false, liveUpdate = false))
    }

    @Test
    fun `an unavailable choice falls back to the next one down`() {
        assertEquals(LIVE_UPDATE, resolve(HYPER_ISLAND, island = false, liveUpdate = true))
        assertEquals(PLAIN, resolve(HYPER_ISLAND, island = false, liveUpdate = false))
        assertEquals(PLAIN, resolve(LIVE_UPDATE, island = true, liveUpdate = false))
    }

    @Test
    fun `a lower choice never climbs back up`() {
        assertEquals(LIVE_UPDATE, resolve(LIVE_UPDATE, island = true, liveUpdate = true))
        assertEquals(PLAIN, resolve(PLAIN, island = true, liveUpdate = true))
    }

    @Test
    fun `offered styles only list what the device has`() {
        fun capability(islandLikely: Boolean, liveUpdateAvailable: Boolean) = LiveCapability(
            backend = PLAIN,
            postNotifications = true,
            liveUpdateAvailable = liveUpdateAvailable,
            liveUpdateGranted = false,
            islandLikely = islandLikely,
            islandGranted = false,
        )
        assertEquals(listOf(PLAIN), capability(islandLikely = false, liveUpdateAvailable = false).offered)
        assertEquals(
            listOf(HYPER_ISLAND, LIVE_UPDATE, PLAIN),
            capability(islandLikely = true, liveUpdateAvailable = true).offered,
        )
    }

    @Test
    fun `island sequence is strictly increasing even when the clock goes back`() {
        assertEquals(1_000L, FocusSequence.next(last = 0L, nowMs = 1_000L))
        assertEquals(1_001L, FocusSequence.next(last = 1_000L, nowMs = 1_000L))
        assertEquals(5_001L, FocusSequence.next(last = 5_000L, nowMs = 2_000L))
    }
}
