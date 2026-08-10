package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.config.ConfigurationResolver
import com.aliothmoon.maafw.domain.Diagnostic
import com.aliothmoon.maafw.domain.DiagnosticSeverity
import com.aliothmoon.maafw.domain.DiagnosticMessage
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

    /** 无活动配置 / 空配置 / 全禁用 / 全不适用 */
    data object NoExecutableTasks : RunPlanResult
}

/** ProjectDefinition + UserConfiguration → RunPlan；UI 不得绕过此模块拼 pipeline JSON */
object RunPlanBuilder {

    // 闭括号必须转义：Android ICU 对孤立 } 抛 PatternSyntaxException（JVM 单测则宽容）
    private val PLACEHOLDER = Regex("""\{([^{}]+)\}""")

    fun build(definition: ProjectDefinition, config: UserConfiguration): RunPlanResult {
        val diagnostics = mutableListOf<Diagnostic>()

        val resource = definition.resources.firstOrNull { it.name == config.activeResourceName }
            ?: definition.resources.firstOrNull()
        if (resource == null) {
            diagnostics += runtimeError("environment", DiagnosticMessage.RuntimeNoResource)
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
                    diagnostics += runtimeError(
                        "task:${configured.taskName}",
                        DiagnosticMessage.EnabledTaskMissingDefinition(configured.taskName),
                    )
                }
                continue
            }
            // Resolver 自动禁用供 UI；此处为运行时兜底
            val applicable = ConfigurationResolver.checkApplicability(definition, task, resource.name) == null
            if (!configured.enabled || !applicable) continue

            val patches = mutableListOf<JsonObject>()
            if (task.pipelineOverride.isNotEmpty()) patches += task.pipelineOverride
            // 顺序：task 基础 → global → resource → controller → task option
            // 当前尚未建模 global/controller/resource 作用域 option
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
                agents = definition.agents,
            ),
        )
    }

    /** 每作用域独立 processed set；同名 option 至多处理一次 */
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
                diagnostics += runtimeError(scopeLabel, DiagnosticMessage.MissingReference("option", name))
                return
            }
            when (option) {
                is OptionDefinition.Choice -> {
                    val value = values[name] as? OptionValue.SingleCase
                    val selectedName = value?.case ?: option.defaultCase
                    if (selectedName == null) {
                        diagnostics += runtimeError(
                            scopeLabel,
                            DiagnosticMessage.OptionUnsetWithoutDefault(name),
                        )
                        return
                    }
                    val case = option.cases.firstOrNull { it.name == selectedName }
                    if (case == null) {
                        diagnostics += runtimeError(
                            scopeLabel,
                            DiagnosticMessage.SelectedCaseMissing(name, selectedName),
                        )
                        return
                    }
                    if (case.pipelineOverride.isNotEmpty()) patches += case.pipelineOverride
                    case.childOptionNames.forEach { compile(it) }
                }

                is OptionDefinition.Checkbox -> {
                    val value = values[name] as? OptionValue.MultipleCases
                    val selected = value?.cases?.toSet() ?: option.defaultCases.toSet()
                    (selected - option.cases.mapTo(mutableSetOf()) { it.name }).forEach {
                        diagnostics += runtimeError(
                            scopeLabel,
                            DiagnosticMessage.SelectedCaseMissing(name, it),
                        )
                    }
                    // patch 按 definition 声明序，不按用户勾选序
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
                                DiagnosticMessage.InvalidInput(
                                    option = name,
                                    input = field.name,
                                    detail = field.patternMessage ?: raw,
                                ),
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
        // 未命中的 {…} 原样透传：可能是运行期节点表达式，不全是 input 引用
        // 整个 string 恰好是 placeholder 时按 pipelineType 保留类型
        val whole = PLACEHOLDER.matchEntire(content)
        if (whole != null) {
            val (field, raw) = fields[whole.groupValues[1]] ?: return JsonPrimitive(content)
            return typedPrimitive(field.pipelineType, raw, scopeLabel, optionName, diagnostics)
                ?: JsonPrimitive(content)
        }
        val replaced = PLACEHOLDER.replace(content) { match ->
            fields[match.groupValues[1]]?.second ?: match.value
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
            diagnostics += runtimeError(
                scopeLabel,
                DiagnosticMessage.IntegerConversionFailed(optionName, raw),
            )
            null
        }

        PipelineType.BoolType -> raw.toBooleanStrictOrNull()?.let { JsonPrimitive(it) } ?: run {
            diagnostics += runtimeError(
                scopeLabel,
                DiagnosticMessage.BooleanConversionFailed(optionName, raw),
            )
            null
        }
    }

    private fun runtimeError(source: String, message: DiagnosticMessage) = Diagnostic.error(source, message)
}
