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
    controller = ControllerDefinition(),
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
        assertEquals("account=filled,path=empty", summary.getValue("settings"))
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
        assertEquals("count=3,flag=false", summary.getValue("settings"))
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

    private fun case(name: String, children: List<String> = emptyList()) = OptionCaseDefinition(
        name = name,
        label = name,
        description = null,
        pipelineOverride = JsonObject(emptyMap()),
        childOptionNames = children,
    )
}
