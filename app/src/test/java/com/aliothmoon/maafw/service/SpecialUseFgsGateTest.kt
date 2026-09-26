package com.aliothmoon.maafw.service

import android.app.AppOpsManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpecialUseFgsGateTest {

    @Test
    fun ignoredOrErroredAppOpDeniesEvenWhenInstallGranted() {
        assertTrue(SpecialUseFgsGate.isDenied(AppOpsManager.MODE_IGNORED, permissionGranted = true))
        assertTrue(SpecialUseFgsGate.isDenied(AppOpsManager.MODE_ERRORED, permissionGranted = true))
    }

    @Test
    fun defaultModeFollowsInstallGrant() {
        assertFalse(SpecialUseFgsGate.isDenied(AppOpsManager.MODE_DEFAULT, permissionGranted = true))
        assertTrue(SpecialUseFgsGate.isDenied(AppOpsManager.MODE_DEFAULT, permissionGranted = false))
    }

    @Test
    fun unreadableModeFallsBackToInstallGrant() {
        assertFalse(SpecialUseFgsGate.isDenied(null, permissionGranted = true))
        assertTrue(SpecialUseFgsGate.isDenied(null, permissionGranted = false))
    }

    @Test
    fun allowedAppOpWinsOverMissingInstallGrant() {
        assertFalse(SpecialUseFgsGate.isDenied(AppOpsManager.MODE_ALLOWED, permissionGranted = false))
    }

    @Test
    fun foregroundModeIsLeftToStartForegroundCatch() {
        assertFalse(SpecialUseFgsGate.isDenied(AppOpsManager.MODE_FOREGROUND, permissionGranted = true))
    }
}
