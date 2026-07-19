package com.aliothmoon.maafw.project

import com.aliothmoon.maafw.domain.DiagnosticSeverity
import com.aliothmoon.maafw.domain.OptionDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 基于文件系统的 ProjectSource，供 JVM 测试直接读取仓库内置 PI。 */
class FileProjectSource(private val root: File) : ProjectSource {
    override val projectName: String = root.name

    override fun list(path: String): List<String> =
        File(root, path).listFiles()?.map { it.name }?.sorted().orEmpty()

    override fun read(path: String): String =
        File(root, path).readText(Charsets.UTF_8)
}

class ProjectLoaderTest {

    private val projectRoot = File("src/main/assets", M9A_ASSET_ROOT)

    private fun load(): ProjectLoadResult.Ready {
        val result = ProjectLoader(FileProjectSource(projectRoot)).load()
        assertTrue("加载应成功: $result", result is ProjectLoadResult.Ready)
        return result as ProjectLoadResult.Ready
    }

    @Test
    fun `加载内置 M9A 项目并合并全部分片`() {
        val ready = load()
        val definition = ready.definition

        assertEquals("m9a", definition.name)
        assertEquals("v0.1.0", definition.version)
        assertEquals(66, definition.tasks.size)
        assertEquals(7, definition.templates.size)
        assertTrue(definition.options.isNotEmpty())
        assertEquals(9, definition.resources.size)
        assertEquals(
            listOf("resource/base", "resource/global_jp", "resource/global_en"),
            definition.resources.first { it.name == "国际服（EN）" }.paths,
        )
        assertEquals(
            listOf("resource/base", "resource/global_jp", "resource/tw"),
            definition.resources.first { it.name == "港澳台服" }.paths,
        )
    }

    @Test
    fun `option 引用完整且无 Error 级诊断`() {
        val ready = load()
        val errors = ready.diagnostics.filter { it.severity == DiagnosticSeverity.Error }
        assertTrue("不应有 Error 诊断: $errors", errors.isEmpty())

        // task 引用的 option 都应存在
        ready.definition.tasks.forEach { task ->
            task.optionNames.forEach { name ->
                assertTrue("task ${task.name} 引用缺失 option $name", name in ready.definition.options)
            }
        }
    }

    @Test
    fun `switch option 解析出嵌套 case 子树`() {
        val ready = load()
        val option = ready.definition.options["自定义作战关卡"] as OptionDefinition.Switch
        val yes = option.cases.first { it.name == "Yes" }
        assertTrue("Yes case 应有子 option", yes.childOptionNames.contains("作战关卡(自定义)"))

        val input = ready.definition.options["作战关卡(自定义)"] as OptionDefinition.Input
        assertEquals(2, input.fields.size)
        assertTrue(input.fields.all { it.verify != null })
    }

    @Test
    fun `group 声明按顺序解析并携带 label`() {
        val definition = load().definition

        assertEquals(listOf("daily", "standalone"), definition.groups.take(2).map { it.name })
        assertEquals(listOf("日常任务", "独立任务"), definition.groups.take(2).map { it.label })
        // 声明组之外最多只允许追加一个「未分组」，且必须排在最后
        val extra = definition.groups.drop(2)
        assertTrue(
            "多余分组只能是未分组: $extra",
            extra.isEmpty() || extra.singleOrNull()?.name == ProjectLoader.UNGROUPED,
        )
    }

    @Test
    fun `preset 解析为模板并携带 option 值`() {
        val ready = load()
        val daily = ready.definition.templates.first { it.name == "日常-长草" }
        assertEquals(11, daily.tasks.size)
        assertEquals("启动游戏", daily.tasks.first().taskName)

        val rerelease = ready.definition.templates.first { it.name == "日常-复刻" }
        val withOption = rerelease.tasks.first { it.optionValues.isNotEmpty() }
        assertEquals("活动代币刷取", withOption.taskName)
    }
}

/** 内存 ProjectSource：按路径前缀模拟目录结构，供分组/解析规则的合成用例使用。 */
private class MapProjectSource(private val files: Map<String, String>) : ProjectSource {
    override val projectName: String = "synthetic"

    override fun list(path: String): List<String> {
        val prefix = "$path/"
        return files.keys
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix).substringBefore('/') }
            .distinct()
            .sorted()
    }

    override fun read(path: String): String =
        files[path] ?: throw IllegalArgumentException("no file: $path")
}

class ProjectLoaderGroupTest {

    private fun load(files: Map<String, String>): ProjectLoadResult.Ready {
        val result = ProjectLoader(MapProjectSource(files)).load()
        assertTrue("加载应成功: $result", result is ProjectLoadResult.Ready)
        return result as ProjectLoadResult.Ready
    }

    @Test
    fun `根与分片声明按顺序合并且重名先定义优先`() {
        val ready = load(
            mapOf(
                "interface.json" to """
                    {"name":"t","group":[{"name":"g1","label":"组一"},{"name":"g2"}]}
                """.trimIndent(),
                "tasks/a.json" to """
                    {
                        "group": [{"name":"g1","label":"重复的组一"},{"name":"g3"}],
                        "task": [
                            {"name":"T1","entry":"E1","group":["g1"]},
                            {"name":"T2","entry":"E2","group":["g3","g1"]}
                        ]
                    }
                """.trimIndent(),
            ),
        )
        val groups = ready.definition.groups
        assertEquals(listOf("g1", "g2", "g3"), groups.map { it.name })
        // label 缺省回落 name；重名声明保留先出现的定义
        assertEquals(listOf("组一", "g2", "g3"), groups.map { it.label })
        assertTrue(ready.diagnostics.any { it.severity == DiagnosticSeverity.Warning && "g1" in it.message })
    }

