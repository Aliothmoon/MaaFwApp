package com.aliothmoon.maafw.session

import com.aliothmoon.maafw.config.InMemoryUserConfigurationStore
import com.aliothmoon.maafw.domain.ConfiguredTask
import com.aliothmoon.maafw.domain.ControllerDefinition
import com.aliothmoon.maafw.domain.ProjectDefinition
import com.aliothmoon.maafw.domain.ResourceDefinition
import com.aliothmoon.maafw.domain.RunConfiguration
import com.aliothmoon.maafw.domain.RunConfigurationId
import com.aliothmoon.maafw.domain.RunMode
import com.aliothmoon.maafw.domain.TaskDefinition
import com.aliothmoon.maafw.domain.TaskGroupDefinition
import com.aliothmoon.maafw.domain.ThemeMode
import com.aliothmoon.maafw.domain.UserConfiguration
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.i18n.isResource
import com.aliothmoon.maafw.privileged.FakePermissionGateway
import com.aliothmoon.maafw.settings.FakeAppSettingsGateway
import com.aliothmoon.maafw.project.FakeProjectRepository
import com.aliothmoon.maafw.project.ProjectState
import com.aliothmoon.maafw.runner.RUN_LOG_CAPACITY
import com.aliothmoon.maafw.runner.RecordingEventRunnerPort
import com.aliothmoon.maafw.runner.RecordingPreviewPort
import com.aliothmoon.maafw.runner.ForegroundModePrecheck
import com.aliothmoon.maafw.runner.KeepAliveHook
import com.aliothmoon.maafw.runner.RecordingRunKeepAlive
import com.aliothmoon.maafw.runner.ResolutionPreference
import com.aliothmoon.maafw.runner.RunLauncher
import com.aliothmoon.maafw.i18n.UiText
import com.aliothmoon.maafw.runner.FocusChannel
import com.aliothmoon.maafw.runner.FocusContentResolver
import com.aliothmoon.maafw.runner.FocusDispatcher
import com.aliothmoon.maafw.runner.FocusMessage
import com.aliothmoon.maafw.runner.PassthroughFocusContentResolver
import com.aliothmoon.maafw.runner.RecordingFocusContentResolver
import com.aliothmoon.maafw.runner.RunLogKind
import com.aliothmoon.maafw.runner.isEssential
import com.aliothmoon.maafw.runner.RunnerEvent
import com.aliothmoon.maafw.runner.RunnerPhase
import com.aliothmoon.maafw.runner.RunnerPort
import com.aliothmoon.maafw.runner.StubRunnerPort
import com.aliothmoon.maafw.runner.StubRunnerScenario
import com.aliothmoon.maafw.runner.isBusy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import com.aliothmoon.maafw.MaaDispatchers
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionViewModelTest {

    private val mainDispatcher = UnconfinedTestDispatcher()
    private val emptyJson = JsonObject(emptyMap())

    private val definition = ProjectDefinition(
        name = "demo",
        version = "1",
        controller = ControllerDefinition(),
        resources = listOf(ResourceDefinition("官服", listOf("./base"))),
        tasks = listOf(
            TaskDefinition(
                name = "启动游戏",
                entry = "Start",
                label = "启动游戏",
                description = null,
                groups = emptyList(),
                optionNames = emptyList(),
                pipelineOverride = emptyJson,
                controllers = emptyList(),
                resources = emptyList(),
                defaultCheck = true,
            ),
        ),
        groups = listOf(TaskGroupDefinition(name = "ungrouped", isUngrouped = true)),
        options = emptyMap(),
        templates = emptyList(),
        translations = mapOf("tip.canister" to "显影罐不足"),
    )

    @Before
    fun setUp() {
        mockkObject(MaaDispatchers)
        every { MaaDispatchers.Default } returns mainDispatcher
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkObject(MaaDispatchers)
    }

    private fun readyStore(
        configId: RunConfigurationId = RunConfigurationId("c1"),
        tasks: List<ConfiguredTask> = listOf(ConfiguredTask("启动游戏", instanceId = "t1")),
    ) = InMemoryUserConfigurationStore(
        UserConfiguration(
            initialized = true,
            activeResourceName = "官服",
            configurations = listOf(RunConfiguration(configId, "日常", tasks)),
            activeConfigurationId = configId,
        ),
    )

    /**
     * 带上真实的前台模式检查与保活挂载物：VM 侧要验的「前台模式点不动」如今由
     * ForegroundModePrecheck 产生，emptyList 会让那条用例空转通过
     */
    private fun TestScope.launcherFor(
        project: FakeProjectRepository,
        store: InMemoryUserConfigurationStore,
        runner: RunnerPort,
        settings: FakeAppSettingsGateway,
    ) = RunLauncher(
        projectRepository = project,
        configurationStore = store,
        runnerPort = runner,
        prechecks = listOf(ForegroundModePrecheck),
        hooks = listOf(KeepAliveHook(RecordingRunKeepAlive())),
        runMode = { settings.runMode.value },
        scope = backgroundScope,
    )

    private fun TestScope.createVm(
        store: InMemoryUserConfigurationStore = readyStore(),
        project: FakeProjectRepository = FakeProjectRepository(ProjectState.Ready(definition, emptyList())),
        runner: StubRunnerPort = StubRunnerPort(
            scope = kotlinx.coroutines.CoroutineScope(mainDispatcher),
            scenario = StubRunnerScenario(prepareDelayMillis = 0, taskDelayMillis = 0),
        ),
        locale: (String?) -> Unit = {},
        permissions: FakePermissionGateway = FakePermissionGateway(),
        settings: FakeAppSettingsGateway = FakeAppSettingsGateway(),
    ): Triple<SessionViewModel, InMemoryUserConfigurationStore, StubRunnerPort> {
        val vm = SessionViewModel(
            projectRepository = project,
            configurationStore = store,
            runnerPort = runner,
            runLauncher = launcherFor(project, store, runner, settings),
            previewPort = RecordingPreviewPort(),
            permissionGateway = permissions,
            appSettings = settings,
            localeController = locale,
            focusDispatcher = idleFocusDispatcher(),
        )
        return Triple(vm, store, runner)
    }

    /** 只换 RunnerPort 的构造点；createVm 的返回三元组绑死了 StubRunnerPort */
    private fun TestScope.createVmWithRunner(
        runner: RunnerPort,
        focusDispatcher: FocusDispatcher = idleFocusDispatcher(),
    ): SessionViewModel {
        val project = FakeProjectRepository(ProjectState.Ready(definition, emptyList()))
        val store = readyStore()
        val settings = FakeAppSettingsGateway()
        return SessionViewModel(
            projectRepository = project,
            configurationStore = store,
            runnerPort = runner,
            runLauncher = launcherFor(project, store, runner, settings),
            previewPort = RecordingPreviewPort(),
            permissionGateway = FakePermissionGateway(),
            appSettings = settings,
            localeController = {},
            focusDispatcher = focusDispatcher,
        )
    }

    /** 不接任何 RunnerPort 的 dispatcher：focus 的补完另有 FocusDispatcherTest 覆盖 */
    private fun TestScope.idleFocusDispatcher(
        resolver: FocusContentResolver = PassthroughFocusContentResolver,
        runner: RunnerPort = RecordingEventRunnerPort(),
    ) = FocusDispatcher(
        projectRepository = FakeProjectRepository(ProjectState.Ready(definition, emptyList())),
        resolver = resolver,
        runnerPort = runner,
        scope = backgroundScope,
    )

    @Test
    fun `run log keeps only the latest entries and clears on intent`() = runTest(mainDispatcher) {
        val runner = RecordingEventRunnerPort()
        val vm = createVmWithRunner(runner)

        repeat(RUN_LOG_CAPACITY + 20) { index -> runner.emit(RunnerEvent.Log("line $index")) }

        assertEquals(RUN_LOG_CAPACITY, vm.runLog.value.size)
        // 丢的是最老的，最新一条必须还在
        assertEquals(UiText.Verbatim("line ${RUN_LOG_CAPACITY + 19}"), vm.runLog.value.last().text)

        vm.onIntent(SessionIntent.ClearRunLog)
        assertTrue(vm.runLog.value.isEmpty())
    }

    /** 合成规则由 RunLogComposerTest 覆盖，这里只验 ViewModel 确实把事件送进了合成器 */
    @Test
    fun `run log routes events through the composer`() = runTest(mainDispatcher) {
        val runner = RecordingEventRunnerPort()
        val vm = createVmWithRunner(runner)

        runner.emit(RunnerEvent.Callback("Tasker.Task.Succeeded", """{"entry":"启动游戏"}"""))
        runner.emit(RunnerEvent.Callback("Node.Action.Failed", """{"name":"NodeA"}"""))
        runner.emit(RunnerEvent.MalformedCallback("{}"))

        assertEquals(
            listOf(RunLogKind.Success, RunLogKind.Verbose, RunLogKind.Error),
            vm.runLog.value.map { it.kind },
        )
        // 认不出的那条保留原文与 details，「全部」档才有东西可看
        assertEquals(UiText.Verbatim("Node.Action.Failed"), vm.runLog.value[1].text)
        assertEquals("""{"name":"NodeA"}""", vm.runLog.value[1].detail)
    }

    /**
     * 补完之后按渠道分流；补完本身的规则见 FocusDispatcherTest
     *
     * dispatcher 与 VM 共用同一个 RunnerPort，事件才走得通那条补完流
     */
    @Test
    fun `focus with the log channel reaches the run log`() = runTest(mainDispatcher) {
        val runner = RecordingEventRunnerPort()
        val vm = createVmWithRunner(runner, idleFocusDispatcher(runner = runner))

        runner.emit(RunnerEvent.Focus(FocusMessage("显影罐不足", setOf(FocusChannel.Log))))
        advanceUntilIdle()

        assertEquals(RunLogKind.Focus, vm.runLog.value.single().kind)
        assertEquals(UiText.Verbatim("显影罐不足"), vm.runLog.value.single().text)
    }

    /** 只声明 toast 的模板不进日志 */
    @Test
    fun `toast only focus does not reach the log`() = runTest(mainDispatcher) {
        val runner = RecordingEventRunnerPort()
        val vm = createVmWithRunner(runner, idleFocusDispatcher(runner = runner))

        runner.emit(RunnerEvent.Focus(FocusMessage("弹一下", setOf(FocusChannel.Toast))))
        advanceUntilIdle()

        assertTrue(vm.runLog.value.isEmpty())
    }

    /** 「只看关键」留下合成过的，滤掉没被合成的原始回调 */
    @Test
    fun `essential filter drops raw callbacks`() = runTest(mainDispatcher) {
        val runner = RecordingEventRunnerPort()
        val vm = createVmWithRunner(runner)

        runner.emit(RunnerEvent.Callback("Controller.Action.Succeeded", """{"action":"Screencap"}"""))
        runner.emit(RunnerEvent.Callback("Node.Recognition.Failed", """{"name":"NodeB"}"""))
        runner.emit(RunnerEvent.Callback("Tasker.Task.Starting", """{"entry":"启动游戏"}"""))

        // 只剩「任务开始」；截图动作与节点识别失败都是原始回调，节点失败在协议里是正常控制流
        assertEquals(
            listOf(RunLogKind.Info),
            vm.runLog.value.filter { it.isEssential }.map { it.kind },
        )
    }

    @Test
    fun `initialize runs once when project ready and config uninitialized`() = runTest(mainDispatcher) {
        val store = InMemoryUserConfigurationStore(UserConfiguration())
        val project = FakeProjectRepository(ProjectState.Ready(definition, emptyList()))
        createVm(store = store, project = project)
        advanceUntilIdle()
        assertTrue(store.current.initialized)
        assertEquals("官服", store.current.activeResourceName)
    }

    @Test
    fun `create configuration appends and activates`() = runTest(mainDispatcher) {
        val (vm, store, _) = createVm()
        advanceUntilIdle()
        vm.onIntent(SessionIntent.CreateConfiguration("新配置"))
        advanceUntilIdle()
        assertEquals(2, store.current.configurations.size)
        assertEquals("新配置", store.current.configurations.last().name)
        assertEquals(store.current.configurations.last().id, store.current.activeConfigurationId)
    }

    @Test
    fun `busy runner rejects configuration mutation`() = runTest(mainDispatcher) {
        val runner = StubRunnerPort(
            scope = backgroundScope,
            scenario = StubRunnerScenario(prepareDelayMillis = 60_000, taskDelayMillis = 60_000),
        )
        val (vm, store, _) = createVm(runner = runner)
        advanceUntilIdle()

        val effects = mutableListOf<SessionEffect>()
        backgroundScope.launch { vm.effects.collect { effects += it } }

        vm.onIntent(SessionIntent.Start)
        advanceUntilIdle()
        assertTrue(runner.state.value.phase.isBusy)

        val before = store.current
        vm.onIntent(SessionIntent.CreateConfiguration("locked"))
        advanceUntilIdle()

        assertEquals(before, store.current)
        assertTrue(
            effects.any {
                it is SessionEffect.ShowMessage &&
                    it.message.isResource(R.string.msg_locked_while_running)
            },
        )
    }

    @Test
    fun `start with no executable tasks emits message`() = runTest(mainDispatcher) {
        val store = readyStore(tasks = listOf(ConfiguredTask("启动游戏", enabled = false, instanceId = "t1")))
        val (vm, _, _) = createVm(store = store)
        advanceUntilIdle()

        val effects = mutableListOf<SessionEffect>()
        backgroundScope.launch { vm.effects.collect { effects += it } }

        vm.onIntent(SessionIntent.Start)
        advanceUntilIdle()

        assertTrue(
            effects.any {
                it is SessionEffect.ShowMessage &&
                    it.message.isResource(R.string.msg_no_executable_tasks)
            },
        )
        assertEquals(RunnerPhase.Idle, vm.uiState.value.runner.phase)
    }

    /** 前台模式的拦截在 VM 而不是 RunLauncher，只有这条路径能证明它没漏 */
    @Test
    fun `start in foreground mode is blocked before reaching the launcher`() = runTest(mainDispatcher) {
        val settings = FakeAppSettingsGateway().apply { runMode.value = RunMode.FOREGROUND }
        val (vm, _, runner) = createVm(settings = settings)
        advanceUntilIdle()

        val effects = mutableListOf<SessionEffect>()
        backgroundScope.launch { vm.effects.collect { effects += it } }

        vm.onIntent(SessionIntent.Start)
        advanceUntilIdle()

        assertTrue(
            effects.any {
                it is SessionEffect.ShowMessage &&
                    it.message.isResource(R.string.runner_foreground_blocked)
            },
        )
        // 拦在投递之前：runner 连 Preparing 都不该进
        assertEquals(RunnerPhase.Idle, runner.state.value.phase)
    }

    /** 虚拟屏尺寸改由用户选之后，这条是它进 UiState 的唯一通路 */
    @Test
    fun `preview resolution follows the resolution preference`() = runTest(mainDispatcher) {
        val settings = FakeAppSettingsGateway()
        val (vm, _, _) = createVm(settings = settings)
        // stateIn(WhileSubscribed) 需要活跃收集器才会投影
        backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        assertEquals(ResolutionPreference.P720.resolution, vm.uiState.value.previewResolution)

        settings.resolutionPreference.value = ResolutionPreference.P1080
        advanceUntilIdle()

        assertEquals(ResolutionPreference.P1080.resolution, vm.uiState.value.previewResolution)
    }

    @Test
    fun `theme change is allowed while busy`() = runTest(mainDispatcher) {
        val runner = StubRunnerPort(
            scope = backgroundScope,
            scenario = StubRunnerScenario(prepareDelayMillis = 60_000, taskDelayMillis = 60_000),
        )
        val (vm, store, _) = createVm(runner = runner)
        advanceUntilIdle()
        vm.onIntent(SessionIntent.Start)
        advanceUntilIdle()
        assertTrue(runner.state.value.phase.isBusy)

        vm.onIntent(SessionIntent.SetThemeMode(ThemeMode.Dark))
        advanceUntilIdle()
        assertEquals(ThemeMode.Dark, store.current.themeMode)
    }

    @Test
    fun `set language delegates to locale controller when idle`() = runTest(mainDispatcher) {
        var applied: String? = "unset"
        val (vm, _, _) = createVm(locale = { applied = it })
        advanceUntilIdle()
        vm.onIntent(SessionIntent.SetLanguage("en"))
        advanceUntilIdle()
        assertEquals("en", applied)
    }

    @Test
    fun `reload project is blocked while busy`() = runTest(mainDispatcher) {
        val project = FakeProjectRepository(ProjectState.Ready(definition, emptyList()))
        val runner = StubRunnerPort(
            scope = backgroundScope,
            scenario = StubRunnerScenario(prepareDelayMillis = 60_000, taskDelayMillis = 60_000),
        )
        val (vm, _, _) = createVm(project = project, runner = runner)
        advanceUntilIdle()
        val before = project.reloadCount
        assertTrue(before >= 1)

        vm.onIntent(SessionIntent.Start)
        advanceUntilIdle()
        vm.onIntent(SessionIntent.ReloadProject)
        advanceUntilIdle()
        assertEquals(before, project.reloadCount)
    }

    @Test
    fun `canStart false without active configuration`() = runTest(mainDispatcher) {
        val store = InMemoryUserConfigurationStore(
            UserConfiguration(initialized = true, activeResourceName = "官服"),
        )
        val (vm, _, _) = createVm(store = store)
        // stateIn(WhileSubscribed) 需要活跃收集器才会投影
        backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()
        assertFalse(vm.uiState.value.canStart)
        assertTrue(vm.uiState.value.activeConfiguration == null)
    }
}
