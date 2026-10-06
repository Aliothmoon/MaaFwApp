package com.aliothmoon.maafw.remote.internal

/**
 * 帧计数连续 [quietMs] 没动算停稳，超过 [timeoutMs] 还在动算等不到；纯逻辑，便于单测
 */
class FrameSettleDetector(
    private val quietMs: Long,
    private val timeoutMs: Long,
    startCount: Long,
    private val startMs: Long,
) {

    enum class State { WAITING, SETTLED, TIMED_OUT }

    /** 最近一次看到的帧计数；[State.SETTLED] 时就是停稳的那个值 */
    var frameCount = startCount
        private set

    private var quietSinceMs = startMs

    fun sample(count: Long, nowMs: Long): State {
        if (count != frameCount) {
            frameCount = count
            quietSinceMs = nowMs
        } else if (nowMs - quietSinceMs >= quietMs) {
            return State.SETTLED
        }
        return if (nowMs - startMs >= timeoutMs) State.TIMED_OUT else State.WAITING
    }
}
