package com.aliothmoon.maafw.runner

/**
 * 运行日志的一条
 *
 * 只在内存，进程重启即清空——与 ExecutionResult 同属执行期产物，不得落盘
 * （docs/persistence-diagnostics.md §2「不持久化」）
 *
 * [text] 与 [detail] **分开存**，不预先拼成一个串：details_json 动辄几百字符，
 * 拼上之后 UI 既没法给事件名单独上色，也没法把 JSON 折叠起来
 */
data class RunLogEntry(
    val id: Long,
    val atMillis: Long,
    val kind: RunLogKind,
    /** 主行：MaaFramework 的事件名，或外壳自产的一句话 */
    val text: String,
    /** 原样 details_json；外壳自产的行没有 */
    val detail: String? = null,
    val outcome: RunLogOutcome = RunLogOutcome.None,
)

/**
 * 日志来源，只用于过滤
 *
 * 着色看 [RunLogOutcome]：同一来源里成功与失败必须一眼分得开，而按来源上色做不到这件事
 */
enum class RunLogKind {
    /** Tasker / Resource / Controller 三类框架级通知 */
    Framework,

    /** Node.* 节点级通知，量最大 */
    Node,

    /** PI 声明的消息模板，唯一写给用户看的一档（见 [FocusMessage]） */
    Focus,

    /** 外壳自己合成的进度行 */
    Progress,

    /** 外壳自产的文字，不是 MaaFramework 的原话 */
    Shell,

    /** 事件名为空等协议异常 */
    Malformed,
}

/** 由事件名后缀推出，不解析 details——那一步在渲染时才按可见行做 */
enum class RunLogOutcome { None, Starting, Succeeded, Failed }

/** 超过就丢最老的：一次长跑能积上万条，全留住会吃光内存也拖慢列表 */
const val RUN_LOG_CAPACITY = 500

/**
 * 「只看关键」留下的那些
 *
 * 滤掉的主体是 Controller 的截图与点击动作——一张图两条、条条带完整 info 块，
 * 排障时有用，扫读时全是噪音
 */
val RunLogEntry.isEssential: Boolean
    get() = when (kind) {
        RunLogKind.Focus, RunLogKind.Malformed, RunLogKind.Shell, RunLogKind.Progress -> true
        RunLogKind.Framework -> outcome == RunLogOutcome.Failed || text.startsWith(TASK_PREFIX)
        RunLogKind.Node -> outcome == RunLogOutcome.Failed
    }

fun RunnerEvent.toLogEntry(id: Long, atMillis: Long): RunLogEntry = when (this) {
    is RunnerEvent.Log -> RunLogEntry(id, atMillis, RunLogKind.Shell, message)

    is RunnerEvent.Progress ->
        RunLogEntry(id, atMillis, RunLogKind.Progress, "$taskName $completed/$total")

    is RunnerEvent.Focus -> RunLogEntry(id, atMillis, RunLogKind.Focus, focus.content)

    is RunnerEvent.MalformedCallback ->
        RunLogEntry(id, atMillis, RunLogKind.Malformed, MALFORMED_LABEL, detail = raw)

    is RunnerEvent.Callback -> RunLogEntry(
        id = id,
        atMillis = atMillis,
        kind = if (message.startsWith(NODE_PREFIX)) RunLogKind.Node else RunLogKind.Framework,
        text = message,
        detail = details.takeIf { it.isNotBlank() },
        outcome = outcomeOf(message),
    )
}

/** 屏保那一行只要一句话，带上 details_json 就糊了 */
fun RunnerEvent.toLogText(): String = when (this) {
    is RunnerEvent.Log -> message
    is RunnerEvent.Progress -> "$taskName $completed/$total"
    is RunnerEvent.Focus -> focus.content
    is RunnerEvent.MalformedCallback -> MALFORMED_LABEL
    is RunnerEvent.Callback -> message
}

private fun outcomeOf(message: String): RunLogOutcome = when {
    message.endsWith(".Failed") -> RunLogOutcome.Failed
    message.endsWith(".Succeeded") -> RunLogOutcome.Succeeded
    message.endsWith(".Starting") -> RunLogOutcome.Starting
    else -> RunLogOutcome.None
}

private const val NODE_PREFIX = "Node."
private const val TASK_PREFIX = "Tasker.Task."

/** 事件名为空时拿不到可指称的东西，给个固定标签，原文进 detail */
private const val MALFORMED_LABEL = "<malformed callback>"
