package com.aliothmoon.maafw.remote.internal

import com.aliothmoon.maafw.remote.internal.StaleFrameDetector.Observation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StaleFrameDetectorTest {

    @Test
    fun `the first tick only establishes a baseline`() {
        val detector = StaleFrameDetector()
        var observed = 0

        assertFalse(detector.onTick(0) { observed++; Observation.GONE })
        assertEquals(0, observed)
    }

    @Test
    fun `nothing is observed while frames keep arriving`() {
        val detector = StaleFrameDetector()
        var observed = 0

        (1L..5L).forEach { count -> detector.onTick(count) { observed++; Observation.GONE } }

        assertEquals(0, observed)
    }

    @Test
    fun `one confirmation is not enough`() {
        val detector = stalledDetector()

        assertFalse(detector.onTick(42) { Observation.GONE })
        assertTrue(detector.onTick(42) { Observation.GONE })
    }

    @Test
    fun `the verdict holds while the content stays gone`() {
        val detector = stalledDetector()
        detector.onTick(42) { Observation.GONE }
        detector.onTick(42) { Observation.GONE }

        assertTrue(detector.onTick(42) { Observation.GONE })
    }

    @Test
    fun `an owner that is still there discards earlier confirmations`() {
        val detector = stalledDetector()
        detector.onTick(42) { Observation.GONE }

        assertFalse(detector.onTick(42) { Observation.STILL_THERE })
        assertFalse(detector.onTick(42) { Observation.GONE })
    }

    @Test
    fun `a tick without an observation neither counts nor resets`() {
        val detector = stalledDetector()
        detector.onTick(42) { Observation.GONE }

        assertFalse(detector.onTick(42) { null })
        assertTrue(detector.onTick(42) { Observation.GONE })
    }

    @Test
    fun `a new frame discards earlier confirmations`() {
        val detector = stalledDetector()
        detector.onTick(42) { Observation.GONE }

        assertFalse(detector.onTick(43) { Observation.GONE })
        assertFalse(detector.onTick(43) { Observation.GONE })
    }

    @Test
    fun `a count reset by a rebuilt display is not a stall`() {
        val detector = stalledDetector()
        detector.onTick(42) { Observation.GONE }

        assertFalse(detector.onTick(0) { Observation.GONE })
        assertFalse(detector.onTick(0) { Observation.GONE })
    }

    private fun stalledDetector() = StaleFrameDetector().apply {
        onTick(42) { Observation.STILL_THERE }
    }
}
