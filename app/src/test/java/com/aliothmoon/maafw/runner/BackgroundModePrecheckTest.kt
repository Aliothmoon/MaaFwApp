package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.domain.ControllerDefinition
import com.aliothmoon.maafw.domain.ResourceDefinition
import com.aliothmoon.maafw.domain.RunConfigurationId
import com.aliothmoon.maafw.domain.RunMode
import com.aliothmoon.maafw.i18n.isResource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundModePrecheckTest {

    private val plan = RunPlan(
        projectName = "demo",
        projectVersion = "1",
        controller = ControllerDefinition(),
        resource = ResourceDefinition("官服", listOf("./base")),
        runConfigurationId = RunConfigurationId("c1"),
        tasks = emptyList(),
    )

    private fun context(runMode: RunMode) =
        RunContext(RunTrigger.Manual, runMode, plan, journal = DiscardingRunJournal)

    @Test
    fun `Android 10 及以上放行后台模式`() = runTest {
        assertEquals(Verdict.Pass, BackgroundModePrecheck(sdkInt = 29).evaluate(context(RunMode.BACKGROUND)))
    }

    @Test
    fun `Android 9 只拦后台模式`() = runTest {
        val precheck = BackgroundModePrecheck(sdkInt = 28)
        assertEquals(Verdict.Pass, precheck.evaluate(context(RunMode.FOREGROUND)))
        val block = precheck.evaluate(context(RunMode.BACKGROUND)) as Verdict.Block
        assertTrue(block.reason.isResource(R.string.runner_background_unsupported))
    }
}
