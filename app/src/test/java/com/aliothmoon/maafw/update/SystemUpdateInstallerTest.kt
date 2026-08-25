package com.aliothmoon.maafw.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.aliothmoon.maafw.MaaDispatchers
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.unmockkObject
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
class SystemUpdateInstallerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()
    private val context = mockk<Context>(relaxed = true)
    private var fileProviderMocked = false

    @Before
    fun setUp() {
        mockkObject(MaaDispatchers)
        every { MaaDispatchers.IO } returns dispatcher
        every { context.packageName } returns "com.example.app"
    }

    @After
    fun tearDown() {
        unmockkObject(MaaDispatchers)
        if (fileProviderMocked) unmockkStatic(FileProvider::class)
    }

    @Test
    fun `starts system installer with provider uri`() = runTest(dispatcher) {
        val directory = temp.newFolder("updates")
        val apk = apk(directory)
        val uri = mockk<Uri>()
        mockkStatic(FileProvider::class)
        fileProviderMocked = true
        every {
            FileProvider.getUriForFile(context, "com.example.app.fileprovider", apk.canonicalFile)
        } returns uri

        val result = SystemUpdateInstaller(context, directory).install(downloadedUpdate(apk))

        assertEquals(UpdateInstallResult.InstallerStarted, result)
        verify { context.startActivity(any()) }
    }

    @Test
    fun `rejects apk outside update directory`() = runTest(dispatcher) {
        val updates = temp.newFolder("updates")
        val apk = apk(temp.newFolder("other"))

        val result = SystemUpdateInstaller(context, updates).install(downloadedUpdate(apk))

        assertEquals(UpdateInstallFailure.FILE_INVALID, (result as UpdateInstallResult.Failed).reason)
        verify { context wasNot Called }
    }

    @Test
    fun `rejects empty or non apk files`() = runTest(dispatcher) {
        val directory = temp.newFolder("updates")
        val emptyApk = File(directory, "empty.apk").apply { createNewFile() }
        val text = File(directory, "update.txt").apply { writeText("apk") }
        val installer = SystemUpdateInstaller(context, directory)

        val emptyResult = installer.install(downloadedUpdate(emptyApk))
        val textResult = installer.install(downloadedUpdate(text))

        assertEquals(UpdateInstallFailure.FILE_INVALID, (emptyResult as UpdateInstallResult.Failed).reason)
        assertEquals(UpdateInstallFailure.FILE_INVALID, (textResult as UpdateInstallResult.Failed).reason)
        verify { context wasNot Called }
    }

    @Test
    fun `missing installer is a failure`() = runTest(dispatcher) {
        val directory = temp.newFolder("updates")
        val apk = apk(directory)
        every { context.startActivity(any()) } throws ActivityNotFoundException("not found")
        mockkStatic(FileProvider::class)
        fileProviderMocked = true
        every {
            FileProvider.getUriForFile(any(), any(), any())
        } returns mockk<Uri>()

        val result = SystemUpdateInstaller(context, directory).install(downloadedUpdate(apk))

        assertEquals(UpdateInstallFailure.INSTALLER_NOT_FOUND, (result as UpdateInstallResult.Failed).reason)
    }

    private fun apk(directory: File): File =
        File(directory, "maafw-1.2.3.apk").apply { writeText("apk") }

    private fun downloadedUpdate(file: File) = DownloadedUpdate(
        source = UpdateSource.GITHUB,
        version = "1.2.3",
        file = file,
        sha256 = "0".repeat(64),
    )
}
