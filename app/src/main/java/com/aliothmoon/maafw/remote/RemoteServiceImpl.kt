package com.aliothmoon.maafw.remote

import com.aliothmoon.maafw.BuildConfig
import com.aliothmoon.maafw.IMaaRunnerCallback
import com.aliothmoon.maafw.ITextInputSink
import com.aliothmoon.maafw.ITouchEventCallback
import com.aliothmoon.maafw.RemoteService
import com.aliothmoon.maafw.bridge.InputControlUtils
import com.aliothmoon.maafw.bridge.NativeBridgeLib
import com.aliothmoon.maafw.bridge.TextInputDispatcher
import com.aliothmoon.maafw.constant.AppFiles
import com.aliothmoon.maafw.constant.DefaultDisplayConfig
import com.aliothmoon.maafw.constant.DisplayMode
import com.aliothmoon.maafw.maa.MaaFrameworkLoader
import com.aliothmoon.maafw.remote.internal.ActivityUtils
import com.aliothmoon.maafw.remote.internal.DisplayLifecycle
import com.aliothmoon.maafw.remote.internal.AppWatchdog
import com.aliothmoon.maafw.remote.internal.GameFpsMonitor
import com.aliothmoon.maafw.remote.internal.GestureRecorder
import com.aliothmoon.maafw.remote.internal.PermissionGrantHelper
import com.aliothmoon.maafw.service.AccessibilityHelperService
import com.aliothmoon.maafw.remote.internal.PowerController
import com.aliothmoon.maafw.remote.internal.PrimaryDisplayManager
import com.aliothmoon.maafw.remote.internal.ScreenManager
import com.aliothmoon.maafw.remote.internal.StaleFrameGuard
import com.aliothmoon.maafw.constant.PrivilegedGrant
import com.aliothmoon.maafw.log.LogExportCollector
import com.aliothmoon.maafw.log.toWireJson
import com.aliothmoon.maafw.remote.internal.LogcatRetention
import com.aliothmoon.maafw.remote.internal.MaafwLogRetention
import com.aliothmoon.maafw.remote.internal.PathDiagnosis
import com.aliothmoon.maafw.remote.internal.ProcessExitQuery
import com.aliothmoon.maafw.remote.internal.SystemLogDump
import com.aliothmoon.maafw.remote.internal.ThreadDump
import com.aliothmoon.maafw.remote.internal.VirtualDisplayManager
import com.aliothmoon.maafw.remote.internal.WakeUnlockController
import com.aliothmoon.maafw.remote.internal.XmsfFirewall
import com.aliothmoon.maafw.root.ProcessExit
import com.aliothmoon.maafw.third.FakeContext
import com.aliothmoon.maafw.third.Ln
import com.aliothmoon.maafw.third.wrappers.ServiceManager
import com.aliothmoon.maafw.third.Workarounds
import android.graphics.Bitmap
import android.view.Surface
import android.os.Process
import android.provider.Settings
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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
    private val runnerDelegate = lazy { MaaRunner(agentHost, onRunFinished = ::syncUserActivityKeepAlive) }
    private val runner: MaaRunner by runnerDelegate
    private val agentHost: ExecAgentHost by lazy {
        ExecAgentHost(
            onOutput = { line, fromStderr -> runner.onAgentLine(line, fromStderr) },
            onExit = { runner.onAgentExited(it) },
            crashDir = { logDir?.let { File(it, AppFiles.CRASH_DIR) } },
        )
    }

    // 主屏真实触摸会占满全局 userActivity 节流，预览或任务期间单独给虚拟屏续命
    private val keepAliveLock = Any()
    private var previewKeepAlive = false

    @Volatile
    private var logDir: String? = null

    init {
        RemoteBootTrace.mark("CTOR_START")
        Workarounds.apply()
        // 正常退出走 ProcessExit 的 halt 不经这里，只兜直接 System.exit 的路径
        Runtime.getRuntime().addShutdownHook(
            Thread { runCatching(::cleanup) }.apply { name = "remote-shutdown-hook" }
        )
        startHeartbeatWatchdog()
        // 上一实例可能断了 xmsf 的网没来得及恢复（被杀、崩溃），规则在 netd 里不随进程消失；
        // 要跑 shell，挪到后台线程，构造函数不能拖
        Thread { runCatching(XmsfFirewall::ensureRestored) }.apply { name = "xmsf-boot-restore" }.start()
        RemoteBootTrace.mark("CTOR_DONE")
    }

    override fun destroy() {
        destroyed.set(true)
        ProcessExit.exit("destroy requested") {
            stopDisplayWatchers()
            InputControlUtils.setTouchCallback(null)
            TextInputDispatcher.sink = null
            cleanup()
            // 最可能卡住、又随进程释放：垫底，卡住了由退出期限收场，别挡住前面撤主屏尺寸与电源
            step("runner") { runner.destroy() }
        }
    }

    override fun exit() = destroy()

    override fun version(): String = buildString {
        append("bridge=").append(if (NativeBridgeLib.LOADED) NativeBridgeLib.ping() else "not loaded")
        append(" uid=").append(Process.myUid())
        append(" pid=").append(Process.myPid())
        append(" pi=").append(piRoot ?: "unset")
    }

    override fun pid(): Int = Process.myPid()

    /**
     * 看门狗一轮跑完就回空闲，虚拟屏却不拆：目标应用还在屏上、点全屏也能操作时报「就绪」。
     * 直接问 isAppOnDisplay，不走 isAppOnVirtualDisplay——那个没屏时回 true、命中还拉起帧率监控；
     * 短任务在看门狗首拍前结束时还没有 targetPackage，兜底探一次屏上是否有任务
     */
    override fun watchdogState(): Int {
        val state = AppWatchdog.state.value
        if (state != AppWatchdog.STATE_IDLE) return state
        val displayId = VirtualDisplayManager.getDisplayId()
        if (displayId == DefaultDisplayConfig.DISPLAY_NONE) return state
        val target = AppWatchdog.targetPackage
        val onDisplay = if (target != null) {
            ActivityUtils.isAppOnDisplay(target, displayId)
        } else {
            ActivityUtils.probeDisplay(displayId) is ActivityUtils.DisplayOccupancy.Occupied
        }
        return if (onDisplay) AppWatchdog.STATE_READY else state
    }

    override fun watchdogTargetPackage(): String = AppWatchdog.targetPackage.orEmpty()

    // ── 亮屏与解锁 ──

    override fun unlock(credential: String?): Int =
        WakeUnlockController.unlock(credential.orEmpty())

    override fun testUnlock(credential: String?): Int =
        WakeUnlockController.testUnlock(credential.orEmpty())

    override fun lockAndSleep(): Int = WakeUnlockController.lockAndSleep()

    override fun startGestureRecord(timeoutMs: Int) = GestureRecorder.start(timeoutMs)

    override fun pollGestureRecord(): String = GestureRecorder.poll()

    override fun cancelGestureRecord() = GestureRecorder.cancel()

    override fun unlockWithGesture(gestureJson: String?): Int =
        WakeUnlockController.unlockWithGesture(gestureJson.orEmpty())

    override fun testUnlockGesture(gestureJson: String?): Int =
        WakeUnlockController.testUnlockGesture(gestureJson.orEmpty())

    override fun isScreenOn(): Boolean =
        runCatching { ServiceManager.getPowerManager().isScreenOn(0) }.getOrDefault(true)

    override fun isSmartResolutionEnabled(): Boolean = try {
        Settings.Global.getInt(FakeContext.get().contentResolver, "low_resolution_switch", 0) == 1
    } catch (e: Exception) {
        Ln.w("$TAG: read low_resolution_switch failed", e)
        false
    }

    override fun setPackageNetworkingEnabled(packageName: String?, enabled: Boolean): Boolean {
        if (packageName.isNullOrBlank()) return false
        return XmsfFirewall.setNetworkingEnabled(packageName, enabled)
    }

    override fun stopTargetApp(): Boolean {
        val target = AppWatchdog.targetPackage ?: run {
            Ln.i("$TAG: stopTargetApp skipped, watchdog never acquired a target")
            return false
        }
        return ActivityUtils.forceStop(target, VirtualDisplayManager.getDisplayId()).also { stopped ->
            if (stopped) Ln.i("$TAG: force-stopped $target")
        }
    }

    override fun heartbeat(pid: Int) {
        appPid.set(pid)
    }

    override fun setup(piRoot: String?, logDir: String?, isDebug: Boolean): Boolean {
        if (piRoot.isNullOrBlank() || !File(piRoot).isDirectory) {
            // 属主与挂载点能分清是 root 建坏的目录，还是存储栈不让 shell 进 Android/data
            val diagnosis = piRoot?.takeIf { it.isNotBlank() }?.let { PathDiagnosis.diagnose(File(it)) } ?: "no path"
            Ln.e("$TAG: setup failed - PI root not readable: $piRoot ($diagnosis)")
            return false
        }
        this.piRoot = piRoot
        // agent child 的 cwd 与上游 MaaPiCli 对齐，取 PI 根
        runner.setProjectRoot(piRoot)
        // Android 12 起子进程会被 phantom process killer 收割，接 native 前先关掉
        // agent child 同样吃这条：它是特权进程 fork 出来的，不关就会被一起收走
        PermissionGrantHelper.disablePhantomProcessKiller()
        runCatching(StaleAgentReaper::reapOnce).onFailure { Ln.w("$TAG: reap stale agents failed: ${it.message}") }
        // 特权进程是 shell/root 身份，app 建的目录未必可写，这里自己建一遍
        if (!logDir.isNullOrBlank() && ensureWritableDir(logDir)) {
            this.logDir = logDir
            runner.applyGlobalOptions(logDir, isDebug)
        } else {
            Ln.w("$TAG: log dir unusable, MaaFramework will write to process CWD: $logDir")
        }
        pruneMaafwBackups()
        logDir?.let { pruneLogcatCaptures(File(it).parentFile) }
        Ln.i("$TAG: setup ok, piRoot=$piRoot")
        return true
    }

    /** 外壳每轮开跑前都会先 setup，借这一下每轮收一次：跑一轮就能滚出上百 MB */
    private fun pruneMaafwBackups() {
        val shellLogDir = logDir?.let(::File)
        val piLogs = piRoot?.let { LogExportCollector.PiLogs(File(it), BuildConfig.MAFW_PI_LOG_INCLUDE.toList()) }
        Thread {
            runCatching { MaafwLogRetention.prune(shellLogDir, piLogs) }
                .onSuccess { if (it > 0) Ln.i("$TAG: pruned $it old maafw log backups") }
                .onFailure { Ln.w("$TAG: prune maafw log backups failed: ${it.message}") }
        }.apply { name = "maafw-log-prune"; isDaemon = true }.start()
    }

    /** 放在这里而非 logcat 进程：调试模式关掉后不再起 logcat 进程，旧文件也得有人收 */
    private fun pruneLogcatCaptures(root: File?) {
        root ?: return
        Thread {
            runCatching { LogcatRetention.prune(File(File(root, AppFiles.DEBUG_DIR), AppFiles.LOGCAT_DIR)) }
                .onSuccess { if (it > 0) Ln.i("$TAG: pruned $it old logcat captures") }
                .onFailure { Ln.w("$TAG: prune logcat captures failed: ${it.message}") }
        }.apply { name = "logcat-prune"; isDaemon = true }.start()
    }

    override fun getProcessExitRecords(packageName: String?, sinceMs: Long, maxNum: Int): String =
        ProcessExitQuery.query(packageName, sinceMs, maxNum).toWireJson()

    override fun getVirtualDisplayFlagTier(): Int = VirtualDisplayManager.getFlagTier()

    override fun dumpSystemLog(sinceMs: Long, untilMs: Long, pid: Int, processPrefix: String?): String =
        SystemLogDump.dump(sinceMs, untilMs, pid, processPrefix.orEmpty())

    override fun dumpThreads(): String = ThreadDump.capture()

    override fun setSaveOnError(enabled: Boolean): Boolean {
        runner.setSaveOnError(enabled)
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

    // 读模式与起停在同一把锁里：切模式与起屏交错时，会按旧模式起出一块没人撤的屏
    override fun setVirtualDisplayMode(mode: Int): Boolean = synchronized(DisplayLifecycle.lock) {
        when (mode) {
            DisplayMode.PRIMARY -> {
                virtualDisplayMode.set(mode)
                stopBackgroundDisplay()
                true
            }

            DisplayMode.BACKGROUND -> {
                PrimaryDisplayManager.stop()
                virtualDisplayMode.set(mode)
                true
            }

            else -> false
        }
    }

    override fun setVirtualDisplayResolution(width: Int, height: Int, dpi: Int) = synchronized(DisplayLifecycle.lock) {
        VirtualDisplayManager.setResolution(width, height, dpi)
        syncUserActivityKeepAlive()
    }

    override fun startVirtualDisplay(): Int = synchronized(DisplayLifecycle.lock) {
        when (virtualDisplayMode.get()) {
            DisplayMode.PRIMARY -> PrimaryDisplayManager.start()
            DisplayMode.BACKGROUND -> startBackgroundDisplay()
            else -> DefaultDisplayConfig.DISPLAY_NONE
        }
    }

    private fun startBackgroundDisplay(): Int {
        val reused = VirtualDisplayManager.getDisplayId()
        if (reused != DefaultDisplayConfig.DISPLAY_NONE && !PowerController.ensureDisplayAwake(reused)) {
            Ln.w("$TAG: display $reused asleep, recreating")
            stopBackgroundDisplay()
        }
        return VirtualDisplayManager.start().also { syncUserActivityKeepAlive() }
    }

    private fun stopBackgroundDisplay() {
        GameFpsMonitor.stop()
        StaleFrameGuard.stop()
        VirtualDisplayManager.stop()
        syncUserActivityKeepAlive()
    }

    private fun backgroundDisplayId(): Int? = VirtualDisplayManager.getDisplayId().takeIf {
        virtualDisplayMode.get() == DisplayMode.BACKGROUND && it != DefaultDisplayConfig.DISPLAY_NONE
    }

    private fun isTaskKeepAlive(): Boolean = runnerDelegate.isInitialized() && runner.isRunning()

    private fun syncUserActivityKeepAlive() = synchronized(keepAliveLock) {
        val target = PowerController.targetKeepAliveDisplay(
            backgroundDisplayId(),
            taskKeepAlive = isTaskKeepAlive(),
            previewKeepAlive = previewKeepAlive,
        )
        if (target != null) {
            PowerController.startUserActivityKeepAlive(target)
        } else {
            PowerController.stopUserActivityKeepAlive()
        }
    }

    override fun stopVirtualDisplay() {
        stopDisplayWatchers()
        GameFpsMonitor.stop()
        synchronized(DisplayLifecycle.lock) {
            when (virtualDisplayMode.get()) {
                DisplayMode.PRIMARY -> PrimaryDisplayManager.stop()
                DisplayMode.BACKGROUND -> stopBackgroundDisplay()
            }
        }
    }

    /** 没有虚拟屏时返回 true：调用方据此判断「是否需要拉回」，无屏可拉即无需处理 */
    override fun isAppOnVirtualDisplay(packageName: String): Boolean {
        val displayId = VirtualDisplayManager.getDisplayId()
        if (displayId == DefaultDisplayConfig.DISPLAY_NONE) return true
        return ActivityUtils.isAppOnDisplay(packageName, displayId).also { onDisplay ->
            if (onDisplay) GameFpsMonitor.ensureStarted(packageName)
        }
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

    override fun setDisplayPowerSync(on: Boolean): Boolean = PowerController.setDisplayPower(on)

    override fun getLastWakeTrace(sinceElapsedMs: Long): String = WakeUnlockController.lastTrace(sinceElapsedMs)

    override fun getVisiblePackages(displayId: Int): List<String>? = ActivityUtils.visiblePackages(displayId)

    /**
     * 改主屏分辨率会把整个系统的 UI 重排一遍，失败要报出去而不是吞掉——
     * 用户看到「已修改」却什么都没变，只会以为是自己屏幕不支持
     */
    override fun setForcedDisplaySize(width: Int, height: Int): Boolean {
        Ln.i("$TAG: setForcedDisplaySize(${width}x$height)")
        return runCatching { ScreenManager.setForcedDisplaySize(width, height) }
            .onFailure { Ln.e("$TAG: setForcedDisplaySize failed: ${it.message}") }
            .getOrDefault(false)
    }

    override fun clearForcedDisplaySize(): Boolean {
        Ln.i("$TAG: clearForcedDisplaySize")
        return runCatching { ScreenManager.clearForcedDisplaySize() }
            .onFailure { Ln.e("$TAG: clearForcedDisplaySize failed: ${it.message}") }
            .getOrDefault(false)
    }

    // ── 预览 ──

    override fun setMonitorSurface(surface: Surface?) {
        Ln.i("$TAG: setMonitorSurface(${surface != null})")
        NativeBridgeLib.setPreviewSurface(surface)
        synchronized(keepAliveLock) {
            previewKeepAlive = surface != null
            if (previewKeepAlive) backgroundDisplayId()?.let { PowerController.ensureDisplayAwake(it) }
            syncUserActivityKeepAlive()
        }
    }

    override fun setTouchCallback(callback: ITouchEventCallback?) {
        InputControlUtils.setTouchCallback(callback)
    }

    override fun setTextInputSink(sink: ITextInputSink?) {
        TextInputDispatcher.sink = sink
    }

    // ── 预览上的手动操作；主屏模式下不接管输入 ──

    override fun touchDown(x: Int, y: Int, contact: Int) =
        withVirtualDisplay { InputControlUtils.down(x, y, contact, it) }

    override fun touchMove(x: Int, y: Int, contact: Int) =
        withVirtualDisplay { InputControlUtils.move(x, y, contact, it) }

    override fun touchUp(x: Int, y: Int, contact: Int) =
        withVirtualDisplay { InputControlUtils.up(x, y, contact, it) }

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
        StaleFrameGuard.blankIfVacant()
        val started = runner.start(runPlanJson)
        if (started) {
            AppWatchdog.startWatching(runner::isRunning)
            StaleFrameGuard.start(runner::isRunning)
            syncUserActivityKeepAlive()
        }
        return started
    }

    override fun stopRun(): Boolean {
        stopDisplayWatchers()
        return runner.stop()
    }

    private fun stopDisplayWatchers() {
        AppWatchdog.stopWatching()
        StaleFrameGuard.stop()
    }

    override fun isRunning(): Boolean = runner.isRunning()

    override fun saveCachedImage(path: String?): Boolean =
        !path.isNullOrBlank() && runner.saveCachedImage(path)

    override fun saveDisplayFrame(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        var bitmap: Bitmap? = null
        return try {
            bitmap = NativeBridgeLib.getFrameBufferBitmap() ?: return false
            val file = File(path)
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (e: Throwable) {
            Ln.w("$TAG: saveDisplayFrame failed: ${e.message}")
            false
        } finally {
            bitmap?.recycle()
        }
    }

    override fun captureFrameJpeg(quality: Int): ByteArray? {
        var frame: Bitmap? = null
        var scaled: Bitmap? = null
        return try {
            frame = NativeBridgeLib.getFrameBufferBitmap() ?: return null
            // 前台模式是原生分辨率，缩到长边 1280 免得撑爆 binder 缓冲
            val scale = JPEG_MAX_EDGE.toFloat() / maxOf(frame.width, frame.height)
            scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(frame, (frame.width * scale).toInt(), (frame.height * scale).toInt(), true)
            } else {
                frame
            }
            ByteArrayOutputStream().use { out ->
                if (scaled.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), out)) out.toByteArray() else null
            }
        } catch (e: Throwable) {
            Ln.w("$TAG: captureFrameJpeg failed: ${e.message}")
            null
        } finally {
            if (scaled !== frame) scaled?.recycle()
            frame?.recycle()
        }
    }

    override fun getGameFps(): Float = GameFpsMonitor.currentFps()

    override fun maaVersion(): String? = MaaFrameworkLoader.library?.MaaVersion()

    /**
     * 逐项独立执行：一项失败不影响其余，返回实际授到的位
     * 失败不抛——app 侧据返回值决定要不要再引导用户手点
     */
    override fun grantPermissions(packageName: String?, uid: Int, permissions: Int): Int {
        if (packageName.isNullOrBlank()) return 0
        var granted = 0
        // 结果不进返回位免改 AIDL 返回协议；放开失败由 App 侧预检兜底
        if (permissions and PrivilegedGrant.FGS_SPECIAL_USE != 0) {
            PermissionGrantHelper.grantForegroundServiceSpecialUse(packageName, uid)
        }
        if (permissions and PrivilegedGrant.SCHEDULE != 0) {
            PermissionGrantHelper.grantScheduleOps(packageName, uid)
        }
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

    /**
     * 逐项隔离，不共用一个 runCatching：原先四项串在一个块里，头一项抛了后面全跳过
     *
     * [ScreenManager.destroy] 尤其漏不得——它撤的是**物理主屏**的强改尺寸，
     * 漏掉的话用户会留在一块被改小的屏幕上，而且只能靠再拉一次特权进程才撤得回来。
     * 它自己按 flag 文件判要不要动手，没改过时是空操作
     */
    private fun cleanup() {
        step("game fps") { GameFpsMonitor.stop() }
        step("screen size") { ScreenManager.destroy() }
        step("power") { PowerController.destroy() }
        // 排在拆屏之前：拆屏要排在卡住的显示调用后面等锁，被退出期限掐掉时断网规则还留在 netd 里，得等下次拉起才放开
        step("xmsf") { XmsfFirewall.restoreIfNeeded() }
        step("primary display") { PrimaryDisplayManager.stop() }
        step("virtual display") { VirtualDisplayManager.stop() }
        step("preview") { shutdownPreview() }
    }

    private inline fun step(name: String, action: () -> Unit) {
        runCatching(action).onFailure { Ln.e("$TAG: cleanup $name failed: ${it.message}") }
    }

    /**
     * 退出前断开预览 Surface：SurfaceView 的缓冲队列认不出 producer 进程死了，不断开的话
     * 这块 Surface 会一直算在本进程头上，下一个特权进程 `eglCreateWindowSurface` 报 already connected
     *
     * 排在最后且限时：渲染线程可能正卡在 swap 上，等不到就走，不能拖住前面那几项和进程退出
     */
    private fun shutdownPreview() {
        if (!NativeBridgeLib.LOADED) return
        val worker = Thread { NativeBridgeLib.shutdownPreview() }.apply {
            name = "preview-shutdown"
            isDaemon = true
            start()
        }
        worker.join(PREVIEW_SHUTDOWN_TIMEOUT_MS)
        if (worker.isAlive) Ln.w("$TAG: preview shutdown still running after ${PREVIEW_SHUTDOWN_TIMEOUT_MS}ms, leaving it")
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
        private const val JPEG_MAX_EDGE = 1280
        const val TAG = "RemoteService"
        const val HEARTBEAT_INTERVAL_MS = 5_000L
        const val PREVIEW_SHUTDOWN_TIMEOUT_MS = 1_000L
    }
}
