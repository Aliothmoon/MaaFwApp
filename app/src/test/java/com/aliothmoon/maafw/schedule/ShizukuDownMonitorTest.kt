package com.aliothmoon.maafw.schedule

import android.content.Context
import com.aliothmoon.maafw.domain.RemoteBackend
import com.aliothmoon.maafw.notification.NotificationCenter
import com.aliothmoon.maafw.privileged.FakeRemoteAccessPort
import com.aliothmoon.maafw.privileged.RemoteAccessState
import com.aliothmoon.maafw.settings.AppSettingsManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ShizukuDownMonitorTest {

    private val notifications = mockk<NotificationCenter>(relaxed = true)

    private fun monitor(
        access: FakeRemoteAccessPort = FakeRemoteAccessPort(),
        backend: RemoteBackend = RemoteBackend.SHIZUKU,
        strategies: List<ScheduleStrategy> = listOf(ScheduleStrategy(name = "早上")),
    ) = ShizukuDownMonitor(
        context = mockk<Context>(),
        access = access,
        appSettings = mockk<AppSettingsManager> {
            every { loaded } returns MutableStateFlow(true)
            every { startupBackend } returns MutableStateFlow(backend)
        },
        store = mockk<ScheduleStrategyStore> {
            every { isLoaded } returns MutableStateFlow(true)
            every { this@mockk.strategies } returns MutableStateFlow(strategies)
        },
        notifications = notifications,
    )

    @Test
    fun `warns when Shizuku is still down with an enabled rule`() = runTest {
        monitor().check(afterBoot = true)

        verify(exactly = 1) { notifications.notifyShizukuDown(true) }
    }

    @Test
    fun `stays quiet without an enabled rule`() = runTest {
        monitor(strategies = listOf(ScheduleStrategy(name = "早上", enabled = false))).check(afterBoot = false)

        verify(exactly = 0) { notifications.notifyShizukuDown(any()) }
    }

    /** Root 没有「没在运行」一说 */
    @Test
    fun `stays quiet on the root backend`() = runTest {
        monitor(backend = RemoteBackend.ROOT).check(afterBoot = true)

        verify(exactly = 0) { notifications.notifyShizukuDown(any()) }
    }

    /** 闹钟拉起的冷进程：binder 晚到一会儿不算没回来 */
    @Test
    fun `stays quiet once the binder arrives`() = runTest {
        val access = FakeRemoteAccessPort().apply {
            upcoming += RemoteAccessState()
            upcoming += RemoteAccessState(shizukuAvailable = true)
        }

        monitor(access = access).check(afterBoot = true)

        verify(exactly = 0) { notifications.notifyShizukuDown(any()) }
    }
}
