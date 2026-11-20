package com.aliothmoon.maafw.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WatchdogStateTest {

    /** 「就绪」不是坏结局：运行日志与提醒只认 isLost */
    @Test
    fun readyMapsFromItsAidlValueAndIsNotLost() {
        assertEquals(WatchdogState.READY, WatchdogState.fromAidl(4))
        assertFalse(WatchdogState.READY.isLost)
    }

    /** 新旧进程跨版本对不上时宁可当空闲，也不要报错 */
    @Test
    fun unknownValueFallsBackToIdle() {
        assertEquals(WatchdogState.IDLE, WatchdogState.fromAidl(99))
    }
}
