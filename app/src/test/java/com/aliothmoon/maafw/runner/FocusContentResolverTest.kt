package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.project.DirectoryProjectSource
import com.aliothmoon.maafw.project.ProjectSource
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class FocusContentResolverTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `focus 读取中文空格路径与空文件 未命中则回退正文`() = runTest {
        val root = temp.newFolder("pi")
        File(root, "使用 说明.md").writeText("# 说明")
        File(root, "empty.md").writeText("")
        val resolver = PrivilegedFocusContentResolver(DirectoryProjectSource(root), mockk())

        assertEquals("# 说明", resolver.resolve("使用 说明.md"))
        assertEquals("", resolver.resolve("empty.md"))
        assertEquals("./missing.md", resolver.resolve("./missing.md"))
        assertEquals("../outside.md", resolver.resolve("../outside.md"))
    }

    @Test
    fun `focus 读取失败时保留原始正文`() = runTest {
        val source = mockk<ProjectSource>()
        every { source.tryReadText("intro.md") } returns Result.failure(IOException("read failed"))
        val resolver = PrivilegedFocusContentResolver(source, mockk())
        assertEquals("intro.md", resolver.resolve("intro.md"))
    }
}
