package com.aliothmoon.maafw.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.aliothmoon.maafw.BuildConfig
import com.aliothmoon.maafw.domain.DiagnosticSeverity
import com.aliothmoon.maafw.domain.ThemeMode
import com.aliothmoon.maafw.project.ProjectState
import com.aliothmoon.maafw.session.SessionIntent
import com.aliothmoon.maafw.session.SessionUiState
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.ui.components.MaaCard
import com.aliothmoon.maafw.ui.components.MaaInfoRow

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
        ResourceCard(state, onIntent)
        DeveloperCard(state, onIntent)
        AboutCard()
    }
}

@Composable
private fun AppearanceCard(state: SessionUiState, onIntent: (SessionIntent) -> Unit) {
    MaaCard(title = "外观") {
        val modes = listOf(
            ThemeMode.System to "跟随系统",
            ThemeMode.Light to "浅色",
            ThemeMode.Dark to "深色",
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            modes.forEachIndexed { index, (mode, label) ->
                SegmentedButton(
                    selected = state.themeMode == mode,
                    onClick = { onIntent(SessionIntent.SetThemeMode(mode)) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                ) {
                    Text(label)
                }
            }
        }
    }
}

@Composable
private fun ResourceCard(state: SessionUiState, onIntent: (SessionIntent) -> Unit) {
    MaaCard(title = "资源") {
        val environment = state.environment
        if (environment == null || environment.resourceCandidates.isEmpty()) {
            Text(
                text = "项目未提供可选资源",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            var expanded by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("当前资源", style = MaterialTheme.typography.bodyLarge)
                Box {
                    OutlinedButton(
                        onClick = { expanded = true },
                        enabled = !state.configurationLocked,
                    ) {
                        Text(environment.resourceName ?: "未选择")
                        Icon(Icons.Outlined.ArrowDropDown, contentDescription = null)
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        environment.resourceCandidates.forEach { name ->
                            DropdownMenuItem(
                                text = { Text(name) },
                                onClick = {
                                    expanded = false
                                    onIntent(SessionIntent.SelectResource(name))
                                },
                            )
                        }
                    }
                }
            }
            Text(
                text = "切换资源会影响任务适用性，运行期间不可切换",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DeveloperCard(state: SessionUiState, onIntent: (SessionIntent) -> Unit) {
    MaaCard(title = "开发者") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("开发者模式", style = MaterialTheme.typography.bodyLarge)
            Switch(
                checked = state.developerMode,
                onCheckedChange = { onIntent(SessionIntent.SetDeveloperMode(it)) },
            )
        }
        OutlinedButton(
            onClick = { onIntent(SessionIntent.ReloadProject) },
            enabled = !state.configurationLocked,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("重新加载项目")
        }
        if (state.developerMode) {
            val diagnostics = buildList {
                (state.projectState as? ProjectState.Ready)?.let { addAll(it.diagnostics) }
                addAll(state.sessionDiagnostics)
            }
            if (diagnostics.isEmpty()) {
                Text(
                    text = "没有诊断信息",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                diagnostics.forEach {
                    Text(
                        text = "[${it.severity}] ${it.source}: ${it.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (it.severity == DiagnosticSeverity.Error) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AboutCard() {
    MaaCard(title = "关于") {
        MaaInfoRow("版本", BuildConfig.VERSION_NAME)
        MaaInfoRow("构建", BuildConfig.VERSION_CODE.toString())
    }
}
