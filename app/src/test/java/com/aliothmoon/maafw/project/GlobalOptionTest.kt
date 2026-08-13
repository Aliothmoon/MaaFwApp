package com.aliothmoon.maafw.project

import com.aliothmoon.maafw.config.ConfigurationResolver
import com.aliothmoon.maafw.domain.ConfiguredTask
import com.aliothmoon.maafw.domain.DiagnosticSeverity
import com.aliothmoon.maafw.domain.OptionValue
import com.aliothmoon.maafw.domain.ProjectDefinition
import com.aliothmoon.maafw.domain.RunConfiguration
import com.aliothmoon.maafw.domain.RunConfigurationId
import com.aliothmoon.maafw.domain.UserConfiguration
import com.aliothmoon.maafw.runner.RunPlanBuilder
import com.aliothmoon.maafw.runner.RunPlanResult
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PI v2.3.0 `global_option[]` 全链路：解析合并 → 只读投影 → override 编译顺序
 *
 * 一律用合成 PI：`src/test/fixtures/PI/M9A` 那份没声明 global_option，
 * 而它是冻结的断言基准，不该为新特性改动
 */
class GlobalOptionTest {

    /** 两个 option：global 与 task 都往同一个 pipeline 节点写，用来验先后 */
    private fun pi(
        globalOption: String = """"global_option":["音量"],""",
        volumeScope: String = "",
    ): String = """
        {
          "interface_version": 2,
          "name": "t",
          "resource": [
            {"name": "官服", "path": "resource/cn"},
            {"name": "国际服", "path": "resource/global"}
          ],
          "controller": [{"name": "安卓", "type": "Adb"}],
          $globalOption
          "task": [{"name": "刷图", "entry": "Fight", "option": ["难度"]}],
          "option": {
            "音量": {
              "type": "select",
              "default_case": "静音",
              $volumeScope
              "cases": [
                {"name": "静音", "pipeline_override": {"Fight": {"volume": 0, "from": "global"}}},
                {"name": "正常", "pipeline_override": {"Fight": {"volume": 100, "from": "global"}}}
              ]
            },
            "难度": {
              "type": "select",
              "default_case": "普通",
              "cases": [{"name": "普通", "pipeline_override": {"Fight": {"from": "task"}}}]
            }
          }
        }
    """.trimIndent()

    /** 全局 option 缺 default_case：应回落首个 case（甲） */
    private fun withoutDefaultCase(): String =
        pi(globalOption = """"global_option":["无默认"],""").replace(
            """"难度": {""",
            """"无默认": {"type": "select", "cases": [
              {"name": "甲", "pipeline_override": {"Fight": {"pick": "first"}}},
              {"name": "乙", "pipeline_override": {"Fight": {"pick": "second"}}}
            ]},
            "难度": {""",
        )

    /** cases 为空：回落也没有落点，编译时必然报「未设值且无默认」 */
    private fun withoutUsableDefault(): String =
        pi(globalOption = """"global_option":["无落点"],""").replace(
            """"难度": {""",
            """"无落点": {"type": "select", "cases": []},
            "难度": {""",
        )

    private fun load(root: String): ProjectLoadResult.Ready {
        val result = loadWithLocale("zh-CN", MapProjectSource(mapOf("interface.json" to root)))
        assertTrue("加载应成功: $result", result is ProjectLoadResult.Ready)
        return result as ProjectLoadResult.Ready
    }

    private fun configWith(
        globalValues: Map<String, OptionValue> = emptyMap(),
        resourceName: String = "官服",
    ): UserConfiguration {
        val id = RunConfigurationId("c1")
        return UserConfiguration(
            initialized = true,
            activeResourceName = resourceName,
            globalOptionValues = globalValues,
            configurations = listOf(RunConfiguration(id, "测试", listOf(ConfiguredTask("刷图")))),
            activeConfigurationId = id,
        )
    }

    private fun plan(definition: ProjectDefinition, config: UserConfiguration): RunPlanResult.Success {
        val result = RunPlanBuilder.build(definition, config)
        assertTrue("应产出可执行计划: $result", result is RunPlanResult.Success)
        return result as RunPlanResult.Success
    }

    @Test
    fun `global_option 解析进 definition`() {
        assertEquals(listOf("音量"), load(pi()).definition.globalOptionNames)
    }

    @Test
    fun `未声明 global_option 时为空`() {
        assertEquals(emptyList<String>(), load(pi(globalOption = "")).definition.globalOptionNames)
    }

    /** 协议「Option 覆盖顺序」 */
    @Test
    fun `global 排在 task option 之前`() {
        val definition = load(pi()).definition
        val patches = plan(definition, configWith()).plan.tasks.single().pipelineOverrides
        val from = patches.mapNotNull { it["Fight"]?.jsonObject?.get("from")?.jsonPrimitive?.content }
        assertEquals(listOf("global", "task"), from)
    }

    @Test
    fun `未设值时取 default_case`() {
        val definition = load(pi()).definition
        val patches = plan(definition, configWith()).plan.tasks.single().pipelineOverrides
        val volume = patches.firstNotNullOf { it["Fight"]?.jsonObject?.get("volume") }
        assertEquals("0", volume.jsonPrimitive.content)
    }

    @Test
    fun `用户改过的值压过 default_case`() {
        val definition = load(pi()).definition
        val config = configWith(mapOf("音量" to OptionValue.SingleCase("正常")))
        val patches = plan(definition, config).plan.tasks.single().pipelineOverrides
        val volume = patches.firstNotNullOf { it["Fight"]?.jsonObject?.get("volume") }
        assertEquals("100", volume.jsonPrimitive.content)
    }

