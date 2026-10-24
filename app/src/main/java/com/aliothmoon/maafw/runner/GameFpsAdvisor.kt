package com.aliothmoon.maafw.runner

import kotlin.math.ceil

/**
 * 低帧率判定：最近 [windowSize] 个有效样本里低于阈值的占比达到 [minFraction] 才告警
 *
 * 用占比而不是均值，转场或加载时的短暂深谷不该触发；每档每次运行只提示一次
 */
class GameFpsAdvisor(
    private val windowSize: Int = DEFAULT_WINDOW_SIZE,
    private val lowThreshold: Float = LOW_FPS,
    private val degradedThreshold: Float = DEGRADED_FPS,
    minFraction: Float = DEFAULT_MIN_FRACTION,
    private val maxIdleStreak: Int = DEFAULT_MAX_IDLE_STREAK,
) {

    enum class Level { LOW, DEGRADED }

    /** [medianFps] 是触发时窗口中位数，不是当次瞬时值 */
    data class Advice(val level: Level, val medianFps: Float)

    private val requiredCount = ceil(windowSize * minFraction).toInt().coerceIn(1, windowSize)
    private val window = ArrayDeque<Float>()
    private var idleStreak = 0
    private var lowAdvised = false
    private var degradedAdvised = false

    fun onSample(fps: Float): Advice? {
        if (fps <= 0f) {
            if (++idleStreak > maxIdleStreak) window.clear()
            return null
        }
        idleStreak = 0
        window.addLast(fps)
        while (window.size > windowSize) window.removeFirst()
        if (window.size < windowSize) return null

        return when {
            window.count { it < lowThreshold } >= requiredCount ->
                advise(Level.LOW, lowAdvised).also { lowAdvised = true }

            window.count { it < degradedThreshold } >= requiredCount ->
                advise(Level.DEGRADED, degradedAdvised).also { degradedAdvised = true }

            else -> null
        }
    }

    private fun advise(level: Level, alreadyAdvised: Boolean): Advice? =
        if (alreadyAdvised) null else Advice(level, median())

    private fun median(): Float {
        val sorted = window.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2f else sorted[mid]
    }

    fun reset() {
        window.clear()
        idleStreak = 0
        lowAdvised = false
        degradedAdvised = false
    }

    companion object {
        const val DEFAULT_WINDOW_SIZE = 15
        const val DEFAULT_MIN_FRACTION = 0.8f
        const val DEFAULT_MAX_IDLE_STREAK = 3
        const val LOW_FPS = 30f
        const val DEGRADED_FPS = 50f
    }
}
