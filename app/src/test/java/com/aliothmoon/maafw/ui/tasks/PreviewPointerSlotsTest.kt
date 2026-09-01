package com.aliothmoon.maafw.ui.tasks

import com.aliothmoon.maafw.runner.MAX_PREVIEW_CONTACTS
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 分配方向是这个类的全部要点：槽位表与 MaaFramework 的注入共用，
 * 撞号会让对方正在进行的手势被整体 CANCEL
 */
class PreviewPointerSlotsTest {

    @Test
    fun `slots are handed out from the top so the framework keeps the low ones`() {
        val slots = PreviewPointerSlots()

        assertEquals(MAX_PREVIEW_CONTACTS - 1, slots.acquire(1L))
        assertEquals(MAX_PREVIEW_CONTACTS - 2, slots.acquire(2L))
    }

    @Test
    fun `acquire is idempotent for the same pointer`() {
        val slots = PreviewPointerSlots()
        val first = slots.acquire(7L)

        assertEquals(first, slots.acquire(7L))
        assertEquals(first, slots.indexOf(7L))
    }

    @Test
    fun `a released slot is reused by the next finger`() {
        val slots = PreviewPointerSlots()
        val top = slots.acquire(1L)
        slots.acquire(2L)

        assertEquals(top, slots.release(1L))
        assertEquals(-1, slots.indexOf(1L))
        assertEquals(top, slots.acquire(3L))
    }

    @Test
    fun `releasing an unknown pointer reports nothing`() {
        assertEquals(-1, PreviewPointerSlots().release(99L))
    }

    @Test
    fun `a full table refuses further fingers`() {
        val slots = PreviewPointerSlots()
        repeat(MAX_PREVIEW_CONTACTS) { slots.acquire(it.toLong()) }

        assertEquals(-1, slots.acquire(MAX_PREVIEW_CONTACTS.toLong()))
    }

    @Test
    fun `held slots come back with the last reported position and are cleared`() {
        val slots = PreviewPointerSlots()
        val a = slots.acquire(1L)
        val b = slots.acquire(2L)
        slots.remember(a, 10, 20)
        slots.remember(b, 30, 40)
        slots.release(2L)

        val released = mutableListOf<Triple<Int, Int, Int>>()
        slots.releaseHeld { contact, x, y -> released += Triple(contact, x, y) }

        assertEquals(listOf(Triple(a, 10, 20)), released)
        assertEquals(-1, slots.indexOf(1L))
    }
}
