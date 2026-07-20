package com.aliothmoon.maafw.config

import com.aliothmoon.maafw.domain.ConfiguredTask
import com.aliothmoon.maafw.domain.Diagnostic
import com.aliothmoon.maafw.domain.Diagnostic.Companion.warning
import com.aliothmoon.maafw.domain.DiagnosticMessage
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
import com.aliothmoon.maafw.domain.UnavailableReason
import com.aliothmoon.maafw.domain.UserConfiguration
import java.util.UUID

/**
 * 定义与用户状态的只读投影。纯函数，不持久化、不产生副作用；
 * 派生的 effectiveEnabled / applicable 不写回 DataStore。
 */
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
                    DiagnosticMessage.ResourceSelectionMissing(
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
            diagnostics += warning("configuration", DiagnosticMessage.ActiveConfigurationMissing)
        }

        return ResolvedProjectSession(
            configurationList = configurationList,
            activeConfiguration = activeConfiguration,
            taskCatalog = buildTaskCatalog(definition, resourceName),
            environment = environment,
            diagnostics = diagnostics,
        )
    }

    /** 首次初始化：每个 PI preset 创建一个独立 RunConfiguration；没有 preset 保持空列表。 */
    fun initialize(definition: ProjectDefinition, config: UserConfiguration): UserConfiguration {
        // 与用户手动「从模板创建」走同一条投影，保证两条路径不漂移
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
     * 从模板创建新配置：仅创建瞬间复制内容，不保存模板引用。
     * configurationName 为空白/null 时沿用模板展示名（label）；taskNames 为 null 表示全部任务，
     * 非 null 时按模板声明顺序保留其中出现的任务。
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
                    DiagnosticMessage.ConfiguredTaskMissing(configured.taskName),
                )
                ResolvedConfiguredTask(
                    instanceId = configured.instanceId,
                    taskName = configured.taskName,
                    label = configured.taskName,
                    description = null,
                    enabled = configured.enabled,
                    applicable = false,
                    missingDefinition = true,
                    unavailableReason = UnavailableReason.MissingDefinition,
                    options = emptyList(),
                )
            } else {
                val applicability = checkApplicability(definition, taskDefinition, resourceName)
                ResolvedConfiguredTask(
                    instanceId = configured.instanceId,
                    taskName = configured.taskName,
                    label = taskDefinition.label,
                    description = taskDefinition.description,
                    enabled = configured.enabled,
                    applicable = applicability == null,
                    missingDefinition = false,
                    unavailableReason = applicability,
                    options = buildOptionEditors(
                        definition = definition,
                        optionNames = taskDefinition.optionNames,
                        values = configured.optionValues,
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

    /** 返回 null 表示适用；否则返回结构化不可用原因（文案由 UI 层本地化）。 */
    fun checkApplicability(
        definition: ProjectDefinition,
        task: TaskDefinition,
        resourceName: String?,
    ): UnavailableReason? {
        val controllerOk = task.controllers.isEmpty() ||
            task.controllers.any {
                it.equals(definition.controller.type, ignoreCase = true) ||
                    it.equals(definition.controller.name, ignoreCase = true)
            }
        if (!controllerOk) return UnavailableReason.ControllerMismatch(task.controllers)
        val resourceOk = task.resources.isEmpty() ||
            (resourceName != null && task.resources.any { it == resourceName })
        if (!resourceOk) return UnavailableReason.ResourceMismatch(task.resources)
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

    /**
     * 构建 option 编辑树：只物化活动分支（active branch），
     * dormant 值保留在持久层；防御 cycle 与超深嵌套。
     */
    private fun buildOptionEditors(
        definition: ProjectDefinition,
        optionNames: List<String>,
        values: Map<String, OptionValue>,
        depth: Int = 0,
        visited: Set<String> = emptySet(),
    ): List<OptionEditorState> {
        if (depth > MAX_OPTION_DEPTH) return emptyList()
        return optionNames.mapNotNull { name ->
            if (name in visited) return@mapNotNull null
            val option = definition.options[name] ?: return@mapNotNull null
            buildOptionEditor(definition, option, values, depth, visited + name)
        }
    }

    private fun buildOptionEditor(
        definition: ProjectDefinition,
        option: OptionDefinition,
        values: Map<String, OptionValue>,
        depth: Int,
        visited: Set<String>,
    ): OptionEditorState {
        val value = values[option.name]
        return when (option) {
            is OptionDefinition.Choice -> {
                // Unset 且没有 defaultCase 时保持无活动 case，不隐式选第一项
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
                    cases = buildCaseStates(definition, option.cases, setOfNotNull(selected), values, depth, visited),
                    inputs = emptyList(),
                )
            }

            is OptionDefinition.Checkbox -> {
                // MultipleCases(emptyList()) 是明确的“全不选”，仅 Unset 才回退默认值
                val selected = (value as? OptionValue.MultipleCases)?.cases ?: option.defaultCases
                OptionEditorState(
                    name = option.name,
                    label = option.label,
                    description = option.description,
                    kind = OptionKind.Checkbox,
                    depth = depth,
                    value = value,
                    cases = buildCaseStates(definition, option.cases, selected.toSet(), values, depth, visited),
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

    /** Choice/Checkbox 共用的 case 投影：仅活动 case 物化子树。 */
    private fun buildCaseStates(
        definition: ProjectDefinition,
        cases: List<OptionCaseDefinition>,
        selected: Set<String>,
        values: Map<String, OptionValue>,
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
                buildOptionEditors(definition, case.childOptionNames, values, depth + 1, visited)
            } else {
                emptyList()
            },
        )
    }
}
