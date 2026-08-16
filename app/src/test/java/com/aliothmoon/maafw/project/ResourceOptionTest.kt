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
 * PI v2.3.0 `resource[].option` 全链路：解析 → 按资源分桶投影 → override 编译顺序
 *
 * 一律用合成 PI：冻结的 M9A fixture 没有 resource.option，不该为新特性改动
 */
class ResourceOptionTest {

    private fun pi(
        officialOptions: String = """"option":["渠道"],""",
        globalOption: String = """"global_option":["音量"],""",
        extraOption: String = "",
    ): String = """
        {
          "interface_version": 2,
          "name": "t",
          "resource": [
            {"name": "官服", "path": "resource/cn", $officialOptions},
            {"name": "国际服", "path": "resource/global", "option": ["语言"]}
          ],
          "controller": [{"name": "安卓", "type": "Adb"}],
          $globalOption
          "task": [{
            "name": "刷图",
            "entry": "Fight",
            "option": ["难度"],
            "pipeline_override": {"Fight": {"from": "task_base"}}
          }],
          "option": {
            "音量": {
              "type": "select",
              "default_case": "静音",
              "cases": [
                {"name": "静音", "pipeline_override": {"Fight": {"from": "global"}}},
                {"name": "正常", "pipeline_override": {"Fight": {"from": "global"}}}
              ]
            },
            "渠道": {
              "type": "select",
              "default_case": "官服渠道",
              "cases": [
                {"name": "官服渠道", "pipeline_override": {"Fight": {"from": "resource", "channel": "official"}}},
                {"name": "B服", "pipeline_override": {"Fight": {"from": "resource", "channel": "bili"}}}
              ]
            },
            "语言": {
              "type": "select",
              "default_case": "英语",
              "cases": [
                {"name": "英语", "pipeline_override": {"Fight": {"from": "resource", "lang": "en"}}}
              ]
            },
            $extraOption
            "难度": {
              "type": "select",
              "default_case": "普通",
              "cases": [{"name": "普通", "pipeline_override": {"Fight": {"from": "task"}}}]
            }
          }
        }
    """.trimIndent()

    private fun load(root: String): ProjectLoadResult.Ready {
        val result = loadWithLocale("zh-CN", MapProjectSource(mapOf("interface.json" to root)))
        assertTrue("加载应成功: $result", result is ProjectLoadResult.Ready)
        return result as ProjectLoadResult.Ready
    }

    private fun configWith(
        resourceName: String = "官服",
        resourceValues: Map<String, Map<String, OptionValue>> = emptyMap(),
        globalValues: Map<String, OptionValue> = emptyMap(),
        enabled: Boolean = true,
        extraTasks: List<ConfiguredTask> = emptyList(),
    ): UserConfiguration {
        val id = RunConfigurationId("c1")
        return UserConfiguration(
            initialized = true,
            activeResourceName = resourceName,
            globalOptionValues = globalValues,
            resourceOptionValues = resourceValues,
            configurations = listOf(
                RunConfiguration(
                    id,
                    "测试",
                    listOf(ConfiguredTask("刷图", enabled = enabled)) + extraTasks,
                ),
            ),
            activeConfigurationId = id,
        )
    }

    private fun plan(definition: ProjectDefinition, config: UserConfiguration): RunPlanResult.Success {
        val result = RunPlanBuilder.build(definition, config)
        assertTrue("应产出可执行计划: $result", result is RunPlanResult.Success)
        return result as RunPlanResult.Success
    }

    private fun fromMarks(result: RunPlanResult.Success): List<String> =
        result.plan.tasks.single().pipelineOverrides.mapNotNull {
            it["Fight"]?.jsonObject?.get("from")?.jsonPrimitive?.content
        }

    @Test
    fun `resource option 解析进 definition`() {
        val resources = load(pi()).definition.resources
        assertEquals(listOf("渠道"), resources.single { it.name == "官服" }.optionNames)
        assertEquals(listOf("语言"), resources.single { it.name == "国际服" }.optionNames)
    }

    @Test
    fun `未声明 resource option 时为空`() {
        val official = load(pi(officialOptions = "")).definition.resources.single { it.name == "官服" }
        assertEquals(emptyList<String>(), official.optionNames)
    }

    /** 协议「Option 覆盖顺序」：task 基础 → global → resource → task option */
    @Test
    fun `resource 排在 global 之后 task option 之前`() {
        val definition = load(pi()).definition
        assertEquals(
            listOf("task_base", "global", "resource", "task"),
            fromMarks(plan(definition, configWith())),
        )
    }

    @Test
    fun `未设值时取 default_case`() {
        val definition = load(pi()).definition
        val channel = plan(definition, configWith()).plan.tasks.single().pipelineOverrides
            .firstNotNullOf { it["Fight"]?.jsonObject?.get("channel") }
        assertEquals("official", channel.jsonPrimitive.content)
    }

