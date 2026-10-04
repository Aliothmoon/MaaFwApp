package com.aliothmoon.maafw.remote.internal

import org.junit.Assert.assertEquals
import org.junit.Test

class BouncerSettleTest {

    @Test
    fun activeDeviceKeepsBaseline() {
        // 屏幕本就亮着，唤醒接近 0，不额外拖慢设置页自测
        assertEquals(1_200L, WakeUnlockController.bouncerSettleMs(0L))
    }

    @Test
    fun deepSleepWakeGetsProportionallyLongerSettle() {
        // #230 现场：唤醒约 1.2 秒，原来只等 1.2 秒就打 PIN
        assertEquals(3_600L, WakeUnlockController.bouncerSettleMs(1_200L))
    }

    @Test
    fun settleIsCappedSoCountdownBudgetSurvives() {
        assertEquals(5_000L, WakeUnlockController.bouncerSettleMs(5_000L))
        assertEquals(5_000L, WakeUnlockController.bouncerSettleMs(60_000L))
    }
}
