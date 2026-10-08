package com.aliothmoon.maafw.remote.internal

import com.aliothmoon.maafw.log.LogExportCollector
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory

class MaafwLogRetentionTest {

    private lateinit var base: File

    /** 固定「现在」：用真实时钟的话跨天跑测试会飘 */
    private val now = 1_800_000_000_000L
    private val hour = 60L * 60 * 1000
    private val day = 24 * hour

    @Before
    fun setUp() {
        base = createTempDirectory("maafw-retention").toFile()
    }

    @After
    fun tearDown() {
        base.deleteRecursively()
    }

    private fun entry(name: String, ageDays: Long, size: Long = 1) = MaafwLogRetention.Entry(File(name), now - ageDays * day, size)

    private fun write(path: String, ageDays: Long = 0): File =
        File(base, path).apply {
            parentFile?.mkdirs()
            writeText("x")
            setLastModified(now - ageDays * day)
        }

    private fun backup(dir: String, index: Int, ageDays: Long): File =
        write("$dir/maafw.bak.2026.10.0$index-10.00.00.000.log", ageDays)

    private fun prune(include: List<String> = listOf("debug/**/*.log")): Int =
        MaafwLogRetention.prune(File(base, "log"), LogExportCollector.PiLogs(File(base, "pi"), include), now)

    @Test
    fun `the newest few stay however old they are`() {
        val entries = (1..5).map { entry("b$it", ageDays = 10L + it) }

        val doomed = MaafwLogRetention.selectExpired(entries, now, maxTotalBytes = Long.MAX_VALUE)

        assertEquals(listOf(File("b5"), File("b4")), doomed)
    }

    @Test
    fun `older ones go past the day window`() {
        val entries = listOf(entry("old", 5), entry("edge", 3), entry("a", 2), entry("b", 1), entry("c", 0))

        val doomed = MaafwLogRetention.selectExpired(entries, now, maxTotalBytes = Long.MAX_VALUE)

        assertEquals(listOf(File("old")), doomed)
    }

    /** 总量把留着的也算进去 */
    @Test
    fun `over budget drops oldest first but never the kept ones`() {
        // 都在天数窗口内，只看总量；按小时错开，排序才确定
        val entries = listOf("a", "b", "c", "d", "e").mapIndexed { i, name ->
            MaafwLogRetention.Entry(File(name), now - (5 - i) * hour, 40)
        }

        assertEquals(listOf(File("a")), MaafwLogRetention.selectExpired(entries.shuffled(), now, maxTotalBytes = 160))
        assertEquals(listOf(File("a"), File("b")), MaafwLogRetention.selectExpired(entries, now, maxTotalBytes = 10))
    }

    @Test
    fun `only framework backups next to a live maafw log are deleted`() {
        val current = write("log/maafw.log")
        val keep = (0..2).map { backup("log", it, ageDays = it.toLong()) }
        val stale = (3..5).map { backup("log", it, ageDays = 4L + it) }
        val decoys = listOf(
            write("log/maafw.bak.manual-copy.log", ageDays = 30),
            write("log/app.log", ageDays = 30),
            write("log/maafw.bak.2026.10.01-10.00.00.000.log.txt", ageDays = 30),
            write("log/on_error/maafw.bak.2026.10.01-10.00.00.000.log", ageDays = 30),
        )

        assertEquals(3, prune())

        assertTrue(current.exists())
        keep.forEach { assertTrue(it.name, it.exists()) }
        stale.forEach { assertFalse(it.name, it.exists()) }
        decoys.forEach { assertTrue(it.path, it.exists()) }
    }

    @Test
    fun `agent dirs are found through the profile include`() {
        write("pi/debug/maafw.log")
        val agentStale = (0..4).map { backup("pi/debug", it, ageDays = 4L + it) }
        // 没有 maafw.log 的目录不是框架的日志目录，不动
        val orphan = (0..4).map { backup("pi/debug/cpp-algo/debug", it, ageDays = 4L + it) }
        // 配方没点名的目录不走
        write("pi/resource/maafw.log")
        val outside = (0..4).map { backup("pi/resource", it, ageDays = 4L + it) }

        assertEquals(2, prune())

        assertEquals(3, agentStale.count { it.exists() })
        orphan.forEach { assertTrue(it.path, it.exists()) }
        outside.forEach { assertTrue(it.path, it.exists()) }
    }

    @Test
    fun `an empty include leaves agent logs alone`() {
        write("pi/debug/maafw.log")
        val backups = (0..4).map { backup("pi/debug", it, ageDays = 10) }

        assertEquals(0, prune(include = emptyList()))
        backups.forEach { assertTrue(it.exists()) }
    }

    @Test
    fun `a symlinked backup is not followed`() {
        write("log/maafw.log")
        val target = write("elsewhere/precious.log", ageDays = 30)
        (0..2).forEach { backup("log", it, ageDays = 0) }
        val link = File(base, "log/maafw.bak.2026.09.01-10.00.00.000.log")
        val linked = runCatching { Files.createSymbolicLink(link.toPath(), target.toPath()) }.isSuccess
        assumeTrue("symlinks unsupported here", linked)

        assertEquals(0, prune())
        assertTrue(target.exists())
    }

    @Test
    fun `missing dirs are not an error`() {
        assertEquals(0, MaafwLogRetention.prune(null, null, now))
        assertEquals(0, prune())
    }
}
