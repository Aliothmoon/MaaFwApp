package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.domain.RunConfigurationId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * UI/ViewModel 与 MaaFramework 之间唯一的执行 seam。
 * 隐藏 JNI、native handle、callback 路由与轮询细节。
 */
interface RunnerPort {
    val state: StateFlow<RunnerState>
    val events: Flow<RunnerEvent>

    suspend fun start(plan: RunPlan): RunnerCommandResult
    suspend fun stop(): RunnerCommandResult
}

data class RunnerState(
    val phase: RunnerPhase = RunnerPhase.Idle,
    val activeExecution: ActiveExecution? = null,
    /** 只在内存保留最近一次结果，进程重启后允许清空。 */
    val latestResult: ExecutionResult? = null,
)

sealed interface RunnerPhase {
    data class Unavailable(val reason: String) : RunnerPhase
    data object Idle : RunnerPhase
    data object Preparing : RunnerPhase
    data object Running : RunnerPhase
    data object Stopping : RunnerPhase
}

/**
 * 忙碌 = Preparing/Running/Stopping。配置锁定（UI 禁用 + 写入口二次校验）
 * 与启停按钮态共用这一处判定，新增 phase 时只改这里。
 */
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

/** 旁路观测事件：适合日志与进度展示，不参与关键状态机判定。 */
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
