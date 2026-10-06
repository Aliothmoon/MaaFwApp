package com.aliothmoon.maafw.runner

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** 只为按需推事件；不模拟执行，start 一律受理，stop 默认受理 */
class RecordingEventRunnerPort : RunnerPort {

    private val _state = MutableStateFlow(RunnerState())
    override val state: StateFlow<RunnerState> = _state.asStateFlow()

    // replay=0 但缓冲足够大：VM 的 collect 在 init 里就挂上了，emit 不会丢
    private val _events = MutableSharedFlow<RunnerEventEnvelope>(
        extraBufferCapacity = 1024,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    override val events: Flow<RunnerEventEnvelope> = _events.asSharedFlow()

    val startedExecutionIds = mutableListOf<String>()

    var stopCount = 0
        private set

    /** 换掉它来模拟停不下来，或在 stop 那一刻取证 */
    var onStop: () -> RunnerCommandResult = { RunnerCommandResult.Accepted }

    fun setState(state: RunnerState) {
        _state.value = state
    }

    fun emit(event: RunnerEvent, executionId: String = DEFAULT_EXECUTION_ID, taskLabel: String? = null) {
        check(_events.tryEmit(RunnerEventEnvelope(executionId, taskLabel, event))) {
            "事件缓冲满了，调大 extraBufferCapacity"
        }
    }

    override suspend fun start(plan: RunPlan, executionId: String): RunnerCommandResult {
        startedExecutionIds += executionId
        return RunnerCommandResult.Accepted
    }

    override suspend fun stop(): RunnerCommandResult {
        stopCount++
        return onStop()
    }

    companion object {
        const val DEFAULT_EXECUTION_ID = "test-execution"
    }
}
