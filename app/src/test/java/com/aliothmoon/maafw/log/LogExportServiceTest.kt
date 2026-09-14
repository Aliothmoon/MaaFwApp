package com.aliothmoon.maafw.log

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import com.aliothmoon.maafw.MaaDispatchers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile
import kotlin.io.path.createTempDirectory

class LogExportServiceTest {

    private lateinit var base: File

    @Before
    fun setUp() {
        base = createTempDirectory("log-export-service").toFile()
        mockkObject(MaaDispatchers)
        every { MaaDispatchers.IO } returns Dispatchers.Unconfined
    }

    @After
    fun tearDown() {
        unmockkObject(MaaDispatchers)
        base.deleteRecursively()
    }

    @Test
    fun `empty exports still contain device info`() = runTest {
        val zip = service().exportZip()

        assertNotNull(zip)
        ZipFile(zip).use { archive ->
            assertEquals(listOf("device_info.txt"), archive.entries().toList().map { it.name })
            assertEquals("device snapshot", archive.getInputStream(archive.getEntry("device_info.txt")).readBytes().decodeToString())
        }
    }

    @Test
    fun `log entries are packed and properties stay debug only`() = runTest {
        val log = File(base, "log/app.log").apply {
            parentFile!!.mkdirs()
            writeText("app log")
        }

        val zip = service().exportZip()

        assertNotNull(zip)
        ZipFile(zip).use { archive ->
            val names = archive.entries().toList().map { it.name }
            assertEquals(listOf("device_info.txt", "log/app.log"), names)
            assertEquals("device snapshot", archive.getInputStream(archive.getEntry("device_info.txt")).readBytes().decodeToString())
            assertEquals("app log", archive.getInputStream(archive.getEntry("log/app.log")).readBytes().decodeToString())
        }
        assertTrue(log.exists())
    }

    private fun service() = LogExportService(
        context = mockk<Context>(),
        baseDir = { base },
        roots = { listOf(File(base, "log"), File(base, "debug")) },
        debugMode = { false },
        deviceInfo = { "device snapshot" },
    )
}
