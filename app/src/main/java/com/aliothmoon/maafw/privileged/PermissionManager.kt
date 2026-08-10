package com.aliothmoon.maafw.privileged

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.aliothmoon.maafw.constant.PrivilegedGrant
import com.aliothmoon.maafw.domain.RunMode
import com.aliothmoon.maafw.service.AccessibilityHelperService
import com.aliothmoon.maafw.domain.RemoteBackend
import com.aliothmoon.maafw.settings.AppSettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 提权授权的唯一入口：状态汇总、发起授权、切后端
 *
 * 保活三件套（通知/电池白名单/后台不受限）在两种运行模式下都要；悬浮窗与无障碍只有前台
 * 主屏模式用得上，按 runMode 决定要不要一起代授——它们是敏感权限，用不上就不该要
 *
 * [RemoteAccessCoordinator] 只汇总两个后端的可用性，不感知持久化；
 * 后端存在哪、什么时候刷新、授权后要不要绑定，都由这一层决定
 */
class PermissionManager(
    context: Context,
    private val appSettings: AppSettingsManager,
) : PermissionGateway, DefaultLifecycleObserver {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _isGranting = MutableStateFlow(false)
    override val isGranting: StateFlow<Boolean> = _isGranting.asStateFlow()

    override val state: StateFlow<RemoteAccessState> = RemoteAccessCoordinator.state

    override val serviceState: StateFlow<PrivilegedServiceState> = RemoteServiceManager.state
        .map { it.toPrivilegedServiceState() }
        .stateIn(scope, SharingStarted.Eagerly, PrivilegedServiceState.Disconnected)

    private val serviceConnected: StateFlow<Boolean> = serviceState
        .map { it == PrivilegedServiceState.Connected }
        .stateIn(scope, SharingStarted.Eagerly, false)

    private val _systemPermissions = MutableStateFlow(readSystemPermissions())
    override val systemPermissions: StateFlow<SystemPermissionState> = _systemPermissions.asStateFlow()

    private val refreshTrigger = MutableStateFlow(0)

    override val readiness: StateFlow<ShizukuReadiness> = combine(
        RemoteAccessCoordinator.state,
        appSettings.skipShizukuCheck,
        appSettings.shizukuLaunchPackage,
        refreshTrigger,
    ) { remoteState, skipCheck, launchPackage, _ ->
        resolveReadiness(remoteState, skipCheck, launchPackage)
    }.stateIn(
        scope = scope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ShizukuReadiness(),
    )

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        // drop(1)：首值是启动时的当前后端，不该被当成"用户切了后端"而去解绑
        scope.launch {
            appSettings.startupBackend.drop(1).distinctUntilChanged().collect {
                // 绑定是按后端建的，换了后端不断开会连着错的特权进程
                RemoteServiceManager.unbind()
                refresh()
            }
        }
        // 授权到手就把特权进程连上，用户不必再手动点一次
        scope.launch {
            RemoteAccessCoordinator.state
                .map { it.isGranted(it.configuredBackend) }
                .distinctUntilChanged()
                .filter { it }
                .collect { RemoteServiceManager.bind() }
        }
        // 特权进程一上线就代授，省掉用户逐个点系统页
        scope.launch {
            serviceConnected.filter { it }.collect { grantViaPrivileged() }
        }
        // 这两项设置决定了要哪些权限。只在连上那一刻代授的话，用户中途改设置就得等
        // 下次特权进程重连才补得上，中间那段时间悬浮窗是加不出来的
        scope.launch {
            combine(appSettings.runMode, appSettings.screenSaverEnabled) { mode, saver -> mode to saver }
                .drop(1)
                .distinctUntilChanged()
                .collect { if (serviceConnected.value) grantViaPrivileged() }
        }
        RemoteServiceManager.initialize(appContext) { appSettings.startupBackend.value }
    }

    /** 从 Shizuku 的授权界面切回来时状态已变，但没有回调会通知我们 */
    override fun onResume(owner: LifecycleOwner) {
        refresh()
    }

    override fun refresh() {
        RemoteAccessCoordinator.refresh()
        _systemPermissions.value = readSystemPermissions()
        refreshTrigger.update { it + 1 }
    }

    /**
     * 用特权身份给自己授权
     *
     * 这是保活权限的主路径，首页那两个手点入口只是特权进程没起来时的兜底。
     * 走 shell/root 身份直接改 AppOps 与 deviceidle 白名单，用户看不到任何系统弹窗
     */
    private suspend fun grantViaPrivileged() {
        // 悬浮窗两处要：前台的控制层，以及后台开着屏保时那层黑屏
        // 无障碍只有前台的音量键唤起要，后台代授等于替用户要用不上的敏感权限
        val foreground = appSettings.runMode.value == RunMode.FOREGROUND
        var requested = PrivilegedGrant.ALL
        if (foreground || appSettings.screenSaverEnabled.value) requested = requested or PrivilegedGrant.OVERLAY
        if (foreground) requested = requested or PrivilegedGrant.ACCESSIBILITY
        val granted = withContext(Dispatchers.IO) {
            runCatching {
                RemoteServiceManager.getInstanceOrNull()?.grantPermissions(
                    appContext.packageName,
                    appContext.applicationInfo.uid,
                    requested,
                )
            }.onFailure { Timber.w(it, "特权代授失败") }.getOrNull()
        }
        Timber.i("特权代授结果 requested=%s granted=%s", requested, granted)
        // 无障碍是异步绑定的，代授返回成功不代表服务已经连上
        if (granted != null && granted and PrivilegedGrant.ACCESSIBILITY != 0) {
            withTimeoutOrNull(ACCESSIBILITY_BIND_TIMEOUT_MS) {
                AccessibilityHelperService.isConnected.first { it }
            } ?: Timber.w("无障碍服务代授后未在超时内连上")
        }
        refresh()
    }

    /** 这两项没有变更回调，只能在 onResume 与手动 refresh 时重读 */
    private fun readSystemPermissions() = SystemPermissionState(
        notification = SystemPermissionRequester.isGranted(appContext, SystemPermission.Notification),
        batteryWhitelist = SystemPermissionRequester.isGranted(
            appContext,
            SystemPermission.BatteryWhitelist,
        ),
    )

    override suspend fun requestRemoteAccess(): Boolean {
        val current = RemoteAccessCoordinator.refresh()
        if (current.isGranted(current.configuredBackend)) return true
        _isGranting.value = true
        return try {
            RemoteAccessCoordinator.request(current.configuredBackend)
        } finally {
            _isGranting.value = false
            refresh()
        }
    }

    /**
     * 手动拉起特权进程
     *
     * 自动路径（授权到手即 bind）覆盖不了两种情况：特权进程崩过一次，或用户在系统里
     * 撤掉又重新给了授权。那时状态卡在 Died/Disconnected，没有这个入口就只能重启 app
     */
    override suspend fun bindService(): ServiceBindResult {
        if (serviceState.value == PrivilegedServiceState.Connected) return ServiceBindResult.AlreadyConnected
        val current = RemoteAccessCoordinator.refresh()
        val backend = current.configuredBackend
        if (!current.isAvailable(backend)) return ServiceBindResult.BackendUnavailable(backend)
        if (!current.isGranted(backend) && !requestRemoteAccess()) {
            return ServiceBindResult.AuthRejected(backend)
        }
        return runCatching { RemoteServiceManager.bind() }
            .fold(
                onSuccess = { ServiceBindResult.Started },
                onFailure = {
                    Timber.e(it, "手动绑定特权进程失败")
                    ServiceBindResult.Failed(it.message.orEmpty())
                },
            )
    }

    override fun unbindService() = RemoteServiceManager.unbind()

    override suspend fun setBackend(backend: RemoteBackend) {
        if (appSettings.startupBackend.value == backend) return
        appSettings.setStartupBackend(backend)
    }

    override suspend fun skipShizukuCheck() {
        appSettings.setSkipShizukuCheck(true)
    }

    fun installShizuku(context: Context): Boolean = ShizukuInstallHelper.installShizuku(context)

    fun openShizuku(context: Context): Boolean =
        ShizukuInstallHelper.openShizuku(context, appSettings.shizukuLaunchPackage.value)

    private suspend fun resolveReadiness(
        remoteState: RemoteAccessState,
        skipCheck: Boolean,
        launchPackage: String,
    ): ShizukuReadiness {
        val stage = when {
            // 已跳过就不再付 checkStatus 那次 IPC 的代价
            skipCheck -> ShizukuReadinessStage.Ready
            remoteState.configuredBackend != RemoteBackend.SHIZUKU -> ShizukuReadinessStage.Ready
            // Sui 在启动时已 init，先报兼容性，别被下面的 shizukuAvailable 抢成 NeedAuth
            ShizukuManager.isSui -> ShizukuReadinessStage.SuiAvailable
            remoteState.shizukuGranted -> ShizukuReadinessStage.Ready
            remoteState.shizukuAvailable -> ShizukuReadinessStage.NeedAuth
            else -> when (
                withContext(Dispatchers.IO) {
                    ShizukuInstallHelper.checkStatus(appContext, launchPackage)
                }
            ) {
                ShizukuInstallHelper.Status.SuiAvailable -> ShizukuReadinessStage.SuiAvailable
                ShizukuInstallHelper.Status.NotRunning -> ShizukuReadinessStage.NotRunning
                ShizukuInstallHelper.Status.Ready -> ShizukuReadinessStage.NeedAuth
                ShizukuInstallHelper.Status.NotInstalled -> ShizukuReadinessStage.NotInstalled
            }
        }
        return ShizukuReadiness(stage = stage, canSwitchToRoot = remoteState.rootAvailable)
    }

    private companion object {
        /** 系统绑定无障碍服务是异步的，3 秒等不到就当没连上，不卡住授权流程 */
        const val ACCESSIBILITY_BIND_TIMEOUT_MS = 3_000L
    }
}

private fun RemoteServiceManager.ServiceState.toPrivilegedServiceState() = when (this) {
    is RemoteServiceManager.ServiceState.Connected -> PrivilegedServiceState.Connected
    RemoteServiceManager.ServiceState.Connecting -> PrivilegedServiceState.Connecting
    RemoteServiceManager.ServiceState.Died -> PrivilegedServiceState.Died
    RemoteServiceManager.ServiceState.Disconnected -> PrivilegedServiceState.Disconnected
    is RemoteServiceManager.ServiceState.Error -> PrivilegedServiceState.Error
}
