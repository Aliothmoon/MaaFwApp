package com.aliothmoon.maafw.schedule

import com.aliothmoon.maafw.domain.UnlockCredential
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleHealthLogicTest {

    private val healthy = ScheduleHealthSnapshot(
        backendGranted = true,
        batteryWhitelist = true,
        exactAlarmAllowed = true,
        notification = true,
        overlayGranted = true,
        overlayNeeded = false,
        wakeCredentialMissing = false,
    )

    private val allFailing = ScheduleHealthSnapshot(
        backendGranted = false,
        batteryWhitelist = false,
        exactAlarmAllowed = false,
        notification = false,
        overlayGranted = false,
        overlayNeeded = true,
        wakeCredentialMissing = true,
    )

    @Test
    fun `healthy snapshot has no issues`() {
        assertEquals(emptyList<ScheduleHealthIssue>(), ScheduleHealthLogic.failingIssues(healthy))
    }

    @Test
    fun `issues follow enum order`() {
        assertEquals(ScheduleHealthIssue.entries, ScheduleHealthLogic.failingIssues(allFailing))
    }

    @Test
    fun `missing overlay only matters when the screen saver will be used`() {
        val snapshot = healthy.copy(overlayGranted = false, overlayNeeded = false)

        assertEquals(emptyList<ScheduleHealthIssue>(), ScheduleHealthLogic.failingIssues(snapshot))
        assertEquals(
            listOf(ScheduleHealthIssue.OVERLAY),
            ScheduleHealthLogic.failingIssues(snapshot.copy(overlayNeeded = true)),
        )
    }

    @Test
    fun `wizard leaves backend and pin to the health card`() {
        assertEquals(
            listOf(
                ScheduleHealthIssue.BATTERY,
                ScheduleHealthIssue.EXACT_ALARM,
                ScheduleHealthIssue.NOTIFICATION,
                ScheduleHealthIssue.OVERLAY,
            ),
            ScheduleHealthLogic.wizardItems(allFailing),
        )
    }

    @Test
    fun `overlay is needed only for the background screen saver`() {
        assertTrue(ScheduleHealthLogic.overlayNeeded(backgroundMode = true, screenSaverEnabled = true))
        assertFalse(ScheduleHealthLogic.overlayNeeded(backgroundMode = false, screenSaverEnabled = true))
        assertFalse(ScheduleHealthLogic.overlayNeeded(backgroundMode = true, screenSaverEnabled = false))
    }

    @Test
    fun `a secure lock screen needs a credential that can open it`() {
        fun missing(type: String, secure: Boolean = true, pin: String = "", gesture: Boolean = false) =
            ScheduleHealthLogic.wakeCredentialMissing(type, secure, pin, gesture)

        // 滑动只能开无密码锁屏
        assertTrue(missing(UnlockCredential.TYPE_SWIPE))
        assertFalse(missing(UnlockCredential.TYPE_SWIPE, secure = false))
        // PIN 要填，手势要录
        assertTrue(missing(UnlockCredential.TYPE_PIN, pin = " "))
        assertFalse(missing(UnlockCredential.TYPE_PIN, pin = "1234"))
        assertTrue(missing(UnlockCredential.TYPE_GESTURE))
        assertFalse(missing(UnlockCredential.TYPE_GESTURE, gesture = true))
    }
}
