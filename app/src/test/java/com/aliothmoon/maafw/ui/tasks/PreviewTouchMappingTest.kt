package com.aliothmoon.maafw.ui.tasks

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.aliothmoon.maafw.runner.DisplayResolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2400×1080 的视图里放 1280×720 的画面：缩放 1.5，左右各留 240 的黑边 */
class PreviewTouchMappingTest {

    private val view = IntSize(2400, 1080)
    private val display = DisplayResolution(1280, 720)

    /** 换算出来是 -0.07，取整后会落成 0；按整数判就成了画面内的有效按下 */
    @Test
    fun `a touch on the black bar just before the picture is outside`() {
        val point = viewToVirtualDisplay(Offset(239.9f, 540f), view, display)

        assertFalse(point.inside)
        assertEquals(IntOffset(0, 360), point.offset)
    }

    @Test
    fun `the first pixel of the picture is inside`() {
        assertTrue(viewToVirtualDisplay(Offset(240f, 540f), view, display).inside)
    }

    /** 拖出画面后的移动与抬起照样要送达，坐标钳在边缘 */
    @Test
    fun `points past the picture clamp to its edge`() {
        val point = viewToVirtualDisplay(Offset(2400f, 1080f), view, display)

        assertFalse(point.inside)
        assertEquals(IntOffset(1279, 719), point.offset)
    }
}
