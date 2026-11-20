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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.core.net.toUri
import com.aliothmoon.maafw.BuildConfig
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.domain.RemoteBackend
import com.aliothmoon.maafw.domain.ThemeMode
import com.aliothmoon.maafw.i18n.AppLocales
import com.aliothmoon.maafw.runner.ResolutionPresets
import com.aliothmoon.maafw.session.SessionIntent
import com.aliothmoon.maafw.session.SessionUiState
import com.aliothmoon.maafw.settings.SettingsIntent
import com.aliothmoon.maafw.settings.SettingsUiState
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.theme.ThemeStyle
import com.aliothmoon.maafw.ui.components.MaaDescriptionPanel
import com.aliothmoon.maafw.ui.components.MaaMarkdown
import com.aliothmoon.maafw.ui.components.MaaMarkdownSheet
import com.aliothmoon.maafw.ui.components.maaClickable
import com.aliothmoon.maafw.ui.components.MaaSingleChoiceFlow
import com.aliothmoon.maafw.ui.pip.PipController
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.aliothmoon.maafw.theme.UiScale
import com.aliothmoon.maafw.ui.components.MaaOutlinedButton
import com.aliothmoon.maafw.ui.components.MaaValueSlider
import timber.log.Timber
import androidx.compose.ui.graphics.vector.ImageVector
import com.aliothmoon.maafw.theme.MaaIcons
import com.aliothmoon.maafw.ui.components.MaaSettingNavRow
import com.aliothmoon.maafw.ui.components.MaaSettingRow
import com.aliothmoon.maafw.ui.components.MaaSettingSwitchRow
import com.aliothmoon.maafw.ui.components.MaaSettingsSection

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
    onConfigBackup: (ConfigBackupAction) -> Unit,
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
                UpdateSection(settingsState, onSettingsIntent)
                TaskSettingsGroup(state, onIntent)
                DisplaySection(state, settingsState, onIntent, onSettingsIntent, onOpenWallpaper)
                RunDurationSection(settingsState, onSettingsIntent)
                NotificationSection(onOpenNotificationSettings)
                LogSection(state, onIntent, onOpenRunLogArchive, onOpenAppLog, onExportLogs)
                BackupSection(onConfigBackup)
                PiSection(onIntent)
                OtherSection(state, settingsState, onIntent, onSettingsIntent)
                AboutSection(state)
                PoweredByFooter()
            }
        }
    }
}

/** 主题、主题风格、页面缩放、背景、语言：都只改观感，合成一个分区（对齐 MaaMeow 的「显示设置」） */
@Composable
private fun DisplaySection(
    state: SessionUiState,
    settingsState: SettingsUiState,
    onIntent: (SessionIntent) -> Unit,
    onSettingsIntent: (SettingsIntent) -> Unit,
    onOpenWallpaper: () -> Unit,
) {
    MaaSettingsSection(
        title = stringResource(R.string.settings_section_display),
        revealToken = sectionRevealToken(SettingsSections.DISPLAY),
    ) {
        SettingSearchTarget(SettingAnchors.THEME) {
            val modes = listOf(
                ThemeMode.System to stringResource(R.string.settings_follow_system),
                ThemeMode.Light to stringResource(R.string.settings_theme_light),
                ThemeMode.Dark to stringResource(R.string.settings_theme_dark),
                ThemeMode.PureDark to stringResource(R.string.settings_theme_pure_dark),
            )
            MaaSettingRow(
                title = stringResource(R.string.settings_theme),
                icon = MaaIcons.Contrast,
                footer = {
                    MaaSingleChoiceFlow(
                        options = modes,
                        selected = state.themeMode,
                        onSelect = { onIntent(SessionIntent.SetThemeMode(it)) },
                    )
                },
            )
        }
        SettingSearchTarget(SettingAnchors.THEME_STYLE) {
            val styles = listOf(
                ThemeStyle.DEFAULT to stringResource(R.string.settings_theme_style_default),
                ThemeStyle.SEMI_DESIGN to stringResource(R.string.settings_theme_style_semi),
            )
            MaaSettingRow(
                title = stringResource(R.string.settings_theme_style),
                icon = MaaIcons.Palette,
                footer = {
                    MaaSingleChoiceFlow(
                        options = styles,
                        selected = state.themeStyle,
                        onSelect = { onIntent(SessionIntent.SetThemeStyle(it)) },
                    )
                },
            )
        }
        SettingSearchTarget(SettingAnchors.UI_SCALE) {
            UiScaleSetting(
                stored = settingsState.uiScale,
                onCommit = { onSettingsIntent(SettingsIntent.SetUiScale(it)) },
            )
        }
        SettingSearchTarget(SettingAnchors.WALLPAPER) {
            MaaSettingNavRow(
                title = stringResource(R.string.wallpaper_title),
                icon = MaaIcons.Wallpaper,
                onClick = onOpenWallpaper,
            )
        }
        SettingSearchTarget(SettingAnchors.LANGUAGE) {
            MaaSettingRow(
                title = stringResource(R.string.settings_language),
                icon = MaaIcons.Language,
                footer = { LanguageChoice(state, onIntent) },
            )
        }
    }
}

