package com.aliothmoon.maafw.project

import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.i18n.isResource
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileNotFoundException

class ProjectTextFileTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun write(root: File, path: String, body: String) {
        File(root, path).apply {
            parentFile?.mkdirs()
            writeText(body, Charsets.UTF_8)
        }
    }

    private fun load(root: File, declarations: List<String>): ProjectLoadResult.Ready {
        val welcome = JsonArray(declarations.map(::JsonPrimitive))
        write(root, "interface.json", """{
            "interface_version": 2,
            "welcome": $welcome,
            "task": [{"name":"T1","entry":"E1"}]
        }""".trimIndent())
        val result = ProjectLoader(DirectoryProjectSource(root)).load()
        assertTrue("加载应成功: $result", result is ProjectLoadResult.Ready)
        return result as ProjectLoadResult.Ready
    }

    @Test
    fun `多条中文公告从实际文件按序读取并支持空格与长路径`() {
        val root = temp.newFolder("pi")
        val paths = listOf(
            "announcement/1.简介.md",
            "announcement/2.模拟器多开.md",
            "announcement/3.问题上报.md",
            "announcement/4.任务介绍.md",
            "announcement/使用 说明.md",
            "announcement/${"long-directory/".repeat(7)}说明.md",
            "LICENSE",
        )
        val bodies = paths.indices.map { "# 公告 $it\n正文" }
        paths.zip(bodies).forEach { (path, body) -> write(root, path, body) }

        val ready = load(root, paths)

        assertEquals(bodies, ready.definition.metadata.welcome)
        assertTrue(ready.diagnostics.none { it.message.isResource(R.string.diagnostic_description_read_failed) })
    }

    @Test
    fun `候选无文件或指向目录时保留正文 URL 和多行内容不读取`() {
        val root = temp.newFolder("pi")
        File(root, "announcement/目录.md").mkdirs()
        val declarations = listOf(
            "筛选结束后保存到工作目录下的 EssencePlan.html",
            "A/B",
            "announcement/不存在.md",
            "announcement/目录.md",
            "https://example.com/公告.md",
            "# 标题\nannouncement/简介.md",
            "<span>announcement/简介.md</span>",
        )

        val ready = load(root, declarations)

        assertEquals(declarations, ready.definition.metadata.welcome)
        assertTrue(ready.diagnostics.none { it.message.isResource(R.string.diagnostic_description_read_failed) })
    }

    @Test
    fun `显式路径缺失会警告 规范化后仍在项目内的路径可以读取`() {
        val root = temp.newFolder("pi")
        write(root, "announcement/简介.md", "# 简介")

        val ready = load(root, listOf(
            "./announcement/不存在.md",
            "announcement/../announcement/简介.md",
            ".\\announcement\\简介.md",
        ))

        assertEquals(listOf("./announcement/不存在.md", "# 简介", "# 简介"), ready.definition.metadata.welcome)
        assertEquals(1, ready.diagnostics.count { it.message.isResource(R.string.diagnostic_description_read_failed) })
    }

    @Test
    fun `拒绝读取项目外文件并保留原始声明`() {
        val root = temp.newFolder("pi")
        val sibling = temp.newFolder("pi-other")
        write(sibling, "secret.md", "不应展示的内容")
        val relative = "../pi-other/secret.md"
        val absolute = File(sibling, "secret.md").absolutePath
        val ready = load(root, listOf(relative, absolute))

        assertEquals(listOf(relative, absolute), ready.definition.metadata.welcome)
        assertEquals(1, ready.diagnostics.count { it.message.isResource(R.string.diagnostic_description_read_failed) })
        val source = DirectoryProjectSource(root)
        assertThrows(IllegalArgumentException::class.java) { source.read(relative) }
        assertThrows(IllegalArgumentException::class.java) { source.read(absolute) }
        assertThrows(IllegalArgumentException::class.java) { source.read("C:/outside.md") }
    }

    @Test
    fun `项目目录本身不能作为正文文件读取`() {
        val source = DirectoryProjectSource(temp.newFolder("pi"))
        assertThrows(FileNotFoundException::class.java) { source.read("./") }
    }
}
