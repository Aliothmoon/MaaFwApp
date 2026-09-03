package com.aliothmoon.maafw.schedule

import com.aliothmoon.maafw.config.InMemoryUserConfigurationStore
import com.aliothmoon.maafw.domain.RunMode
import com.aliothmoon.maafw.settings.FakeAppSettingsGateway
import io.mockk.mockk
import io.mockk.every
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScheduleViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val settings = FakeAppSettingsGateway()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `ui state follows the current run mode`() = runTest(dispatcher) {
        settings.runMode.value = RunMode.FOREGROUND
        val store = mockk<ScheduleStrategyStore>(relaxed = true) {
            every { strategies } returns MutableStateFlow(emptyList())
        }
        val alarms = mockk<ScheduleAlarmManager>(relaxed = true) {
            every { canScheduleExact() } returns true
            every { hasExactAlarmToggle() } returns false
        }
        val viewModel = ScheduleViewModel(
            store = store,
            alarms = alarms,
            triggerLog = mockk(relaxed = true),
            configurationStore = InMemoryUserConfigurationStore(),
            appSettings = settings,
        )

        check(viewModel.uiState.first { it.rows.isEmpty() }.runMode == RunMode.FOREGROUND)
    }
}
