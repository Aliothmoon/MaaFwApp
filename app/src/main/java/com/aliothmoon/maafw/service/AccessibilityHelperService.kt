package com.aliothmoon.maafw.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.aliothmoon.maafw.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * 两件事：
 * - 前台模式下同时按音量 ± 唤起控制面板
 * - 后台真实熄屏时，单按音量减恢复物理屏幕
 * - InputText 里按键打不出来的文本经 [AccessibilityTextWriter] 写进输入框
 *
 * 按键监听只在对应功能启用时打开；没有监听者时音量键原样交给系统。
 */
class AccessibilityHelperService : AccessibilityService() {

    private var volumeUpPressTime = 0L
    private var volumeDownPressTime = 0L
    private var triggered = false
    private var volumeDownTriggered = false
    private var pendingWakeRunnable: Runnable? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        applyKeyFiltering()
        _isConnected.value = true
        Timber.d("Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val comboListener = onVolumeUpDownPressed.get()
        val wakeListener = onVolumeDownPressed.get()

        // 两种监听可以同时存在：音量减先短暂等待组合键窗口，
        // 若没有形成音量 ± 组合，再执行单键唤醒。
        if (comboListener != null) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> when (event.action) {
                    KeyEvent.ACTION_DOWN -> {
                        if (recordAndCheck { volumeUpPressTime = it }) {
                            cancelPendingWake()
                            return true
                        }
                    }
                    KeyEvent.ACTION_UP -> reset { volumeUpPressTime = 0L }
                }

                KeyEvent.KEYCODE_VOLUME_DOWN -> when (event.action) {
                    KeyEvent.ACTION_DOWN -> {
                        if (recordAndCheck { volumeDownPressTime = it }) {
                            cancelPendingWake()
                            return true
                        }
                        if (wakeListener != null && pendingWakeRunnable == null) {
                            scheduleWake(wakeListener)
                            return true
                        }
                    }
                    KeyEvent.ACTION_UP -> {
                        reset { volumeDownPressTime = 0L }
                        if (wakeListener != null) return true
                    }
                }
            }
            return super.onKeyEvent(event)
        }

        if (wakeListener == null || event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return super.onKeyEvent(event)
        }
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (!volumeDownTriggered) {
                    volumeDownTriggered = true
                    Timber.d("Volume-down wake key triggered")
                    wakeListener.invoke()
                }
                true
            }
            KeyEvent.ACTION_UP -> {
                volumeDownTriggered = false
                true
            }
            else -> true
        }
    }

    private fun scheduleWake(listener: () -> Unit) {
        val task = Runnable {
            pendingWakeRunnable = null
            if (!triggered && onVolumeDownPressed.get() != null) {
                Timber.d("Volume-down wake key triggered after combo window")
                listener.invoke()
            }
        }
        pendingWakeRunnable = task
        mainHandler.postDelayed(task, SIMULTANEOUS_PRESS_THRESHOLD_MS + 30L)
    }

    private fun cancelPendingWake() {
        pendingWakeRunnable?.let(mainHandler::removeCallbacks)
        pendingWakeRunnable = null
    }

    /** 只有有人监听时才请求系统把实体按键交给本服务。 */
    private fun applyKeyFiltering() {
        val wanted = onVolumeUpDownPressed.get() != null || onVolumeDownPressed.get() != null
        if (onVolumeUpDownPressed.get() == null) resetCombo()
        val info = serviceInfo ?: return
        val filtering = info.flags and AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS != 0
        if (filtering == wanted) return
        info.flags = if (wanted) {
            info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        } else {
            info.flags and AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS.inv()
        }
        serviceInfo = info
        Timber.d("Key event filtering %s", if (wanted) "on" else "off")
    }

    private fun resetCombo() {
        volumeUpPressTime = 0L
        volumeDownPressTime = 0L
        triggered = false
        volumeDownTriggered = false
        cancelPendingWake()
    }

    private inline fun recordAndCheck(record: (Long) -> Unit): Boolean {
        record(System.currentTimeMillis())
        return checkSimultaneousPress()
    }

    private inline fun reset(clear: () -> Unit) {
        clear()
        triggered = false
    }

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
        instance = null
        _isConnected.value = false
        Timber.d("Accessibility service disconnected")
    }

    companion object {
        private const val SIMULTANEOUS_PRESS_THRESHOLD_MS = 300L

        val SERVICE_ID: String =
            BuildConfig.APPLICATION_ID + "/" + AccessibilityHelperService::class.java.name

        @Volatile
        var instance: AccessibilityHelperService? = null
            private set

        private val onVolumeUpDownPressed = AtomicReference<(() -> Unit)?>(null)
        private val onVolumeDownPressed = AtomicReference<(() -> Unit)?>(null)
        private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

        /** 前台控制层的原有音量组合快捷键。 */
        fun setVolumeComboListener(listener: (() -> Unit)?) {
            onVolumeUpDownPressed.set(listener)
            mainHandler.post { instance?.applyKeyFiltering() }
        }

        /** 后台真实熄屏时使用；与前台组合键监听相互独立。 */
        fun setVolumeDownListener(listener: (() -> Unit)?) {
            onVolumeDownPressed.set(listener)
            mainHandler.post { instance?.applyKeyFiltering() }
        }

        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    }
}
