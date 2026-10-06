package com.aliothmoon.maafw.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TelemetryUserIdTest {

    /** 原值出不了设备：上报的是加盐后的 64 位十六进制摘要，同一原值恒得同一结果 */
    @Test
    fun `哈希稳定且不含原值`() {
        val id = TelemetryUserId.hash("0123456789abcdef")
        assertEquals(64, id.length)
        assertEquals(id, TelemetryUserId.hash("0123456789abcdef"))
        assertNotEquals(id, TelemetryUserId.hash("0123456789abcdee"))
        assertEquals(false, id.contains("0123456789abcdef"))
    }

    /** 空值与 Android 2.2 那批机型共用的坏值都不能当标识，否则一批设备会被数成一个人 */
    @Test
    fun `坏 ANDROID_ID 不可用`() {
        assertNull(TelemetryUserId.usableAndroidId(null))
        assertNull(TelemetryUserId.usableAndroidId("  "))
        assertNull(TelemetryUserId.usableAndroidId("9774D56D682E549C"))
        assertEquals("abc", TelemetryUserId.usableAndroidId(" abc "))
    }
}
