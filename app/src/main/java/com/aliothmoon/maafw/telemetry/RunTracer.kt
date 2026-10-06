package com.aliothmoon.maafw.telemetry

import com.aliothmoon.maafw.maa.MaaMsg
import com.aliothmoon.maafw.runner.ExecutionResult
import com.aliothmoon.maafw.runner.RunPlan
import com.aliothmoon.maafw.runner.RunnerEvent
import io.sentry.ISpan
import com.aliothmoon.maafw.util.long
import com.aliothmoon.maafw.util.parseJsonObject
import com.aliothmoon.maafw.util.string
import io.sentry.SpanStatus
import kotlinx.serialization.json.JsonObject

/**
 * 把 Runner 的事件流翻成 Sentry 的一轮 Transaction、每任务一条 Span、每个上报节点一条 Span
 *
 * 结构逐项对齐 MXU `commands/telemetry.rs`，名字前缀换成外壳自己的：
 * `maafwapp.task_run`（op `maafwapp.run`）/ `maafwapp.task` / `maafwapp.node`
 *
 * **只吃 `RunnerPort.events` 这一条有序流**：任务的开与结、节点回调、整轮终局在里面先后分明。
 * 事务开在首个任务真正开跑时，准备阶段失败不算一轮，与 MXU 在 post_task 前才开一致；
 * 本轮的计划由 [TelemetryHook] 在投递前经 [begin] 交进来
 *
 * 外层任务终态失败时另经 [onTaskFailure] 交出一份 [TaskFailure]：Span 只进 Trace，
 * 不进 Issues，要聚类、分派、告警得靠那条 Error Event
 *
 * 不是线程安全的，调用方负责串行
 */
