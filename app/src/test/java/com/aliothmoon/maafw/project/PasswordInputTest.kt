package com.aliothmoon.maafw.project

import com.aliothmoon.maafw.config.ConfigurationResolver
import com.aliothmoon.maafw.domain.ConfiguredTask
import com.aliothmoon.maafw.domain.DiagnosticSeverity
import com.aliothmoon.maafw.domain.OptionDefinition
import com.aliothmoon.maafw.domain.OptionValue
import com.aliothmoon.maafw.domain.RunConfiguration
import com.aliothmoon.maafw.domain.RunConfigurationId
import com.aliothmoon.maafw.domain.SECRET_MASK
import com.aliothmoon.maafw.domain.UserConfiguration
import com.aliothmoon.maafw.i18n.UiText
import com.aliothmoon.maafw.runner.RunPlanBuilder
import com.aliothmoon.maafw.runner.RunPlanResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PI v2.10.0 `inputs[].password`：不带 default、不进 preset、诊断里不出原文 */
class PasswordInputTest {

    private val pi = """
        {
          "interface_version": 2,
          "name": "t",
          "resource": [{"name": "官服", "path": "resource"}],
          "controller": [{"name": "安卓", "type": "Adb"}],
          "task": [{"name": "登录", "entry": "Login", "option": ["账号"]}],
          "option": {
            "账号": {
              "type": "input",
              "inputs": [
                {"name": "user", "default": "admin"},
                {"name": "pin", "pipeline_type": "int", "password": true, "default": "1234", "verify": "^\\d{6}${'$'}"}
              ],
              "pipeline_override": {"Login": {"user": "{user}", "pin": "{pin}"}}
            }
          },
          "preset": [{"name": "默认", "task": [{"name": "登录", "option": {"账号": {"user": "u", "pin": "999999"}}}]}]
        }
    """.trimIndent()

    private fun load() = loadWithLocale("zh-CN", MapProjectSource(mapOf("interface.json" to pi))) as ProjectLoadResult.Ready

    private fun config(values: Map<String, String>): UserConfiguration {
        val id = RunConfigurationId("c")
        val task = ConfiguredTask("登录", optionValues = mapOf("账号" to OptionValue.Inputs(values)))
        return UserConfiguration(
            initialized = true,
            activeResourceName = "官服",
            configurations = listOf(RunConfiguration(id, "c", listOf(task))),
            activeConfigurationId = id,
        )
    }

    @Test
    fun `password 字段解析出来且丢掉 default`() {
        val result = load()
        val fields = (result.definition.options.getValue("账号") as OptionDefinition.Input).fields
        val pin = fields.single { it.name == "pin" }

        assertTrue(pin.password)
        assertEquals("", pin.default)
        assertFalse(fields.single { it.name == "user" }.password)
        assertEquals("admin", fields.single { it.name == "user" }.default)
        assertTrue(result.diagnostics.any { it.severity == DiagnosticSeverity.Warning && "pin" in it.message.args() })
    }

    @Test
    fun `preset 里的 password 值被剔除，其余保留`() {
        val result = load()
        val values = result.definition.templates.single().tasks.single().optionValues.getValue("账号")

        assertEquals(mapOf("user" to "u"), (values as OptionValue.Inputs).values)
        assertTrue(result.diagnostics.any { "默认" in it.message.args() && "pin" in it.message.args() })
    }

    @Test
    fun `校验不过的 password 不把原文写进诊断`() {
        val definition = load().definition
        val result = RunPlanBuilder.build(definition, config(mapOf("user" to "u", "pin" to "4321")))

        assertTrue(result is RunPlanResult.Invalid)
        val args = (result as RunPlanResult.Invalid).diagnostics.flatMap { it.message.args() }
        assertFalse("诊断里出现了原文: $args", args.any { "4321" in it })
        assertTrue(SECRET_MASK in args)
    }

    @Test
    fun `编辑投影带上 password 标记`() {
        val definition = load().definition
        val session = ConfigurationResolver.resolve(definition, config(mapOf("pin" to "123456")))
        val inputs = session.activeConfiguration!!.tasks.single().options.single().inputs

        assertTrue(inputs.single { it.name == "pin" }.password)
        assertFalse(inputs.single { it.name == "user" }.password)
    }

    private fun UiText.args(): List<String> = when (this) {
        is UiText.Resource -> args.flatMap { if (it is UiText) it.args() else listOf(it.toString()) }
        else -> emptyList()
    }
}
