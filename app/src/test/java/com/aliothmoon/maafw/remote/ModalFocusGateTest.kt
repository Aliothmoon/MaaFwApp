package com.aliothmoon.maafw.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

class ModalFocusGateTest {

    private lateinit var executor: ExecutorService

    @Test
    fun `acknowledge releases a waiting modal`() {
        executor = Executors.newSingleThreadExecutor()
        val gate = ModalFocusGate()
        val started = CountDownLatch(1)

        assertTrue(gate.register("focus-1"))
        assertFalse(gate.register("focus-1"))

        val waiting = executor.submit<Boolean> {
            started.countDown()
            gate.await("focus-1")
        }
        assertTrue(started.await(1, TimeUnit.SECONDS))
        assertFalse(gate.acknowledge("unknown"))
        assertTrue(gate.acknowledge("focus-1"))
        assertTrue(waiting.get(1, TimeUnit.SECONDS))
        assertFalse(gate.acknowledge("focus-1"))

        executor.shutdownNow()
    }

    @Test
    fun `releaseAll releases every waiting modal`() {
        executor = Executors.newFixedThreadPool(2)
        val gate = ModalFocusGate()
        val started = CountDownLatch(2)

        val waiting = listOf("focus-1", "focus-2").map { id ->
            gate.register(id)
            submitAwait(executor, gate, id, started)
        }
        assertTrue(started.await(1, TimeUnit.SECONDS))

        gate.releaseAll()

        waiting.forEach { future -> assertTrue(future.get(1, TimeUnit.SECONDS)) }
        assertFalse(gate.acknowledge("focus-1"))
        assertFalse(gate.acknowledge("focus-2"))

        executor.shutdownNow()
    }

    @Test
    fun `awaiting an unknown id fails fast`() {
        assertFalse(ModalFocusGate().await("unknown"))
    }

    private fun submitAwait(
        executor: ExecutorService,
        gate: ModalFocusGate,
        id: String,
        started: CountDownLatch,
    ): Future<Boolean> = executor.submit<Boolean> {
        started.countDown()
        gate.await(id)
    }
}
