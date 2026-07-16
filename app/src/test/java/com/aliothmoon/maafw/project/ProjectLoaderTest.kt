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
