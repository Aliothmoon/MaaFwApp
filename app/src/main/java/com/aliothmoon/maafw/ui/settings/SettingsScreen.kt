package com.aliothmoon.maafw.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.core.net.toUri
import com.aliothmoon.maafw.BuildConfig
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.domain.RemoteBackend
import com.aliothmoon.maafw.domain.ThemeMode
import com.aliothmoon.maafw.i18n.AppLocales
import com.aliothmoon.maafw.i18n.asString
import com.aliothmoon.maafw.runner.ResolutionPreference
import com.aliothmoon.maafw.session.SessionIntent
import com.aliothmoon.maafw.session.SessionUiState
import com.aliothmoon.maafw.settings.SettingsIntent
import com.aliothmoon.maafw.settings.SettingsUiState
import com.aliothmoon.maafw.settings.UpdatePanelState
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.theme.ThemeStyle
import com.aliothmoon.maafw.ui.components.ITextFieldWithFocus
import com.aliothmoon.maafw.ui.components.MaaButton
import com.aliothmoon.maafw.ui.components.MaaCard
import com.aliothmoon.maafw.ui.components.MaaDescriptionPanel
import com.aliothmoon.maafw.ui.components.MaaFieldLabel
import com.aliothmoon.maafw.ui.components.MaaInfoRow
import com.aliothmoon.maafw.ui.components.MaaLabeledControlRow
import com.aliothmoon.maafw.ui.components.MaaMarkdown
import com.aliothmoon.maafw.ui.components.MaaMarkdownSheet
import com.aliothmoon.maafw.ui.components.MaaNavigationRow
import com.aliothmoon.maafw.ui.components.MaaSemanticOutlinedButton
import com.aliothmoon.maafw.ui.components.MaaSingleChoiceFlow
import com.aliothmoon.maafw.ui.components.MaaSwitch
import com.aliothmoon.maafw.ui.components.MaaSwitchRow
import com.aliothmoon.maafw.ui.components.updateSourceLabel
import com.aliothmoon.maafw.ui.options.OptionEditorList
import com.aliothmoon.maafw.ui.pip.PipController
import com.aliothmoon.maafw.update.UpdateChannel
import com.aliothmoon.maafw.update.UpdateCheckResult
import com.aliothmoon.maafw.update.UpdateSource
import timber.log.Timber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SessionUiState,
    onIntent: (SessionIntent) -> Unit,
    settingsState: SettingsUiState,
    onSettingsIntent: (SettingsIntent) -> Unit,
    // 二级页面与 SAF 都需要 Activity 宿主，导航与弹窗归 AppRoot 那一层
    onOpenRunLogArchive: () -> Unit,
    onOpenAppLog: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onExportLogs: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    text = stringResource(R.string.nav_settings),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            },
            windowInsets = WindowInsets(0, 0, 0, 0),
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onBackground,
                actionIconContentColor = MaterialTheme.colorScheme.primary,
            ),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                // 主 tab 里唯一有行内输入框的一页，键盘只压这一列：底栏不动，另外三个 tab 也不必每帧重测
                // 必须排在 verticalScroll 之前，排后面只是给内容尾部垫一段，视口照旧被键盘盖住
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = MaaDesignTokens.Spacing.lg,
                    end = MaaDesignTokens.Spacing.lg,
                    top = MaaDesignTokens.Spacing.sm,
                    bottom = MaaDesignTokens.Spacing.lg,
                ),
            verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.md),
        ) {
            UpdateCard(settingsState, onSettingsIntent)
            GlobalOptionCard(state, onIntent)
            ResourceOptionCard(state, onIntent)
            DisplayCard(state, onIntent)
            ScheduleCard(state, onIntent)
            NotificationCard(onOpenNotificationSettings)
            LogCard(state, onIntent, onOpenRunLogArchive, onOpenAppLog, onExportLogs)
            PiCard(onIntent)
            OtherCard(state, settingsState, onIntent, onSettingsIntent)
            AboutCard(state)
        }
    }
}

/**
 * 落在设置页而不是任务页：`global_option` 对所有运行配置生效
 *
 * 卡名取「任务设置」而非「全局选项」：对齐 MXU 同名分区，且「全局」在用户侧不指称什么
 */
