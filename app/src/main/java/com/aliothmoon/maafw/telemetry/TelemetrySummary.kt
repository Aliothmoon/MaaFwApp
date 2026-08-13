package com.aliothmoon.maafw.telemetry

import com.aliothmoon.maafw.domain.OptionDefinition
import com.aliothmoon.maafw.domain.OptionValue
import com.aliothmoon.maafw.domain.PipelineType
import com.aliothmoon.maafw.domain.ProjectDefinition
import com.aliothmoon.maafw.domain.casesOrEmpty

/**
 * 一个任务的选项取值摘要
 *
 * case 名是 PI 自己声明的枚举，原样上报；input 的自由文本只报填没填——
 * 那里装的是路径、账号、URL 这类用户输入，出不得设备
 */
object TelemetrySummary {

    private const val MAX_ENTRIES = 100
    private const val FILLED = "filled"
    private const val EMPTY = "empty"

    fun summarize(
        definition: ProjectDefinition,
        optionNames: List<String>,
        values: Map<String, OptionValue>,
    ): Map<String, String> = buildMap {
        optionNames.forEach { collect(definition, it, values, this, visited = emptySet()) }
    }

    private fun collect(
        definition: ProjectDefinition,
        optionName: String,
        values: Map<String, OptionValue>,
        into: MutableMap<String, String>,
        visited: Set<String>,
    ) {
        if (into.size >= MAX_ENTRIES || optionName in visited) return
        val option = definition.options[optionName] ?: return
        val value = values[optionName]

        val selected = when (option) {
            is OptionDefinition.Choice -> {
                val case = (value as? OptionValue.SingleCase)?.case
                    ?.takeIf { s -> option.cases.any { it.name == s } }
                    ?: option.effectiveDefaultCase
                listOfNotNull(case)
            }

            is OptionDefinition.Checkbox ->
                (value as? OptionValue.MultipleCases)?.cases ?: option.defaultCases

            is OptionDefinition.Input -> {
                val inputs = (value as? OptionValue.Inputs)?.values.orEmpty()
                into[optionName] = option.fields.joinToString(",") { field ->
                    val raw = inputs[field.name] ?: field.default
                    "${field.name}=${summarizeInput(field.pipelineType, raw)}"
                }
                return
            }
        }
        into[optionName] = selected.joinToString(",")

        option.casesOrEmpty()
            .filter { it.name in selected }
            .flatMap { it.childOptionNames }
            .forEach { collect(definition, it, values, into, visited + optionName) }
    }

    /** 数值与布尔的取值域由 PI 定死，带不出隐私 */
    private fun summarizeInput(type: PipelineType, value: String): String = when (type) {
        PipelineType.IntType, PipelineType.BoolType -> value.ifBlank { EMPTY }
        PipelineType.StringType -> if (value.isBlank()) EMPTY else FILLED
    }
}
