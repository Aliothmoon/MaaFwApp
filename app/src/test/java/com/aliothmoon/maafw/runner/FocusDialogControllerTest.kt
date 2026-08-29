package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.domain.ControllerDefinition
import com.aliothmoon.maafw.domain.ProjectDefinition
import com.aliothmoon.maafw.domain.ResourceDefinition
import com.aliothmoon.maafw.domain.RunConfigurationId
import com.aliothmoon.maafw.project.FakeProjectRepository
import com.aliothmoon.maafw.project.ProjectState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FocusDialogControllerTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private fun TestScope.controller(
        runner: RecordingEventRunnerPort,
        resolver: FocusContentResolver = PassthroughFocusContentResolver,
    ) =
        FocusDialogController(
            focusDispatcher = FocusDispatcher(
                projectRepository = FakeProjectRepository(ProjectState.Ready(DEFINITION, emptyList())),
                resolver = resolver,
                runnerPort = runner,
                scope = backgroundScope,
            ),
            runnerPort = runner,
            scope = backgroundScope,
        )

    private fun focus(
        content: String,
        channels: Set<FocusChannel>,
        modalId: String? = null,
        executionId: String? = EXECUTION_ID,
    ) = FocusMessage(
        message = "Node.Action.Starting",
        content = content,
        channels = channels,
        trace = false,
        modalId = modalId,
        executionId = executionId,
    )

    private fun RecordingEventRunnerPort.emitFocus(
        content: String,
        channels: Set<FocusChannel>,
        modalId: String? = null,
        executionId: String? = EXECUTION_ID,
    ) = emit(RunnerEvent.Focus(focus(content, channels, modalId, executionId)))

    private fun activeState(
        executionId: String = EXECUTION_ID,
        phase: RunnerPhase = RunnerPhase.Running,
    ) = RunnerState(
        phase = phase,
        activeExecution = ActiveExecution(
            executionId = executionId,
            runConfigurationId = RunConfigurationId("c1"),
            currentTaskName = null,
            completedTaskCount = 0,
            totalTaskCount = 0,
            taskResults = emptyList(),
        ),
    )

    @Test
    fun `dialog and modal requests share one ordered queue`() = runTest(dispatcher) {
        val runner = RecordingEventRunnerPort()
        runner.emitState(activeState())
        val controller = controller(runner)

        runner.emitFocus("blocking", setOf(FocusChannel.Log, FocusChannel.Modal), "modal-1")
        runner.emitFocus("read me", setOf(FocusChannel.Dialog))

        assertEquals(EXECUTION_ID, controller.requests.value.first().executionId)
        assertEquals(
            listOf("blocking" to true, "read me" to false),
            controller.requests.value.map { it.content to it.modal },
        )
    }

    @Test
    fun `modal without a privileged-process handle is not queued as blocking`() = runTest(dispatcher) {
        val runner = RecordingEventRunnerPort()
        runner.emitState(activeState())
        val controller = controller(runner)

        runner.emitFocus("orphan", setOf(FocusChannel.Modal))

        assertTrue(controller.requests.value.isEmpty())
    }

    @Test
    fun `successful acknowledgement removes only the confirmed modal`() = runTest(dispatcher) {
        val runner = RecordingEventRunnerPort()
        runner.emitState(activeState())
        val controller = controller(runner)
        runner.emitFocus("first", setOf(FocusChannel.Modal), "modal-1")
        runner.emitFocus("second", setOf(FocusChannel.Modal), "modal-2")

        assertTrue(controller.confirmModal("modal-1"))

        assertEquals(listOf("modal-2"), controller.requests.value.map { it.id })
        assertEquals(listOf("modal-1"), runner.acknowledgedModalFocusIds)
    }

    @Test
    fun `failed acknowledgement keeps the modal`() = runTest(dispatcher) {
        val runner = RecordingEventRunnerPort().apply { acknowledgeModalFocusResult = false }
        runner.emitState(activeState())
        val controller = controller(runner)
        runner.emitFocus("try again", setOf(FocusChannel.Modal), "modal-1")

        assertTrue(!controller.confirmModal("modal-1"))

        assertEquals(listOf("modal-1"), controller.requests.value.map { it.id })
    }

    @Test
    fun `idle clears modal requests but keeps unread dialogs`() = runTest(dispatcher) {
        val runner = RecordingEventRunnerPort()
        runner.emitState(activeState())
        val controller = controller(runner)
        runner.emitFocus("read me", setOf(FocusChannel.Dialog))
        runner.emitFocus("blocking", setOf(FocusChannel.Modal), "modal-1")

        runner.emitState(RunnerState(phase = RunnerPhase.Idle))

        assertEquals(listOf(false), controller.requests.value.map { it.modal })
    }

    @Test
    fun `stale modal is cleared when idle is conflated into the next execution`() = runTest(dispatcher) {
        val runner = RecordingEventRunnerPort()
        runner.emitState(activeState(executionId = "execution-1"))
        val controller = controller(runner)
        runner.emitFocus("read me", setOf(FocusChannel.Dialog))
        runner.emitFocus("old blocking", setOf(FocusChannel.Modal), "modal-1", "execution-1")

        runner.emitState(RunnerState(phase = RunnerPhase.Idle))
        runner.emitState(activeState(executionId = "execution-2"))

        assertEquals(listOf("read me" to false), controller.requests.value.map { it.content to it.modal })
    }

    @Test
    fun `stopping a modal goes through the runner`() = runTest(dispatcher) {
        val runner = RecordingEventRunnerPort()
        runner.emitState(activeState())
        val controller = controller(runner)
        runner.emitFocus("blocking", setOf(FocusChannel.Modal), "modal-1")

        assertTrue(controller.stopModal("modal-1"))

        assertEquals(1, runner.stopCount)
        assertEquals(listOf("modal-1"), controller.requests.value.map { it.id })
    }

    @Test
    fun `modal completing after idle is ignored`() = runTest(dispatcher) {
        val runner = RecordingEventRunnerPort()
        runner.emitState(activeState())
        val resolver = GatedFocusContentResolver()
        val controller = controller(runner, resolver)

        runner.emitFocus("![]({image})", setOf(FocusChannel.Log, FocusChannel.Modal), "modal-1")
        advanceUntilIdle()
        runner.emitState(RunnerState(phase = RunnerPhase.Idle))
        resolver.release()
        advanceUntilIdle()

        assertTrue(controller.requests.value.isEmpty())
    }

    @Test
    fun `modal from an earlier execution is ignored during the next run`() = runTest(dispatcher) {
        val runner = RecordingEventRunnerPort()
        runner.emitState(activeState(executionId = "execution-2"))
        val controller = controller(runner)

        runner.emitFocus(
            content = "old blocking",
            channels = setOf(FocusChannel.Modal),
            modalId = "modal-1",
            executionId = "execution-1",
        )

        assertTrue(controller.requests.value.isEmpty())
    }

    @Test
    fun `dialog completing after idle remains readable`() = runTest(dispatcher) {
        val runner = RecordingEventRunnerPort()
        runner.emitState(activeState())
        val resolver = GatedFocusContentResolver()
        val controller = controller(runner, resolver)

        runner.emitFocus("![]({image})", setOf(FocusChannel.Dialog))
        advanceUntilIdle()
        runner.emitState(RunnerState(phase = RunnerPhase.Idle))
        resolver.release()
        advanceUntilIdle()

        assertEquals(listOf(false), controller.requests.value.map { it.modal })
    }

    @Test
    fun `actionable modal requires a handle and the current execution`() {
        val state = activeState()

        assertTrue(
            focus("blocking", setOf(FocusChannel.Modal), "modal-1").isActionableModalFor(state),
        )
        assertTrue(
            !focus("blocking", setOf(FocusChannel.Modal), executionId = "execution-2")
                .isActionableModalFor(state),
        )
        assertTrue(
            !focus("blocking", setOf(FocusChannel.Modal)).isActionableModalFor(state),
        )
    }

    private companion object {
        private const val EXECUTION_ID = "execution-1"

        val DEFINITION = ProjectDefinition(
            name = "demo",
            version = "1",
            controller = ControllerDefinition(),
            resources = emptyList(),
            tasks = emptyList(),
            groups = emptyList(),
            options = emptyMap(),
            templates = emptyList(),
        )
    }
}
