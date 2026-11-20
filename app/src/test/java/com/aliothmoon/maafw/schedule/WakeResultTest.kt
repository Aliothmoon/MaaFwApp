package com.aliothmoon.maafw.schedule

import com.aliothmoon.maafw.constant.WakeUnlockResult
import org.junit.Assert.assertEquals
import org.junit.Test

class WakeResultTest {

    @Test
    fun `known codes map to themselves`() {
        assertEquals(WakeResult.OK, WakeResult.fromCode(WakeUnlockResult.OK))
        assertEquals(WakeResult.RECORD_NO_TOUCH, WakeResult.fromCode(WakeUnlockResult.RECORD_NO_TOUCH))
        // 解锁挂载物拿 IPC_FAILED 当连不上时的默认码，要原样认回来
        assertEquals(WakeResult.IPC_FAILED, WakeResult.fromCode(WakeUnlockResult.IPC_FAILED))
    }

    /** 两端版本不一致时特权侧可能回新码，不能说成「服务未连接」 */
    @Test
    fun `an unknown code is not reported as an ipc failure`() {
        assertEquals(WakeResult.UNKNOWN, WakeResult.fromCode(99))
    }
}
