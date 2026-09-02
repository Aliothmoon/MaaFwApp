package com.aliothmoon.maafw.notification

import com.aliothmoon.maafw.domain.RunConfigurationId
import com.aliothmoon.maafw.runner.ActiveExecution
import com.aliothmoon.maafw.runner.RunnerPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunProgressSnapshotTest {

    @Test
    fun `Preparing is indeterminate even when the plan already knows the total`() {
        val snapshot = RunProgressSnapshots.from(
            phase = RunnerPhase.Preparing,
            execution = execution(done = 0, total = 5),
            statusText = null,
        )
        assertEquals(RunProgressTitle.Preparing, snapshot.title)
        assertTrue(snapshot.indeterminate)
        assertEquals(0, snapshot.progress)
        assertEquals("0/5", snapshot.shortCriticalText)
        assertEquals("0/5", snapshot.contentText)
    }

    @Test
    fun `zero tasks stay indeterminate while running`() {
        val snapshot = RunProgressSnapshots.from(
            phase = RunnerPhase.Running,
            execution = execution(done = 0, total = 0),
            statusText = "连接中",
        )
        assertTrue(snapshot.indeterminate)
        assertEquals(0, snapshot.progress)
        assertNull(snapshot.shortCriticalText)
        assertEquals("连接中", snapshot.contentText)
    }

    @Test
    fun `running with a current task sits halfway through the next slot`() {
        val snapshot = RunProgressSnapshots.from(
            phase = RunnerPhase.Running,
            execution = execution(
                done = 2,
                total = 5,
                currentTaskName = "Fight",
                taskLabels = mapOf("Fight" to "作战"),
            ),
            statusText = "正在刷关",
        )
        assertEquals(RunProgressTitle.Running, snapshot.title)
        assertFalse(snapshot.indeterminate)
        // (2 + 0.5) / 5 * 1000
        assertEquals(500, snapshot.progress)
        assertEquals("2/5", snapshot.shortCriticalText)
        assertEquals("2/5 · 作战 · 正在刷关", snapshot.contentText)
    }

    @Test
    fun `without a current task the bar stays on completed slots`() {
        assertEquals(400, RunProgressSnapshots.progressValue(done = 2, total = 5, hasCurrentTask = false))
        assertEquals(0, RunProgressSnapshots.progressValue(done = 0, total = 5, hasCurrentTask = false))
        assertEquals(1000, RunProgressSnapshots.progressValue(done = 5, total = 5, hasCurrentTask = true))
        assertEquals(100, RunProgressSnapshots.progressValue(done = 0, total = 5, hasCurrentTask = true))
    }

    @Test
    fun `missing label falls back to the task name`() {
        val snapshot = RunProgressSnapshots.from(
            phase = RunnerPhase.Running,
            execution = execution(done = 0, total = 3, currentTaskName = "StartUp"),
            statusText = null,
        )
        assertEquals("0/3 · StartUp", snapshot.contentText)
    }

    @Test
    fun `duplicate task names use the captured instance label`() {
        val snapshot = RunProgressSnapshots.from(
            phase = RunnerPhase.Running,
            execution = execution(
                done = 1,
                total = 2,
                currentTaskName = "Fight",
                currentTaskLabel = "第二次",
            ),
            statusText = null,
        )
        assertEquals("1/2 · 第二次", snapshot.contentText)
    }

    @Test
    fun `stopping keeps a determinate bar`() {
        val snapshot = RunProgressSnapshots.from(
            phase = RunnerPhase.Stopping,
            execution = execution(done = 3, total = 4, currentTaskName = "Mall"),
            statusText = null,
        )
        assertEquals(RunProgressTitle.Stopping, snapshot.title)
        assertFalse(snapshot.indeterminate)
        // (3 + 0.5) / 4 * 1000
        assertEquals(875, snapshot.progress)
        assertEquals("3/4 · Mall", snapshot.contentText)
    }

    @Test
    fun `multiline status keeps the first line`() {
        val snapshot = RunProgressSnapshots.from(
            phase = RunnerPhase.Running,
            execution = execution(done = 1, total = 2),
            statusText = "第一行\n第二行",
        )
        assertEquals("1/2 · 第一行", snapshot.contentText)
    }

    @Test
    fun `blank status is omitted`() {
        val snapshot = RunProgressSnapshots.from(
            phase = RunnerPhase.Running,
            execution = execution(done = 1, total = 1),
            statusText = "  \n  ",
        )
        assertEquals("1/1", snapshot.contentText)
    }

    private fun execution(
        done: Int,
        total: Int,
        currentTaskName: String? = null,
        currentTaskLabel: String? = null,
        taskLabels: Map<String, String> = emptyMap(),
    ) = ActiveExecution(
        executionId = "e1",
        runConfigurationId = RunConfigurationId("c1"),
        currentTaskName = currentTaskName,
        currentTaskLabel = currentTaskLabel,
        completedTaskCount = done,
        totalTaskCount = total,
        taskResults = emptyList(),
        taskLabels = taskLabels,
    )
}
