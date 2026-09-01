package com.aliothmoon.maafw

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class SystemApkInstallerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()
    private val context = mockk<Context>(relaxed = true)
    private lateinit var cacheDir: File
    private var fileProviderMocked = false

    @Before
    fun setUp() {
        cacheDir = temp.newFolder("cache")
        every { context.packageName } returns "com.example.app"
        every { context.cacheDir } returns cacheDir
    }

    @After
    fun tearDown() {
        if (fileProviderMocked) unmockkStatic(FileProvider::class)
    }

    @Test
    fun `starts system installer with provider uri`() = runTest(dispatcher) {
        val apk = apk(File(cacheDir, "updates").apply { mkdirs() })
        val uri = mockk<Uri>()
        mockkStatic(FileProvider::class)
        fileProviderMocked = true
        every {
            FileProvider.getUriForFile(context, "com.example.app.fileprovider", apk.canonicalFile)
        } returns uri

        val result = SystemApkInstaller(context).install(apk)

        assertEquals(SystemApkInstaller.Result.Started, result)
        verify { context.startActivity(any()) }
    }

    @Test
    fun `rejects apk outside the app cache`() = runTest(dispatcher) {
        val apk = apk(temp.newFolder("other"))

        val result = SystemApkInstaller(context).install(apk)

        assertEquals(
            SystemApkInstaller.Failure.FILE_INVALID,
            (result as SystemApkInstaller.Result.Failed).reason,
        )
        verify(exactly = 0) { context.startActivity(any()) }
    }

    @Test
    fun `rejects empty or non apk files`() = runTest(dispatcher) {
        val emptyApk = File(cacheDir, "empty.apk").apply { createNewFile() }
        val text = File(cacheDir, "update.txt").apply { writeText("apk") }
        val installer = SystemApkInstaller(context)

        val emptyResult = installer.install(emptyApk)
        val textResult = installer.install(text)

        assertEquals(
            SystemApkInstaller.Failure.FILE_INVALID,
            (emptyResult as SystemApkInstaller.Result.Failed).reason,
        )
        assertEquals(
            SystemApkInstaller.Failure.FILE_INVALID,
            (textResult as SystemApkInstaller.Result.Failed).reason,
        )
        verify(exactly = 0) { context.startActivity(any()) }
    }

    @Test
    fun `missing installer is a failure`() = runTest(dispatcher) {
        val apk = apk(File(cacheDir, "updates").apply { mkdirs() })
        every { context.startActivity(any()) } throws ActivityNotFoundException("not found")
        mockkStatic(FileProvider::class)
        fileProviderMocked = true
        every {
            FileProvider.getUriForFile(any(), any(), any())
        } returns mockk<Uri>()

        val result = SystemApkInstaller(context).install(apk)

        assertEquals(
            SystemApkInstaller.Failure.INSTALLER_NOT_FOUND,
            (result as SystemApkInstaller.Result.Failed).reason,
        )
    }

    private fun apk(directory: File): File =
        File(directory, "maafw-1.2.3.apk").apply { writeText("apk") }
}
