package com.aliothmoon.maafw.schedule

import com.aliothmoon.maafw.domain.RunMode
import com.aliothmoon.maafw.settings.FakeAppSettingsGateway
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import java.time.LocalTime

@OptIn(ExperimentalCoroutinesApi::class)
class ScheduleAlarmModeObserverTest {

    @Test
    fun `run mode change reschedules all loaded strategies`() = runTest(UnconfinedTestDispatcher()) {
        val settings = FakeAppSettingsGateway()
        val strategies = listOf(
            ScheduleStrategy(name = "daily", executionTimes = listOf(LocalTime.of(12, 0))),
        )
        val strategyFlow = MutableStateFlow(strategies)
        val alarms = mockk<ScheduleAlarmManager>()
        every { alarms.rescheduleAll(any()) } just Runs
        val observer = ScheduleAlarmModeObserver(
            appSettings = settings,
            storeLoaded = MutableStateFlow(true),
            strategies = strategyFlow,
            alarms = alarms,
            scope = backgroundScope,
        )

        observer.start()
        settings.setRunMode(RunMode.FOREGROUND)
        advanceUntilIdle()

        verify(exactly = 1) { alarms.rescheduleAll(strategies) }
    }

    @Test
    fun `run mode change waits for settings before rescheduling`() = runTest(UnconfinedTestDispatcher()) {
        val settings = FakeAppSettingsGateway().apply { loaded.value = false }
        val strategies = listOf(ScheduleStrategy(name = "daily"))
        val strategyFlow = MutableStateFlow(strategies)
        val alarms = mockk<ScheduleAlarmManager>()
        every { alarms.rescheduleAll(any()) } just Runs
        val observer = ScheduleAlarmModeObserver(
            appSettings = settings,
            storeLoaded = MutableStateFlow(true),
            strategies = strategyFlow,
            alarms = alarms,
            scope = backgroundScope,
        )

        observer.start()
        settings.setRunMode(RunMode.FOREGROUND)
        advanceUntilIdle()
        verify(exactly = 0) { alarms.rescheduleAll(any()) }

        settings.loaded.value = true
        advanceUntilIdle()

        verify(exactly = 1) { alarms.rescheduleAll(strategies) }
    }
}
