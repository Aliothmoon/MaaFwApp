package com.aliothmoon.maafw.service

import android.accessibilityservice.AccessibilityService
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * 只为一件事存在：前台模式下同时按音量 ± 唤起控制面板
 *
 * 前台模式把屏幕让给了目标应用，没有这条快捷键就只剩悬浮球一条路，而悬浮球会挡住画面。
 * 不读窗口内容（`canRetrieveWindowContent=false`），只过滤按键
 *
 * 服务本身不认识执行状态，收到组合键就调 [onVolumeUpDownPressed]，由
 * [com.aliothmoon.maafw.overlay.OverlayController] 决定做什么
 */
class AccessibilityHelperService : AccessibilityService() {

    private var volumeUpPressTime = 0L
    private var volumeDownPressTime = 0L

    /** 一次组合按下只触发一次；两个键都抬起才复位 */
    private var triggered = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        _isConnected.value = true
        Timber.d("Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // 没人监听就原样放行，别把音量键吞了
        if (onVolumeUpDownPressed.get() == null) return super.onKeyEvent(event)

        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> when (event.action) {
                KeyEvent.ACTION_DOWN -> if (recordAndCheck { volumeUpPressTime = it }) return true
                KeyEvent.ACTION_UP -> reset { volumeUpPressTime = 0L }
            }

            KeyEvent.KEYCODE_VOLUME_DOWN -> when (event.action) {
                KeyEvent.ACTION_DOWN -> if (recordAndCheck { volumeDownPressTime = it }) return true
                KeyEvent.ACTION_UP -> reset { volumeDownPressTime = 0L }
            }
        }
        return super.onKeyEvent(event)
    }

    private inline fun recordAndCheck(record: (Long) -> Unit): Boolean {
        record(System.currentTimeMillis())
        return checkSimultaneousPress()
    }

    private inline fun reset(clear: () -> Unit) {
        clear()
        triggered = false
    }

    /**
     * 两键按下时间差在容差内即算组合键
     *
     * 触发后一直返回 true 直到抬起：不然长按期间的重复事件会漏给系统，音量条会弹出来
     */
    private fun checkSimultaneousPress(): Boolean {
        if (triggered) return true
        if (volumeUpPressTime <= 0L || volumeDownPressTime <= 0L) return false
        if (abs(volumeUpPressTime - volumeDownPressTime) >= SIMULTANEOUS_PRESS_THRESHOLD_MS) return false

        Timber.d("Volume up/down combo triggered")
        triggered = true
        onVolumeUpDownPressed.get()?.invoke()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        _isConnected.value = false
        Timber.d("Accessibility service disconnected")
    }

    companion object {
        private const val SIMULTANEOUS_PRESS_THRESHOLD_MS = 300L

        /** 写进 `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` 的组件名，代授时要用 */
        const val SERVICE_ID = "com.aliothmoon.maafw/.service.AccessibilityHelperService"

        /** 由 OverlayController 装卸；null 表示当前不需要拦截 */
        val onVolumeUpDownPressed = AtomicReference<(() -> Unit)?>()

        private val _isConnected = MutableStateFlow(false)

        /** 代授之后要等它变 true 才算真连上，系统绑定是异步的 */
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    }
}
