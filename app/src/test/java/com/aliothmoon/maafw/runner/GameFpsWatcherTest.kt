package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.i18n.UiText
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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

        override suspend fun begin(plan: RunPlan) = Unit
        override suspend fun end(reason: RunEndReason) = Unit
        override fun note(level: RunNote, text: UiText) {
            notes += level to text
        }
    }

    @Test
    fun `polling publishes valid values and unknown clears the display`() = runTest {
        val reader = QueueReader(60f, null)
        val watcher = GameFpsWatcher(reader, DiscardingRunJournal, backgroundScope)

        watcher.start()
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

        repeat(15) { watcher.pollOnce() }
        repeat(5) { watcher.pollOnce() }

        assertEquals(1, journal.notes.size)
        assertEquals(RunNote.Error, journal.notes.single().first)
    }

    @Test
    fun `sustained degraded fps logs a warning`() = runTest {
        val journal = RecordingJournal()
        val watcher = GameFpsWatcher(object : GameFpsReader {
            override suspend fun readGameFps(): Float? = 40f
        }, journal, backgroundScope)

        repeat(15) { watcher.pollOnce() }

        assertEquals(listOf(RunNote.Warning), journal.notes.map { it.first })
    }

    @Test
    fun `unreadable remote does not log low fps`() = runTest {
        val journal = RecordingJournal()
        val watcher = GameFpsWatcher(object : GameFpsReader {
            override suspend fun readGameFps(): Float? = null
        }, journal, backgroundScope)

        repeat(GameFpsAdvisor.DEFAULT_WINDOW_SIZE + GameFpsAdvisor.DEFAULT_MAX_IDLE_STREAK) {
            watcher.pollOnce()
        }

        assertNull(watcher.fps.value)
        assertEquals(emptyList<Pair<RunNote, UiText>>(), journal.notes)
    }
}
