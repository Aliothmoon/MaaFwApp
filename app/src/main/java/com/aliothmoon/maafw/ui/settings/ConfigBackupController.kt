package com.aliothmoon.maafw.ui.settings

import android.content.ActivityNotFoundException
import android.content.res.Resources
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.config.backup.ConfigApplyResult
import com.aliothmoon.maafw.config.backup.ConfigBackupService
import com.aliothmoon.maafw.config.backup.ConfigImport
import com.aliothmoon.maafw.config.backup.ConfigImportError
import com.aliothmoon.maafw.config.backup.ConfigReadResult
import com.aliothmoon.maafw.config.backup.MxuSkipped
import com.aliothmoon.maafw.domain.RunConfiguration
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.theme.OpaqueTheme
import com.aliothmoon.maafw.ui.components.ITextField
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** 设置页「备份与恢复」卡上的三个入口 */
enum class ConfigBackupAction { Export, Import, PasteShareCode }

/**
 * 配置导出、导入：SAF 选文件、粘贴分享码、导入前的预览确认
 *
 * 与 LogExportController 同理，必须无条件挂在调用方的组合顶层，SAF launcher 的注册才稳定。
 * [action] 由设置页的入口置位，这里消费后经 [onActionConsumed] 清回 null
 */
@Composable
fun ConfigBackupController(
    action: ConfigBackupAction?,
    onActionConsumed: () -> Unit,
    onMessage: (String) -> Unit,
    service: ConfigBackupService = koinInject(),
) {
    val scope = rememberCoroutineScope()
    // 回调里取文案用：LocalContext 不随语言切换失效，Resources 会
    val resources = LocalResources.current
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<ConfigImport?>(null) }
    var pasteVisible by remember { mutableStateOf(false) }

    fun handle(result: ConfigReadResult) {
        when (result) {
            is ConfigReadResult.Ready -> pending = result.import
            is ConfigReadResult.Failed -> onMessage(resources.importError(result.error))
            ConfigReadResult.Unreadable -> onMessage(resources.getString(R.string.config_import_unreadable))
            ConfigReadResult.ProjectNotReady -> onMessage(resources.getString(R.string.config_import_not_ready))
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(MIME_JSON),
    ) { uri ->
        if (uri == null) {
            busy = false
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val name = service.exportTo(uri)
            busy = false
            onMessage(
                name?.let { resources.getString(R.string.config_export_saved, it) }
                    ?: resources.getString(R.string.config_export_failed),
            )
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) {
            busy = false
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val result = service.read(uri)
            busy = false
            handle(result)
        }
    }

    // 有的精简 ROM 把系统文件选择器删了，launch 直接抛
    fun launchPicker(launch: () -> Unit) {
        busy = true
        try {
            launch()
        } catch (e: ActivityNotFoundException) {
            busy = false
            onMessage(resources.getString(R.string.config_picker_unavailable))
        }
    }

    LaunchedEffect(action) {
        val current = action ?: return@LaunchedEffect
        onActionConsumed()
        if (busy) return@LaunchedEffect
        when (current) {
            ConfigBackupAction.Export -> launchPicker { exportLauncher.launch(service.suggestedFileName()) }
            // 按内容认格式；MXU 的 txt、各家文件管理器给 json 的 MIME 都不统一，不按类型过滤
            ConfigBackupAction.Import -> launchPicker { importLauncher.launch(arrayOf("*/*")) }
            ConfigBackupAction.PasteShareCode -> pasteVisible = true
        }
    }

    if (pasteVisible) {
        ShareCodeDialog(
            onDismiss = { pasteVisible = false },
            onSubmit = { text ->
                pasteVisible = false
                scope.launch { handle(service.parseText(text)) }
            },
        )
    }

    pending?.let { import ->
        ImportPreviewDialog(
            import = import,
            onDismiss = { pending = null },
            onConfirm = {
                pending = null
                scope.launch {
                    val message = when (service.apply(import)) {
                        ConfigApplyResult.Applied -> R.string.config_import_done
                        ConfigApplyResult.Locked -> R.string.msg_locked_while_running
                        ConfigApplyResult.ProjectNotReady -> R.string.config_import_not_ready
                        ConfigApplyResult.Failed -> R.string.config_import_failed
                    }
                    onMessage(resources.getString(message))
                }
            },
        )
    }
}

