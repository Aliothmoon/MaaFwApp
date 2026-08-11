package com.aliothmoon.maafw.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aliothmoon.maafw.config.ConfigurationResolver
import com.aliothmoon.maafw.config.UserConfigurationStore
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.domain.ConfiguredTask
import com.aliothmoon.maafw.domain.duplicateTask
import com.aliothmoon.maafw.domain.renameTask
import com.aliothmoon.maafw.domain.RunConfiguration
import com.aliothmoon.maafw.domain.OverlayControlMode
import com.aliothmoon.maafw.domain.RunConfigurationId
import com.aliothmoon.maafw.domain.RunMode
import com.aliothmoon.maafw.domain.UserConfiguration
import com.aliothmoon.maafw.domain.duplicate
import com.aliothmoon.maafw.i18n.LocaleController
import com.aliothmoon.maafw.privileged.PermissionGateway
import com.aliothmoon.maafw.privileged.PrivilegedServiceState
import com.aliothmoon.maafw.privileged.RemoteAccessState
import com.aliothmoon.maafw.privileged.ServiceBindResult
import com.aliothmoon.maafw.privileged.ShizukuReadiness
import com.aliothmoon.maafw.privileged.SystemPermissionState
import com.aliothmoon.maafw.project.PiInstallCoordinator
import com.aliothmoon.maafw.project.ProjectRepository
import com.aliothmoon.maafw.project.ProjectState
import com.aliothmoon.maafw.domain.ResolvedProjectSession
import com.aliothmoon.maafw.runner.FocusChannel
import com.aliothmoon.maafw.runner.FocusDispatcher
import com.aliothmoon.maafw.runner.FocusMessage
import com.aliothmoon.maafw.runner.PreviewPort
import com.aliothmoon.maafw.runner.PreviewTouchMarker
import com.aliothmoon.maafw.runner.RunLogEntry
import com.aliothmoon.maafw.runner.RunLaunchResult
import com.aliothmoon.maafw.runner.RunLauncher
import com.aliothmoon.maafw.runner.RunTrigger
import com.aliothmoon.maafw.runner.RunLogRecorder
import com.aliothmoon.maafw.runner.RunnerCommandResult
import com.aliothmoon.maafw.runner.RunnerPort
import com.aliothmoon.maafw.runner.RunnerState
import com.aliothmoon.maafw.runner.isBusy
import com.aliothmoon.maafw.runner.ResolutionPreference
import com.aliothmoon.maafw.theme.ThemeStyle
import com.aliothmoon.maafw.i18n.uiTextFromProject
import com.aliothmoon.maafw.i18n.uiTextOf
import com.aliothmoon.maafw.settings.AppSettingsGateway
import com.aliothmoon.maafw.MaaDispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** app 设置的一次快照；combine 的元数上限是 5，几项设置得先并成一个 */
private data class SettingsSnapshot(
    val runMode: RunMode,
    val overlayControlMode: OverlayControlMode,
    val screenSaverEnabled: Boolean,
    val resolutionPreference: ResolutionPreference,
    val debugMode: Boolean,
    val themeStyle: ThemeStyle = ThemeStyle.DEFAULT,
    val env: EnvSnapshot = EnvSnapshot(),
)

/** 定时任务解锁那两项；单独一层只为把 combine 的元数压回上限内 */
private data class EnvSnapshot(
    val wakeUnlockEnabled: Boolean = false,
    val wakeCredential: String = "",
)

/** 提权相关几条流的一次快照；只为把外层 combine 的元数压回 4 以内 */
private data class PrivilegedSnapshot(
    val access: RemoteAccessState,
    val granting: Boolean,
    val readiness: ShizukuReadiness,
    val serviceState: PrivilegedServiceState,
    val systemPermissions: SystemPermissionState,
)

