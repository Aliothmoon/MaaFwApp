package com.aliothmoon.maafw.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.aliothmoon.maafw.BuildConfig
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.domain.ThemeMode
import com.aliothmoon.maafw.i18n.AppLocales
import com.aliothmoon.maafw.session.SessionIntent
import com.aliothmoon.maafw.session.SessionUiState
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.ui.components.MaaCard
import com.aliothmoon.maafw.ui.components.MaaDiagnosticList
import com.aliothmoon.maafw.ui.components.MaaInfoRow
import com.aliothmoon.maafw.ui.components.MaaLabeledControlRow
import com.aliothmoon.maafw.ui.components.MaaSingleChoiceFlow
import com.aliothmoon.maafw.ui.components.MaaSwitch

@Composable
fun SettingsScreen(
    state: SessionUiState,
    onIntent: (SessionIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(MaaDesignTokens.Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.lg),
    ) {
        AppearanceCard(state, onIntent)
        LanguageCard(state, onIntent)
        ResourceCard(state, onIntent)
        DeveloperCard(state, onIntent)
        AboutCard()
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
        // 切换后 Activity 重建，本处在新组合中重新读取，无需观察流。
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
private fun ResourceCard(state: SessionUiState, onIntent: (SessionIntent) -> Unit) {
    MaaCard(title = stringResource(R.string.settings_resource)) {
        val environment = state.environment
        if (environment == null || environment.resourceCandidates.isEmpty()) {
            Text(
                text = stringResource(R.string.settings_no_resources),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            var expanded by remember { mutableStateOf(false) }
            Text(stringResource(R.string.settings_current_resource), style = MaterialTheme.typography.bodyLarge)
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { expanded = true },
                    enabled = !state.configurationLocked,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = environment.resource?.label ?: stringResource(R.string.settings_not_selected),
                        textAlign = TextAlign.Start,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(Icons.Outlined.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    environment.resourceCandidates.forEach { resource ->
                        DropdownMenuItem(
                            text = { Text(resource.label) },
                            onClick = {
                                expanded = false
                                onIntent(SessionIntent.SelectResource(resource.name))
                            },
                        )
                    }
                }
            }
            Text(
                text = stringResource(R.string.settings_resource_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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

@Composable
private fun AboutCard() {
    MaaCard(title = stringResource(R.string.settings_about)) {
        MaaInfoRow(stringResource(R.string.settings_version), BuildConfig.VERSION_NAME)
        MaaInfoRow(stringResource(R.string.settings_build), BuildConfig.VERSION_CODE.toString())
    }
}
