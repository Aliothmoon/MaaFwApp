package com.aliothmoon.maafw.bridge

import com.aliothmoon.maafw.bridge.TouchPointerSequence.FailureReason
import com.aliothmoon.maafw.bridge.TouchPointerSequence.Kind
import com.aliothmoon.maafw.bridge.TouchPointerSequence.Pointer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多指规划：pointer index 必须紧凑且 ACTION_POINTER_INDEX 指向正确的手指，
 * 否则系统侧会把第二指的按下/抬起记到别的手指上
 */
class TouchPointerSequenceTest {

    private fun p(contact: Int, x: Float = contact * 10f) = Pointer(contact, x, 0f)

    @Test
    fun `manual high contact starts with Android pointer zero`() {
        val down = TouchPointerSequence.plan(Kind.Down, emptyList(), 15, 20f, 30f)
        assertEquals(15, down.pointers.single().contact)
        assertEquals(0, down.pointers.single().pointerId)
        for (kind in listOf(Kind.Move, Kind.Up)) {
            val next = TouchPointerSequence.plan(kind, down.pointers, 15, 40f, 50f)
            assertEquals(0, next.pointers.single().pointerId)
        }
    }

    @Test
    fun `manual and automated touches have separate contacts and stable low pointer ids`() {
        val manual = TouchPointerSequence.plan(Kind.Down, emptyList(), 15, 10f, 20f)
        val both = TouchPointerSequence.plan(Kind.Down, manual.pointers, 0, 30f, 40f)
        assertFalse(both.cancelFirst)
        assertEquals(listOf(15, 0), both.pointers.map { it.contact })
        assertEquals(listOf(0, 1), both.pointers.map { it.pointerId })
        val manualUp = TouchPointerSequence.plan(Kind.Up, both.pointers, 15, 10f, 20f)
        assertEquals(TouchPointerSequence.ACTION_POINTER_UP, manualUp.actionMasked)
        val remaining = manualUp.pointers.filter { it.contact != 15 }
        val secondManual = TouchPointerSequence.plan(Kind.Down, remaining, 14, 50f, 60f)
        assertEquals(listOf(1, 0), secondManual.pointers.map { it.pointerId })
        val automaticMove = TouchPointerSequence.plan(Kind.Move, secondManual.pointers, 0, 31f, 41f)
        assertEquals(1, automaticMove.pointers[automaticMove.changingIndex].pointerId)
    }

    @Test
    fun `automation first leaves pointer one for manual touch without cancel`() {
        val automatic = TouchPointerSequence.plan(Kind.Down, emptyList(), 0, 10f, 20f)
        val manual = TouchPointerSequence.plan(Kind.Down, automatic.pointers, 15, 30f, 40f)
        assertFalse(manual.cancelFirst)
        assertEquals(listOf(0, 1), manual.pointers.map { it.pointerId })
    }

    @Test
    fun `duplicate logical contact resets Android pointer id after cancellation`() {
        val current = listOf(Pointer(0, 1f, 2f, 1), Pointer(15, 3f, 4f, 0))
        val repeated = TouchPointerSequence.plan(Kind.Down, current, 0, 5f, 6f)
        assertTrue(repeated.cancelFirst)
        assertEquals(0, repeated.pointers.single().pointerId)
    }

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
        val step = TouchPointerSequence.plan(Kind.Up, current, 1, 10f, 0f)
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
        val step = TouchPointerSequence.plan(Kind.Up, listOf(p(1)), 1, 10f, 0f)
        assertTrue(step.ok)
        assertEquals(TouchPointerSequence.ACTION_UP, step.actionMasked)
        assertEquals(listOf(p(1)), step.pointers)
    }

    @Test
    fun `up carries the lift point of that contact`() {
        // fw 的 touch_up 传的是该手指最后位置；抬起坐标要落到被抬的那根手指上
        val step = TouchPointerSequence.plan(Kind.Up, listOf(p(0), p(1)), 1, 40f, 50f)
        assertTrue(step.ok)
        assertEquals(Pointer(1, 40f, 50f), step.pointers[1])
        assertEquals(p(0), step.pointers[0])
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
        val move = TouchPointerSequence.plan(Kind.Move, listOf(p(0)), 1, 0f, 0f)
        val up = TouchPointerSequence.plan(Kind.Up, listOf(p(0)), 1, 0f, 0f)

        assertFalse(move.ok)
        assertEquals(FailureReason.MissingContact, move.failureReason)
        assertFalse(up.ok)
        assertEquals(FailureReason.MissingContact, up.failureReason)
    }

    @Test
    fun `out of range contact is rejected`() {
        val negative = TouchPointerSequence.plan(Kind.Down, emptyList(), -1, 0f, 0f)
        val tooLarge =
            TouchPointerSequence.plan(
                Kind.Down,
                emptyList(),
                TouchPointerSequence.MAX_CONTACTS,
                0f,
                0f,
            )

        assertFalse(negative.ok)
        assertEquals(FailureReason.InvalidContact, negative.failureReason)
        assertFalse(tooLarge.ok)
        assertEquals(FailureReason.InvalidContact, tooLarge.failureReason)
    }

    @Test
    fun `adding another contact beyond capacity is rejected`() {
        val current = List(TouchPointerSequence.MAX_CONTACTS) { p(1) }
        val step = TouchPointerSequence.plan(Kind.Down, current, 0, 0f, 0f)

        assertFalse(step.ok)
        assertEquals(FailureReason.TooManyContacts, step.failureReason)
    }
}
