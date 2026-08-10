package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.config.InMemoryUserConfigurationStore
import com.aliothmoon.maafw.domain.ConfiguredTask
import com.aliothmoon.maafw.domain.ControllerDefinition
import com.aliothmoon.maafw.domain.ProjectDefinition
import com.aliothmoon.maafw.domain.ResourceDefinition
import com.aliothmoon.maafw.domain.RunConfiguration
import com.aliothmoon.maafw.domain.RunConfigurationId
import com.aliothmoon.maafw.domain.TaskDefinition
import com.aliothmoon.maafw.domain.TaskGroupDefinition
import com.aliothmoon.maafw.domain.UserConfiguration
import com.aliothmoon.maafw.project.FakeProjectRepository
import com.aliothmoon.maafw.project.ProjectState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RunLauncherTest {

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

    private fun store(tasks: List<ConfiguredTask> = listOf(ConfiguredTask("启动游戏", instanceId = "t1"))) =
        InMemoryUserConfigurationStore(
            UserConfiguration(
                initialized = true,
                activeResourceName = "官服",
                configurations = listOf(RunConfiguration(RunConfigurationId("c1"), "日常", tasks)),
                activeConfigurationId = RunConfigurationId("c1"),
            ),
        )

    @Test
    fun `keep alive starts once the runner accepts`() = runTest {
        val keepAlive = RecordingRunKeepAlive()
        val runner = StubRunnerPort(
            scope = backgroundScope,
            scenario = StubRunnerScenario(prepareDelayMillis = 0, taskDelayMillis = 0),
        )
        val launcher = RunLauncher(
            FakeProjectRepository(ProjectState.Ready(definition, emptyList())),
            store(),
            runner,
            keepAlive,
        )

        assertEquals(RunLaunchResult.Started, launcher.launch())
        assertEquals(1, keepAlive.startCount)
    }

    /** 保活是执行的一部分：没受理就拉，前台服务会因为读到非 busy 而当场自停 */
    @Test
    fun `keep alive stays untouched when there is nothing to run`() = runTest {
        val keepAlive = RecordingRunKeepAlive()
        val launcher = RunLauncher(
            FakeProjectRepository(ProjectState.Ready(definition, emptyList())),
            store(tasks = listOf(ConfiguredTask("启动游戏", enabled = false, instanceId = "t1"))),
            StubRunnerPort(scope = backgroundScope),
            keepAlive,
        )

        assertEquals(RunLaunchResult.NoExecutableTasks, launcher.launch())
        assertEquals(0, keepAlive.startCount)
    }

    @Test
    fun `project still loading is reported without touching the runner`() = runTest {
        val keepAlive = RecordingRunKeepAlive()
        val launcher = RunLauncher(
            FakeProjectRepository(ProjectState.Loading),
            store(),
            StubRunnerPort(scope = backgroundScope),
            keepAlive,
        )

        assertEquals(RunLaunchResult.ProjectNotReady, launcher.launch())
        assertEquals(0, keepAlive.startCount)
    }

    @Test
    fun `second launch while busy is rejected and does not re-arm keep alive`() = runTest {
        val keepAlive = RecordingRunKeepAlive()
        val runner = StubRunnerPort(
            scope = backgroundScope,
            scenario = StubRunnerScenario(prepareDelayMillis = 60_000, taskDelayMillis = 60_000),
        )
        val launcher = RunLauncher(
            FakeProjectRepository(ProjectState.Ready(definition, emptyList())),
            store(),
            runner,
            keepAlive,
        )

        assertEquals(RunLaunchResult.Started, launcher.launch())
        assertTrue(launcher.launch() is RunLaunchResult.Rejected)
        assertEquals(1, keepAlive.startCount)
    }
}
