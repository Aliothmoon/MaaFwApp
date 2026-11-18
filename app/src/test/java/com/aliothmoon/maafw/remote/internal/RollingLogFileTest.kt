package com.aliothmoon.maafw.remote.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RollingLogFileTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun target() = File(tempFolder.root, "logcat_2026-10-03_03-36-19.log")
    private fun previous() = File(tempFolder.root, "logcat_2026-10-03_03-36-19.1.log")

    @Test
    fun underLimit_singleFile() {
        RollingLogFile(target(), maxSegmentBytes = 100).use {
            it.writeLine("aaaa")
            it.writeLine("bbbb")
        }

        assertEquals("aaaa\nbbbb\n", target().readText())
        assertFalse(previous().exists())
    }

    @Test
    fun overLimit_rollsAndKeepsLatestTwoSegments() {
        RollingLogFile(target(), maxSegmentBytes = 10).use {
            it.writeLine("1111")
            it.writeLine("2222")
            it.writeLine("3333")
            it.writeLine("4444")
            it.writeLine("5555")
        }

        assertEquals("3333\n4444\n", previous().readText())
        assertEquals("5555\n", target().readText())
        assertTrue(target().length() + previous().length() <= 20)
    }

    @Test
    fun lineLongerThanSegmentIsStillWritten() {
        RollingLogFile(target(), maxSegmentBytes = 4).use {
            it.writeLine("0123456789")
            it.writeLine("x")
        }

        assertEquals("0123456789\n", previous().readText())
        assertEquals("x\n", target().readText())
    }

    @Test
    fun appendsToExistingFile() {
        target().writeText("old\n")

        RollingLogFile(target(), maxSegmentBytes = 100).use { it.writeLine("new") }

        assertEquals("old\nnew\n", target().readText())
    }

    @Test
    fun flushMakesBufferedLinesVisible() {
        RollingLogFile(target(), maxSegmentBytes = 100).use {
            it.writeLine("tail")
            it.flush()
            assertEquals("tail\n", target().readText())
        }
    }
}
