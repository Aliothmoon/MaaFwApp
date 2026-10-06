package com.aliothmoon.maafw.telemetry

import com.aliothmoon.maafw.MaaDispatchers
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.sentry.ISpan
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipInputStream

@OptIn(ExperimentalCoroutinesApi::class)
class TaskFailureReporterTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val logDir by lazy { temp.newFolder("log") }
    private val sent = mutableListOf<FailureReport>()

    private val failure = TaskFailure(
        runId = "e1",
        task = "Reward",
        taskId = 7,
        startedAtMs = 1_000,
        durationMs = 300,
        failedNodes = 1,
        root = FailureSignal("FindReward", "recognition", sourceTaskId = 7, nodeId = 3, durationMs = 250),
        terminal = null,
        options = emptyMap(),
        tags = emptyMap(),
        span = mockk<ISpan>(relaxed = true),
    )

    @Before
    fun setUp() {
        mockkObject(MaaDispatchers)
        every { MaaDispatchers.IO } returns dispatcher
    }

    @After
    fun tearDown() {
        unmockkObject(MaaDispatchers)
    }

    private fun kotlinx.coroutines.test.TestScope.reporter(secrets: List<String> = emptyList()) = TaskFailureReporter(
        scope = this,
        sources = { listOf(EvidenceSource(root = logDir, images = true)) },
        secrets = { secrets },
        send = sent::add,
    )

    private fun write(path: String, content: String) = File(logDir, path).apply {
        parentFile?.mkdirs()
        writeText(content)
    }

    private fun entries(report: FailureReport): Set<String> {
        val bytes = (report.attachment as AttachmentOutcome.Attached).bytes
        return ZipInputStream(bytes.inputStream()).use { zip -> generateSequence { zip.nextEntry }.map { it.name }.toSet() }
    }

    @Test
    fun `失败事件带上这个任务的日志与截图，密码先打码`() = runTest(dispatcher) {
        val reporter = reporter(secrets = listOf("hunter2-secret"))
        write("maafw.log", "before\n")
        reporter.onTaskStarted()
        advanceUntilIdle()

        write("maafw.log", "before\npassword=hunter2-secret failed\n")
        write("on_error/failure.png", "image")
        reporter.report(failure, "Demo", attachmentsSampleRate = 1.0)
        advanceUntilIdle()

        val report = sent.single()
        assertEquals("Demo", report.appName)
        assertEquals("before\npassword=*** failed\n", report.logs?.entries?.single()?.content)
        assertEquals(setOf("on_error/failure.png"), entries(report))
    }

    @Test
    fun `没被采样到时不带截图，日志照带`() = runTest(dispatcher) {
        val reporter = reporter()
        reporter.onTaskStarted()
        advanceUntilIdle()

        write("maafw.log", "failed\n")
        write("on_error/failure.png", "image")
        reporter.report(failure, "Demo", attachmentsSampleRate = 0.0)
        advanceUntilIdle()

        assertEquals(AttachmentOutcome.NotSelected, sent.single().attachment)
        assertEquals("failed\n", sent.single().logs?.entries?.single()?.content)
    }

    /** 遥测中途打开的那一轮没有开跑快照，事件照发，只是没有证据 */
    @Test
    fun `没有开跑快照时只发事件`() = runTest(dispatcher) {
        write("maafw.log", "failed\n")
        reporter().report(failure, "Demo", attachmentsSampleRate = 1.0)
        advanceUntilIdle()

        assertNull(sent.single().logs)
        assertEquals(AttachmentOutcome.NotSelected, sent.single().attachment)
    }

    @Test
    fun `下一个任务开跑后不再补采截图`() = runTest(dispatcher) {
        val reporter = reporter()
        reporter.onTaskStarted()
        advanceUntilIdle()

        write("on_error/mine.png", "image")
        reporter.report(failure, "Demo", attachmentsSampleRate = 1.0)
        advanceTimeBy(150)
        reporter.onTaskStarted()
        write("on_error/next-task.png", "image")
        advanceUntilIdle()

        assertEquals(setOf("on_error/mine.png"), entries(sent.single()))
    }

    @Test
    fun `遥测关掉后排着队的事件不发`() = runTest(dispatcher) {
        val reporter = reporter()
        reporter.onTaskStarted()
        advanceUntilIdle()

        reporter.report(failure, "Demo", attachmentsSampleRate = 1.0)
        reporter.cancelAll()
        advanceUntilIdle()

        assertTrue(sent.isEmpty())
    }

    @Test
    fun `附件采样对同一个失败结论固定，比例大致对得上`() {
        assertFalse(shouldSampleAttachment("e1", 7, 0.0))
        assertTrue(shouldSampleAttachment("e1", 7, 1.0))
        assertEquals(shouldSampleAttachment("e1", 7, 0.5), shouldSampleAttachment("e1", 7, 0.5))

        val sampled = (1..2_000).count { shouldSampleAttachment("run-$it", it.toLong(), 0.25) }
        assertTrue("sampled=$sampled", sampled in 400..600)
    }
}
