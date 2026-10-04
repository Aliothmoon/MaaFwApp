package com.aliothmoon.maafw.ui.settings

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalFocusManager
import com.aliothmoon.maafw.domain.ProjectMetadata
import com.aliothmoon.maafw.domain.RunMode
import com.aliothmoon.maafw.settings.search.SearchCondition
import com.aliothmoon.maafw.settings.search.SettingAnchors
import com.aliothmoon.maafw.settings.search.SettingSearchEntry
import com.aliothmoon.maafw.settings.search.SettingSearchIndex
import com.aliothmoon.maafw.settings.search.SettingsSections
import com.aliothmoon.maafw.theme.OpaqueTheme
import com.aliothmoon.maafw.ui.settings.search.SettingSearchField
import com.aliothmoon.maafw.ui.settings.search.SettingSearchResults
import com.aliothmoon.maafw.ui.settings.search.SettingSearchTarget
import com.aliothmoon.maafw.ui.settings.search.sectionRevealToken
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.core.net.toUri
import com.aliothmoon.maafw.BuildConfig
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.domain.RemoteBackend
import com.aliothmoon.maafw.domain.ThemeMode
import com.aliothmoon.maafw.i18n.AppLocales
import com.aliothmoon.maafw.i18n.asString
import com.aliothmoon.maafw.runner.ResolutionPresets
import com.aliothmoon.maafw.session.SessionIntent
import com.aliothmoon.maafw.session.SessionUiState
import com.aliothmoon.maafw.settings.SettingsIntent
import com.aliothmoon.maafw.settings.SettingsUiState
import com.aliothmoon.maafw.settings.UpdatePanelState
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.theme.ThemeStyle
import com.aliothmoon.maafw.ui.components.MaaButton
import com.aliothmoon.maafw.ui.components.MaaCard
import com.aliothmoon.maafw.ui.components.MaaFieldLabel
import com.aliothmoon.maafw.ui.components.MaaInfoRow
import com.aliothmoon.maafw.ui.components.MaaLabeledControlRow
import com.aliothmoon.maafw.ui.components.MaaDescriptionPanel
import com.aliothmoon.maafw.ui.components.MaaMarkdown
import com.aliothmoon.maafw.ui.components.MaaMarkdownSheet
import com.aliothmoon.maafw.ui.components.MaaNavigationRow
import com.aliothmoon.maafw.ui.components.MaaSingleChoiceFlow
import com.aliothmoon.maafw.ui.components.MaaSwitch
import com.aliothmoon.maafw.ui.components.MaaSwitchRow
import com.aliothmoon.maafw.ui.components.updateSourceLabel
import com.aliothmoon.maafw.ui.options.OptionEditorList
import com.aliothmoon.maafw.ui.pip.PipController
import com.aliothmoon.maafw.update.UpdateChannel
import com.aliothmoon.maafw.update.UpdateCheckResult
import com.aliothmoon.maafw.update.UpdateSource
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.aliothmoon.maafw.theme.UiScale
import com.aliothmoon.maafw.ui.components.MaaOutlinedButton
import com.aliothmoon.maafw.ui.components.MaaValueSlider
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
    onOpenWallpaper: () -> Unit,
    onExportLogs: () -> Unit,
    /** 点了某条搜索结果：发定位请求、按位置切页由 AppRoot 做，本页只管清掉输入 */
    onOpenSearchResult: (SettingSearchEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val searching by remember { derivedStateOf { query.isNotBlank() } }
    val focusManager = LocalFocusManager.current
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
            SettingSearchField(query = query, onQueryChange = { query = it })
            if (searching) {
                SettingSearchResults(
                    entries = searchEntries(state),
                    query = query,
                    onClick = { entry ->
                        focusManager.clearFocus()
                        query = ""
                        onOpenSearchResult(entry)
                    },
                )
            }
            // 搜索时只是不量不摆，不移出组合：卡片折叠态与没提交的输入草稿都挂在组合里，移出去就丢了
            // 放在结果后面，多出的那份 spacedBy 间距只落在末尾
            Column(
                modifier = Modifier.layout { measurable, constraints ->
                    if (searching) return@layout layout(0, 0) {}
                    val placeable = measurable.measure(constraints)
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                },
                verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.md),
            ) {
                UpdateCard(settingsState, onSettingsIntent)
                TaskSettingsGroup(state, onIntent)
                DisplayCard(state, settingsState, onIntent, onSettingsIntent, onOpenWallpaper)
                RunDurationCard(settingsState, onSettingsIntent)
                NotificationCard(onOpenNotificationSettings)
                LogCard(state, onIntent, onOpenRunLogArchive, onOpenAppLog, onExportLogs)
                PiCard(onIntent)
                OtherCard(state, settingsState, onIntent, onSettingsIntent)
                AboutCard(state)
            }
        }
    }
}

