package com.aliothmoon.maafw.remote.internal

import android.os.Build
import com.aliothmoon.maafw.constant.AndroidVersions
import com.aliothmoon.maafw.constant.DefaultDisplayConfig
import com.aliothmoon.maafw.third.Ln
import com.aliothmoon.maafw.third.wrappers.DisplayControl
import com.aliothmoon.maafw.third.wrappers.ServiceManager
import com.aliothmoon.maafw.third.wrappers.SurfaceControl
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

object PowerController {
    private const val TAG = "PowerController"
    private const val USER_ACTIVITY_INTERVAL_MS = 4_000L

    // 不落盘：落盘的标记会被被杀的进程留下，下一个进程 destroy 时把用户自己关的屏强行点亮。
    // 进程被杀后面板由用户唤醒时系统恢复
    private val poweredOff = AtomicBoolean(false)

    private val keepAliveDisplayId = AtomicInteger(DefaultDisplayConfig.DISPLAY_NONE)
    private val keepAliveRunning = AtomicBoolean(false)
    private val keepAliveWhile = AtomicReference<() -> Boolean> { false }

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

    /** [isActive] 转 false 即自停；接着排队的下一轮在线程醒来前到达时，换上新的判定继续续 */
    fun startUserActivityKeepAlive(displayId: Int, isActive: () -> Boolean) {
        keepAliveWhile.set(isActive)
        keepAliveDisplayId.set(displayId)
        if (!keepAliveRunning.compareAndSet(false, true)) return
        Thread {
            Ln.i("$TAG: userActivity keep-alive started, displayId=$displayId")
            while (true) {
                val id = keepAliveDisplayId.get()
                if (id == DefaultDisplayConfig.DISPLAY_NONE) break
                try {
                    Thread.sleep(USER_ACTIVITY_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                }
                val currentId = keepAliveDisplayId.get()
                if (currentId == DefaultDisplayConfig.DISPLAY_NONE || !keepAliveWhile.get()()) break
                runCatching { ServiceManager.getPowerManager().userActivity(currentId) }
                    .onFailure { Ln.e("$TAG: userActivity failed", it) }
            }
            keepAliveRunning.set(false)
            Ln.i("$TAG: userActivity keep-alive stopped")
        }.apply {
            name = "power-user-activity-keepalive"
            isDaemon = true
        }.start()
    }

    fun stopUserActivityKeepAlive() {
        keepAliveDisplayId.set(DefaultDisplayConfig.DISPLAY_NONE)
    }

    /** 判不出按醒着处理，宁可点亮 */
    internal var systemAwake: () -> Boolean = {
        runCatching { ServiceManager.getPowerManager().isScreenOnOrNull(0) }.getOrNull() ?: true
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