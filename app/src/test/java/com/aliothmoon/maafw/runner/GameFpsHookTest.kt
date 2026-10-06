package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.domain.ControllerDefinition
import com.aliothmoon.maafw.domain.ResourceDefinition
import com.aliothmoon.maafw.domain.RunConfigurationId
import com.aliothmoon.maafw.domain.RunMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GameFpsHookTest {

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
    fun `foreground runs do not poll`() = runTest {
        val reader = object : GameFpsReader {
            var reads = 0
                private set

            override suspend fun readGameFps(): Float? {
                reads++
                return 60f
            }
        }
        val watcher = GameFpsWatcher(reader, DiscardingRunJournal, backgroundScope)

        assertTrue(GameFpsHook(watcher).engage(context(RunMode.FOREGROUND)) is EngageResult.Skipped)
        advanceTimeBy(5_000)

        assertEquals(0, reader.reads)
    }

    @Test
    fun `background runs engage and release the watcher`() = runTest {
        val reader = object : GameFpsReader {
            var reads = 0
                private set

            override suspend fun readGameFps(): Float? {
                reads++
                return 60f
            }
        }
        val watcher = GameFpsWatcher(reader, DiscardingRunJournal, backgroundScope)
        val hook = GameFpsHook(watcher)

        val result = hook.engage(context(RunMode.BACKGROUND))
        advanceTimeBy(1_001)
        assertEquals(1, reader.reads)
        assertEquals(60f, watcher.fps.value)

        (result as EngageResult.Engaged).release(RunEndReason.Ran(ExecutionResult.Completed(emptyList())))
        val readsAtRelease = reader.reads
        advanceTimeBy(2_000)
        assertEquals(readsAtRelease, reader.reads)
        assertNull(watcher.fps.value)
    }
}
