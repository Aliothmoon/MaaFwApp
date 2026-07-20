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

    /** 无活动配置、空配置、全部禁用或全部不适用统一映射到这里。 */
    data object NoExecutableTasks : RunPlanResult
}

/**
 * 纯配置编译模块：ProjectDefinition + UserConfiguration -> RunPlan。
 * 不执行任务、不持有 native handle；UI 不得绕过它拼 pipeline JSON。
 */
object RunPlanBuilder {

    // 闭括号必须转义：Android ICU regex 对孤立 } 抛 PatternSyntaxException（JVM 单测环境则宽容）
    private val PLACEHOLDER = Regex("""\{([^{}]+)\}""")

    fun build(definition: ProjectDefinition, config: UserConfiguration): RunPlanResult {
        val diagnostics = mutableListOf<Diagnostic>()

        // 共享环境：固定 Android controller + activeResourceName（缺失回退首个资源）
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
            // Resolver 的自动禁用用于 UI 反馈，这里是运行时兜底
            val applicable = ConfigurationResolver.checkApplicability(definition, task, resource.name) == null
            if (!configured.enabled || !applicable) continue

            val patches = mutableListOf<JsonObject>()
            if (task.pipelineOverride.isNotEmpty()) patches += task.pipelineOverride
            // patch 顺序：task 基础 patch -> global -> resource -> controller -> task option。
            // 当前 Android ProjectDefinition 尚未建模 global/controller/resource 作用域 option。
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
        // 未命中 input 的 {…} 不是错误：M9A 等项目在 override 里写
        // "{节点名}<{输入名}" 这类运行期表达式，节点引用必须原样透传给框架
        // 整个 string token 恰好是 placeholder 时保留目标类型
        val whole = PLACEHOLDER.matchEntire(content)
        if (whole != null) {
            val (field, raw) = fields[whole.groupValues[1]] ?: return JsonPrimitive(content)
            return typedPrimitive(field.pipelineType, raw, scopeLabel, optionName, diagnostics)
                ?: JsonPrimitive(content)
        }
        // 嵌入文本时保持字符串
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
