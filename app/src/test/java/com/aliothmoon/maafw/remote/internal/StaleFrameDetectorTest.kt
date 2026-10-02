package com.aliothmoon.maafw.remote.internal

import com.aliothmoon.maafw.remote.internal.StaleFrameDetector.Observation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StaleFrameDetectorTest {

    @Test
    fun `the first sample only establishes a baseline`() {
        val detector = StaleFrameDetector()

        assertFalse(detector.stalled(0))
    }

    @Test
    fun `an unchanged count is a stall`() {
        val detector = StaleFrameDetector()
        detector.stalled(42)

        assertTrue(detector.stalled(42))
    }

    @Test
    fun `one confirmation is not enough`() {
        val detector = stalledDetector()

        assertFalse(detector.confirm(Observation.GONE))
        assertTrue(detector.confirm(Observation.GONE))
    }

    @Test
    fun `the verdict holds while the content stays gone`() {
        val detector = stalledDetector()
        detector.confirm(Observation.GONE)
        detector.confirm(Observation.GONE)

        assertTrue(detector.confirm(Observation.GONE))
    }

    @Test
    fun `an unknown observation discards earlier confirmations`() {
        val detector = stalledDetector()
        detector.confirm(Observation.GONE)

        assertFalse(detector.confirm(Observation.UNKNOWN))
        assertFalse(detector.confirm(Observation.GONE))
    }

    @Test
    fun `a present owner discards earlier confirmations`() {
        val detector = stalledDetector()
        detector.confirm(Observation.GONE)

        assertFalse(detector.confirm(Observation.PRESENT))
        assertFalse(detector.confirm(Observation.GONE))
    }

    @Test
    fun `a new frame discards earlier confirmations`() {
        val detector = stalledDetector()
        detector.confirm(Observation.GONE)

        assertFalse(detector.stalled(43))
        assertTrue(detector.stalled(43))
        assertFalse(detector.confirm(Observation.GONE))
    }

    @Test
    fun `a count reset by a rebuilt display is not a stall`() {
        val detector = stalledDetector()
        detector.confirm(Observation.GONE)

        assertFalse(detector.stalled(0))
        assertTrue(detector.stalled(0))
        assertFalse(detector.confirm(Observation.GONE))
    }

    private fun stalledDetector() = StaleFrameDetector().apply {
        stalled(42)
        stalled(42)
    }
}
