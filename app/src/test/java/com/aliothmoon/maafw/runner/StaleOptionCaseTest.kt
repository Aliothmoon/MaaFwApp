package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.config.ConfigurationResolver
import com.aliothmoon.maafw.domain.ConfiguredTask
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
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/** PI 更新后存的 case 已不在定义里：编译与展示同一口径回落，不把整轮判成 Invalid */
class StaleOptionCaseTest {

    companion object {
        private const val TASK = "启动游戏"
        private const val OPTION = "切换"
        private lateinit var fixture: ProjectDefinition

        @JvmStatic
        @BeforeClass
        fun loadProject() {
            val result = ProjectLoader(DirectoryProjectSource(File("src/test/fixtures/PI/M9A"))).load()
            fixture = (result as ProjectLoadResult.Ready).definition
        }
    }

    private fun cases(vararg names: String) = names.map {
        OptionCaseDefinition(it, it, null, JsonObject(mapOf(it to JsonObject(emptyMap()))), emptyList())
    }

    private fun definition(option: OptionDefinition): ProjectDefinition = fixture.copy(
        options = fixture.options + (OPTION to option),
        tasks = fixture.tasks.map {
            if (it.name == TASK) it.copy(optionNames = it.optionNames + OPTION) else it
        },
    )

    private fun config(value: OptionValue): UserConfiguration {
        val id = RunConfigurationId("test")
        return UserConfiguration(
            initialized = true,
            activeResourceName = "官服",
            configurations = listOf(
                RunConfiguration(id, "测试", listOf(ConfiguredTask(TASK, optionValues = mapOf(OPTION to value)))),
            ),
            activeConfigurationId = id,
        )
    }

    private fun compiledCases(result: RunPlanResult, candidates: Set<String>): List<String> {
        assertTrue("应编译成功: $result", result is RunPlanResult.Success)
        return (result as RunPlanResult.Success).plan.tasks.single().pipelineOverrides
            .flatMap { it.keys }
            .filter { it in candidates }
    }

    private fun displayedCases(definition: ProjectDefinition, config: UserConfiguration): List<String> =
        ConfigurationResolver.resolve(definition, config).activeConfiguration!!.tasks.single()
            .options.first { it.name == OPTION }
            .activeCases.map { it.name }

    @Test
    fun `select 改成 switch 后旧 case 回落默认，跑的与显示的一致`() {
        val definition = definition(
            OptionDefinition.Switch(OPTION, OPTION, null, cases("No", "Yes"), defaultCase = null),
        )
        val config = config(OptionValue.SingleCase("关闭"))

        assertEquals(listOf("No"), compiledCases(RunPlanBuilder.build(definition, config), setOf("No", "Yes")))
        assertEquals(listOf("No"), displayedCases(definition, config))
    }

    @Test
    fun `改名的 case 回落到 default_case 而不是首项`() {
        val definition = definition(
            OptionDefinition.Select(OPTION, OPTION, null, cases("a", "b", "c"), defaultCase = "b"),
        )
        val config = config(OptionValue.SingleCase("旧名"))

        assertEquals(listOf("b"), compiledCases(RunPlanBuilder.build(definition, config), setOf("a", "b", "c")))
        assertEquals(listOf("b"), displayedCases(definition, config))
    }

    @Test
    fun `checkbox 里失效的名字滤掉，其余照常编译`() {
        val definition = definition(
            OptionDefinition.Checkbox(OPTION, OPTION, null, cases("a", "b", "c"), defaultCases = emptyList()),
        )
        val config = config(OptionValue.MultipleCases(listOf("c", "旧名", "a")))

        assertEquals(listOf("a", "c"), compiledCases(RunPlanBuilder.build(definition, config), setOf("a", "b", "c")))
        assertEquals(listOf("a", "c"), displayedCases(definition, config))
    }

    @Test
    fun `checkbox 滤掉失效名后不够下限仍拦下`() {
        val definition = definition(
            OptionDefinition.Checkbox(
                OPTION, OPTION, null, cases("a", "b"), defaultCases = emptyList(), minCount = 1,
            ),
        )
        val result = RunPlanBuilder.build(definition, config(OptionValue.MultipleCases(listOf("旧名"))))

        assertTrue("应被拦下: $result", result is RunPlanResult.Invalid)
    }
}
