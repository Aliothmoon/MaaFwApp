package com.aliothmoon.maafw.ui

import com.aliothmoon.maafw.ui.components.WheelTimePickerState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 守住滚轮选择器的状态边界
 *
 * 滚轮把「离中心最近那一项的下标」直接写进 state，下标就是小时/分钟本身——
 * 所以 12 小时制那套索引换算已经不需要，这里管的是初始值收敛与时分互不串扰
 */
class WheelTimePickerStateTest {

    @Test
    fun `0 到 23 点每个初始小时都原样保留`() {
        for (hour in 0..23) {
            val state = WheelTimePickerState(hour, 41)

            assertEquals(hour, state.hour)
            assertEquals(41, state.minute)
        }
    }

    @Test
    fun `滚轮的初始下标落在合法区间`() {
        for (hour in 0..23) {
            for (minute in intArrayOf(0, 1, 30, 59)) {
                val state = WheelTimePickerState(hour, minute)

                assertEquals(hour, state.startHour)
                assertEquals(minute, state.startMinute)
            }
        }
    }

    @Test
    fun `越界初始值收敛到端点`() {
        val early = WheelTimePickerState(-1, -1)
        assertEquals(0, early.hour)
        assertEquals(0, early.minute)

        val late = WheelTimePickerState(24, 60)
        assertEquals(23, late.hour)
        assertEquals(59, late.minute)
    }

    @Test
    fun `滚小时列不影响分钟`() {
        val state = WheelTimePickerState(9, 41)

        for (hour in 0..23) {
            state.hour = hour
            assertEquals(41, state.minute)
        }
    }

    @Test
    fun `滚分钟列不影响小时`() {
        val state = WheelTimePickerState(23, 0)

        for (minute in 0..59) {
            state.minute = minute
            assertEquals(23, state.hour)
        }
    }

    @Test
    fun `午夜与当天最后一分钟都能取到`() {
        val state = WheelTimePickerState(0, 0)
        assertEquals(0, state.hour)
        assertEquals(0, state.minute)

        state.hour = 23
        state.minute = 59
        assertEquals(23, state.hour)
        assertEquals(59, state.minute)
    }
}
