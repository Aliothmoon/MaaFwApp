package com.aliothmoon.maafw.telemetry

import com.aliothmoon.maafw.domain.ControllerDefinition
import com.aliothmoon.maafw.domain.InputFieldDefinition
import com.aliothmoon.maafw.domain.OptionCaseDefinition
import com.aliothmoon.maafw.domain.OptionDefinition
import com.aliothmoon.maafw.domain.OptionValue
import com.aliothmoon.maafw.domain.PipelineType
import com.aliothmoon.maafw.domain.ProjectDefinition
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

private fun input(vararg fields: InputFieldDefinition) = OptionDefinition.Input(
    name = "settings",
    label = "settings",
    description = null,
    icon = null,
    fields = fields.toList(),
    pipelineOverride = JsonObject(emptyMap()),
)

private fun field(name: String, type: PipelineType, default: String = "") = InputFieldDefinition(
    name = name,
    pipelineType = type,
    default = default,
    verify = null,
    patternMessage = null,
    description = null,
)

private fun definition(vararg options: OptionDefinition) = ProjectDefinition(
    name = "p",
    version = "1.0.0",
    controllers = listOf(ControllerDefinition()),
    resources = emptyList(),
    tasks = emptyList(),
    groups = emptyList(),
    options = options.associateBy { it.name },
    templates = emptyList(),
)

class TelemetrySummaryTest {

    /** 自由文本装的是路径、账号这类用户输入，只能报填没填 */
    @Test
    fun `字符串输入只报填没填`() {
        val definition = definition(
            input(
                field("account", PipelineType.StringType),
                field("path", PipelineType.StringType),
            ),
        )
        val summary = TelemetrySummary.summarize(
            definition,
            listOf("settings"),
            mapOf("settings" to OptionValue.Inputs(mapOf("account" to "user@example.com"))),
        )
        assertEquals("filled", summary.getValue("settings.account"))
        assertEquals("empty", summary.getValue("settings.path"))
    }

    /** 数值与布尔的取值域由 PI 定死，带不出隐私 */
    @Test
    fun `数值与布尔原样上报`() {
        val definition = definition(
            input(
                field("count", PipelineType.IntType, default = "3"),
                field("flag", PipelineType.BoolType, default = "false"),
            ),
        )
        val summary = TelemetrySummary.summarize(definition, listOf("settings"), emptyMap())
        assertEquals("3", summary.getValue("settings.count"))
        assertEquals("false", summary.getValue("settings.flag"))
    }

    /** 纯数字 PIN 按 int 走也不能报原值 */
    @Test
    fun `password 不论类型只报填没填`() {
        val definition = definition(
            input(
                field("pin", PipelineType.IntType).copy(password = true),
                field("token", PipelineType.StringType).copy(password = true),
            ),
        )
        val summary = TelemetrySummary.summarize(
            definition,
            listOf("settings"),
            mapOf("settings" to OptionValue.Inputs(mapOf("pin" to "123456"))),
        )
        assertEquals("filled", summary.getValue("settings.pin"))
        assertEquals("empty", summary.getValue("settings.token"))
    }

    @Test
    fun `case 名原样上报并递归子选项`() {
        val child = OptionDefinition.Select(
            name = "child",
            label = "child",
            description = null,
            icon = null,
            cases = listOf(case("x"), case("y")),
            defaultCase = "y",
        )
        val parent = OptionDefinition.Select(
            name = "parent",
            label = "parent",
            description = null,
            icon = null,
            cases = listOf(case("on", listOf("child")), case("off")),
            defaultCase = "off",
        )
        val summary = TelemetrySummary.summarize(
            definition(parent, child),
            listOf("parent"),
            mapOf("parent" to OptionValue.SingleCase("on")),
        )
        assertEquals("on", summary.getValue("parent"))
        assertEquals("y", summary.getValue("child"))
    }

    @Test
    fun `未选中的分支不带出子选项`() {
        val child = OptionDefinition.Select(
            name = "child",
            label = "child",
            description = null,
            icon = null,
            cases = listOf(case("x")),
            defaultCase = "x",
        )
        val parent = OptionDefinition.Select(
            name = "parent",
            label = "parent",
            description = null,
            icon = null,
            cases = listOf(case("on", listOf("child")), case("off")),
            defaultCase = "off",
        )
        val summary = TelemetrySummary.summarize(definition(parent, child), listOf("parent"), emptyMap())
        assertEquals("off", summary.getValue("parent"))
        assertEquals(null, summary["child"])
    }

    /** 与 MXU 一致：switch 报 true/false，不报 case 名 */
    @Test
    fun `switch 报布尔值`() {
        val option = OptionDefinition.Switch(
            name = "auto",
            label = "auto",
            description = null,
            icon = null,
            cases = listOf(case("Yes"), case("No")),
            defaultCase = "No",
        )
        val summary = TelemetrySummary.summarize(
            definition(option),
            listOf("auto"),
            mapOf("auto" to OptionValue.SingleCase("Yes")),
        )
        assertEquals("true", summary.getValue("auto"))
    }

    /** 与 MXU 一致：每个选中 case 单独一条，一个都没选报 none */
    @Test
    fun `checkbox 每个选中 case 一条`() {
        val option = OptionDefinition.Checkbox(
            name = "items",
            label = "items",
            description = null,
            icon = null,
            cases = listOf(case("a"), case("b"), case("c")),
            defaultCases = emptyList(),
        )
        val picked = TelemetrySummary.summarize(
            definition(option),
            listOf("items"),
            mapOf("items" to OptionValue.MultipleCases(listOf("a", "c"))),
        )
        assertEquals(mapOf("items.a" to "true", "items.c" to "true"), picked)

        val none = TelemetrySummary.summarize(definition(option), listOf("items"), emptyMap())
        assertEquals(mapOf("items" to "none"), none)
    }

    private fun case(name: String, children: List<String> = emptyList()) = OptionCaseDefinition(
        name = name,
        label = name,
        description = null,
        pipelineOverride = JsonObject(emptyMap()),
        childOptionNames = children,
    )
}
