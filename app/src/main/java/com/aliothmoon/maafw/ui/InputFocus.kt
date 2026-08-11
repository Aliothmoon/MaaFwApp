package com.aliothmoon.maafw.ui

import android.content.Context
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.SoftwareKeyboardController

/** Compose TextField + 悬浮窗 EditText + IME 的输入失焦 */
interface InputFocusManager {
    fun clear(force: Boolean = true)
}

/**
 * 在调用处取当前窗口的那一套
 *
 * 不从根部经 CompositionLocal 下发：FocusManager / View / 键盘控制器都是按窗口的，
 * Dialog 与 Popup 各自成窗，拿到根部那份只会去清主窗口的焦点，本窗口的输入框纹丝不动
 */
@Composable
fun rememberInputFocusManager(): InputFocusManager {
    val focusManager = LocalFocusManager.current
    val hostView = LocalView.current
    val keyboardController = LocalSoftwareKeyboardController.current
    return remember(focusManager, hostView, keyboardController) {
        object : InputFocusManager {
            override fun clear(force: Boolean) {
                clearInputFocus(
                    focusManager = focusManager,
                    hostView = hostView,
                    keyboardController = keyboardController,
                    force = force,
                )
            }
        }
    }
}

fun clearInputFocus(
    focusManager: FocusManager,
    hostView: View,
    keyboardController: SoftwareKeyboardController? = null,
    force: Boolean = true,
) {
    // 整窗的空白点击与每次拖拽都会打到这里；没有 View 持焦就无事可做，下面两次 IMM binder 不白付
    if (hostView.findFocus() == null) return

    focusManager.clearFocus(force = force)

    val focused = hostView.findFocus()
    if (focused != null && focused.onCheckIsTextEditor()) {
        focused.clearFocus()
    }

    keyboardController?.hide()
    val imm = hostView.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    imm?.hideSoftInputFromWindow(hostView.windowToken, InputMethodManager.HIDE_NOT_ALWAYS)
}
