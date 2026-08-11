package com.aliothmoon.maafw.project

import com.aliothmoon.maafw.constant.AppFiles
import com.aliothmoon.maafw.constant.AppPaths
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream

/** 内存 PiPackage；清单由构建期产出，这里直接给 */
private class MapPiPackage(private val files: Map<String, String>) : PiPackage {

    @Volatile
    var openCount = 0
        private set

    override fun manifest(): List<String> = files.keys.sorted()

    @Synchronized
    override fun open(path: String): InputStream {
        val content = files[path] ?: throw FileNotFoundException(path)
        openCount++
        return content.byteInputStream()
    }
}

class PiInstallerTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Before
    fun mockAppPaths() {
        mockkObject(AppPaths)
    }

    @After
    fun unmockAppPaths() {
        unmockkObject(AppPaths)
    }

    private val files = mapOf(
        "interface.json" to """{"interface_version":2}""",
        "tasks/a.json" to "{}",
        "resource/base/pipeline/x.json" to "{}",
    )

    private fun installer(base: File, pkg: PiPackage, fingerprint: String): PiInstaller {
        every { AppPaths.externalRoot } returns base
        return PiInstaller(pkg, fingerprint)
    }

    @Test
    fun `首次解包产出完整目录树并写下标记`() {
        val base = temp.newFolder("external")
        val root = installer(base, MapPiPackage(files), "fp1").ensureInstalled()

        assertEquals(AppFiles.PI_DIR, root.name)
        assertEquals("""{"interface_version":2}""", File(root, "interface.json").readText())
        assertTrue(File(root, "tasks/a.json").isFile)
        assertTrue(File(root, "resource/base/pipeline/x.json").isFile)
        assertEquals("fp1", File(base, PiInstaller.PI_MARKER_NAME).readText())
        assertTrue("外部私有目录要挡住媒体扫描", File(base, ".nomedia").isFile)
    }

    @Test
    fun `标记与指纹一致时复用已解包目录`() {
        val base = temp.newFolder("external")
        val pkg = MapPiPackage(files)

        installer(base, pkg, "fp1").ensureInstalled()
        val afterFirst = pkg.openCount
        installer(base, pkg, "fp1").ensureInstalled()

        assertEquals("指纹未变不应重复解包", afterFirst, pkg.openCount)
    }

    @Test
    fun `指纹变化时整体重解并清掉旧内容`() {
        val base = temp.newFolder("external")
        installer(base, MapPiPackage(files), "fp1").ensureInstalled()

        val updated = files - "tasks/a.json" + ("tasks/b.json" to "{}")
        val root = installer(base, MapPiPackage(updated), "fp2").ensureInstalled()

        assertTrue(File(root, "tasks/b.json").isFile)
        assertFalse("旧版本才有的条目不该残留", File(root, "tasks/a.json").exists())
        assertEquals("fp2", File(base, PiInstaller.PI_MARKER_NAME).readText())
    }

    /** 标记是提交点：内容在但标记缺失，说明上次解包没走完 */
    @Test
    fun `标记缺失时重解`() {
        val base = temp.newFolder("external")
        val pkg = MapPiPackage(files)
        installer(base, pkg, "fp1").ensureInstalled()
        val afterFirst = pkg.openCount

        File(base, PiInstaller.PI_MARKER_NAME).delete()
        installer(base, pkg, "fp1").ensureInstalled()

        assertTrue("标记缺失应触发重解", pkg.openCount > afterFirst)
    }

    /** 解包不完整比解包失败更难查，任一条目失败即整体失败且不留标记 */
    @Test
    fun `条目读取失败时整体失败且不写标记`() {
        val base = temp.newFolder("external")
        val broken = object : PiPackage {
            override fun manifest(): List<String> = listOf("interface.json", "tasks/a.json")

            override fun open(path: String): InputStream =
                if (path == "interface.json") "{}".byteInputStream() else throw FileNotFoundException(path)
        }

        every { AppPaths.externalRoot } returns base
        assertThrows(Exception::class.java) {
            PiInstaller(broken, "fp1").ensureInstalled()
        }
        assertFalse(File(base, PiInstaller.PI_MARKER_NAME).exists())
    }

    @Test
    fun `空清单不报错`() {
        val base = temp.newFolder("external")
        val root = installer(base, MapPiPackage(emptyMap()), "fp1").ensureInstalled()

        assertTrue(root.isDirectory)
        assertEquals("fp1", File(base, PiInstaller.PI_MARKER_NAME).readText())
    }
}
