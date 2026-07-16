package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.config.ConfigurationResolver
import com.aliothmoon.maafw.domain.Diagnostic
import com.aliothmoon.maafw.domain.DiagnosticSeverity
import com.aliothmoon.maafw.domain.InputFieldDefinition
import com.aliothmoon.maafw.domain.OptionDefinition
import com.aliothmoon.maafw.domain.OptionValue
import com.aliothmoon.maafw.domain.PipelineType
import com.aliothmoon.maafw.domain.ProjectDefinition
import com.aliothmoon.maafw.domain.UserConfiguration
import com.aliothmoon.maafw.domain.validateInputCandidate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

sealed interface RunPlanResult {
    data class Success(val plan: RunPlan) : RunPlanResult
    data class Invalid(val diagnostics: List<Diagnostic>) : RunPlanResult

    /** 无活动配置、空配置、全部禁用或全部不适用统一映射到这里。 */
    data object NoExecutableTasks : RunPlanResult
}

/**
 * 纯配置编译模块：ProjectDefinition + UserConfiguration -> RunPlan。
 * 不执行任务、不持有 native handle；UI 不得绕过它拼 pipeline JSON。
 */
object RunPlanBuilder {

    private val PLACEHOLDER = Regex("""\{([^{}]+)}""")

    fun build(definition: ProjectDefinition, config: UserConfiguration): RunPlanResult {
        val diagnostics = mutableListOf<Diagnostic>()

        // 共享环境：固定 Android controller + activeResourceName（缺失回退首个资源）
        val resource = definition.resources.firstOrNull { it.name == config.activeResourceName }
            ?: definition.resources.firstOrNull()
        if (resource == null) {
            diagnostics += runtimeError("environment", "没有可用的 resource，无法构造运行环境")
            return RunPlanResult.Invalid(diagnostics)
        }

        val runConfiguration = config.configuration(config.activeConfigurationId)
            ?: return RunPlanResult.NoExecutableTasks
        if (runConfiguration.tasks.isEmpty()) return RunPlanResult.NoExecutableTasks

        val runtimeTasks = mutableListOf<RuntimeTask>()
        for (configured in runConfiguration.tasks) {
            val task = definition.task(configured.taskName)
            if (task == null) {
                if (configured.enabled) {
                    diagnostics += runtimeError("task:${configured.taskName}", "enabled 任务缺少 definition")
                }
                continue
            }
            // Resolver 的自动禁用用于 UI 反馈，这里是运行时兜底
            val applicable = ConfigurationResolver.checkApplicability(definition, task, resource.name) == null
            if (!configured.enabled || !applicable) continue

            val patches = mutableListOf<JsonObject>()
            if (task.pipelineOverride.isNotEmpty()) patches += task.pipelineOverride
            // patch 顺序：task 基础 patch -> global -> resource -> controller -> task option。
            // 当前示例 PI 未声明 global/controller/resource 作用域 option，对应步骤暂为空。
            compileOptions(
                definition = definition,
                optionNames = task.optionNames,
                values = configured.optionValues,
                scopeLabel = "task:${task.name}",
                patches = patches,
                diagnostics = diagnostics,
            )
            runtimeTasks += RuntimeTask(task.name, task.entry, patches)
        }

        if (diagnostics.any { it.severity == DiagnosticSeverity.Error }) {
            return RunPlanResult.Invalid(diagnostics)
        }
        if (runtimeTasks.isEmpty()) return RunPlanResult.NoExecutableTasks

        return RunPlanResult.Success(
            RunPlan(
                projectName = definition.name,
                projectVersion = definition.version,
                controller = definition.controller,
                resource = resource,
                runConfigurationId = runConfiguration.id,
                tasks = runtimeTasks,
            ),
        )
    }

