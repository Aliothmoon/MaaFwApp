package com.aliothmoon.maafw.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.res.stringResource
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.domain.OptionValue
import com.aliothmoon.maafw.session.SessionIntent
import com.aliothmoon.maafw.session.SessionUiState
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.ui.components.MaaCard
import com.aliothmoon.maafw.ui.components.MaaDescriptionPanel
import com.aliothmoon.maafw.ui.components.MaaMarkdown
import com.aliothmoon.maafw.ui.components.MaaPiIcon
import com.aliothmoon.maafw.ui.options.OptionEditorList

/**
 * 设置页的全局选项：PI `setting[]` 的每个分区一张卡，按声明顺序排；没被任何分区收录的留在末尾的「任务设置」卡
 *
 * 落在设置页而不是任务页：`global_option` 对所有运行配置生效。
 * 兜底卡名取「任务设置」而非「全局选项」：对齐 MXU 同名分区，且「全局」在用户侧不指称什么
 */
@Composable
internal fun TaskSettingCards(state: SessionUiState, onIntent: (SessionIntent) -> Unit) {
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
                )
            }
        }
    }
    val grouped = state.settingSections.flatMapTo(mutableSetOf()) { section -> section.options.map { it.name } }
    val ungrouped = state.globalOptions.filterNot { it.name in grouped }
    if (ungrouped.isEmpty()) return
    MaaCard(title = stringResource(R.string.settings_section_global_option), collapsible = true) {
        OptionEditorList(
            options = ungrouped,
            locked = state.configurationLocked,
            onSetOption = onSetOption,
        )
    }
}
