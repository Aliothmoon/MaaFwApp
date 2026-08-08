package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.domain.RunConfigurationId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** 与 MaaFramework 的唯一执行 seam；隐藏 JNI / handle / callback */
interface RunnerPort {
    val state: StateFlow<RunnerState>
    val events: Flow<RunnerEvent>

    suspend fun start(plan: RunPlan): RunnerCommandResult
    suspend fun stop(): RunnerCommandResult
}

data class RunnerState(
    val phase: RunnerPhase = RunnerPhase.Idle,
    val activeExecution: ActiveExecution? = null,
    /** 仅内存；进程重启可清空 */
    val latestResult: ExecutionResult? = null,
)

sealed interface RunnerPhase {
    data class Unavailable(val reason: String) : RunnerPhase
    data object Idle : RunnerPhase
    data object Preparing : RunnerPhase
    data object Running : RunnerPhase
    data object Stopping : RunnerPhase
}

/** 配置锁定与启停态共用；新增 phase 时只改此处 */
val RunnerPhase.isBusy: Boolean
    get() = this == RunnerPhase.Preparing || this == RunnerPhase.Running || this == RunnerPhase.Stopping

data class ActiveExecution(
    val executionId: String,
    val runConfigurationId: RunConfigurationId,
    val currentTaskName: String?,
    val completedTaskCount: Int,
    val totalTaskCount: Int,
    val taskResults: List<TaskResult>,
)

data class TaskResult(
    val taskName: String,
    val success: Boolean,
    val message: String? = null,
)

sealed interface ExecutionResult {
    val taskResults: List<TaskResult>

    data class Completed(override val taskResults: List<TaskResult>) : ExecutionResult
    data class CompletedWithFailures(override val taskResults: List<TaskResult>) : ExecutionResult
    data class Cancelled(override val taskResults: List<TaskResult>) : ExecutionResult
    data class Failed(val reason: String, override val taskResults: List<TaskResult> = emptyList()) : ExecutionResult
}

/** 旁路观测（日志/进度）；不参与状态机判定 */
sealed interface RunnerEvent {
    data class Log(val message: String) : RunnerEvent
    data class Progress(val taskName: String, val completed: Int, val total: Int) : RunnerEvent
    data class TaskObservation(val taskName: String, val message: String) : RunnerEvent
    data class Unknown(val raw: String) : RunnerEvent
    data class MalformedCallback(val raw: String) : RunnerEvent
}

sealed interface RunnerCommandResult {
    data object Accepted : RunnerCommandResult
    data class Rejected(val reason: String) : RunnerCommandResult
}
