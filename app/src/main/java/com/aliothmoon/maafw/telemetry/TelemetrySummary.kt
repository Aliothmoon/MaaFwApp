package com.aliothmoon.maafw.telemetry

import com.aliothmoon.maafw.domain.InputFieldDefinition
import com.aliothmoon.maafw.domain.OptionDefinition
import com.aliothmoon.maafw.domain.OptionValue
import com.aliothmoon.maafw.domain.PipelineType
import com.aliothmoon.maafw.domain.ProjectDefinition
import com.aliothmoon.maafw.domain.casesOrEmpty

/**
 * 一个任务的选项取值摘要，写到任务 Span 的 `option.<key>` 上
 *
 * 键形与取值对齐 MXU `buildTaskOptionSummary`，两个客户端的数据才能在同一张看板里按选项筛：
 *
 * - select：`key = case`；switch：`key = true/false`（认不出是/否的 case 退回 case 名）
 * - checkbox：每个选中 case 一条 `key.case = true`，一个都没选报 `key = none`
 * - input：每个字段一条 `key.field`，自由文本只报填没填——那里装的是路径、账号、URL，出不得设备
 */
object TelemetrySummary {

    private const val MAX_ENTRIES = 100
    private const val MAX_VALUE_LENGTH = 512
    private const val FILLED = "filled"
    private const val EMPTY = "empty"
    private const val NONE = "none"

    /** 与 MXU `findSwitchCase` 的判定表一致 */
    private val YES_CASES = setOf("Yes", "yes", "Y", "y")
    private val NO_CASES = setOf("No", "no", "N", "n")

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

        fun put(key: String, text: String) {
            if (into.size < MAX_ENTRIES) into[key] = text.take(MAX_VALUE_LENGTH)
        }

        val selected = when (option) {
            is OptionDefinition.Choice -> {
                val case = (value as? OptionValue.SingleCase)?.case
                    ?.takeIf { s -> option.cases.any { it.name == s } }
                    ?: option.effectiveDefaultCase
                if (case != null) put(optionName, if (option is OptionDefinition.Switch) switchValue(case) else case)
                listOfNotNull(case)
            }

            is OptionDefinition.Checkbox -> {
                val cases = (value as? OptionValue.MultipleCases)?.cases ?: option.defaultCases
                if (cases.isEmpty()) put(optionName, NONE)
                cases.forEach { put("$optionName.$it", "true") }
                cases
            }

            is OptionDefinition.Input -> {
                val inputs = (value as? OptionValue.Inputs)?.values.orEmpty()
                option.fields.forEach { field ->
                    put("$optionName.${field.name}", summarizeInput(field, inputs[field.name] ?: field.default))
                }
                return
            }
        }

        option.casesOrEmpty()
            .filter { it.name in selected }
            .flatMap { it.childOptionNames }
            .forEach { collect(definition, it, values, into, visited + optionName) }
    }

    private fun switchValue(case: String): String = when (case) {
        in YES_CASES -> "true"
        in NO_CASES -> "false"
        else -> case
    }

    /** 数值与布尔的取值域由 PI 定死，带不出隐私；password 例外，纯数字 PIN 也只报填没填 */
    private fun summarizeInput(field: InputFieldDefinition, value: String): String = when {
        field.password || field.pipelineType == PipelineType.StringType -> if (value.isBlank()) EMPTY else FILLED
        else -> value.ifBlank { EMPTY }
    }
}