/** 主题、主题风格、页面缩放、背景、语言：都只改观感，合成一张卡（对齐 MaaMeow 的「显示设置」） */
@Composable
private fun DisplayCard(
    state: SessionUiState,
    settingsState: SettingsUiState,
    onIntent: (SessionIntent) -> Unit,
    onSettingsIntent: (SettingsIntent) -> Unit,
    onOpenWallpaper: () -> Unit,
) {
    MaaCard(
        title = stringResource(R.string.settings_section_display),
        collapsible = true,
        revealToken = sectionRevealToken(SettingsSections.DISPLAY),
    ) {
        SettingSearchTarget(SettingAnchors.THEME) {
            Column(verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm)) {
                MaaFieldLabel(stringResource(R.string.settings_theme))
                val modes = listOf(
                    ThemeMode.System to stringResource(R.string.settings_follow_system),
                    ThemeMode.Light to stringResource(R.string.settings_theme_light),
                    ThemeMode.Dark to stringResource(R.string.settings_theme_dark),
                    ThemeMode.PureDark to stringResource(R.string.settings_theme_pure_dark),
                )
                MaaSingleChoiceFlow(
                    options = modes,
                    selected = state.themeMode,
                    onSelect = { onIntent(SessionIntent.SetThemeMode(it)) },
                )
            }
        }
        Spacer(Modifier.height(MaaDesignTokens.Spacing.sm))
        SettingSearchTarget(SettingAnchors.THEME_STYLE) {
            Column(verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm)) {
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
            }
        }
        Spacer(Modifier.height(MaaDesignTokens.Spacing.sm))
        SettingSearchTarget(SettingAnchors.UI_SCALE) {
            UiScaleSetting(
                stored = settingsState.uiScale,
                onCommit = { onSettingsIntent(SettingsIntent.SetUiScale(it)) },
            )
        }
        SettingSearchTarget(SettingAnchors.WALLPAPER) {
            MaaNavigationRow(
                label = stringResource(R.string.wallpaper_title),
                description = stringResource(R.string.wallpaper_desc),
                onClick = onOpenWallpaper,
            )
        }
        Spacer(Modifier.height(MaaDesignTokens.Spacing.sm))
        SettingSearchTarget(SettingAnchors.LANGUAGE) {
            Column(verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm)) {
                MaaFieldLabel(stringResource(R.string.settings_language))
                LanguageChoice(state, onIntent)
            }
        }
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