class SessionViewModel(
    private val projectRepository: ProjectRepository,
    private val configurationStore: UserConfigurationStore,
    private val runnerPort: RunnerPort,
    private val runLauncher: RunLauncher,
    private val previewPort: PreviewPort,
    private val permissionGateway: PermissionGateway,
    private val appSettings: AppSettingsGateway,
    private val localeController: LocaleController,
    /** 已补完的 focus 模板；补完在进程级做，见 [FocusDispatcher] */
    private val focusDispatcher: FocusDispatcher,
    /** 运行日志的产地；VM 只转发它的流并转达「清空」 */
    private val recorder: RunLogRecorder,
    private val piInstall: PiInstallCoordinator,
) : ViewModel() {

    private val privilegedState: Flow<PrivilegedSnapshot> = combine(
        permissionGateway.state,
        permissionGateway.isGranting,
        permissionGateway.readiness,
        permissionGateway.serviceState,
        permissionGateway.systemPermissions,
    ) { access, granting, readiness, service, system ->
        PrivilegedSnapshot(access, granting, readiness, service, system)
    }

    private val settingsState: Flow<SettingsSnapshot> = combine(
        appSettings.runMode,
        appSettings.overlayControlMode,
        appSettings.screenSaverEnabled,
        appSettings.resolutionPreference,
        appSettings.debugMode,
    ) { runMode, overlayMode, screenSaver, resolution, debug ->
        SettingsSnapshot(runMode, overlayMode, screenSaver, resolution, debug)
    }.combine(appSettings.themeStyle) { snapshot, style ->
        snapshot.copy(themeStyle = style)
    }.combine(
        combine(appSettings.wakeUnlockEnabled, appSettings.wakeCredential, ::EnvSnapshot),
    ) { snapshot, env -> snapshot.copy(env = env) }


    val uiState: StateFlow<SessionUiState> = combine(
        projectRepository.state,
        configurationStore.data,
        runnerPort.state,
        privilegedState,
        settingsState,
    ) { project, config, runner, privileged, settings ->
        buildUiState(project, config, runner, privileged, settings)
    }.flowOn(MaaDispatchers.Default) // resolve 属重计算，不占用主线程
        .combine(permissionGateway.watchdogState) { base, wd -> base.copy(watchdogState = wd) }
        .combine(piInstall.state) { base, install -> base.copy(piInstallState = install) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = SessionUiState(),
        )

    /**
     * 预览上的触点，单独一条流不进 SessionUiState：
     * 一次滑动能连发几十个触点，混进聚合态会让整棵树按触摸频率重组
     */
    val previewMarkers: StateFlow<List<PreviewTouchMarker>> = previewPort.markers

    /**
     * 运行日志，同样单独一条流：一次长跑上千条，混进聚合态会让整棵树按日志频率重组
     *
     * 合成与落盘都在进程级的 [RunLogRecorder] 里，这里只是转发——挂在 VM 上时切语言
     * Activity 一重建日志就没了，定时触发更是压根没有 VM
     */
    val runLog: StateFlow<List<RunLogEntry>> = recorder.runLog

    private val effectChannel = Channel<SessionEffect>(Channel.BUFFERED)
    val effects: Flow<SessionEffect> = effectChannel.receiveAsFlow()

    // Intent 串行消费，保证配置写入不交错
    private val intents = Channel<SessionIntent>(Channel.UNLIMITED)

    init {
        // 解包在前、加载在后；解包没成，reload 只会拿到一句「PI 尚未解包」
        viewModelScope.launch {
            if (piInstall.ensureInstalled()) projectRepository.reload()
        }
        viewModelScope.launch {
            for (intent in intents) handle(intent)
        }
        viewModelScope.launch {
            focusDispatcher.resolved.collect { focus -> dispatchFocus(focus) }
        }
        viewModelScope.launch {
            combine(projectRepository.state, configurationStore.data) { p, c -> p to c }
                .collect { (project, config) ->
                    if (project is ProjectState.Ready && !config.initialized) {
                        configurationStore.update { current ->
                            if (current.initialized) current
                            else ConfigurationResolver.initialize(project.definition, current)
                        }
                    }
                }
        }
    }

    fun onIntent(intent: SessionIntent) {
        intents.trySend(intent)
    }

    /**
     * Toast 那一档
     *
     * Log 档归 [RunLogRecorder]，Notification 档归前台服务——后者在 app 退到后台时也得响，
     * 拿不到 VM。三档各收各的，同一条 focus 因此可能同时走三条路，这是协议本来的语义
     */
    private fun dispatchFocus(focus: FocusMessage) {
        if (FocusChannel.Toast in focus.channels) {
            effectChannel.trySend(SessionEffect.ShowMessage(uiTextFromProject(focus.content)))
        }
    }

    // resolve 只依赖 (project, config)；runner tick 触发 combine 时复用缓存
    private var resolveCacheKey: Pair<ProjectState, UserConfiguration>? = null
    private var resolveCacheValue: ResolvedProjectSession? = null

    private fun resolveCached(project: ProjectState.Ready, config: UserConfiguration): ResolvedProjectSession {
        val cached = resolveCacheValue
        val key = resolveCacheKey
        if (cached != null && key != null && key.first === project && key.second === config) return cached
        return ConfigurationResolver.resolve(project.definition, config).also {
            resolveCacheKey = project to config
            resolveCacheValue = it
        }
    }

    private fun buildUiState(
        project: ProjectState,
        config: UserConfiguration,
        runner: RunnerState,
        privileged: PrivilegedSnapshot,
        settings: SettingsSnapshot,
    ): SessionUiState {
        val runMode = settings.runMode
        val base = SessionUiState(
            projectState = project,
            runner = runner,
            themeMode = config.themeMode,
            debugMode = settings.debugMode,
            themeStyle = settings.themeStyle,
            runMode = runMode,
            overlayControlMode = settings.overlayControlMode,
            screenSaverEnabled = settings.screenSaverEnabled,
            wakeUnlockEnabled = settings.env.wakeUnlockEnabled,
            wakeCredential = settings.env.wakeCredential,
            resolutionPreference = settings.resolutionPreference,
            remoteAccess = privileged.access,
            remoteAccessGranting = privileged.granting,
            shizukuReadiness = privileged.readiness,
            privilegedService = privileged.serviceState,
            systemPermissions = privileged.systemPermissions,
        )
        if (project !is ProjectState.Ready) return base
        val session = resolveCached(project, config)
        return base.copy(
            configurationList = session.configurationList,
            activeConfiguration = session.activeConfiguration,
            taskCatalog = session.taskCatalog,
            environment = session.environment,
            sessionDiagnostics = session.diagnostics,
            previewResolution = settings.resolutionPreference.resolution,
        )
    }

    private suspend fun handle(intent: SessionIntent) {
        when (intent) {
            is SessionIntent.CreateConfiguration -> guarded {
                appendAndActivate(RunConfiguration(ConfigurationResolver.newConfigurationId(), intent.name))
            }

            is SessionIntent.CreateFromTemplate -> guarded {
                val definition = (projectRepository.state.value as? ProjectState.Ready)?.definition
                val created = definition?.let {
                    ConfigurationResolver.createFromTemplate(
                        definition = it,
                        templateName = intent.templateName,
                        configurationName = intent.configurationName,
                        taskNames = intent.taskNames,
                    )
                }
                if (created == null) {
                    effectChannel.send(SessionEffect.ShowMessage(uiTextOf(R.string.msg_template_not_found, intent.templateName)))
                } else {
                    appendAndActivate(created)
                }
            }

            is SessionIntent.SelectConfiguration -> guarded {
                configurationStore.update { config ->
                    if (config.configurations.any { it.id == intent.id }) {
                        config.copy(activeConfigurationId = intent.id)
                    } else {
                        config
                    }
                }
            }

            is SessionIntent.DuplicateConfiguration -> guarded {
                configurationStore.update { config ->
                    val source = config.configuration(intent.id) ?: return@update config
                    val duplicated = source.duplicate(
                        id = ConfigurationResolver.newConfigurationId(),
                        name = intent.name,
                    )
                    config.copy(
                        configurations = config.configurations + duplicated,
                        activeConfigurationId = duplicated.id,
                    )
                }
            }

            is SessionIntent.RenameConfiguration -> guarded {
                mutateConfiguration(intent.id) { it.copy(name = intent.name) }
            }

            is SessionIntent.DeleteConfiguration -> guarded {
                configurationStore.update { config ->
                    val remaining = config.configurations.filterNot { it.id == intent.id }
                    config.copy(
                        configurations = remaining,
                        activeConfigurationId = if (config.activeConfigurationId == intent.id) {
                            remaining.firstOrNull()?.id
                        } else {
                            config.activeConfigurationId
                        },
                    )
                }
            }

            is SessionIntent.ConfirmAddTasks -> guarded {
                mutateConfiguration(intent.configurationId) { configuration ->
                    val added = intent.orderedTaskNames.map { ConfiguredTask(taskName = it) }
                    configuration.copy(tasks = configuration.tasks + added)
                }
            }

            is SessionIntent.RemoveTask -> guarded {
                mutateConfiguration(intent.configurationId) { configuration ->
                    configuration.copy(
                        tasks = configuration.tasks.filterNot { it.instanceId == intent.taskInstanceId },
                    )
                }
            }
            is SessionIntent.DuplicateTask -> guarded {
                mutateConfiguration(intent.configurationId) {
                    it.duplicateTask(intent.taskInstanceId, intent.customLabel)
                }
            }

            is SessionIntent.RenameTask -> guarded {
                mutateConfiguration(intent.configurationId) {
                    it.renameTask(intent.taskInstanceId, intent.customLabel)
                }
            }

            is SessionIntent.ToggleTask -> guarded {
                mutateTask(intent.configurationId, intent.taskInstanceId) { it.copy(enabled = intent.enabled) }
            }

            is SessionIntent.MoveTask -> guarded {
                mutateConfiguration(intent.configurationId) { configuration ->
                    val tasks = configuration.tasks.toMutableList()
                    val index = tasks.indexOfFirst { it.instanceId == intent.taskInstanceId }
                    if (index < 0) return@mutateConfiguration configuration
                    val task = tasks.removeAt(index)
                    tasks.add(intent.targetIndex.coerceIn(0, tasks.size), task)
                    configuration.copy(tasks = tasks)
                }
            }

            is SessionIntent.SetTaskOption -> guarded {
                mutateTask(intent.configurationId, intent.taskInstanceId) { task ->
                    task.copy(optionValues = task.optionValues + (intent.optionName to intent.value))
                }
            }

            is SessionIntent.SelectResource -> guarded {
                configurationStore.update { it.copy(activeResourceName = intent.resourceName) }
            }

            // 展示偏好不锁配置、不改变 Definition/RunPlan
            is SessionIntent.SetThemeMode ->
                configurationStore.update { it.copy(themeMode = intent.mode) }

            // 只有开启才重启：关闭这一档由组合根的观察者停掉 logcat 抓取，
            // 而 setup() 每轮现读 debugMode，下一轮自然是 false，没必要把用户的页面掀掉
            is SessionIntent.SetDebugMode -> {
                appSettings.setDebugMode(intent.enabled)
                if (intent.enabled) effectChannel.send(SessionEffect.RestartApp)
            }

            is SessionIntent.SetThemeStyle ->
                appSettings.setThemeStyle(intent.style)

            // 环境开关不走 guarded：改的是下一轮的事，运行中改不影响本轮
            // （挂载物的条件在 engage 时就冻结了）
            is SessionIntent.SetWakeUnlockEnabled ->
                appSettings.setWakeUnlockEnabled(intent.enabled)

            is SessionIntent.SetWakeCredential ->
                appSettings.setWakeCredential(intent.credential)


            // 运行模式在 prepare 阶段读一次就固定，运行中改会让这轮的屏与下轮的判定对不上
            is SessionIntent.SetRunMode -> guarded {
                appSettings.setRunMode(intent.mode)
            }

            is SessionIntent.SetOverlayControlMode -> guarded {
                appSettings.setOverlayControlMode(intent.mode)
            }

            is SessionIntent.SetResolutionPreference -> guarded {
                appSettings.setResolutionPreference(intent.preference)
            }

            // 不走 guarded：屏保只盖窗口，与运行配置无关，运行中恰恰是它最该开的时候
            is SessionIntent.SetScreenSaverEnabled ->
                appSettings.setScreenSaverEnabled(intent.enabled)

            SessionIntent.ShowOverlay -> effectChannel.send(SessionEffect.ShowOverlay)
            SessionIntent.ShowScreenSaver -> effectChannel.send(SessionEffect.ShowScreenSaver)

            // 语言切换会触发 PI 重载（翻译加载期物化），运行中同样拦截
            is SessionIntent.SetLanguage -> guarded {
                localeController.apply(intent.localeTag)
            }

            SessionIntent.ReloadProject -> guarded {
                projectRepository.reload()
            }

            SessionIntent.ReinstallPi -> guarded {
                if (piInstall.reinstall()) projectRepository.reload()
            }

            SessionIntent.Start -> start()
            SessionIntent.Stop -> stop()

            // 不走 guarded：预览与配置写入无关，运行中反而更需要它
            is SessionIntent.AttachPreviewSurface -> previewPort.attachSurface(intent.surface)
            SessionIntent.DetachPreviewSurface -> previewPort.detachSurface()

            is SessionIntent.PreviewTouch -> when (intent.action) {
                PreviewTouchAction.Down -> previewPort.touchDown(intent.x, intent.y)
                PreviewTouchAction.Move -> previewPort.touchMove(intent.x, intent.y)
                PreviewTouchAction.Up -> previewPort.touchUp(intent.x, intent.y)
            }

            // 提权一律不走 guarded：它不改 UserConfiguration，运行中断了连也得能重授
            SessionIntent.RequestRemoteAccess -> permissionGateway.requestRemoteAccess()
            SessionIntent.TogglePrivilegedService -> togglePrivilegedService()
            SessionIntent.SkipShizukuCheck -> permissionGateway.skipShizukuCheck()
            SessionIntent.InstallShizuku -> effectChannel.send(SessionEffect.InstallShizuku)
            SessionIntent.OpenShizuku -> effectChannel.send(SessionEffect.OpenShizuku)
            is SessionIntent.RequestSystemPermission ->
                effectChannel.send(SessionEffect.RequestSystemPermission(intent.permission))

            SessionIntent.RefreshPermissions -> permissionGateway.refresh()

            SessionIntent.ClearRunLog -> recorder.clear()
        }
    }

    /** Screen 禁用之外的第二层写锁：写入前再读 RunnerState */
    private suspend fun guarded(block: suspend () -> Unit) {
        if (locked()) {
            effectChannel.send(SessionEffect.ShowMessage(uiTextOf(R.string.msg_locked_while_running)))
            return
        }
        block()
    }

    private fun locked(): Boolean = runnerPort.state.value.phase.isBusy

    private suspend fun appendAndActivate(configuration: RunConfiguration) {
        configurationStore.update { config ->
            config.copy(
                configurations = config.configurations + configuration,
                activeConfigurationId = configuration.id,
            )
        }
    }

    private suspend fun mutateConfiguration(
        id: RunConfigurationId,
        transform: (RunConfiguration) -> RunConfiguration,
    ) {
        configurationStore.update { config ->
            config.copy(
                configurations = config.configurations.map {
                    if (it.id == id) transform(it) else it
                },
            )
        }
    }

    private suspend fun mutateTask(
        configurationId: RunConfigurationId,
        taskInstanceId: String,
        transform: (ConfiguredTask) -> ConfiguredTask,
    ) {
        mutateConfiguration(configurationId) { configuration ->
            configuration.copy(
                tasks = configuration.tasks.map {
                    if (it.instanceId == taskInstanceId) transform(it) else it
                },
            )
        }
    }

    /**
     * 断开只是解绑，特权进程本体还在（它有自己的看门狗）
     * 运行中不拦：跑飞了的时候用户就该能一脚踹开这条连接
     */
    private suspend fun togglePrivilegedService() {
        if (permissionGateway.serviceState.value == PrivilegedServiceState.Connected) {
            permissionGateway.unbindService()
            return
        }
        val message = when (val result = permissionGateway.bindService()) {
            ServiceBindResult.Started, ServiceBindResult.AlreadyConnected -> return
            is ServiceBindResult.BackendUnavailable ->
                uiTextOf(R.string.msg_backend_unavailable, result.backend.display)

            is ServiceBindResult.AuthRejected ->
                uiTextOf(R.string.msg_backend_auth_rejected, result.backend.display)

            is ServiceBindResult.Failed -> uiTextOf(R.string.msg_bind_service_failed, result.reason)
        }
        effectChannel.send(SessionEffect.ShowMessage(message))
    }

    /**
     * 发起本身在 [RunLauncher]（进程级，定时触发共用同一条）；这里只把结局翻成 UI 消息
     *
     * 前台模式的拦截已下沉成 [com.aliothmoon.maafw.runner.ForegroundModePrecheck]：
     * 留在这里只压得住任务页一个入口，定时触发那条路绕得过去
     */
    private suspend fun start() {
        when (val result = runLauncher.launch(RunTrigger.Manual)) {
            // 手动发起不传 requestId，这条到不了
            RunLaunchResult.Started, RunLaunchResult.DuplicateRequest -> Unit

            RunLaunchResult.ProjectNotReady ->
                effectChannel.send(SessionEffect.ShowMessage(uiTextOf(R.string.msg_project_not_loaded)))

            RunLaunchResult.NoExecutableTasks ->
                effectChannel.send(SessionEffect.ShowMessage(uiTextOf(R.string.msg_no_executable_tasks)))

            // 首页只跑当前激活的配置，不点名，所以这条到不了这里；定时才会撞上
            RunLaunchResult.ConfigurationMissing ->
                effectChannel.send(
                    SessionEffect.ShowMessage(uiTextOf(R.string.schedule_reason_configuration_missing)),
                )

            is RunLaunchResult.Invalid ->
                effectChannel.send(SessionEffect.ShowDiagnostics(result.diagnostics))

            is RunLaunchResult.Rejected ->
                effectChannel.send(SessionEffect.ShowMessage(uiTextOf(R.string.msg_cannot_start, result.reason)))

            is RunLaunchResult.Blocked ->
                effectChannel.send(SessionEffect.ShowMessage(result.reason))

            // 确认框等第一道会问的检查落地时再补；现在没有检查产生这个分支，
            // 先原样把问题呈出来，不做无人生产的 UI
            is RunLaunchResult.NeedsConfirmation ->
                effectChannel.send(SessionEffect.ShowMessage(result.prompt))
        }
    }

    private suspend fun stop() {
        when (val command = runnerPort.stop()) {
            is RunnerCommandResult.Accepted -> Unit
            is RunnerCommandResult.Rejected ->
                effectChannel.send(SessionEffect.ShowMessage(uiTextOf(R.string.msg_cannot_stop, command.reason)))
        }
    }
}
