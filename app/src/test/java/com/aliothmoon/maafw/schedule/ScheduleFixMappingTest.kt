package com.aliothmoon.maafw.schedule

import com.aliothmoon.maafw.runner.WakeUnlockHook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScheduleFixMappingTest {

    private fun entry(
        result: TriggerResult,
        reason: TriggerFailureReason? = null,
        steps: List<TriggerStep> = emptyList(),
    ) = TriggerLogEntry(
        strategyId = "s",
        strategyName = "s",
        scheduledAt = 0L,
        actualAt = 0L,
        result = result,
        failureReason = reason,
        steps = steps,
    )

    @Test
    fun `failed wake unlock step points to the unlock settings`() {
        val blocked = entry(
            TriggerResult.FAILED_START,
            TriggerFailureReason.BLOCKED,
            steps = listOf(TriggerStep(WakeUnlockHook.ID, TriggerStepOutcome.FAILED)),
        )

        assertEquals(ScheduleFixAction.WAKE_UNLOCK_SETTINGS, ScheduleFixMapping.fixActionFor(blocked))
    }

    @Test
    fun `skipped wake unlock is not a failure`() {
        val started = entry(
            TriggerResult.STARTED,
            steps = listOf(TriggerStep(WakeUnlockHook.ID, TriggerStepOutcome.SKIPPED)),
        )

        assertNull(ScheduleFixMapping.fixActionFor(started))
    }

    @Test
    fun `service start failure points to the battery whitelist`() {
        assertEquals(
            ScheduleFixAction.BATTERY,
            ScheduleFixMapping.fixActionFor(entry(TriggerResult.FAILED_SERVICE_START)),
        )
    }

    @Test
    fun `missing configuration points back to the rule`() {
        assertEquals(
            ScheduleFixAction.EDIT_RULE,
            ScheduleFixMapping.fixActionFor(
                entry(TriggerResult.FAILED_START, TriggerFailureReason.CONFIGURATION_MISSING),
            ),
        )
    }

    @Test
    fun `transient failures get no fix button`() {
        listOf(
            TriggerFailureReason.PROJECT_NOT_READY,
            TriggerFailureReason.REJECTED,
            TriggerFailureReason.BLOCKED,
            TriggerFailureReason.NO_EXECUTABLE_TASKS,
            TriggerFailureReason.INVALID_PLAN,
        ).forEach { reason ->
            assertNull(reason.name, ScheduleFixMapping.fixActionFor(entry(TriggerResult.FAILED_START, reason)))
        }
        assertNull(ScheduleFixMapping.fixActionFor(entry(TriggerResult.STARTED)))
        assertNull(ScheduleFixMapping.fixActionFor(entry(TriggerResult.FAILED_VALIDATION)))
    }
}
