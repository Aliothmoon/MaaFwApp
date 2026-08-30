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

    private val _state = MutableStateFlow(RunnerState())
    override val state: StateFlow<RunnerState> = _state.asStateFlow()

    var acknowledgeModalFocusResult: Boolean = true
    private val _acknowledgedModalFocusIds = mutableListOf<String>()
    val acknowledgedModalFocusIds: List<String> get() = _acknowledgedModalFocusIds
    var stopCount: Int = 0
        private set

    // replay=0 但缓冲足够大：VM 的 collect 在 init 里就挂上了，emit 不会丢
    private val _events = MutableSharedFlow<RunnerEvent>(
        extraBufferCapacity = 1024,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    override val events: Flow<RunnerEvent> = _events.asSharedFlow()

    fun emit(event: RunnerEvent) {
        check(_events.tryEmit(event)) { "事件缓冲满了，调大 extraBufferCapacity" }
    }

    fun emitState(state: RunnerState) {
        _state.value = state
    }

    override suspend fun start(plan: RunPlan): RunnerCommandResult = RunnerCommandResult.Accepted

    override suspend fun stop(): RunnerCommandResult {
        stopCount++
        return RunnerCommandResult.Accepted
    }

    override suspend fun acknowledgeModalFocus(focusId: String): Boolean {
        _acknowledgedModalFocusIds += focusId
        return acknowledgeModalFocusResult
    }
}
