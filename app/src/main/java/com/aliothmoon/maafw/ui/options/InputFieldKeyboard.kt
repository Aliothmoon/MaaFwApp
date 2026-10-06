package com.aliothmoon.maafw.ui.options

import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.aliothmoon.maafw.domain.InputFieldState
import com.aliothmoon.maafw.domain.PipelineType

/** 任务页与悬浮窗两处输入框共用：password 字段掩码显示，不提供显示原文的开关 */
internal fun InputFieldState.visualTransformation(): VisualTransformation =
    if (password) PasswordVisualTransformation() else VisualTransformation.None

/** password 字段用密码键盘，输入法不记词；int 型用数字密码键盘 */
internal fun InputFieldState.keyboardType(): KeyboardType = when {
    !password -> KeyboardType.Unspecified
    pipelineType == PipelineType.IntType -> KeyboardType.NumberPassword
    else -> KeyboardType.Password
}
