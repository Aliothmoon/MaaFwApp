package com.aliothmoon.maafw.schedule

import com.aliothmoon.maafw.domain.RunMode
import org.junit.Assert.assertEquals
import org.junit.Test

/** 与 CloseTargetAppHook 同序：前台整项不生效，其次全局压过规则 */
class CloseAppEffectTest {

    @Test
    fun `foreground mode disables the option whatever the switches say`() {
        assertEquals(CloseAppEffect.FOREGROUND_INACTIVE, CloseAppEffect.of(RunMode.FOREGROUND, true, true))
    }

    @Test
    fun `the global switch wins over the rule`() {
        assertEquals(CloseAppEffect.GLOBAL_OVERRIDE, CloseAppEffect.of(RunMode.BACKGROUND, true, false))
        assertEquals(CloseAppEffect.GLOBAL_OVERRIDE, CloseAppEffect.of(RunMode.BACKGROUND, true, true))
    }

    @Test
    fun `the rule applies only when the global switch is off`() {
        assertEquals(CloseAppEffect.STRATEGY_ACTIVE, CloseAppEffect.of(RunMode.BACKGROUND, false, true))
        assertEquals(CloseAppEffect.INACTIVE, CloseAppEffect.of(RunMode.BACKGROUND, false, false))
    }
}
