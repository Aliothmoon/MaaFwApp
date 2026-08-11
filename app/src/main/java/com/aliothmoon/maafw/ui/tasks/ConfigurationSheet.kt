package com.aliothmoon.maafw.ui.tasks

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.domain.ConfigurationTemplate
import com.aliothmoon.maafw.domain.ResolvedRunConfiguration
import com.aliothmoon.maafw.domain.RunConfigurationId
import com.aliothmoon.maafw.domain.TemplateTask
import com.aliothmoon.maafw.session.SessionUiState
import com.aliothmoon.maafw.i18n.asString
import com.aliothmoon.maafw.i18n.uiTextPlural
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.theme.MaaMotion
import com.aliothmoon.maafw.theme.MaaTheme
import com.aliothmoon.maafw.ui.components.MaaButton
import com.aliothmoon.maafw.ui.components.MaaCard
import com.aliothmoon.maafw.ui.components.MaaDescriptionPanel
import com.aliothmoon.maafw.ui.components.MaaMarkdown
import com.aliothmoon.maafw.ui.components.MaaModalSheet
import com.aliothmoon.maafw.ui.components.MaaSelectableCard
import com.aliothmoon.maafw.ui.components.MaaSelectionMarker
import com.aliothmoon.maafw.ui.components.MaaSheetHeader
import com.aliothmoon.maafw.ui.components.MaaSingleChoiceFlow
import com.aliothmoon.maafw.ui.components.MaaToneBadge
import com.aliothmoon.maafw.ui.components.maaClickable

private sealed interface ConfigSheetPage {
    /** 导航深度；AnimatedContent 用 depth 差决定滑入方向 */
    val depth: Int

    data object Home : ConfigSheetPage {
        override val depth = 0
    }

    data class TemplatePreview(val templateName: String) : ConfigSheetPage {
        override val depth = 1
    }

    data object CreateEmpty : ConfigSheetPage {
        override val depth = 1
    }

    data class Rename(val id: RunConfigurationId) : ConfigSheetPage {
        override val depth = 1
    }
}

