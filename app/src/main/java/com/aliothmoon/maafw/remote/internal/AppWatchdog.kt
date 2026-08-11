package com.aliothmoon.maafw.remote.internal

import android.os.SystemClock
import com.aliothmoon.maafw.constant.DefaultDisplayConfig
import com.aliothmoon.maafw.third.Ln
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 盯虚拟屏上的目标 app：从 [VirtualDisplayManager] 的 displayId 反推顶层包名作为目标，
 * 离屏则用 [ActivityUtils.repinAppToDisplay] 拉回；状态经 RemoteService.watchdogState() 暴露给 app。
 *
 * 与 MaaMeow 的差异：目标包不是外部告知，而是 getTopPackageOnDisplay 自取；
 * 判活与 onDisplay 合成一步（屏上有 app 即活）。全程 runCatching 宽松，不抛不误伤
 */
object AppWatchdog {

    const val STATE_IDLE = 0
    const val STATE_WATCHING = 1
    const val STATE_APP_DIED = 2

    private const val POLL_INTERVAL_MS = 5000L
    private const val REPIN_GRACE_MS = 5000L
    private const val MAX_REPIN_ATTEMPTS = 3

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private val _state = MutableStateFlow(STATE_IDLE)
    val state: StateFlow<Int> = _state.asStateFlow()

    private var job: Job? = null
    private var targetPackage: String? = null
    private var driftFirstSeenMs = 0L
    private var driftRepinAttempts = 0
    private var diedNotified = false

    fun startWatching() {
        stopWatching()
        targetPackage = null
        driftFirstSeenMs = 0L
        driftRepinAttempts = 0
        diedNotified = false
        _state.value = STATE_IDLE
        Ln.i("AppWatchdog: start watching display ${VirtualDisplayManager.getDisplayId()}")
        job = scope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                tick()
            }
        }
    }

    fun stopWatching() {
        job?.cancel()
        job = null
        _state.value = STATE_IDLE
    }

    private fun tick() {
        val displayId = VirtualDisplayManager.getDisplayId()
        if (displayId == DefaultDisplayConfig.DISPLAY_NONE) {
            _state.value = STATE_IDLE
            return
        }
        val top = runCatching { ActivityUtils.getTopPackageOnDisplay(displayId) }.getOrNull()
        if (top != null) {
            if (targetPackage == null) Ln.i("AppWatchdog: target acquired: $top")
            targetPackage = top
            driftFirstSeenMs = 0L
            driftRepinAttempts = 0
            diedNotified = false
            _state.value = STATE_WATCHING
            return
        }

        val pkg = targetPackage
        if (pkg == null) {
            _state.value = STATE_IDLE
            return
        }

        // 曾有目标、屏上空了 → 宽限后 repin，超限判死
        if (driftRepinAttempts >= MAX_REPIN_ATTEMPTS) return
        val now = SystemClock.elapsedRealtime()
        if (driftFirstSeenMs == 0L) {
            driftFirstSeenMs = now
            Ln.i("AppWatchdog: $pkg left the virtual display, grace ${REPIN_GRACE_MS}ms")
            return
        }
        if (now - driftFirstSeenMs < REPIN_GRACE_MS) return

        Ln.w("AppWatchdog: $pkg drifted, repin attempt ${driftRepinAttempts + 1}/$MAX_REPIN_ATTEMPTS")
        val ok = runCatching { ActivityUtils.repinAppToDisplay(pkg, displayId) }.getOrDefault(false)
        val back = ok && runCatching { ActivityUtils.isAppOnDisplay(pkg, displayId) }.getOrDefault(true)
        if (back) {
            Ln.i("AppWatchdog: $pkg moved back to the virtual display")
            driftFirstSeenMs = 0L
            driftRepinAttempts = 0
            return
        }
        driftRepinAttempts++
        if (driftRepinAttempts >= MAX_REPIN_ATTEMPTS && !diedNotified) {
            diedNotified = true
            _state.value = STATE_APP_DIED
            Ln.w("AppWatchdog: $pkg gone and repin failed")
        }
    }
}
