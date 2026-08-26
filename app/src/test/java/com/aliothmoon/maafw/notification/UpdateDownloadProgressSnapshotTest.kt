package com.aliothmoon.maafw.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateDownloadProgressSnapshotTest {

    private val noopVersion: (String) -> String = { v -> "v$v" }
    private val noopSize: (Long) -> String = { bytes -> "${bytes}B" }

    @Test
    fun `downloading with known total is determinate and sets progress to exact fraction`() {
        val snap = UpdateDownloadProgressSnapshots.from(
            state = DownloadState.Downloading("1.4.0", downloadedBytes = 250L, totalBytes = 1000L),
            versionLabel = noopVersion,
            sizeLabel = noopSize,
            errorMessage = null,
        )
        // 250/1000 = 25% → 0.25 * PROGRESS_MAX(1000) = 250
        assertEquals(250, snap.progress)
        assertFalse(snap.indeterminate)
        assertTrue(snap.ongoing)
        assertFalse(snap.autoCancel)
        assertEquals(UpdateDownloadProgressSnapshots.PROGRESS_MAX, UpdateDownloadProgressSnapshots.PROGRESS_MAX)
        assertEquals("250B / 1000B", snap.contentText)
        assertEquals("v1.4.0", snap.shortCriticalText)
    }

    @Test
    fun `downloading with unknown total is indeterminate with content only of downloaded bytes`() {
        val snap = UpdateDownloadProgressSnapshots.from(
            state = DownloadState.Downloading("1.4.0", downloadedBytes = 300L, totalBytes = -1L),
            versionLabel = noopVersion,
            sizeLabel = noopSize,
            errorMessage = null,
        )
        assertEquals(0, snap.progress)
        assertTrue(snap.indeterminate)
        assertTrue(snap.ongoing)
        assertEquals("300B", snap.contentText)
    }

    @Test
    fun `complete state fills progress and marks autoCancel`() {
        val snap = UpdateDownloadProgressSnapshots.from(
            state = DownloadState.Complete("2.0.0"),
            versionLabel = noopVersion,
            sizeLabel = noopSize,
            errorMessage = null,
        )
        assertEquals(UpdateDownloadProgressSnapshots.PROGRESS_MAX, snap.progress)
        assertFalse(snap.indeterminate)
        assertFalse(snap.ongoing)
        assertTrue(snap.autoCancel)
        assertEquals("v2.0.0", snap.shortCriticalText)
    }

    @Test
    fun `failed state surfaces error message and version when present`() {
        val snap = UpdateDownloadProgressSnapshots.from(
            state = DownloadState.Failed(version = "1.4.0", message = "Cannot truncate stale partial update download"),
            versionLabel = noopVersion,
            sizeLabel = noopSize,
            errorMessage = null,
        )
        assertTrue(snap.autoCancel)
        assertFalse(snap.ongoing)
        assertTrue(snap.indeterminate)
        assertEquals("v1.4.0", snap.shortCriticalText)
        assertEquals("Cannot truncate stale partial update download", snap.contentText)
    }

    @Test
    fun `failed state without version drops shortCriticalText`() {
        val snap = UpdateDownloadProgressSnapshots.from(
            state = DownloadState.Failed(version = null, message = "Update SHA-256 digest is missing or invalid"),
            versionLabel = noopVersion,
            sizeLabel = noopSize,
            errorMessage = null,
        )
        assertNull(snap.shortCriticalText)
        assertEquals("Update SHA-256 digest is missing or invalid", snap.contentText)
    }

    @Test
    fun `progress clamps to total when downloadedBytes exceeds totalBytes`() {
        // 服务器中途改了 Content-Length 或者客户端算错了,不让百分比倒吸
        val snap = UpdateDownloadProgressSnapshots.from(
            state = DownloadState.Downloading("1.4.0", downloadedBytes = 1500L, totalBytes = 1000L),
            versionLabel = noopVersion,
            sizeLabel = noopSize,
            errorMessage = null,
        )
        assertEquals(UpdateDownloadProgressSnapshots.PROGRESS_MAX, snap.progress)
    }
}
