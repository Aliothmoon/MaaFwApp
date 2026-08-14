package com.aliothmoon.maafw.bridge

import com.aliothmoon.maafw.bridge.TouchPointerSequence.Kind
import com.aliothmoon.maafw.bridge.TouchPointerSequence.Pointer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchPointerSequenceTest {

    private fun p(contact: Int, x: Float = contact * 10f) = Pointer(contact, x, 0f)

    @Test
    fun `first down is ACTION_DOWN`() {
        val step = TouchPointerSequence.plan(Kind.Down, emptyList(), 0, 1f, 2f)
        assertTrue(step.ok)
        assertEquals(TouchPointerSequence.ACTION_DOWN, step.actionMasked)
        assertEquals(0, step.changingIndex)
        assertFalse(step.cancelFirst)
        assertEquals(listOf(Pointer(0, 1f, 2f)), step.pointers)
    }

    @Test
    fun `second down is POINTER_DOWN at the new index`() {
        val step = TouchPointerSequence.plan(Kind.Down, listOf(p(0)), 1, 8f, 9f)
        assertTrue(step.ok)
        assertEquals(TouchPointerSequence.ACTION_POINTER_DOWN, step.actionMasked)
        assertEquals(1, step.changingIndex)
        assertEquals(listOf(p(0), Pointer(1, 8f, 9f)), step.pointers)
    }

    @Test
    fun `lift the second finger first is POINTER_UP`() {
        val current = listOf(p(0), p(1))
        val step = TouchPointerSequence.plan(Kind.Up, current, 1, 0f, 0f)
        assertTrue(step.ok)
        assertEquals(TouchPointerSequence.ACTION_POINTER_UP, step.actionMasked)
        assertEquals(1, step.changingIndex)
        assertEquals(current, step.pointers)
    }

    @Test
    fun `lift the first finger while another stays is POINTER_UP at index 0`() {
        val current = listOf(p(0), p(1))
        val step = TouchPointerSequence.plan(Kind.Up, current, 0, 0f, 0f)
        assertTrue(step.ok)
        assertEquals(TouchPointerSequence.ACTION_POINTER_UP, step.actionMasked)
        assertEquals(0, step.changingIndex)
        assertEquals(current, step.pointers)
    }

    @Test
    fun `last finger up is ACTION_UP`() {
        val step = TouchPointerSequence.plan(Kind.Up, listOf(p(1)), 1, 0f, 0f)
        assertTrue(step.ok)
        assertEquals(TouchPointerSequence.ACTION_UP, step.actionMasked)
        assertEquals(listOf(p(1)), step.pointers)
    }

    @Test
    fun `move updates only that contact`() {
        val step = TouchPointerSequence.plan(Kind.Move, listOf(p(0), p(1)), 1, 40f, 50f)
        assertTrue(step.ok)
        assertEquals(TouchPointerSequence.ACTION_MOVE, step.actionMasked)
        assertEquals(Pointer(1, 40f, 50f), step.pointers[1])
        assertEquals(p(0), step.pointers[0])
    }

    @Test
    fun `repeat down on the same contact cancels then starts a new gesture`() {
        val step = TouchPointerSequence.plan(Kind.Down, listOf(p(0), p(1)), 0, 3f, 4f)
        assertTrue(step.ok)
        assertTrue(step.cancelFirst)
        assertEquals(TouchPointerSequence.ACTION_DOWN, step.actionMasked)
        assertEquals(listOf(Pointer(0, 3f, 4f)), step.pointers)
    }

    @Test
    fun `move or up without that contact is rejected`() {
        assertFalse(TouchPointerSequence.plan(Kind.Move, listOf(p(0)), 1, 0f, 0f).ok)
        assertFalse(TouchPointerSequence.plan(Kind.Up, listOf(p(0)), 1, 0f, 0f).ok)
    }

    @Test
    fun `out of range contact is rejected`() {
        assertFalse(TouchPointerSequence.plan(Kind.Down, emptyList(), -1, 0f, 0f).ok)
        assertFalse(
            TouchPointerSequence.plan(
                Kind.Down,
                emptyList(),
                TouchPointerSequence.MAX_CONTACTS,
                0f,
                0f,
            ).ok,
        )
    }
}
