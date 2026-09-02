package com.aliothmoon.maafw.telemetry

import android.content.Context
import com.aliothmoon.maafw.domain.ControllerDefinition
import com.aliothmoon.maafw.domain.ProjectDefinition
import com.aliothmoon.maafw.domain.ResourceDefinition
import com.aliothmoon.maafw.domain.RunConfigurationId
import com.aliothmoon.maafw.domain.TelemetryDefinition
import com.aliothmoon.maafw.domain.TaskGroupDefinition
import com.aliothmoon.maafw.project.FakeProjectRepository
import com.aliothmoon.maafw.project.ProjectState
import com.aliothmoon.maafw.runner.FocusChannel
import com.aliothmoon.maafw.runner.FocusDispatcher
import com.aliothmoon.maafw.runner.FocusMessage
import com.aliothmoon.maafw.runner.PassthroughFocusContentResolver
import com.aliothmoon.maafw.runner.RecordingEventRunnerPort
import com.aliothmoon.maafw.runner.RunPlan
import com.aliothmoon.maafw.runner.RunnerEvent
import com.aliothmoon.maafw.settings.FakeAppSettingsGateway
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.verify
import io.sentry.Sentry
import io.sentry.SentryLevel
import io.sentry.android.core.SentryAndroid
import io.sentry.android.core.SentryAndroidOptions
import io.sentry.protocol.SentryId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TelemetryControllerTest {

    @Test
    fun `traced focus from an old execution is ignored after a new run starts`() =
        runTest(UnconfinedTestDispatcher()) {
            val runner = RecordingEventRunnerPort()
            val settings = FakeAppSettingsGateway().apply { telemetryEnabled.value = true }
            val focusDispatcher = FocusDispatcher(
                projectRepository = FakeProjectRepository(ProjectState.Ready(DEFINITION, emptyList())),
                resolver = PassthroughFocusContentResolver,
                runnerPort = runner,
                scope = backgroundScope,
            )
            val controller = TelemetryController(
                context = mockk<Context>(),
                projectRepository = FakeProjectRepository(ProjectState.Ready(DEFINITION, emptyList())),
                settings = settings,
                focusDispatcher = focusDispatcher,
                runnerPort = runner,
                scope = backgroundScope,
            )

            mockkStatic(Sentry::class)
            mockkStatic(SentryAndroid::class)
            try {
                every {
                    Sentry.captureMessage(any<String>(), any<SentryLevel>())
                } returns SentryId("00000000-0000-0000-0000-000000000000")
                every {
                    SentryAndroid.init(
                        any<Context>(),
                        any<Sentry.OptionsConfiguration<SentryAndroidOptions>>(),
                    )
                } just Runs

                controller.setup()
                runner.prepare(PLAN, "execution-1")
                runner.emit(tracedFocus("old-event"), executionId = "execution-1")
                runner.prepare(PLAN, "execution-2")
                runner.emit(tracedFocus("late-old-event"), executionId = "execution-1")
                runner.emit(tracedFocus("new-event"), executionId = "execution-2")
                advanceUntilIdle()

                verify(exactly = 1) { Sentry.captureMessage("old-event", SentryLevel.INFO) }
                verify(exactly = 1) { Sentry.captureMessage("new-event", SentryLevel.INFO) }
                verify(exactly = 0) {
                    Sentry.captureMessage("late-old-event", any<SentryLevel>())
                }
            } finally {
                io.mockk.unmockkStatic(Sentry::class)
                io.mockk.unmockkStatic(SentryAndroid::class)
            }
        }

    private fun tracedFocus(message: String) = RunnerEvent.Focus(
        FocusMessage(
            message = message,
            content = "",
            channels = setOf(FocusChannel.Log),
            trace = true,
        ),
    )

    private companion object {
        val DEFINITION = ProjectDefinition(
            name = "demo",
            version = "1.0",
            controller = ControllerDefinition(),
            resources = emptyList(),
            tasks = emptyList(),
            groups = listOf(TaskGroupDefinition(name = "ungrouped", isUngrouped = true)),
            options = emptyMap(),
            templates = emptyList(),
            telemetry = TelemetryDefinition(dsn = "https://example.invalid", tracing = false),
        )

        val PLAN = RunPlan(
            projectName = "demo",
            projectVersion = "1.0",
            controller = ControllerDefinition(),
            resource = ResourceDefinition(name = "official", paths = listOf("resource")),
            runConfigurationId = RunConfigurationId("cfg"),
            tasks = emptyList(),
        )
    }
}
