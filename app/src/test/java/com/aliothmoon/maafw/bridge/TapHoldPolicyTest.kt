package com.aliothmoon.maafw.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

class TapHoldPolicyTest {

    @Test
    fun `unknown fps does not pad`() {
        assertEquals(0L, TapHoldPolicy.minHoldMs(-1f))
        assertEquals(0L, TapHoldPolicy.minHoldMs(0f))
        assertEquals(0L, TapHoldPolicy.minHoldMs(Float.NaN))
    }

    @Test
    fun `30 fps and above does not pad`() {
        assertEquals(0L, TapHoldPolicy.minHoldMs(30f))
        assertEquals(0L, TapHoldPolicy.minHoldMs(60f))
        assertEquals(0L, TapHoldPolicy.padMs(56, 30f))
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
