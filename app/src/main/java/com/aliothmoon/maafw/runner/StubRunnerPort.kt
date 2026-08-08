package com.aliothmoon.maafw.runner

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

sealed interface StubTaskOutcome {
    data object Success : StubTaskOutcome
    data class Failure(val message: String) : StubTaskOutcome
}

data class StubRunnerScenario(
    val prepareDelayMillis: Long = 600,
    val taskDelayMillis: Long = 1500,
    val taskOutcomes: Map<String, StubTaskOutcome> = emptyMap(),
    val preparationFailure: String? = null,
    val emitProgress: Boolean = true,
)

/** Stub：不写配置、不调 Builder、不解析 PI */
class StubRunnerPort(
    private val scope: CoroutineScope,
    private val scenario: StubRunnerScenario = StubRunnerScenario(),
) : RunnerPort {

    private val _state = MutableStateFlow(RunnerState())
    override val state: StateFlow<RunnerState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<RunnerEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<RunnerEvent> = _events.asSharedFlow()

    private val commandMutex = Mutex()

    /** stopRequested 绑定单次 executionId，不跨轮次 */
    private var currentContext: ExecutionContext? = null

    private class ExecutionContext(val executionId: String) {
        val stopRequested = AtomicBoolean(false)
    }

    override suspend fun start(plan: RunPlan): RunnerCommandResult = commandMutex.withLock {
        if (_state.value.phase != RunnerPhase.Idle) {
            return RunnerCommandResult.Rejected("Busy")
        }
        val context = ExecutionContext(UUID.randomUUID().toString())
        currentContext = context
        val execution = ActiveExecution(
            executionId = context.executionId,
            runConfigurationId = plan.runConfigurationId,
            currentTaskName = null,
            completedTaskCount = 0,
            totalTaskCount = plan.tasks.size,
            taskResults = emptyList(),
        )
        _state.update { it.copy(phase = RunnerPhase.Preparing, activeExecution = execution) }
        scope.launch { execute(plan, context) }
        RunnerCommandResult.Accepted
    }

    override suspend fun stop(): RunnerCommandResult = commandMutex.withLock {
        val context = currentContext
        val phase = _state.value.phase
        if (context == null || phase == RunnerPhase.Idle || phase is RunnerPhase.Unavailable) {
            return RunnerCommandResult.Rejected("NotRunning")
        }
        context.stopRequested.set(true)
        _state.update { it.copy(phase = RunnerPhase.Stopping) }
        RunnerCommandResult.Accepted
    }

    private suspend fun execute(plan: RunPlan, context: ExecutionContext) {
        _events.tryEmit(RunnerEvent.Log("准备运行环境（${plan.resource.name}）"))
        delay(scenario.prepareDelayMillis)

        scenario.preparationFailure?.let { reason ->
            finish(ExecutionResult.Failed(reason))
            return
        }

        val results = mutableListOf<TaskResult>()
        for ((index, task) in plan.tasks.withIndex()) {
            if (context.stopRequested.get()) break
            _state.update {
                it.copy(
                    phase = RunnerPhase.Running,
                    activeExecution = it.activeExecution?.copy(
                        currentTaskName = task.taskName,
                        completedTaskCount = index,
                        taskResults = results.toList(),
                    ),
                )
            }
            if (scenario.emitProgress) {
                _events.tryEmit(RunnerEvent.Progress(task.taskName, index, plan.tasks.size))
            }
            _events.tryEmit(RunnerEvent.Log("开始任务: ${task.taskName}"))
            delay(scenario.taskDelayMillis)
            if (context.stopRequested.get()) break

            // 单任务失败不终止后续
            val result = when (val outcome = scenario.taskOutcomes[task.taskName] ?: StubTaskOutcome.Success) {
                is StubTaskOutcome.Success -> TaskResult(task.taskName, success = true)
                is StubTaskOutcome.Failure -> TaskResult(task.taskName, success = false, message = outcome.message)
            }
            results += result
            _events.tryEmit(
                RunnerEvent.Log(if (result.success) "任务完成: ${task.taskName}" else "任务失败: ${task.taskName}"),
            )
            _state.update {
                it.copy(
                    activeExecution = it.activeExecution?.copy(
                        completedTaskCount = index + 1,
                        taskResults = results.toList(),
                    ),
                )
            }
        }

        val result = when {
            context.stopRequested.get() -> ExecutionResult.Cancelled(results.toList())
            results.any { !it.success } -> ExecutionResult.CompletedWithFailures(results.toList())
            else -> ExecutionResult.Completed(results.toList())
        }
        finish(result)
    }

    private fun finish(result: ExecutionResult) {
        currentContext = null
        _events.tryEmit(RunnerEvent.Log("本轮执行结束: ${result::class.simpleName}"))
        _state.update { RunnerState(phase = RunnerPhase.Idle, activeExecution = null, latestResult = result) }
    }
}
