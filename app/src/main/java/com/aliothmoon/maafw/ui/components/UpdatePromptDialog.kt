package com.aliothmoon.maafw.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.settings.UpdatePanelState
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.update.UpdateSource

/** 更新日志区的限高；超出转为内部滚动，不让长日志把按钮挤出屏幕 */
private val ReleaseNotesMaxHeight = 280.dp

/**
 * 「发现新版本」弹窗：启动自检与手动检查发现新版本都从这里走
 *
 * [UpdatePanelState.updatePrompt] 非空才渲染；下载走用户所选更新源，
 * Mirror酱 源缺 CDK 由下载前置拦截（CDK_REQUIRED）弹错误
 */
@Composable
fun UpdatePromptDialog(
    update: UpdatePanelState,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
) {
    val prompt = update.updatePrompt ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_update_found_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm)) {
                Text(
                    text = "${prompt.info.version} · ${prompt.source.updateSourceLabel()}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                prompt.info.releaseNotes?.takeIf(String::isNotBlank)?.let { notes ->
                    Column(
                        modifier = Modifier
                            .heightIn(max = ReleaseNotesMaxHeight)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        MaaMarkdown(text = notes)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDownload) {
                Text(stringResource(R.string.settings_update_download))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_update_ignore))
            }
        },
    )
}

@Composable
fun UpdateSource.updateSourceLabel(): String = when (this) {
    UpdateSource.MIRRORCHYAN -> stringResource(R.string.settings_update_source_mirror)
    UpdateSource.GITHUB -> stringResource(R.string.settings_update_source_github)
}
