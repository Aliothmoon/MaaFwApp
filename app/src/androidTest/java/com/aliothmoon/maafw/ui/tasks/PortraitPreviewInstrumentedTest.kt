package com.aliothmoon.maafw.ui.tasks

import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.privileged.WatchdogState
import com.aliothmoon.maafw.runner.DisplayResolution
import com.aliothmoon.maafw.session.PreviewTouchAction
import com.aliothmoon.maafw.ui.components.MaaPreviewSurface
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PortraitPreviewInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun expandPortraitSurfaceTouchAndExit() {
        val resolution = DisplayResolution(720, 1280)
        val fullscreen = mutableStateOf(false)
        val events = mutableListOf<Triple<Int, Int, PreviewTouchAction>>()
        val originalOrientation = compose.activity.requestedOrientation
        compose.setContent {
            MaterialTheme {
                if (fullscreen.value) {
                    FullscreenPreview(
                        resolution = resolution,
                        onExit = { fullscreen.value = false },
                        onTouch = { x, y, action, _ -> events += Triple(x, y, action) },
                    ) {
                        MaaPreviewSurface(
                            resolution = resolution,
                            onSurfaceCreated = {}, onSurfaceAvailable = {}, onSurfaceDestroyed = {},
                        ) {
                            Box(Modifier.fillMaxSize().testTag("image"))
                        }
                    }
                } else {
                    LivePreview(
                        resolution = resolution, surfaceReady = true, running = true,
                        watchdogState = WatchdogState.WATCHING,
                        content = { Box(Modifier.fillMaxSize()) },
                        onEnterFullscreen = { fullscreen.value = true },
                        modifier = Modifier.size(240.dp),
                    )
                }
            }
        }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.tasks_preview_enter_fullscreen))
            .performTouchInput { click() }
        compose.waitUntil(10_000) {
            compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
        }
        compose.waitForIdle()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT, compose.activity.requestedOrientation)
        val image = compose.onNodeWithTag("image")
        val bounds = image.fetchSemanticsNode().boundsInRoot
        assertEquals(720f / 1280f, bounds.width / bounds.height, 0.01f)
        image.performTouchInput { click(Offset(width * 0.25f, height * 0.75f)) }
        compose.runOnIdle {
            assertEquals(listOf(PreviewTouchAction.Down, PreviewTouchAction.Up), events.map { it.third })
            events.forEach {
                assertEquals(180f, it.first.toFloat(), 2f)
                assertEquals(960f, it.second.toFloat(), 2f)
            }
            events.clear()
        }
        image.performTouchInput { swipe(Offset(width * 0.5f, height * 0.6f), Offset(width * 0.5f, height * 0.4f)) }
        compose.runOnIdle {
            assertEquals(PreviewTouchAction.Down, events.first().third)
            assertEquals(PreviewTouchAction.Up, events.last().third)
            assertTrue(events.any { it.third == PreviewTouchAction.Move })
            events.clear()
        }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.tasks_preview_exit_fullscreen))
            .performTouchInput { click() }
        compose.runOnIdle {
            assertFalse(fullscreen.value)
            assertTrue("Exit must not inject touches into the game", events.isEmpty())
            assertEquals(originalOrientation, compose.activity.requestedOrientation)
        }
    }
}
