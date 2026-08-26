package com.aliothmoon.maafw.remote.internal

import org.junit.Assert.assertEquals
import org.junit.Test

class TargetAppInfoLogTest {

    @Test
    fun `success includes raw component spec and resolved package`() {
        val text = TargetAppInfoLog.success(
            rawSpec = "com.game/.MainActivity",
            packageName = "com.game",
            versionName = "2.3.4",
            versionCode = 204,
            uid = 10_123,
            displayId = 5,
            forceStop = true,
        )

        assertEquals(
            "Target app info: spec=com.game/.MainActivity package=com.game " +
                "version=2.3.4 (204) uid=10123 displayId=5 forceStop=true",
            text,
        )
    }

    @Test
    fun `missing package metadata is explicit`() {
        assertEquals(
            "Target app info: spec=com.game package=com.game version=unknown name (9) " +
                "uid=unknown displayId=0 forceStop=false",
            TargetAppInfoLog.success("com.game", "com.game", null, 9, null, 0, false),
        )
        assertEquals(
            "Target app info: spec=com.game package=com.game version=unknown " +
                "uid=10123 displayId=0 forceStop=false",
            TargetAppInfoLog.success("com.game", "com.game", null, null, 10_123, 0, false),
        )
    }

    @Test
    fun `failure keeps launch context and error type`() {
        val error = IllegalStateException("package not found")

        assertEquals(
            "Target app info unavailable: spec=com.game package=com.game " +
                "error=IllegalStateException displayId=3 forceStop=true",
            TargetAppInfoLog.failure("com.game", "com.game", error, 3, true),
        )
    }
}
