package com.aliothmoon.maafw.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectAnnouncementTest {

    private fun load(files: Map<String, String>): ProjectLoadResult.Ready =
        ProjectLoader(MapProjectSource(files)).load() as ProjectLoadResult.Ready

    @Test
    fun `目录公告按文件名排序且与 welcome 分离`() {
        val ready = load(
            mapOf(
                "interface.json" to
                    """{"interface_version":2,"name":"t","welcome":"# Welcome\nWelcome body"}""",
                "resource/announcement/02-second.md" to "# Second\nSecond body",
                "resource/announcement/01-first.MD" to "# First\nFirst body",
                "resource/announcement/ignored.txt" to "Ignored",
            ),
        )

        val announcements = ready.definition.announcements.items
        assertEquals(listOf("First", "Second"), announcements.map { it.title })
        assertEquals(listOf("First body", "Second body"), announcements.map { it.body })
        assertEquals("# Welcome\nWelcome body", ready.definition.metadata.welcome)
        assertEquals("resource/announcement/01-first.MD", announcements.first().sourcePath)
        assertTrue(ready.definition.announcements.fingerprint != null)
    }

    @Test
    fun `单行公告保留正文且空标题回退文件名`() {
        val ready = load(
            mapOf(
                "interface.json" to """{"interface_version":2,"name":"t"}""",
                "resource/announcement/news.md" to "#\nRelease notes",
                "resource/announcement/empty.md" to "   ",
            ),
        )

        val announcement = ready.definition.announcements.items.single()
        assertEquals("news", announcement.title)
        assertEquals("Release notes", announcement.body)
    }

    @Test
    fun `公告文件变化会更新整组指纹`() {
        fun fingerprint(body: String) = load(
            mapOf(
                "interface.json" to """{"interface_version":2,"name":"t","version":"1"}""",
                "resource/announcement/news.md" to body,
            ),
        ).definition.announcements.fingerprint

        assertNotEquals(fingerprint("# News\nOld"), fingerprint("# News\nNew"))
    }

    @Test
    fun `PI 版本变化不会改变独立公告指纹`() {
        fun fingerprint(version: String) = load(
            mapOf(
                "interface.json" to
                    """{"interface_version":2,"name":"t","version":"$version"}""",
                "resource/announcement/news.md" to "# News\nBody",
            ),
        ).definition.announcements.fingerprint

        assertEquals(fingerprint("1"), fingerprint("2"))
    }

    @Test
    fun `本地文件型 welcome 保持原有声明加版本指纹`() {
        fun fingerprint(body: String) = load(
            mapOf(
                "interface.json" to
                    """{"interface_version":2,"name":"t","version":"1","welcome":"WELCOME.md"}""",
                "WELCOME.md" to body,
            ),
        ).definition.metadata.welcomeFingerprint

        assertEquals(fingerprint("# Welcome\nOld"), fingerprint("# Welcome\nNew"))
    }

    @Test
    fun `announcement 目录不会成为 fallback 资源`() {
        val ready = load(
            mapOf(
                "interface.json" to """{"interface_version":2,"name":"t"}""",
                "resource/announcement/news.md" to "News",
                "resource/base/pipeline.json" to "{}",
            ),
        )

        assertEquals(listOf("base"), ready.definition.resources.map { it.name })
    }
}
