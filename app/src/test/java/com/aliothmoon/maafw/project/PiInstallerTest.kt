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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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

    /** agent 以 PI 根为工作目录，它写下的记录不能跟着升级一起没了 */
    @Test
    fun `versionCode 变化时保留包外的根级条目`() {
        val base = temp.newFolder("external")
        val root = installer(base, MapPiPackage(files), 11).ensureInstalled()
        File(root, "debug/record").mkdirs()
        File(root, "debug/record/random_salt.txt").writeText("salt")
        File(root, "EssenceInventory.json").writeText("[]")

        installer(base, MapPiPackage(files), 12).ensureInstalled()

        assertEquals("salt", File(root, "debug/record/random_salt.txt").readText())
        assertEquals("[]", File(root, "EssenceInventory.json").readText())
    }

    @Test
    fun `归档解包同样保留包外的根级条目并替换包内的`() {
        val base = temp.newFolder("external")
        val root = installer(base, ZipPiPackage(files), 11).ensureInstalled()
        File(root, "debug").mkdirs()
        File(root, "debug/go-service.log").writeText("log")

        val updated = files - "tasks/a.json" + ("tasks/b.json" to "{}")
        installer(base, ZipPiPackage(updated), 12).ensureInstalled()

        assertEquals("log", File(root, "debug/go-service.log").readText())
        assertTrue(File(root, "tasks/b.json").isFile)
        assertFalse("旧版本才有的条目不该残留", File(root, "tasks/a.json").exists())
    }

    /** 新包里没有的根级条目不会被 unpack 碰到，得靠上一次记下的清单认出来 */
    @Test
    fun `旧包带过而新包不再带的根级条目被清掉`() {
        val base = temp.newFolder("external")
        val root = installer(base, MapPiPackage(files + ("resource_old/x.json" to "{}")), 11).ensureInstalled()

        installer(base, MapPiPackage(files), 12).ensureInstalled()

        assertFalse(File(root, "resource_old").exists())
        assertEquals(
            setOf("interface.json", "tasks", "resource"),
            File(base, PiInstaller.PI_ROOTS_NAME).readLines().toSet(),
        )
    }

    /** 与 MXU 全量更新同一口径：包带了同名的根级条目就整体替换，里面 agent 写的东西不留 */
    @Test
    fun `包内根级条目里 agent 写的文件随升级替换`() {
        val base = temp.newFolder("external")
        val withConfig = files + ("config/default.json" to "{}")
        val root = installer(base, MapPiPackage(withConfig), 11).ensureInstalled()
        File(root, "config/user.json").writeText("{}")

        installer(base, MapPiPackage(withConfig), 12).ensureInstalled()

        assertTrue(File(root, "config/default.json").isFile)
        assertFalse(File(root, "config/user.json").exists())
    }

    /** 清单是外部私有目录里的普通文件；被改坏也不能删到 PI 根外面 */
    @Test
    fun `根级条目清单里的越界行不认`() {
        val base = temp.newFolder("external")
        installer(base, MapPiPackage(files), 11).ensureInstalled()
        val outside = File(base, "keep.txt").apply { writeText("keep") }
        File(base, PiInstaller.PI_ROOTS_NAME).writeText("..\n../keep.txt\n.\n")

        installer(base, MapPiPackage(files), 12).ensureInstalled()

        assertEquals("keep", outside.readText())
    }

    /** 日志不随升级清空之后靠这条管住体积；口径跟导出一致，再早的本来也导不出去 */
    @Test
    fun `升级时清掉过期的 agent 日志而不动记录`() {
        val base = temp.newFolder("external")
        val now = 1_800_000_000_000L
        val stale = now - 8 * DAY_MS
        fun installerAt(versionCode: Int): PiInstaller {
            every { AppPaths.ROOT } returns base
            return PiInstaller(MapPiPackage(files), versionCode, logInclude = listOf("debug/**/*.log"), now = { now })
        }
        val root = installerAt(11).ensureInstalled()
        File(root, "debug/cpp-algo").mkdirs()
        File(root, "debug/record").mkdirs()
        fun fileAt(path: String, modified: Long) = File(root, path).apply {
            writeText(path)
            setLastModified(modified)
        }
        val oldLog = fileAt("debug/cpp-algo/maafw.bak.log", stale)
        val freshLog = fileAt("debug/go-service.log", now - DAY_MS)
        val oldRecord = fileAt("debug/record/IMS.json", stale)

        installerAt(12).ensureInstalled()

        assertFalse("超过 7 天的日志该清掉", oldLog.exists())
        assertTrue("近期日志留着，升级后出问题还能回看", freshLog.exists())
        assertTrue("记录不是日志，多旧都不碰", oldRecord.exists())
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

    /** 弹窗的进度条吃的就是这串回报；内存包按清单顺序 */
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

    /** 手动重来是恢复原样的手段：agent 写坏的记录只有这条路能清 */
    @Test
    fun `reinstall 在已就绪时连 agent 数据一起清掉`() {
        val base = temp.newFolder("external")
        val root = installer(base, MapPiPackage(files), 11).ensureInstalled()
        File(root, "debug/record").mkdirs()
        File(root, "debug/record/IMS.json").writeText("{}")

        installer(base, MapPiPackage(files), 11).reinstall()

        assertFalse(File(root, "debug").exists())
        assertTrue(File(root, "interface.json").isFile)
    }

    /** 升级解到一半失败后的「重试」是那次升级的延续，不是用户要清数据 */
    @Test
    fun `reinstall 在上次没走完时保留 agent 数据`() {
        val base = temp.newFolder("external")
        val root = installer(base, MapPiPackage(files), 11).ensureInstalled()
        File(root, "debug/record").mkdirs()
        File(root, "debug/record/IMS.json").writeText("{}")
        File(base, PiInstaller.PI_MARKER_NAME).delete()

        installer(base, MapPiPackage(files), 12).reinstall()

        assertEquals("{}", File(root, "debug/record/IMS.json").readText())
        assertEquals("12", File(base, PiInstaller.PI_MARKER_NAME).readText())
    }

    /** 读取路径拿的是这个；它不该顺带解包，未解包就得响 */
    @Test
    fun `installedDir 在未解包时抛出`() {
        val base = temp.newFolder("external")
        every { AppPaths.ROOT } returns base
        val pi = PiInstaller(MapPiPackage(files), 11)

        assertThrows(PiNotInstalledException::class.java) { pi.installedDir() }

        pi.ensureInstalled()
        assertEquals(AppFiles.PI_DIR, pi.installedDir().name)
    }

    @Test
    fun `仅有目录没有标记时 installedDir 仍抛`() {
        val base = temp.newFolder("external")
        every { AppPaths.ROOT } returns base
        File(base, AppFiles.PI_DIR).mkdirs()
        val pi = PiInstaller(MapPiPackage(files), 11)

        assertThrows(PiNotInstalledException::class.java) { pi.installedDir() }
    }

    @Test
    fun `归档解包保留下划线目录与 gz 文件名`() {
        val base = temp.newFolder("external")
        val files = mapOf(
            "interface.json" to "{}",
            "resource/pipeline/Common/__Private/AutoAltClick/Action.json" to "{}",
            "resource/model/map/navmesh/base.nav.gz" to "gz",
        )
        val root = installer(base, ZipPiPackage(files), 11).ensureInstalled()

        assertEquals("{}", File(root, "resource/pipeline/Common/__Private/AutoAltClick/Action.json").readText())
        assertEquals("gz", File(root, "resource/model/map/navmesh/base.nav.gz").readText())
    }

    @Test
    fun `归档解包按 zip 顺序写出且不看清单`() {
        val base = temp.newFolder("external")
        val files = mapOf(
            "interface.json" to "{}",
            "resource/base/model/ocr/keys.txt" to "keys",
        )
        val seen = CopyOnWriteArrayList<Triple<Int, Int, String>>()
        val root = installer(base, ZipPiPackage(files, manifest = listOf("ignored.json")), 11)
            .ensureInstalled { done, total, path -> seen += Triple(done, total, path) }

        assertEquals("{}", File(root, "interface.json").readText())
        assertEquals("keys", File(root, "resource/base/model/ocr/keys.txt").readText())
        assertFalse(File(root, "ignored.json").exists())
        assertTrue("归档扫 zip 时不知道总数", seen.all { it.second == 0 })
        assertEquals(files.keys, seen.map { it.third }.toSet())
    }

    @Test
    fun `空归档当无 PI 写出标记`() {
        val base = temp.newFolder("external")
        val root = installer(base, ZipPiPackage(emptyMap()), 11).ensureInstalled()

        assertTrue(root.isDirectory)
        assertEquals("11", File(base, PiInstaller.PI_MARKER_NAME).readText())
        assertFalse(File(root, "interface.json").exists())
    }

    @Test
    fun `有文件但缺 interface json 失败且不写标记`() {
        val base = temp.newFolder("external")
        every { AppPaths.ROOT } returns base
        assertThrows(PiUnpackException::class.java) {
            installer(base, ZipPiPackage(mapOf("tasks/a.json" to "{}")), 11).ensureInstalled()
        }
        assertFalse(File(base, PiInstaller.PI_MARKER_NAME).exists())
    }

    @Test
    fun `zip slip 条目失败且不写标记`() {
        val base = temp.newFolder("external")
        every { AppPaths.ROOT } returns base
        assertThrows(PiUnpackException::class.java) {
            installer(base, ZipPiPackage(mapOf("../evil" to "x", "interface.json" to "{}")), 11)
                .ensureInstalled()
        }
        assertFalse(File(base, PiInstaller.PI_MARKER_NAME).exists())
        assertFalse(File(base, "evil").exists())
    }

    @Test
    fun `文件名里的连续点不是 zip slip`() {
        val base = temp.newFolder("external")
        val files = mapOf(
            "interface.json" to "{}",
            "resource/foo..bar.json" to "ok",
        )
        val root = installer(base, ZipPiPackage(files), 11).ensureInstalled()
        assertEquals("ok", File(root, "resource/foo..bar.json").readText())
    }
}

private const val DAY_MS = 24L * 60 * 60 * 1000

private class ZipPiPackage(
    files: Map<String, String>,
    manifest: List<String> = files.keys.sorted(),
) : PiPackage {
    private val bytes: ByteArray = zipBytes(files)
    private val names = manifest

    override fun manifest(): List<String> = names

    override fun open(path: String): InputStream = error(path)

    override fun openArchive(): InputStream = bytes.inputStream()
}

private fun zipBytes(files: Map<String, String>): ByteArray =
    ByteArrayOutputStream().use { raw ->
        ZipOutputStream(raw).use { zip ->
            files.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        raw.toByteArray()
    }
