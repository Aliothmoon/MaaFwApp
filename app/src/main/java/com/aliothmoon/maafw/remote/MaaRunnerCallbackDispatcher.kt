package com.aliothmoon.maafw.remote

import com.aliothmoon.maafw.IMaaRunnerCallback
import com.aliothmoon.maafw.third.Ln
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException

/**
 * Serializes callback delivery inside the privileged process.
 *
 * Native event callbacks and runner-worker callbacks otherwise travel over separate oneway
 * Binder paths. In particular, onFinished can overtake a Tasker failure and make the app close
 * its log session before the failure arrives. Every invocation captures the callback that was
 * registered when the event was queued, so an old event cannot be delivered to a new execution.
 */
internal class MaaRunnerCallbackDispatcher(
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "maa-runner-callback").apply { isDaemon = true }
    },
) : AutoCloseable {

    @Volatile
    private var callback: IMaaRunnerCallback? = null

    fun setCallback(callback: IMaaRunnerCallback?) {
        this.callback = callback
    }

    fun dispatch(block: IMaaRunnerCallback.() -> Unit) {
        enqueue(block, awaitDispatch = false)
    }

    /**
     * Used for the run terminal callback. Waiting until the Binder invocation has been sent
     * prevents MaaRunner from accepting the next run before all previously queued raw events
     * have at least entered the same callback stream.
     */
    fun dispatchAndAwait(block: IMaaRunnerCallback.() -> Unit) {
        enqueue(block, awaitDispatch = true)
    }

    private fun enqueue(block: IMaaRunnerCallback.() -> Unit, awaitDispatch: Boolean) {
        val receiver = callback ?: return
        val dispatch: Future<*> = try {
            executor.submit {
                runCatching { receiver.block() }
                    .onFailure { Ln.w("MaaRunner: callback failed: ${it.message}") }
            }
        } catch (e: RejectedExecutionException) {
            Ln.w("MaaRunner: callback rejected: ${e.message}")
            return
        }
        if (!awaitDispatch) return
        try {
            dispatch.get()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            Ln.w("MaaRunner: terminal callback wait failed: ${e.message}")
        }
    }

    override fun close() {
        executor.shutdownNow()
    }
}
