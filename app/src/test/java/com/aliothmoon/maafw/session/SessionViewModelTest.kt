package com.aliothmoon.maafw.session

import com.aliothmoon.maafw.config.InMemoryUserConfigurationStore
import com.aliothmoon.maafw.domain.ConfiguredTask
import com.aliothmoon.maafw.domain.ControllerDefinition
import com.aliothmoon.maafw.domain.ProjectDefinition
import com.aliothmoon.maafw.domain.ResourceDefinition
import com.aliothmoon.maafw.domain.RunConfiguration
import com.aliothmoon.maafw.domain.RunConfigurationId
import com.aliothmoon.maafw.domain.TaskDefinition
import com.aliothmoon.maafw.domain.TaskGroupDefinition
import com.aliothmoon.maafw.domain.ThemeMode
import com.aliothmoon.maafw.domain.UserConfiguration
import com.aliothmoon.maafw.project.FakeProjectRepository
import com.aliothmoon.maafw.project.ProjectState
import com.aliothmoon.maafw.runner.RunnerPhase
import com.aliothmoon.maafw.runner.StubRunnerPort
import com.aliothmoon.maafw.runner.StubRunnerScenario
import com.aliothmoon.maafw.runner.isBusy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
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

    private fun createVm(
        store: InMemoryUserConfigurationStore = readyStore(),
        project: FakeProjectRepository = FakeProjectRepository(ProjectState.Ready(definition, emptyList())),
        runner: StubRunnerPort = StubRunnerPort(
            scope = kotlinx.coroutines.CoroutineScope(mainDispatcher),
            scenario = StubRunnerScenario(prepareDelayMillis = 0, taskDelayMillis = 0),
        ),
        locale: (String?) -> Unit = {},
    ): Triple<SessionViewModel, InMemoryUserConfigurationStore, StubRunnerPort> {
        val vm = SessionViewModel(project, store, runner, locale)
        return Triple(vm, store, runner)
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
                it is SessionEffect.ShowMessage && it.message is SessionMessage.ConfigurationLocked
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
                it is SessionEffect.ShowMessage && it.message is SessionMessage.NoExecutableTasks
            },
        )
        assertEquals(RunnerPhase.Idle, vm.uiState.value.runner.phase)
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
