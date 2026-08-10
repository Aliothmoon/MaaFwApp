package com.aliothmoon.maafw.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aliothmoon.maafw.config.ConfigurationResolver
import com.aliothmoon.maafw.config.UserConfigurationStore
import com.aliothmoon.maafw.domain.ConfiguredTask
import com.aliothmoon.maafw.domain.RunConfiguration
import com.aliothmoon.maafw.domain.RunConfigurationId
import com.aliothmoon.maafw.domain.UserConfiguration
import com.aliothmoon.maafw.domain.duplicate
import com.aliothmoon.maafw.i18n.LocaleController
import com.aliothmoon.maafw.privileged.PermissionGateway
import com.aliothmoon.maafw.privileged.RemoteAccessState
import com.aliothmoon.maafw.privileged.ShizukuReadiness
import com.aliothmoon.maafw.privileged.SystemPermissionState
import com.aliothmoon.maafw.project.ProjectRepository
import com.aliothmoon.maafw.project.ProjectState
import com.aliothmoon.maafw.domain.ResolvedProjectSession
import com.aliothmoon.maafw.runner.PreviewPort
import com.aliothmoon.maafw.runner.PreviewTouchMarker
import com.aliothmoon.maafw.runner.RunPlanBuilder
import com.aliothmoon.maafw.runner.RunPlanResult
import com.aliothmoon.maafw.runner.RunnerCommandResult
import com.aliothmoon.maafw.runner.RunnerPort
import com.aliothmoon.maafw.runner.RunnerState
import com.aliothmoon.maafw.runner.isBusy
import com.aliothmoon.maafw.runner.resolveDisplayResolution
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 提权相关几条流的一次快照；只为把外层 combine 的元数压回 4 以内 */
private data class PrivilegedSnapshot(
    val access: RemoteAccessState,
    val granting: Boolean,
    val readiness: ShizukuReadiness,
    val serviceConnected: Boolean,
    val systemPermissions: SystemPermissionState,
)

class SessionViewModel(
    private val projectRepository: ProjectRepository,
    private val configurationStore: UserConfigurationStore,
    private val runnerPort: RunnerPort,
    private val previewPort: PreviewPort,
    private val permissionGateway: PermissionGateway,
    private val localeController: LocaleController,
) : ViewModel() {

    private val privilegedState: Flow<PrivilegedSnapshot> = combine(
        permissionGateway.state,
        permissionGateway.isGranting,
        permissionGateway.readiness,
        permissionGateway.serviceConnected,
        permissionGateway.systemPermissions,
    ) { access, granting, readiness, connected, system ->
        PrivilegedSnapshot(access, granting, readiness, connected, system)
    }

    val uiState: StateFlow<SessionUiState> = combine(
        projectRepository.state,
        configurationStore.data,
        runnerPort.state,
        privilegedState,
    ) { project, config, runner, privileged ->
        buildUiState(project, config, runner, privileged)
    }.flowOn(Dispatchers.Default) // resolve 属重计算，不占用主线程
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

    private val effectChannel = Channel<SessionEffect>(Channel.BUFFERED)
    val effects: Flow<SessionEffect> = effectChannel.receiveAsFlow()

    // Intent 串行消费，保证配置写入不交错
    private val intents = Channel<SessionIntent>(Channel.UNLIMITED)

    init {
        viewModelScope.launch { projectRepository.reload() }
        viewModelScope.launch {
            for (intent in intents) handle(intent)
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
    ): SessionUiState {
        val base = SessionUiState(
            projectState = project,
            runner = runner,
            themeMode = config.themeMode,
            developerMode = config.developerMode,
            remoteAccess = privileged.access,
            remoteAccessGranting = privileged.granting,
            shizukuReadiness = privileged.readiness,
            privilegedServiceConnected = privileged.serviceConnected,
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
            previewResolution = resolveDisplayResolution(project.definition.controller),
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
                    effectChannel.send(SessionEffect.ShowMessage(SessionMessage.TemplateNotFound(intent.templateName)))
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

            is SessionIntent.SetDeveloperMode ->
                configurationStore.update { it.copy(developerMode = intent.enabled) }

            // 语言切换会触发 PI 重载（翻译加载期物化），运行中同样拦截
            is SessionIntent.SetLanguage -> guarded {
                localeController.apply(intent.localeTag)
            }

            SessionIntent.ReloadProject -> guarded {
                projectRepository.reload()
            }

            SessionIntent.Start -> start()
            SessionIntent.Stop -> stop()

            // 不走 guarded：预览与配置写入无关，运行中反而更需要它
            is SessionIntent.AttachPreviewSurface -> previewPort.attachSurface(intent.surface)
            SessionIntent.DetachPreviewSurface -> previewPort.detachSurface()

            // 提权一律不走 guarded：它不改 UserConfiguration，运行中断了连也得能重授
            SessionIntent.RequestRemoteAccess -> permissionGateway.requestRemoteAccess()
            is SessionIntent.SetRemoteBackend -> permissionGateway.setBackend(intent.backend)
            SessionIntent.SkipShizukuCheck -> permissionGateway.skipShizukuCheck()
            SessionIntent.InstallShizuku -> effectChannel.send(SessionEffect.InstallShizuku)
            SessionIntent.OpenShizuku -> effectChannel.send(SessionEffect.OpenShizuku)
            is SessionIntent.RequestSystemPermission ->
                effectChannel.send(SessionEffect.RequestSystemPermission(intent.permission))

            SessionIntent.RefreshPermissions -> permissionGateway.refresh()
        }
    }

    /** Screen 禁用之外的第二层写锁：写入前再读 RunnerState */
    private suspend fun guarded(block: suspend () -> Unit) {
        if (locked()) {
            effectChannel.send(SessionEffect.ShowMessage(SessionMessage.ConfigurationLocked))
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

    private suspend fun start() {
        val project = projectRepository.state.value
        if (project !is ProjectState.Ready) {
            effectChannel.send(SessionEffect.ShowMessage(SessionMessage.ProjectNotLoaded))
            return
        }
        val config = configurationStore.data.first()
        when (val result = RunPlanBuilder.build(project.definition, config)) {
            is RunPlanResult.NoExecutableTasks ->
                effectChannel.send(SessionEffect.ShowMessage(SessionMessage.NoExecutableTasks))

            is RunPlanResult.Invalid ->
                effectChannel.send(SessionEffect.ShowDiagnostics(result.diagnostics))

            is RunPlanResult.Success -> {
                when (val command = runnerPort.start(result.plan)) {
                    is RunnerCommandResult.Accepted -> Unit
                    is RunnerCommandResult.Rejected ->
                        effectChannel.send(SessionEffect.ShowMessage(SessionMessage.CannotStart(command.reason)))
                }
            }
        }
    }

    private suspend fun stop() {
        when (val command = runnerPort.stop()) {
            is RunnerCommandResult.Accepted -> Unit
            is RunnerCommandResult.Rejected ->
                effectChannel.send(SessionEffect.ShowMessage(SessionMessage.CannotStop(command.reason)))
        }
    }
}
