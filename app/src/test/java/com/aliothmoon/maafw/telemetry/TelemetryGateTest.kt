package com.aliothmoon.maafw.telemetry

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryGateTest {

    @Test
    fun `正式版本不算开发态`() {
        assertFalse(isDebugProjectVersion("1.0.0"))
        assertFalse(isDebugProjectVersion("v4.2.1"))
    }

    @Test
    fun `1_0_0 之下一律算开发态`() {
        assertTrue(isDebugProjectVersion("0.9.9"))
        assertTrue(isDebugProjectVersion("v0.1.0"))
    }

    @Test
    fun `beta 与 rc 之外的预发布标签算开发态`() {
        assertFalse(isDebugProjectVersion("1.2.0-beta.1"))
        assertFalse(isDebugProjectVersion("1.2.0-rc.2"))
        assertTrue(isDebugProjectVersion("1.2.0-ci.123"))
        assertTrue(isDebugProjectVersion("1.2.0-alpha"))
    }

    @Test
    fun `占位版本号与无版本`() {
        assertTrue(isDebugProjectVersion("DEBUG_VERSION"))
        assertFalse(isDebugProjectVersion(null))
        assertFalse(isDebugProjectVersion(""))
        // 解析不出版本号时不拦：PI 写了什么都可能，拦掉等于默认不上报
        assertFalse(isDebugProjectVersion("nightly"))
    }
}
