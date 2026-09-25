package com.aliothmoon.maafw.bridge

import android.os.SystemClock
import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Test

/** Check the actual MotionEvent properties handed to Android, not just logical slots. */
class TouchInjectionInstrumentedTest {
    @Test
    fun highManualContactIsEncodedAsPointerZeroAndAutomationKeepsItsOwnPointer() {
        val manual = TouchPointerSequence.plan(TouchPointerSequence.Kind.Down, emptyList(), 15, 180f, 960f)
        event(manual).let {
            try {
                assertEquals(MotionEvent.ACTION_DOWN, it.actionMasked)
                assertEquals(0, it.getPointerId(0))
                assertEquals(180f, it.x, 0f)
                assertEquals(960f, it.y, 0f)
            } finally { it.recycle() }
        }
        val automatic = TouchPointerSequence.plan(TouchPointerSequence.Kind.Down, manual.pointers, 0, 300f, 400f)
        event(automatic).let {
            try {
                assertEquals(MotionEvent.ACTION_POINTER_DOWN, it.actionMasked)
                assertEquals(1, it.actionIndex)
                assertEquals(0, it.getPointerId(0))
                assertEquals(1, it.getPointerId(1))
            } finally { it.recycle() }
        }
    }

    private fun event(step: TouchPointerSequence.Step): MotionEvent {
        val method = InputControlUtils::class.java.getDeclaredMethod(
            "obtainEvent", List::class.java, Long::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        val action = step.actionMasked or (step.changingIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        return method.invoke(null, step.pointers, SystemClock.uptimeMillis(), action, -1) as MotionEvent
    }
}
