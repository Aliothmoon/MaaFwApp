package com.aliothmoon.maafw.settings

import com.aliothmoon.maafw.domain.ControllerDefinition
import com.aliothmoon.maafw.domain.ProjectDefinition
import com.aliothmoon.maafw.domain.ProjectMetadata
import com.aliothmoon.maafw.privileged.FakePermissionGateway
import com.aliothmoon.maafw.project.FakeProjectRepository
import com.aliothmoon.maafw.project.ProjectState
import com.aliothmoon.maafw.update.DownloadedUpdate
import com.aliothmoon.maafw.update.UpdateCheckApi
import com.aliothmoon.maafw.update.UpdateCheckRequest
import com.aliothmoon.maafw.update.UpdateCheckResult
import com.aliothmoon.maafw.update.UpdateChannel
import com.aliothmoon.maafw.update.UpdateDownloadApi
import com.aliothmoon.maafw.update.UpdateDownloadCredentials
import com.aliothmoon.maafw.update.UpdateDownloadResult
import com.aliothmoon.maafw.update.UpdateInstallApi
import com.aliothmoon.maafw.update.UpdateInstallResult
import com.aliothmoon.maafw.update.UpdateSource
import com.aliothmoon.maafw.notification.UpdateDownloadNotification
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `manual check keeps default sources and download resolves selected source`() = runTest {
        val mirrorUpdate = availableUpdate(source = UpdateSource.MIRROR_CHYAN)
        val githubUpdate = availableUpdate(
            source = UpdateSource.GITHUB,
            url = "https://github.com/app.apk",
        )
        val checkApi = RecordingUpdateCheckApi(mirrorUpdate, githubUpdate)
        val downloader = RecordingUpdateDownloader()
        val settings = FakeAppSettingsGateway()
        val viewModel = viewModel(checkApi, downloader, settings)

        settings.setUpdateChannel(UpdateChannel.BETA)
        settings.setUpdateDownloadSource(UpdateSource.GITHUB)
        settings.setGithubToken("github-token")
        settings.setMirrorChyanCdk("mirror-cdk")
        viewModel.onIntent(SettingsIntent.CheckUpdate)
        viewModel.onIntent(SettingsIntent.DownloadUpdate)

        assertEquals(2, checkApi.requests.size)
        val manualCheck = checkApi.requests[0]
        assertEquals(UpdateSource.MIRROR_CHYAN, manualCheck.preferredSource)
        assertEquals(UpdateSource.GITHUB, manualCheck.alternativeSource)
        assertEquals(UpdateChannel.BETA, manualCheck.channel)
        assertNull(manualCheck.githubToken)
        assertNull(manualCheck.mirrorChyanCdk)

        val downloadCheck = checkApi.requests[1]
        assertEquals(UpdateSource.GITHUB, downloadCheck.preferredSource)
        assertNull(downloadCheck.alternativeSource)
        assertEquals(UpdateChannel.BETA, downloadCheck.channel)
        assertEquals("github-token", downloadCheck.githubToken)
        assertNull(downloadCheck.mirrorChyanCdk)

        assertEquals(githubUpdate, downloader.update)
        assertEquals(UpdateDownloadCredentials(githubToken = "github-token"), downloader.credentials)
    }

    @Test
    fun `mirror download requires cdk`() = runTest {
        val checkApi = RecordingUpdateCheckApi(availableUpdate())
        val downloader = RecordingUpdateDownloader()
        val viewModel = viewModel(checkApi, downloader)

        viewModel.onIntent(SettingsIntent.CheckUpdate)
        viewModel.onIntent(SettingsIntent.DownloadUpdate)

        assertEquals(1, checkApi.requests.size)
        assertTrue(downloader.updates.isEmpty())
    }

    @Test
    fun `changing settings during check does not allow duplicate check`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val checkApi = GatedUpdateCheckApi(availableUpdate(), gate)
        val settings = FakeAppSettingsGateway()
        val viewModel = viewModel(checkApi = checkApi, settings = settings)

        viewModel.onIntent(SettingsIntent.CheckUpdate)
        settings.setGithubToken("github-token")
        viewModel.onIntent(SettingsIntent.CheckUpdate)

        assertEquals(1, checkApi.requests)
        gate.complete(Unit)
    }

    private fun viewModel(
        checkApi: UpdateCheckApi = RecordingUpdateCheckApi(availableUpdate()),
        downloader: UpdateDownloadApi = RecordingUpdateDownloader(),
        settings: AppSettingsGateway = FakeAppSettingsGateway(),
    ): SettingsViewModel {
        val definition = ProjectDefinition(
            name = "demo",
            version = "1",
            controller = ControllerDefinition(),
            resources = emptyList(),
            tasks = emptyList(),
            groups = emptyList(),
            options = emptyMap(),
            templates = emptyList(),
            metadata = ProjectMetadata(
                githubRepository = "owner/repo",
                mirrorChyanRid = "mirror-rid",
            ),
        )
        return SettingsViewModel(
            permissionGateway = FakePermissionGateway(),
            appSettings = settings,
            projectRepository = FakeProjectRepository(ProjectState.Ready(definition, emptyList())),
            updateCheckApi = checkApi,
            updateDownloader = downloader,
            updateInstaller = RecordingUpdateInstaller(),
            updateDownloadNotifier = NoopUpdateDownloadNotification(),
            currentVersion = "1.0.0",
            supportedAbis = listOf("arm64-v8a"),
        )
    }

    private fun availableUpdate(
        source: UpdateSource = UpdateSource.MIRROR_CHYAN,
        url: String = "https://mirror.example.com/app.apk",
    ) = UpdateCheckResult.UpdateAvailable(
        source = source,
        version = "2.0.0",
        downloadUrl = url,
        sha256 = "a".repeat(64),
        releaseNotesUrl = null,
        releaseNotes = null,
    )

    private class RecordingUpdateCheckApi(
        vararg results: UpdateCheckResult,
    ) : UpdateCheckApi {
        val requests = mutableListOf<UpdateCheckRequest>()
        private val pendingResults = ArrayDeque(results.toList())

        override suspend fun check(request: UpdateCheckRequest): UpdateCheckResult {
            requests += request
            return pendingResults.removeFirstOrNull() ?: error("No queued update result")
        }
    }

    private class GatedUpdateCheckApi(
        private val result: UpdateCheckResult,
        private val gate: CompletableDeferred<Unit>,
    ) : UpdateCheckApi {
        var requests = 0
            private set

        override suspend fun check(request: UpdateCheckRequest): UpdateCheckResult {
            requests++
            gate.await()
            return result
        }
    }

    private class RecordingUpdateDownloader : UpdateDownloadApi {
        val updates = mutableListOf<UpdateCheckResult.UpdateAvailable>()
        var update: UpdateCheckResult.UpdateAvailable? = null
            private set
        var credentials: UpdateDownloadCredentials? = null
            private set

        override suspend fun download(
            update: UpdateCheckResult.UpdateAvailable,
            credentials: UpdateDownloadCredentials,
            onProgress: (Long, Long) -> Unit,
        ): UpdateDownloadResult {
            updates += update
            this.update = update
            this.credentials = credentials
            return UpdateDownloadResult.Downloaded(
                DownloadedUpdate(
                    source = update.source,
                    version = update.version,
                    file = File("update.apk"),
                    sha256 = update.sha256.orEmpty(),
                ),
            )
        }
    }

    private class RecordingUpdateInstaller : UpdateInstallApi {
        override suspend fun install(update: DownloadedUpdate) = UpdateInstallResult.InstallerStarted
    }
}

private class NoopUpdateDownloadNotification : UpdateDownloadNotification {
    override fun start(version: String, totalBytes: Long) = Unit
    override fun progress(version: String, downloadedBytes: Long, totalBytes: Long) = Unit
    override fun complete(version: String) = Unit
    override fun failed(message: String) = Unit
    override fun cancel() = Unit
}
