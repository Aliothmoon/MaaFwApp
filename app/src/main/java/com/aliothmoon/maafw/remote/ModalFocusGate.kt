package com.aliothmoon.maafw.remote

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch

/** 特权进程内的 blocking modal 确认闸门 */
class ModalFocusGate {

    private val waiting = ConcurrentHashMap<String, CountDownLatch>()

    fun register(id: String): Boolean {
        if (id.isBlank()) return false
        return waiting.putIfAbsent(id, CountDownLatch(1)) == null
    }

    fun await(id: String): Boolean {
        val latch = waiting[id] ?: return false
        return try {
            latch.await()
            true
        } catch (_: InterruptedException) {
            release(id)
            false
        }
    }

    fun acknowledge(id: String): Boolean = release(id)

    fun release(id: String): Boolean {
        val latch = waiting.remove(id) ?: return false
        latch.countDown()
        return true
    }

    fun releaseAll() {
        while (!waiting.isEmpty()) {
            waiting.keys.forEach(::release)
        }
    }
}
