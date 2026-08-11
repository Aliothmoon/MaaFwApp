package com.aliothmoon.maafw.config

import com.aliothmoon.maafw.domain.ConfiguredTask
import com.aliothmoon.maafw.domain.Diagnostic
import com.aliothmoon.maafw.domain.Diagnostic.Companion.warning
import com.aliothmoon.maafw.domain.DiagnosticMessages
import com.aliothmoon.maafw.domain.InputFieldState
import com.aliothmoon.maafw.domain.OptionCaseDefinition
import com.aliothmoon.maafw.domain.OptionCaseState
import com.aliothmoon.maafw.domain.OptionDefinition
import com.aliothmoon.maafw.domain.OptionEditorState
import com.aliothmoon.maafw.domain.OptionKind
import com.aliothmoon.maafw.domain.OptionValue
import com.aliothmoon.maafw.domain.ProjectDefinition
import com.aliothmoon.maafw.domain.ResolvedConfiguredTask
import com.aliothmoon.maafw.domain.ResolvedEnvironment
import com.aliothmoon.maafw.domain.ResolvedProjectSession
import com.aliothmoon.maafw.domain.ResolvedResource
import com.aliothmoon.maafw.domain.ResolvedRunConfiguration
import com.aliothmoon.maafw.domain.RunConfiguration
import com.aliothmoon.maafw.domain.RunConfigurationId
import com.aliothmoon.maafw.domain.TaskCatalogGroup
import com.aliothmoon.maafw.domain.TaskCatalogItem
import com.aliothmoon.maafw.domain.TaskDefinition
import com.aliothmoon.maafw.domain.UnavailableReasons
import com.aliothmoon.maafw.i18n.UiText
import com.aliothmoon.maafw.domain.UserConfiguration
import java.util.UUID

/** 定义 × 用户状态投影；纯函数，effectiveEnabled/applicable 不写回 DataStore */
object ConfigurationResolver {

    private const val MAX_OPTION_DEPTH = 8

    fun resolve(definition: ProjectDefinition, config: UserConfiguration): ResolvedProjectSession {
        val diagnostics = mutableListOf<Diagnostic>()

        val resourceNames = definition.resources.map { it.name }
        val resourceName = when {
            config.activeResourceName != null && config.activeResourceName in resourceNames ->
                config.activeResourceName

            config.activeResourceName != null -> {
                diagnostics += warning(
                    "resource",
                    DiagnosticMessages.resourceSelectionMissing(
                        selected = config.activeResourceName,
                        fallback = resourceNames.firstOrNull(),
                    ),
                )
                resourceNames.firstOrNull()
            }

            else -> resourceNames.firstOrNull()
        }

        val environment = ResolvedEnvironment(
            controllerName = definition.controller.name,
            resource = definition.resources.firstOrNull { it.name == resourceName }
                ?.let { ResolvedResource(it.name, it.label) },
            resourceCandidates = definition.resources.map { ResolvedResource(it.name, it.label) },
        )

        val configurationList = config.configurations.map { runConfiguration ->
            resolveConfiguration(definition, runConfiguration, resourceName, config, diagnostics)
        }
        val activeConfiguration = configurationList.firstOrNull { it.isActive }
        if (config.activeConfigurationId != null && activeConfiguration == null) {
            diagnostics += warning("configuration", DiagnosticMessages.activeConfigurationMissing())
        }

        return ResolvedProjectSession(
            configurationList = configurationList,
            activeConfiguration = activeConfiguration,
            taskCatalog = buildTaskCatalog(definition, resourceName),
            globalOptions = buildOptionEditors(
                definition = definition,
                optionNames = definition.globalOptionNames,
                values = config.globalOptionValues,
                resourceName = resourceName,
            ),
            environment = environment,
            diagnostics = diagnostics,
        )
    }

    /** 每个 preset 一份配置；无 preset 则空列表 */
    fun initialize(definition: ProjectDefinition, config: UserConfiguration): UserConfiguration {
        // 与「从模板创建」同路径，避免两条入口语义漂移
        val configurations = definition.templates.mapNotNull { createFromTemplate(definition, it.name) }
        return config.copy(
            initialized = true,
            configurations = configurations,
            activeConfigurationId = configurations.firstOrNull()?.id,
            activeResourceName = config.activeResourceName
                ?: definition.resources.firstOrNull()?.name,
        )
    }

