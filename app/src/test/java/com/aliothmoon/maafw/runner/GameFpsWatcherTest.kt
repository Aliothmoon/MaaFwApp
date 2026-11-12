package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.i18n.UiText
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private const val EXECUTION_ID = "e1"

@OptIn(ExperimentalCoroutinesApi::class)
class GameFpsWatcherTest {

    private class QueueReader(vararg values: Float?) : GameFpsReader {
        private val queue = ArrayDeque(values.toList())
        var readCount = 0
            private set

        override suspend fun readGameFps(): Float? {
            readCount++
            return queue.removeFirstOrNull() ?: queue.lastOrNull()
        }
    }

    private class RecordingJournal : RunJournal {
        val notes = mutableListOf<Pair<RunNote, UiText>>()

        override suspend fun begin(plan: RunPlan, executionId: String) = Unit
        override suspend fun end(executionId: String, reason: RunEndReason) = Unit
        override fun note(executionId: String, level: RunNote, text: UiText) {
            notes += level to text
        }
    }

    @Test
    fun `polling publishes valid values and unknown clears the display`() = runTest {
        val reader = QueueReader(60f, null)
        val watcher = GameFpsWatcher(reader, DiscardingRunJournal, backgroundScope)

        watcher.start(EXECUTION_ID)
        advanceTimeBy(1_001)
        assertEquals(60f, watcher.fps.value)

        advanceTimeBy(1_000)
        assertNull(watcher.fps.value)

        watcher.stop()
        assertEquals(2, reader.readCount)
    }

    @Test
    fun `sustained low fps logs once`() = runTest {
        val journal = RecordingJournal()
        val watcher = GameFpsWatcher(object : GameFpsReader {
            override suspend fun readGameFps(): Float? = 25f
        }, journal, backgroundScope)
        val advisor = GameFpsAdvisor()

        repeat(15) { watcher.pollOnce(EXECUTION_ID, advisor) }
        repeat(5) { watcher.pollOnce(EXECUTION_ID, advisor) }

        assertEquals(1, journal.notes.size)
        assertEquals(RunNote.Error, journal.notes.single().first)
    }

    @Test
    fun `sustained degraded fps logs a warning`() = runTest {
        val journal = RecordingJournal()
        val watcher = GameFpsWatcher(object : GameFpsReader {
            override suspend fun readGameFps(): Float? = 40f
        }, journal, backgroundScope)
        val advisor = GameFpsAdvisor()

        repeat(15) { watcher.pollOnce(EXECUTION_ID, advisor) }

        assertEquals(listOf(RunNote.Warning), journal.notes.map { it.first })
    }

    @Test
    fun `unreadable remote does not log low fps`() = runTest {
        val journal = RecordingJournal()
        val watcher = GameFpsWatcher(object : GameFpsReader {
            override suspend fun readGameFps(): Float? = null
        }, journal, backgroundScope)
        val advisor = GameFpsAdvisor()

        repeat(GameFpsAdvisor.DEFAULT_WINDOW_SIZE + GameFpsAdvisor.DEFAULT_MAX_IDLE_STREAK) {
            watcher.pollOnce(EXECUTION_ID, advisor)
        }

        assertNull(watcher.fps.value)
        assertEquals(emptyList<Pair<RunNote, UiText>>(), journal.notes)
    }

    /** 每轮自带判定器：上一轮报过的低帧率，新一轮照样要报 */
    @Test
    fun `a restarted round advises again`() = runTest {
        val journal = RecordingJournal()
        val watcher = GameFpsWatcher(object : GameFpsReader {
            override suspend fun readGameFps(): Float? = 25f
        }, journal, backgroundScope)

        watcher.start(EXECUTION_ID)
        advanceTimeBy(GameFpsAdvisor.DEFAULT_WINDOW_SIZE * 1_000L + 1)
        watcher.start("e2")
        advanceTimeBy(GameFpsAdvisor.DEFAULT_WINDOW_SIZE * 1_000L + 1)
        watcher.stop()

        assertEquals(2, journal.notes.size)
    }

    /** binder 读数取消不掉：读的途中被停了，回来的那次不能把旧帧率写回已清空的状态 */
    @Test
    fun `a read that lands after stop is dropped`() = runTest {
        lateinit var watcher: GameFpsWatcher
        watcher = GameFpsWatcher(object : GameFpsReader {
            override suspend fun readGameFps(): Float? {
                watcher.stop()
                return 60f
            }
        }, DiscardingRunJournal, backgroundScope)

        watcher.start(EXECUTION_ID)
        advanceTimeBy(1_001)

        assertNull(watcher.fps.value)
    }
}
