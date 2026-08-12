package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.i18n.UiText
import kotlinx.serialization.Serializable

/**
 * 运行日志的一条
 *
 * 本类型只在内存，进程重启即清空——[text] 带的是资源 id，跨版本会变，落不了盘。
 * 要落盘的那份由 [RunLogRecorder] 渲染成 [RunSessionRecord.Line] 另写
 * （docs/persistence-diagnostics.md §2「诊断产物」）
 *
 * [text] 与 [detail] 分开存：正文是给人读的一句话，原始 details_json 收在折叠区，
 * 拼成一个串就既没法单独上色也没法折叠
 */
data class RunLogEntry(
    val id: Long,
    val atMillis: Long,
    val kind: RunLogKind,
    val text: UiText,
    /** 原样 details_json；外壳自产与已合成的行没有 */
    val detail: String? = null,
)

/**
 * 日志分级，取自桌面端 MXU 的 `LogType`（info / success / warning / error / agent / focus）
 *
 * [Verbose] 是本项目多出来的一档：MXU 把认不出的回调直接丢掉，我们降级留着，
 * 「全部」档可见。排障时对得上官方文档与源码的原文比什么都值钱
 *
 * 按名字进会话日志文件。外壳自产行走 [RunNote]，由 [RunLogRecorder] 映到 Info/Warning/Error
 */
@Serializable
enum class RunLogKind {
    Info,
    Success,
    Warning,
    Error,

    /** agent child 自己 print 的（stdout） */
    Agent,

    /** agent child 的 stderr：它自己的 traceback，也可能是加载器、解释器写的 */
    AgentError,

    /** PI 声明的消息模板，正文按 Markdown 渲染（见 [FocusMessage]） */
    Focus,

    /** 没被合成成人话的原始回调 */
    Verbose,
}

/** 超过就丢最老的：一次长跑能积上万条，全留住会吃光内存也拖慢列表 */
const val RUN_LOG_CAPACITY = 500

/**
 * 「只看关键」留下的：这一轮跑到哪了
 *
 * agent 的两条流都不在内——它和 `Node.*` 原始转储同级，是排障信息。agent 崩了照样看得见，
 * 那会以 `Tasker.Task.Failed` 的形式出现在关键档，再切「全部」看 stderr 上的现场
 */
val RunLogEntry.isEssential: Boolean
    get() = kind !in NON_ESSENTIAL_KINDS

private val NON_ESSENTIAL_KINDS =
    setOf(RunLogKind.Verbose, RunLogKind.Agent, RunLogKind.AgentError)

/** 屏保那一行只要一句话，带上 details_json 就糊了 */
fun RunnerEvent.toLogText(): String = when (this) {
    is RunnerEvent.Log -> message
    is RunnerEvent.Progress -> "$taskName $completed/$total"
    is RunnerEvent.Focus -> focus.content
    is RunnerEvent.AgentOutput -> line
    is RunnerEvent.MalformedCallback -> MALFORMED_LABEL
    is RunnerEvent.Callback -> message
}

/** 事件名为空时拿不到可指称的东西，给个固定标签，原文进 detail */
internal const val MALFORMED_LABEL = "<malformed callback>"
