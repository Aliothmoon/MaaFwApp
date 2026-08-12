package com.aliothmoon.maafw.privileged

import android.os.IBinder
import android.view.Surface
import com.aliothmoon.maafw.IMaaRunnerCallback
import com.aliothmoon.maafw.ITouchEventCallback
import com.aliothmoon.maafw.RemoteService
import com.aliothmoon.maafw.constant.WakeUnlockResult
import com.aliothmoon.maafw.domain.RemoteBackend
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * AIDL 接口的测试替身
 *
 * 直接实现 `RemoteService` 而不是在它外面再包一层「设备动作」接口：那层是纯转发，
 * 而 [PrivilegedServicePort] 已经是特权进程的缝了，再加一道只是把同一条边界描两遍
 *
 * `asBinder()` 返回 null 是安全的——JVM 单测里没人会拿它去 transact
 */
open class FakePrivilegedService : RemoteService {

    var unlockResult: Int = WakeUnlockResult.OK
    var lockAndSleepResult: Int = WakeUnlockResult.OK
    var screenOn: Boolean = true

    var unlockCalls: MutableList<String> = mutableListOf()
        private set
    var lockAndSleepCount: Int = 0
        private set
    var stopTargetAppCount: Int = 0
        private set

    var runnerCallback: IMaaRunnerCallback? = null
        private set
    var running: Boolean = false
    var setupResult: Boolean = true
    var startRunResult: Boolean = true
    var stopRunCount: Int = 0
        private set

    override fun asBinder(): IBinder? = null

    // ── 本测试关心的 ──

    override fun unlock(credential: String?): Int {
        unlockCalls += credential.orEmpty()
        return unlockResult
    }

    override fun lockAndSleep(): Int {
        lockAndSleepCount++
        return lockAndSleepResult
    }

    override fun stopTargetApp(): Boolean {
        stopTargetAppCount++
        return true
    }

    override fun isScreenOn(): Boolean = screenOn

    // ── 其余：本测试用不到，保持无副作用的零值 ──

    override fun destroy() = Unit
    override fun exit() = Unit
    override fun version(): String = "fake"
    override fun pid(): Int = 0
    override fun heartbeat(appPid: Int) = Unit
    override fun setup(piRoot: String?, logDir: String?, isDebug: Boolean): Boolean = setupResult
    override fun setVirtualDisplayMode(mode: Int): Boolean = true
    override fun setVirtualDisplayResolution(width: Int, height: Int, dpi: Int) = Unit
    override fun startVirtualDisplay(): Int = 1
    override fun stopVirtualDisplay() = Unit
    override fun isAppOnVirtualDisplay(packageName: String?): Boolean = true
    override fun moveAppToVirtualDisplay(packageName: String?): Boolean = true
    override fun setForceFullscreenOnVirtualDisplay(enabled: Boolean) = Unit
    override fun setDisplayPower(on: Boolean) = Unit
    override fun setForcedDisplaySize(width: Int, height: Int): Boolean = true
    override fun clearForcedDisplaySize(): Boolean = true
    override fun setMonitorSurface(surface: Surface?) = Unit
    override fun setTouchCallback(callback: ITouchEventCallback?) = Unit
    override fun touchDown(x: Int, y: Int) = Unit
    override fun touchMove(x: Int, y: Int) = Unit
    override fun touchUp(x: Int, y: Int) = Unit
    override fun grantPermissions(packageName: String?, uid: Int, permissions: Int): Int = permissions
    override fun isPackageInstalled(packageName: String?): Boolean = true
    override fun setRunnerCallback(callback: IMaaRunnerCallback?) {
        runnerCallback = callback
    }
    override fun startRun(runPlanJson: String?): Boolean {
        if (!startRunResult) return false
        running = true
        return true
    }
    override fun stopRun(): Boolean {
        stopRunCount++
        return true
    }
    override fun isRunning(): Boolean = running
    override fun maaVersion(): String = "fake"
    override fun testUnlock(credential: String?): Int = unlockResult
    override fun watchdogState(): Int = 0

    /** 缓存帧要真 controller 才有；测试里没有可落盘的东西 */
    override fun saveCachedImage(path: String?): Boolean = false
}

/** [service] 为 null 即「特权进程没连上」，收尾路径要走这条 */
class FakePrivilegedServicePort(
    var service: RemoteService? = FakePrivilegedService(),
) : PrivilegedServicePort {

    private val _state = MutableStateFlow(PrivilegedServiceState.Connected)
    override val serviceState: StateFlow<PrivilegedServiceState> = _state.asStateFlow()

    fun emit(state: PrivilegedServiceState) {
        _state.value = state
    }

    override val currentBackend: RemoteBackend? = RemoteBackend.SHIZUKU

    override fun bind() = Unit
    override fun unbind() = Unit

    override fun serviceOrNull(): RemoteService? = service

    /** 非 null 时 [useService] 先挂在这上面，测 Preparing 窗口 */
    var holdUseService: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    override suspend fun <R> useService(action: suspend (RemoteService) -> R): R {
        holdUseService?.await()
        return action(checkNotNull(service) { "fake service unavailable" })
    }
}
