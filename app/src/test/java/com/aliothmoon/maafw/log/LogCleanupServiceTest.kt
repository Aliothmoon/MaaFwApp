package com.aliothmoon.maafw.log

import com.aliothmoon.maafw.MaaDispatchers
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class LogCleanupServiceTest {

    private lateinit var base: File
    private val writer = mockk<AppLogWriter>()

    @Before
    fun setUp() {
        base = createTempDirectory("log-cleanup").toFile()
        mockkObject(MaaDispatchers)
        every { MaaDispatchers.IO } returns Dispatchers.Unconfined
        every { writer.purge() } returns Job().apply { complete() }
    }

    @After
    fun tearDown() {
        unmockkObject(MaaDispatchers)
        base.deleteRecursively()
    }

    @Test
    fun `clearing removes both diagnostic roots after closing app logs`() = runBlocking {
        write("log/app.log")
        write("log/run/run_a.jsonl")
        write("log/focus/focus_a.png")
        write("debug/logcat/app/logcat_a.log")
        write("debug/root_launch_debug.log")
        write("pi/framework.so")
        val service = service()

        assertTrue(service.clearAll())

        assertFalse(File(base, "log").exists())
        assertFalse(File(base, "debug").exists())
        assertTrue(File(base, "pi/framework.so").exists())
        verify(exactly = 1) { writer.purge() }
    }

    @Test
    fun `missing roots are treated as cleared`() = runBlocking {
        val service = LogCleanupService(writer) {
            listOf(File(base, "log"), File(base, "debug"))
        }

        assertTrue(service.clearAll())
    }

    private fun service() = LogCleanupService(writer) {
        listOf(File(base, "log"), File(base, "debug"))
    }

    private fun write(path: String) {
        File(base, path).apply {
            parentFile?.mkdirs()
            writeText("diagnostic")
        }
    }
}
