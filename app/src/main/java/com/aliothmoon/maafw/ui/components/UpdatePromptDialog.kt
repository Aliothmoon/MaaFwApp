package com.aliothmoon.maafw.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
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
 * 「发现新版本」弹窗；[UpdatePanelState.updatePrompt] 非空才渲染。
 * 下载走用户所选更新源，Mirror酱 缺 CDK 由下载前置拦截（CDK_REQUIRED）弹「更新失败」
 */
@Composable
fun UpdatePromptDialog(
    update: UpdatePanelState,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
) {
    val prompt = update.updatePrompt ?: return
    // 没有更新说明时整个正文槽给 null：给个空 lambda 的话 AlertDialog 照样把那段留白撑出来
    val releaseNotes: (@Composable () -> Unit)? =
        prompt.info.releaseNotes?.takeIf(String::isNotBlank)?.let { notes ->
            {
                Column(
                    modifier = Modifier
                        .heightIn(max = ReleaseNotesMaxHeight)
                        .verticalScroll(rememberScrollState()),
                ) {
                    MaaMarkdown(
                        text = notes,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
            ) {
                Icon(
                    imageVector = Icons.Outlined.SystemUpdate,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(MaaDesignTokens.IconSize.md),
                )
                Text(
                    text = "${stringResource(R.string.dialog_update_found_title)} ${prompt.info.version}",
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        },
        text = releaseNotes,
        confirmButton = {
            TextButton(onClick = onDownload) {
                Text(stringResource(R.string.settings_update_download))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_update_later))
            }
        },
    )
}

@Composable
fun UpdateSource.updateSourceLabel(): String = when (this) {
    UpdateSource.MIRRORCHYAN -> stringResource(R.string.settings_update_source_mirror)
    UpdateSource.GITHUB -> stringResource(R.string.settings_update_source_github)
}
