package com.aliothmoon.maafw.service

import com.aliothmoon.maafw.ITextInputSink
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.constant.TextInputResult
import com.aliothmoon.maafw.i18n.UiText
import com.aliothmoon.maafw.i18n.uiTextOf
import com.aliothmoon.maafw.runner.RunJournal
import com.aliothmoon.maafw.runner.error
import timber.log.Timber

/** 特权进程只拿得到返回码，面向用户的失败原因在这里落运行日志 */
class AccessibilityTextInputSink(
    private val journal: RunJournal,
    private val activeExecutionId: () -> String?,
) : ITextInputSink.Stub() {

    override fun setText(displayId: Int, targetPackage: String?, text: String?): Int {
        val service = AccessibilityHelperService.instance
        val result = if (service == null) {
            TextInputResult.SERVICE_UNAVAILABLE
        } else {
            // binder 线程上抛出去的多数异常到对端只剩一个默认返回值，这里就地收成失败码
            try {
                AccessibilityTextWriter.write(service, displayId, targetPackage.orEmpty(), text.orEmpty())
            } catch (e: RuntimeException) {
                Timber.w(e, "Accessibility text write threw")
                TextInputResult.ACTION_FAILED
            }
        }
        Timber.i("Accessibility text input displayId=%d chars=%d result=%d", displayId, text?.length ?: 0, result)
        if (result != TextInputResult.OK) report(result, targetPackage.orEmpty())
        return result
    }

    private fun report(result: Int, targetPackage: String) {
        val executionId = activeExecutionId() ?: return
        journal.error(executionId, textInputFailureMessage(result, targetPackage) ?: return)
    }
}

internal fun textInputFailureMessage(result: Int, targetPackage: String): UiText? = when (result) {
    TextInputResult.OK -> null
    TextInputResult.SERVICE_UNAVAILABLE -> uiTextOf(R.string.text_input_accessibility_unavailable)
    TextInputResult.UNSUPPORTED_DISPLAY -> uiTextOf(R.string.text_input_unsupported_display)
    TextInputResult.NO_FOCUSED_INPUT -> uiTextOf(R.string.text_input_no_focused_input)
    TextInputResult.FOREIGN_PACKAGE -> uiTextOf(R.string.text_input_foreign_package, targetPackage)
    else -> uiTextOf(R.string.text_input_action_failed)
}