@Composable
internal fun ConfigurationSheet(
    state: SessionUiState,
    templates: List<ConfigurationTemplate>,
    locked: Boolean,
    onSelect: (RunConfigurationId) -> Unit,
    onDuplicate: (RunConfigurationId, String) -> Unit,
    onRename: (RunConfigurationId, String) -> Unit,
    onDelete: (RunConfigurationId) -> Unit,
    onCreateEmpty: (name: String) -> Unit,
    onCreateFromTemplate: (templateName: String, configurationName: String, taskNames: List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var page by remember { mutableStateOf<ConfigSheetPage>(ConfigSheetPage.Home) }
    var homeTab by rememberSaveable { mutableIntStateOf(0) }
    val existingNames = state.configurationList.map { it.name }

    MaaModalSheet(onDismiss = onDismiss) { sheetModifier ->
        AnimatedContent(
            targetState = page,
            transitionSpec = {
                val forward = targetState.depth > initialState.depth
                val enter = fadeIn(MaaMotion.enter()) + slideInHorizontally(MaaMotion.enter()) {
                    if (forward) it / 3 else -it / 3
                }
                val exit = fadeOut(MaaMotion.exit()) + slideOutHorizontally(MaaMotion.exit()) {
                    if (forward) -it / 3 else it / 3
                }
                enter.togetherWith(exit)
            },
            label = "configSheetPage",
            modifier = sheetModifier,
        ) { current ->
            when (current) {
                is ConfigSheetPage.Home -> ConfigSheetHomePage(
                    state = state,
                    templates = templates,
                    locked = locked,
                    tab = homeTab,
                    onTabChange = { homeTab = it },
                    onSelect = onSelect,
                    onDuplicate = onDuplicate,
                    onOpenRename = { page = ConfigSheetPage.Rename(it) },
                    onDelete = onDelete,
                    onOpenCreateEmpty = { page = ConfigSheetPage.CreateEmpty },
                    onOpenTemplate = { page = ConfigSheetPage.TemplatePreview(it) },
                    onClose = onDismiss,
                )

                is ConfigSheetPage.Rename -> {
                    val configuration = state.configurationList.firstOrNull { it.id == current.id }
                    if (configuration == null) {
                        LaunchedEffect(current) { page = ConfigSheetPage.Home }
                    } else {
                        RenameConfigurationPage(
                            configuration = configuration,
                            writeEnabled = !locked,
                            onBack = { page = ConfigSheetPage.Home },
                            onClose = onDismiss,
                            onRename = { name ->
                                page = ConfigSheetPage.Home
                                onRename(configuration.id, name)
                            },
                        )
                    }
                }

                is ConfigSheetPage.CreateEmpty -> CreateEmptyPage(
                    existingNames = existingNames,
                    writeEnabled = !locked,
                    onBack = { page = ConfigSheetPage.Home },
                    onClose = onDismiss,
                    onCreate = onCreateEmpty,
                )

                is ConfigSheetPage.TemplatePreview -> {
                    val template = templates.firstOrNull { it.name == current.templateName }
                    if (template == null) {
                        LaunchedEffect(current) { page = ConfigSheetPage.Home }
                    } else {
                        TemplatePreviewPage(
                            template = template,
                            existingNames = existingNames,
                            writeEnabled = !locked,
                            onBack = { page = ConfigSheetPage.Home },
                            onClose = onDismiss,
                            onCreate = { name, taskNames ->
                                onCreateFromTemplate(template.name, name, taskNames)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfigSheetHomePage(
    state: SessionUiState,
    templates: List<ConfigurationTemplate>,
    locked: Boolean,
    tab: Int,
    onTabChange: (Int) -> Unit,
    onSelect: (RunConfigurationId) -> Unit,
    onDuplicate: (RunConfigurationId, String) -> Unit,
    onOpenRename: (RunConfigurationId) -> Unit,
    onDelete: (RunConfigurationId) -> Unit,
    onOpenCreateEmpty: () -> Unit,
    onOpenTemplate: (String) -> Unit,
    onClose: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.md)) {
        MaaSheetHeader(title = stringResource(R.string.config_title), onClose = onClose)
        val tabs = buildList {
            add(0 to stringResource(R.string.config_select))
            if (templates.isNotEmpty()) {
                add(1 to stringResource(R.string.config_create_from_template))
            }
        }
        MaaSingleChoiceFlow(
            options = tabs,
            selected = if (templates.isEmpty()) 0 else tab,
            onSelect = onTabChange,
        )
        val showTemplates = tab == 1 && templates.isNotEmpty()
        Crossfade(
            targetState = showTemplates,
            animationSpec = MaaMotion.enter(),
            label = "configSheetTab",
            modifier = Modifier.weight(1f),
        ) { templatesPage ->
            if (!templatesPage) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
                ) {
                    items(state.configurationList, key = { it.id.value }) { configuration ->
                        val duplicatedName = uniqueConfigurationName(
                            base = stringResource(R.string.config_copy_name, configuration.name),
                            existing = state.configurationList.map { it.name },
                        )
                        ConfigRowCard(
                            configuration = configuration,
                            writeEnabled = !locked,
                            onSelect = { onSelect(configuration.id) },
                            onDuplicate = { onDuplicate(configuration.id, duplicatedName) },
                            onRename = { onOpenRename(configuration.id) },
                            onDelete = { onDelete(configuration.id) },
                        )
                    }
                    item(key = "create-empty") {
                        CreateEmptyCard(
                            writeEnabled = !locked,
                            onClick = onOpenCreateEmpty,
                        )
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm)) {
                    Text(
                        text = stringResource(R.string.config_template_list_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
                    ) {
                        items(templates, key = { "template-${it.name}" }) { template ->
                            TemplateCard(
                                template = template,
                                writeEnabled = !locked,
                                onClick = { onOpenTemplate(template.name) },
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.size(MaaDesignTokens.Spacing.sm))
    }
}

@Composable
private fun TemplatePreviewPage(
    template: ConfigurationTemplate,
    existingNames: List<String>,
    writeEnabled: Boolean,
    onBack: () -> Unit,
    onClose: () -> Unit,
    onCreate: (name: String, taskNames: List<String>) -> Unit,
) {
    val templateTasks = remember(template) { template.distinctTasks }
    var name by rememberSaveable(template.name) {
        mutableStateOf(uniqueConfigurationName(template.label, existingNames))
    }
    // 默认全选；创建顺序由模板声明序决定，不按勾选顺序
    val included = remember(template) {
        mutableStateListOf<String>().apply { templateTasks.forEach { add(it.taskName) } }
    }

    Column(verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.md)) {
        MaaSheetHeader(title = stringResource(R.string.config_create_from_template), onClose = onClose, onBack = onBack)
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.config_name_label)) },
            singleLine = true,
            enabled = writeEnabled,
            modifier = Modifier.fillMaxWidth(),
        )
        template.description?.takeIf { it.isNotBlank() }?.let {
            MaaDescriptionPanel {
                MaaMarkdown(
                    text = it,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.config_included_tasks), style = MaterialTheme.typography.titleSmall)
            Text(
                text = stringResource(R.string.config_included_count, included.size, templateTasks.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
            contentPadding = PaddingValues(bottom = MaaDesignTokens.Spacing.xs),
        ) {
            items(templateTasks, key = { it.taskName }) { task ->
                TemplateTaskRow(
                    task = task,
                    checked = task.taskName in included,
                    writeEnabled = writeEnabled,
                    onToggle = { checked -> included.setPresent(task.taskName, checked) },
                )
            }
        }
        MaaButton(
            onClick = { onCreate(name.trim(), included.toList()) },
            enabled = writeEnabled && name.isNotBlank() && included.isNotEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = MaaDesignTokens.Spacing.lg),
        ) {
            Text(
                uiTextPlural(R.plurals.config_create_use_count, included.size, included.size)
                    .asString(),
            )
        }
    }
}

@Composable
private fun TemplateTaskRow(
    task: TemplateTask,
    checked: Boolean,
    writeEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    TaskPickRow(
        label = task.label,
        checked = checked,
        enabled = writeEnabled,
        onToggle = onToggle,
        trailing = if (!task.enabled) {
            {
                MaaToneBadge(
                    text = stringResource(R.string.config_task_disabled_by_default),
                    tone = MaaTheme.palette.neutral,
                )
            }
        } else {
            null
        },
    )
}

@Composable
private fun CreateEmptyPage(
    existingNames: List<String>,
    writeEnabled: Boolean,
    onBack: () -> Unit,
    onClose: () -> Unit,
    onCreate: (String) -> Unit,
) {
    val defaultName = stringResource(R.string.config_empty_default_name)
    var name by rememberSaveable {
        mutableStateOf(uniqueConfigurationName(defaultName, existingNames))
    }
    Column(verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.md)) {
        MaaSheetHeader(title = stringResource(R.string.config_create_empty), onClose = onClose, onBack = onBack)
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.config_name_label)) },
            singleLine = true,
            enabled = writeEnabled,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stringResource(R.string.config_create_empty_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        MaaButton(
            onClick = { onCreate(name.trim()) },
            enabled = writeEnabled && name.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = MaaDesignTokens.Spacing.lg),
        ) {
            Text(stringResource(R.string.config_create_use))
        }
    }
}

@Composable
private fun RenameConfigurationPage(
    configuration: ResolvedRunConfiguration,
    writeEnabled: Boolean,
    onBack: () -> Unit,
    onClose: () -> Unit,
    onRename: (String) -> Unit,
) {
    var name by remember(configuration.id) {
        mutableStateOf(
            TextFieldValue(configuration.name, selection = TextRange(0, configuration.name.length)),
        )
    }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (writeEnabled) focusRequester.requestFocus() }
    val commit = { if (writeEnabled && name.text.isNotBlank()) onRename(name.text.trim()) }

    Column(verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.md)) {
        MaaSheetHeader(title = stringResource(R.string.config_rename_title), onClose = onClose, onBack = onBack)
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.config_name_label)) },
            singleLine = true,
            enabled = writeEnabled,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commit() }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
        )
        Spacer(Modifier.weight(1f))
        MaaButton(
            onClick = commit,
            enabled = writeEnabled && name.text.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = MaaDesignTokens.Spacing.lg),
        ) {
            Text(stringResource(R.string.common_save))
        }
    }
}

