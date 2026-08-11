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
import java.util.concurrent.CopyOnWriteArrayList

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

    private fun installer(base: File, pkg: PiPackage, versionCode: Int): PiInstaller {
        every { AppPaths.ROOT } returns base
        return PiInstaller(pkg, versionCode)
    }

    @Test
    fun `首次解包产出完整目录树并写下标记`() {
        val base = temp.newFolder("external")
        val root = installer(base, MapPiPackage(files), 11).ensureInstalled()

        assertEquals(AppFiles.PI_DIR, root.name)
        assertEquals("""{"interface_version":2}""", File(root, "interface.json").readText())
        assertTrue(File(root, "tasks/a.json").isFile)
        assertTrue(File(root, "resource/base/pipeline/x.json").isFile)
        assertEquals("11", File(base, PiInstaller.PI_MARKER_NAME).readText())
        assertTrue("外部私有目录要挡住媒体扫描", File(base, ".nomedia").isFile)
    }

    @Test
    fun `标记与 versionCode 一致时复用已解包目录`() {
        val base = temp.newFolder("external")
        val pkg = MapPiPackage(files)

        installer(base, pkg, 11).ensureInstalled()
        val afterFirst = pkg.openCount
        installer(base, pkg, 11).ensureInstalled()

        assertEquals("versionCode 未变不应重复解包", afterFirst, pkg.openCount)
    }

    @Test
    fun `versionCode 变化时整体重解并清掉旧内容`() {
        val base = temp.newFolder("external")
        installer(base, MapPiPackage(files), 11).ensureInstalled()

        val updated = files - "tasks/a.json" + ("tasks/b.json" to "{}")
        val root = installer(base, MapPiPackage(updated), 12).ensureInstalled()

        assertTrue(File(root, "tasks/b.json").isFile)
        assertFalse("旧版本才有的条目不该残留", File(root, "tasks/a.json").exists())
        assertEquals("12", File(base, PiInstaller.PI_MARKER_NAME).readText())
    }

    /** 标记是提交点：内容在但标记缺失，说明上次解包没走完 */
    @Test
    fun `标记缺失时重解`() {
        val base = temp.newFolder("external")
        val pkg = MapPiPackage(files)
        installer(base, pkg, 11).ensureInstalled()
        val afterFirst = pkg.openCount

        File(base, PiInstaller.PI_MARKER_NAME).delete()
        installer(base, pkg, 11).ensureInstalled()

        assertTrue("标记缺失应触发重解", pkg.openCount > afterFirst)
    }

    /** 按内容指纹判过期的旧包升上来时，两个标记并存会让人分不清哪个在生效 */
    @Test
    fun `重解时清掉旧版指纹标记`() {
        val base = temp.newFolder("external")
        val legacy = File(base, "pi.fingerprint").apply { writeText("0123abcd") }

        installer(base, MapPiPackage(files), 11).ensureInstalled()

        assertFalse(legacy.exists())
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

        every { AppPaths.ROOT } returns base
        assertThrows(Exception::class.java) {
            PiInstaller(broken, 11).ensureInstalled()
        }
        assertFalse(File(base, PiInstaller.PI_MARKER_NAME).exists())
    }

    @Test
    fun `空清单不报错`() {
        val base = temp.newFolder("external")
        val root = installer(base, MapPiPackage(emptyMap()), 11).ensureInstalled()

        assertTrue(root.isDirectory)
        assertEquals("11", File(base, PiInstaller.PI_MARKER_NAME).readText())
    }

    /** 弹窗的进度条吃的就是这串回报，条目顺序由线程池决定，只有计数是确定的 */
    @Test
    fun `解包逐条目回报进度`() {
        val base = temp.newFolder("external")
        val seen = CopyOnWriteArrayList<Triple<Int, Int, String>>()

        installer(base, MapPiPackage(files), 11).ensureInstalled { done, total, path ->
            seen += Triple(done, total, path)
        }

        assertEquals(files.size, seen.size)
        assertEquals((1..files.size).toList(), seen.map { it.first }.sorted())
        assertTrue("total 恒等于清单条目数", seen.all { it.second == files.size })
        assertEquals(files.keys, seen.map { it.third }.toSet())
    }

    @Test
    fun `标记一致时一条进度都不回报`() {
        val base = temp.newFolder("external")
        val pkg = MapPiPackage(files)
        installer(base, pkg, 11).ensureInstalled()

        var reported = 0
        installer(base, pkg, 11).ensureInstalled { _, _, _ -> reported++ }

        assertEquals(0, reported)
    }

    /** 设置页的手动重来：versionCode 没变也要真解一遍 */
    @Test
    fun `reinstall 不看标记`() {
        val base = temp.newFolder("external")
        val pkg = MapPiPackage(files)
        installer(base, pkg, 11).ensureInstalled()
        val afterFirst = pkg.openCount

        installer(base, pkg, 11).reinstall()

        assertEquals(afterFirst * 2, pkg.openCount)
        assertEquals("11", File(base, PiInstaller.PI_MARKER_NAME).readText())
    }

    /** 读取路径拿的是这个；它不该顺带解包，未解包就得响 */
    @Test
    fun `installedDir 在未解包时抛出`() {
        val base = temp.newFolder("external")
        every { AppPaths.ROOT } returns base
        val pi = PiInstaller(MapPiPackage(files), 11)

        assertThrows(IllegalStateException::class.java) { pi.installedDir() }

        pi.ensureInstalled()
        assertEquals(AppFiles.PI_DIR, pi.installedDir().name)
    }
}
