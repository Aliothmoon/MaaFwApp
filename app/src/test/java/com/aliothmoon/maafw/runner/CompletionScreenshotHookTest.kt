package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.MaaDispatchers
import com.aliothmoon.maafw.domain.ControllerDefinition
import com.aliothmoon.maafw.domain.ResourceDefinition
import com.aliothmoon.maafw.domain.RunConfigurationId
import com.aliothmoon.maafw.domain.RunMode
import com.aliothmoon.maafw.notification.NotificationCenter
import com.aliothmoon.maafw.privileged.FakePrivilegedService
import com.aliothmoon.maafw.privileged.FakePrivilegedServicePort
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CompletionScreenshotHookTest {

    private val plan = RunPlan(
        projectName = "demo",
        projectVersion = "1",
        controller = ControllerDefinition(),
        resource = ResourceDefinition("官服", listOf("./base")),
        runConfigurationId = RunConfigurationId("c1"),
        tasks = emptyList(),
    )
    private val service = FakePrivilegedService().apply { frameJpeg = byteArrayOf(9, 8, 7) }
    private var wanted = true
    private val center = mockk<NotificationCenter> { coEvery { wantsCompletionScreenshot() } answers { wanted } }
    private val hook = CompletionScreenshotHook(center, FakePrivilegedServicePort(service))

    @Before
    fun setUp() {
        mockkObject(MaaDispatchers)
        every { MaaDispatchers.IO } returns Dispatchers.Unconfined
    }

    @After
    fun tearDown() {
        unmockkObject(MaaDispatchers)
    }

    private fun context() = RunContext(RunTrigger.Manual, RunMode.BACKGROUND, plan, journal = DiscardingRunJournal)

    @Test
    fun `captures the end frame only when every task completed`() = runTest {
        val done = context()
        (hook.engage(done) as EngageResult.Engaged).release(RunEndReason.Ran(ExecutionResult.Completed(emptyList())))
        assertArrayEquals(byteArrayOf(9, 8, 7), done.endScreenshot.get())

        val failed = context()
        (hook.engage(failed) as EngageResult.Engaged).release(
            RunEndReason.Ran(ExecutionResult.CompletedWithFailures(emptyList())),
        )
        assertNull(failed.endScreenshot.get())
    }

    /** 没开附图或没有能发图的渠道：连收尾位都不占 */
    @Test
    fun `stays out when nobody wants the screenshot`() = runTest {
        wanted = false

        assertTrue(hook.engage(context()) is EngageResult.Skipped)
    }
}
