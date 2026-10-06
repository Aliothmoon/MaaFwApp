package com.aliothmoon.maafw.telemetry

import org.junit.Assert.assertEquals
import org.junit.Test

class TelemetryAppVersionTest {

    /** PI 里的占位版本让位给构建期的版本，`v` 前缀沿用 PI 的写法，与桌面端报的 tag 同形 */
    @Test
    fun `整包构建用构建期版本并沿用 PI 的前缀`() {
        assertEquals("v2.31.0-beta.5", telemetryAppVersion("v0.1.0", "2.31.0-beta.5"))
        assertEquals("2.31.0", telemetryAppVersion("0.1.0", "2.31.0"))
        assertEquals("2.31.0", telemetryAppVersion(null, "2.31.0"))
    }

    /** 没有 tag 的仓库版本名退化成短哈希，数字开头的也不是版本号，前面不能安一个 v */
    @Test
    fun `构建期版本不是版本号时原样用`() {
        assertEquals("7b423d7", telemetryAppVersion("v0.1.0", "7b423d7"))
        assertEquals("abc1234", telemetryAppVersion("v0.1.0", "abc1234"))
        assertEquals("v2.31.0", telemetryAppVersion("v0.1.0", "v2.31.0"))
    }

    @Test
    fun `没有构建期版本时用 PI 自己写的`() {
        assertEquals("v1.2.0", telemetryAppVersion("v1.2.0", ""))
        assertEquals("0.0.0", telemetryAppVersion(null, ""))
        assertEquals("0.0.0", telemetryAppVersion(" ", ""))
    }
}
