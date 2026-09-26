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
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    /** MaaFramework 会把替换后的 pipeline_override 原样写进 maafw.log，只能在导出这一步打码 */
    @Test
    fun `password plaintexts are masked in text logs only`() = runTest {
        File(base, "log/maafw.log").apply {
            parentFile!!.mkdirs()
            writeText("override={\"pin\":\"secret12\",\"code\":\"abc\"}\r\nsecret1234 again")
        }
        val image = byteArrayOf(0x73, 0x65, 0x63, 0x72, 0x65, 0x74, 0x31, 0x32)
        File(base, "debug/on_error/shot.png").apply {
            parentFile!!.mkdirs()
            writeBytes(image)
        }

        val zip = service(secrets = listOf("secret12", "secret1234", "abc")).exportZip()

        ZipFile(zip!!).use { archive ->
            assertEquals(
                "override={\"pin\":\"***\",\"code\":\"abc\"}\n*** again\n",
                archive.getInputStream(archive.getEntry("log/maafw.log")).readBytes().decodeToString(),
            )
            assertTrue(image.contentEquals(archive.getInputStream(archive.getEntry("debug/on_error/shot.png")).readBytes()))
        }
    }

    @Test
    fun `password plaintexts stay in debug mode text logs`() = runTest {
        File(base, "log/maafw.log").apply {
            parentFile!!.mkdirs()
            writeText("override={\"pin\":\"secret12\"}\n")
        }

        val zip = service(debugMode = true, secrets = listOf("secret12")).exportZip()

        ZipFile(zip!!).use { archive ->
            assertEquals(
                "override={\"pin\":\"secret12\"}\n",
                archive.getInputStream(archive.getEntry("log/maafw.log")).readBytes().decodeToString(),
            )
        }
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
                assertEquals(
                    listOf(
                        "device_info.txt",
                        "settings_snapshot.json",
                        "pi_config_snapshot.json",
                        "log/app.log",
                        "export_skipped.txt",
                    ),
                    names,
                )
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
        debugMode: Boolean = false,
        secrets: List<String> = emptyList(),
        settingsSnapshot: suspend () -> String = { """{"snapshotVersion":1}""" },
        piConfigSnapshot: suspend () -> String = { """{"snapshotVersion":1}""" },
    ) = LogExportService(
        context = mockk<Context>(),
        baseDir = { base },
        roots = { listOf(File(base, "log"), File(base, "debug")) },
        debugMode = { debugMode },
        deviceInfo = { "device snapshot" },
        settingsSnapshot = settingsSnapshot,
        piConfigSnapshot = piConfigSnapshot,
        secrets = { secrets },
    )
}
