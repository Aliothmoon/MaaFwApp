package com.aliothmoon.maafw.notification

import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.domain.RemoteBackend
import com.aliothmoon.maafw.i18n.UiText
import com.aliothmoon.maafw.i18n.uiTextOf
import com.aliothmoon.maafw.privileged.FakePrivilegedServicePort
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** 定时没跑起来的通知：失败外推、跳过不外推，后端没就绪的顶替提前提醒 */
class NotificationCenterScheduleTest {

    private val notifier = mockk<RunEventNotifier>(relaxed = true)
    private val external = mockk<ExternalNotificationService>(relaxed = true)
    private val settings = mockk<NotificationSettingsManager> {
        coEvery { current() } returns NotificationSettings(sendOnError = "true")
    }

    /** 资源 id 换不成文案，单测里只看结构：把 UiText 原样转成字符串比对 */
    private val center = NotificationCenter(
        eventNotifier = notifier,
        external = external,
        settings = settings,
        recorder = mockk(relaxed = true),
        renderText = UiText::toString,
        servicePort = FakePrivilegedServicePort(),
    )

    private val failedTitle = uiTextOf(R.string.notification_schedule_failed).toString()

    @Test
    fun failureNotifiesAndPushes() = runTest {
        val reason = uiTextOf(R.string.schedule_reason_rejected)
        val text = uiTextOf(R.string.notification_schedule_detail, "Daily", reason).toString()

        center.onScheduleNotStarted("Daily", reason, ScheduleNotice.FAILED)

        verify(exactly = 1) { notifier.notifyScheduleNotStarted(failedTitle, text, isError = true, offerShizuku = false) }
        verify(exactly = 1) { external.send(failedTitle, text) }
        verify(exactly = 0) { notifier.cancelShizukuDown() }
    }

    @Test
    fun skipNotifiesWithoutPush() = runTest {
        center.onScheduleNotStarted("Daily", uiTextOf(R.string.schedule_reason_rejected), ScheduleNotice.SKIPPED)

        verify(exactly = 1) {
            notifier.notifyScheduleNotStarted(
                uiTextOf(R.string.notification_schedule_skipped).toString(), any(), isError = false, offerShizuku = false,
            )
        }
        verify(exactly = 0) { external.send(any(), any()) }
    }

    @Test
    fun failureRespectsPushSwitch() = runTest {
        coEvery { settings.current() } returns NotificationSettings(sendOnError = "false")

        center.onScheduleNotStarted("Daily", uiTextOf(R.string.schedule_reason_rejected), ScheduleNotice.FAILED)

        verify(exactly = 1) { notifier.notifyScheduleNotStarted(any(), any(), any(), any()) }
        verify(exactly = 0) { external.send(any(), any()) }
    }

    /** 连接失败拼的英文短原因只进触发日志；这一条顶掉「Shizuku 未运行」的提前提醒 */
    @Test
    fun backendDownReplacesTheEarlyWarningAndDropsTheTechnicalCause() = runTest {
        val reason = uiTextOf(R.string.msg_backend_connect_failed, RemoteBackend.SHIZUKU.display, "launcher exited code=1")
        val brief = uiTextOf(R.string.msg_backend_connect_failed_brief, RemoteBackend.SHIZUKU.display)
        val text = uiTextOf(R.string.notification_schedule_detail, "Daily", brief).toString()

        center.onScheduleNotStarted("Daily", reason, ScheduleNotice.BACKEND_DOWN)

        verify(exactly = 1) { notifier.cancelShizukuDown() }
        verify(exactly = 1) { notifier.notifyScheduleNotStarted(failedTitle, text, isError = true, offerShizuku = true) }
        verify(exactly = 1) { external.send(failedTitle, text) }
    }
}