/** 预填不重名建议名（仅预填，不强制唯一） */
private fun uniqueConfigurationName(base: String, existing: Collection<String>): String {
    if (base !in existing) return base
    var index = 2
    while ("$base $index" in existing) index++
    return "$base $index"
}

/** 虚线感的新建入口：只有描边没有底色，和上面那串实心卡片区分开 */
@Composable
private fun CreateEmptyCard(
    writeEnabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .maaClickable(enabled = writeEnabled, onClick = onClick)
            .alpha(if (writeEnabled) 1f else MaaDesignTokens.Alpha.disabled),
        shape = MaterialTheme.shapes.medium,
        color = Color.Transparent,
        border = BorderStroke(MaaDesignTokens.Separator.thickness, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MaaDesignTokens.Spacing.md),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(MaaDesignTokens.IconSize.sm),
            )
            Spacer(Modifier.width(MaaDesignTokens.Spacing.xs))
            Text(
                text = stringResource(R.string.config_create_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun ConfigRowCard(
    configuration: ResolvedRunConfiguration,
    writeEnabled: Boolean,
    onSelect: () -> Unit,
    onDuplicate: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val active = configuration.isActive
    val contentColor = if (active) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    MaaSelectableCard(
        selected = active,
        onClick = onSelect,
        enabled = writeEnabled,
    ) {
        Row(
            modifier = Modifier.padding(
                start = MaaDesignTokens.Spacing.md,
                end = MaaDesignTokens.Spacing.xs,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MaaSelectionMarker(selected = active)
            Spacer(Modifier.width(MaaDesignTokens.Spacing.sm))
            Column(Modifier.weight(1f)) {
                Text(
                    text = configuration.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = uiTextPlural(
                        R.plurals.config_task_count,
                        configuration.tasks.size,
                        configuration.tasks.size,
                    ).asString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = MaaDesignTokens.Alpha.secondary),
                )
            }
            IconButton(onClick = onDuplicate, enabled = writeEnabled) {
                Icon(
                    imageVector = Icons.Outlined.ContentCopy,
                    contentDescription = stringResource(R.string.common_copy),
                    tint = contentColor.copy(alpha = MaaDesignTokens.Alpha.secondary),
                    modifier = Modifier.size(MaaDesignTokens.IconSize.md),
                )
            }
            IconButton(onClick = onRename, enabled = writeEnabled) {
                Icon(
                    imageVector = Icons.Outlined.Edit,
                    contentDescription = stringResource(R.string.common_rename),
                    tint = contentColor.copy(alpha = MaaDesignTokens.Alpha.secondary),
                    modifier = Modifier.size(MaaDesignTokens.IconSize.md),
                )
            }
            IconButton(onClick = onDelete, enabled = writeEnabled) {
                Icon(
                    imageVector = Icons.Outlined.DeleteOutline,
                    contentDescription = stringResource(R.string.common_delete),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(MaaDesignTokens.IconSize.md),
                )
            }
        }
    }
}

@Composable
private fun TemplateCard(
    template: ConfigurationTemplate,
    writeEnabled: Boolean,
    onClick: () -> Unit,
) {
    MaaCard(
        modifier = Modifier
            .maaClickable(enabled = writeEnabled, onClick = onClick)
            .alpha(if (writeEnabled) 1f else MaaDesignTokens.Alpha.disabled),
        contentPadding = PaddingValues(MaaDesignTokens.Spacing.md),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
        ) {
            Text(
                text = template.label,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = uiTextPlural(
                    R.plurals.template_task_count,
                    template.tasks.size,
                    template.tasks.count { it.enabled },
                    template.tasks.size,
                ).asString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        template.description?.takeIf { it.isNotBlank() }?.let {
            MaaDescriptionPanel {
                MaaMarkdown(
                    text = it,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 3,
                    linksClickable = false,
                )
            }
        }
    }
}