    /** 每个作用域使用独立 processed set；同名 option 在一个作用域内最多处理一次。 */
    private fun compileOptions(
        definition: ProjectDefinition,
        optionNames: List<String>,
        values: Map<String, OptionValue>,
        scopeLabel: String,
        patches: MutableList<JsonObject>,
        diagnostics: MutableList<Diagnostic>,
    ) {
        val processed = mutableSetOf<String>()

        fun compile(name: String) {
            if (!processed.add(name)) return
            val option = definition.options[name]
            if (option == null) {
                diagnostics += runtimeError(scopeLabel, "引用了不存在的 option \"$name\"")
                return
            }
            when (option) {
                is OptionDefinition.Select, is OptionDefinition.Switch -> {
                    val cases = when (option) {
                        is OptionDefinition.Select -> option.cases
                        is OptionDefinition.Switch -> option.cases
                        else -> emptyList()
                    }
                    val defaultCase = when (option) {
                        is OptionDefinition.Select -> option.defaultCase
                        is OptionDefinition.Switch -> option.defaultCase
                        else -> null
                    }
                    val value = values[name] as? OptionValue.SingleCase
                    val selectedName = value?.case ?: defaultCase
                    if (selectedName == null) {
                        diagnostics += runtimeError(scopeLabel, "option \"$name\" 未设置且没有默认值")
                        return
                    }
                    val case = cases.firstOrNull { it.name == selectedName }
                    if (case == null) {
                        diagnostics += runtimeError(scopeLabel, "option \"$name\" 选择的 case \"$selectedName\" 不存在")
                        return
                    }
                    if (case.pipelineOverride.isNotEmpty()) patches += case.pipelineOverride
                    case.childOptionNames.forEach { compile(it) }
                }

                is OptionDefinition.Checkbox -> {
                    val value = values[name] as? OptionValue.MultipleCases
                    val selected = value?.cases?.toSet() ?: option.defaultCases.toSet()
                    (selected - option.cases.mapTo(mutableSetOf()) { it.name }).forEach {
                        diagnostics += runtimeError(scopeLabel, "option \"$name\" 选择的 case \"$it\" 不存在")
                    }
                    // 用户选择顺序不改变 patch 顺序：按 case definition 声明顺序编译
                    for (case in option.cases) {
                        if (case.name !in selected) continue
                        if (case.pipelineOverride.isNotEmpty()) patches += case.pipelineOverride
                        case.childOptionNames.forEach { compile(it) }
                    }
                }

                is OptionDefinition.Input -> {
                    val inputValues = (values[name] as? OptionValue.Inputs)?.values ?: emptyMap()
                    val fields = mutableMapOf<String, Pair<InputFieldDefinition, String>>()
                    var valid = true
                    for (field in option.fields) {
                        val raw = inputValues[field.name] ?: field.default
                        if (!validateInputCandidate(field.pipelineType, field.verify, raw)) {
                            diagnostics += runtimeError(
                                scopeLabel,
                                "option \"$name\" 的输入 \"${field.name}\" 不合法: ${field.patternMessage ?: raw}",
                            )
                            valid = false
                        }
                        fields[field.name] = field to raw
                    }
                    if (!valid) return
                    val substituted = substitute(option.pipelineOverride, fields, scopeLabel, name, diagnostics)
                    if (substituted.isNotEmpty()) patches += substituted
                }
            }
        }

        optionNames.forEach { compile(it) }
    }

    /** 递归替换 placeholder，覆盖 JsonObject 与 JsonArray。 */
    private fun substitute(
        element: JsonObject,
        fields: Map<String, Pair<InputFieldDefinition, String>>,
        scopeLabel: String,
        optionName: String,
        diagnostics: MutableList<Diagnostic>,
    ): JsonObject =
        substituteElement(element, fields, scopeLabel, optionName, diagnostics) as JsonObject

    private fun substituteElement(
        element: JsonElement,
        fields: Map<String, Pair<InputFieldDefinition, String>>,
        scopeLabel: String,
        optionName: String,
        diagnostics: MutableList<Diagnostic>,
    ): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.mapValues { (_, v) -> substituteElement(v, fields, scopeLabel, optionName, diagnostics) },
        )

        is JsonArray -> JsonArray(
            element.map { substituteElement(it, fields, scopeLabel, optionName, diagnostics) },
        )

        is JsonPrimitive -> {
            if (!element.isString) {
                element
            } else {
                substituteString(element.content, fields, scopeLabel, optionName, diagnostics)
            }
        }
    }

    private fun substituteString(
        content: String,
        fields: Map<String, Pair<InputFieldDefinition, String>>,
        scopeLabel: String,
        optionName: String,
        diagnostics: MutableList<Diagnostic>,
    ): JsonElement {
        // 整个 string token 恰好是 placeholder 时保留目标类型
        val whole = PLACEHOLDER.matchEntire(content)
        if (whole != null) {
            val key = whole.groupValues[1]
            val entry = fields[key]
            if (entry == null) {
                diagnostics += runtimeError(scopeLabel, "option \"$optionName\" 的 placeholder \"{$key}\" 没有对应输入")
                return JsonPrimitive(content)
            }
            val (field, raw) = entry
            return typedPrimitive(field.pipelineType, raw, scopeLabel, optionName, diagnostics)
                ?: JsonPrimitive(content)
        }
        // 嵌入文本时保持字符串
        var missing = false
        val replaced = PLACEHOLDER.replace(content) { match ->
            val key = match.groupValues[1]
            val entry = fields[key]
            if (entry == null) {
                missing = true
                match.value
            } else {
                entry.second
            }
        }
        if (missing) {
            diagnostics += runtimeError(scopeLabel, "option \"$optionName\" 存在无法替换的 placeholder: $content")
        }
        return JsonPrimitive(replaced)
    }

    private fun typedPrimitive(
        type: PipelineType,
        raw: String,
        scopeLabel: String,
        optionName: String,
        diagnostics: MutableList<Diagnostic>,
    ): JsonPrimitive? = when (type) {
        PipelineType.StringType -> JsonPrimitive(raw)
        PipelineType.IntType -> raw.toLongOrNull()?.let { JsonPrimitive(it) } ?: run {
            diagnostics += runtimeError(scopeLabel, "option \"$optionName\" 的输入 \"$raw\" 无法转换为整数")
            null
        }

        PipelineType.BoolType -> raw.toBooleanStrictOrNull()?.let { JsonPrimitive(it) } ?: run {
            diagnostics += runtimeError(scopeLabel, "option \"$optionName\" 的输入 \"$raw\" 无法转换为布尔值")
            null
        }
    }

    private fun runtimeError(source: String, message: String) =
        Diagnostic(DiagnosticSeverity.Error, source, message)
}
