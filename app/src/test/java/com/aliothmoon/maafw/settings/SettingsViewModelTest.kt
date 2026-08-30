package com.aliothmoon.maafw.settings

import com.aliothmoon.maafw.domain.ControllerDefinition
import com.aliothmoon.maafw.domain.ProjectDefinition
import com.aliothmoon.maafw.domain.ProjectMetadata
import com.aliothmoon.maafw.privileged.FakePermissionGateway
import com.aliothmoon.maafw.project.FakeProjectRepository
import com.aliothmoon.maafw.project.ProjectState
import com.aliothmoon.maafw.SystemApkInstaller
import com.aliothmoon.maafw.update.DownloadedUpdate
import com.aliothmoon.maafw.update.OkHttpUpdateDownloader
import com.aliothmoon.maafw.update.ResolvedUpdate
import com.aliothmoon.maafw.update.UpdateChannel
import com.aliothmoon.maafw.update.UpdateCheckRequest
import com.aliothmoon.maafw.update.UpdateCheckResult
import com.aliothmoon.maafw.update.UpdateDownloadResult
import com.aliothmoon.maafw.update.UpdateDownloadFailure
import com.aliothmoon.maafw.update.UpdateInfo
import com.aliothmoon.maafw.update.UpdateResolveRequest
import com.aliothmoon.maafw.update.UpdateResolveResult
import com.aliothmoon.maafw.update.UpdateService
import com.aliothmoon.maafw.update.UpdateSource
import com.aliothmoon.maafw.notification.NotificationPermissionRequester
import com.aliothmoon.maafw.notification.UpdateDownloadProgressState
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
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
        val githubResolved = ResolvedUpdate(
            source = UpdateSource.GITHUB,
            version = "2.0.0",
            downloadUrl = "https://github.com/app.apk",
            sha256 = "a".repeat(64),
        )
        val checkRequests = mutableListOf<UpdateCheckRequest>()
        val resolveRequests = mutableListOf<UpdateResolveRequest>()
        var downloaded: ResolvedUpdate? = null
        val viewModel = viewModel(
            service = mockk {
                coEvery { checkUpdate(any(), any(), any()) } coAnswers {
                    checkRequests += firstArg<UpdateCheckRequest>()
                    UpdateCheckResult.UpdateAvailable(
                        UpdateSource.MIRRORCHYAN,
                        UpdateInfo(version = "2.0.0"),
                    )
                }
                coEvery { resolveDownload(any()) } coAnswers {
                    resolveRequests += firstArg<UpdateResolveRequest>()
                    UpdateResolveResult.Resolved(githubResolved)
                }
            },
            downloader = mockk {
                coEvery { download(any(), any()) } coAnswers {
                    downloaded = firstArg<ResolvedUpdate>()
                    UpdateDownloadResult.Downloaded(
                        DownloadedUpdate(
                            source = githubResolved.source,
                            version = githubResolved.version,
                            file = File("update.apk"),
                            sha256 = githubResolved.sha256.orEmpty(),
                        ),
                    )
                }
            },
        )

        viewModel.onIntent(SettingsIntent.SetUpdateChannel(UpdateChannel.BETA))
        viewModel.onIntent(SettingsIntent.SetUpdateDownloadSource(UpdateSource.GITHUB))
        viewModel.onIntent(SettingsIntent.SetMirrorchyanCdk("mirror-cdk"))
        viewModel.onIntent(SettingsIntent.CheckUpdate)
        viewModel.onIntent(SettingsIntent.DownloadUpdate)
        advanceUntilIdle()

        // 检查固定 MirrorChyan 优先 + GitHub 兜底，不看下载源设置
        val manualCheck = checkRequests.single()
        assertEquals(UpdateChannel.BETA, manualCheck.channel)

        // 解析只用用户选的源，CDK 只在这一步带上
        val resolveRequest = resolveRequests.single()
        assertEquals(UpdateSource.GITHUB, resolveRequest.source)
        assertEquals(UpdateChannel.BETA, resolveRequest.channel)
        assertNull(resolveRequest.mirrorchyanCdk)

        assertEquals(githubResolved, downloaded)
    }

    @Test
    fun `download submits notification service before network resolve`() = runTest {
        val events = mutableListOf<String>()
        val viewModel = viewModel(
            service = mockk {
                coEvery { checkUpdate(any(), any(), any()) } coAnswers {
                    events += "check"
                    UpdateCheckResult.UpdateAvailable(UpdateSource.GITHUB, UpdateInfo("2.0.0"))
                }
                coEvery { resolveDownload(any()) } coAnswers {
                    events += "resolve"
                    UpdateResolveResult.Resolved(
                        ResolvedUpdate(UpdateSource.GITHUB, "2.0.0", "https://github.com/app.apk", null),
                    )
                }
            },
            downloader = mockk {
                coEvery { download(any(), any()) } coAnswers {
                    events += "download"
                    UpdateDownloadResult.Failed(UpdateDownloadFailure.UNKNOWN)
                }
            },
            notifier = mockk(relaxed = true) {
                every { start(any(), any()) } answers {
                    events += "start"
                    true
                }
            },
            settings = FakeAppSettingsGateway().also { it.setUpdateDownloadSource(UpdateSource.GITHUB) },
        )

        viewModel.onIntent(SettingsIntent.CheckUpdate)
        viewModel.onIntent(SettingsIntent.DownloadUpdate)
        advanceUntilIdle()

        // start 在 resolve 之前提交：FGS 要趁 Activity 在前台时启动；
        // resolve 落地后再 start 一次，把通知版本号同步成实际要下的那个
        assertEquals(listOf("check", "start", "resolve", "start", "download"), events)
    }

    @Test
    fun `download update surfaces error and skips downloader when notification permission denied`() = runTest {
        var resolved = false
        val viewModel = viewModel(
            service = mockk {
                coEvery { checkUpdate(any(), any(), any()) } returns
                    UpdateCheckResult.UpdateAvailable(UpdateSource.GITHUB, UpdateInfo("2.0.0"))
                coEvery { resolveDownload(any()) } coAnswers {
                    resolved = true
                    UpdateResolveResult.Resolved(
                        ResolvedUpdate(UpdateSource.GITHUB, "2.0.0", "https://github.com/app.apk", null),
                    )
                }
            },
            notificationPermissionRequester = mockk { every { isGranted() } returns false },
            settings = FakeAppSettingsGateway().also { it.setUpdateDownloadSource(UpdateSource.GITHUB) },
        )

        viewModel.onIntent(SettingsIntent.CheckUpdate)
        viewModel.onIntent(SettingsIntent.DownloadUpdate)
        advanceUntilIdle()

        // 检查照常拿到 UpdateAvailable，但权限拦在解析之前——resolver 不该被调到
        assertFalse(resolved)
        val panel = latestPanel(viewModel)
        assertTrue(panel.notificationPermissionDenied)
        assertNotNull(panel.errorMessage)
        assertFalse(panel.downloading)
    }

    @Test
    fun `notification permission result clears denial flag when granted`() = runTest {
        val permissionRequester = mockk<NotificationPermissionRequester> { every { isGranted() } returns false }
        val viewModel = viewModel(
            notificationPermissionRequester = permissionRequester,
            settings = FakeAppSettingsGateway().also { it.setUpdateDownloadSource(UpdateSource.GITHUB) },
        )

        viewModel.onIntent(SettingsIntent.CheckUpdate)
        viewModel.onIntent(SettingsIntent.DownloadUpdate)
        advanceUntilIdle()
        assertTrue(latestPanel(viewModel).notificationPermissionDenied)

        every { permissionRequester.isGranted() } returns true
        viewModel.onIntent(SettingsIntent.NotificationPermissionResult(granted = true))
        advanceUntilIdle()

        val panel = latestPanel(viewModel)
        assertFalse(panel.notificationPermissionDenied)
        assertNull(panel.errorMessage)
    }

    @Test
    fun `mirror download requires cdk`() = runTest {
        var resolved = false
        val viewModel = viewModel(
            service = mockk {
                coEvery { checkUpdate(any(), any(), any()) } returns
                    UpdateCheckResult.UpdateAvailable(UpdateSource.MIRRORCHYAN, UpdateInfo("2.0.0"))
                coEvery { resolveDownload(any()) } coAnswers {
                    resolved = true
                    UpdateResolveResult.Resolved(
                        ResolvedUpdate(UpdateSource.MIRRORCHYAN, "2.0.0", "https://mirror.example.com/app.apk", null),
                    )
                }
            },
        )

        viewModel.onIntent(SettingsIntent.CheckUpdate)
        viewModel.onIntent(SettingsIntent.DownloadUpdate)
        advanceUntilIdle()

        assertFalse(resolved)
    }

    @Test
    fun `changing settings during check does not allow duplicate check`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var requests = 0
        val viewModel = viewModel(
            service = mockk {
                coEvery { checkUpdate(any(), any(), any()) } coAnswers {
                    requests++
                    gate.await()
                    UpdateCheckResult.UpToDate(UpdateSource.MIRRORCHYAN, "1.0.0")
                }
            },
        )

        viewModel.onIntent(SettingsIntent.CheckUpdate)
        viewModel.onIntent(SettingsIntent.SetMirrorchyanCdk("mirror-cdk"))
        viewModel.onIntent(SettingsIntent.CheckUpdate)

        assertEquals(1, requests)
        gate.complete(Unit)
    }

    // uiState 用 stateIn(WhileSubscribed)；直接 .value 会拿 initialValue。订阅一次
    // 等 combine 跑完，再读才有最新 updateOperation
    private suspend fun latestPanel(viewModel: SettingsViewModel): UpdatePanelState =
        viewModel.uiState.first().update

    private fun viewModel(
        service: UpdateService = mockk {
            coEvery { checkUpdate(any(), any(), any()) } returns
                UpdateCheckResult.UpdateAvailable(UpdateSource.MIRRORCHYAN, UpdateInfo("2.0.0"))
            coEvery { resolveDownload(any()) } returns UpdateResolveResult.Resolved(
                ResolvedUpdate(UpdateSource.MIRRORCHYAN, "2.0.0", "https://mirror.example.com/app.apk", "a".repeat(64)),
            )
        },
        downloader: OkHttpUpdateDownloader = mockk {
            coEvery { download(any(), any()) } returns UpdateDownloadResult.Downloaded(
                DownloadedUpdate(UpdateSource.MIRRORCHYAN, "2.0.0", File("update.apk"), "a".repeat(64)),
            )
        },
        settings: AppSettingsGateway = FakeAppSettingsGateway(),
        notifier: UpdateDownloadProgressState = mockk(relaxed = true) {
            every { start(any(), any()) } returns true
        },
        notificationPermissionRequester: NotificationPermissionRequester =
            mockk { every { isGranted() } returns true },
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
                mirrorchyanRid = "mirror-rid",
            ),
        )
        return SettingsViewModel(
            permissionGateway = FakePermissionGateway(),
            appSettings = settings,
            projectRepository = FakeProjectRepository(ProjectState.Ready(definition, emptyList())),
            updateService = service,
            updateDownloader = downloader,
            apkInstaller = mockk {
                coEvery { install(any()) } returns SystemApkInstaller.Result.Started
            },
            updateDownloadNotifier = notifier,
            notificationPermissionRequester = notificationPermissionRequester,
            currentVersion = "1.0.0",
            supportedAbis = listOf("arm64-v8a"),
        )
    }
}
