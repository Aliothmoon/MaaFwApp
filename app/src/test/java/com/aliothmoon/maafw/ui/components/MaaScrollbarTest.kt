package com.aliothmoon.maafw.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MaaScrollbarTest {

    @Test
    fun returnsNullWhenContentFitsViewport() {
        val result = computeVerticalScrollbarThumb(
            viewportHeightPx = 500f,
            scrollOffsetPx = 0,
            maxScrollOffsetPx = 0,
            topPaddingPx = 8f,
            bottomPaddingPx = 8f,
            minThumbLengthPx = 48f,
        )
        assertNull(result)
    }

    @Test
    fun returnsNullBeforeFirstLayoutPass() {
        val result = computeVerticalScrollbarThumb(
            viewportHeightPx = 500f,
            scrollOffsetPx = 0,
            maxScrollOffsetPx = Int.MAX_VALUE,
            topPaddingPx = 8f,
            bottomPaddingPx = 8f,
            minThumbLengthPx = 48f,
        )
        assertNull(result)
    }

    @Test
    fun returnsNullForNonPositiveViewportOrTrack() {
        assertNull(
            computeVerticalScrollbarThumb(
                viewportHeightPx = 0f,
                scrollOffsetPx = 0,
                maxScrollOffsetPx = 200,
                topPaddingPx = 4f,
                bottomPaddingPx = 4f,
                minThumbLengthPx = 24f,
            ),
        )
        assertNull(
            computeVerticalScrollbarThumb(
                viewportHeightPx = 16f,
                scrollOffsetPx = 0,
                maxScrollOffsetPx = 200,
                topPaddingPx = 8f,
                bottomPaddingPx = 8f,
                minThumbLengthPx = 24f,
            ),
        )
    }

    @Test
    fun thumbLengthMatchesViewportToContentRatioAndTracksScrollPosition() {
        // viewport = 400, maxScroll = 400 -> content = 800, ratio = 0.5; track = 380 -> thumb = 190
        val atTop = computeVerticalScrollbarThumb(
            viewportHeightPx = 400f,
            scrollOffsetPx = 0,
            maxScrollOffsetPx = 400,
            topPaddingPx = 10f,
            bottomPaddingPx = 10f,
            minThumbLengthPx = 48f,
        )
        val atMid = computeVerticalScrollbarThumb(
            viewportHeightPx = 400f,
            scrollOffsetPx = 200,
            maxScrollOffsetPx = 400,
            topPaddingPx = 10f,
            bottomPaddingPx = 10f,
            minThumbLengthPx = 48f,
        )
        val atBottom = computeVerticalScrollbarThumb(
            viewportHeightPx = 400f,
            scrollOffsetPx = 400,
            maxScrollOffsetPx = 400,
            topPaddingPx = 10f,
            bottomPaddingPx = 10f,
            minThumbLengthPx = 48f,
        )

        assertNotNull(atTop)
        assertEquals(190f, atTop!!.heightPx, 0.01f)
        assertEquals(10f, atTop.topPx, 0.01f)

        assertNotNull(atMid)
        assertEquals(190f, atMid!!.heightPx, 0.01f)
        assertEquals(105f, atMid.topPx, 0.01f)

        assertNotNull(atBottom)
        assertEquals(190f, atBottom!!.heightPx, 0.01f)
        assertEquals(200f, atBottom.topPx, 0.01f)
        assertEquals(390f, atBottom.topPx + atBottom.heightPx, 0.01f)
    }

    @Test
    fun enforcesMinimumThumbLengthForVeryLongContent() {
        // viewport = 400, content = 10400 -> raw thumb ~ 14.6px, clamped to min 48px
        val atBottom = computeVerticalScrollbarThumb(
            viewportHeightPx = 400f,
            scrollOffsetPx = 10_000,
            maxScrollOffsetPx = 10_000,
            topPaddingPx = 10f,
            bottomPaddingPx = 10f,
            minThumbLengthPx = 48f,
        )
        assertNotNull(atBottom)
        assertEquals(48f, atBottom!!.heightPx, 0.01f)
        assertEquals(342f, atBottom.topPx, 0.01f)
    }

    @Test
    fun clampsThumbToTrackWhenViewportIsShorterThanMinThumb() {
        val result = computeVerticalScrollbarThumb(
            viewportHeightPx = 40f,
            scrollOffsetPx = 100,
            maxScrollOffsetPx = 200,
            topPaddingPx = 5f,
            bottomPaddingPx = 5f,
            minThumbLengthPx = 48f,
        )
        assertNotNull(result)
        assertEquals(30f, result!!.heightPx, 0.01f)
        assertEquals(5f, result.topPx, 0.01f)
    }

    @Test
    fun clampsOutOfRangeScrollOffsetWithinTrack() {
        val belowZero = computeVerticalScrollbarThumb(
            viewportHeightPx = 400f,
            scrollOffsetPx = -50,
            maxScrollOffsetPx = 400,
            topPaddingPx = 10f,
            bottomPaddingPx = 10f,
            minThumbLengthPx = 48f,
        )
        val aboveMax = computeVerticalScrollbarThumb(
            viewportHeightPx = 400f,
            scrollOffsetPx = 999,
            maxScrollOffsetPx = 400,
            topPaddingPx = 10f,
            bottomPaddingPx = 10f,
            minThumbLengthPx = 48f,
        )
        assertEquals(10f, belowZero!!.topPx, 0.01f)
        assertEquals(200f, aboveMax!!.topPx, 0.01f)
    }
}
