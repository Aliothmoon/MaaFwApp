package com.aliothmoon.maafw.overlay.screensaver

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.aliothmoon.maafw.domain.RunMode
import com.aliothmoon.maafw.overlay.OverlayViewModelOwner
import com.aliothmoon.maafw.runner.RunnerPhase
import com.aliothmoon.maafw.runner.RunnerPort
import com.aliothmoon.maafw.runner.isBusy
import com.aliothmoon.maafw.runner.toLogText
import com.aliothmoon.maafw.settings.AppSettingsGateway
import com.aliothmoon.maafw.theme.MaaFwTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 后台模式的运行期屏保
 *
 */
class ScreenSaverOverlayManager(
    private val context: Context,
    private val runnerPort: RunnerPort,
    private val appSettings: AppSettingsGateway,
) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /**
     * 不复用 Koin 里那个 single：那份归控制层，生命周期跟着面板显隐推
     * 两处共用会互相把对方推回 CREATED，`collectAsStateWithLifecycle` 就此停摆
     */
    private val viewModelOwner = OverlayViewModelOwner()

    private var composeView: ComposeView? = null
    private var phaseJob: Job? = null
    private var logJob: Job? = null

    private val _isShowing = MutableStateFlow(false)
    val isShowing: StateFlow<Boolean> = _isShowing.asStateFlow()

    /** 屏保上滚动那一行；只保最新一条，历史留在运行日志里 */
    private val latestLog = MutableStateFlow<String?>(null)

    fun setup() {
        scope.launch {
            appSettings.runMode.collect { mode ->
                when (mode) {
                    RunMode.BACKGROUND -> observePhase()
                    RunMode.FOREGROUND -> {
                        stopObservingPhase()
                        hide()
                    }
                }
            }
        }
    }

    // ── 执行态 ──

    private fun observePhase() {
        if (phaseJob != null) return
        phaseJob = scope.launch {
            var previous: RunnerPhase = runnerPort.state.value.phase
            runnerPort.state.collect { state ->
                val current = state.phase
                when {
                    // 开关只管「自动盖」；手动盖上的那次不受它影响
                    !previous.isBusy && current.isBusy ->
                        if (appSettings.screenSaverEnabled.value) show()

                    // 结束就撤，别让用户回来面对一块黑屏还得先滑一下
                    previous.isBusy && !current.isBusy -> hide()
                }
                previous = current
            }
        }
    }

    private fun stopObservingPhase() {
        phaseJob?.cancel()
        phaseJob = null
    }

    // ── 显隐 ──

    /** 返回是否真的盖上了；没有悬浮窗权限时 addView 会抛，这里吞掉并如实回 false */
    suspend fun show(): Boolean = withContext(Dispatchers.Main.immediate) {
        if (_isShowing.value) return@withContext true
        if (appSettings.runMode.value != RunMode.BACKGROUND) {
            Timber.w("Not in background mode; ignoring screen-saver show request")
            return@withContext false
        }

        val view = createView()
        runCatching { windowManager.addView(view, createLayoutParams()) }
            .onSuccess {
                composeView = view
                viewModelOwner.start()
                startLogRelay()
                _isShowing.value = true
                Timber.d("Screen saver shown")
            }
            .onFailure { Timber.e(it, "Failed to show screen saver") }
        _isShowing.value
    }

    suspend fun hide() = withContext(Dispatchers.Main.immediate) {
        val view = composeView ?: return@withContext
        composeView = null
        _isShowing.value = false
        logJob?.cancel()
        logJob = null
        // 清掉，否则下一轮盖上的瞬间显示的是上一轮的尾句
        latestLog.value = null
        viewModelOwner.stop()
        runCatching { windowManager.removeView(view) }
            .onSuccess { Timber.d("Screen saver dismissed") }
            .onFailure { Timber.e(it, "Failed to remove screen saver") }
    }

    private fun startLogRelay() {
        logJob?.cancel()
        logJob = scope.launch {
            runnerPort.events.collect { latestLog.value = it.toLogText() }
        }
    }

    private fun createView(): ComposeView = ComposeView(context).apply {
        setViewTreeLifecycleOwner(viewModelOwner)
        setViewTreeViewModelStoreOwner(viewModelOwner)
        setViewTreeSavedStateRegistryOwner(viewModelOwner)
        setContent {
            // 屏保恒为暗色：整块屏幕本来就该压到最黑；风格仍跟设置里的 ThemeStyle
            val themeStyle by appSettings.themeStyle.collectAsState()
            MaaFwTheme(themeStyle = themeStyle, darkTheme = true) {
                ScreenSaverView(
                    latestLog = latestLog,
                    onUnlock = { scope.launch { hide() } },
                )
            }
        }
        // 排掉底部手势区，不然系统的返回手势会把解锁条的横向拖拽抢走
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                val excluded = (v.resources.displayMetrics.density * GESTURE_EXCLUSION_DP).toInt()
                v.systemGestureExclusionRects =
                    listOf(Rect(0, v.height - excluded, v.width, v.height))
            }
        }
    }

    /**
     * 不可聚焦：返回键与音量键要原样落给系统，屏保不该改变它们的行为
     * KEEP_SCREEN_ON + screenBrightness 压到最低是这层的核心——真息屏会让
     */
    private fun createLayoutParams(): WindowManager.LayoutParams {
        @Suppress("DEPRECATION")
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
            screenBrightness = MIN_BRIGHTNESS
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private companion object {
        /** 0f 在部分 ROM 上被当成「跟随系统」，给一个够小但非零的值 */
        const val MIN_BRIGHTNESS = 0.01f

        /** 覆盖三大厂的手势条高度还有余量；解锁条本身离底 56dp，不会被这块挡住 */
        const val GESTURE_EXCLUSION_DP = 120
    }
}
