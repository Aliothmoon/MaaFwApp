package com.aliothmoon.maafw.remote

import com.aliothmoon.maafw.ITouchEventCallback
import com.aliothmoon.maafw.RemoteService
import com.aliothmoon.maafw.bridge.InputControlUtils
import com.aliothmoon.maafw.bridge.NativeBridgeLib
import com.aliothmoon.maafw.constant.DefaultDisplayConfig
import com.aliothmoon.maafw.constant.DisplayMode
import com.aliothmoon.maafw.remote.internal.ActivityUtils
import com.aliothmoon.maafw.remote.internal.PermissionGrantHelper
import com.aliothmoon.maafw.remote.internal.PowerController
import com.aliothmoon.maafw.remote.internal.PrimaryDisplayManager
import com.aliothmoon.maafw.remote.internal.VirtualDisplayManager
import com.aliothmoon.maafw.third.FakeContext
import com.aliothmoon.maafw.third.Ln
import com.aliothmoon.maafw.third.Workarounds
import android.view.Surface
import android.os.Process
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

/**
 * 特权进程的入口对象：由 Shizuku 或 root starter 反射实例化，实例化即完成进程内初始化
 * 构造函数不能抛：抛了 binder 回不去，app 侧只看得到连接超时
 */
class RemoteServiceImpl : RemoteService.Stub() {

    private val virtualDisplayMode = AtomicInteger(DisplayMode.BACKGROUND)
    private val appPid = AtomicInteger(0)
    private val destroyed = AtomicBoolean(false)
    private var piRoot: String? = null

    init {
        RemoteBootTrace.mark("CTOR_START")
        Workarounds.apply()
        Runtime.getRuntime().addShutdownHook(
            Thread { runCatching(::cleanup) }.apply { name = "remote-shutdown-hook" }
        )
        startHeartbeatWatchdog()
        RemoteBootTrace.mark("CTOR_DONE")
    }

    override fun destroy() {
        if (!destroyed.compareAndSet(false, true)) return
        Ln.i("$TAG: destroy()")
        InputControlUtils.setTouchCallback(null)
        cleanup()
        exitProcess(0)
    }

    override fun exit() = destroy()

    override fun version(): String = buildString {
        append("bridge=").append(if (NativeBridgeLib.LOADED) NativeBridgeLib.ping() else "not loaded")
        append(" uid=").append(Process.myUid())
        append(" pid=").append(Process.myPid())
        append(" pi=").append(piRoot ?: "unset")
    }

    override fun pid(): Int = Process.myPid()

    override fun heartbeat(pid: Int) {
        appPid.set(pid)
    }

    override fun setup(piRoot: String?, isDebug: Boolean): Boolean {
        if (piRoot.isNullOrBlank() || !File(piRoot).isDirectory) {
            Ln.e("$TAG: setup failed - PI root not readable: $piRoot")
            return false
        }
        this.piRoot = piRoot
        // Android 12 起子进程会被 phantom process killer 收割，接 native 前先关掉
        PermissionGrantHelper.disablePhantomProcessKiller()
        Ln.i("$TAG: setup ok, piRoot=$piRoot")
        return true
    }

    // ── 显示 ──

    override fun setVirtualDisplayMode(mode: Int): Boolean = when (mode) {
        DisplayMode.PRIMARY -> {
            VirtualDisplayManager.stop()
            virtualDisplayMode.set(mode)
            true
        }

        DisplayMode.BACKGROUND -> {
            PrimaryDisplayManager.stop()
            virtualDisplayMode.set(mode)
            true
        }

        else -> false
    }

    override fun setVirtualDisplayResolution(width: Int, height: Int, dpi: Int) {
        VirtualDisplayManager.setResolution(width, height, dpi)
    }

    override fun startVirtualDisplay(): Int = when (virtualDisplayMode.get()) {
        DisplayMode.PRIMARY -> PrimaryDisplayManager.start()
        DisplayMode.BACKGROUND -> VirtualDisplayManager.start().also { displayId ->
            if (displayId != DefaultDisplayConfig.DISPLAY_NONE) {
                PowerController.startUserActivityKeepAlive(displayId)
            }
        }

        else -> DefaultDisplayConfig.DISPLAY_NONE
    }

