package com.aliothmoon.maafw.runner

/**
 * 运行日志的一条
 *
 * 只在内存，进程重启即清空——与 ExecutionResult 同属执行期产物，不得落盘
 * （docs/persistence-diagnostics.md §2「不持久化」）
 */
data class RunLogEntry(
    val id: Long,
    val atMillis: Long,
    val kind: RunLogKind,
    val text: String,
)

/**
 * 只用于着色与过滤；文本一律保持 MaaFramework 原样，不翻译也不清洗
 *
 * [Focus] 是唯一例外：那是 PI 作者写给用户的正文，按 Markdown 渲染而不是等宽转储
 */
enum class RunLogKind {
    Log,
    Progress,
    Observation,
    Unknown,
    Malformed,
    Focus,
}

/** 超过就丢最老的：一次长跑能积上万条，全留住会吃光内存也拖慢列表 */
const val RUN_LOG_CAPACITY = 500

fun RunnerEvent.toLogKind(): RunLogKind = when (this) {
    is RunnerEvent.Log -> RunLogKind.Log
    is RunnerEvent.Progress -> RunLogKind.Progress
    is RunnerEvent.TaskObservation -> RunLogKind.Observation
    is RunnerEvent.Unknown -> RunLogKind.Unknown
    is RunnerEvent.MalformedCallback -> RunLogKind.Malformed
    is RunnerEvent.Focus -> RunLogKind.Focus
}

fun RunnerEvent.toLogText(): String = when (this) {
    is RunnerEvent.Log -> message
    is RunnerEvent.Progress -> "$taskName $completed/$total"
    is RunnerEvent.TaskObservation -> "$taskName $message"
    is RunnerEvent.Unknown -> raw
    is RunnerEvent.MalformedCallback -> raw
    is RunnerEvent.Focus -> focus.content
}