internal class RunTracer(
    private val startTransaction: (name: String, op: String) -> ISpan,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onTaskFailure: (TaskFailure) -> Unit = {},
) {

    private class TaskTrace(
        val index: Int,
        val name: String,
        val span: ISpan,
        val startedAt: Long,
        val options: Map<String, String>,
    ) {
        var taskId: Long? = null
        var tracedNodes = 0

        /** 直接观测到的失败节点数，不受 [tracedNodes] 的 Span 预算限制 */
        var failedNodes = 0

        /** 第一个失败节点，事件按它分组；最后一个留作终态上下文 */
        var rootFailure: FailureSignal? = null
        var terminalFailure: FailureSignal? = null
    }

    private class RunTrace(
        val executionId: String,
        val transaction: ISpan,
        val plan: RunPlan?,
        /** 事务与本轮每条失败事件共用的 tag */
        val tags: Map<String, String>,
    ) {
        var task: TaskTrace? = null

        /** 各 task_id 当前 pipeline 步骤的起点（节点 id 与时刻），算节点上卡了多久 */
        val lastSteps = mutableMapOf<Long, Pair<Long, Long>>()
    }

    private var run: RunTrace? = null

    /** 投递前登记、首个任务开跑时取走；没跑起来的那轮由 [forget] 清掉 */
    private val plans = mutableMapOf<String, RunPlan>()

    fun begin(executionId: String, plan: RunPlan) {
        plans[executionId] = plan
    }

    fun forget(executionId: String) {
        plans.remove(executionId)
    }

    fun onEvent(executionId: String, event: RunnerEvent) {
        when (event) {
            is RunnerEvent.Progress -> onTaskStarted(executionId, event)
            is RunnerEvent.TaskFinished -> onTaskFinished(executionId, event)
            is RunnerEvent.ExecutionFinished -> onExecutionFinished(executionId, event.result)
            is RunnerEvent.Callback -> onCallback(executionId, event.message, event.details, trace = null)
            is RunnerEvent.Focus -> onCallback(executionId, event.focus.message, event.details, event.focus.trace)
            else -> Unit
        }
    }

    /** 遥测关掉或换了 DSN：客户端已经关了，进行中的这轮直接丢掉，不再往旧客户端上结 */
    fun reset() {
        run = null
    }

    private fun onExecutionFinished(executionId: String, result: ExecutionResult) {
        plans.remove(executionId)
        val trace = run?.takeIf { it.executionId == executionId } ?: return
        finish(trace, runSpanStatus(result))
    }

    private fun onTaskStarted(executionId: String, progress: RunnerEvent.Progress) {
        val trace = run?.takeIf { it.executionId == executionId } ?: startRun(executionId, plans.remove(executionId))
        // 上一个任务的终局丢了，按取消收掉，别让它把节点吞到新任务之前
        trace.task?.let { finishTask(it, SpanStatus.CANCELLED) }

        val runtimeTask = trace.plan?.tasks?.getOrNull(progress.completed)
            ?.takeIf { it.taskName == progress.taskName }
        val options = runtimeTask?.telemetryOptions.orEmpty()
        val span = trace.transaction.startChild(TASK_OP, progress.taskName).apply {
            setData("run_id", trace.executionId)
            setData("task", progress.taskName)
            options.forEach { (key, value) -> setData("option.$key", value) }
        }
        trace.task = TaskTrace(progress.completed, progress.taskName, span, clock(), options)
    }

    private fun onTaskFinished(executionId: String, finished: RunnerEvent.TaskFinished) {
        val trace = run?.takeIf { it.executionId == executionId } ?: return
        val task = trace.task?.takeIf { it.index == finished.index } ?: return
        trace.task = null
        task.taskId?.let(trace.lastSteps::remove)
        finishTask(task, if (finished.success) SpanStatus.OK else SpanStatus.INTERNAL_ERROR)
        if (!finished.success) onTaskFailure(failureOf(trace, task))
    }

    /** 只有终态失败才出事件，被取消或终局丢了的任务不算，与 MXU `on_task_finished` 一致 */
    private fun failureOf(trace: RunTrace, task: TaskTrace) = TaskFailure(
        runId = trace.executionId,
        task = task.name,
        taskId = task.taskId,
        startedAtMs = task.startedAt,
        durationMs = clock() - task.startedAt,
        failedNodes = task.failedNodes,
        root = task.rootFailure,
        // 只有一个失败节点时两者是同一个，不重复写
        terminal = task.terminalFailure.takeIf { it != task.rootFailure },
        options = task.options,
        tags = trace.tags,
        span = task.span,
    )

    /**
     * 节点级回调：按 PI v2.9.1 的 `focus.trace` 决定是否挂成任务 Span 的子 Span
     *
     * [trace] 为 null 是这条回调没有 focus 模板，取协议默认值：只有 `Node.PipelineNode.Failed` 报。
     * 高频回调，先比消息名再解 JSON，与 MXU `on_node_event` 同一套筛法
     */
    private fun onCallback(executionId: String, message: String, details: String, trace: Boolean?) {
        val traced = trace ?: (message == MaaMsg.NODE_PIPELINE_NODE_FAILED)
        val needsParse = message == MaaMsg.TASKER_TASK_STARTING ||
            message == MaaMsg.NODE_PIPELINE_NODE_STARTING ||
            (traced && message.startsWith(NODE_PREFIX))
        if (!needsParse) return

        val run = run?.takeIf { it.executionId == executionId } ?: return
        val task = run.task ?: return
        val detail = parseJsonObject(details) ?: return
        val taskId = detail.long("task_id") ?: return
        val nodeId = detail.long("node_id")

        if (message == MaaMsg.TASKER_TASK_STARTING) {
            // MaaFW 的 task id 是进程内自增计数，跨用户没有可比性，只作 data 供对照用户的 maafw.log
            if (task.taskId == null) {
                task.taskId = taskId
                task.span.setData("task_id", taskId)
            }
            return
        }
        // 步骤起点与是否上报无关：步骤收尾时靠它算出在这个节点上停了多久
        if (message == MaaMsg.NODE_PIPELINE_NODE_STARTING && nodeId != null) {
            run.lastSteps[taskId] = nodeId to clock()
        }
        if (traced) recordNode(run, task, message, taskId, nodeId, detail)
    }

    /**
     * Span 名：`Node.PipelineNode.*` 有 `node_details.name`（已命中并执行）用它，否则用搜 `next` 的
     * 当前节点 `name`；有命中时把后者另写进 `search_node`，失败时据此分出失败在哪一阶段
     *
     * `Context.run_task` 的子 pipeline 会发新 task_id，一律归到当前外层任务：任务串行执行，
     * 同一时刻只有一个，否则「子任务失败但吞掉」的流程只剩汇总节点能上报，看不到根因
     */
    private fun recordNode(
        run: RunTrace,
        task: TaskTrace,
        message: String,
        taskId: Long,
        nodeId: Long?,
        detail: JsonObject,
    ) {
        val searchNode = detail.string("name")
        val hitNode = if (message.startsWith(PIPELINE_NODE_PREFIX)) {
            (detail["node_details"] as? JsonObject)?.string("name")
        } else {
            null
        }
        val node = hitNode ?: searchNode ?: return
        val stage = if (message == MaaMsg.NODE_PIPELINE_NODE_FAILED) {
            if (hitNode != null) "action" else "recognition"
        } else {
            null
        }

        // 只有步骤收尾消息才结算耗时，中途的消息把起点取走，真正的步骤结果就算不出了；节点对不上宁可不写
        val durationMs = if (message == MaaMsg.NODE_PIPELINE_NODE_SUCCEEDED || message == MaaMsg.NODE_PIPELINE_NODE_FAILED) {
            run.lastSteps.remove(taskId)
                ?.takeIf { (stepNodeId, _) -> stepNodeId == nodeId }
                ?.let { (_, startedAt) -> clock() - startedAt }
        } else {
            null
        }

        // 失败摘要不占 Span 预算：Span 满了，根因与终态照样留着
        if (stage != null) {
            val signal = FailureSignal(node, stage, taskId, nodeId, durationMs)
            task.failedNodes++
            if (task.rootFailure == null) task.rootFailure = signal
            task.terminalFailure = signal
        }

        task.tracedNodes++
        if (task.tracedNodes > MAX_TRACED_NODES_PER_TASK) return

        val status = if (message.endsWith(".Failed")) SpanStatus.INTERNAL_ERROR else SpanStatus.OK
        task.span.startChild(NODE_OP, node).apply {
            setData("message", message)
            if (hitNode != null && searchNode != null) setData("search_node", searchNode)
            stage?.let { setData("stage", it) }
            // 冗余任务名：Sentry 的 span 查询不能沿父子关系往上过滤
            setData("task", task.name)
            // 嵌套 run_task 时这是子 pipeline 的 id，与父 Span 上的外层任务不同
            setData("task_id", taskId)
            nodeId?.let { setData("node_id", it) }
            durationMs?.let { setData("duration_ms", it) }
            finish(status)
        }
    }

    private fun startRun(executionId: String, plan: RunPlan?): RunTrace {
        // 上一轮没等到终局（对账收回时丢了事件），按取消收掉，别让它挂到新一轮上
        run?.let { finish(it, SpanStatus.CANCELLED) }

        // controller 既写 data（事件详情可见）又打 tag（可搜索 / 分组）
        val controller = plan?.controller
            ?.let { listOf("controller.name" to it.name, "controller.type" to it.type) }
            .orEmpty()
            .filter { (_, value) -> value.isNotBlank() }
        // 本轮的关联 ID 直接用 executionId，对应 MXU 每轮现生成的 run_id
        val tags = mapOf("run.id" to executionId) + controller

        val transaction = startTransaction(RUN_NAME, RUN_OP).apply {
            setData("run_id", executionId)
            if (plan != null) {
                setData("task_count", plan.tasks.size)
                if (plan.tasks.isNotEmpty()) setData("tasks", plan.tasks.joinToString(",") { it.taskName })
            }
            controller.forEach { (key, value) -> setData(key, value) }
            tags.forEach { (key, value) -> setTag(key, value) }
        }
        return RunTrace(executionId, transaction, plan, tags).also { run = it }
    }

    /** 未收尾的任务（取消时还在跑的那个）一并按取消结掉，与 MXU `finish_run` 一致 */
    private fun finish(trace: RunTrace, status: SpanStatus) {
        trace.task?.let { finishTask(it, SpanStatus.CANCELLED) }
        trace.task = null
        trace.transaction.setData("result", resultLabel(status))
        trace.transaction.finish(status)
        if (run === trace) run = null
    }

    private fun finishTask(task: TaskTrace, status: SpanStatus) {
        task.span.setData("result", resultLabel(status))
        task.span.finish(status)
    }

    /** 整轮结局到 Span 状态，对齐 MXU `finish_run`：取消记 CANCELLED，其余有任务失败即 INTERNAL_ERROR */
    private fun runSpanStatus(result: ExecutionResult): SpanStatus = when (result) {
        is ExecutionResult.Completed -> SpanStatus.OK
        is ExecutionResult.CompletedWithFailures, is ExecutionResult.Failed -> SpanStatus.INTERNAL_ERROR
        is ExecutionResult.Cancelled -> SpanStatus.CANCELLED
    }

    /** Span 上 `result` 的文案，与 MXU `result_label` 一致 */
    private fun resultLabel(status: SpanStatus): String = when (status) {
        SpanStatus.OK -> "success"
        SpanStatus.CANCELLED -> "cancelled"
        else -> "failure"
    }

    companion object {
        const val RUN_NAME = "maafwapp.task_run"
        const val RUN_OP = "maafwapp.run"
        const val TASK_OP = "maafwapp.task"
        const val NODE_OP = "maafwapp.node"

        /** 失败节点与 `trace` 显式开启的节点共用；与 SDK 单个 Transaction 1000 个 Span 的硬上限对齐 */
        const val MAX_TRACED_NODES_PER_TASK = 1000

        private const val NODE_PREFIX = "Node."
        private const val PIPELINE_NODE_PREFIX = "Node.PipelineNode."
    }
}
