package com.aliothmoon.maafw.remote.internal

import android.view.Display
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityUtilsDecisionTest {

    @Test
    fun `force restart is skipped only when app is confirmed on target virtual display`() {
        val targetDisplay = 12

        assertFalse(
            ActivityUtils.shouldForceStopBeforeStart(
                requestedForceStop = true,
                targetDisplayId = targetDisplay,
                currentDisplayId = targetDisplay,
                forceRestart = false,
            ),
        )
    }

    @Test
    fun `main display or unknown placement keeps legacy force restart`() {
        val targetDisplay = 12

        assertTrue(
            ActivityUtils.shouldForceStopBeforeStart(
                requestedForceStop = true,
                targetDisplayId = targetDisplay,
                currentDisplayId = Display.DEFAULT_DISPLAY,
                forceRestart = false,
            ),
        )
        assertTrue(
            ActivityUtils.shouldForceStopBeforeStart(
                requestedForceStop = true,
                targetDisplayId = targetDisplay,
                currentDisplayId = null,
                forceRestart = false,
            ),
        )
    }

    @Test
    fun `explicit force restart and primary display preserve current behavior`() {
        assertTrue(
            ActivityUtils.shouldForceStopBeforeStart(
                requestedForceStop = true,
                targetDisplayId = Display.DEFAULT_DISPLAY,
                currentDisplayId = Display.DEFAULT_DISPLAY,
                forceRestart = false,
            ),
        )
        assertTrue(
            ActivityUtils.shouldForceStopBeforeStart(
                requestedForceStop = true,
                targetDisplayId = 12,
                currentDisplayId = 12,
                forceRestart = true,
            ),
        )
        assertFalse(
            ActivityUtils.shouldForceStopBeforeStart(
                requestedForceStop = false,
                targetDisplayId = 12,
                currentDisplayId = Display.DEFAULT_DISPLAY,
                forceRestart = true,
            ),
        )
    }
}
