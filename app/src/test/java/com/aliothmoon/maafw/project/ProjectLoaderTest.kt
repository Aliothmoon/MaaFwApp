package com.aliothmoon.maafw.project

import com.aliothmoon.maafw.config.ConfigurationResolver
import com.aliothmoon.maafw.domain.ConfiguredTask
import com.aliothmoon.maafw.domain.DiagnosticSeverity
import com.aliothmoon.maafw.domain.DiagnosticMessage
import com.aliothmoon.maafw.domain.OptionDefinition
import com.aliothmoon.maafw.domain.RunConfiguration
import com.aliothmoon.maafw.domain.RunConfigurationId
import com.aliothmoon.maafw.domain.UserConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/** 基于文件系统的 ProjectSource，供 JVM 测试直接读取仓库内置 PI */
class FileProjectSource(private val root: File) : ProjectSource {
    override val projectName: String = root.name

    override fun list(path: String): List<String> =
        File(root, path).listFiles()?.map { it.name }?.sorted().orEmpty()

    override fun read(path: String): String =
        File(root, path).readText(Charsets.UTF_8)
}

class ProjectLoaderTest {

    companion object {
        // M9A 全量加载（29 个分片 + 翻译）较重，整个测试类共享一次结果
        // 夹具固定在 test/fixtures，不随打包资源更换而变
        private lateinit var ready: ProjectLoadResult.Ready

        @JvmStatic
        @BeforeClass
        fun loadProject() {
            // locale 显式固定，不依赖运行机默认语言
            val result = ProjectLoader(
                FileProjectSource(File("src/test/fixtures/PI/M9A")),
                localeProvider = { "zh-CN" },
            ).load()
            assertTrue("加载应成功: $result", result is ProjectLoadResult.Ready)
            ready = result as ProjectLoadResult.Ready
        }
    }

    private fun load(): ProjectLoadResult.Ready = ready

