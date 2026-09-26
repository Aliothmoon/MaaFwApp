package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.domain.RunConfigurationId
import com.aliothmoon.maafw.i18n.UiText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** 与 MaaFramework 的唯一执行边界；隐藏 JNI / handle / callback */
interface RunnerPort {
    val state: StateFlow<RunnerState>
    val events: Flow<RunnerEventEnvelope>

    /** [executionId] 由编排层先生成：会话日志在 start 之前就要按它开文件 */
    suspend fun start(plan: RunPlan, executionId: String): RunnerCommandResult
    suspend fun stop(): RunnerCommandResult
}

/** 轮次与任务名在收到回调时冻下：日志异步消费，那时 state 可能已进下一个任务甚至下一轮 */
data class RunnerEventEnvelope(
    val executionId: String,
    val taskLabel: String?,
    val event: RunnerEvent,
)

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
    /** 恒等于 [taskResults] 的条数：已结束的才算完成，正在跑的那条不算 */
    val completedTaskCount: Int,
    val totalTaskCount: Int,
    val taskResults: List<TaskResult>,
    /** 本轮冻住的 name → 展示名；缺的回落 [currentTaskName] */
    val taskLabels: Map<String, String> = emptyMap(),
) {
    val currentTaskLabel: String?
        get() = currentTaskName?.let { taskLabels[it]?.takeIf(String::isNotBlank) ?: it }
}

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
    data class Failed(val reason: UiText, override val taskResults: List<TaskResult> = emptyList()) : ExecutionResult
}

/** 旁路观测（日志/进度）；不参与状态机判定 */
sealed interface RunnerEvent {
    /** 一轮的最后一个事件；会话日志等它被消费到再关文件，本身不成行 */
    data object ExecutionFinished : RunnerEvent

    /** 外壳自产的一句话，不是 MaaFramework 的原话 */
    data class Log(val message: String) : RunnerEvent

    data class Progress(val taskName: String, val completed: Int, val total: Int) : RunnerEvent

    /**
     * MaaFramework 的一条原样通知
     *
     * 两段分开带，不在这里拼串：拼上之后消费方既没法按事件名分类，也没法把
     * [details] 单独折叠起来。识别哪一类看 [message] 前缀，不另设事件类型
     */
    data class Callback(val message: String, val details: String) : RunnerEvent

    /** 事件名为空——协议异常，[raw] 是原样详情 */
    data class MalformedCallback(val raw: String) : RunnerEvent

    /**
     * agent child 的一行输出；与 MaaFramework 的通知不是同一回事，单列一档
     *
     * [fromStderr] 的用处是把「agent 自己 print 的」与「加载器、解释器写的」分开——
     * 链接器的 unused DT entry 警告走 stderr，那不是 agent 在说话
     */
    data class AgentOutput(val line: String, val fromStderr: Boolean) : RunnerEvent {
        /** 特权进程按窗口攒批，[line] 可能是用换行连起来的好几行 */
        val lineCount: Int get() = line.count { it == '\n' } + 1
    }

    /**
     * 一个 agent child 已经 MaaAgentClientConnect 成功（或本轮复用了还活着的）
     *
     * 不是 child 自己 print 的，也不是 MaaFramework 回调——编排层的事实，单独一档才能进进度档
     *
     * [exec] 是特权进程真正 exec 的那条路径（来自 `agent-runtime.json`），不是 PI 的 child_exec：
     * 后者按桌面端整条命令行写，设备上根本没跑那个东西，显示出来只会误导排障
     */
    data class AgentConnected(val index: Int, val total: Int, val exec: String) : RunnerEvent {
        val label: String
            get() = exec.substringAfterLast('/').substringAfterLast('\\')
                .ifBlank { "agent[$index]" }
    }

    /** 一条回调命中的 PI focus 模板；旧协议可能同时产出 start/toast 多条 */
    data class Focus(val focus: List<FocusMessage>) : RunnerEvent
}

sealed interface RunnerCommandResult {
    data object Accepted : RunnerCommandResult
    data class Rejected(val reason: UiText) : RunnerCommandResult
}