@Composable
private fun LanguageChoice(state: SessionUiState, onIntent: (SessionIntent) -> Unit) {
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
}

/** 只是入口；档位与渠道都在二级页面里改，不参与运行锁定 */
@Composable
private fun NotificationSection(onOpen: () -> Unit) {
    MaaSettingsSection(
        title = stringResource(R.string.settings_section_notification),
        revealToken = sectionRevealToken(SettingsSections.NOTIFICATION),
    ) {
        SettingSearchTarget(SettingAnchors.NOTIFICATION_SETTINGS) {
            MaaSettingNavRow(
                title = stringResource(R.string.notification_settings_title),
                icon = MaaIcons.Notification,
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
private fun LogSection(
    state: SessionUiState,
    onIntent: (SessionIntent) -> Unit,
    onOpenRunLogArchive: () -> Unit,
    onOpenAppLog: () -> Unit,
    onExportLogs: () -> Unit,
) {
    var showEnableConfirm by remember { mutableStateOf(false) }
    MaaSettingsSection(
        title = stringResource(R.string.settings_section_log),
        revealToken = sectionRevealToken(SettingsSections.LOG),
    ) {
        SettingSearchTarget(SettingAnchors.RUN_LOG_ARCHIVE) {
            MaaSettingNavRow(
                title = stringResource(R.string.log_archive_title),
                icon = MaaIcons.History,
                onClick = onOpenRunLogArchive,
            )
        }
        SettingSearchTarget(SettingAnchors.APP_LOG) {
            MaaSettingNavRow(
                title = stringResource(R.string.app_log_title),
                icon = MaaIcons.Article,
                onClick = onOpenAppLog,
            )
        }
        SettingSearchTarget(SettingAnchors.EXPORT_LOGS) {
            MaaSettingNavRow(
                title = stringResource(R.string.log_export_title),
                icon = MaaIcons.Share,
                onClick = onExportLogs,
            )
        }
        // 启用走确认弹窗，确认即落盘 + 重启 App（对齐 MaaMeow）；关闭直接关
        SettingSearchTarget(SettingAnchors.DEBUG_MODE) {
            MaaSettingSwitchRow(
                title = stringResource(R.string.settings_debug_mode),
                icon = MaaIcons.Debug,
                info = stringResource(R.string.settings_debug_mode_desc),
                checked = state.debugMode,
                onCheckedChange = { enabled ->
                    if (enabled) showEnableConfirm = true
                    else onIntent(SessionIntent.SetDebugMode(false))
                },
            )
        }
        SettingSearchTarget(SettingAnchors.SAVE_ON_ERROR) {
            MaaSettingSwitchRow(
                title = stringResource(R.string.settings_save_on_error),
                icon = MaaIcons.DebugImage,
                checked = state.saveOnError,
                onCheckedChange = { enabled -> onIntent(SessionIntent.SetSaveOnError(enabled)) },
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

/** 配置导出、导入；选文件、预览与确认都在 [ConfigBackupController]，这里只发起 */
@Composable
private fun BackupSection(onConfigBackup: (ConfigBackupAction) -> Unit) {
    MaaSettingsSection(
        title = stringResource(R.string.settings_section_backup),
        revealToken = sectionRevealToken(SettingsSections.BACKUP),
    ) {
        SettingSearchTarget(SettingAnchors.EXPORT_CONFIG) {
            MaaSettingNavRow(
                title = stringResource(R.string.config_export_title),
                icon = MaaIcons.Export,
                info = stringResource(R.string.config_export_desc),
                onClick = { onConfigBackup(ConfigBackupAction.Export) },
            )
        }
        SettingSearchTarget(SettingAnchors.IMPORT_CONFIG) {
            MaaSettingNavRow(
                title = stringResource(R.string.config_import_title),
                icon = MaaIcons.Import,
                info = stringResource(R.string.config_import_desc),
                onClick = { onConfigBackup(ConfigBackupAction.Import) },
            )
        }
        SettingSearchTarget(SettingAnchors.IMPORT_SHARE_CODE) {
            MaaSettingNavRow(
                title = stringResource(R.string.config_import_share_code_title),
                icon = MaaIcons.ShareCode,
                onClick = { onConfigBackup(ConfigBackupAction.PasteShareCode) },
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
private fun PiSection(onIntent: (SessionIntent) -> Unit) {
    var showConfirm by remember { mutableStateOf(false) }
    MaaSettingsSection(
        title = stringResource(R.string.settings_section_pi),
        revealToken = sectionRevealToken(SettingsSections.PI),
    ) {
        SettingSearchTarget(SettingAnchors.REINSTALL_PI) {
            MaaSettingNavRow(
                title = stringResource(R.string.pi_reinstall_title),
                icon = MaaIcons.Reinstall,
                info = stringResource(R.string.pi_reinstall_desc),
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
private fun UpdateSection(
    state: SettingsUiState,
    onSettingsIntent: (SettingsIntent) -> Unit,
) {
    val update = state.update
    // 下载过程中更新设置锁死，防止改到进行中那一轮的语义；VM 写入口有二次校验
    val settingsEnabled = !update.downloading
    MaaSettingsSection(
        title = stringResource(R.string.settings_section_update),
        revealToken = sectionRevealToken(SettingsSections.UPDATE),
    ) {
        SettingSearchTarget(SettingAnchors.AUTO_CHECK_UPDATE) {
            MaaSettingSwitchRow(
                title = stringResource(R.string.settings_update_auto_check),
                icon = MaaIcons.Update,
                checked = update.autoCheckUpdate,
                enabled = settingsEnabled,
                onCheckedChange = { onSettingsIntent(SettingsIntent.SetAutoCheckUpdate(it)) },
            )
        }
        SettingSearchTarget(SettingAnchors.AUTO_DOWNLOAD_UPDATE) {
            MaaSettingSwitchRow(
                title = stringResource(R.string.settings_update_auto_download),
                icon = MaaIcons.Download,
                checked = update.autoDownloadUpdate,
                enabled = update.autoCheckUpdate && settingsEnabled,
                onCheckedChange = { onSettingsIntent(SettingsIntent.SetAutoDownloadUpdate(it)) },
            )
        }
    }
}

/**
 * 启动模式与后台模式分辨率：都是「跑起来之前得先定」的环境选项（对齐 MaaMeow 的「其他设置」）
 *
 * 启动模式从首页权限卡搬来——首页只显示当前后端状态，切换归设置页管
 * 两者运行中都禁用：切后端会断开当前特权进程，改分辨率要重建虚拟屏，跑一半时切会丢这一轮
 */
@Composable
private fun OtherSection(
    state: SessionUiState,
    settingsState: SettingsUiState,
    onIntent: (SessionIntent) -> Unit,
    onSettingsIntent: (SettingsIntent) -> Unit,
) {
    val locked = state.configurationLocked
    MaaSettingsSection(
        title = stringResource(R.string.settings_section_other),
        revealToken = sectionRevealToken(SettingsSections.OTHER),
    ) {
        SettingSearchTarget(SettingAnchors.BACKEND) {
            MaaSettingRow(
                title = stringResource(R.string.permission_backend),
                icon = MaaIcons.Privileged,
                enabled = !locked,
                footer = {
                    MaaSingleChoiceFlow(
                        // 对齐 MaaMeow：只列后端名，不展示「可用/不可用」——选哪个都行，可用性交给连接流程判
                        options = RemoteBackend.entries.map { it to it.display },
                        selected = settingsState.remoteAccess.configuredBackend,
                        enabled = !locked,
                        onSelect = { onSettingsIntent(SettingsIntent.SetBackend(it)) },
                    )
                },
            )
        }
        SettingSearchTarget(SettingAnchors.RESOLUTION) {
            MaaSettingRow(
                title = stringResource(R.string.settings_resolution),
                icon = MaaIcons.Resolution,
                // 预设来自打包配方，label 语言无关；具体尺寸与 dpi 放同行右侧，同页面缩放
                value = stringResource(
                    R.string.settings_resolution_detail,
                    state.resolutionPreset.resolution.width,
                    state.resolutionPreset.resolution.height,
                    state.resolutionPreset.dpi,
                ),
                enabled = !locked,
                footer = {
                    MaaSingleChoiceFlow(
                        options = ResolutionPresets.available.map { it to it.label },
                        selected = state.resolutionPreset,
                        enabled = !locked,
                        onSelect = { onIntent(SessionIntent.SetResolutionPreset(it)) },
                    )
                },
            )
        }
        if (PipController.isSupported(LocalContext.current)) {
            SettingSearchTarget(SettingAnchors.PIP_ON_HOME) {
                MaaSettingSwitchRow(
                    title = stringResource(R.string.settings_pip_on_home),
                    icon = MaaIcons.PictureInPicture,
                    info = stringResource(R.string.settings_pip_on_home_desc),
                    checked = settingsState.pipOnHome,
                    onCheckedChange = { onSettingsIntent(SettingsIntent.SetPipOnHome(it)) },
                )
            }
        }
        if (state.telemetryDeclared) {
            SettingSearchTarget(SettingAnchors.TELEMETRY) {
                MaaSettingSwitchRow(
                    title = stringResource(R.string.settings_telemetry),
                    icon = MaaIcons.Telemetry,
                    info = stringResource(R.string.settings_telemetry_desc),
                    checked = state.telemetryEnabled,
                    onCheckedChange = { onIntent(SessionIntent.SetTelemetryEnabled(it)) },
                )
            }
        }
    }
}

private data class AboutSheet(val title: String, val body: String)

/** 欢迎信息、联系方式、开源许可、项目仓库；排在版本号之后，每项一行 */
@Composable
private fun AboutLinks(
    metadata: ProjectMetadata,
    onOpen: (AboutSheet) -> Unit,
    onOpenWelcome: () -> Unit,
) {
    val context = LocalContext.current
    if (metadata.welcome.isNotEmpty()) {
        MaaSettingNavRow(
            title = stringResource(R.string.settings_about_welcome),
            icon = MaaIcons.Announcement,
            onClick = onOpenWelcome,
        )
    }
    // 联系方式通常就几行链接，直接摊开（对齐 MXU），不再点进 sheet
    metadata.contact?.let { body ->
        MaaSettingRow(
            title = stringResource(R.string.settings_about_contact),
            icon = MaaIcons.Contact,
            footer = {
                MaaDescriptionPanel {
                    MaaMarkdown(
                        text = body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            },
        )
    }
    metadata.license?.let { body ->
        val title = stringResource(R.string.settings_about_license)
        MaaSettingNavRow(
            title = title,
            icon = MaaIcons.License,
            onClick = { onOpen(AboutSheet(title, body)) },
        )
    }
    metadata.github?.let { url ->
        MaaSettingNavRow(
            title = stringResource(R.string.settings_about_repository),
            icon = MaaIcons.Repository,
            onClick = { context.openLink(url) },
            external = true,
        )
    }
}

/** 页底一行「Powered by MaaFwApp」（对齐 MXU 的页脚），点了打开外壳的仓库；放在关于分区外，收起关于时照样在 */
@Composable
private fun PoweredByFooter() {
    val context = LocalContext.current
    val appName = stringResource(R.string.app_name)
    val text = stringResource(R.string.settings_about_powered_by, appName)
    val linkColor = MaterialTheme.colorScheme.primary
    val styled = remember(text, appName, linkColor) {
        buildAnnotatedString {
            append(text)
            val start = text.indexOf(appName)
            if (start >= 0) addStyle(SpanStyle(color = linkColor), start, start + appName.length)
        }
    }
    Text(
        text = styled,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = MaaDesignTokens.Spacing.sm)
            .maaClickable(indication = false) { context.openLink(APP_REPOSITORY_URL) }
            .padding(vertical = MaaDesignTokens.Spacing.xs),
    )
}

private fun Context.openLink(url: String) {
    val intent = Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { startActivity(intent) }
        .onFailure { Timber.w(it, "No activity handles the link %s", url) }
}

private const val APP_REPOSITORY_URL = "https://github.com/Aliothmoon/MaaFwApp"

@Composable
private fun AboutSection(state: SessionUiState) {
    val context = LocalContext.current
    val metadata = state.projectMetadata
    var sheet by remember { mutableStateOf<AboutSheet?>(null) }
    var welcomeVisible by remember { mutableStateOf(false) }
    val appLabel = remember(context) {
        context.applicationInfo.loadLabel(context.packageManager).toString()
    }

    MaaSettingsSection(
        title = stringResource(R.string.settings_about),
        revealToken = sectionRevealToken(SettingsSections.ABOUT),
    ) {
        SettingSearchTarget(SettingAnchors.ABOUT) {
            AboutHeader(name = appLabel, icon = metadata.icon, description = metadata.description)
        }
        // 空串 = 非子模块又没钉版本名，此时它和下面那行同值，不重复显示
        if (BuildConfig.MAFW_PROJECT_VERSION.isNotEmpty()) {
            VersionRow(
                label = stringResource(R.string.settings_version_of, appLabel),
                value = BuildConfig.MAFW_PROJECT_VERSION,
                icon = MaaIcons.ProjectVersion,
            )
        }
        VersionRow(
            label = stringResource(R.string.settings_version_of, stringResource(R.string.app_name)),
            value = BuildConfig.MAFW_APP_VERSION,
            icon = MaaIcons.AppVersion,
        )
        if (BuildConfig.MAFW_FRAMEWORK_VERSION.isNotEmpty()) {
            VersionRow(
                label = stringResource(R.string.settings_version_of, stringResource(R.string.settings_framework)),
                value = BuildConfig.MAFW_FRAMEWORK_VERSION,
                icon = MaaIcons.FrameworkVersion,
            )
        }
        AboutLinks(metadata, onOpen = { sheet = it }, onOpenWelcome = { welcomeVisible = true })
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

@Composable
private fun VersionRow(label: String, value: String, icon: ImageVector) {
    MaaSettingRow(title = label, icon = icon, value = value)
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
                SearchCondition.FOREGROUND_ALLOWED -> BuildConfig.MAFW_FOREGROUND_ALLOWED
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
    val valueText = if (isAuto && dragging == null) {
        stringResource(R.string.settings_ui_scale_auto_value, effective)
    } else {
        "${dragging ?: effective}%"
    }
    MaaSettingRow(
        title = stringResource(R.string.settings_ui_scale),
        icon = MaaIcons.FontSize,
        info = stringResource(R.string.settings_ui_scale_desc),
        value = valueText,
        footer = {
            Column(verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm)) {
                MaaValueSlider(
                    label = null,
                    value = effective,
                    range = UiScale.MIN..UiScale.MAX,
                    onDrag = { dragging = it },
                    onValueCommit = {
                        dragging = null
                        onCommit(it)
                    },
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
        },
    )
}
