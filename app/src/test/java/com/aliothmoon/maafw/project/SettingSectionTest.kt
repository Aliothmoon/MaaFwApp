package com.aliothmoon.maafw.project

import com.aliothmoon.maafw.config.ConfigurationResolver
import com.aliothmoon.maafw.domain.Diagnostic
import com.aliothmoon.maafw.domain.DiagnosticSeverity
import com.aliothmoon.maafw.domain.UserConfiguration
import com.aliothmoon.maafw.i18n.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PI v2.8.0 `setting[]`：只给 global_option 在设置页分区，不参与编译 */
class SettingSectionTest {

    private fun select(name: String, extra: String = "") = """
        "$name": {
          "type": "select",
          $extra
          "cases": [{"name": "开"}, {"name": "关"}]
        }
    """

    private val pi = """
        {
          "interface_version": 2,
          "name": "t",
          "resource": [
            {"name": "官服", "path": "resource/cn"},
            {"name": "国际服", "path": "resource/global"}
          ],
          "controller": [{"name": "安卓", "type": "Adb"}],
          "global_option": ["音量", "画质", "仅国际服"],
          "setting": [
            {
              "name": "audio",
              "label": "声音",
              "description": "声音相关",
              "icon": "./setting/audio.png",
              "option": ["音量", "不存在", "难度"],
              "default_expand": false
            },
            {"label": "没有名字"},
            {"name": "audio", "option": ["画质"]}
          ],
          "import": ["more.json"],
          "task": [{"name": "刷图", "entry": "Fight", "option": ["难度"]}],
          "option": {
            ${select("音量")},
            ${select("画质")},
            ${select("难度")},
            ${select("仅国际服", """"resource": ["国际服"],""")}
          }
        }
    """.trimIndent()

    private val more = """{ "setting": [{"name": "visual", "option": ["画质", "仅国际服"]}] }"""

    private fun load() = loadWithLocale(
        "zh-CN",
        MapProjectSource(mapOf("interface.json" to pi, "more.json" to more)),
    ) as ProjectLoadResult.Ready

    private fun Diagnostic.args(): List<String> = (message as? UiText.Resource)?.args.orEmpty().map { it.toString() }

    @Test
    fun `分区按声明顺序合并，import 追加在后，重名保留先声明的`() {
        val sections = load().definition.settingSections

        assertEquals(listOf("audio", "visual"), sections.map { it.name })
        val audio = sections.first()
        assertEquals("声音", audio.label)
        assertEquals("声音相关", audio.description)
        assertEquals("setting/audio.png", audio.icon)
        assertFalse(audio.defaultExpand)
        assertEquals("visual", sections[1].label)
        assertTrue(sections[1].defaultExpand)
    }

    @Test
    fun `不存在的键记 Error，不在 global_option 里的记 warning，都剔除`() {
        val result = load()
        val audio = result.definition.settingSections.first()

        assertEquals(listOf("音量"), audio.optionNames)
        assertTrue(result.diagnostics.any { it.severity == DiagnosticSeverity.Error && "不存在" in it.args() })
        assertTrue(result.diagnostics.any { it.severity == DiagnosticSeverity.Warning && "难度" in it.args() })
    }

    @Test
    fun `缺 name 记 Error，重名记 warning`() {
        val diagnostics = load().diagnostics

        assertTrue(diagnostics.any { it.severity == DiagnosticSeverity.Error && "setting" in it.args() && "name" in it.args() })
        assertTrue(diagnostics.any { it.severity == DiagnosticSeverity.Warning && "audio" in it.args() })
    }

    @Test
    fun `分区投影跟着适用性走，没被收录的留在兜底`() {
        val definition = load().definition

        val official = ConfigurationResolver.resolve(definition, UserConfiguration(initialized = true, activeResourceName = "官服"))
        assertEquals(
            listOf("audio" to listOf("音量"), "visual" to listOf("画质")),
            official.settingSections.map { section -> section.name to section.options.map { it.name } },
        )
        assertEquals(listOf("音量", "画质"), official.globalOptions.map { it.name })

        val global = ConfigurationResolver.resolve(definition, UserConfiguration(initialized = true, activeResourceName = "国际服"))
        assertEquals(listOf("画质", "仅国际服"), global.settingSections.last().options.map { it.name })
    }

    @Test
    fun `分区里的选项全不适用时整个分区不出现`() {
        val onlyGlobalServer = pi.replace(
            """"option": ["音量", "不存在", "难度"]""",
            """"option": ["仅国际服"]""",
        )
        val definition = (
            loadWithLocale("zh-CN", MapProjectSource(mapOf("interface.json" to onlyGlobalServer, "more.json" to "{}")))
                as ProjectLoadResult.Ready
            ).definition

        val sections = ConfigurationResolver.resolve(definition, UserConfiguration(initialized = true, activeResourceName = "官服"))
            .settingSections
        assertTrue(sections.isEmpty())
    }
}
