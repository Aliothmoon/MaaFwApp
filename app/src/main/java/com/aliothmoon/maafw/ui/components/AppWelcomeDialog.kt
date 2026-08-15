package com.aliothmoon.maafw.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.aliothmoon.maafw.R

/** MaaFwApp 自身的首次启动欢迎；与 PI 的 interface.welcome 无关。 */
@Composable
fun AppWelcomeDialog(
    visible: Boolean,
    onConfirm: () -> Unit,
) {
    if (!visible) return
    val context = LocalContext.current
    val appLabel = remember(context) {
        context.applicationInfo.loadLabel(context.packageManager).toString()
    }
    MaaPromptDialog(
        title = stringResource(R.string.app_welcome_title, appLabel),
        message = stringResource(R.string.app_welcome_message),
        icon = Icons.Outlined.Info,
        confirmText = stringResource(R.string.common_got_it),
        onConfirm = onConfirm,
        onDismissRequest = {},
    )
}
