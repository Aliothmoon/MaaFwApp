package com.aliothmoon.maafw.ui.settings

import com.aliothmoon.maafw.settings.search.SettingAnchors
import com.aliothmoon.maafw.settings.search.SettingsSections
import com.aliothmoon.maafw.ui.settings.search.sectionRevealToken
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.domain.OptionValue
import com.aliothmoon.maafw.session.SessionIntent
import com.aliothmoon.maafw.session.SessionUiState
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.theme.MaaTheme
import com.aliothmoon.maafw.ui.components.MaaCard
import com.aliothmoon.maafw.ui.components.MaaDescriptionPanel
import com.aliothmoon.maafw.ui.components.MaaMarkdown
import com.aliothmoon.maafw.ui.components.MaaPiIcon
import com.aliothmoon.maafw.ui.options.OptionEditorList

/**
 * PI 带来的设置（`setting[]` 分区、全局、资源、控制器选项）收进同一个「任务设置」分组，
 * 对齐 MXU 的同名分栏：和 FwApp 自己的显示、通知这些卡混排时分不出哪张是项目的
 */
@Composable
internal fun TaskSettingsGroup(state: SessionUiState, onIntent: (SessionIntent) -> Unit) {
    // 与各卡自己的出现条件同源，全都不出时连分组的壳也不留
    val hasResource = state.resourceOptions.isNotEmpty() && state.environment?.resource != null
    val hasController = state.controllerOptions.isNotEmpty() && state.environment?.controller != null
    if (state.globalOptions.isEmpty() && state.settingSections.isEmpty() && !hasResource && !hasController) return
    Surface(
        shape = RoundedCornerShape(MaaTheme.style.radii.large),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(MaaDesignTokens.Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
        ) {
            Column(
                modifier = Modifier.padding(
                    horizontal = MaaDesignTokens.Spacing.xs,
                    vertical = MaaDesignTokens.Spacing.xs,
                ),
                verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xxs),
            ) {
                Text(
                    text = stringResource(R.string.settings_group_task),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.settings_group_task_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TaskSettingCards(state, onIntent)
            ResourceOptionCard(state, onIntent)
            ControllerOptionCard(state, onIntent)
        }
    }
}

/**
 * PI `setting[]` 的每个分区一张卡，按声明顺序排；没被任何分区收录的留在末尾的「通用」卡
 *
 * 落在设置页而不是任务页：`global_option` 对所有运行配置生效
 */
@Composable
private fun TaskSettingCards(state: SessionUiState, onIntent: (SessionIntent) -> Unit) {
    val onSetOption: (String, OptionValue) -> Unit = { name, value ->
        onIntent(SessionIntent.SetGlobalOption(name, value))
    }
    state.settingSections.forEach { section ->
        // 分区会随 PI 更新增减、重排；按名字 key 住，折叠态才跟着分区走而不是跟着位置走
        key(section.name) {
            MaaCard(
                title = section.label,
                leading = section.icon?.let { { MaaPiIcon(it, MaaDesignTokens.IconSize.sm, null) } },
                collapsible = true,
                initiallyExpanded = section.defaultExpand,
                revealToken = sectionRevealToken(SettingsSections.projectSection(section.name)),
            ) {
                section.description?.let {
                    MaaDescriptionPanel {
                        MaaMarkdown(text = it, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
                OptionEditorList(
                    options = section.options,
                    locked = state.configurationLocked,
                    onSetOption = onSetOption,
                    // 同一个选项可以进好几个分区，锚点按分区分开，免得几处同时滚动、闪烁
                    searchAnchor = { SettingAnchors.projectOption(SettingsSections.projectSection(section.name), it.name) },
                )
            }
        }
    }
    val grouped = state.settingSections.flatMapTo(mutableSetOf()) { section -> section.options.map { it.name } }
    val ungrouped = state.globalOptions.filterNot { it.name in grouped }
    if (ungrouped.isEmpty()) return
    MaaCard(
        title = stringResource(R.string.settings_section_global_option),
        collapsible = true,
        revealToken = sectionRevealToken(SettingsSections.PI_GLOBAL),
    ) {
        OptionEditorList(
            options = ungrouped,
            locked = state.configurationLocked,
            onSetOption = onSetOption,
            searchAnchor = { SettingAnchors.projectOption(SettingsSections.PI_GLOBAL, it.name) },
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
    MaaCard(
        title = stringResource(R.string.settings_section_resource_option),
        collapsible = true,
        revealToken = sectionRevealToken(SettingsSections.PI_RESOURCE),
    ) {
        OptionEditorList(
            options = state.resourceOptions,
            locked = state.configurationLocked,
            searchAnchor = { SettingAnchors.projectOption(SettingsSections.PI_RESOURCE, it.name) },
            onSetOption = { name, value ->
                onIntent(SessionIntent.SetResourceOption(resourceName, name, value))
            },
        )
    }
}

/** 同 [ResourceOptionCard]：`controller.option` 对所有运行配置生效，值按 controller name 分桶 */
@Composable
private fun ControllerOptionCard(state: SessionUiState, onIntent: (SessionIntent) -> Unit) {
    if (state.controllerOptions.isEmpty()) return
    val controllerName = state.environment?.controller?.name ?: return
    MaaCard(
        title = stringResource(R.string.settings_section_controller_option),
        collapsible = true,
        revealToken = sectionRevealToken(SettingsSections.PI_CONTROLLER),
    ) {
        OptionEditorList(
            options = state.controllerOptions,
            locked = state.configurationLocked,
            searchAnchor = { SettingAnchors.projectOption(SettingsSections.PI_CONTROLLER, it.name) },
            onSetOption = { name, value ->
                onIntent(SessionIntent.SetControllerOption(controllerName, name, value))
            },
        )
    }
}
