package com.aliothmoon.maafw.notification

import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.i18n.UiText
import com.aliothmoon.maafw.i18n.uiTextOf
import com.aliothmoon.maafw.privileged.FakePrivilegedServicePort
import com.aliothmoon.maafw.runner.ExecutionResult
import com.aliothmoon.maafw.runner.RunEndReason
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** 一轮结束的播报：手动停不报，到点停要报 */
class NotificationCenterRunTest {

    private val notifier = mockk<RunEventNotifier>(relaxed = true)
    private val external = mockk<ExternalNotificationService>(relaxed = true)
    private val center = NotificationCenter(
        eventNotifier = notifier,
        external = external,
        settings = mockk(relaxed = true),
        recorder = mockk(relaxed = true),
        renderText = UiText::toString,
        servicePort = FakePrivilegedServicePort(),
    )

    @Test
    fun runAlertPostsTheSameSentenceWithoutPush() {
        val message = uiTextOf(R.string.run_log_app_left_display, "pkg")

        center.onRunAlert(message)

        verify(exactly = 1) {
            notifier.notifyRunAlert(uiTextOf(R.string.notification_run_alert_title).toString(), message.toString())
        }
        verify(exactly = 0) { external.send(any(), any()) }
    }

    /** 截图只随「全部完成」那条走；有失败的那轮不带 */
    @Test
    fun screenshotRidesOnlyOnTheCompletionPush() = runTest {
        val shot = NotificationImage(byteArrayOf(1, 2, 3))

        center.onRunFinished(RunEndReason.Ran(ExecutionResult.Completed(emptyList())), screenshot = shot)
        verify(exactly = 1) { external.send(any(), any(), shot) }

        center.onRunFinished(
            RunEndReason.Ran(ExecutionResult.CompletedWithFailures(emptyList())),
            screenshot = shot,
        )
        verify(exactly = 1) { external.send(any(), any(), null) }
    }

    @Test
    fun manualStopIsNotReported() = runTest {
        center.onRunFinished(RunEndReason.Ran(ExecutionResult.Cancelled(emptyList())))

        verify(exactly = 0) { notifier.notifyRunFinished(any(), any(), any()) }
    }

    @Test
    fun stopAtTheTimeLimitIsReportedWithoutPush() = runTest {
        center.onRunFinished(RunEndReason.Ran(ExecutionResult.Cancelled(emptyList())), stoppedAtLimit = true)

        verify(exactly = 1) {
            notifier.notifyRunFinished(
                uiTextOf(R.string.notification_event_run_time_limit).toString(),
                uiTextOf(R.string.notification_event_run_time_limit_body).toString(),
                isError = false,
            )
        }
        verify(exactly = 0) { external.send(any(), any()) }
    }
}
