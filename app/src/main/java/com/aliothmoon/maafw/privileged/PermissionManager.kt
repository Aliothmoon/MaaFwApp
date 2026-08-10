package com.aliothmoon.maafw.privileged

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.aliothmoon.maafw.domain.RemoteBackend
import com.aliothmoon.maafw.settings.AppSettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 提权授权的唯一入口：状态汇总、发起授权、切后端
 *
 * MaaFwApp 只需要提权后端这一项权限——manifest 里除 INTERNET 之外只声明了 Shizuku 的
 * API_V23。MaaMeow 那套悬浮窗/无障碍/存储/电池白名单/通知对应的是它的前台模式与常驻服务，
 * 本项目只跑后台虚拟屏，没有对应功能就不该要这些权限
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

    override val serviceConnected: StateFlow<Boolean> = RemoteServiceManager.state
        .map { it is RemoteServiceManager.ServiceState.Connected }
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
}
