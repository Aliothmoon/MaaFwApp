package com.aliothmoon.maafw.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import com.aliothmoon.maafw.BuildConfig
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.domain.RemoteBackend
import com.aliothmoon.maafw.runner.ResolutionPreference
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import com.aliothmoon.maafw.domain.ThemeMode
import com.aliothmoon.maafw.i18n.AppLocales
import com.aliothmoon.maafw.session.SessionIntent
import com.aliothmoon.maafw.session.SessionUiState
import com.aliothmoon.maafw.settings.SettingsIntent
import com.aliothmoon.maafw.settings.SettingsUiState
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.theme.ThemeStyle
import com.aliothmoon.maafw.ui.components.MaaCard
import com.aliothmoon.maafw.ui.components.MaaDiagnosticList
import com.aliothmoon.maafw.ui.components.MaaFieldLabel
import com.aliothmoon.maafw.ui.components.MaaInfoRow
import com.aliothmoon.maafw.ui.components.MaaLabeledControlRow
import com.aliothmoon.maafw.ui.components.MaaNavigationRow
import com.aliothmoon.maafw.ui.components.MaaSingleChoiceFlow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import com.aliothmoon.maafw.ui.components.ITextFieldWithFocus
import com.aliothmoon.maafw.ui.components.MaaSwitch

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
                .verticalScroll(rememberScrollState())
                .padding(
                    start = MaaDesignTokens.Spacing.lg,
                    end = MaaDesignTokens.Spacing.lg,
                    top = MaaDesignTokens.Spacing.sm,
                    bottom = MaaDesignTokens.Spacing.lg,
                ),
            verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.lg),
        ) {
            DisplayCard(state, onIntent)
            ScheduleCard(state, onIntent)
            LogCard(state, onIntent, onOpenRunLogArchive, onOpenAppLog, onExportLogs)
            PiCard(onIntent)
            OtherCard(state, settingsState, onIntent, onSettingsIntent)
            AboutCard()
        }
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
    }
}

@Composable
private fun AboutCard() {
    MaaCard(title = stringResource(R.string.settings_about), collapsible = true) {
        MaaInfoRow(stringResource(R.string.settings_version), BuildConfig.VERSION_NAME)
        MaaInfoRow(stringResource(R.string.settings_build), BuildConfig.VERSION_CODE.toString())
    }
}
