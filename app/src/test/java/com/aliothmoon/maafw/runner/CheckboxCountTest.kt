package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.config.ConfigurationResolver
import com.aliothmoon.maafw.domain.ConfiguredTask
import com.aliothmoon.maafw.domain.DiagnosticSeverity
import com.aliothmoon.maafw.domain.OptionCaseDefinition
import com.aliothmoon.maafw.domain.OptionDefinition
import com.aliothmoon.maafw.domain.OptionValue
import com.aliothmoon.maafw.domain.ProjectDefinition
import com.aliothmoon.maafw.domain.RunConfiguration
import com.aliothmoon.maafw.domain.RunConfigurationId
import com.aliothmoon.maafw.domain.UserConfiguration
import com.aliothmoon.maafw.project.DirectoryProjectSource
import com.aliothmoon.maafw.project.ProjectLoadResult
import com.aliothmoon.maafw.project.ProjectLoader
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/** 选择数量不满足 min_count / max_count 时不能带着它启动（协议 v2.10.1） */
class CheckboxCountTest {

    companion object {
        private const val TASK = "启动游戏"
        private const val OPTION = "多选"
        private lateinit var fixture: ProjectDefinition

        @JvmStatic
        @BeforeClass
        fun loadProject() {
            val result = ProjectLoader(DirectoryProjectSource(File("src/test/fixtures/PI/M9A"))).load()
            fixture = (result as ProjectLoadResult.Ready).definition
        }
    }

    private fun definition(minCount: Int, maxCount: Int?, defaults: List<String> = emptyList()): ProjectDefinition {
        val option = OptionDefinition.Checkbox(
            name = OPTION,
            label = OPTION,
            description = null,
            cases = listOf("a", "b", "c").map {
                OptionCaseDefinition(it, it, null, JsonObject(mapOf(it to JsonObject(emptyMap()))), emptyList())
            },
            defaultCases = defaults,
            minCount = minCount,
            maxCount = maxCount,
        )
        return fixture.copy(
            options = fixture.options + (OPTION to option),
            tasks = fixture.tasks.map {
                if (it.name == TASK) it.copy(optionNames = it.optionNames + OPTION) else it
            },
        )
    }

    private fun config(selected: List<String>?): UserConfiguration {
        val id = RunConfigurationId("test")
        val values = selected?.let { mapOf(OPTION to OptionValue.MultipleCases(it)) }.orEmpty()
        return UserConfiguration(
            initialized = true,
            activeResourceName = "官服",
            configurations = listOf(RunConfiguration(id, "测试", listOf(ConfiguredTask(TASK, optionValues = values)))),
            activeConfigurationId = id,
        )
    }

    private fun assertInvalid(result: RunPlanResult) {
        assertTrue("应被拦下: $result", result is RunPlanResult.Invalid)
        assertTrue((result as RunPlanResult.Invalid).diagnostics.any { it.severity == DiagnosticSeverity.Error })
    }

    @Test
    fun `少于下限拦下`() {
        assertInvalid(RunPlanBuilder.build(definition(minCount = 2, maxCount = null), config(listOf("a"))))
    }

    @Test
    fun `超过上限拦下`() {
        assertInvalid(RunPlanBuilder.build(definition(minCount = 0, maxCount = 1), config(listOf("a", "b"))))
    }

    @Test
    fun `区间内照常编译，patch 按声明序`() {
        val result = RunPlanBuilder.build(definition(minCount = 1, maxCount = 2), config(listOf("c", "a")))
        assertTrue("应编译成功: $result", result is RunPlanResult.Success)
        val keys = (result as RunPlanResult.Success).plan.tasks.single().pipelineOverrides
            .flatMap { it.keys }
            .filter { it in setOf("a", "b", "c") }
        assertEquals(listOf("a", "c"), keys)
    }

    @Test
    fun `未设值时按 default_case 计数`() {
        assertInvalid(RunPlanBuilder.build(definition(minCount = 1, maxCount = null), config(null)))
        val withDefault = RunPlanBuilder.build(definition(minCount = 1, maxCount = null, defaults = listOf("b")), config(null))
        assertTrue("默认值满足下限应放行: $withDefault", withDefault is RunPlanResult.Success)
    }

    @Test
    fun `选满上限后未选项不可点，已选项仍可取消`() {
        val session = ConfigurationResolver.resolve(definition(minCount = 1, maxCount = 2), config(listOf("a", "b")))
        val option = session.activeConfiguration!!.tasks.single().options.first { it.name == OPTION }
        val (a, _, c) = option.cases

        assertTrue(option.canToggle(a))
        assertFalse(option.canToggle(c))
        assertFalse(option.belowMinCount)
    }
}
