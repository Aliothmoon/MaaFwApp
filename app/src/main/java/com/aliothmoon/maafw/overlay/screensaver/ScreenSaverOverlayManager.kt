package com.aliothmoon.maafw.overlay.screensaver

import com.aliothmoon.maafw.domain.RunMode
import com.aliothmoon.maafw.privileged.PrivilegedServicePort
import com.aliothmoon.maafw.runner.RunnerPhase
import com.aliothmoon.maafw.runner.RunnerPort
import com.aliothmoon.maafw.runner.isBusy
import com.aliothmoon.maafw.service.AccessibilityHelperService
import com.aliothmoon.maafw.settings.AppSettingsGateway
import kotlinx.coroutines.CancellationException
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
 * 后台模式运行期关闭物理屏幕。
 *
 * 游戏仍运行在原有虚拟显示屏上；无障碍服务负责接收音量减键并恢复物理屏幕。
 */
class ScreenSaverOverlayManager(
    private val runnerPort: RunnerPort,
    private val appSettings: AppSettingsGateway,
    private val servicePort: PrivilegedServicePort,
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var phaseJob: Job? = null

    private val _isShowing = MutableStateFlow(false)
    val isShowing: StateFlow<Boolean> = _isShowing.asStateFlow()

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

    private fun observePhase() {
        if (phaseJob != null) return
        phaseJob = scope.launch {
            var previous: RunnerPhase = runnerPort.state.value.phase
            runnerPort.state.collect { state ->
                val current = state.phase
                when {
                    !previous.isBusy && current.isBusy ->
                        if (appSettings.screenSaverEnabled.value) show()
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

    /** 返回 true 表示已请求特权服务关闭物理屏幕。 */
    suspend fun show(): Boolean {
        if (_isShowing.value) return true
        if (appSettings.runMode.value != RunMode.BACKGROUND) {
            Timber.w("Not in background mode; ignoring real screen-off request")
            return false
        }

        // 先安装唤醒监听，再关屏，避免两步之间漏掉用户的按键。
        AccessibilityHelperService.setVolumeDownListener {
            scope.launch { wakeScreen() }
        }

        return try {
            val applied = withContext(Dispatchers.IO) {
                servicePort.useService { it.setDisplayPowerAndReport(false) }
            }
            if (!applied) {
                AccessibilityHelperService.setVolumeDownListener(null)
                Timber.e("SurfaceControl rejected physical screen-off request")
                return false
            }
            _isShowing.value = true
            Timber.i("Physical display powered off; volume-down wake listener armed")
            true
        } catch (e: CancellationException) {
            AccessibilityHelperService.setVolumeDownListener(null)
            throw e
        } catch (e: Exception) {
            AccessibilityHelperService.setVolumeDownListener(null)
            Timber.e(e, "Failed to power off physical display")
            false
        }
    }

    /** 运行结束时恢复屏幕；若调用失败，保留音量减监听以便用户仍可唤醒。 */
    suspend fun hide() {
        if (!_isShowing.value) return
        try {
            val applied = withContext(Dispatchers.IO) {
                servicePort.useService { it.setDisplayPowerAndReport(true) }
            }
            check(applied) { "SurfaceControl rejected physical screen-on request" }
            finishWake()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Failed to restore physical display after run")
        }
    }

    private suspend fun wakeScreen() {
        if (!_isShowing.value) return
        try {
            withContext(Dispatchers.IO) {
                servicePort.useService { it.setDisplayPower(true) }
            }
            finishWake()
            Timber.i("Physical display restored by volume-down key")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 不摘监听：用户可以再按一次音量减重试。
            Timber.e(e, "Volume-down wake failed; listener remains armed")
        }
    }

    private fun finishWake() {
        AccessibilityHelperService.setVolumeDownListener(null)
        _isShowing.value = false
    }
}
