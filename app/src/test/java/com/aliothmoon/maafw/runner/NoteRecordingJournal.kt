package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.i18n.UiText

/** 只记外壳自产行；要断言 begin / end 顺序的用例各自另记 */
class NoteRecordingJournal : RunJournal {
    val notes = mutableListOf<Pair<RunNote, UiText>>()

    override suspend fun begin(plan: RunPlan, executionId: String) = Unit

    override suspend fun end(executionId: String, reason: RunEndReason) = Unit

    override fun note(executionId: String, level: RunNote, text: UiText) {
        notes += level to text
    }
}
