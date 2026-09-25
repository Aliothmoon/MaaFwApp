package com.aliothmoon.maafw.project

import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.domain.ControllerDisplay
import com.aliothmoon.maafw.domain.DiagnosticSeverity
import com.aliothmoon.maafw.domain.OptionDefinition
import com.aliothmoon.maafw.i18n.isResource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PiParserV5_13Test {

    private fun load(body: String): ProjectLoadResult.Ready {
        val result = ProjectLoader(
            MapProjectSource(
                mapOf(
                    "interface.json" to """{"interface_version":2,"name":"t",$body}""",
                    "tasks/a.json" to """{"task":[{"name":"T1","entry":"E1"}]}""",
                    "a.json" to "{}",
                ),
            ),
        ).load()
        assertTrue(result is ProjectLoadResult.Ready)
        return result as ProjectLoadResult.Ready
    }

    @Test
    fun `解析 display_expand 并保留原始 controller 条目`() {
        val ready = load(
            """"import":["a.json","tasks/a.json"],"controller":[{"name":"ADB","type":"Adb","display_expand":[1280,720]}]""",
        )

        assertEquals(ControllerDisplay.Expand(1280, 720), ready.definition.controller.display)
        assertTrue("display_expand" in ready.definition.controller.raw.toString())
    }

    @Test
    fun `display 字段冲突时回退默认`() {
        val ready = load(
            """"import":["a.json","tasks/a.json"],"controller":[{"name":"ADB","type":"Adb","display_short_side":720,"display_raw":true}]""",
        )

        assertEquals(ControllerDisplay.Default, ready.definition.controller.display)
        assertTrue(
            ready.diagnostics.any {
                it.severity == DiagnosticSeverity.Error &&
                    it.message.isResource(R.string.diagnostic_controller_display_conflict, "ADB")
            },
        )
    }

    @Test
    fun `解析 checkbox 数量限制和 password 字段`() {
        val ready = load(
            """
            "import":["a.json","tasks/a.json"],
            "controller":[{"name":"ADB","type":"Adb"}],
            "option":{
              "multi":{
                "type":"checkbox","cases":[{"name":"a"},{"name":"b"},{"name":"c"}],
                "default_case":["a","a","b"],"min_count":1,"max_count":2
              },
              "cred":{
                "type":"input","pipeline_override":{"token":"{secret}"},
                "inputs":[{"name":"secret","password":true}]
              }
            }
            """.trimIndent().replace("\n", ""),
        )

        val checkbox = ready.definition.options.getValue("multi") as OptionDefinition.Checkbox
        assertEquals(1, checkbox.minCount)
        assertEquals(2, checkbox.maxCount)
        assertEquals(listOf("a", "b"), checkbox.defaultCases)

        val input = ready.definition.options.getValue("cred") as OptionDefinition.Input
        assertTrue(input.fields.single().password)
    }

    @Test
    fun `password input 禁止默认值并忽略明文默认值`() {
        val ready = load(
            """
            "import":["a.json","tasks/a.json"],
            "controller":[{"name":"ADB","type":"Adb"}],
            "option":{"cred":{"type":"input","pipeline_override":{},"inputs":[{"name":"secret","password":true,"default":"raw-secret"}]}}
            """.trimIndent().replace("\n", ""),
        )

        val input = ready.definition.options.getValue("cred") as OptionDefinition.Input
        assertEquals("", input.fields.single().default)
        assertTrue(
            ready.diagnostics.any {
                it.severity == DiagnosticSeverity.Error &&
                    it.message.isResource(R.string.diagnostic_password_default_forbidden, "cred", "secret")
            },
        )
    }
}