    /**
     * 创建瞬间复制模板，不保存模板引用
     * configurationName 空白/null 沿用 label；taskNames null = 全部，非 null 按声明序过滤
     */
    fun createFromTemplate(
        definition: ProjectDefinition,
        templateName: String,
        configurationName: String? = null,
        taskNames: List<String>? = null,
    ): RunConfiguration? {
        val template = definition.templates.firstOrNull { it.name == templateName } ?: return null
        val included = taskNames?.toSet()
        return RunConfiguration(
            id = newConfigurationId(),
            name = configurationName?.takeIf { it.isNotBlank() } ?: template.label,
            tasks = template.distinctTasks
                .filter { included == null || it.taskName in included }
                .map { ConfiguredTask(it.taskName, it.enabled, it.optionValues) },
        )
    }

    fun newConfigurationId(): RunConfigurationId = RunConfigurationId(UUID.randomUUID().toString())

    private fun resolveConfiguration(
        definition: ProjectDefinition,
        runConfiguration: RunConfiguration,
        resourceName: String?,
        config: UserConfiguration,
        diagnostics: MutableList<Diagnostic>,
    ): ResolvedRunConfiguration {
        val tasks = runConfiguration.tasks.map { configured ->
            val taskDefinition = definition.task(configured.taskName)
            if (taskDefinition == null) {
                diagnostics += warning(
                    "configuration:${runConfiguration.name}",
                    DiagnosticMessages.configuredTaskMissing(configured.taskName),
                )
                ResolvedConfiguredTask(
                    instanceId = configured.instanceId,
                    taskName = configured.taskName,
                    label = configured.customLabel ?: configured.taskName,
                    description = null,
                    enabled = configured.enabled,
                    applicable = false,
                    missingDefinition = true,
                    unavailableReason = UnavailableReasons.missingDefinition(),
                    options = emptyList(),
                )
            } else {
                val applicability = checkApplicability(definition, taskDefinition, resourceName)
                ResolvedConfiguredTask(
                    instanceId = configured.instanceId,
                    taskName = configured.taskName,
                    label = configured.customLabel ?: taskDefinition.label,
                    description = taskDefinition.description,
                    enabled = configured.enabled,
                    applicable = applicability == null,
                    missingDefinition = false,
                    unavailableReason = applicability,
                    options = buildOptionEditors(
                        definition = definition,
                        optionNames = taskDefinition.optionNames,
                        values = configured.optionValues,
                        resourceName = resourceName,
                    ),
                )
            }
        }
        return ResolvedRunConfiguration(
            id = runConfiguration.id,
            name = runConfiguration.name,
            isActive = runConfiguration.id == config.activeConfigurationId,
            tasks = tasks,
        )
    }

    /** null = 适用；否则给出不适用的原因文案 */
    fun checkApplicability(
        definition: ProjectDefinition,
        task: TaskDefinition,
        resourceName: String?,
    ): UiText? {
        val controllerOk = task.controllers.isEmpty() ||
            task.controllers.any {
                it.equals(definition.controller.type, ignoreCase = true) ||
                    it.equals(definition.controller.name, ignoreCase = true)
            }
        if (!controllerOk) return UnavailableReasons.controllerMismatch(task.controllers)
        val resourceOk = task.resources.isEmpty() ||
            (resourceName != null && task.resources.any { it == resourceName })
        if (!resourceOk) return UnavailableReasons.resourceMismatch(task.resources)
        return null
    }

