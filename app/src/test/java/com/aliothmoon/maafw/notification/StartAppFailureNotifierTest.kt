package com.aliothmoon.maafw.notification

import com.aliothmoon.maafw.domain.RunConfigurationId
import com.aliothmoon.maafw.runner.ActiveExecution
import com.aliothmoon.maafw.runner.RunnerCommandResult
import com.aliothmoon.maafw.runner.RunnerEvent
import com.aliothmoon.maafw.runner.RunnerPort
import com.aliothmoon.maafw.runner.RunnerState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StartAppFailureNotifierTest {

    @Test
    fun `a failed StartApp callback notifies once per run`() = runTest(UnconfinedTestDispatcher()) {
        val runner = StateRunnerPort(
            RunnerState(
                activeExecution = ActiveExecution(
                    executionId = "run-1",
                    runConfigurationId = RunConfigurationId("config"),
                    currentTaskName = "startup",
                    completedTaskCount = 0,
                    totalTaskCount = 1,
                    taskResults = emptyList(),
                    taskLabels = mapOf("startup" to "启动应用"),
                ),
            ),
        )
        val notifications = mutableListOf<String?>()
        val notifier = StartAppFailureNotifier(runner, { taskLabel -> notifications += taskLabel }, backgroundScope)
        notifier.setup()

        runner.emit(RunnerEvent.Callback("Node.Action.Failed", """{"action":"StartApp","name":"startup"}"""))
        runner.emit(RunnerEvent.Callback("Node.Action.Failed", """{"action":"StartApp","name":"startup"}"""))

        assertEquals(listOf("启动应用"), notifications)
    }

    @Test
    fun `other action failures do not notify`() = runTest(UnconfinedTestDispatcher()) {
        val runner = StateRunnerPort(RunnerState())
        val notifications = mutableListOf<String?>()
        val notifier = StartAppFailureNotifier(runner, { taskLabel -> notifications += taskLabel }, backgroundScope)
        notifier.setup()

        runner.emit(RunnerEvent.Callback("Node.Action.Failed", """{"action":"Click","name":"NodeA"}"""))

        assertEquals(emptyList<String?>(), notifications)
    }

    private class StateRunnerPort(initialState: RunnerState) : RunnerPort {
        private val _state = MutableStateFlow(initialState)
        override val state: StateFlow<RunnerState> = _state.asStateFlow()

        private val _events = MutableSharedFlow<RunnerEvent>(extraBufferCapacity = 16)
        override val events: Flow<RunnerEvent> = _events.asSharedFlow()

        fun emit(event: RunnerEvent) {
            check(_events.tryEmit(event))
        }

        override suspend fun start(plan: com.aliothmoon.maafw.runner.RunPlan): RunnerCommandResult =
            RunnerCommandResult.Accepted

        override suspend fun stop(): RunnerCommandResult = RunnerCommandResult.Accepted
    }
}
