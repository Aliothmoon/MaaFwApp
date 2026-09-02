package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.i18n.UiText

/**
 * 本轮运行日志的写口
 *
 * 挂载物与编排只认这一面：不碰 [RunLogRecorder]、不发 [RunnerEvent]、不选 [RunLogKind]。
 * 分级是外壳自己的 Info / Warning / Error，落盘与配色由实现映射。
 * 会话文件的开/关也在这里——观察者只依赖本接口，不依赖落盘实现
 */
interface RunJournal {

    suspend fun begin(plan: RunPlan, executionId: String)

    suspend fun end(executionId: String, reason: RunEndReason)

    /** 生产会话日志用：先等事件流终局被消费，再写 Footer 并关文件 */
    suspend fun endAfterDrain(executionId: String, reason: RunEndReason) =
        end(executionId, reason)

    fun note(executionId: String, level: RunNote, text: UiText)
}

/** 外壳自产行的级别；与 MXU 回调那套 [RunLogKind] 分开，避免 hook 去选 Verbose / Agent */
enum class RunNote {
    Info,
    Warning,
    Error,
}

fun RunJournal.info(executionId: String, text: UiText) =
    note(executionId, RunNote.Info, text)

fun RunJournal.warn(executionId: String, text: UiText) =
    note(executionId, RunNote.Warning, text)

fun RunJournal.error(executionId: String, text: UiText) =
    note(executionId, RunNote.Error, text)

/** 单测与没有落盘的调用方 */
object DiscardingRunJournal : RunJournal {
    override suspend fun begin(plan: RunPlan, executionId: String) = Unit
    override suspend fun end(executionId: String, reason: RunEndReason) = Unit
    override fun note(executionId: String, level: RunNote, text: UiText) = Unit
}
