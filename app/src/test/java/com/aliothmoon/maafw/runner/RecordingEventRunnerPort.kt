package com.aliothmoon.maafw.runner

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** 只为按需推事件；不模拟执行，start/stop 一律受理 */
class RecordingEventRunnerPort : RunnerPort {

    val startedExecutionIds = mutableListOf<String>()

    private val _state = MutableStateFlow(RunnerState())
    override val state: StateFlow<RunnerState> = _state.asStateFlow()

    fun prepare(plan: RunPlan, executionId: String) {
        _state.value = RunnerState(
            phase = RunnerPhase.Preparing,
            latestExecutionId = executionId,
            activeExecution = ActiveExecution(
                executionId = executionId,
                runConfigurationId = plan.runConfigurationId,
                currentTaskName = null,
                completedTaskCount = 0,
                totalTaskCount = plan.tasks.size,
                taskResults = emptyList(),
                taskLabels = plan.taskLabelMap(),
            ),
        )
    }

    // replay=0 但缓冲足够大：VM 的 collect 在 init 里就挂上了，emit 不会丢
    private val _events = MutableSharedFlow<RunnerEventEnvelope>(
        extraBufferCapacity = 1024,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    override val events: Flow<RunnerEventEnvelope> = _events.asSharedFlow()

    fun emit(
        event: RunnerEvent,
        executionId: String = DEFAULT_EXECUTION_ID,
        currentTaskName: String? = null,
        currentTaskLabel: String? = null,
    ) {
        _events.tryEmit(
            RunnerEventEnvelope(
                executionId = executionId,
                currentTaskName = currentTaskName,
                currentTaskLabel = currentTaskLabel,
                event = event,
            ),
        )
    }

    override suspend fun start(plan: RunPlan, executionId: String): RunnerCommandResult {
        startedExecutionIds += executionId
        _state.value = RunnerState(
            phase = RunnerPhase.Running,
            latestExecutionId = executionId,
            activeExecution = ActiveExecution(
                executionId = executionId,
                runConfigurationId = plan.runConfigurationId,
                currentTaskName = null,
                completedTaskCount = 0,
                totalTaskCount = plan.tasks.size,
                taskResults = emptyList(),
                taskLabels = plan.taskLabelMap(),
            ),
        )
        return RunnerCommandResult.Accepted
    }

    override suspend fun stop(): RunnerCommandResult {
        settle(_state.value.latestExecutionId ?: _state.value.activeExecution?.executionId)
        return RunnerCommandResult.Accepted
    }

    fun settle(executionId: String?) {
        _state.value = RunnerState(
            phase = RunnerPhase.Idle,
            latestExecutionId = executionId ?: _state.value.latestExecutionId,
            latestResult = ExecutionResult.Completed(emptyList()),
        )
    }

    companion object {
        const val DEFAULT_EXECUTION_ID = "test-execution"
    }
}
