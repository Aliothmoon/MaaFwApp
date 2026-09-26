package com.aliothmoon.maafw.service

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.aliothmoon.maafw.constant.TextInputResult

/** ACTION_SET_TEXT 只对标准可编辑控件有效，自绘输入框写不进去 */
internal object AccessibilityTextWriter {

    fun write(service: AccessibilityService, displayId: Int, targetPackage: String, text: String): Int {
        val windows = windowsOn(service, displayId) ?: return TextInputResult.UNSUPPORTED_DISPLAY
        // 弹出的输入框可能在另一个 Dialog 窗口里，不能只查焦点窗口
        val node = windows.sortedByDescending { it.isFocused }
            .firstNotNullOfOrNull { window ->
                window.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
            }
            ?: return TextInputResult.NO_FOCUSED_INPUT
        if (targetPackage.isNotEmpty() && node.packageName?.toString() != targetPackage) {
            return TextInputResult.FOREIGN_PACKAGE
        }

        // 密码框读不到原文，显示提示文字时 text 是那段提示，两种都当空框处理
        val current = if (node.isPassword || node.isShowingHintText) "" else node.text?.toString().orEmpty()
        val (merged, cursor) = mergeAtSelection(current, node.textSelectionStart, node.textSelectionEnd, text)
        val setText = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, merged)
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setText)) {
            return TextInputResult.ACTION_FAILED
        }
        val selection = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection)
        return TextInputResult.OK
    }

    /** Android 11 以下只能看到主屏的窗口 */
    private fun windowsOn(service: AccessibilityService, displayId: Int): List<AccessibilityWindowInfo>? = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> service.windowsOnAllDisplays.get(displayId).orEmpty()
        displayId == Display.DEFAULT_DISPLAY -> service.windows
        else -> null
    }
}

/** ACTION_SET_TEXT 是整段替换，InputText 是在光标处输入，所以先拼好全文；选区未知（-1）时接在末尾 */
internal fun mergeAtSelection(current: String, selectionStart: Int, selectionEnd: Int, insert: String): Pair<String, Int> {
    if (selectionStart < 0 || selectionEnd < 0) {
        return current + insert to current.length + insert.length
    }
    val start = minOf(selectionStart, selectionEnd).coerceIn(0, current.length)
    val end = maxOf(selectionStart, selectionEnd).coerceIn(0, current.length)
    return current.substring(0, start) + insert + current.substring(end) to start + insert.length
}
