package com.aliothmoon.maafw.remote.internal

import android.os.Build
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import com.aliothmoon.maafw.constant.AndroidVersions
import com.aliothmoon.maafw.constant.DefaultDisplayConfig
import com.aliothmoon.maafw.third.Ln
import com.aliothmoon.maafw.third.wrappers.DisplayControl
import com.aliothmoon.maafw.third.wrappers.DisplayManager
import com.aliothmoon.maafw.third.wrappers.PowerManager
import com.aliothmoon.maafw.third.wrappers.ServiceManager
import com.aliothmoon.maafw.third.wrappers.SurfaceControl
import java.util.concurrent.atomic.AtomicBoolean

object PowerController {
    private const val TAG = "PowerController"
    private const val USER_ACTIVITY_INTERVAL_MS = 4_000L

    // 不落盘：落盘的标记会被被杀的进程留下，下一个进程 destroy 时把用户自己关的屏强行点亮。
    // 进程被杀后面板由用户唤醒时系统恢复
    private val poweredOff = AtomicBoolean(false)

    private val keepAliveLock = Any()
    private var keepAliveDisplayId = DefaultDisplayConfig.DISPLAY_NONE
    private var keepAliveThread: Thread? = null

    @VisibleForTesting
    internal var displayManager: () -> DisplayManager = { ServiceManager.getDisplayManager() }

    @VisibleForTesting
    internal var powerManager: () -> PowerManager = { ServiceManager.getPowerManager() }

    /** 判不出按醒着处理，宁可点亮 */
    @VisibleForTesting
    internal var systemAwake: () -> Boolean = {
        runCatching { powerManager().isScreenOnOrNull(0) }.getOrNull() ?: true
    }

    fun hasOwnPowerGroup(displayId: Int): Boolean =
        runCatching { displayManager().getDisplayGroupId(displayId) }.getOrDefault(-1) > 0

    // 同主屏电源组时预览不续命，避免主屏无法自然息屏
    internal fun targetKeepAliveDisplay(
        displayId: Int?,
        taskKeepAlive: Boolean,
        previewKeepAlive: Boolean,
    ): Int? = displayId?.takeIf { taskKeepAlive || (previewKeepAlive && hasOwnPowerGroup(it)) }

    fun setDisplayPower(on: Boolean): Boolean {
        poweredOff.set(!on)
        return setDisplayPowerInternal(on)
    }

    private fun setDisplayPowerInternal(on: Boolean): Boolean {
        var applyToMultiPhysicalDisplays =
            Build.VERSION.SDK_INT >= AndroidVersions.API_29_ANDROID_10

        if (applyToMultiPhysicalDisplays
            && Build.VERSION.SDK_INT >= AndroidVersions.API_34_ANDROID_14 && Build.BRAND.equals(
                "honor",
                ignoreCase = true
            )
            && SurfaceControl.hasGetBuildInDisplayMethod()
        ) {
            applyToMultiPhysicalDisplays = false
        }

        val mode: Int =
            if (on) SurfaceControl.POWER_MODE_NORMAL else SurfaceControl.POWER_MODE_OFF
        if (applyToMultiPhysicalDisplays) {
            val useDisplayControl =
                Build.VERSION.SDK_INT >= AndroidVersions.API_34_ANDROID_14 && !SurfaceControl.hasGetPhysicalDisplayIdsMethod()

            val physicalDisplayIds =
                if (useDisplayControl) DisplayControl.getPhysicalDisplayIds() else SurfaceControl.getPhysicalDisplayIds()
            if (physicalDisplayIds == null) {
                Ln.e("Could not get physical display ids")
                return false
            }

            var allOk = true
            for (physicalDisplayId in physicalDisplayIds) {
                val binder = if (useDisplayControl) DisplayControl.getPhysicalDisplayToken(
                    physicalDisplayId
                ) else SurfaceControl.getPhysicalDisplayToken(physicalDisplayId)
                allOk = allOk and SurfaceControl.setDisplayPowerMode(binder, mode)
            }
            return allOk
        }

        val d = SurfaceControl.getBuiltInDisplay()
        if (d == null) {
            Ln.e("Could not get built-in display")
            return false
        }
        return SurfaceControl.setDisplayPowerMode(d, mode)
    }

    fun startUserActivityKeepAlive(displayId: Int) {
        synchronized(keepAliveLock) {
            val prev = keepAliveDisplayId
            keepAliveDisplayId = displayId
            if (keepAliveThread != null) {
                if (prev != displayId && prev != DefaultDisplayConfig.DISPLAY_NONE) {
                    Ln.i("$TAG: userActivity keep-alive switched displayId=$prev -> $displayId")
                }
                return
            }
            Ln.i("$TAG: userActivity keep-alive started, displayId=$displayId")
            keepAliveThread = Thread(::runKeepAlive).apply {
                name = "power-user-activity-keepalive"
                isDaemon = true
                start()
            }
        }
    }

    fun stopUserActivityKeepAlive() {
        synchronized(keepAliveLock) { keepAliveDisplayId = DefaultDisplayConfig.DISPLAY_NONE }
    }

    // 退出与清零 keepAliveThread 同锁，防停后立刻重启撞上线程收尾
    private fun nextKeepAliveDisplay(): Int = synchronized(keepAliveLock) {
        keepAliveDisplayId.also { if (it == DefaultDisplayConfig.DISPLAY_NONE) keepAliveThread = null }
    }

    private fun runKeepAlive() {
        while (true) {
            val id = nextKeepAliveDisplay()
            if (id == DefaultDisplayConfig.DISPLAY_NONE) break
            runCatching { powerManager().userActivity(id) }
                .onFailure { Ln.e("$TAG: userActivity failed", it) }
            SystemClock.sleep(USER_ACTIVITY_INTERVAL_MS)
        }
        Ln.i("$TAG: userActivity keep-alive stopped")
    }

    // 仅管独立电源组（同主屏组唤醒会点亮主屏）；14+ 可按屏查交互态，16+ 可按屏唤醒
    fun ensureDisplayAwake(displayId: Int): Boolean =
        ensureDisplayAwake(displayId, Build.VERSION.SDK_INT)

    @VisibleForTesting
    internal fun ensureDisplayAwake(displayId: Int, sdkInt: Int): Boolean {
        if (sdkInt < AndroidVersions.API_34_ANDROID_14 || !hasOwnPowerGroup(displayId)) return true
        val pm = runCatching { powerManager() }.getOrNull() ?: return true
        if (pm.isScreenOnOrNull(displayId) != false) return true
        val woke = sdkInt >= AndroidVersions.API_36_ANDROID_16
            && pm.wakeUp(displayId)
            && pm.isScreenOnOrNull(displayId) == true
        Ln.i("$TAG: display $displayId asleep, wake ${if (woke) "ok" else "failed"}")
        return woke
    }

    fun destroy() {
        stopUserActivityKeepAlive()
        if (poweredOff.get()) {
            // 系统已休眠就别点亮：用户关了屏走开，进程这时退出不该把屏点亮；下次唤醒由系统恢复面板
            if (!systemAwake()) {
                Ln.i("$TAG: System asleep, leave display power to the system")
                poweredOff.set(false)
                return
            }
            Ln.i("$TAG: Emergency recovering screen power...")
            runCatching {
                setDisplayPower(true)
            }.onFailure {
                Ln.e("$TAG: Failed to recover screen power: ${it.message}")
            }
        }
    }
}