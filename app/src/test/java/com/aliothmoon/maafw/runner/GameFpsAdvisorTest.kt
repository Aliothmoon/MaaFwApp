package com.aliothmoon.maafw.runner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameFpsAdvisorTest {

    private fun samples(lowCount: Int, highFps: Float = 60f): List<Float> =
        List(GameFpsAdvisor.DEFAULT_WINDOW_SIZE) { index ->
            if (index < lowCount) 25f else highFps
        }

    @Test
    fun `fewer than eighty percent low samples do not advise`() {
        val advisor = GameFpsAdvisor()

        samples(lowCount = 11).forEach { assertNull(advisor.onSample(it)) }
    }

    @Test
    fun `eighty percent low samples advise with the window median`() {
        val advisor = GameFpsAdvisor()
        var advice: GameFpsAdvisor.Advice? = null

        samples(lowCount = 12).forEach { sample -> advice = advisor.onSample(sample) ?: advice }
        val result = advice ?: error("Expected low FPS advice")

        assertEquals(GameFpsAdvisor.Level.LOW, result.level)
        assertEquals(25f, result.medianFps, 0.001f)
    }

    @Test
    fun `each level advises only once`() {
        val advisor = GameFpsAdvisor()
        var low: GameFpsAdvisor.Advice? = null
        var degraded: GameFpsAdvisor.Advice? = null

        samples(lowCount = 15).forEach { sample -> low = advisor.onSample(sample) ?: low }
        assertEquals(GameFpsAdvisor.Level.LOW, low!!.level)
        repeat(5) { assertNull(advisor.onSample(25f)) }

        samples(lowCount = 0, highFps = 40f).forEach { sample ->
            degraded = advisor.onSample(sample) ?: degraded
        }
        assertEquals(GameFpsAdvisor.Level.DEGRADED, degraded!!.level)
        assertNull(advisor.onSample(40f))
    }

    @Test
    fun `short idle streaks are ignored and longer streaks clear the window`() {
        val advisor = GameFpsAdvisor()
        samples(lowCount = 12).take(12).forEach { advisor.onSample(it) }
        repeat(3) { assertNull(advisor.onSample(0f)) }

        assertNull(advisor.onSample(25f))
        assertNull(advisor.onSample(25f))

        repeat(4) { advisor.onSample(0f) }
        repeat(14) { assertNull(advisor.onSample(25f)) }
        assertEquals(GameFpsAdvisor.Level.LOW, advisor.onSample(25f)!!.level)
    }

    @Test
    fun `reset forgets previous advice`() {
        val advisor = GameFpsAdvisor()
        samples(lowCount = 15).forEach { advisor.onSample(it) }

        advisor.reset()

        var advice: GameFpsAdvisor.Advice? = null
        samples(lowCount = 15).forEach { sample -> advice = advisor.onSample(sample) ?: advice }
        assertEquals(GameFpsAdvisor.Level.LOW, advice!!.level)
    }
}
