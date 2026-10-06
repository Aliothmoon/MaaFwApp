package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.domain.ControllerDefinition
import com.aliothmoon.maafw.domain.ResourceDefinition
import com.aliothmoon.maafw.domain.RunConfigurationId
import com.aliothmoon.maafw.domain.RunMode
import com.aliothmoon.maafw.i18n.UiText
import com.aliothmoon.maafw.i18n.isResource
import com.aliothmoon.maafw.i18n.uiTextFormatted
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayHazardsTest {

    private val plan = RunPlan(
        projectName = "demo",
        projectVersion = "1",
        controller = ControllerDefinition(),
        resource = ResourceDefinition("官服", listOf("./base")),
        runConfigurationId = RunConfigurationId("c1"),
        tasks = emptyList(),
    )

    private class RecordingRunJournal : RunJournal {
        val notes = mutableListOf<Pair<RunNote, UiText>>()

        override suspend fun begin(plan: RunPlan, executionId: String) = Unit
        override suspend fun end(executionId: String, reason: RunEndReason) = Unit
        override fun note(executionId: String, level: RunNote, text: UiText) {
            notes += level to text
        }
    }

    private fun context(
        runMode: RunMode = RunMode.BACKGROUND,
        acknowledged: Set<ConfirmToken> = emptySet(),
        journal: RunJournal = DiscardingRunJournal,
    ) = RunContext(RunTrigger.Manual, runMode, plan, acknowledged, journal = journal)

    private val both = DisplayHazards(smartResolution = true, eyeProtectionSource = "xiaomi:screen_paper_mode_enabled")

    private fun precheck(hazards: DisplayHazards) = DisplayHazardPrecheck { hazards }

    // ── 检查 ─────────────────────────────────────────────────────────

    @Test
    fun `nothing on passes`() = runTest {
        assertEquals(Verdict.Pass, precheck(DisplayHazards()).evaluate(context()))
    }

    @Test
    fun `smart resolution is asked first and only in background mode`() = runTest {
        val background = precheck(both).evaluate(context())
        assertTrue(background is Verdict.NeedsConfirmation)
        background as Verdict.NeedsConfirmation
        assertEquals(DisplayHazardPrecheck.SMART_RESOLUTION, background.token)
        assertTrue(background.prompt.isResource(R.string.precheck_smart_resolution_enabled))
        // 只是提醒：定时与悬浮窗照跑
        assertTrue(background.advisory)

        val foreground = precheck(both).evaluate(context(RunMode.FOREGROUND))
        assertEquals(DisplayHazardPrecheck.EYE_PROTECTION, (foreground as Verdict.NeedsConfirmation).token)
    }

    @Test
    fun `eye protection is still asked after smart resolution is acknowledged`() = runTest {
        val verdict = precheck(both).evaluate(context(acknowledged = setOf(DisplayHazardPrecheck.SMART_RESOLUTION)))

        assertEquals(DisplayHazardPrecheck.EYE_PROTECTION, (verdict as Verdict.NeedsConfirmation).token)
        assertTrue(verdict.prompt.isResource(R.string.precheck_eye_protection_enabled))
    }

    @Test
    fun `both acknowledged passes without probing`() = runTest {
        var reads = 0
        val check = DisplayHazardPrecheck {
            reads++
            both
        }
        val acked = setOf(DisplayHazardPrecheck.SMART_RESOLUTION, DisplayHazardPrecheck.EYE_PROTECTION)

        assertEquals(Verdict.Pass, check.evaluate(context(acknowledged = acked)))
        assertEquals(0, reads)
    }

    // ── 运行日志 ─────────────────────────────────────────────────────

    @Test
    fun `notice hook warns about every hazard that is on`() = runTest {
        val journal = RecordingRunJournal()

        val result = DisplayHazardNoticeHook({ both }, journal).engage(context(journal = journal))

        assertTrue(result is EngageResult.Engaged)
        assertEquals(listOf(RunNote.Warning, RunNote.Warning), journal.notes.map { it.first })
        assertTrue(journal.notes[0].second.isResource(R.string.run_log_smart_resolution_enabled))
        assertTrue(
            journal.notes[1].second.isResource(
                R.string.run_log_eye_protection_enabled,
                uiTextFormatted("xiaomi:screen_paper_mode_enabled"),
            ),
        )
    }

    @Test
    fun `notice hook ignores smart resolution in foreground mode`() = runTest {
        val journal = RecordingRunJournal()
        val hook = DisplayHazardNoticeHook({ DisplayHazards(smartResolution = true) }, journal)

        val result = hook.engage(context(RunMode.FOREGROUND, journal = journal))

        assertTrue(result is EngageResult.Skipped)
        assertEquals(emptyList<Pair<RunNote, UiText>>(), journal.notes)
    }
}
