package com.aliothmoon.maafw.remote

import com.aliothmoon.maafw.IMaaRunnerCallback
import com.aliothmoon.maafw.ITouchEventCallback
import com.aliothmoon.maafw.RemoteService
import com.aliothmoon.maafw.bridge.InputControlUtils
import com.aliothmoon.maafw.bridge.NativeBridgeLib
import com.aliothmoon.maafw.constant.DefaultDisplayConfig
import com.aliothmoon.maafw.constant.DisplayMode
import com.aliothmoon.maafw.maa.MaaFrameworkLoader
import com.aliothmoon.maafw.remote.internal.ActivityUtils
import com.aliothmoon.maafw.remote.internal.AppWatchdog
import com.aliothmoon.maafw.remote.internal.PermissionGrantHelper
import com.aliothmoon.maafw.service.AccessibilityHelperService
import com.aliothmoon.maafw.remote.internal.PowerController
import com.aliothmoon.maafw.remote.internal.PrimaryDisplayManager
import com.aliothmoon.maafw.constant.PrivilegedGrant
import com.aliothmoon.maafw.remote.internal.VirtualDisplayManager
import com.aliothmoon.maafw.remote.internal.WakeUnlockController
import com.aliothmoon.maafw.third.FakeContext
import com.aliothmoon.maafw.third.Ln
import com.aliothmoon.maafw.third.wrappers.ServiceManager
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
    // 两者互相引用：host 要把 child 的输出交回 runner 的回调。用 lazy 打破初始化顺序——
    // host 的 lambda 到真正有输出时才读 runner，那会儿它早已建好
    private val runner: MaaRunner by lazy { MaaRunner(agentHost) }
    private val agentHost: ExecAgentHost by lazy { ExecAgentHost { runner.onAgentLine(it) } }

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
        AppWatchdog.stopWatching()
        InputControlUtils.setTouchCallback(null)
        runner.destroy()
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

    override fun watchdogState(): Int = AppWatchdog.state.value

    // ── 亮屏与解锁 ──

    override fun unlock(credential: String?): Int =
        WakeUnlockController.unlock(credential.orEmpty())

    override fun testUnlock(credential: String?): Int =
        WakeUnlockController.testUnlock(credential.orEmpty())

    override fun lockAndSleep(): Int = WakeUnlockController.lockAndSleep()

    override fun isScreenOn(): Boolean =
        runCatching { ServiceManager.getPowerManager().isScreenOn(0) }.getOrDefault(true)

    override fun stopTargetApp(): Boolean {
        val target = AppWatchdog.targetPackage ?: run {
            Ln.i("$TAG: stopTargetApp skipped, watchdog never acquired a target")
            return false
        }
        return runCatching {
            ServiceManager.getActivityManager().forceStopPackage(target)
            Ln.i("$TAG: force-stopped $target")
            true
        }.getOrElse {
            Ln.w("$TAG: stopTargetApp failed: ${'$'}it")
            false
        }
    }

    override fun heartbeat(pid: Int) {
        appPid.set(pid)
    }

    override fun setup(piRoot: String?, logDir: String?, isDebug: Boolean): Boolean {
        if (piRoot.isNullOrBlank() || !File(piRoot).isDirectory) {
            Ln.e("$TAG: setup failed - PI root not readable: $piRoot")
            return false
        }
        this.piRoot = piRoot
        // agent child 的 cwd 与上游 MaaPiCli 对齐，取 PI 根
        runner.setProjectRoot(piRoot)
        // Android 12 起子进程会被 phantom process killer 收割，接 native 前先关掉
        // agent child 同样吃这条：它是特权进程 fork 出来的，不关就会被一起收走
        PermissionGrantHelper.disablePhantomProcessKiller()
        // 特权进程是 shell/root 身份，app 建的目录未必可写，这里自己建一遍
        if (!logDir.isNullOrBlank() && ensureWritableDir(logDir)) {
            runner.applyGlobalOptions(logDir, isDebug)
        } else {
            Ln.w("$TAG: log dir unusable, MaaFramework will write to process CWD: $logDir")
        }
        Ln.i("$TAG: setup ok, piRoot=$piRoot")
        return true
    }

    private fun ensureWritableDir(path: String): Boolean {
        val dir = File(path)
        if (!dir.isDirectory && !dir.mkdirs()) {
            Ln.e("$TAG: mkdirs failed: $path")
            return false
        }
        return dir.canWrite()
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
        AppWatchdog.stopWatching()
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

    // ── 执行 ──

    override fun setRunnerCallback(callback: IMaaRunnerCallback?) {
        runner.setCallback(callback)
    }

    override fun startRun(runPlanJson: String?): Boolean {
        if (runPlanJson.isNullOrBlank()) return false
        val started = runner.start(runPlanJson)
        if (started) AppWatchdog.startWatching()
        return started
    }

    override fun stopRun(): Boolean {
        AppWatchdog.stopWatching()
        return runner.stop()
    }

    override fun isRunning(): Boolean = runner.isRunning()

    override fun saveCachedImage(path: String?): Boolean =
        !path.isNullOrBlank() && runner.saveCachedImage(path)

    override fun maaVersion(): String? = MaaFrameworkLoader.library?.MaaVersion()

    /**
     * 逐项独立执行：一项失败不影响其余，返回实际授到的位
     * 失败不抛——app 侧据返回值决定要不要再引导用户手点
     */
    override fun grantPermissions(packageName: String?, uid: Int, permissions: Int): Int {
        if (packageName.isNullOrBlank()) return 0
        var granted = 0
        if (permissions and PrivilegedGrant.NOTIFICATION != 0 &&
            PermissionGrantHelper.grantNotificationPermission(packageName, uid)
        ) {
            granted = granted or PrivilegedGrant.NOTIFICATION
        }
        if (permissions and PrivilegedGrant.BATTERY != 0 &&
            PermissionGrantHelper.grantBatteryOptimizationExemption(packageName)
        ) {
            granted = granted or PrivilegedGrant.BATTERY
        }
        if (permissions and PrivilegedGrant.BACKGROUND != 0 &&
            PermissionGrantHelper.grantBackgroundUnrestricted(packageName, uid)
        ) {
            granted = granted or PrivilegedGrant.BACKGROUND
        }
        if (permissions and PrivilegedGrant.OVERLAY != 0 &&
            PermissionGrantHelper.grantFloatingWindowPermission(packageName, uid)
        ) {
            granted = granted or PrivilegedGrant.OVERLAY
        }
        // 服务 id 不用过 binder 传：特权进程跑的就是这个 APK，直接引用常量即可
        if (permissions and PrivilegedGrant.ACCESSIBILITY != 0 &&
            PermissionGrantHelper.grantAccessibilityService(AccessibilityHelperService.SERVICE_ID)
        ) {
            granted = granted or PrivilegedGrant.ACCESSIBILITY
        }
        if (permissions and PrivilegedGrant.STORAGE != 0 &&
            PermissionGrantHelper.grantStoragePermission(packageName, uid)
        ) {
            granted = granted or PrivilegedGrant.STORAGE
        }
        Ln.i("$TAG: grantPermissions($packageName) requested=$permissions granted=$granted")
        return granted
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
