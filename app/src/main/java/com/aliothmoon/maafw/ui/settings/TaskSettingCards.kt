package com.aliothmoon.maafw.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.domain.OptionValue
import com.aliothmoon.maafw.session.SessionIntent
import com.aliothmoon.maafw.session.SessionUiState
import com.aliothmoon.maafw.settings.search.SettingAnchors
import com.aliothmoon.maafw.settings.search.SettingsSections
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.ui.components.CardCollapse
import com.aliothmoon.maafw.ui.components.CardExpand
import com.aliothmoon.maafw.ui.components.LocalCardRowBleed
import com.aliothmoon.maafw.ui.components.MaaDescriptionPanel
import com.aliothmoon.maafw.ui.components.MaaExpandChevron
import com.aliothmoon.maafw.ui.components.MaaMarkdown
import com.aliothmoon.maafw.ui.components.MaaPiIcon
import com.aliothmoon.maafw.ui.components.MaaSettingsSection
import com.aliothmoon.maafw.ui.components.cardRowClickable
import com.aliothmoon.maafw.ui.options.OptionEditorList
import com.aliothmoon.maafw.ui.settings.search.sectionPrefixRevealToken
import com.aliothmoon.maafw.ui.settings.search.sectionRevealToken

/**
 * PI 带来的设置（`setting[]` 分区、全局、资源、控制器选项）收进同一个「任务设置」分组卡：
 * 外壳与设置页其余分区共用 [MaaSettingsSection]，卡内各子分区按分隔行就地展开，
 * 不再卡套卡或缩窄宽度
 */
@Composable
internal fun TaskSettingsGroup(state: SessionUiState, onIntent: (SessionIntent) -> Unit) {
    // 与各子项自己的出现条件同源，全都不出时连分组卡也不留
    val hasResource = state.resourceOptions.isNotEmpty() && state.environment?.resource != null
    val hasController = state.controllerOptions.isNotEmpty() && state.environment?.controller != null
    if (state.globalOptions.isEmpty() && state.settingSections.isEmpty() && !hasResource && !hasController) return
    MaaSettingsSection(
        title = stringResource(R.string.settings_group_task),
        info = stringResource(R.string.settings_group_task_desc),
        revealToken = sectionPrefixRevealToken(SettingsSections.PI_PREFIX),
        dividerInset = 0.dp,
    ) {
        TaskSettingCards(state, onIntent)
        ResourceOptionCard(state, onIntent)
        ControllerOptionCard(state, onIntent)
    }
}

/**
 * PI `setting[]` 的每个分区一行可折叠子区，按声明顺序排；没被任何分区收录的留在末尾的「通用」
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
            TaskSettingSubSection(
                title = section.label,
                icon = section.icon,
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
    TaskSettingSubSection(
        title = stringResource(R.string.settings_section_global_option),
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
 * 名取「资源设置」：和「任务设置」内其余子区并列，值按 resource name 分桶
 */
@Composable
private fun ResourceOptionCard(state: SessionUiState, onIntent: (SessionIntent) -> Unit) {
    if (state.resourceOptions.isEmpty()) return
    val resourceName = state.environment?.resource?.name ?: return
    TaskSettingSubSection(
        title = stringResource(R.string.settings_section_resource_option),
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
    TaskSettingSubSection(
        title = stringResource(R.string.settings_section_controller_option),
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

/**
 * 任务设置卡内的可折叠子分区：56dp 标准行高，默认收起（分区一多整页全是选项，不看 PI 的 default_expand）
 */
@Composable
private fun TaskSettingSubSection(
    title: String,
    icon: String? = null,
    revealToken: Any? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    if (revealToken != null) {
        LaunchedEffect(revealToken) { expanded = true }
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .cardRowClickable(LocalCardRowBleed.current) { expanded = !expanded }
                .semantics { role = Role.Button }
                .heightIn(min = MaaDesignTokens.RowHeight.setting)
                .padding(vertical = MaaDesignTokens.Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
        ) {
            icon?.let { MaaPiIcon(it, MaaDesignTokens.IconSize.md, null) }
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            MaaExpandChevron(expanded)
        }
        AnimatedVisibility(visible = expanded, enter = CardExpand, exit = CardCollapse) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = MaaDesignTokens.Spacing.md),
                verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
                content = content,
            )
        }
    }
}
