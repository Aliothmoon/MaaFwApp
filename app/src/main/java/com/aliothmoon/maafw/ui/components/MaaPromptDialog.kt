package com.aliothmoon.maafw.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.window.DialogProperties

/**
 * 三按钮提示弹窗：确认 / 中间出口 / 关闭
 *
 * [neutralText] 用于「换条路走」这类出口（如 Shizuku 引导里的切 Root），
 * M3 的 AlertDialog 只有 confirm 与 dismiss 两槽，中间出口挤在 dismiss 槽里
 */
@Composable
fun MaaPromptDialog(
    title: String,
    message: String,
    icon: ImageVector,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
    dismissText: String? = null,
    neutralText: String? = null,
    onNeutralClick: () -> Unit = {},
    dismissOnOutsideClick: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = { if (dismissOnOutsideClick) onDismissRequest() },
        properties = DialogProperties(
            dismissOnBackPress = dismissOnOutsideClick,
            dismissOnClickOutside = dismissOnOutsideClick,
        ),
        icon = { Icon(imageVector = icon, contentDescription = null) },
        title = { Text(title) },
        text = { Text(message, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmText) }
        },
        dismissButton = {
            if (neutralText != null || dismissText != null) {
                Row {
                    neutralText?.let {
                        TextButton(onClick = onNeutralClick) { Text(it) }
                    }
                    dismissText?.let {
                        TextButton(onClick = onDismissRequest) { Text(it) }
                    }
                }
            }
        },
    )
}
