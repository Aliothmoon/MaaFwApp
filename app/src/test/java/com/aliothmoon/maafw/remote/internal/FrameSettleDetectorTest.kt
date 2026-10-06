package com.aliothmoon.maafw.remote.internal

import com.aliothmoon.maafw.remote.internal.FrameSettleDetector.State
import org.junit.Assert.assertEquals
import org.junit.Test

class FrameSettleDetectorTest {

    private fun detector(startCount: Long = 10) =
        FrameSettleDetector(quietMs = 500, timeoutMs = 1500, startCount = startCount, startMs = 0)

    @Test
    fun `it keeps waiting until the count has been quiet long enough`() {
        val detector = detector()

        assertEquals(State.WAITING, detector.sample(10, 450))
        assertEquals(State.SETTLED, detector.sample(10, 500))
        assertEquals(10, detector.frameCount)
    }

    @Test
    fun `a late frame restarts the quiet period`() {
        val detector = detector()
        detector.sample(10, 450)

        assertEquals(State.WAITING, detector.sample(11, 480))
        assertEquals(State.WAITING, detector.sample(11, 950))
        assertEquals(State.SETTLED, detector.sample(11, 980))
        assertEquals(11, detector.frameCount)
    }

    @Test
    fun `frames that never stop end in a timeout`() {
        val detector = detector()
        var count = 10L
        var state = State.WAITING
        var now = 0L
        while (state == State.WAITING) {
            now += 50
            state = detector.sample(++count, now)
        }

        assertEquals(State.TIMED_OUT, state)
        assertEquals(1500, now)
    }

    @Test
    fun `settling on the last sample before the deadline still counts`() {
        val detector = detector()
        detector.sample(11, 1000)

        assertEquals(State.SETTLED, detector.sample(11, 1500))
    }
}
