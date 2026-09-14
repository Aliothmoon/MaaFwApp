package com.aliothmoon.maafw.ui.home

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.core.net.toUri
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.i18n.asString
import com.aliothmoon.maafw.settings.SettingsIntent
import com.aliothmoon.maafw.settings.UpdatePanelState
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.ui.components.ITextFieldWithFocus
import com.aliothmoon.maafw.ui.components.MaaChoiceChip
import com.aliothmoon.maafw.ui.components.MaaInfoRow
import com.aliothmoon.maafw.ui.components.MaaOutlinedButton
import com.aliothmoon.maafw.ui.components.updateSourceLabel
import com.aliothmoon.maafw.update.UpdateChannel
import com.aliothmoon.maafw.update.UpdateCheckResult
import com.aliothmoon.maafw.update.UpdateSource
import timber.log.Timber

private const val MIRRORCHYAN_SITE = "https://mirrorchyan.com/"

/** 概览行左侧标签；不用 MaaFieldLabel（组标题档），颜色跟 MaaInfoRow 的 onSurface 一致 */
@Composable
private fun UpdateRowLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/**
 * 概览卡里的更新区块（无独立卡头）：源/渠道/CDK 选择与检查、下载入口都在这；
 * 启动自检与自动下载两个开关留在设置页的更新卡
 */
@Composable
internal fun UpdateSection(
    update: UpdatePanelState,
    onSettingsIntent: (SettingsIntent) -> Unit,
) {
    val settingsEnabled = !update.downloading
    var mirrorInfoVisible by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xs),
    ) {
        UpdateRowLabel(stringResource(R.string.settings_update_source))
        IconButton(
            onClick = { mirrorInfoVisible = true },
            modifier = Modifier.size(MaaDesignTokens.IconContainer.sm),
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = stringResource(R.string.settings_update_mirror_about),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(MaaDesignTokens.IconSize.sm),
            )
        }
        Spacer(Modifier.weight(1f))
        UpdateSource.entries.forEach { source ->
            MaaChoiceChip(
                label = source.updateSourceLabel(),
                selected = update.updateSource == source,
                enabled = settingsEnabled,
                role = Role.RadioButton,
                onClick = { onSettingsIntent(SettingsIntent.SetUpdateSource(source)) },
            )
        }
    }
    if (update.updateSource == UpdateSource.MIRRORCHYAN) {
        CdkInputBlock(update, settingsEnabled, onSettingsIntent)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xs),
    ) {
        UpdateRowLabel(stringResource(R.string.settings_update_channel))
        Spacer(Modifier.weight(1f))
        listOf(
            UpdateChannel.STABLE to stringResource(R.string.settings_update_channel_stable),
            UpdateChannel.BETA to stringResource(R.string.settings_update_channel_beta),
        ).forEach { (channel, label) ->
            MaaChoiceChip(
                label = label,
                selected = update.channel == channel,
                enabled = settingsEnabled,
                role = Role.RadioButton,
                onClick = { onSettingsIntent(SettingsIntent.SetUpdateChannel(channel)) },
            )
        }
    }
    UpdateStatus(update)
    if (update.downloading) {
        DownloadProgressRow(update, onSettingsIntent)
    } else {
        MaaOutlinedButton(
            onClick = { onSettingsIntent(SettingsIntent.CheckUpdate) },
            enabled = !update.checking,
            modifier = Modifier.fillMaxWidth(),
            // 描边款 + 主色内容；整块填充在概览卡里太重
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.primary,
            ),
        ) {
            // 检查中图标位换转圈；颜色跟 LocalContentColor 才能吃到禁用态透明度
            // （strokeWidth 与 HomeScreen 的行内转圈同档）
            if (update.checking) {
                CircularProgressIndicator(
                    modifier = Modifier.size(MaaDesignTokens.IconSize.md),
                    strokeWidth = MaaDesignTokens.Border.marker,
                    color = LocalContentColor.current,
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(MaaDesignTokens.IconSize.md),
                )
            }
            Spacer(Modifier.width(MaaDesignTokens.Spacing.sm))
            Text(
                text = stringResource(
                    if (update.checking) R.string.settings_update_checking
                    else R.string.settings_update_check,
                ),
            )
        }
    }
    if (mirrorInfoVisible) {
        MirrorInfoDialog(onDismiss = { mirrorInfoVisible = false })
    }
}

/**
 * 对齐 MaaMeow 的 CdkInputField：常驻标题行兼折叠开关。
 * 填过之后默认收起——输入框尾部的清除键太容易误触；用户手动展开后以其选择为准
 */