/** 只是入口；档位与渠道都在二级页面里改，不参与运行锁定 */
@Composable
private fun NotificationCard(onOpen: () -> Unit) {
    MaaCard(
        title = stringResource(R.string.settings_section_notification),
        collapsible = true,
        revealToken = sectionRevealToken(SettingsSections.NOTIFICATION),
    ) {
        SettingSearchTarget(SettingAnchors.NOTIFICATION_SETTINGS) {
            MaaNavigationRow(
                label = stringResource(R.string.notification_settings_title),
                onClick = onOpen,
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
    MaaCard(
        title = stringResource(R.string.settings_section_log),
        collapsible = true,
        revealToken = sectionRevealToken(SettingsSections.LOG),
    ) {
        SettingSearchTarget(SettingAnchors.RUN_LOG_ARCHIVE) {
            MaaNavigationRow(
                label = stringResource(R.string.log_archive_title),
                description = stringResource(R.string.settings_log_archive_desc),
                onClick = onOpenRunLogArchive,
            )
        }
        SettingSearchTarget(SettingAnchors.APP_LOG) {
            MaaNavigationRow(
                label = stringResource(R.string.app_log_title),
                description = stringResource(R.string.settings_log_error_desc),
                onClick = onOpenAppLog,
            )
        }
        SettingSearchTarget(SettingAnchors.EXPORT_LOGS) {
            MaaNavigationRow(
                label = stringResource(R.string.log_export_title),
                description = stringResource(R.string.settings_log_export_desc),
                onClick = onExportLogs,
            )
        }
        // 启用走确认弹窗，确认即落盘 + 重启 App（对齐 MaaMeow）；关闭直接关
        SettingSearchTarget(SettingAnchors.DEBUG_MODE) {
            Column(verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm)) {
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
        }
        SettingSearchTarget(SettingAnchors.SAVE_ON_ERROR) {
            MaaLabeledControlRow(
                label = stringResource(R.string.settings_save_on_error),
                trailing = {
                    MaaSwitch(
                        checked = state.saveOnError,
                        onCheckedChange = { enabled -> onIntent(SessionIntent.SetSaveOnError(enabled)) },
                    )
                },
            )
        }
    }
    if (showEnableConfirm) {
OpaqueTheme {
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
    MaaCard(
        title = stringResource(R.string.settings_section_pi),
        collapsible = true,
        revealToken = sectionRevealToken(SettingsSections.PI),
    ) {
        SettingSearchTarget(SettingAnchors.REINSTALL_PI) {
            MaaNavigationRow(
                label = stringResource(R.string.pi_reinstall_title),
                description = stringResource(R.string.pi_reinstall_desc),
                onClick = { showConfirm = true },
            )
        }
    }
    if (showConfirm) {
OpaqueTheme {
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
}

/** 只剩启动自检与自动下载两个开关；源/渠道/CDK 与检查、下载入口在首页的更新卡 */
@Composable
private fun UpdateCard(
    state: SettingsUiState,
    onSettingsIntent: (SettingsIntent) -> Unit,
) {
    val update = state.update
    MaaCard(
        title = stringResource(R.string.settings_section_update),
        collapsible = true,
        revealToken = sectionRevealToken(SettingsSections.UPDATE),
    ) {
        // 下载过程中更新设置锁死，防止改到进行中那一轮的语义；VM 写入口有二次校验
        val settingsEnabled = !update.downloading
        SettingSearchTarget(SettingAnchors.AUTO_CHECK_UPDATE) {
            MaaSwitchRow(
                label = stringResource(R.string.settings_update_auto_check),
                checked = update.autoCheckUpdate,
                enabled = settingsEnabled,
                onCheckedChange = { onSettingsIntent(SettingsIntent.SetAutoCheckUpdate(it)) },
            )
        }
        SettingSearchTarget(SettingAnchors.AUTO_DOWNLOAD_UPDATE) {
            MaaSwitchRow(
                label = stringResource(R.string.settings_update_auto_download),
                checked = update.autoDownloadUpdate,
                enabled = update.autoCheckUpdate && settingsEnabled,
                onCheckedChange = { onSettingsIntent(SettingsIntent.SetAutoDownloadUpdate(it)) },
            )
        }
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
    MaaCard(
        title = stringResource(R.string.settings_section_other),
        collapsible = true,
        revealToken = sectionRevealToken(SettingsSections.OTHER),
    ) {
        SettingSearchTarget(SettingAnchors.BACKEND) {
            Column(verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm)) {
                MaaFieldLabel(stringResource(R.string.permission_backend))
                MaaSingleChoiceFlow(
                    // 对齐 MaaMeow：只列后端名，不展示「可用/不可用」——选哪个都行，可用性交给连接流程判
                    options = RemoteBackend.entries.map { it to it.display },
                    selected = settingsState.remoteAccess.configuredBackend,
                    enabled = !locked,
                    onSelect = { onSettingsIntent(SettingsIntent.SetBackend(it)) },
                )
            }
        }
        Spacer(Modifier.height(MaaDesignTokens.Spacing.sm))
        SettingSearchTarget(SettingAnchors.RESOLUTION) {
            Column(verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm)) {
                MaaFieldLabel(stringResource(R.string.settings_resolution))
                // 预设来自打包配方，label 语言无关；具体尺寸与 dpi 写在下面一行
                MaaSingleChoiceFlow(
                    options = ResolutionPresets.available.map { it to it.label },
                    selected = state.resolutionPreset,
                    enabled = !locked,
                    onSelect = { onIntent(SessionIntent.SetResolutionPreset(it)) },
                )
                Text(
                    text = stringResource(
                        R.string.settings_resolution_detail,
                        state.resolutionPreset.resolution.width,
                        state.resolutionPreset.resolution.height,
                        state.resolutionPreset.dpi,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (PipController.isSupported(LocalContext.current)) {
            Spacer(Modifier.height(MaaDesignTokens.Spacing.sm))
            SettingSearchTarget(SettingAnchors.PIP_ON_HOME) {
                MaaSwitchRow(
                    label = stringResource(R.string.settings_pip_on_home),
                    checked = settingsState.pipOnHome,
                    onCheckedChange = { onSettingsIntent(SettingsIntent.SetPipOnHome(it)) },
                )
            }
            Text(
                text = stringResource(R.string.settings_pip_on_home_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.telemetryDeclared) {
            Spacer(Modifier.height(MaaDesignTokens.Spacing.sm))
            SettingSearchTarget(SettingAnchors.TELEMETRY) {
                MaaSwitchRow(
                    label = stringResource(R.string.settings_telemetry),
                    checked = state.telemetryEnabled,
                    onCheckedChange = { onIntent(SessionIntent.SetTelemetryEnabled(it)) },
                )
            }
            Text(
                text = stringResource(R.string.settings_telemetry_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private data class AboutSheet(val title: String, val body: String)

/** 欢迎信息、联系方式、开源许可、项目仓库；排在版本号之前 */
@Composable
private fun AboutLinks(
    metadata: ProjectMetadata,
    onOpen: (AboutSheet) -> Unit,
    onOpenWelcome: () -> Unit,
) {
    val context = LocalContext.current
    if (metadata.welcome.isNotEmpty()) {
        MaaNavigationRow(
            label = stringResource(R.string.settings_about_welcome),
            onClick = onOpenWelcome,
        )
    }
    // 联系方式通常就几行链接，直接摊开（对齐 MXU），不再点进 sheet
    metadata.contact?.let { body ->
        Column(
            modifier = Modifier.padding(top = MaaDesignTokens.Spacing.xs),
            verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xs),
        ) {
            Text(text = stringResource(R.string.settings_about_contact), style = MaterialTheme.typography.bodyLarge)
            MaaDescriptionPanel {
                MaaMarkdown(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
    metadata.license?.let { body ->
        val title = stringResource(R.string.settings_about_license)
        MaaNavigationRow(label = title, onClick = { onOpen(AboutSheet(title, body)) })
    }
    metadata.github?.let { url ->
        MaaNavigationRow(
            label = stringResource(R.string.settings_about_repository),
            onClick = { context.openLink(url) },
        )
    }
}

/** 外壳自己的仓库；放在 PI 那组链接之后，行名带上 MaaFwApp，免得和项目仓库混淆 */
@Composable
private fun AppRepositoryRow() {
    val context = LocalContext.current
    MaaNavigationRow(
        label = stringResource(R.string.settings_about_app_repository, stringResource(R.string.app_name)),
        onClick = { context.openLink(APP_REPOSITORY_URL) },
    )
}

private fun Context.openLink(url: String) {
    val intent = Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { startActivity(intent) }
        .onFailure { Timber.w(it, "No activity handles the link %s", url) }
}

private const val APP_REPOSITORY_URL = "https://github.com/Aliothmoon/MaaFwApp"

@Composable
private fun AboutCard(state: SessionUiState) {
    val context = LocalContext.current
    val metadata = state.projectMetadata
    var sheet by remember { mutableStateOf<AboutSheet?>(null) }
    var welcomeVisible by remember { mutableStateOf(false) }
    val appLabel = remember(context) {
        context.applicationInfo.loadLabel(context.packageManager).toString()
    }

    MaaCard(
        title = stringResource(R.string.settings_about),
        collapsible = true,
        revealToken = sectionRevealToken(SettingsSections.ABOUT),
    ) {
        SettingSearchTarget(SettingAnchors.ABOUT) {
            AboutHeader(name = appLabel, icon = metadata.icon, description = metadata.description)
        }
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
        AboutLinks(metadata, onOpen = { sheet = it }, onOpenWelcome = { welcomeVisible = true })
        AppRepositoryRow()
    }

    sheet?.let {
        MaaMarkdownSheet(
            title = it.title,
            body = it.body,
            onDismiss = { sheet = null },
        )
    }
    // 主动查看：总是展示当前正文，不读也不写「已看过」指纹；URL 项由 MaaMarkdown 现拉
    if (welcomeVisible) {
        MaaMarkdownSheet(
            title = appLabel,
            bodies = metadata.welcome,
            onDismiss = { welcomeVisible = false },
        )
    }
}

/**
 * 当下能搜到的条目：手写的 + 当前 PI 的选项，滤掉此刻不会渲染的
 *
 * 渲染条件与各卡片自己的判断同源（画中画、遥测、运行模式），否则点了定位不到
 */
@Composable
private fun searchEntries(state: SessionUiState): List<SettingSearchEntry> {
    val pipSupported = PipController.isSupported(LocalContext.current)
    return remember(
        state.settingSections,
        state.globalOptions,
        state.resourceOptions,
        state.controllerOptions,
        state.telemetryDeclared,
        state.runMode,
        state.environment,
        pipSupported,
    ) {
        val static = SettingSearchIndex.staticEntries.filter { entry ->
            when (entry.condition) {
                null -> true
                SearchCondition.PIP_SUPPORTED -> pipSupported
                SearchCondition.TELEMETRY_DECLARED -> state.telemetryDeclared
                SearchCondition.BACKGROUND_MODE -> state.runMode == RunMode.BACKGROUND
                SearchCondition.FOREGROUND_MODE -> state.runMode == RunMode.FOREGROUND
                SearchCondition.CONTROLLER_CHOICE ->
                    (state.environment?.controllerCandidates?.size ?: 0) >= 2
            }
        }
        static + SettingSearchIndex.projectEntries(
            sections = state.settingSections,
            globalOptions = state.globalOptions,
            resourceOptions = state.resourceOptions,
            controllerOptions = state.controllerOptions,
        )
    }
}

/**
 * 页面缩放：自动（按屏幕推荐）或手动 80–110（移植自 MaaMeow 的 FontSizeSetting）
 *
 * 拖动即进入手动，「使用自动」一键回去；预览块按拖动值相对当前生效值现缩，
 * 松手前就能看到效果，又不必每一帧都落盘重排整棵树
 */
@Composable
private fun UiScaleSetting(stored: Int, onCommit: (Int) -> Unit) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val isAuto = stored == UiScale.AUTO
    val effective = UiScale.resolve(stored, configuration.smallestScreenWidthDp, density.fontScale)
    var dragging by remember { mutableStateOf<Int?>(null) }
    val autoText = stringResource(R.string.settings_ui_scale_auto_value, effective)
    Column(verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm)) {
        MaaValueSlider(
            label = stringResource(R.string.settings_ui_scale),
            value = effective,
            range = UiScale.MIN..UiScale.MAX,
            valueText = { if (isAuto && dragging == null) autoText else "$it%" },
            onDrag = { dragging = it },
            onValueCommit = {
                dragging = null
                onCommit(it)
            },
        )
        Text(
            text = stringResource(R.string.settings_ui_scale_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val factor = (dragging ?: effective).toFloat() / effective
        CompositionLocalProvider(
            LocalDensity provides Density(density.density * factor, density.fontScale),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Text(
                    text = stringResource(R.string.settings_ui_scale_preview),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(MaaDesignTokens.Spacing.md),
                )
            }
        }
        if (!isAuto) {
            MaaOutlinedButton(
                onClick = { onCommit(UiScale.AUTO) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_ui_scale_use_auto))
            }
        }
    }
}
