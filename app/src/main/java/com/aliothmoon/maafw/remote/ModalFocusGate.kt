package com.aliothmoon.maafw.remote

import java.util.concurrent.CountDownLatch

/** 特权进程内的 blocking modal 确认闸门 */
class ModalFocusGate {

    private val lock = Any()
    private var open = true
    private val waiting = LinkedHashMap<String, CountDownLatch>()

    fun register(id: String): Boolean {
        if (id.isBlank()) return false
        synchronized(lock) {
            if (!open) return false
            return waiting.putIfAbsent(id, CountDownLatch(1)) == null
        }
    }

    fun await(id: String): Boolean {
        val latch = synchronized(lock) { waiting[id] } ?: return false
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
        val latch = synchronized(lock) { waiting.remove(id) } ?: return false
        latch.countDown()
        return true
    }

    fun releaseAll() {
        val released: List<CountDownLatch>
        synchronized(lock) {
            open = false
            released = waiting.values.toList()
            waiting.clear()
        }
        released.forEach(CountDownLatch::countDown)
    }

    /** stop / destroy 后不再接受新 modal；下一轮 start 前调用 [reset] */
    fun reset() {
        val stale: List<CountDownLatch>
        synchronized(lock) {
            stale = waiting.values.toList()
            waiting.clear()
            open = true
        }
        stale.forEach(CountDownLatch::countDown)
    }
}
