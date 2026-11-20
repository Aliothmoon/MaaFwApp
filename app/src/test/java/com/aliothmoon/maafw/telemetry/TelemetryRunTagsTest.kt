package com.aliothmoon.maafw.telemetry

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import com.aliothmoon.maafw.domain.RunMode
import com.aliothmoon.maafw.runner.ResolutionPresets
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TelemetryRunTagsTest {

    private val packages = mockk<PackageManager> {
        every { getPackageInfo("game", 0) } returns PackageInfo().apply { versionName = "1.2.3" }
        every { getPackageInfo("gone", 0) } throws PackageManager.NameNotFoundException()
    }
    private val context = mockk<Context> { every { packageManager } returns packages }
    private val preset = ResolutionPresets.builtIn.first()

    @Test
    fun `后台模式带分辨率与目标应用版本`() {
        val tags = TelemetryRunTags.collect(context, RunMode.BACKGROUND, preset, "game")

        assertEquals("background", tags["run_mode"])
        assertEquals(preset.id, tags["resolution"])
        assertEquals("1.2.3", tags["target.version"])
    }

    /** 前台的尺寸是设备物理尺寸；还没跑过一轮就不知道目标是谁 */
    @Test
    fun `前台不带分辨率，目标未知不打版本`() {
        val tags = TelemetryRunTags.collect(context, RunMode.FOREGROUND, preset, targetPackage = null)

        assertFalse("resolution" in tags)
        assertFalse("target.version" in tags)
    }

    @Test
    fun `目标应用卸了记 not_installed`() {
        assertEquals("not_installed", TelemetryRunTags.collect(context, RunMode.BACKGROUND, preset, "gone")["target.version"])
    }
}
