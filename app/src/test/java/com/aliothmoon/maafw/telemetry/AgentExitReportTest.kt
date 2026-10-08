package com.aliothmoon.maafw.telemetry

import com.aliothmoon.maafw.MaaDispatchers
import com.aliothmoon.maafw.runner.RunnerEvent
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.sentry.Attachment
import io.sentry.SentryEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class AgentExitReportTest {

    private lateinit var crashDir: File
    private val sent = mutableListOf<Pair<SentryEvent, List<Attachment>>>()

    @Before
    fun setUp() {
        crashDir = createTempDirectory("agent-crash").toFile()
        mockkObject(MaaDispatchers)
        every { MaaDispatchers.IO } returns Dispatchers.Unconfined
    }

    @After
    fun tearDown() {
        unmockkObject(MaaDispatchers)
        crashDir.deleteRecursively()
    }

    private fun reporter(secrets: List<String> = emptyList()) = AgentExitReporter(
        scope = CoroutineScope(Dispatchers.Unconfined),
        crashDir = { crashDir },
        secrets = { secrets },
        send = { event, attachments -> sent += event to attachments },
    )

    @Test
    fun `a signal names the group`() {
        val event = RunnerEvent.AgentExited(0, "/data/agent/go-service", exitCode = 139, name = "go-service")
            .toSentryEvent("MaaEnd", runId = "r1", taskLabel = "拜访好友")

        assertEquals("Agent exited: go-service (SIGSEGV)", event.message?.formatted)
        assertEquals(listOf("maafwapp-agent-exited", "MaaEnd", "go-service", "SIGSEGV"), event.fingerprints)
        assertEquals("SIGSEGV", event.getTag("agent.exit"))
        assertEquals("r1", event.getTag("run.id"))
    }

    @Test
    fun `a plain exit code names the group`() {
        val event = RunnerEvent.AgentExited(1, "/data/agent/cpp-algo", exitCode = 2)
            .toSentryEvent("MaaEnd", runId = "r1", taskLabel = null)

        assertEquals("Agent exited: cpp-algo (code 2)", event.message?.formatted)
        assertEquals("code 2", event.getTag("agent.exit"))
    }

    @Test
    fun `the crash report and a redacted stderr tail are attached`() {
        File(crashDir, "agent_1.txt").writeText("backtrace")
        val reporter = reporter(secrets = listOf("hunter22"))
        reporter.onOutput(RunnerEvent.AgentOutput("stdout line", fromStderr = false))
        reporter.onOutput(RunnerEvent.AgentOutput("panic: boom\npassword=hunter22", fromStderr = true))

        reporter.report(RunnerEvent.AgentExited(0, "go-service", 2, crashReport = "agent_1.txt"), "r1", null, "MaaEnd")

        val attachments = sent.single().second
        assertEquals(listOf("agent_1.txt", "agent_stderr_tail.txt"), attachments.map { it.filename })
        assertEquals("backtrace", attachments[0].bytes!!.decodeToString())
        assertEquals("panic: boom\npassword=***", attachments[1].bytes!!.decodeToString())
    }

    @Test
    fun `only the latest stderr lines are kept and a missing report is skipped`() {
        val reporter = reporter()
        repeat(250) { reporter.onOutput(RunnerEvent.AgentOutput("line $it", fromStderr = true)) }

        reporter.report(RunnerEvent.AgentExited(0, "go-service", 137, crashReport = "gone.txt"), "r1", null, "MaaEnd")

        val attachment = sent.single().second.single()
        val lines = attachment.bytes!!.decodeToString().lines()
        assertEquals(200, lines.size)
        assertEquals("line 50", lines.first())
        assertEquals("line 249", lines.last())
    }
}
