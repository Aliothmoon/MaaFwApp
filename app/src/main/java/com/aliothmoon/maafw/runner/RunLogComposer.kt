package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.i18n.UiText
import com.aliothmoon.maafw.i18n.uiTextFromFramework
import com.aliothmoon.maafw.i18n.uiTextFromProject
import com.aliothmoon.maafw.i18n.uiTextOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 把 MaaFramework 的原始回调合成为人能读的一行
 *
 * 分工照搬桌面端 MXU 的 `useMaaCallbackLogger`：Runner 只把回调原样投出来，
 * 认得出「哪个任务」「哪个资源」的是读得到 PI 与用户配置的这一层。放这儿而不是 Runner 里，
 * 是因为 Runner 按分层不解析 ProjectInterface
 *
 * 有状态：资源多路径加载与 agent 洪泛都要靠前后文去重，所以按轮持有，
 * 每轮开始前调 [reset]
 */
class RunLogComposer {

    private var lastKind: RunLogKind? = null
    private var lastText: UiText? = null

    /** agent 输出的时间戳滑窗；超阈值就闭嘴，与 MXU 的 2s/15 条同参数 */
    private val agentTimestamps = ArrayDeque<Long>()
    private var agentFlooded = false

    fun reset() {
        lastKind = null
        lastText = null
        agentTimestamps.clear()
        agentFlooded = false
    }

    /** 返回 null 表示这条不展示（被去重掉，或洪泛期的 agent 输出） */
    fun compose(event: RunnerEvent, id: Long, atMillis: Long, context: RunLogContext): RunLogEntry? {
        val composed = when (event) {
            is RunnerEvent.ExecutionFinished -> return null

            is RunnerEvent.Log -> Composed(RunLogKind.Info, uiTextFromFramework(event.message))

            is RunnerEvent.Progress -> Composed(
                RunLogKind.Info,
                uiTextFromFramework("${event.taskLabel ?: event.taskName} ${event.completed}/${event.total}"),
            )

            // 正文已由调用方补完（$i18n 查表、{image}、文件路径），这里只负责装进条目：
            // 那几步有先后依赖也要 IO，塞进合成器会让它既不纯也不同步
            is RunnerEvent.Focus -> Composed(RunLogKind.Focus, uiTextFromProject(event.focus.content))

            is RunnerEvent.AgentOutput -> agentEntry(event, atMillis) ?: return null

            is RunnerEvent.AgentConnected -> Composed(
                RunLogKind.Success,
                uiTextOf(R.string.run_log_agent_connected, event.label),
            )

            is RunnerEvent.MalformedCallback -> Composed(
                RunLogKind.Error,
                uiTextFromFramework(MALFORMED_LABEL),
                detail = event.raw,
            )

            is RunnerEvent.Callback -> callbackEntry(event, context)
        }

        // 资源多路径逐条发同样的通知，合成后文案一模一样；连着重复只留第一条
        // （MXU 靠 res_id 的 isFirst/isLast 做同一件事）
        if (composed.kind == lastKind && composed.text == lastText) return null
        lastKind = composed.kind
        lastText = composed.text

        return RunLogEntry(id, atMillis, composed.kind, composed.text, composed.detail)
    }

    /**
     * 洪泛滑窗按**行**计，不按事件计
     *
     * 特权进程会把同一瞬间涌出来的若干行攒成一次回调，按事件计的话一批 64 行只算 1，
     * 阈值永远踩不到，抑制器等于关掉了。两条流合起来算：刷屏就是刷屏，不分从哪条管道出来
     */
    private fun agentEntry(event: RunnerEvent.AgentOutput, atMillis: Long): Composed? {
        while (agentTimestamps.isNotEmpty() && atMillis - agentTimestamps.first() >= AGENT_FLOOD_WINDOW_MS) {
            agentTimestamps.removeFirst()
        }
        if (agentFlooded) {
            // 速率降下来才恢复，并且明说恢复了——静悄悄地少显示一段比显示不全更糟
            if (agentTimestamps.size >= AGENT_FLOOD_THRESHOLD) return null
            agentFlooded = false
            return Composed(RunLogKind.Warning, uiTextOf(R.string.run_log_agent_flood_recovered))
        }
        repeat(event.lineCount) { agentTimestamps.addLast(atMillis) }
        if (agentTimestamps.size >= AGENT_FLOOD_THRESHOLD) {
            agentFlooded = true
            return Composed(RunLogKind.Warning, uiTextOf(R.string.run_log_agent_flood))
        }
        val kind = if (event.fromStderr) RunLogKind.AgentError else RunLogKind.Agent
        return Composed(kind, uiTextFromProject(event.line))
    }

