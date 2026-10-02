package com.aliothmoon.maafw.remote.internal

/**
 * 帧停了以后，要连续几拍确认画面的主人不在了才算残影；纯逻辑，便于单测
 */
class StaleFrameDetector(private val confirmTicks: Int = 2) {

    enum class Observation { GONE, PRESENT, UNKNOWN }

    private var lastCount = -1L
    private var goneStreak = 0

    /**
     * 帧计数与上一拍相同返回 true。比的是「不等」而不是「变大」：
     * 虚拟屏重建会把计数清零，那也是有新画面要来
     */
    fun stalled(frameCount: Long): Boolean {
        if (frameCount == lastCount) return true
        lastCount = frameCount
        goneStreak = 0
        return false
    }

    /** 只在 [stalled] 为 true 的那一拍调；判不出不算半次确认，和 PRESENT 一样清零 */
    fun confirm(observation: Observation): Boolean {
        goneStreak = if (observation == Observation.GONE) minOf(goneStreak + 1, confirmTicks) else 0
        return goneStreak >= confirmTicks
    }
}
