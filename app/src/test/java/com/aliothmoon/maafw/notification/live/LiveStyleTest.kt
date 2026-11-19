package com.aliothmoon.maafw.notification.live

import com.aliothmoon.maafw.notification.RunProgressSnapshots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveStyleTest {

    private fun content(
        taskLabel: String? = "Daily",
        progressLabel: String? = "2/5",
        statusLine: String? = "Tapping start",
        indeterminate: Boolean = false,
    ) = RunNotificationContent(
        title = "Running",
        text = listOfNotNull(progressLabel, taskLabel, statusLine).joinToString(" · "),
        progressLabel = progressLabel,
        taskLabel = taskLabel,
        statusLine = statusLine,
        progress = RunProgressSnapshots.PROGRESS_MAX / 2,
        indeterminate = indeterminate,
        barColor = RunProgressSnapshots.BAR_COLOR,
    )

    private fun chip(choice: LiveChipContent, frame: RunNotificationContent = content()) =
        LiveStyle(chipContent = choice).chipText(frame)

    // ── 实时更新的胶囊 ──────────────────────────────────────────────

    @Test
    fun `chip text follows the chosen content`() {
        assertEquals("Daily 2/5", chip(LiveChipContent.BOTH))
        assertEquals("2/5", chip(LiveChipContent.PROGRESS))
        assertEquals("Daily", chip(LiveChipContent.TASK))
        assertEquals("Tapping start", chip(LiveChipContent.LOG))
    }

    @Test
    fun `none clears the chip explicitly while a missing value leaves it unset`() {
        assertEquals("", chip(LiveChipContent.NONE))
        assertNull(chip(LiveChipContent.TASK, content(taskLabel = null)))
        assertNull(chip(LiveChipContent.BOTH, content(taskLabel = null, progressLabel = null)))
    }

    @Test
    fun `both drops whichever half is missing`() {
        assertEquals("2/5", chip(LiveChipContent.BOTH, content(taskLabel = null)))
        assertEquals("Daily", chip(LiveChipContent.BOTH, content(progressLabel = null)))
    }

    @Test
    fun `latest log falls back to the phase title when the status was deduplicated`() {
        assertEquals("Running", chip(LiveChipContent.LOG, content(statusLine = null)))
    }

    // ── 超级岛 ──────────────────────────────────────────────────────

    private fun island(choice: LiveChipContent, frame: RunNotificationContent = content()) =
        LiveStyle(chipContent = choice).islandTexts(frame, taskFallback = "App")

    @Test
    fun `island texts follow the chosen content`() {
        assertEquals(LiveIslandTexts("Daily", "2/5", "50%"), island(LiveChipContent.BOTH))
        assertEquals(LiveIslandTexts("2/5", "", "50%"), island(LiveChipContent.PROGRESS))
        assertEquals(LiveIslandTexts("Daily", "", "Daily"), island(LiveChipContent.TASK))
        assertEquals(LiveIslandTexts("Daily", "Tapping ", "Tapping "), island(LiveChipContent.LOG))
        assertEquals(LiveIslandTexts("", "", ""), island(LiveChipContent.NONE))
    }

    @Test
    fun `island falls back to the app name and an ellipsis before there is progress`() {
        val frame = content(taskLabel = null, progressLabel = null, indeterminate = true)

        assertEquals(LiveIslandTexts("App", "", "…"), island(LiveChipContent.BOTH, frame))
    }

    @Test
    fun `island texts are cut to what the template shows`() {
        val frame = content(taskLabel = "A".repeat(30), progressLabel = "123/4567890")

        val texts = island(LiveChipContent.BOTH, frame)
        assertEquals(16, texts.leftTitle.length)
        assertEquals(8, texts.leftContent.length)
    }

    // ── 配色 ────────────────────────────────────────────────────────

    @Test
    fun `default scheme leaves the color to each style`() {
        assertNull(LiveStyle().progressColor)
        assertNull(LiveStyle().progressColorHex)
    }

    @Test
    fun `preset scheme uses its own color`() {
        val style = LiveStyle(colorScheme = LiveColorScheme.ORANGE)

        assertEquals(0xFFFF9800.toInt(), style.progressColor)
        assertEquals("#FF9800", style.progressColorHex)
    }

    @Test
    fun `custom scheme reads the stored hex and ignores it under other schemes`() {
        assertEquals(0xFF795548.toInt(), LiveStyle(colorScheme = LiveColorScheme.CUSTOM, customColor = "#795548").progressColor)
        assertEquals(0xFF00BCD4.toInt(), LiveStyle(colorScheme = LiveColorScheme.CUSTOM, customColor = "00bcd4").progressColor)
        assertEquals(0xFF4CAF50.toInt(), LiveStyle(colorScheme = LiveColorScheme.GREEN, customColor = "#795548").progressColor)
    }

    @Test
    fun `an invalid custom hex falls back to the default color`() {
        listOf("", "red", "#12345", "#1234567", "#GGGGGG", "#-12345", "+123456").forEach { raw ->
            assertNull(raw, LiveStyle(colorScheme = LiveColorScheme.CUSTOM, customColor = raw).progressColor)
        }
    }

    @Test
    fun `eight digit hex keeps alpha but the island hex drops it`() {
        val style = LiveStyle(colorScheme = LiveColorScheme.CUSTOM, customColor = "#80F44336")

        assertEquals(0x80F44336.toInt(), style.progressColor)
        assertEquals("#F44336", style.progressColorHex)
    }

    // ── 落盘值 ──────────────────────────────────────────────────────

    @Test
    fun `unknown stored names fall back to defaults`() {
        assertEquals(
            LiveStyle(customColor = "#123456", customTrackerPath = "/x"),
            LiveStyle.parse("BOGUS", "BLUE", "#123456", "DOT", "/x"),
        )
        assertEquals(
            LiveStyle(LiveChipContent.LOG, LiveColorScheme.TEAL, "", LiveTrackerIcon.APP, ""),
            LiveStyle.parse("LOG", "TEAL", "", "APP", ""),
        )
    }

    @Test
    fun `a custom tracker icon is exported as the default one`() {
        assertEquals("DEFAULT", LiveStyle.portableTrackerIcon("CUSTOM"))
        assertEquals("APP", LiveStyle.portableTrackerIcon("APP"))
        assertEquals("DEFAULT", LiveStyle.portableTrackerIcon("DEFAULT"))
    }

    // ── 样式页入口 ──────────────────────────────────────────────────

    @Test
    fun `style page is offered unless the standard shade has nothing to style`() {
        fun capability(backend: LiveBackend, liveUpdateAvailable: Boolean) = LiveCapability(
            backend = backend,
            postNotifications = true,
            liveUpdateAvailable = liveUpdateAvailable,
            liveUpdateGranted = false,
            islandLikely = false,
            islandGranted = false,
        )

        assertTrue(capability(LiveBackend.HYPER_ISLAND, liveUpdateAvailable = false).styleConfigurable)
        assertTrue(capability(LiveBackend.LIVE_UPDATE, liveUpdateAvailable = true).styleConfigurable)
        assertTrue(capability(LiveBackend.PLAIN, liveUpdateAvailable = true).styleConfigurable)
        assertFalse(capability(LiveBackend.PLAIN, liveUpdateAvailable = false).styleConfigurable)
    }
}