    private fun buildTaskCatalog(
        definition: ProjectDefinition,
        resourceName: String?,
    ): List<TaskCatalogGroup> {
        return definition.groups.map { group ->
            val tasks = definition.tasks.filter { task ->
                if (group.isUngrouped) task.groups.isEmpty()
                else group.name in task.groups
            }.map { task ->
                val reason = checkApplicability(definition, task, resourceName)
                TaskCatalogItem(
                    taskName = task.name,
                    label = task.label,
                    description = task.description,
                    applicable = reason == null,
                    unavailableReason = reason,
                    defaultChecked = task.defaultCheck,
                )
            }
            TaskCatalogGroup(group.name, group.label, tasks, isUngrouped = group.isUngrouped)
        }.filter { it.tasks.isNotEmpty() }
    }

    /** 只物化 active branch；dormant 留持久层；防 cycle / 过深嵌套 */
    private fun buildOptionEditors(
        definition: ProjectDefinition,
        optionNames: List<String>,
        values: Map<String, OptionValue>,
        resourceName: String?,
        depth: Int = 0,
        visited: Set<String> = emptySet(),
    ): List<OptionEditorState> {
        if (depth > MAX_OPTION_DEPTH) return emptyList()
        return optionNames.mapNotNull { name ->
            if (name in visited) return@mapNotNull null
            val option = definition.options[name] ?: return@mapNotNull null
            if (!option.applicability.matches(definition.controller.name, resourceName)) {
                return@mapNotNull null
            }
            buildOptionEditor(definition, option, values, resourceName, depth, visited + name)
        }
    }

    private fun buildOptionEditor(
        definition: ProjectDefinition,
        option: OptionDefinition,
        values: Map<String, OptionValue>,
        resourceName: String?,
        depth: Int,
        visited: Set<String>,
    ): OptionEditorState {
        val value = values[option.name]
        return when (option) {
            is OptionDefinition.Choice -> {
                // Unset 且无 defaultCase 时不隐式选第一项
                val selected = (value as? OptionValue.SingleCase)?.case
                    ?.takeIf { s -> option.cases.any { it.name == s } }
                    ?: option.defaultCase
                OptionEditorState(
                    name = option.name,
                    label = option.label,
                    description = option.description,
                    kind = if (option is OptionDefinition.Select) OptionKind.Select else OptionKind.Switch,
                    depth = depth,
                    value = value,
                    cases = buildCaseStates(definition, option.cases, setOfNotNull(selected), values, resourceName, depth, visited),
                    inputs = emptyList(),
                )
            }

            is OptionDefinition.Checkbox -> {
                // emptyList() = 明确全不选；仅 Unset 才回退默认
                val selected = (value as? OptionValue.MultipleCases)?.cases ?: option.defaultCases
                OptionEditorState(
                    name = option.name,
                    label = option.label,
                    description = option.description,
                    kind = OptionKind.Checkbox,
                    depth = depth,
                    value = value,
                    cases = buildCaseStates(definition, option.cases, selected.toSet(), values, resourceName, depth, visited),
                    inputs = emptyList(),
                )
            }

            is OptionDefinition.Input -> {
                val inputValues = (value as? OptionValue.Inputs)?.values ?: emptyMap()
                OptionEditorState(
                    name = option.name,
                    label = option.label,
                    description = option.description,
                    kind = OptionKind.Input,
                    depth = depth,
                    value = value,
                    cases = emptyList(),
                    inputs = option.fields.map { field ->
                        InputFieldState(
                            name = field.name,
                            label = field.label,
                            pipelineType = field.pipelineType,
                            value = inputValues[field.name] ?: field.default,
                            default = field.default,
                            verify = field.verify,
                            patternMessage = field.patternMessage,
                            description = field.description,
                        )
                    },
                )
            }
        }
    }

    /** 仅活动 case 物化子树 */
    private fun buildCaseStates(
        definition: ProjectDefinition,
        cases: List<OptionCaseDefinition>,
        selected: Set<String>,
        values: Map<String, OptionValue>,
        resourceName: String?,
        depth: Int,
        visited: Set<String>,
    ): List<OptionCaseState> = cases.map { case ->
        val active = case.name in selected
        OptionCaseState(
            name = case.name,
            label = case.label,
            description = case.description,
            active = active,
            children = if (active) {
                buildOptionEditors(definition, case.childOptionNames, values, resourceName, depth + 1, visited)
            } else {
                emptyList()
            },
        )
    }
}
