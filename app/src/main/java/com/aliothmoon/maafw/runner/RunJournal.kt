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

    suspend fun begin(plan: RunPlan)

    suspend fun end(reason: RunEndReason)

    fun note(level: RunNote, text: UiText)
}

/** 外壳自产行的级别；与 MXU 回调那套 [RunLogKind] 分开，避免 hook 去选 Verbose / Agent */
enum class RunNote {
    Info,
    Warning,
    Error,
}

fun RunJournal.info(text: UiText) = note(RunNote.Info, text)

fun RunJournal.warn(text: UiText) = note(RunNote.Warning, text)

fun RunJournal.error(text: UiText) = note(RunNote.Error, text)

/** 单测与没有落盘的调用方 */
object DiscardingRunJournal : RunJournal {
    override suspend fun begin(plan: RunPlan) = Unit
    override suspend fun end(reason: RunEndReason) = Unit
    override fun note(level: RunNote, text: UiText) = Unit
}
