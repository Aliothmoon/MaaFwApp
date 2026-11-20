package com.aliothmoon.maafw.remote.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LogcatRetentionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val dayMs = 24L * 60 * 60 * 1000
    private val now = 100 * dayMs

    private fun entry(name: String, ageDays: Int, size: Long) =
        LogcatRetention.Entry(File(name), now - ageDays * dayMs, size)

    private fun expired(entries: List<LogcatRetention.Entry>, maxTotalBytes: Long = 100) =
        LogcatRetention.selectExpired(entries, now, keepDays = 7, maxTotalBytes = maxTotalBytes)
            .map { it.name }

    @Test
    fun dropsFilesPastKeepDays() {
        val entries = listOf(entry("old", 8, 1), entry("edge", 7, 1), entry("new", 0, 1))

        assertEquals(listOf("old"), expired(entries))
    }

    @Test
    fun overTotal_dropsOldestUntilUnderLimit() {
        val entries = listOf(
            entry("d1", 1, 40),
            entry("d3", 3, 40),
            entry("d2", 2, 40),
            entry("d0", 0, 40),
        )

        assertEquals(listOf("d3", "d2"), expired(entries))
    }

    @Test
    fun expiredFilesDoNotCountTowardTotal() {
        val entries = listOf(entry("ancient", 30, 1000), entry("a", 1, 50), entry("b", 0, 50))

        assertEquals(listOf("ancient"), expired(entries))
    }

    @Test
    fun underLimit_keepsEverything() {
        assertTrue(expired(listOf(entry("a", 1, 10), entry("b", 2, 10))).isEmpty())
    }

    @Test
    fun prune_walksBothProcessDirsAndDeletes() {
        val logcat = tempFolder.newFolder("logcat")
        val wall = System.currentTimeMillis()
        fun file(rel: String, ageDays: Int) = File(logcat, rel).apply {
            parentFile?.mkdirs()
            writeText("x")
            setLastModified(wall - ageDays * dayMs)
        }

        val oldCore = file("core/logcat_old.log", 30)
        val oldApp = file("app/logcat_old.log", 9)
        val recent = file("core/logcat_new.log", 1)

        assertEquals(2, LogcatRetention.prune(logcat, wall))
        assertFalse(oldCore.exists())
        assertFalse(oldApp.exists())
        assertTrue(recent.exists())
    }

    @Test
    fun prune_missingDirIsNoop() {
        assertEquals(0, LogcatRetention.prune(File(tempFolder.root, "nope")))
    }
}
