package com.aliothmoon.maafw.log

import android.content.Context
import kotlinx.coroutines.CancellationException
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
import org.junit.Assert.assertNull
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
            assertEquals(
                listOf(
                    "device_info.txt",
                    "settings_snapshot.json",
                    "pi_config_snapshot.json",
                ),
                archive.entries().toList().map { it.name },
            )
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
            assertEquals(
                listOf(
                    "device_info.txt",
                    "settings_snapshot.json",
                    "pi_config_snapshot.json",
                    "log/app.log",
                ),
                names,
            )
            assertEquals("device snapshot", archive.getInputStream(archive.getEntry("device_info.txt")).readBytes().decodeToString())
            assertEquals("app log", archive.getInputStream(archive.getEntry("log/app.log")).readBytes().decodeToString())
        }
        assertTrue(log.exists())
    }

    @Test
    fun `export fails instead of silently omitting snapshots`() = runTest {
        val zip = service(settingsSnapshot = { throw IllegalStateException("settings unavailable") }).exportZip()

        assertNull(zip)
        assertEquals(emptyList<File>(), File(base, "log/export").listFiles()?.toList().orEmpty())
    }

    @Test
    fun `export cancellation propagates to caller`() = runTest {
        val service = service(settingsSnapshot = { throw CancellationException("export canceled") })

        val result = runCatching { service.exportZip() }

        assertTrue(result.exceptionOrNull() is CancellationException)
        assertEquals(emptyList<File>(), File(base, "log/export").listFiles()?.toList().orEmpty())
    }

    private fun service(
        settingsSnapshot: suspend () -> String = { """{"snapshotVersion":1}""" },
        piConfigSnapshot: suspend () -> String = { """{"snapshotVersion":1}""" },
    ) = LogExportService(
        context = mockk<Context>(),
        baseDir = { base },
        roots = { listOf(File(base, "log"), File(base, "debug")) },
        debugMode = { false },
        deviceInfo = { "device snapshot" },
        settingsSnapshot = settingsSnapshot,
        piConfigSnapshot = piConfigSnapshot,
    )
}
