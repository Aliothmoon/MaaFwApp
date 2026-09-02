package com.aliothmoon.maafw.remote

import com.aliothmoon.maafw.IMaaRunnerCallback
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class MaaRunnerCallbackDispatcherTest {

    @Test
    fun `terminal callback waits for an earlier raw callback`() {
        val calls = mutableListOf<String>()
        val rawStarted = CountDownLatch(1)
        val releaseRaw = CountDownLatch(1)
        val callback = mockk<IMaaRunnerCallback>()
        every { callback.onEvent(any(), any()) } answers {
            calls += "raw"
            rawStarted.countDown()
            releaseRaw.await(5, TimeUnit.SECONDS)
        }
        every { callback.onFinished(any(), any()) } answers {
            calls += "finished"
        }

        val executor = ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.MILLISECONDS,
            LinkedBlockingQueue(),
        )
        val dispatcher = MaaRunnerCallbackDispatcher(executor)
        dispatcher.setCallback(callback)
        try {
            val rawThread = thread {
                dispatcher.dispatch {
                    onEvent("Tasker.Task.Failed", """{"entry":"Fight"}""")
                }
            }
            assertTrue(rawStarted.await(5, TimeUnit.SECONDS))

            val terminalThread = thread {
                dispatcher.dispatchAndAwait {
                    onFinished(2, null)
                }
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (executor.queue.isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(10)
            }
            assertTrue("terminal callback was not queued", executor.queue.isNotEmpty())

            releaseRaw.countDown()
            rawThread.join(TimeUnit.SECONDS.toMillis(5))
            terminalThread.join(TimeUnit.SECONDS.toMillis(5))

            assertEquals(listOf("raw", "finished"), calls)
        } finally {
            dispatcher.close()
        }
    }
}
