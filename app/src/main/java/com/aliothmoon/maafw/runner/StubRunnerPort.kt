package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.i18n.uiTextFromFramework
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

    private val _events = MutableSharedFlow<RunnerEventEnvelope>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<RunnerEventEnvelope> = _events.asSharedFlow()

    private val commandMutex = Mutex()

    /** stopRequested 绑定单次 executionId，不跨轮次 */
    private var currentContext: ExecutionContext? = null

    private class ExecutionContext(val executionId: String) {
        val stopRequested = AtomicBoolean(false)
    }

    override suspend fun start(plan: RunPlan, executionId: String): RunnerCommandResult = commandMutex.withLock {
        if (_state.value.phase != RunnerPhase.Idle) {
            return RunnerCommandResult.Rejected(uiTextFromFramework("Busy"))
        }
        val context = ExecutionContext(executionId)
        currentContext = context
        val execution = ActiveExecution(
            executionId = context.executionId,
            runConfigurationId = plan.runConfigurationId,
            currentTaskName = null,
            currentTaskLabel = null,
            completedTaskCount = 0,
            totalTaskCount = plan.tasks.size,
            taskResults = emptyList(),
            taskLabels = plan.taskLabelMap(),
        )
        _state.update {
            it.copy(
                phase = RunnerPhase.Preparing,
                activeExecution = execution,
                latestExecutionId = context.executionId,
                latestResult = null,
            )
        }
        scope.launch { execute(plan, context) }
        RunnerCommandResult.Accepted
    }

    override suspend fun stop(): RunnerCommandResult = commandMutex.withLock {
        val context = currentContext
        val phase = _state.value.phase
        if (context == null || phase == RunnerPhase.Idle || phase is RunnerPhase.Unavailable) {
            return RunnerCommandResult.Rejected(uiTextFromFramework("NotRunning"))
        }
        context.stopRequested.set(true)
        _state.update { it.copy(phase = RunnerPhase.Stopping) }
        RunnerCommandResult.Accepted
    }

    private suspend fun execute(plan: RunPlan, context: ExecutionContext) {
        emit(context.executionId, RunnerEvent.Log("准备运行环境（${plan.resource.name}）"))
        delay(scenario.prepareDelayMillis)

        scenario.preparationFailure?.let { reason ->
            finish(context, ExecutionResult.Failed(uiTextFromFramework(reason)))
            return
        }

        val results = mutableListOf<TaskResult>()
        for ((index, task) in plan.tasks.withIndex()) {
            if (context.stopRequested.get()) break
            val taskLabel = task.label.takeIf(String::isNotBlank) ?: task.taskName
            _state.update {
                it.copy(
                    phase = RunnerPhase.Running,
                    activeExecution = it.activeExecution?.copy(
                        currentTaskName = task.taskName,
                        currentTaskLabel = taskLabel,
                        completedTaskCount = index,
                        taskResults = results.toList(),
                    ),
                )
            }
            if (scenario.emitProgress) {
                emit(
                    context.executionId,
                    RunnerEvent.Progress(task.taskName, index, plan.tasks.size, taskLabel),
                    task.taskName,
                    taskLabel,
                )
            }
            emit(
                context.executionId,
                RunnerEvent.Log("开始任务: ${task.taskName}"),
                task.taskName,
                taskLabel,
            )
            delay(scenario.taskDelayMillis)
            if (context.stopRequested.get()) break

            // 单任务失败不终止后续
            val result = when (val outcome = scenario.taskOutcomes[task.taskName] ?: StubTaskOutcome.Success) {
                is StubTaskOutcome.Success -> TaskResult(task.taskName, success = true)
                is StubTaskOutcome.Failure -> TaskResult(task.taskName, success = false, message = outcome.message)
            }
            results += result
            emit(
                context.executionId,
                RunnerEvent.Log(if (result.success) "任务完成: ${task.taskName}" else "任务失败: ${task.taskName}"),
                task.taskName,
                taskLabel,
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
        finish(context, result)
    }

    private fun finish(context: ExecutionContext, result: ExecutionResult) {
        currentContext = null
        emit(context.executionId, RunnerEvent.Log("本轮执行结束: ${result::class.simpleName}"))
        emit(context.executionId, RunnerEvent.ExecutionFinished)
        _state.update {
            RunnerState(
                phase = RunnerPhase.Idle,
                activeExecution = null,
                latestExecutionId = context.executionId,
                latestResult = result,
            )
        }
    }

    private fun emit(
        executionId: String,
        event: RunnerEvent,
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
}