    @Test
    fun `加载内置 M9A 项目并按 import 声明合并分片`() {
        val ready = load()
        val definition = ready.definition

        assertEquals("m9a", definition.name)
        assertEquals("v0.1.0", definition.version)
        // 严格 import 语义：只加载 interface.json + import[] 声明的 29 个分片
        assertEquals(25, definition.tasks.size)
        assertEquals(4, definition.templates.size)
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

/** 内存 ProjectSource：按路径前缀模拟目录结构，供分组/解析规则的合成用例使用 */
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

/** 生成合法的 V2 根文件：interface_version + import 声明，body 追加其余顶层字段 */
private fun piRoot(vararg imports: String, body: String = ""): String {
    val importJson = imports.joinToString(",") { "\"$it\"" }
    val extra = if (body.isBlank()) "" else ",$body"
    return """{"interface_version":2,"name":"t"$extra,"import":[$importJson]}"""
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
                "interface.json" to piRoot(
                    "tasks/a.json",
                    body = """"group":[{"name":"g1","label":"组一"},{"name":"g2"}]""",
                ),
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
        assertTrue(
            ready.diagnostics.any {
                it.severity == DiagnosticSeverity.Warning &&
                    it.message == DiagnosticMessage.DuplicateDeclaration("group", "g1")
            },
        )
    }

    @Test
    fun `未命中声明的引用被丢弃并落未分组`() {
        val ready = load(
            mapOf(
                "interface.json" to piRoot("tasks/a.json", body = """"group":[{"name":"g1"}]"""),
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
        assertEquals(
            2,
            ready.diagnostics.count { it.message is DiagnosticMessage.MissingReference },
        )
    }

    @Test
    fun `无顶层声明时忽略任务级引用全部扁平`() {
        val ready = load(
            mapOf(
                "interface.json" to piRoot("tasks/a.json"),
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
                "interface.json" to piRoot("tasks/a.json"),
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
                            "interface_version": 2,
                            "name": "t",
                            "languages": {"zh-CN": "./i18n/zh-CN.json", "en-US": "./i18n/en-US.json"},
                            "resource": [{"name": "cn", "label": "${'$'}resource.cn.label", "path": ["./resource"]}],
                            "group": [{"name": "g1", "label": "${'$'}group.g1"}],
                            "import": ["tasks/a.json"]
                        }
                    """.trimIndent(),
                    "i18n/zh-CN.json" to """{"resource.cn.label": "国服资源", "group.g1": "分组一", "task.t1.label": "任务一", "task.t1.desc": "任务一说明", "opt.label": "选项一", "input.label": "次数"}""",
                    "i18n/en-US.json" to """{"group.g1": "Group One"}""",
                    "tasks/a.json" to """
                        {
                            "task": [{"name": "T1", "entry": "E1", "label": "${'$'}task.t1.label", "description": "${'$'}task.t1.desc", "group": ["g1"], "option": ["I1"]}],
                            "option": {
                                "O1": {"type": "select", "label": "${'$'}opt.label", "cases": [{"name": "c1"}]},
                                "I1": {"type": "input", "inputs": [{"name": "Times", "label": "${'$'}input.label"}]}
                            }
                        }
                    """.trimIndent(),
                ),
            ),
            // 前缀匹配：zh-TW 未声明，应命中 zh-CN
            localeProvider = { "zh-TW" },
        ).load() as ProjectLoadResult.Ready

        val definition = ready.definition
        assertEquals("任务一", definition.tasks.single().label)
        assertEquals("任务一说明", definition.tasks.single().description)
        assertEquals("选项一", definition.options.getValue("O1").label)
        assertEquals("分组一", definition.groups.first { it.name == "g1" }.label)
        assertEquals("国服资源", definition.resources.single().label)
        val input = definition.options.getValue("I1") as OptionDefinition.Input
        assertEquals("次数", input.fields.single().label)

        val configurationId = RunConfigurationId("localized-labels")
        val session = ConfigurationResolver.resolve(
            definition,
            UserConfiguration(
                initialized = true,
                activeResourceName = "cn",
                configurations = listOf(
                    RunConfiguration(configurationId, "test", listOf(ConfiguredTask("T1"))),
                ),
                activeConfigurationId = configurationId,
            ),
        )
        assertEquals("国服资源", session.environment.resource?.label)
        assertEquals(
            "次数",
            session.activeConfiguration!!.tasks.single().options.single().inputs.single().label,
        )
    }

    @Test
    fun `languages 下划线风格 tag 可与 BCP-47 locale 匹配`() {
        val ready = ProjectLoader(
            MapProjectSource(
                mapOf(
                    "interface.json" to """
                        {
                            "interface_version": 2,
                            "name": "t",
                            "languages": {"en_us": "./i18n/en.json", "zh_cn": "./i18n/zh.json"},
                            "import": ["tasks/a.json"]
                        }
                    """.trimIndent(),
                    "i18n/en.json" to """{"t1": "Task One"}""",
                    "i18n/zh.json" to """{"t1": "任务一"}""",
                    "tasks/a.json" to """{"task":[{"name":"T1","entry":"E1","label":"${'$'}t1"}]}""",
                ),
            ),
            // zh_cn 与 zh-CN 归一化后应精确命中，而不是回落首个声明的 en_us
            localeProvider = { "zh-CN" },
        ).load() as ProjectLoadResult.Ready

        assertEquals("任务一", ready.definition.tasks.single().label)
    }

    @Test
    fun `未声明的语言回落首个声明`() {
        val ready = ProjectLoader(
            MapProjectSource(
                mapOf(
                    "interface.json" to """
                        {
                            "interface_version": 2,
                            "name": "t",
                            "languages": {"zh_cn": "./i18n/zh.json", "en_us": "./i18n/en.json"},
                            "import": ["tasks/a.json"]
                        }
                    """.trimIndent(),
                    "i18n/zh.json" to """{"t1": "任务一"}""",
                    "i18n/en.json" to """{"t1": "Task One"}""",
                    "tasks/a.json" to """{"task":[{"name":"T1","entry":"E1","label":"${'$'}t1"}]}""",
                ),
            ),
            // ja-JP 精确与前缀都不命中，回落声明序首位 zh_cn
            localeProvider = { "ja-JP" },
        ).load() as ProjectLoadResult.Ready

        assertEquals("任务一", ready.definition.tasks.single().label)
    }

    @Test
    fun `i18n 查无翻译回落 key`() {
        val ready = load(
            mapOf(
                "interface.json" to piRoot("tasks/a.json"),
                "tasks/a.json" to """{"task":[{"name":"T1","entry":"E1","description":"${'$'}missing.key"}]}""",
            ),
        )
        assertEquals("missing.key", ready.definition.tasks.single().description)
    }

    @Test
    fun `文件形态 description 加载期物化`() {
        val ready = load(
            mapOf(
                "interface.json" to piRoot("tasks/a.json"),
                "tasks/a.json" to """{"task":[{"name":"T1","entry":"E1","description":"./docs/help.md"}]}""",
                "docs/help.md" to "# 帮助\n完整说明内容",
            ),
        )
        assertEquals("# 帮助\n完整说明内容", ready.definition.tasks.single().description)
    }

    @Test
    fun `以文档扩展名结尾的本地化说明仍作为正文`() {
        val description = "筛选结束后保存到工作目录下的 EssencePlan.html"
        val ready = ProjectLoader(
            source = MapProjectSource(
                mapOf(
                    "interface.json" to """
                        {
                            "interface_version": 2,
                            "languages": {"zh_cn": "./i18n/zh.json"},
                            "import": ["tasks/a.json"]
                        }
                    """.trimIndent(),
                    "i18n/zh.json" to """{"task.description": "$description"}""",
                    "tasks/a.json" to """{"task":[{"name":"T1","entry":"E1","description":"${'$'}task.description"}]}""",
                ),
            ),
            localeProvider = { "zh-CN" },
        ).load() as ProjectLoadResult.Ready

        assertEquals(description, ready.definition.tasks.single().description)
        assertTrue(ready.diagnostics.none { it.message is DiagnosticMessage.DescriptionReadFailed })
    }

    @Test
    fun `URL 形态 description 原样保留`() {
        val url = "https://example.com/help.md"
        val ready = load(
            mapOf(
                "interface.json" to piRoot("tasks/a.json"),
                "tasks/a.json" to """{"task":[{"name":"T1","entry":"E1","description":"$url"}]}""",
            ),
        )
        assertEquals(url, ready.definition.tasks.single().description)
    }

    @Test
    fun `hotkey option 跳过并降级为 warning`() {
        val ready = load(
            mapOf(
                "interface.json" to piRoot("tasks/a.json"),
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
        assertTrue(
            ready.diagnostics.any {
                it.message == DiagnosticMessage.UnsupportedOptionType("Keymap", "hotkey") &&
                    it.severity == DiagnosticSeverity.Warning
            },
        )
    }
}

class ProjectLoaderProtocolTest {

    @Test
    fun `缺少 interface_version 拒绝加载`() {
        val result = ProjectLoader(
            MapProjectSource(mapOf("interface.json" to """{"name":"t","task":[]}""")),
        ).load()
        assertTrue("应为 Failure: $result", result is ProjectLoadResult.Failure)
        val diagnostics = (result as ProjectLoadResult.Failure).diagnostics
        assertTrue(
            diagnostics.any {
                it.message == DiagnosticMessage.MissingInterfaceVersion &&
                    it.severity == DiagnosticSeverity.Error
            },
        )
    }

    @Test
    fun `interface_version 非 2 拒绝加载`() {
        val result = ProjectLoader(
            MapProjectSource(mapOf("interface.json" to """{"interface_version":1,"name":"t"}""")),
        ).load()
        assertTrue("应为 Failure: $result", result is ProjectLoadResult.Failure)
    }

    @Test
    fun `interface json 缺失拒绝加载`() {
        val result = ProjectLoader(MapProjectSource(emptyMap())).load()
        assertTrue("应为 Failure: $result", result is ProjectLoadResult.Failure)
    }

    @Test
    fun `根文件自身可声明 task 与 preset`() {
        val ready = ProjectLoader(
            MapProjectSource(
                mapOf(
                    "interface.json" to """
                        {
                            "interface_version": 2,
                            "name": "t",
                            "task": [{"name":"T1","entry":"E1"}],
                            "preset": [{"name":"P1","task":[{"name":"T1"}]}]
                        }
                    """.trimIndent(),
                ),
            ),
        ).load() as ProjectLoadResult.Ready
        assertEquals("T1", ready.definition.tasks.single().name)
        assertEquals("P1", ready.definition.templates.single().name)
    }

    @Test
    fun `模板任务 label 加载期由任务定义物化且缺失定义回落 taskName`() {
        val ready = ProjectLoader(
            MapProjectSource(
                mapOf(
                    "interface.json" to """
                        {
                            "interface_version": 2,
                            "name": "t",
                            "task": [{"name":"T1","entry":"E1","label":"任务一"}],
                            "preset": [{"name":"P1","task":[{"name":"T1"},{"name":"Nope"}]}]
                        }
                    """.trimIndent(),
                ),
            ),
        ).load() as ProjectLoadResult.Ready
        val template = ready.definition.templates.single()
        assertEquals(listOf("任务一", "Nope"), template.tasks.map { it.label })
    }

    @Test
    fun `import 分片按声明顺序加载且缺失分片降级 warning`() {
        val ready = ProjectLoader(
            MapProjectSource(
                mapOf(
                    "interface.json" to piRoot("tasks/b.json", "tasks/missing.json", "tasks/a.json"),
                    "tasks/a.json" to """{"task":[{"name":"TA","entry":"E"}]}""",
                    "tasks/b.json" to """{"task":[{"name":"TB","entry":"E"}]}""",
                ),
            ),
        ).load() as ProjectLoadResult.Ready
        // 顺序 = import 声明序（b 在 a 前），不是字典序
        assertEquals(listOf("TB", "TA"), ready.definition.tasks.map { it.name })
        assertTrue(
            ready.diagnostics.any {
                "tasks/missing.json" == it.source &&
                    it.severity == DiagnosticSeverity.Warning &&
                    it.message is DiagnosticMessage.ImportReadFailed
            },
        )
        assertTrue(ready.diagnostics.none { it.severity == DiagnosticSeverity.Error })
    }

    @Test
    fun `无任何任务时仍 Ready 并记 warning`() {
        val ready = ProjectLoader(
            MapProjectSource(mapOf("interface.json" to """{"interface_version":2,"name":"t"}""")),
        ).load()
        assertTrue("应为 Ready: $ready", ready is ProjectLoadResult.Ready)
        val diagnostics = (ready as ProjectLoadResult.Ready).diagnostics
        assertTrue(
            diagnostics.any {
                it.message == DiagnosticMessage.ProjectHasNoTasks &&
                    it.severity == DiagnosticSeverity.Warning
            },
        )
    }

    @Test
    fun `default_check 与 check 双读且规范键优先`() {
        val ready = ProjectLoader(
            MapProjectSource(
                mapOf(
                    "interface.json" to """
                        {
                            "interface_version": 2,
                            "name": "t",
                            "task": [
                                {"name":"T1","entry":"E","default_check":true},
                                {"name":"T2","entry":"E","check":true},
                                {"name":"T3","entry":"E","default_check":false,"check":true},
                                {"name":"T4","entry":"E"}
                            ]
                        }
                    """.trimIndent(),
                ),
            ),
        ).load() as ProjectLoadResult.Ready
        val byName = ready.definition.tasks.associateBy { it.name }
        assertEquals(true, byName.getValue("T1").defaultCheck)
        assertEquals(true, byName.getValue("T2").defaultCheck)
        // 规范键 default_check 优先于 legacy check
        assertEquals(false, byName.getValue("T3").defaultCheck)
        assertEquals(false, byName.getValue("T4").defaultCheck)
    }

    @Test
    fun `task label 缺省回落 name`() {
        val ready = ProjectLoader(
            MapProjectSource(
                mapOf(
                    "interface.json" to """
                        {
                            "interface_version": 2,
                            "name": "t",
                            "task": [
                                {"name":"T1","entry":"E","label":"显示名"},
                                {"name":"T2","entry":"E"}
                            ]
                        }
                    """.trimIndent(),
                ),
            ),
        ).load() as ProjectLoadResult.Ready
        val byName = ready.definition.tasks.associateBy { it.name }
        assertEquals("显示名", byName.getValue("T1").label)
        assertEquals("T2", byName.getValue("T2").label)
    }
}
