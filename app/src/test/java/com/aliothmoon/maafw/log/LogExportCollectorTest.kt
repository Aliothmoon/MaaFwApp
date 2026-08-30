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
        write("log/manual/manual_a.png", ageDays = 1)
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
}