@Composable
private fun CdkInputBlock(
    update: UpdatePanelState,
    settingsEnabled: Boolean,
    onSettingsIntent: (SettingsIntent) -> Unit,
) {
    val context = LocalContext.current
    var cdkVisible by remember { mutableStateOf(false) }
    var userExpanded by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val expanded = userExpanded ?: update.mirrorchyanCdk.isEmpty()
    val toggleLabel = stringResource(
        if (expanded) R.string.common_collapse_action else R.string.common_expand_action,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = toggleLabel) {
                // 收起时把明文一并藏回去
                if (expanded) cdkVisible = false
                userExpanded = !expanded
            },
        horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UpdateRowLabel(stringResource(R.string.settings_update_mirror_cdk))
        Spacer(Modifier.weight(1f))
        // 收起时才提示填没填，展开后输入框自己会说；贴着箭头放，对齐 MaaMeow
        if (!expanded) {
            Text(
                text = stringResource(
                    if (update.mirrorchyanCdk.isEmpty()) R.string.settings_update_cdk_unset
                    else R.string.settings_update_cdk_saved,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
            contentDescription = toggleLabel,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(MaaDesignTokens.IconSize.md),
        )
    }
    AnimatedVisibility(
        visible = expanded,
        enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
        exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
    ) {
        ITextFieldWithFocus(
            value = update.mirrorchyanCdk,
            onValueChange = { onSettingsIntent(SettingsIntent.SetMirrorchyanCdk(it)) },
            onFocusLost = {},
            enabled = settingsEnabled,
            placeholder = stringResource(R.string.settings_update_cdk_placeholder),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation = if (cdkVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                Row {
                    if (update.mirrorchyanCdk.isNotEmpty()) {
                        IconButton(
                            enabled = settingsEnabled,
                            onClick = { onSettingsIntent(SettingsIntent.SetMirrorchyanCdk("")) },
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = stringResource(R.string.settings_update_cdk_clear),
                            )
                        }
                    }
                    IconButton(onClick = { cdkVisible = !cdkVisible }) {
                        Icon(
                            imageVector = if (cdkVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = stringResource(
                                if (cdkVisible) R.string.settings_update_cdk_hide
                                else R.string.settings_update_cdk_show,
                            ),
                        )
                    }
                }
            },
        )
    }
    // 订阅入口单独一行放输入框下面；收起时也保留——没 CDK 的人往往停在收起态；
    // 不用 TextButton，它的 40dp 最小高度会把这行撑得太高
    Text(
        text = stringResource(R.string.settings_update_cdk_subscribe),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.clickable(role = Role.Button) {
            val intent = Intent(Intent.ACTION_VIEW, MIRRORCHYAN_SITE.toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
                .onFailure { Timber.w(it, "No activity handles the MirrorChyan link") }
        },
    )
}

/** Mirror酱 付费服务说明；正文里的品牌名带官网链接 */
@Composable
private fun MirrorInfoDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val appLabel = remember(context) {
        context.applicationInfo.loadLabel(context.packageManager).toString()
    }
    val cdkDesc = stringResource(R.string.settings_update_mirror_cdk_desc, appLabel)
    val mirrorBrand = stringResource(R.string.settings_update_source_mirror)
    val linkColor = MaterialTheme.colorScheme.primary
    val cdkDescLinked = remember(cdkDesc, mirrorBrand, linkColor) {
        buildAnnotatedString {
            append(cdkDesc)
            val start = cdkDesc.indexOf(mirrorBrand)
            if (start >= 0) {
                addLink(
                    LinkAnnotation.Url(
                        MIRRORCHYAN_SITE,
                        TextLinkStyles(style = SpanStyle(color = linkColor)),
                    ),
                    start,
                    start + mirrorBrand.length,
                )
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(mirrorBrand) },
        text = {
            Text(
                text = cdkDescLinked,
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_confirm))
            }
        },
    )
}

@Composable
private fun DownloadProgressRow(
    update: UpdatePanelState,
    onSettingsIntent: (SettingsIntent) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
    ) {
        if (update.totalBytes > 0) {
            LinearProgressIndicator(
                progress = { update.downloadedBytes.toFloat() / update.totalBytes },
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${update.downloadedBytes.coerceAtLeast(0L) * 100 / update.totalBytes}%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            // 总长未知：不确定进度条，没有可算的百分比
            LinearProgressIndicator(modifier = Modifier.weight(1f))
        }
        IconButton(
            onClick = { onSettingsIntent(SettingsIntent.CancelDownload) },
            modifier = Modifier.size(MaaDesignTokens.IconContainer.sm),
        ) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = stringResource(R.string.settings_update_cancel_download),
                modifier = Modifier.size(MaaDesignTokens.IconSize.sm),
            )
        }
    }
}

@Composable
private fun UpdateStatus(update: UpdatePanelState) {
    when (update.checkResult) {
        is UpdateCheckResult.UpdateAvailable -> Unit
        is UpdateCheckResult.SourceFailed -> Unit

        is UpdateCheckResult.UpToDate -> MaaInfoRow(
            label = stringResource(R.string.settings_update_result),
            value = stringResource(R.string.settings_update_up_to_date),
        )

        null -> Unit
    }
    update.errorMessage?.let {
        Text(
            text = it.asString(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}
