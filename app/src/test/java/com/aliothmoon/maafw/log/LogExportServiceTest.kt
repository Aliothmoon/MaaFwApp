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
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
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

    @Test
    fun `unreadable log file is skipped without failing export`() = runTest {
        assumeTrue(
            Files.getFileStore(base.toPath())
                .supportsFileAttributeView(PosixFileAttributeView::class.java)
        )
        val readable = File(base, "log/app.log").apply {
            parentFile!!.mkdirs()
            writeText("app log")
        }
        val unreadable = File(base, "debug/logcat/service.log").apply {
            parentFile!!.mkdirs()
            writeText("privileged log")
        }

        try {
            Files.setPosixFilePermissions(unreadable.toPath(), setOf(PosixFilePermission.OWNER_WRITE))
            val zip = service().exportZip()

            assertNotNull(zip)
            ZipFile(zip).use { archive ->
                val names = archive.entries().toList().map { it.name }
                assertEquals(listOf("device_info.txt", "log/app.log", "export_skipped.txt"), names)
                assertEquals(
                    "app log",
                    archive.getInputStream(archive.getEntry("log/app.log")).readBytes().decodeToString()
                )
                assertTrue(
                    archive.getInputStream(archive.getEntry("export_skipped.txt"))
                        .readBytes()
                        .decodeToString()
                        .startsWith("debug/logcat/service.log:")
                )
            }
        } finally {
            Files.setPosixFilePermissions(
                unreadable.toPath(),
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
            )
        }
        assertTrue(readable.exists())
        assertTrue(unreadable.exists())
    }

    private fun service() = LogExportService(
        context = mockk<Context>(),
        baseDir = { base },
        roots = { listOf(File(base, "log"), File(base, "debug")) },
        debugMode = { false },
        deviceInfo = { "device snapshot" },
    )
}
