package com.aliothmoon.maafw.schedule

import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.i18n.uiTextOf
import com.aliothmoon.maafw.notification.NotificationCenter
import com.aliothmoon.maafw.notification.ScheduleNotice
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ScheduleMissedReporterTest {

    private val kept = ScheduleStrategy(id = "kept", name = "Kept")

    private val alarms = mockk<ScheduleAlarmManager> {
        every { drainMissed() } returns listOf(
            ScheduleAlarmManager.MissedTrigger("kept", 1_000L, rebooted = true),
            ScheduleAlarmManager.MissedTrigger("deleted", 2_000L, rebooted = false),
        )
    }
    private val store = mockk<ScheduleStrategyStore>(relaxed = true) {
        every { isLoaded } returns MutableStateFlow(true)
        every { findById("kept") } returns kept
        every { findById("deleted") } returns null
    }
    private val triggerLog = mockk<ScheduleTriggerLog>(relaxed = true)
    private val notifications = mockk<NotificationCenter>(relaxed = true)

    private fun reporter() = ScheduleMissedReporter(alarms, store, triggerLog, notifications)

    /** 原定时刻同时当实际时刻记：没有被叫醒的那一刻，时刻行也就不该多出一段延迟 */
    @Test
    fun missedTriggerIsLoggedAsFailedStartAtItsScheduledTime() = runTest {
        reporter().reportMissed()

        coVerify(exactly = 1) {
            triggerLog.append(
                TriggerLogEntry(
                    strategyId = "kept",
                    strategyName = "Kept",
                    scheduledAt = 1_000L,
                    actualAt = 1_000L,
                    result = TriggerResult.FAILED_START,
                    failureReason = TriggerFailureReason.MISSED_REBOOTED,
                ),
            )
        }
        coVerify(exactly = 1) { store.recordTrigger("kept", TriggerResult.FAILED_START, null, 1_000L) }
    }

    @Test
    fun deletedStrategyIsDropped() = runTest {
        reporter().reportMissed()

        coVerify(exactly = 0) { store.recordTrigger("deleted", any(), any(), any()) }
        coVerify(exactly = 1) { triggerLog.append(any()) }
        coVerify(exactly = 1) {
            notifications.onScheduleNotStarted("Kept", uiTextOf(R.string.schedule_reason_missed_rebooted), ScheduleNotice.FAILED)
        }
    }

    /** 关机一夜漏了好几次：合成一条，带上次数与最近那次的原因 */
    @Test
    fun severalMissedTriggersBecomeOneNotification() = runTest {
        every { alarms.drainMissed() } returns listOf(
            ScheduleAlarmManager.MissedTrigger("kept", 1_000L, rebooted = true),
            ScheduleAlarmManager.MissedTrigger("kept", 2_000L, rebooted = false),
        )

        reporter().reportMissed()

        coVerify(exactly = 1) { notifications.onScheduleNotStarted(any(), any(), any()) }
        coVerify {
            notifications.onScheduleNotStarted(
                "Kept",
                uiTextOf(R.string.schedule_missed_many, 2, uiTextOf(R.string.schedule_reason_missed)),
                ScheduleNotice.FAILED,
            )
        }
    }
}
