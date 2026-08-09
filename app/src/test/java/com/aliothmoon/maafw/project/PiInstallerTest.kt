package com.aliothmoon.maafw.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.FileNotFoundException
import java.io.File
import java.io.InputStream

/** 内存 PiPackage，语义对齐 AssetManager：目录返回子项，文件返回空 */
private class MapPiPackage(private val files: Map<String, String>) : PiPackage {

    var openCount = 0
        private set

    override fun list(path: String): List<String> {
        val prefix = if (path.isEmpty()) "" else "$path/"
        return files.keys
            .filter { it.startsWith(prefix) && it != path }
            .map { it.removePrefix(prefix).substringBefore('/') }
            .distinct()
            .sorted()
    }

    override fun open(path: String): InputStream {
        val content = files[path] ?: throw FileNotFoundException(path)
        openCount++
        return content.byteInputStream()
    }
}

class PiInstallerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val files = mapOf(
        "interface.json" to """{"interface_version":2}""",
        "tasks/a.json" to "{}",
        "resource/base/pipeline/x.json" to "{}",
    )

    @Test
    fun `首次解包产出完整目录树`() {
        val root = PiInstaller(MapPiPackage(files), temp.newFolder("pi"), "fp1").ensureInstalled()

        assertEquals("fp1", root.name)
        assertEquals("""{"interface_version":2}""", File(root, "interface.json").readText())
        assertTrue(File(root, "tasks/a.json").isFile)
        assertTrue(File(root, "resource/base/pipeline/x.json").isFile)
    }

    @Test
    fun `指纹未变时复用已解包目录`() {
        val pkg = MapPiPackage(files)
        val installer = PiInstaller(pkg, temp.newFolder("pi"), "fp1")

        installer.ensureInstalled()
        val afterFirst = pkg.openCount
        installer.ensureInstalled()

        assertEquals("指纹未变不应重复解包", afterFirst, pkg.openCount)
    }

    @Test
    fun `指纹变化时重解包并清理旧版本`() {
        val base = temp.newFolder("pi")
        PiInstaller(MapPiPackage(files), base, "fp1").ensureInstalled()

        val updated = files + ("tasks/b.json" to "{}")
        val root = PiInstaller(MapPiPackage(updated), base, "fp2").ensureInstalled()

        assertEquals("fp2", root.name)
        assertTrue(File(root, "tasks/b.json").isFile)
        assertEquals(listOf("fp2"), base.listFiles().orEmpty().map { it.name })
    }

    /** assets 不区分文件与空目录，打不开的条目跳过而不是让整次解包失败 */
    @Test
    fun `打不开的条目被跳过`() {
        val pkg = object : PiPackage {
            override fun list(path: String): List<String> =
                if (path.isEmpty()) listOf("empty", "interface.json") else emptyList()

            override fun open(path: String): InputStream =
                if (path == "interface.json") "{}".byteInputStream() else throw FileNotFoundException(path)
        }

        val root = PiInstaller(pkg, temp.newFolder("pi"), "fp1").ensureInstalled()

        assertTrue(File(root, "interface.json").isFile)
        assertFalse("空目录条目不该产出文件", File(root, "empty").isFile)
    }
}
