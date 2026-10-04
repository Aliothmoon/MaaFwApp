package com.aliothmoon.maafw.remote.internal

import android.view.Display
import com.aliothmoon.maafw.domain.UnlockGesture
import com.aliothmoon.maafw.domain.UnlockStep
import com.aliothmoon.maafw.bridge.InputControlUtils
import kotlin.math.roundToInt

internal sealed interface InjectAction {
    data class Sleep(val ms: Long) : InjectAction
    data class Down(val x: Int, val y: Int) : InjectAction
    data class Move(val x: Int, val y: Int) : InjectAction
    data class Up(val x: Int, val y: Int) : InjectAction
}

/** 步骤序列 → 注入时间轴；分辨率有出入时按比例缩放 */
internal object UnlockGestureReplay {

    const val TAP_HOLD_MS = 60

    /** 录制只取单指，回放也只用一个触点，直接注入主屏 */
    private const val SINGLE_CONTACT = 0

    fun timeline(gesture: UnlockGesture, targetWidth: Int, targetHeight: Int): List<InjectAction> {
        if (gesture.screenWidth <= 0 || gesture.screenHeight <= 0) return emptyList()
        val scaleX = targetWidth.toDouble() / gesture.screenWidth
        val scaleY = targetHeight.toDouble() / gesture.screenHeight

        fun mapX(x: Int) = (x * scaleX).roundToInt().coerceIn(0, targetWidth - 1)
        fun mapY(y: Int) = (y * scaleY).roundToInt().coerceIn(0, targetHeight - 1)

        val out = mutableListOf<InjectAction>()
        for (step in gesture.steps) {
            if (step.delayBeforeMs > 0) out += InjectAction.Sleep(step.delayBeforeMs.toLong())
            when (step) {
                is UnlockStep.Tap -> out += press(mapX(step.x), mapY(step.y), TAP_HOLD_MS.toLong())

                is UnlockStep.LongPress ->
                    out += press(mapX(step.x), mapY(step.y), step.holdMs.toLong())

                is UnlockStep.Swipe -> {
                    // 轨迹来自持久化文件，少于两点按点击兜底
                    val points = step.points
                    if (points.size < 2) {
                        points.firstOrNull()?.let {
                            out += press(mapX(it.x), mapY(it.y), TAP_HOLD_MS.toLong())
                        }
                    } else {
                        out += InjectAction.Down(mapX(points[0].x), mapY(points[0].y))
                        for (i in 1 until points.size) {
                            val dt = (points[i].tMs - points[i - 1].tMs).toLong()
                            if (dt > 0) out += InjectAction.Sleep(dt)
                            val x = mapX(points[i].x)
                            val y = mapY(points[i].y)
                            out += if (i == points.lastIndex) {
                                InjectAction.Up(x, y)
                            } else {
                                InjectAction.Move(x, y)
                            }
                        }
                    }
                }
            }
        }
        return out
    }

    /**
     * 按时间轴注入到主屏；解锁发生在任务启动前，不会和 MAA 的触控抢 [InputControlUtils]
     * @param proceed 每一笔按下前问一次，false 即停；已按下的那笔照常走到抬起，触摸焦点在按下时就定在锁屏窗口
     * @return false 表示被 [proceed] 叫停
     */
    fun execute(actions: List<InjectAction>, proceed: () -> Boolean = { true }): Boolean =
        withSensitiveInput { play(actions, proceed, ::inject) }

    internal fun play(
        actions: List<InjectAction>,
        proceed: () -> Boolean,
        sink: (InjectAction) -> Unit,
    ): Boolean {
        for (action in actions) {
            if (action is InjectAction.Down && !proceed()) return false
            sink(action)
        }
        return true
    }

    private fun inject(action: InjectAction) {
        when (action) {
            is InjectAction.Sleep -> Thread.sleep(action.ms)
            is InjectAction.Down ->
                InputControlUtils.down(action.x, action.y, SINGLE_CONTACT, Display.DEFAULT_DISPLAY)

            is InjectAction.Move ->
                InputControlUtils.move(action.x, action.y, SINGLE_CONTACT, Display.DEFAULT_DISPLAY)

            is InjectAction.Up ->
                InputControlUtils.up(action.x, action.y, SINGLE_CONTACT, Display.DEFAULT_DISPLAY)
        }
    }

    private fun press(x: Int, y: Int, holdMs: Long): List<InjectAction> = listOf(
        InjectAction.Down(x, y),
        InjectAction.Sleep(holdMs.coerceAtLeast(0)),
        InjectAction.Up(x, y),
    )
}