    @Test
    fun `未命中声明的引用被丢弃并落未分组`() {
        val ready = load(
            mapOf(
                "interface.json" to """{"name":"t","group":[{"name":"g1"}]}""",
                "tasks/a.json" to """
                    {"task":[
                        {"name":"T1","entry":"E1","group":["g1","nope"]},
                        {"name":"T2","entry":"E2","group":["nope"]}
                    ]}
                """.trimIndent(),
            ),
        )
        val definition = ready.definition
        // 部分命中：只保留命中的引用
        assertEquals(listOf("g1"), definition.tasks.first { it.name == "T1" }.groups)
        // 全部未命中：归一化为空，落未分组
        assertEquals(emptyList<String>(), definition.tasks.first { it.name == "T2" }.groups)
        assertEquals(listOf("g1", ProjectLoader.UNGROUPED), definition.groups.map { it.name })
        assertEquals(2, ready.diagnostics.count { "未声明的 group" in it.message })
    }

    @Test
    fun `无顶层声明时忽略任务级引用全部扁平`() {
        val ready = load(
            mapOf(
                "tasks/a.json" to """{"task":[{"name":"T1","entry":"E1","group":["x"]}]}""",
            ),
        )
        val definition = ready.definition
        assertEquals(listOf(ProjectLoader.UNGROUPED), definition.groups.map { it.name })
        assertEquals(emptyList<String>(), definition.tasks.single().groups)
    }

    @Test
    fun `JSONC 注释与尾逗号可解析`() {
        val ready = load(
            mapOf(
                "tasks/a.json" to """
                    {
                        // 行注释
                        "task": [
                            {"name":"T1","entry":"E1",}, /* 块注释 */
                        ],
                    }
                """.trimIndent(),
            ),
        )
        assertEquals("T1", ready.definition.tasks.single().name)
        assertTrue(ready.diagnostics.none { it.severity == DiagnosticSeverity.Error })
    }

    @Test
    fun `languages 按 locale 匹配并物化 i18n key`() {
        val ready = ProjectLoader(
            MapProjectSource(
                mapOf(
                    "interface.json" to """
                        {
                            "name": "t",
                            "languages": {"zh-CN": "./i18n/zh-CN.json", "en-US": "./i18n/en-US.json"},
                            "group": [{"name": "g1", "label": "${'$'}group.g1"}]
                        }
                    """.trimIndent(),
                    "i18n/zh-CN.json" to """{"group.g1": "分组一", "task.t1.desc": "任务一说明", "opt.label": "选项一"}""",
                    "i18n/en-US.json" to """{"group.g1": "Group One"}""",
                    "tasks/a.json" to """
                        {
                            "task": [{"name": "T1", "entry": "E1", "description": "${'$'}task.t1.desc", "group": ["g1"]}],
                            "option": {"O1": {"type": "select", "label": "${'$'}opt.label", "cases": [{"name": "c1"}]}}
                        }
                    """.trimIndent(),
                ),
            ),
            // 前缀匹配：zh-TW 未声明，应命中 zh-CN
            locale = "zh-TW",
        ).load() as ProjectLoadResult.Ready

        val definition = ready.definition
        assertEquals("任务一说明", definition.tasks.single().description)
        assertEquals("选项一", definition.options.getValue("O1").label)
        assertEquals("分组一", definition.groups.first { it.name == "g1" }.label)
    }

    @Test
    fun `i18n 查无翻译回落 key`() {
        val ready = load(
            mapOf(
                "tasks/a.json" to """{"task":[{"name":"T1","entry":"E1","description":"${'$'}missing.key"}]}""",
            ),
        )
        assertEquals("missing.key", ready.definition.tasks.single().description)
    }

    @Test
    fun `文件形态 description 加载期物化`() {
        val ready = load(
            mapOf(
                "tasks/a.json" to """{"task":[{"name":"T1","entry":"E1","description":"./docs/help.md"}]}""",
                "docs/help.md" to "# 帮助\n完整说明内容",
            ),
        )
        assertEquals("# 帮助\n完整说明内容", ready.definition.tasks.single().description)
    }

    @Test
    fun `URL 形态 description 原样保留`() {
        val url = "https://example.com/help.md"
        val ready = load(
            mapOf(
                "tasks/a.json" to """{"task":[{"name":"T1","entry":"E1","description":"$url"}]}""",
            ),
        )
        assertEquals(url, ready.definition.tasks.single().description)
    }

    @Test
    fun `hotkey option 跳过并降级为 warning`() {
        val ready = load(
            mapOf(
                "tasks/a.json" to """
                    {
                        "task": [{"name":"T1","entry":"E1"}],
                        "option": {"Keymap": {"type":"hotkey","hotkeys":[]}}
                    }
                """.trimIndent(),
            ),
        )
        assertTrue(ready.definition.options.isEmpty())
        assertTrue(ready.diagnostics.none { it.severity == DiagnosticSeverity.Error })
        assertTrue(ready.diagnostics.any { "hotkey" in it.message && it.severity == DiagnosticSeverity.Warning })
    }
}
