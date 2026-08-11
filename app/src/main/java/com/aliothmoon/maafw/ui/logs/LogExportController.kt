package com.aliothmoon.maafw.ui.logs

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.log.LogExportService
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.ui.components.MaaModalSheet
import com.aliothmoon.maafw.ui.components.MaaSheetHeader
import com.aliothmoon.maafw.ui.components.maaClickable
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * 导出日志：sheet 显隐、SAF 选位置、分享 Intent、重复点击保护
 *
 * 必须无条件挂在调用方的组合顶层——`rememberLauncherForActivityResult` 的注册要稳定，
 * 跟着 sheet 的显隐一起装卸的话，回调回来时注册已经没了
 *
 * 反馈经 [onMessage] 交给调用方：提示该显示在哪（snackbar / toast）由承载它的那一层决定
 */
@Composable
fun LogExportController(
    visible: Boolean,
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit,
    service: LogExportService = koinInject(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }

    val runningText = stringResource(R.string.log_export_running)
    val failedText = stringResource(R.string.log_export_failed)
    val chooserTitle = stringResource(R.string.log_export_title)

    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(MIME_ZIP),
    ) { uri ->
        if (uri == null) {
            busy = false
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val name = service.exportTo(uri)
            busy = false
            onMessage(name?.let { context.getString(R.string.log_export_saved, it) } ?: failedText)
        }
    }

    if (!visible) return

    MaaModalSheet(onDismiss = onDismiss) { modifier ->
        Column(modifier = modifier) {
            MaaSheetHeader(title = stringResource(R.string.log_export_title), onClose = onDismiss)
            ExportAction(
                label = stringResource(R.string.log_export_share),
                icon = Icons.Outlined.Share,
                onClick = {
                    onDismiss()
                    if (busy) return@ExportAction
                    busy = true
                    onMessage(runningText)
                    scope.launch {
                        val intent = service.shareIntent()
                        busy = false
                        if (intent == null) onMessage(failedText)
                        else context.startActivity(Intent.createChooser(intent, chooserTitle))
                    }
                },
            )
            ExportAction(
                label = stringResource(R.string.log_export_save),
                icon = Icons.Outlined.Save,
                onClick = {
                    onDismiss()
                    if (busy) return@ExportAction
                    busy = true
                    onMessage(runningText)
                    // 打包推迟到用户选完位置：选一半退出去就白打了
                    saveLauncher.launch(service.suggestedFileName())
                },
            )
        }
    }
}

@Composable
private fun ExportAction(label: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .maaClickable(onClick = onClick)
            .padding(vertical = MaaDesignTokens.Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.md),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(MaaDesignTokens.IconSize.md),
        )
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}

private const val MIME_ZIP = "application/zip"
