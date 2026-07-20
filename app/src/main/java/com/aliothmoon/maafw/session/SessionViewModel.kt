package com.aliothmoon.maafw.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aliothmoon.maafw.config.ConfigurationResolver
import com.aliothmoon.maafw.config.UserConfigurationStore
import com.aliothmoon.maafw.domain.ConfiguredTask
import com.aliothmoon.maafw.domain.RunConfiguration
import com.aliothmoon.maafw.domain.RunConfigurationId
import com.aliothmoon.maafw.domain.UserConfiguration
import com.aliothmoon.maafw.project.ProjectRepository
import com.aliothmoon.maafw.project.ProjectState
import com.aliothmoon.maafw.domain.ResolvedProjectSession
import com.aliothmoon.maafw.runner.RunPlanBuilder
import com.aliothmoon.maafw.runner.RunPlanResult
import com.aliothmoon.maafw.runner.RunnerCommandResult
import com.aliothmoon.maafw.runner.RunnerPort
import com.aliothmoon.maafw.runner.RunnerState
import com.aliothmoon.maafw.runner.isBusy
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

class SessionViewModel(
    private val projectRepository: ProjectRepository,
    private val configurationStore: UserConfigurationStore,
    private val runnerPort: RunnerPort,
) : ViewModel() {

    val uiState: StateFlow<SessionUiState> = combine(
        projectRepository.state,
        configurationStore.data,
        runnerPort.state,
    ) { project, config, runner ->
        buildUiState(project, config, runner)
    }.flowOn(Dispatchers.Default) // resolve 属重计算，不占用主线程
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = SessionUiState(),
        )

    private val effectChannel = Channel<SessionEffect>(Channel.BUFFERED)
    val effects: Flow<SessionEffect> = effectChannel.receiveAsFlow()

    /** Intent 经单一 Channel 串行消费，保证用户配置串行更新。 */
    private val intents = Channel<SessionIntent>(Channel.UNLIMITED)

    init {
        viewModelScope.launch { projectRepository.reload() }
        viewModelScope.launch {
            for (intent in intents) handle(intent)
        }
        viewModelScope.launch {
            // 首次初始化：项目就绪且未初始化时按 preset 建立初始配置
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

    // resolve 只依赖 (project, config)；runner tick 触发的 combine 直接复用上次结果。
    // combine transform 串行执行，无并发访问。
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
    ): SessionUiState {
        val base = SessionUiState(
            projectState = project,
            runner = runner,
            themeMode = config.themeMode,
            developerMode = config.developerMode,
        )
        if (project !is ProjectState.Ready) return base
        val session = resolveCached(project, config)
        return base.copy(
            configurationList = session.configurationList,
            activeConfiguration = session.activeConfiguration,
            taskCatalog = session.taskCatalog,
            environment = session.environment,
            sessionDiagnostics = session.diagnostics,
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
                    effectChannel.send(SessionEffect.ShowMessage("模板 \"${intent.templateName}\" 不存在"))
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
                    // 允许重复 taskName：每个名称都追加为独立实例
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

            // 纯展示偏好不参与锁定，也不触发 ProjectDefinition 或 RunPlan 改变
            is SessionIntent.SetThemeMode ->
                configurationStore.update { it.copy(themeMode = intent.mode) }

            is SessionIntent.SetDeveloperMode ->
                configurationStore.update { it.copy(developerMode = intent.enabled) }

            SessionIntent.ReloadProject -> guarded {
                projectRepository.reload()
            }

            SessionIntent.Start -> start()
            SessionIntent.Stop -> stop()
        }
    }

    /**
     * ConfigurationMutationGate：写入前读取最新 RunnerState 再次拒绝，
     * 是 Screen 禁用之外的第二层锁（契约要求，非纯 UI）。
     */
    private suspend fun guarded(block: suspend () -> Unit) {
        if (locked()) {
            effectChannel.send(SessionEffect.ShowMessage("运行期间不能修改配置"))
            return
        }
        block()
    }

    private fun locked(): Boolean = runnerPort.state.value.phase.isBusy

    /** 新建配置统一动作：追加到列表末尾并立即设为活动配置。 */
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
            effectChannel.send(SessionEffect.ShowMessage("项目尚未加载完成"))
            return
        }
        val config = configurationStore.data.first()
        when (val result = RunPlanBuilder.build(project.definition, config)) {
            is RunPlanResult.NoExecutableTasks ->
                effectChannel.send(SessionEffect.ShowMessage("没有可用的任务"))

            is RunPlanResult.Invalid ->
                effectChannel.send(SessionEffect.ShowDiagnostics(result.diagnostics))

            is RunPlanResult.Success -> {
                when (val command = runnerPort.start(result.plan)) {
                    is RunnerCommandResult.Accepted -> Unit
                    is RunnerCommandResult.Rejected ->
                        effectChannel.send(SessionEffect.ShowMessage("无法开始：${command.reason}"))
                }
            }
        }
    }

    private suspend fun stop() {
        when (val command = runnerPort.stop()) {
            is RunnerCommandResult.Accepted -> Unit
            is RunnerCommandResult.Rejected ->
                effectChannel.send(SessionEffect.ShowMessage("无法停止：${command.reason}"))
        }
    }
}
