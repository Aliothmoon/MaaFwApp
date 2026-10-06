package com.aliothmoon.maafw.ui

import com.aliothmoon.maafw.ui.components.WheelTimePickerState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 守住滚轮选择器的状态边界
 *
 * [WheelTimePickerState.hour] 恒为 24 小时制；12 小时制只是小时列显示 1..12 再配上下午，
 * 下标与钟点之间的换算、换制式时钟点不变，都在这里钉住
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

    @Test
    fun `12 小时制每个初始小时经滚轮下标回写后不变`() {
        for (hour in 0..23) {
            val state = WheelTimePickerState(hour, 41)

            assertEquals(hour >= 12, state.isPm)
            state.selectHourIndex(state.startHourIndex)

            assertEquals(hour, state.hour)
            assertEquals(41, state.minute)
        }
    }

    @Test
    fun `上午 12 点是午夜，下午 12 点是正午`() {
        val state = WheelTimePickerState(9, 30)
        state.selectHour(12)
        assertEquals(0, state.hour)

        state.selectPeriod(true)
        assertEquals(12, state.hour)

        state.selectPeriod(false)
        assertEquals(0, state.hour)
        assertEquals(30, state.minute)
    }

    @Test
    fun `滚小时列保持所选上下午`() {
        val state = WheelTimePickerState(0, 59)
        state.selectPeriod(true)

        for (hour in 1..11) {
            state.selectHour(hour)
            assertEquals(hour + 12, state.hour)
        }
        state.selectHour(12)
        assertEquals(12, state.hour)
        assertEquals(59, state.minute)
    }

    @Test
    fun `切上下午保持钟点与分钟`() {
        val state = WheelTimePickerState(23, 59)
        state.selectPeriod(false)
        assertEquals(11, state.hour)
        state.selectPeriod(false)
        assertEquals(11, state.hour)
        state.selectPeriod(true)
        assertEquals(23, state.hour)
        assertEquals(59, state.minute)
    }

    @Test
    fun `24 小时制下标直接就是小时`() {
        for (hour in 0..23) {
            val state = WheelTimePickerState(hour, 41, is24Hour = true)

            assertEquals(24, state.hourCount)
            assertEquals(0, state.firstHourValue)
            assertEquals(hour, state.startHourIndex)

            state.selectHourIndex(state.startHourIndex)
            assertEquals(hour, state.hour)
            assertEquals(41, state.minute)
        }
    }

    @Test
    fun `12 小时制小时列是 1 到 12`() {
        val state = WheelTimePickerState(13, 0)

        assertEquals(12, state.hourCount)
        assertEquals(1, state.firstHourValue)
        // 13 点落在第 1 项「01」
        assertEquals(0, state.startHourIndex)

        state.selectHourIndex(0)
        assertEquals(13, state.hour)
    }

    @Test
    fun `24 小时制下标越界收敛在一天之内`() {
        val state = WheelTimePickerState(8, 0, is24Hour = true)

        state.selectHourIndex(-1)
        assertEquals(0, state.hour)

        state.selectHourIndex(24)
        assertEquals(23, state.hour)
    }

    @Test
    fun `换制式重建后钟点不变`() {
        for (hour in 0..23) {
            val from24 = WheelTimePickerState(hour, 41, is24Hour = true)
            val to12 = WheelTimePickerState(from24.hour, from24.minute, is24Hour = false)
            to12.selectHourIndex(to12.startHourIndex)
            assertEquals(hour, to12.hour)
            assertEquals(41, to12.minute)

            val back24 = WheelTimePickerState(to12.hour, to12.minute, is24Hour = true)
            back24.selectHourIndex(back24.startHourIndex)
            assertEquals(hour, back24.hour)
            assertEquals(41, back24.minute)
        }
    }

    /** 行数变化后按它重新对中，必须跟着当前钟点走，而不是停在初始值 */
    @Test
    fun `小时列当前下标跟随所选钟点`() {
        val twelve = WheelTimePickerState(9, 0)
        twelve.selectPeriod(true)
        twelve.selectHour(12)
        assertEquals(12, twelve.hour)
        assertEquals(11, twelve.hourIndex)

        val twentyFour = WheelTimePickerState(9, 0, is24Hour = true)
        twentyFour.selectHourIndex(17)
        assertEquals(17, twentyFour.hourIndex)
    }
}
