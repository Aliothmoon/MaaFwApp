package com.aliothmoon.maafw.remote.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameCountFpsEstimatorTest {

    @Test
    fun `the first sample only establishes a baseline`() {
        val estimator = FrameCountFpsEstimator()

        assertNull(estimator.sample(frameCount = 10, nowNs = 1_000_000_000))
    }

    @Test
    fun `fps comes from the count delta`() {
        val estimator = FrameCountFpsEstimator()
        estimator.sample(10, 1_000_000_000)

        assertEquals(60f, estimator.sample(70, 2_000_000_000)!!, 0.001f)
    }

    @Test
    fun `time must move forward`() {
        val estimator = FrameCountFpsEstimator()
        estimator.sample(10, 2_000_000_000)

        assertNull(estimator.sample(70, 2_000_000_000))
    }

    @Test
    fun `a decreasing count restarts the baseline`() {
        val estimator = FrameCountFpsEstimator()
        estimator.sample(70, 1_000_000_000)

        assertNull(estimator.sample(10, 2_000_000_000))
        assertEquals(30f, estimator.sample(40, 3_000_000_000)!!, 0.001f)
    }
}
