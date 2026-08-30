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
import com.aliothmoon.maafw.notification.DownloadState
import com.aliothmoon.maafw.notification.UpdateDownloadNotification
import com.aliothmoon.maafw.notification.NotificationPermissionRequester
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    fun `download submits notification service before network recheck`() = runTest {
        val events = mutableListOf<String>()
        val checkApi = object : UpdateCheckApi {
            override suspend fun check(request: UpdateCheckRequest): UpdateCheckResult {
                events += "check"
                return availableUpdate()
            }
        }
        val downloader = object : UpdateDownloadApi {
            override suspend fun download(
                update: UpdateCheckResult.UpdateAvailable,
                credentials: UpdateDownloadCredentials,
                onProgress: (Long, Long) -> Unit,
            ): UpdateDownloadResult {
                events += "download"
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
        val notifier = RecordingUpdateDownloadNotification(events)
        val viewModel = viewModel(
            checkApi = checkApi,
            downloader = downloader,
            settings = FakeAppSettingsGateway().also { it.setUpdateDownloadSource(UpdateSource.GITHUB) },
            notifier = notifier,
        )

        viewModel.onIntent(SettingsIntent.CheckUpdate)
        viewModel.onIntent(SettingsIntent.DownloadUpdate)
        advanceUntilIdle()

        assertEquals(listOf("check", "start", "check", "start", "download"), events)
    }

    @Test
    fun `download update surfaces error and skips notifier when notification permission denied`() = runTest {
        val checkApi = RecordingUpdateCheckApi(availableUpdate())
        val downloader = RecordingUpdateDownloader()
        val notifier = NoopUpdateDownloadNotification()
        val settings = FakeAppSettingsGateway()
        val viewModel = viewModel(
            checkApi = checkApi,
            downloader = downloader,
            settings = settings,
            notificationPermissionRequester = FakeNotificationPermissionRequester(granted = false),
        )

        settings.setUpdateDownloadSource(UpdateSource.GITHUB)
        viewModel.onIntent(SettingsIntent.CheckUpdate)
        viewModel.onIntent(SettingsIntent.DownloadUpdate)
        advanceUntilIdle()

        // 重检照常跑（拿到 UpdateAvailable），但 downloader 不该被调到——permission 拦在前面
        assertEquals(1, checkApi.requests.size)
        assertTrue(downloader.updates.isEmpty())
        val panel = latestPanel(viewModel)
        assertTrue(panel.notificationPermissionDenied)
        assertNotNull(panel.errorMessage)
        assertFalse(panel.downloading)
    }

    private suspend fun latestPanel(viewModel: SettingsViewModel): UpdatePanelState {
        // uiState 用 stateIn(WhileSubscribed)；直接 .value 会拿 initialValue。订阅一次
        // 等 combine 跑完，再读才有最新 updateOperation
        val first = viewModel.uiState.first().update
        return first
    }

    @Test
    fun `notification permission result clears denial flag when granted`() = runTest {
        val checkApi = RecordingUpdateCheckApi(availableUpdate(), availableUpdate())
        val settings = FakeAppSettingsGateway()
        val permissionRequester = FakeNotificationPermissionRequester(granted = false)
        val viewModel = viewModel(
            checkApi = checkApi,
            settings = settings,
            notificationPermissionRequester = permissionRequester,
        )
        settings.setUpdateDownloadSource(UpdateSource.GITHUB)
        viewModel.onIntent(SettingsIntent.CheckUpdate)
        viewModel.onIntent(SettingsIntent.DownloadUpdate)
        advanceUntilIdle()
        assertTrue(latestPanel(viewModel).notificationPermissionDenied)

        permissionRequester.granted = true
        viewModel.onIntent(SettingsIntent.NotificationPermissionResult(granted = true))
        advanceUntilIdle()

        val panel = latestPanel(viewModel)
        assertFalse(panel.notificationPermissionDenied)
        assertNull(panel.errorMessage)
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
        notifier: UpdateDownloadNotification = NoopUpdateDownloadNotification(),
        notificationPermissionRequester: NotificationPermissionRequester =
            FakeNotificationPermissionRequester(granted = true),
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
            updateDownloadNotifier = notifier,
            notificationPermissionRequester = notificationPermissionRequester,
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
    private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
    override val state: StateFlow<DownloadState> = _state.asStateFlow()
    override fun start(version: String, totalBytes: Long) = true
    override fun progress(version: String, downloadedBytes: Long, totalBytes: Long) = Unit
    override fun complete(version: String) = Unit
    override fun failed(message: String) = Unit
    override fun cancel() = Unit
}


private class RecordingUpdateDownloadNotification(
    private val events: MutableList<String>,
) : UpdateDownloadNotification {
    private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
    override val state: StateFlow<DownloadState> = _state.asStateFlow()
    override fun start(version: String, totalBytes: Long): Boolean {
        events += "start"
        return true
    }
    override fun progress(version: String, downloadedBytes: Long, totalBytes: Long) = Unit
    override fun complete(version: String) = Unit
    override fun failed(message: String) = Unit
    override fun cancel() = Unit
}


private class FakeNotificationPermissionRequester(
    var granted: Boolean = true,
) : NotificationPermissionRequester {
    override fun isGranted(): Boolean = granted
}