    @Test
    fun `用户改过的值压过 default_case`() {
        val definition = load(pi()).definition
        val config = configWith(
            resourceValues = mapOf("官服" to mapOf("渠道" to OptionValue.SingleCase("B服"))),
        )
        val channel = plan(definition, config).plan.tasks.single().pipelineOverrides
            .firstNotNullOf { it["Fight"]?.jsonObject?.get("channel") }
        assertEquals("bili", channel.jsonPrimitive.content)
    }

    @Test
    fun `换资源换一份 option 且值按 name 分桶`() {
        val definition = load(pi()).definition
        val stored = mapOf(
            "官服" to mapOf("渠道" to OptionValue.SingleCase("B服")),
            "国际服" to mapOf("语言" to OptionValue.SingleCase("英语")),
        )

        val onOfficial = plan(definition, configWith(resourceName = "官服", resourceValues = stored))
        assertEquals("bili", onOfficial.plan.tasks.single().pipelineOverrides
            .firstNotNullOf { it["Fight"]?.jsonObject?.get("channel") }.jsonPrimitive.content)
        assertTrue(onOfficial.plan.tasks.single().pipelineOverrides.none {
            it["Fight"]?.jsonObject?.containsKey("lang") == true
        })

        val onGlobal = plan(definition, configWith(resourceName = "国际服", resourceValues = stored))
        assertEquals("en", onGlobal.plan.tasks.single().pipelineOverrides
            .firstNotNullOf { it["Fight"]?.jsonObject?.get("lang") }.jsonPrimitive.content)
        assertTrue(onGlobal.plan.tasks.single().pipelineOverrides.none {
            it["Fight"]?.jsonObject?.containsKey("channel") == true
        })
    }

    /** v2.3.1：option 自身的 resource 限制对 resource.option 同样生效 */
    @Test
    fun `不满足 resource 限制的 resource option 不产生 override`() {
        val extra = """
            "仅国际": {
              "type": "select",
              "resource": ["国际服"],
              "default_case": "开",
              "cases": [{"name": "开", "pipeline_override": {"Fight": {"extra": "yes"}}}]
            },
        """.trimIndent()
        val definition = load(
            pi(officialOptions = """"option":["渠道","仅国际"],""", extraOption = extra),
        ).definition

        val onCn = plan(definition, configWith(resourceName = "官服"))
        assertTrue(onCn.plan.tasks.single().pipelineOverrides.none {
            it["Fight"]?.jsonObject?.containsKey("extra") == true
        })
    }

    @Test
    fun `引用不存在的 option 记 Error 并从 definition 里剔除`() {
        val ready = load(pi(officialOptions = """"option":["渠道","不存在的"],"""))
        assertEquals(
            listOf("渠道"),
            ready.definition.resources.single { it.name == "官服" }.optionNames,
        )
        assertTrue(
            "应报引用缺失: ${ready.diagnostics}",
            ready.diagnostics.any {
                it.severity == DiagnosticSeverity.Error && it.source == "resource:官服"
            },
        )
    }

    @Test
    fun `resolver 只投影当前资源的 option`() {
        val definition = load(pi()).definition

        val official = ConfigurationResolver.resolve(definition, configWith(resourceName = "官服"))
        assertEquals(listOf("渠道"), official.resourceOptions.map { it.name })
        assertEquals(listOf("官服渠道"), official.resourceOptions.single().activeCases.map { it.name })

        val global = ConfigurationResolver.resolve(definition, configWith(resourceName = "国际服"))
        assertEquals(listOf("语言"), global.resourceOptions.map { it.name })
    }

    @Test
    fun `resolver 换资源后仍保留另一份已存的值`() {
        val definition = load(pi()).definition
        val stored = mapOf("官服" to mapOf("渠道" to OptionValue.SingleCase("B服")))

        val onGlobal = ConfigurationResolver.resolve(
            definition,
            configWith(resourceName = "国际服", resourceValues = stored),
        )
        assertEquals(listOf("语言"), onGlobal.resourceOptions.map { it.name })

        val back = ConfigurationResolver.resolve(
            definition,
            configWith(resourceName = "官服", resourceValues = stored),
        )
        assertEquals(listOf("B服"), back.resourceOptions.single().activeCases.map { it.name })
    }

    @Test
    fun `cases 为空时只报一条诊断`() {
        val extra = """
            "无落点": {"type": "select", "cases": []},
        """.trimIndent()
        val definition = load(
            pi(officialOptions = """"option":["无落点"],""", extraOption = extra),
        ).definition
        val config = configWith(extraTasks = listOf(ConfiguredTask("刷图")))

        val result = RunPlanBuilder.build(definition, config)
        assertTrue("应因缺默认值失败: $result", result is RunPlanResult.Invalid)
        assertEquals(
            1,
            (result as RunPlanResult.Invalid).diagnostics.count { it.source == "resource:官服" },
        )
    }

    @Test
    fun `任务全禁用时仍报 NoExecutableTasks`() {
        val extra = """
            "无落点": {"type": "select", "cases": []},
        """.trimIndent()
        val definition = load(
            pi(officialOptions = """"option":["无落点"],""", extraOption = extra),
        ).definition

        assertTrue(
            RunPlanBuilder.build(definition, configWith(enabled = false))
                is RunPlanResult.NoExecutableTasks,
        )
    }
}
