package com.aliothmoon.maafw.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

class TapHoldPolicyTest {

    @Test
    fun `unknown fps falls back to the floor`() {
        assertEquals(TapHoldPolicy.FLOOR_MS, TapHoldPolicy.minHoldMs(-1f))
        assertEquals(TapHoldPolicy.FLOOR_MS, TapHoldPolicy.minHoldMs(0f))
        assertEquals(TapHoldPolicy.FLOOR_MS, TapHoldPolicy.minHoldMs(Float.NaN))
    }

    @Test
    fun `high fps is held to the floor`() {
        // 60 FPS 两帧只有 34ms
        assertEquals(TapHoldPolicy.FLOOR_MS, TapHoldPolicy.minHoldMs(60f))
    }

    /** 回归：实机 11~14 FPS 下 ~50ms 的点击偶发被吞 */
    @Test
    fun `low fps spans two frames`() {
        assertEquals(182L, TapHoldPolicy.minHoldMs(11f))
        assertEquals(143L, TapHoldPolicy.minHoldMs(14f))
    }

    @Test
    fun `very low fps stays under the long press threshold`() {
        assertEquals(TapHoldPolicy.CAP_MS, TapHoldPolicy.minHoldMs(3f))
    }

    @Test
    fun `pad only covers what is still missing`() {
        assertEquals(182L - 56L, TapHoldPolicy.padMs(56, 11f))
        assertEquals(0L, TapHoldPolicy.padMs(400, 11f))
    }
}
