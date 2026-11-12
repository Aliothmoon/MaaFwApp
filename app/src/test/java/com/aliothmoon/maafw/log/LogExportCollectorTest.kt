package com.aliothmoon.maafw.log

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class LogExportCollectorTest {

    private lateinit var base: File

    /** 固定「现在」：用真实时钟的话跨天跑测试会飘 */
    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    @Before
    fun setUp() {
        base = createTempDirectory("log-export").toFile()
    }

    @After
    fun tearDown() {
        base.deleteRecursively()
    }

    private fun write(path: String, ageDays: Long = 0): File =
        File(base, path).apply {
            parentFile?.mkdirs()
            writeText("x")
            setLastModified(now - ageDays * day)
        }

    private fun collect(): List<String> =
        LogExportCollector.collect(listOf(File(base, "log"), File(base, "debug")), now)
            .map { it.relativeTo(base).invariantSeparatorsPath }

    /** 这几份自己就有大小或份数上限，不必再按时间筛 */
    @Test
    fun `capped files are collected no matter how old`() {
        write("log/app.log", ageDays = 400)
        write("log/maa.log", ageDays = 90)
        write("log/schedule-trigger.log", ageDays = 30)
        write("debug/root_launch_debug.log", ageDays = 60)

        assertEquals(4, collect().size)
    }

    /** 按次堆文件的目录只留近 7 天，否则一年后的导出包会有上千个文件 */
    @Test
    fun `rolling dirs drop anything past the window`() {
        write("log/run/run_a.jsonl", ageDays = 1)
        write("log/run/run_b.jsonl", ageDays = 30)
        write("log/crash/crash_a.txt", ageDays = 2)
        write("log/crash/crash_b.txt", ageDays = 8)
        write("log/focus/focus_0.png", ageDays = 100)
        write("log/manual/manual_b.png", ageDays = 8)
        write("debug/logcat/app/logcat_a.log", ageDays = 3)
        write("debug/logcat/app/logcat_b.log", ageDays = 9)

        val kept = collect()
        assertTrue("log/run/run_a.jsonl" in kept)
        assertTrue("log/crash/crash_a.txt" in kept)
        assertTrue("debug/logcat/app/logcat_a.log" in kept)
        assertFalse("log/run/run_b.jsonl" in kept)
        assertFalse("log/crash/crash_b.txt" in kept)
        assertFalse("log/focus/focus_0.png" in kept)
        assertFalse("log/manual/manual_b.png" in kept)
        assertFalse("debug/logcat/app/logcat_b.log" in kept)
    }

    /** 上一次的 zip 再打进来，导一次体积翻一倍 */
    @Test
    fun `previous exports are never packed again`() {
        write("log/export/maafw_logs_old.zip")
        write("log/app.log")

        assertEquals(listOf("log/app.log"), collect())
    }

    @Test
    fun `a missing root is not an error`() {
        write("log/app.log")

        // debug/ 压根没建过：没开过特权进程的设备就是这样
        assertEquals(listOf("log/app.log"), collect())
    }

    private fun collectPi(vararg include: String): List<String> =
        LogExportCollector.collect(emptyList(), now, LogExportCollector.PiLogs(File(base, "pi"), include.toList()))
            .map { it.relativeTo(base).invariantSeparatorsPath }

    /** agent 自己的日志：默认按 MaaFramework 的 debug/ 约定收，截图、录制这类非日志文件不带 */
    @Test
    fun `agent logs under the pi root are picked by glob`() {
        write("pi/debug/go-service.log")
        write("pi/debug/cpp-algo/debug/maafw.log")
        write("pi/debug/cpp-algo/debug/maafw.bak.2026.09.29-04.38.30.303.log")
        write("pi/debug/vision/draw_0.png")
        write("pi/debug/record/rec.jsonl")
        write("pi/resource/pipeline/a.json")

        assertEquals(
            setOf(
                "pi/debug/go-service.log",
                "pi/debug/cpp-algo/debug/maafw.log",
                "pi/debug/cpp-algo/debug/maafw.bak.2026.09.29-04.38.30.303.log",
            ),
            collectPi("debug/**/*.log").toSet(),
        )
    }

    /** 外壳管不了 agent 日志的轮转，一律只收近 7 天 */
    @Test
    fun `agent logs past the window are dropped`() {
        write("pi/debug/new.log", ageDays = 1)
        write("pi/debug/old.log", ageDays = 30)

        assertEquals(listOf("pi/debug/new.log"), collectPi("debug/**/*.log"))
    }

    @Test
    fun `an empty include or a missing pi root exports nothing from it`() {
        write("pi/debug/go-service.log")

        assertEquals(emptyList<String>(), collectPi())
        base.resolve("pi").deleteRecursively()
        assertEquals(emptyList<String>(), collectPi("debug/**/*.log"))
    }

    /** 备份日志、截图和按次堆的文件份数没有上限，交给导出按预算装 */
    @Test
    fun `unbounded files are budgeted newest first across dirs`() {
        val files = listOf(
            write("log/app.log"),
            write("log/maafw.log"),
            write("log/maafw.bak.2026.10.08-10.34.10.978.log", ageDays = 3),
            write("log/maafw.bak.2026.10.08-10.43.35.511.log", ageDays = 2),
            write("pi/debug/maafw.log"),
            write("pi/debug/maafw.bak.2026.10.07-16.39.02.842.log", ageDays = 1),
            write("log/on_error/a.png", ageDays = 1),
            write("log/on_error/b.png"),
            write("log/focus/c.jpg", ageDays = 5),
            write("log/run/run_a.jsonl", ageDays = 6),
        )

        val plan = LogExportCollector.plan(files)
        fun List<File>.names() = map { it.relativeTo(base).invariantSeparatorsPath }

        assertEquals(listOf("log/app.log", "log/maafw.log", "pi/debug/maafw.log"), plan.required.names())
        assertEquals(
            listOf(
                "log/on_error/b.png",
                "pi/debug/maafw.bak.2026.10.07-16.39.02.842.log",
                "log/maafw.bak.2026.10.08-10.43.35.511.log",
                "log/focus/c.jpg",
                "log/run/run_a.jsonl",
                "log/on_error/a.png",
                "log/maafw.bak.2026.10.08-10.34.10.978.log",
            ),
            plan.optional.names(),
        )
    }

    @Test
    fun `nested include prefixes list a file once`() {
        write("pi/debug/go-service.log")
        write("pi/debug/cpp-algo/maafw.log")
        write("pi/resource/notes.log")

        val found = LogExportCollector.piLogFiles(
            LogExportCollector.PiLogs(File(base, "pi"), listOf("debug/**/*.log", "debug/cpp-algo/*.log")),
        ).map { it.relativeTo(base).invariantSeparatorsPath }.toList()

        assertEquals(listOf("pi/debug/cpp-algo/maafw.log", "pi/debug/go-service.log"), found.sorted())
    }

    @Test
    fun `glob keeps star and question mark inside one directory`() {
        val star = LogExportCollector.globToRegex("logs/*.log")
        assertTrue(star.matches("logs/a.log"))
        assertFalse(star.matches("logs/sub/a.log"))

        assertTrue(LogExportCollector.globToRegex("logs/**").matches("logs/sub/a.log"))

        val question = LogExportCollector.globToRegex("a?.log")
        assertTrue(question.matches("ab.log"))
        assertFalse(question.matches("a/.log"))

        // 点号按字面量匹配
        assertFalse(LogExportCollector.globToRegex("a.log").matches("aXlog"))
    }
}
