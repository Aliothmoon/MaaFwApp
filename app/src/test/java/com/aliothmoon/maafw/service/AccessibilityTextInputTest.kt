package com.aliothmoon.maafw.service

import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.constant.TextInputResult
import com.aliothmoon.maafw.i18n.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 无障碍写文本的纯逻辑：光标处拼接，以及失败码到运行日志文案 */
class AccessibilityTextInputTest {

    @Test
    fun `空框直接写入，光标落在末尾`() {
        assertEquals("中文" to 2, mergeAtSelection("", 0, 0, "中文"))
    }

    @Test
    fun `按键先打的内容保留，中文接在光标处`() {
        assertEquals("abc中文" to 5, mergeAtSelection("abc", 3, 3, "中文"))
        assertEquals("a中文bc" to 3, mergeAtSelection("abc", 1, 1, "中文"))
    }

    @Test
    fun `有选区时替换选中部分，反向选区同样处理`() {
        assertEquals("a中c" to 2, mergeAtSelection("abc", 1, 2, "中"))
        assertEquals("a中c" to 2, mergeAtSelection("abc", 2, 1, "中"))
    }

    @Test
    fun `选区未知或越界时接在末尾`() {
        assertEquals("abc😀" to 5, mergeAtSelection("abc", -1, -1, "😀"))
        assertEquals("abc中" to 4, mergeAtSelection("abc", 9, 9, "中"))
    }

    @Test
    fun `服务没开时提示手动开启，其余失败各有文案`() {
        assertNull(textInputFailureMessage(TextInputResult.OK, "pkg"))
        assertEquals(
            R.string.text_input_accessibility_unavailable,
            (textInputFailureMessage(TextInputResult.SERVICE_UNAVAILABLE, "pkg") as UiText.Resource).resId,
        )
        val foreign = textInputFailureMessage(TextInputResult.FOREIGN_PACKAGE, "com.example.game") as UiText.Resource
        assertEquals(R.string.text_input_foreign_package, foreign.resId)
        assertEquals(listOf("com.example.game"), foreign.args)
        assertEquals(
            R.string.text_input_action_failed,
            (textInputFailureMessage(99, "pkg") as UiText.Resource).resId,
        )
    }
}