    override fun stopVirtualDisplay() {
        when (virtualDisplayMode.get()) {
            DisplayMode.PRIMARY -> PrimaryDisplayManager.stop()
            DisplayMode.BACKGROUND -> {
                PowerController.stopUserActivityKeepAlive()
                VirtualDisplayManager.stop()
            }
        }
    }

    /** 没有虚拟屏时返回 true：调用方据此判断「是否需要拉回」，无屏可拉即无需处理 */
    override fun isAppOnVirtualDisplay(packageName: String): Boolean {
        val displayId = VirtualDisplayManager.getDisplayId()
        if (displayId == DefaultDisplayConfig.DISPLAY_NONE) return true
        return ActivityUtils.isAppOnDisplay(packageName, displayId)
    }

    override fun moveAppToVirtualDisplay(packageName: String): Boolean {
        val displayId = VirtualDisplayManager.getDisplayId()
        if (displayId == DefaultDisplayConfig.DISPLAY_NONE) {
            Ln.w("$TAG: moveAppToVirtualDisplay: no active virtual display")
            return false
        }
        return ActivityUtils.repinAppToDisplay(packageName, displayId)
    }

    override fun setForceFullscreenOnVirtualDisplay(enabled: Boolean) {
        ActivityUtils.forceFullscreenOnVirtualDisplay = enabled
    }

    override fun setDisplayPower(on: Boolean) {
        PowerController.setDisplayPower(on)
    }

    // ── 预览 ──

    override fun setMonitorSurface(surface: Surface?) {
        Ln.i("$TAG: setMonitorSurface(${surface != null})")
        VirtualDisplayManager.setMonitorSurface(surface)
        NativeBridgeLib.setPreviewSurface(surface)
    }

    override fun setTouchCallback(callback: ITouchEventCallback?) {
        InputControlUtils.setTouchCallback(callback)
    }

    // ── 预览上的手动操作；主屏模式下不接管输入 ──

    override fun touchDown(x: Int, y: Int) = withVirtualDisplay { InputControlUtils.down(x, y, it) }

    override fun touchMove(x: Int, y: Int) = withVirtualDisplay { InputControlUtils.move(x, y, it) }

    override fun touchUp(x: Int, y: Int) = withVirtualDisplay { InputControlUtils.up(x, y, it) }

    private inline fun withVirtualDisplay(action: (Int) -> Unit) {
        if (virtualDisplayMode.get() == DisplayMode.PRIMARY) return
        val displayId = VirtualDisplayManager.getDisplayId()
        if (displayId != DefaultDisplayConfig.DISPLAY_NONE) action(displayId)
    }

    override fun isPackageInstalled(packageName: String): Boolean = try {
        FakeContext.get().packageManager.getPackageInfo(packageName, 0)
        true
    } catch (e: Exception) {
        Ln.w("$TAG: isPackageInstalled: $packageName not found", e)
        false
    }

    private fun cleanup() {
        runCatching {
            PowerController.destroy()
            PrimaryDisplayManager.stop()
            VirtualDisplayManager.stop()
        }.onFailure { Ln.e("$TAG: cleanup failed: ${it.message}") }
    }

    /**
     * app 进程消失后特权进程必须自杀
     * linkToDeath 是主路径，这里兜住「binder 还没建立就崩了」的窗口
     */
    private fun startHeartbeatWatchdog() {
        Thread {
            while (!destroyed.get()) {
                try {
                    Thread.sleep(HEARTBEAT_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                val pid = appPid.get()
                if (pid <= 0) continue
                if (!File("/proc/$pid").exists()) {
                    Ln.w("$TAG: app process (pid=$pid) gone, destroying remote service")
                    destroy()
                    return@Thread
                }
            }
        }.apply {
            name = "remote-heartbeat-watchdog"
            isDaemon = true
        }.start()
    }

    private companion object {
        const val TAG = "RemoteService"
        const val HEARTBEAT_INTERVAL_MS = 5_000L
    }
}
