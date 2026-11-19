package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.i18n.uiTextFromFramework
import com.aliothmoon.maafw.i18n.uiTextOf
import org.junit.Assert.assertEquals
import org.junit.Test

class PrepareFailureMessagesTest {

    @Test
    fun `known codes map to localized text`() {
        assertEquals(uiTextOf(R.string.msg_prepare_resource), prepareFailureText(PrepareFailureCode.RESOURCE_LOAD))
        assertEquals(uiTextOf(R.string.msg_prepare_tasker), prepareFailureText(PrepareFailureCode.TASKER_BIND))
    }

    /** 两端版本不一致时特权侧可能回新码：原样显示，好过一句笼统的「执行失败」 */
    @Test
    fun `unknown codes pass through and empty falls back`() {
        assertEquals(uiTextFromFramework("new-code"), prepareFailureText("new-code"))
        assertEquals(uiTextOf(R.string.msg_fail_default), prepareFailureText(null))
    }
}