@Composable
private fun GlobalOptionCard(state: SessionUiState, onIntent: (SessionIntent) -> Unit) {
    if (state.globalOptions.isEmpty()) return
    MaaCard(title = stringResource(R.string.settings_section_global_option), collapsible = true) {
        OptionEditorList(
            options = state.globalOptions,
            locked = state.configurationLocked,
            onSetOption = { name, value -> onIntent(SessionIntent.SetGlobalOption(name, value)) },
        )
    }
}

/**
 * 落在设置页而不是首页资源选择：`resource.option` 对所有运行配置生效，只随当前资源换一份
 *
 * 卡名取「资源设置」：和「任务设置」并列，值按 resource name 分桶
 */
@Composable
private fun ResourceOptionCard(state: SessionUiState, onIntent: (SessionIntent) -> Unit) {
    if (state.resourceOptions.isEmpty()) return
    val resourceName = state.environment?.resource?.name ?: return
    MaaCard(title = stringResource(R.string.settings_section_resource_option), collapsible = true) {
        OptionEditorList(
            options = state.resourceOptions,
            locked = state.configurationLocked,
            onSetOption = { name, value ->
                onIntent(SessionIntent.SetResourceOption(resourceName, name, value))
            },
        )
    }
}

/** 主题、主题风格、语言：三组都只改观感，合成一张卡（对齐 MaaMeow 的「显示设置」） */
@Composable
private fun DisplayCard(state: SessionUiState, onIntent: (SessionIntent) -> Unit) {
    MaaCard(title = stringResource(R.string.settings_section_display), collapsible = true) {
        MaaFieldLabel(stringResource(R.string.settings_theme))
        val modes = listOf(
            ThemeMode.System to stringResource(R.string.settings_follow_system),
            ThemeMode.Light to stringResource(R.string.settings_theme_light),
            ThemeMode.Dark to stringResource(R.string.settings_theme_dark),
        )
        MaaSingleChoiceFlow(
            options = modes,
            selected = state.themeMode,
            onSelect = { onIntent(SessionIntent.SetThemeMode(it)) },
        )
        Spacer(Modifier.height(MaaDesignTokens.Spacing.sm))
        MaaFieldLabel(stringResource(R.string.settings_theme_style))
        val styles = listOf(
            ThemeStyle.DEFAULT to stringResource(R.string.settings_theme_style_default),
            ThemeStyle.SEMI_DESIGN to stringResource(R.string.settings_theme_style_semi),
        )
        MaaSingleChoiceFlow(
            options = styles,
            selected = state.themeStyle,
            onSelect = { onIntent(SessionIntent.SetThemeStyle(it)) },
        )
        Spacer(Modifier.height(MaaDesignTokens.Spacing.sm))
        MaaFieldLabel(stringResource(R.string.settings_language))
        LanguageChoice(state, onIntent)
    }
}

