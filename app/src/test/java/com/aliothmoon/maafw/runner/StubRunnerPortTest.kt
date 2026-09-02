package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.domain.ControllerDefinition
import com.aliothmoon.maafw.domain.ResourceDefinition
import com.aliothmoon.maafw.domain.RunConfigurationId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StubRunnerPortTest {
    @Test
    fun `blank task labels fall back to the task name in progress`() =
        runTest(UnconfinedTestDispatcher()) {
            val runner = StubRunnerPort(
                scope = backgroundScope,
                scenario = StubRunnerScenario(
                    prepareDelayMillis = 0,
                    taskDelayMillis = 0,
                ),
            )
            val events = mutableListOf<RunnerEventEnvelope>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                runner.events.collect { events += it }
            }
            advanceUntilIdle()

            runner.start(PLAN, "execution-1")
            advanceUntilIdle()

            val progress = events.mapNotNull { it.event as? RunnerEvent.Progress }
            assertEquals(listOf("启动游戏"), progress.map { it.taskLabel })
        }

    private companion object {
        val PLAN = RunPlan(
            projectName = "demo",
            projectVersion = "1",
            controller = ControllerDefinition(),
            resource = ResourceDefinition(name = "official", paths = listOf("resource")),
            runConfigurationId = RunConfigurationId("cfg"),
            tasks = listOf(RuntimeTask("启动游戏", "Start", emptyList(), label = "")),
        )
    }
}