@Composable
private fun ShareCodeDialog(onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    var text by remember { mutableStateOf("") }
    OpaqueTheme {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.config_import_share_code_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm)) {
                    Text(
                        text = stringResource(R.string.config_import_share_code_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ITextField(
                        value = text,
                        onValueChange = { text = it },
                        placeholder = stringResource(R.string.config_share_code_hint),
                        singleLine = false,
                        // 分享码是一长串，撑开会把按钮挤出屏幕
                        modifier = Modifier.heightIn(max = 160.dp),
                    )
                    TextButton(
                        onClick = {
                            scope.launch {
                                val clip = clipboard.getClipEntry()?.clipData
                                val pasted = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
                                if (!pasted.isNullOrBlank()) text = pasted
                            }
                        },
                    ) { Text(stringResource(R.string.config_share_code_paste)) }
                }
            },
            confirmButton = {
                TextButton(onClick = { onSubmit(text) }, enabled = text.isNotBlank()) {
                    Text(stringResource(R.string.config_share_code_parse))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
            },
        )
    }
}

@Composable
private fun ImportPreviewDialog(import: ConfigImport, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    OpaqueTheme {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.config_import_preview_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
                ) {
                    when (import) {
                        is ConfigImport.Restore -> RestorePreview(import)
                        is ConfigImport.FromMxu -> MxuPreview(import)
                    }
                    Muted(
                        stringResource(
                            if (import is ConfigImport.Restore) R.string.config_import_secrets_kept
                            else R.string.config_import_mxu_secrets_kept,
                        ),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = onConfirm) {
                    Text(
                        stringResource(
                            if (import is ConfigImport.Restore) R.string.config_import_confirm_restore
                            else R.string.config_import_confirm_append,
                        ),
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
            },
        )
    }
}

@Composable
private fun RestorePreview(import: ConfigImport.Restore) {
    val backup = import.backup
    val exportedAt = DateUtils.formatDateTime(
        // 带时刻时要按系统 12/24 小时制排，传 null 会空指针
        LocalContext.current,
        backup.exportedAt,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR,
    )
    val project = listOfNotNull(backup.project.name, backup.project.version).joinToString(" ")
    Text(stringResource(R.string.config_import_source_backup, project, exportedAt))
    Warning(stringResource(R.string.config_import_restore_warning))
    Text(
        stringResource(
            R.string.config_import_restore_counts,
            backup.configuration.configurations.size,
            backup.schedules.size,
            backup.appSettings.size,
        ),
    )
    ConfigurationList(backup.configuration.configurations)
}

@Composable
private fun MxuPreview(import: ConfigImport.FromMxu) {
    val result = import.result
    Text(
        stringResource(
            when (import.source) {
                ConfigImport.MxuSource.ConfigFile -> R.string.config_import_source_mxu_file
                ConfigImport.MxuSource.ShareCode -> R.string.config_import_source_mxu_share
            },
        ),
    )
    Warning(stringResource(R.string.config_import_append_warning))
    ConfigurationList(result.configurations)
    if (result.schedules.isNotEmpty()) {
        Text(stringResource(R.string.config_import_schedules_disabled, result.schedules.size))
    }
    SkippedList(result.skipped)
}

@Composable
private fun ConfigurationList(configurations: List<RunConfiguration>) {
    configurations.forEach {
        Bullet(stringResource(R.string.config_import_configuration_item, it.name, it.tasks.size))
    }
}

@Composable
private fun SkippedList(skipped: MxuSkipped) {
    val lines = buildList {
        if (skipped.unknownTasks.isNotEmpty()) {
            add(stringResource(R.string.config_import_skipped_unknown_tasks, skipped.unknownTasks.joinToString(stringResource(R.string.config_import_name_separator))))
        }
        if (skipped.mxuTasks > 0) add(stringResource(R.string.config_import_skipped_mxu_tasks, skipped.mxuTasks))
        if (skipped.preActions > 0) add(stringResource(R.string.config_import_skipped_pre_actions, skipped.preActions))
        if (skipped.options > 0) add(stringResource(R.string.config_import_skipped_options, skipped.options))
        if (skipped.schedules > 0) add(stringResource(R.string.config_import_skipped_schedules, skipped.schedules))
    }
    if (lines.isEmpty()) return
    Text(stringResource(R.string.config_import_skipped_title), fontWeight = FontWeight.Medium)
    lines.forEach { Bullet(it) }
}

@Composable
private fun Warning(text: String) {
    Text(text = text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun Muted(text: String) {
    Text(text = text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun Bullet(text: String) {
    Text(text = "· $text", style = MaterialTheme.typography.bodyMedium)
}

private fun Resources.importError(error: ConfigImportError): String = when (error) {
    ConfigImportError.Unrecognized -> getString(R.string.config_import_unrecognized)
    is ConfigImportError.ProjectMismatch -> getString(R.string.config_import_project_mismatch, error.source, error.current)
    ConfigImportError.NewerFormat -> getString(R.string.config_import_newer)
    ConfigImportError.UnsupportedShareCode -> getString(R.string.config_import_share_code_version)
    ConfigImportError.Empty -> getString(R.string.config_import_empty)
}

private const val MIME_JSON = "application/json"
