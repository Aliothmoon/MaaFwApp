package com.aliothmoon.maafw.remote.internal

/**
 * 帧停了以后，要连续几次确认画面的主人不在了才算残影；纯逻辑，便于单测
 */
class StaleFrameDetector {

    /** 判不出也算 [STILL_THERE] */
    enum class Observation { GONE, STILL_THERE }

    private var lastCount = -1L
    private var goneStreak = 0

    /**
     * 每拍调一次，返回 true 表示该换黑帧了。帧计数变了就不调 [observe]——比的是「不等」而不是「变大」，
     * 虚拟屏重建会把计数清零，那也是有新画面要来。[observe] 返回 null 表示这一拍没查，不算数也不清零
     */
    fun onTick(frameCount: Long, observe: () -> Observation?): Boolean {
        if (frameCount != lastCount) {
            lastCount = frameCount
            goneStreak = 0
            return false
        }
        val observation = observe() ?: return false
        goneStreak = if (observation == Observation.GONE) minOf(goneStreak + 1, CONFIRMATIONS) else 0
        return goneStreak >= CONFIRMATIONS
    }

    private companion object {
        const val CONFIRMATIONS = 2
    }
}