@Composable
private fun ColumnScope.LanguageChoice(state: SessionUiState, onIntent: (SessionIntent) -> Unit) {
    // 事实来源在平台侧 per-app locale（AppLocales），不进 UserConfiguration；
    // 切换后 Activity 重建，本处在新组合中重新读取，无需观察流
    // 语言名按惯例保持本族语原文，不随界面语言翻译
    val options = listOf<Pair<String?, String>>(
        null to stringResource(R.string.settings_follow_system),
        "zh-CN" to "简体中文",
        "en" to "English",
    )
    // 选中态用本地 state 立即回显：切到效果相同的档位（如 跟随系统(中文) ↔ 简体中文）
    // 不触发 Activity 重建，重新读 AppLocales 的时机不会到来
    var selectedTag by remember {
        mutableStateOf(
            when (val tag = AppLocales.currentTag()) {
                null -> null
                else -> if (tag.startsWith("zh")) "zh-CN" else "en"
            },
        )
    }
    MaaSingleChoiceFlow(
        options = options,
        selected = selectedTag,
        enabled = !state.configurationLocked,
        // 重复点选当前档位不发 Intent：避免无意义的 Activity 重建闪屏
        onSelect = { tag ->
            if (tag != selectedTag) {
                selectedTag = tag
                onIntent(SessionIntent.SetLanguage(tag))
            }
        },
    )
    Text(
        text = stringResource(R.string.settings_language_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 定时任务的亮屏解锁（对齐 MaaMeow 的「定时任务解锁方式」）
 *
 * 只有解锁是全局的：熄屏、关应用、倒计时、强制启动都是**逐条规则**的，
 * 落在定时编辑页的「高级选项」里，不在这一页
 *
 * 不进 `configurationLocked`：改的是下一轮的事，挂载物的条件在 engage 时就冻结了
 */
@Composable
private fun ScheduleCard(state: SessionUiState, onIntent: (SessionIntent) -> Unit) {
    MaaCard(title = stringResource(R.string.settings_section_schedule), collapsible = true) {
        MaaLabeledControlRow(
            label = stringResource(R.string.settings_wake_unlock),
            trailing = {
                MaaSwitch(
                    checked = state.wakeUnlockEnabled,
                    onCheckedChange = { onIntent(SessionIntent.SetWakeUnlockEnabled(it)) },
                )
            },
        )
        if (state.wakeUnlockEnabled) {
            // 只收数字：注入按键只打得出 0-9，图案与密码锁屏的面板模拟不出来
            ITextFieldWithFocus(
                value = state.wakeCredential,
                onValueChange = { onIntent(SessionIntent.SetWakeCredential(it)) },
                onFocusLost = {},
                label = stringResource(R.string.settings_wake_credential),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                inputFilter = { it.all(Char::isDigit) },
                supportingText = {
                    Text(
                        text = stringResource(R.string.settings_wake_credential_hint),
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
            )
        }
    }
}

/** 只是入口；档位与渠道都在二级页面里改，不参与运行锁定 */
@Composable
private fun NotificationCard(onOpen: () -> Unit) {
    MaaCard(title = stringResource(R.string.settings_section_notification), collapsible = true) {
        MaaNavigationRow(
            label = stringResource(R.string.notification_settings_title),
            description = stringResource(R.string.settings_notification_desc),
            onClick = onOpen,
        )
    }
}

/**
 * 三个入口都是「离开这一页」，不带任何开关，因此不参与运行锁定
 *
 * 调试模式并在这里（对齐 MaaMeow）：它管的就是日志详略，跟运行行为无关
 */
@Composable
private fun LogCard(
    state: SessionUiState,
    onIntent: (SessionIntent) -> Unit,
    onOpenRunLogArchive: () -> Unit,
    onOpenAppLog: () -> Unit,
    onExportLogs: () -> Unit,
) {
    var showEnableConfirm by remember { mutableStateOf(false) }
    var showCleanupConfirm by remember { mutableStateOf(false) }
    MaaCard(title = stringResource(R.string.settings_section_log), collapsible = true) {
        MaaNavigationRow(
            label = stringResource(R.string.log_archive_title),
            description = stringResource(R.string.settings_log_archive_desc),
            onClick = onOpenRunLogArchive,
        )
        MaaNavigationRow(
            label = stringResource(R.string.app_log_title),
            description = stringResource(R.string.settings_log_error_desc),
            onClick = onOpenAppLog,
        )
        MaaNavigationRow(
            label = stringResource(R.string.log_export_title),
            description = stringResource(R.string.settings_log_export_desc),
            onClick = onExportLogs,
        )
        MaaSemanticOutlinedButton(
            onClick = { showCleanupConfirm = true },
            enabled = !state.configurationLocked,
            semantic = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .fillMaxWidth()
                .height(MaaDesignTokens.ButtonHeight.prominent),
        ) {
            Icon(
                imageVector = Icons.Outlined.DeleteOutline,
                contentDescription = null,
                modifier = Modifier.size(MaaDesignTokens.IconSize.md),
            )
            Box(Modifier.size(MaaDesignTokens.Spacing.sm))
            Text(stringResource(R.string.settings_log_cleanup))
        }
        Text(
            text = stringResource(R.string.settings_log_cleanup_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // 启用走确认弹窗，确认即落盘 + 重启 App（对齐 MaaMeow）；关闭直接关
        MaaLabeledControlRow(
            label = stringResource(R.string.settings_debug_mode),
            trailing = {
                MaaSwitch(
                    checked = state.debugMode,
                    onCheckedChange = { enabled ->
                        if (enabled) showEnableConfirm = true
                        else onIntent(SessionIntent.SetDebugMode(false))
                    },
                )
            },
        )
        Text(
            text = stringResource(R.string.settings_debug_mode_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (showEnableConfirm) {
        AlertDialog(
            onDismissRequest = { showEnableConfirm = false },
            title = { Text(stringResource(R.string.dialog_enable_debug_title)) },
            text = { Text(stringResource(R.string.dialog_enable_debug_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showEnableConfirm = false
                    onIntent(SessionIntent.SetDebugMode(true))
                }) { Text(stringResource(R.string.common_restart)) }
            },
            dismissButton = {
                TextButton(onClick = { showEnableConfirm = false }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
        )
    }
    if (showCleanupConfirm) {
        AlertDialog(
            onDismissRequest = { showCleanupConfirm = false },
            title = { Text(stringResource(R.string.settings_log_cleanup_title)) },
            text = { Text(stringResource(R.string.settings_log_cleanup_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showCleanupConfirm = false
                    onIntent(SessionIntent.ClearDiagnosticData)
                }) { Text(stringResource(R.string.settings_log_cleanup_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showCleanupConfirm = false }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
        )
    }
}

/**
 * 重解 PI 的手动出口
 *
 * 平时只有 versionCode 变了才重解；PI 在仓库外，「只换 PI 没换版本号」就得从这里手动来一次
 * （docs/privileged-runtime.md §9 的已知空档）。运行中由 ViewModel 的 guarded 挡下并给提示
 */
@Composable
private fun PiCard(onIntent: (SessionIntent) -> Unit) {
    var showConfirm by remember { mutableStateOf(false) }
    MaaCard(title = stringResource(R.string.settings_section_pi), collapsible = true) {
        MaaNavigationRow(
            label = stringResource(R.string.pi_reinstall_title),
            description = stringResource(R.string.pi_reinstall_desc),
            onClick = { showConfirm = true },
        )
    }
    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text(stringResource(R.string.dialog_reinstall_pi_title)) },
            text = { Text(stringResource(R.string.dialog_reinstall_pi_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showConfirm = false
                    onIntent(SessionIntent.ReinstallPi)
                }) { Text(stringResource(R.string.dialog_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
        )
    }
}

/** 只剩启动自检与自动下载两个开关；源/渠道/CDK 与检查、下载入口在首页的更新卡 */
@Composable
private fun UpdateCard(
    state: SettingsUiState,
    onSettingsIntent: (SettingsIntent) -> Unit,
) {
    val update = state.update
    MaaCard(title = stringResource(R.string.settings_section_update), collapsible = true) {
        // 下载过程中更新设置锁死，防止改到进行中那一轮的语义；VM 写入口有二次校验
        val settingsEnabled = !update.downloading
        MaaSwitchRow(
            label = stringResource(R.string.settings_update_auto_check),
            checked = update.autoCheckUpdate,
            enabled = settingsEnabled,
            onCheckedChange = { onSettingsIntent(SettingsIntent.SetAutoCheckUpdate(it)) },
        )
        MaaSwitchRow(
            label = stringResource(R.string.settings_update_auto_download),
            checked = update.autoDownloadUpdate,
            enabled = update.autoCheckUpdate && settingsEnabled,
            onCheckedChange = { onSettingsIntent(SettingsIntent.SetAutoDownloadUpdate(it)) },
        )
        Text(
            text = stringResource(R.string.settings_update_auto_download_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}


/**
 * 启动模式与后台模式分辨率：都是「跑起来之前得先定」的环境选项（对齐 MaaMeow 的「其他设置」）
 *
 * 启动模式从首页权限卡搬来——首页只显示当前后端状态，切换归设置页管
 * 两者运行中都禁用：切后端会断开当前特权进程，改分辨率要重建虚拟屏，跑一半时切会丢这一轮
 */
@Composable
private fun OtherCard(
    state: SessionUiState,
    settingsState: SettingsUiState,
    onIntent: (SessionIntent) -> Unit,
    onSettingsIntent: (SettingsIntent) -> Unit,
) {
    val locked = state.configurationLocked
    MaaCard(title = stringResource(R.string.settings_section_other), collapsible = true) {
        MaaFieldLabel(stringResource(R.string.permission_backend))
        MaaSingleChoiceFlow(
            // 对齐 MaaMeow：只列后端名，不展示「可用/不可用」——选哪个都行，可用性交给连接流程判
            options = RemoteBackend.entries.map { it to it.display },
            selected = settingsState.remoteAccess.configuredBackend,
            enabled = !locked,
            onSelect = { onSettingsIntent(SettingsIntent.SetBackend(it)) },
        )
        Spacer(Modifier.height(MaaDesignTokens.Spacing.sm))
        MaaFieldLabel(stringResource(R.string.settings_resolution))
        MaaSingleChoiceFlow(
            options = listOf(
                ResolutionPreference.P720 to stringResource(R.string.settings_resolution_720p),
                ResolutionPreference.P1080 to stringResource(R.string.settings_resolution_1080p),
            ),
            selected = state.resolutionPreference,
            enabled = !locked,
            onSelect = { onIntent(SessionIntent.SetResolutionPreference(it)) },
        )
        if (PipController.isSupported(LocalContext.current)) {
            Spacer(Modifier.height(MaaDesignTokens.Spacing.sm))
            MaaSwitchRow(
                label = stringResource(R.string.settings_pip_on_home),
                checked = settingsState.pipOnHome,
                onCheckedChange = { onSettingsIntent(SettingsIntent.SetPipOnHome(it)) },
            )
            Text(
                text = stringResource(R.string.settings_pip_on_home_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // 开发版 PI 一律不上报（TelemetryController 也照此拦），留个点不动的开关只会让人以为坏了
        if (state.telemetryDeclared && !state.telemetryLockedByVersion) {
            Spacer(Modifier.height(MaaDesignTokens.Spacing.sm))
            MaaSwitchRow(
                label = stringResource(R.string.settings_telemetry),
                checked = state.telemetryEnabled,
                onCheckedChange = { onIntent(SessionIntent.SetTelemetryEnabled(it)) },
            )
            Text(
                text = stringResource(R.string.settings_telemetry_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private data class AboutSheet(val titleRes: Int, val body: String)

@Composable
private fun AboutCard(state: SessionUiState) {
    val context = LocalContext.current
    val metadata = state.projectMetadata
    var sheet by remember { mutableStateOf<AboutSheet?>(null) }
    val appLabel = remember(context) {
        context.applicationInfo.loadLabel(context.packageManager).toString()
    }

    MaaCard(title = stringResource(R.string.settings_about), collapsible = true) {
        MaaInfoRow(stringResource(R.string.settings_project), appLabel)
        // 空串 = 非子模块又没钉版本名，此时它和下面那行同值，不重复显示
        if (BuildConfig.MAFW_PROJECT_VERSION.isNotEmpty()) {
            MaaInfoRow(
                label = stringResource(R.string.settings_version_of, appLabel),
                value = BuildConfig.MAFW_PROJECT_VERSION,
            )
        }
        MaaInfoRow(
            label = stringResource(
                R.string.settings_version_of,
                stringResource(R.string.app_name),
            ),
            value = BuildConfig.MAFW_APP_VERSION,
        )
        if (BuildConfig.MAFW_FRAMEWORK_VERSION.isNotEmpty()) {
            MaaInfoRow(
                label = stringResource(
                    R.string.settings_version_of,
                    stringResource(R.string.settings_framework),
                ),
                value = BuildConfig.MAFW_FRAMEWORK_VERSION,
            )
        }

        metadata.description?.let {
            MaaDescriptionPanel {
                MaaMarkdown(text = it, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
        metadata.contact?.let { body ->
            MaaNavigationRow(
                label = stringResource(R.string.settings_about_contact),
                onClick = { sheet = AboutSheet(R.string.settings_about_contact, body) },
            )
        }
        metadata.license?.let { body ->
            MaaNavigationRow(
                label = stringResource(R.string.settings_about_license),
                onClick = { sheet = AboutSheet(R.string.settings_about_license, body) },
            )
        }
        metadata.github?.let { url ->
            MaaNavigationRow(
                label = stringResource(R.string.settings_about_repository),
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, url.toUri())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(intent) }
                        .onFailure { Timber.w(it, "No activity handles the project repository link") }
                },
            )
        }
    }

    sheet?.let {
        MaaMarkdownSheet(
            title = stringResource(it.titleRes),
            body = it.body,
            onDismiss = { sheet = null },
        )
    }
}