    /**
     * 认得出的合成人话，认不出的降级为原始转储
     *
     * MXU 把认不出的直接丢掉；这里留成 [RunLogKind.Verbose]，「全部」档可见——
     * 排障时对得上官方文档与源码的原文比什么都值钱
     */
    private fun callbackEntry(event: RunnerEvent.Callback, context: RunLogContext): Composed {
        // 落到 else 的 Node.* 是识别期最密的一档，它不看 details；compose 单协程，不必上锁
        val details by lazy(LazyThreadSafetyMode.NONE) { parseDetails(event.details) }
        val verbose = Composed(RunLogKind.Verbose, uiTextFromFramework(event.message), event.details)

        return when (event.message) {
            CONTROLLER_STARTING, CONTROLLER_SUCCEEDED, CONTROLLER_FAILED -> {
                // 只讲连接；点击与截图是每帧都来的动作，讲出来就是刷屏
                if (!details.isConnectAction()) return verbose
                when (event.message) {
                    CONTROLLER_STARTING -> Composed(RunLogKind.Info, uiTextOf(R.string.run_log_connecting))
                    CONTROLLER_SUCCEEDED -> Composed(RunLogKind.Success, uiTextOf(R.string.run_log_connected))
                    else -> Composed(RunLogKind.Error, uiTextOf(R.string.run_log_connect_failed), event.details)
                }
            }

            RESOURCE_STARTING -> Composed(
                RunLogKind.Info,
                uiTextOf(R.string.run_log_resource_loading, context.resourceLabel(details)),
            )

            RESOURCE_SUCCEEDED -> Composed(
                RunLogKind.Success,
                uiTextOf(R.string.run_log_resource_loaded, context.resourceLabel(details)),
            )

            RESOURCE_FAILED -> Composed(
                RunLogKind.Error,
                uiTextOf(R.string.run_log_resource_failed, context.resourceLabel(details)),
                event.details,
            )

            TASK_STARTING -> Composed(
                RunLogKind.Info,
                uiTextOf(R.string.run_log_task_starting, context.taskLabel(details)),
            )

            TASK_SUCCEEDED -> Composed(
                RunLogKind.Success,
                uiTextOf(R.string.run_log_task_succeeded, context.taskLabel(details)),
            )

            TASK_FAILED -> Composed(
                RunLogKind.Error,
                uiTextOf(R.string.run_log_task_failed, context.taskLabel(details)),
                event.details,
            )

            else -> verbose
        }
    }

    private fun parseDetails(raw: String): JsonObject? =
        runCatching { LOG_JSON.parseToJsonElement(raw) }.getOrNull() as? JsonObject

    private data class Composed(
        val kind: RunLogKind,
        val text: UiText,
        val detail: String? = null,
    )

    private companion object {
        const val CONTROLLER_STARTING = "Controller.Action.Starting"
        const val CONTROLLER_SUCCEEDED = "Controller.Action.Succeeded"
        const val CONTROLLER_FAILED = "Controller.Action.Failed"
        const val RESOURCE_STARTING = "Resource.Loading.Starting"
        const val RESOURCE_SUCCEEDED = "Resource.Loading.Succeeded"
        const val RESOURCE_FAILED = "Resource.Loading.Failed"
        const val TASK_STARTING = "Tasker.Task.Starting"
        const val TASK_SUCCEEDED = "Tasker.Task.Succeeded"
        const val TASK_FAILED = "Tasker.Task.Failed"

        const val AGENT_FLOOD_WINDOW_MS = 2_000L
        const val AGENT_FLOOD_THRESHOLD = 15

        val LOG_JSON = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}

/** 合成一行人话需要的、Runner 给不出的那些东西 */
data class RunLogContext(
    val currentTaskName: String? = null,
    /** Runner 在事件产生时冻下的展示名；回调缺 entry 且任务名不在本轮映射时兜底 */
    val currentTaskLabel: String? = null,
    /** 内部任务名到展示名；只在回调缺 entry 时兜底 */
    val taskLabels: Map<String, String> = emptyMap(),
    /** Tasker 回调 entry 到展示名；与任务名是两个 key 空间 */
    val entryLabels: Map<String, String> = emptyMap(),
    val resourceLabel: String? = null,
) {
    /**
     * Tasker 回调自带 entry；不能依赖 currentTaskName，因为异步日志消费可能晚于
     * 下一个任务的 onTaskStarted，把旧任务的结果标成新任务。
     */
    fun taskLabel(details: JsonObject?): String =
        details.string("entry")
            ?.let { entryLabels[it]?.takeIf(String::isNotBlank) ?: it }
            ?: currentTaskLabel?.takeIf(String::isNotBlank)
            ?: currentTaskName?.let { taskLabels[it]?.takeIf(String::isNotBlank) ?: it }
            ?: UNKNOWN_SUBJECT

    /** 一轮只加载一个 resource 的若干路径，用它的展示名比逐条报绝对路径有用 */
    fun resourceLabel(details: JsonObject?): String =
        resourceLabel?.takeIf { it.isNotBlank() }
            ?: details.string("path")?.substringAfterLast('/')
            ?: UNKNOWN_SUBJECT
}

internal fun JsonObject?.string(key: String): String? =
    (this?.get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

/** `action` 的取值官方是 `Connect`，宽容收一个小写写法 */
private fun JsonObject?.isConnectAction(): Boolean =
    this.string("action")?.equals("Connect", ignoreCase = true) == true

private const val UNKNOWN_SUBJECT = "?"
