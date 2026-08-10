package com.aliothmoon.maafw.overlay

import android.app.Application
import android.content.ComponentCallbacks
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.view.ViewGroup
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.aliothmoon.maafw.MainActivity
import com.aliothmoon.maafw.domain.OverlayControlMode
import com.aliothmoon.maafw.domain.RunMode
import com.aliothmoon.maafw.overlay.border.BorderOverlayManager
import com.aliothmoon.maafw.runner.RunnerPhase
import com.aliothmoon.maafw.runner.RunnerPort
import com.aliothmoon.maafw.runner.isBusy
import com.aliothmoon.maafw.service.AccessibilityHelperService
import com.aliothmoon.maafw.settings.AppSettingsGateway
import com.aliothmoon.maafw.theme.MaaFwTheme
import com.petterp.floatingx.FloatingX
import com.petterp.floatingx.assist.FxDisplayMode
import com.petterp.floatingx.assist.FxGravity
import com.petterp.floatingx.assist.FxScopeType
import com.petterp.floatingx.compose.enableComposeSupport
import com.petterp.floatingx.listener.IKeyBackListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 前台模式的控制层
 *
 * 前台模式把屏幕让给了目标应用，app 界面被完全盖住——没有这一层就没有任何办法停任务。
 * 后台虚拟屏模式下整层不存在（`setup` 里跟着 `runMode` 装卸）
 *
 * 与 [com.aliothmoon.maafw.session.SessionViewModel] 平级而不是它的下游：悬浮窗跨 Activity
 * 存活，拿不到 Activity 作用域的 VM，所以直接吃 [RunnerPort]
 */