    /** v2.3.1：option 自身的 resource 限制对 global 同样生效，不满足即整个不参与合并 */
    @Test
    fun `不满足 resource 限制的 global option 不产生 override`() {
        val definition = load(pi(volumeScope = """"resource":["国际服"],""")).definition

        val onCn = plan(definition, configWith(resourceName = "官服")).plan.tasks.single()
        assertEquals(
            listOf("task"),
            onCn.pipelineOverrides.mapNotNull { it["Fight"]?.jsonObject?.get("from")?.jsonPrimitive?.content },
        )

        val onGlobal = plan(definition, configWith(resourceName = "国际服")).plan.tasks.single()
        assertEquals(
            listOf("global", "task"),
            onGlobal.pipelineOverrides.mapNotNull { it["Fight"]?.jsonObject?.get("from")?.jsonPrimitive?.content },
        )
    }

    /** 生态里绝大多数 option 不写 default_case，回落首个 case 而不是把整轮拦下 */
    @Test
    fun `无 default_case 时回落首个 case`() {
        val definition = load(withoutDefaultCase()).definition
        val patches = plan(definition, configWith()).plan.tasks.single().pipelineOverrides
        val pick = patches.firstNotNullOf { it["Fight"]?.jsonObject?.get("pick") }
        assertEquals("first", pick.jsonPrimitive.content)
    }

    /** 全局 option 与任务无关，诊断不该按启用任务数翻倍 */
    @Test
    fun `cases 为空时只报一条诊断`() {
        val definition = load(withoutUsableDefault()).definition
        val id = RunConfigurationId("c1")
        val config = UserConfiguration(
            initialized = true,
            activeResourceName = "官服",
            configurations = listOf(
                RunConfiguration(id, "测试", listOf(ConfiguredTask("刷图"), ConfiguredTask("刷图"))),
            ),
            activeConfigurationId = id,
        )

        val result = RunPlanBuilder.build(definition, config)
        assertTrue("应因缺默认值失败: $result", result is RunPlanResult.Invalid)
        assertEquals(
            1,
            (result as RunPlanResult.Invalid).diagnostics.count { it.source == "global_option" },
        )
    }

    /** 跑不到的全局 option 不该把「没有可执行任务」盖成 Invalid */
    @Test
    fun `任务全禁用时仍报 NoExecutableTasks`() {
        val definition = load(withoutUsableDefault()).definition
        val id = RunConfigurationId("c1")
        val config = UserConfiguration(
            initialized = true,
            activeResourceName = "官服",
            configurations = listOf(
                RunConfiguration(id, "测试", listOf(ConfiguredTask("刷图", enabled = false))),
            ),
            activeConfigurationId = id,
        )

        assertTrue(RunPlanBuilder.build(definition, config) is RunPlanResult.NoExecutableTasks)
    }

    @Test
    fun `引用不存在的 option 记 Error 并从 definition 里剔除`() {
        val ready = load(pi(globalOption = """"global_option":["音量","不存在的"],"""))
        assertEquals(listOf("音量"), ready.definition.globalOptionNames)
        assertTrue(
            "应报引用缺失: ${ready.diagnostics}",
            ready.diagnostics.any { it.severity == DiagnosticSeverity.Error && it.source == "global_option" },
        )
    }

    @Test
    fun `import 分片的 global_option 追加去重`() {
        val root = """
            {
              "interface_version": 2,
              "name": "t",
              "global_option": ["音量"],
              "import": ["extra.json"],
              "resource": [{"name": "官服", "path": "resource/cn"}],
              "controller": [{"name": "安卓", "type": "Adb"}],
              "task": [{"name": "刷图", "entry": "Fight"}],
              "option": {
                "音量": {"type": "switch", "default_case": "No", "cases": [{"name": "No"}, {"name": "Yes"}]},
                "画质": {"type": "switch", "default_case": "No", "cases": [{"name": "No"}, {"name": "Yes"}]}
              }
            }
        """.trimIndent()
        val extra = """{"global_option":["音量","画质"]}"""
        val result = loadWithLocale(
            "zh-CN",
            MapProjectSource(mapOf("interface.json" to root, "extra.json" to extra)),
        ) as ProjectLoadResult.Ready

        assertEquals(listOf("音量", "画质"), result.definition.globalOptionNames)
    }

    @Test
    fun `resolver 按声明顺序投影出可编辑项`() {
        val definition = load(pi()).definition
        val session = ConfigurationResolver.resolve(definition, configWith())
        assertEquals(listOf("音量"), session.globalOptions.map { it.name })
        // 未设值时选中态回落 default_case
        assertEquals(listOf("静音"), session.globalOptions.single().activeCases.map { it.name })
    }

    /** docs/domain-model.md §6.3：不适用的 option 连同子树整个不显示，但值不删 */
    @Test
    fun `resolver 隐藏不适用于当前资源的 global option`() {
        val definition = load(pi(volumeScope = """"resource":["国际服"],""")).definition
        val stored = mapOf("音量" to OptionValue.SingleCase("正常"))

        assertEquals(
            emptyList<String>(),
            ConfigurationResolver.resolve(definition, configWith(stored, "官服")).globalOptions.map { it.name },
        )
        assertEquals(
            listOf("音量"),
            ConfigurationResolver.resolve(definition, configWith(stored, "国际服")).globalOptions.map { it.name },
        )
    }
}
