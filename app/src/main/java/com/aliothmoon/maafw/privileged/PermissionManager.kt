package com.aliothmoon.maafw.privileged

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.aliothmoon.maafw.constant.PrivilegedGrant
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 提权授权的唯一入口：状态汇总、发起授权、切后端
 *
 * 代授集合固定为 [PrivilegedGrant.ALL] 全集，不按运行模式挑：用不上的位代授是空操作，
 * 少授反而会在用户切模式时漏掉某一项
 *
 * [RemoteAccessPort] 只汇总两个后端的可用性，不感知持久化；
 * 后端存在哪、什么时候刷新、授权后要不要绑定，都由这一层决定
 *
 * 两个 Port 由构造注入而不是直接点名 object：进程级单例该在 Koin 里装配，
 * 而不是让每个调用点各自去 import 一个全局
 */
class PermissionManager(
    context: Context,
    private val appSettings: AppSettingsManager,
    private val servicePort: PrivilegedServicePort,
    private val accessPort: RemoteAccessPort,
) : PermissionGateway, DefaultLifecycleObserver {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _isGranting = MutableStateFlow(false)
    override val isGranting: StateFlow<Boolean> = _isGranting.asStateFlow()

    override val state: StateFlow<RemoteAccessState> = accessPort.state

    override val serviceState: StateFlow<PrivilegedServiceState> = servicePort.serviceState

    /**
     * 看门狗状态：service 连上时 2s 轮询 RemoteService.watchdogState()，断开回 IDLE。
     * flatMapLatest 随连接态切换——断开即停轮询，避免空转
     *
     * 每轮现取服务面而不是扣住连上那一刻的 binder：binder 死了 serviceOrNull 即 null，
     * 轮询当场收摊，不必等连接态那条流转过来
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    override val watchdogState: StateFlow<WatchdogState> = servicePort.serviceState
        .flatMapLatest { state ->
            if (state != PrivilegedServiceState.Connected) {
                flowOf(WatchdogState.IDLE)
            } else {
                flow {
                    while (true) {
                        val service = servicePort.serviceOrNull() ?: break
                        emit(
                            WatchdogState.fromAidl(
                                runCatching { service.watchdogState() }.getOrDefault(0),
                            ),
                        )
                        delay(2_000)
                    }
                    emit(WatchdogState.IDLE)
                }
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, WatchdogState.IDLE)

    private val serviceConnected: StateFlow<Boolean> = serviceState
        .map { it == PrivilegedServiceState.Connected }
        .stateIn(scope, SharingStarted.Eagerly, false)

    private val _systemPermissions = MutableStateFlow(readSystemPermissions())
    override val systemPermissions: StateFlow<SystemPermissionState> = _systemPermissions.asStateFlow()

    private val refreshTrigger = MutableStateFlow(0)

    override val readiness: StateFlow<ShizukuReadiness> = combine(
        accessPort.state,
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
                servicePort.unbind()
                refresh()
            }
        }
        // 授权到手就把特权进程连上，用户不必再手动点一次
        scope.launch {
            accessPort.state
                .map { it.isGranted(it.configuredBackend) }
                .distinctUntilChanged()
                .filter { it }
                .collect { servicePort.bind() }
        }
        // 特权进程一上线就代授，省掉用户逐个点系统页
        scope.launch {
            serviceConnected.filter { it }.collect { grantViaPrivileged() }
        }
    }

    /** 从 Shizuku 的授权界面切回来时状态已变，但没有回调会通知我们 */
    override fun onResume(owner: LifecycleOwner) {
        refresh()
    }

    override fun refresh() {
        accessPort.refresh()
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
        // 对齐 MaaMeow：特权进程上线即把全集代授一遍，不再按运行模式挑
        val requested = PrivilegedGrant.ALL
        val granted = withContext(Dispatchers.IO) {
            runCatching {
                servicePort.serviceOrNull()?.grantPermissions(
                    appContext.packageName,
                    appContext.applicationInfo.uid,
                    requested,
                )
            }.onFailure { Timber.w(it, "Privileged permission grant failed") }.getOrNull()
        }
        Timber.i("Privileged grant result requested=%s granted=%s", requested, granted)
        // 无障碍是异步绑定的，代授返回成功不代表服务已经连上
        if (granted != null && granted and PrivilegedGrant.ACCESSIBILITY != 0) {
            withTimeoutOrNull(ACCESSIBILITY_BIND_TIMEOUT_MS) {
                AccessibilityHelperService.isConnected.first { it }
            } ?: Timber.w("Accessibility service did not connect within timeout after grant")
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
        overlay = SystemPermissionRequester.isGranted(appContext, SystemPermission.Overlay),
        storage = SystemPermissionRequester.isGranted(appContext, SystemPermission.Storage),
        accessibility = SystemPermissionRequester.isGranted(appContext, SystemPermission.Accessibility),
    )

    override suspend fun requestRemoteAccess(): Boolean {
        val current = accessPort.refresh()
        if (current.isGranted(current.configuredBackend)) return true
        _isGranting.value = true
        return try {
            accessPort.request(current.configuredBackend)
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
        val current = accessPort.refresh()
        val backend = current.configuredBackend
        if (!current.isAvailable(backend)) return ServiceBindResult.BackendUnavailable(backend)
        if (!current.isGranted(backend) && !requestRemoteAccess()) {
            return ServiceBindResult.AuthRejected(backend)
        }
        return runCatching { servicePort.bind() }
            .fold(
                onSuccess = { ServiceBindResult.Started },
                onFailure = {
                    Timber.e(it, "Failed to bind privileged process manually")
                    ServiceBindResult.Failed(it.message.orEmpty())
                },
            )
    }

    override fun unbindService() = servicePort.unbind()

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

