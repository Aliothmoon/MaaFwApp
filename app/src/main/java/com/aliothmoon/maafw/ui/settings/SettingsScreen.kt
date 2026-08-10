package com.aliothmoon.maafw.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.aliothmoon.maafw.BuildConfig
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.domain.RemoteBackend
import com.aliothmoon.maafw.runner.ResolutionPreference
import com.aliothmoon.maafw.domain.ThemeMode
import com.aliothmoon.maafw.i18n.AppLocales
import com.aliothmoon.maafw.session.SessionIntent
import com.aliothmoon.maafw.session.SessionUiState
import com.aliothmoon.maafw.settings.SettingsIntent
import com.aliothmoon.maafw.settings.SettingsUiState
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.ui.components.MaaCard
import com.aliothmoon.maafw.ui.components.MaaDiagnosticList
import com.aliothmoon.maafw.ui.components.MaaInfoRow
import com.aliothmoon.maafw.ui.components.MaaLabeledControlRow
import com.aliothmoon.maafw.ui.components.MaaSingleChoiceFlow
import com.aliothmoon.maafw.ui.components.MaaSwitch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SessionUiState,
    onIntent: (SessionIntent) -> Unit,
    settingsState: SettingsUiState,
    onSettingsIntent: (SettingsIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.nav_settings)) },
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
                .padding(MaaDesignTokens.Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.lg),
        ) {
            AppearanceCard(state, onIntent)
            LanguageCard(state, onIntent)
            BackendCard(settingsState, state.configurationLocked, onSettingsIntent)
            ResolutionCard(state, onIntent)
            DeveloperCard(state, onIntent)
            AboutCard()
        }
    }
}

@Composable
private fun AppearanceCard(state: SessionUiState, onIntent: (SessionIntent) -> Unit) {
    MaaCard(title = stringResource(R.string.settings_appearance)) {
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
    }
}

@Composable
private fun LanguageCard(state: SessionUiState, onIntent: (SessionIntent) -> Unit) {
    MaaCard(title = stringResource(R.string.settings_language)) {
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
}

@Composable
private fun DeveloperCard(state: SessionUiState, onIntent: (SessionIntent) -> Unit) {
    MaaCard(title = stringResource(R.string.settings_developer)) {
        MaaLabeledControlRow(
            label = stringResource(R.string.settings_developer_mode),
            trailing = {
                MaaSwitch(
                    checked = state.developerMode,
                    onCheckedChange = { onIntent(SessionIntent.SetDeveloperMode(it)) },
                )
            },
        )
        OutlinedButton(
            onClick = { onIntent(SessionIntent.ReloadProject) },
            enabled = !state.configurationLocked,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.settings_reload_project))
        }
        if (state.developerMode) {
            val diagnostics = state.visibleDiagnostics
            if (diagnostics.isEmpty()) {
                Text(
                    text = stringResource(R.string.settings_no_diagnostics),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                MaaDiagnosticList(diagnostics, showSeverity = true)
            }
        }
    }
}

/**
 * 后端选择：Shizuku / Root
 *
 * 从首页权限卡搬来——首页只显示当前后端状态，切换归设置页管（对齐 MaaMeow）
 * 运行中禁用：切后端会断开当前特权进程，跑一半时切会丢这一轮
 */
@Composable
private fun BackendCard(
    settingsState: SettingsUiState,
    locked: Boolean,
    onSettingsIntent: (SettingsIntent) -> Unit,
) {
    val access = settingsState.remoteAccess
    MaaCard(title = stringResource(R.string.permission_backend)) {
        MaaSingleChoiceFlow(
            options = RemoteBackend.entries.map { entry ->
                val suffix = if (access.isAvailable(entry)) {
                    ""
                } else {
                    "（${stringResource(R.string.permission_backend_unavailable)}）"
                }
                entry to "${entry.display}$suffix"
            },
            selected = access.configuredBackend,
            enabled = !locked,
            onSelect = { onSettingsIntent(SettingsIntent.SetBackend(it)) },
        )
        Text(
            text = stringResource(R.string.permission_backend_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 虚拟屏分辨率：720P / 1080P（对齐 MaaMeow 的可配置偏好）
 */
@Composable
private fun ResolutionCard(state: SessionUiState, onIntent: (SessionIntent) -> Unit) {
    MaaCard(title = stringResource(R.string.settings_resolution)) {
        MaaSingleChoiceFlow(
            options = listOf(
                ResolutionPreference.P720 to stringResource(R.string.settings_resolution_720p),
                ResolutionPreference.P1080 to stringResource(R.string.settings_resolution_1080p),
            ),
            selected = state.resolutionPreference,
            enabled = !state.configurationLocked,
            onSelect = { onIntent(SessionIntent.SetResolutionPreference(it)) },
        )
    }
}

@Composable
private fun AboutCard() {
    MaaCard(title = stringResource(R.string.settings_about)) {
        MaaInfoRow(stringResource(R.string.settings_version), BuildConfig.VERSION_NAME)
        MaaInfoRow(stringResource(R.string.settings_build), BuildConfig.VERSION_CODE.toString())
    }
}
