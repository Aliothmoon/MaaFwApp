package com.aliothmoon.maafw.ui.tasks

import android.content.pm.ActivityInfo
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.aliothmoon.maafw.runner.DisplayResolution
import org.junit.Assert.*
import org.junit.Test

class PreviewGeometryTest {
    private val portrait = DisplayResolution(720, 1280)

    @Test
    fun `fullscreen follows virtual display orientation`() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT, previewOrientation(portrait))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT, previewOrientation(DisplayResolution(1080, 1920)))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, previewOrientation(DisplayResolution(1280, 720)))
    }

    @Test
    fun `portrait fills width with top and bottom letterboxing`() {
        val size = IntSize(1080, 2400)
        assertEquals(DisplayPoint(IntOffset(360, 640), true), viewToVirtualDisplay(Offset(540f, 1200f), size, portrait))
        assertEquals(DisplayPoint(IntOffset(0, 0), true), viewToVirtualDisplay(Offset(0f, 240f), size, portrait))
        assertFalse(viewToVirtualDisplay(Offset(540f, 239.9f), size, portrait).inside)
        assertFalse(viewToVirtualDisplay(Offset(540f, 2160f), size, portrait).inside)
    }

    @Test
    fun `portrait on landscape screen maps the centered image and ignores side bars`() {
        val size = IntSize(2400, 1080)
        assertEquals(IntOffset(360, 640), viewToVirtualDisplay(Offset(1200f, 540f), size, portrait).offset)
        assertFalse(viewToVirtualDisplay(Offset(500f, 540f), size, portrait).inside)
        val outside = viewToVirtualDisplay(Offset(2400f, 1500f), size, portrait)
        assertFalse(outside.inside)
        assertEquals(IntOffset(719, 1279), outside.offset)
    }

    @Test
    fun `landscape and 1080p previews preserve touch coordinates`() {
        assertEquals(IntOffset(640, 360), viewToVirtualDisplay(Offset(1200f, 540f), IntSize(2400, 1080), DisplayResolution(1280, 720)).offset)
        assertEquals(IntOffset(540, 960), viewToVirtualDisplay(Offset(540f, 1200f), IntSize(1080, 2400), DisplayResolution(1080, 1920)).offset)
    }

    @Test
    fun `unmeasured view cannot accept touch`() {
        assertFalse(viewToVirtualDisplay(Offset.Zero, IntSize.Zero, portrait).inside)
    }
}