class OverlayController(
    private val context: Application,
    private val runnerPort: RunnerPort,
    private val appSettings: AppSettingsGateway,
    val borderOverlayManager: BorderOverlayManager,
    private val viewModelOwner: OverlayViewModelOwner,
) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _isActive = MutableStateFlow(false)

    /** 用户是否已开启控制层；未开启时即便进了运行态也不出球、不出边框 */
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private val isPanelLocked = MutableStateFlow(true)

    private var currentMode: OverlayControlMode = OverlayControlMode.FLOAT_BALL
    private var phaseJob: Job? = null
    private var panelLayout: Pair<Int, Int>? = null

    private val configCallback = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            val next = calculatePanelLayout(newConfig)
            if (next == panelLayout) return
            panelLayout = next
            applyPanelLayout(newConfig, next)
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onLowMemory() = Unit
    }

    fun setup() {
        context.registerComponentCallbacks(configCallback)
        scope.launch {
            appSettings.runMode.collect { mode ->
                when (mode) {
                    RunMode.FOREGROUND -> {
                        install()
                        configCallback.onConfigurationChanged(context.resources.configuration)
                        observePhase()
                    }

                    RunMode.BACKGROUND -> {
                        stopObservingPhase()
                        hideAll()
                        uninstall()
                    }
                }
            }
        }
        scope.launch {
            appSettings.overlayControlMode.collect { applyMode(it) }
        }
    }

    // ── 执行态 ──

    private fun observePhase() {
        if (phaseJob != null) return
        phaseJob = scope.launch {
            var previous: RunnerPhase = runnerPort.state.value.phase
            runnerPort.state.collect { state ->
                onPhaseChanged(previous, state.phase)
                previous = state.phase
            }
        }
    }

    private fun stopObservingPhase() {
        phaseJob?.cancel()
        phaseJob = null
    }

    /**
     * 只在用户开过控制层之后才响应
     * 定时触发那条链是静默启动的，用户没开控制层就不该被突然弹出的悬浮球打断
     */
    private suspend fun onPhaseChanged(previous: RunnerPhase, current: RunnerPhase) {
        if (!_isActive.value) return
        when {
            !previous.isBusy && current.isBusy -> {
                hidePanel()
                when (currentMode) {
                    OverlayControlMode.FLOAT_BALL -> showBall()
                    OverlayControlMode.ACCESSIBILITY -> borderOverlayManager.show()
                }
            }

            previous.isBusy && !current.isBusy -> {
                when (currentMode) {
                    OverlayControlMode.FLOAT_BALL -> hideBall()
                    OverlayControlMode.ACCESSIBILITY -> borderOverlayManager.hide()
                }
                showPanel()
            }
        }
    }

    // ── 装卸 ──

    private fun install() {
        if (!FloatingX.isInstalled(PANEL_TAG)) {
            FloatingX.install {
                enableComposeSupport()
                setContext(context)
                setTag(PANEL_TAG)
                setScopeType(FxScopeType.SYSTEM)
                setLayoutView(createPanelView())
                setEnableEdgeAdsorption(false)
                setGravity(FxGravity.CENTER)
                setEnableSafeArea(false)
                setEnableAnimation(true)
                setDisplayMode(FxDisplayMode.ClickOnly)
                setEnableKeyBoardAdapt(true)
                setKeyBackListener(object : IKeyBackListener {
                    // 消费返回键：不然会落到下面的目标应用，把它退出去
                    override fun onBackPressed(): Boolean = true
                })
            }
        }
        if (!FloatingX.isInstalled(BALL_TAG)) {
            FloatingX.install {
                enableComposeSupport()
                setContext(context)
                setTag(BALL_TAG)
                setScopeType(FxScopeType.SYSTEM)
                setLayoutView(createBallView())
                setEnableEdgeAdsorption(true)
                setGravity(FxGravity.RIGHT_OR_CENTER)
                setEnableAnimation(true)
            }
        }
        Timber.d("Control overlay attached")
    }

    private fun uninstall() {
        FloatingX.uninstallAll()
        Timber.d("Control overlay detached")
    }

    private fun createPanelView(): ComposeView = newComposeView().apply {
        panelLayout?.let { layoutParams = ViewGroup.LayoutParams(it.first, it.second) }
        setContent {
            val themeStyle by appSettings.themeStyle.collectAsState()
            MaaFwTheme(themeStyle = themeStyle) {
                // 不用 collectAsStateWithLifecycle：悬浮窗隐藏时 owner 停在 CREATED，
                // 那样收不到运行态变化，再显示出来就是过期数据
                val state by runnerPort.state.collectAsState()
                val locked by isPanelLocked.collectAsState()
                OverlayPanel(
                    state = state,
                    isLocked = locked,
                    onStop = { scope.launch { runnerPort.stop() } },
                    onBackToApp = ::bringAppToFront,
                    onLockToggle = { setPanelLocked(it) },
                    onClose = ::onPanelClosed,
                )
            }
        }
    }

    private fun createBallView(): ComposeView = newComposeView().apply {
        setContent {
            val themeStyle by appSettings.themeStyle.collectAsState()
            MaaFwTheme(themeStyle = themeStyle) {
                val state by runnerPort.state.collectAsState()
                FloatBall(phase = state.phase, onClick = ::onBallClick)
            }
        }
    }

    private fun newComposeView(): ComposeView = ComposeView(context).apply {
        setBackgroundColor(Color.TRANSPARENT)
        setViewTreeLifecycleOwner(viewModelOwner)
        setViewTreeViewModelStoreOwner(viewModelOwner)
        setViewTreeSavedStateRegistryOwner(viewModelOwner)
    }

    // ── 交互 ──

    private fun onBallClick() {
        hideBall()
        showPanel()
    }

    private fun onPanelClosed() {
        hidePanel()
        if (currentMode == OverlayControlMode.FLOAT_BALL) showBall()
    }

    private fun bringAppToFront() {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
        runCatching { context.startActivity(intent) }
            .onFailure { Timber.w(it, "Failed to return to app") }
    }

    private fun setPanelLocked(locked: Boolean) {
        isPanelLocked.value = locked
        FloatingX.controlOrNull(PANEL_TAG)?.updateConfig {
            setDisplayMode(if (locked) FxDisplayMode.ClickOnly else FxDisplayMode.Normal)
        }
    }

    // ── 显隐 ──

    /** 由 UI 显式开启；不自动开是因为悬浮窗要盖住别人的画面，得用户点头 */
    fun show() {
        if (appSettings.runMode.value != RunMode.FOREGROUND) {
            Timber.w("Not in foreground mode; ignoring control overlay show request")
            return
        }
        _isActive.value = true
        when (currentMode) {
            OverlayControlMode.ACCESSIBILITY -> {
                hideBall()
                registerVolumeKeyListener()
                showPanel()
            }

            OverlayControlMode.FLOAT_BALL -> {
                unregisterVolumeKeyListener()
                showBall()
            }
        }
    }

    suspend fun hideAll() {
        hidePanel()
        hideBall()
        borderOverlayManager.hide()
        unregisterVolumeKeyListener()
        _isActive.value = false
    }

    private fun showPanel() {
        viewModelOwner.start()
        FloatingX.controlOrNull(PANEL_TAG)?.show()
    }

    private fun hidePanel() {
        FloatingX.controlOrNull(PANEL_TAG)?.hide()
        viewModelOwner.stop()
    }

    private fun showBall() = FloatingX.controlOrNull(BALL_TAG)?.show()

    private fun hideBall() = FloatingX.controlOrNull(BALL_TAG)?.hide()

    private fun togglePanel() {
        if (FloatingX.controlOrNull(PANEL_TAG)?.isShow() == true) hidePanel() else showPanel()
    }

    private suspend fun applyMode(mode: OverlayControlMode) {
        if (currentMode == mode) return
        Timber.d("Control overlay mode $currentMode -> $mode")
        when (currentMode) {
            OverlayControlMode.ACCESSIBILITY -> borderOverlayManager.hide()
            OverlayControlMode.FLOAT_BALL -> hideBall()
        }
        currentMode = mode
        if (!_isActive.value) return
        when (mode) {
            OverlayControlMode.ACCESSIBILITY -> {
                registerVolumeKeyListener()
                if (runnerPort.state.value.phase.isBusy) borderOverlayManager.show()
            }

            OverlayControlMode.FLOAT_BALL -> {
                unregisterVolumeKeyListener()
                showBall()
            }
        }
    }

    private fun registerVolumeKeyListener() {
        AccessibilityHelperService.onVolumeUpDownPressed.set { scope.launch { togglePanel() } }
    }

    private fun unregisterVolumeKeyListener() {
        AccessibilityHelperService.onVolumeUpDownPressed.set(null)
    }

    // ── 布局 ──

    /** 横屏时高度吃满一点：可用高度本来就少，按竖屏那个比例会挤成一条 */
    private fun calculatePanelLayout(config: Configuration): Pair<Int, Int> {
        val density = context.resources.displayMetrics.density
        val heightRatio = if (config.orientation == Configuration.ORIENTATION_LANDSCAPE) 0.85f else 0.6f
        return (config.screenWidthDp * density * 0.85f).toInt() to
            (config.screenHeightDp * density * heightRatio).toInt()
    }

    private fun applyPanelLayout(config: Configuration, layout: Pair<Int, Int>) {
        val control = FloatingX.controlOrNull(PANEL_TAG) ?: return
        val density = context.resources.displayMetrics.density
        val (width, height) = layout
        val wasShowing = control.isShow()
        if (wasShowing) control.hide()
        control.move((config.screenWidthDp * density - width) / 2, (config.screenHeightDp * density - height) / 2)
        // 尺寸变了必须换视图：FloatingX 不会因为 layoutParams 改了就重新测量已挂载的那份
        control.updateView(createPanelView())
        if (wasShowing) control.show()
    }

    private companion object {
        const val PANEL_TAG = "maafw_overlay_panel"
        const val BALL_TAG = "maafw_overlay_ball"
    }
}
