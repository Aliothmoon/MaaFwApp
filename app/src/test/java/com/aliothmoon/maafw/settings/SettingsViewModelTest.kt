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
import com.aliothmoon.maafw.update.UpdateCheckFailure
import com.aliothmoon.maafw.update.UpdateCheckRequest
import com.aliothmoon.maafw.update.UpdateCheckResult
import com.aliothmoon.maafw.update.UpdateDownloadResult
import com.aliothmoon.maafw.update.UpdateDownloadFailure
import com.aliothmoon.maafw.update.UpdateInfo
import com.aliothmoon.maafw.update.UpdateResolveRequest
import com.aliothmoon.maafw.update.UpdateResolveResult
import com.aliothmoon.maafw.update.UpdateService
import com.aliothmoon.maafw.update.UpdateSource
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
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
    fun `manual check and download follow the selected mirror source`() = runTest {
        val resolvedUpdate = ResolvedUpdate(
            source = UpdateSource.MIRRORCHYAN,
            version = "2.0.0",
            downloadUrl = "https://mirror.example.com/app.apk",
            sha256 = "a".repeat(64),
        )
        val checkSources = mutableListOf<UpdateSource>()
        val checkRequests = mutableListOf<UpdateCheckRequest>()
        val resolveRequests = mutableListOf<UpdateResolveRequest>()
        var downloaded: ResolvedUpdate? = null
        val viewModel = viewModel(
            service = mockk {
                coEvery { check(any()) } coAnswers {
                    val request = firstArg<UpdateCheckRequest>()
                    checkSources += request.source
                    checkRequests += request
                    UpdateCheckResult.UpdateAvailable(
                        UpdateSource.MIRRORCHYAN,
                        UpdateInfo(version = "2.0.0"),
                    )
                }
                coEvery { resolve(any()) } coAnswers {
                    resolveRequests += firstArg<UpdateResolveRequest>()
                    UpdateResolveResult.Resolved(resolvedUpdate)
                }
            },
            downloader = mockk {
                coEvery { download(any(), any()) } coAnswers {
                    downloaded = firstArg<ResolvedUpdate>()
                    UpdateDownloadResult.Downloaded(
                        DownloadedUpdate(
                            version = resolvedUpdate.version,
                            file = File("update.apk"),
                            sha256 = resolvedUpdate.sha256.orEmpty(),
                        ),
                    )
                }
            },
        )

        viewModel.onIntent(SettingsIntent.SetUpdateChannel(UpdateChannel.BETA))
        viewModel.onIntent(SettingsIntent.SetMirrorchyanCdk("mirror-cdk"))
        viewModel.onIntent(SettingsIntent.CheckUpdate)
        viewModel.onIntent(SettingsIntent.DownloadUpdate)
        advanceUntilIdle()

        // 手动一次 + 填 CDK 触发的静默检查一次；都打所选的 Mirror酱 源
        assertEquals(2, checkRequests.size)
        assertEquals(listOf(UpdateSource.MIRRORCHYAN, UpdateSource.MIRRORCHYAN), checkSources)
        assertEquals(UpdateChannel.BETA, checkRequests.first().channel)

        // 只有下载这一次 resolve，源与 CDK 都来自设置
        val resolveRequest = resolveRequests.single()
        assertEquals(UpdateSource.MIRRORCHYAN, resolveRequest.source)
        assertEquals(UpdateChannel.BETA, resolveRequest.channel)
        assertEquals("mirror-cdk", resolveRequest.mirrorchyanCdk)

        assertEquals(resolvedUpdate, downloaded)
    }

    @Test
    fun `github source checks and downloads from github without cdk`() = runTest {
        val checkSources = mutableListOf<UpdateSource>()
        val resolveRequests = mutableListOf<UpdateResolveRequest>()
        val viewModel = viewModel(
            service = mockk {
                coEvery { check(any()) } coAnswers {
                    checkSources += firstArg<UpdateCheckRequest>().source
                    UpdateCheckResult.UpdateAvailable(UpdateSource.GITHUB, UpdateInfo("2.0.0"))
                }
                coEvery { resolve(any()) } coAnswers {
                    resolveRequests += firstArg<UpdateResolveRequest>()
                    UpdateResolveResult.Resolved(
                        ResolvedUpdate(UpdateSource.GITHUB, "2.0.0", "https://github.com/app.apk", "a".repeat(64)),
                    )
                }
            },
        )

        viewModel.onIntent(SettingsIntent.SetUpdateSource(UpdateSource.GITHUB))
        viewModel.onIntent(SettingsIntent.CheckUpdate)
        viewModel.onIntent(SettingsIntent.DownloadUpdate)
        advanceUntilIdle()

        assertEquals(listOf(UpdateSource.GITHUB), checkSources)
        val resolveRequest = resolveRequests.single()
        assertEquals(UpdateSource.GITHUB, resolveRequest.source)
        assertNull(resolveRequest.mirrorchyanCdk)
    }

    @Test
    fun `mirror source with blank cdk blocks download before any request`() = runTest {
        var resolved = false
        val viewModel = viewModel(
            service = mockk {
                coEvery { check(any()) } returns
                    UpdateCheckResult.UpdateAvailable(UpdateSource.MIRRORCHYAN, UpdateInfo("2.0.0"))
                coEvery { resolve(any()) } coAnswers {
                    resolved = true
                    UpdateResolveResult.Failed(UpdateSource.MIRRORCHYAN, UpdateCheckFailure.CDK_REQUIRED)
                }
            },
        )

        viewModel.onIntent(SettingsIntent.CheckUpdate)
        viewModel.onIntent(SettingsIntent.DownloadUpdate)
        advanceUntilIdle()

        // Mirror酱 源 + 空 CDK：不发请求，弹错误 dialog 引导填 CDK 或切源
        assertFalse(resolved)
        val panel = latestPanel(viewModel)
        assertNull(panel.updatePrompt)
        assertNotNull(panel.errorPrompt)
        assertFalse(panel.downloading)
    }

    @Test
    fun `changing settings during check does not allow duplicate check`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var requests = 0
        val viewModel = viewModel(
            service = mockk {
                coEvery { check(any()) } coAnswers {
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

    @Test
    fun `startup check pops prompt when update available and auto download off`() = runTest {
        var checks = 0
        val viewModel = viewModel(
            service = mockk {
                coEvery { check(any()) } coAnswers {
                    checks++
                    UpdateCheckResult.UpdateAvailable(UpdateSource.MIRRORCHYAN, UpdateInfo("2.0.0"))
                }
            },
            settings = FakeAppSettingsGateway().also { it.setAutoCheckUpdate(true) },
        )
        advanceUntilIdle()

        assertEquals(1, checks)
        val panel = latestPanel(viewModel)
        assertNotNull(panel.updatePrompt)
        assertNotNull(panel.checkResult)
        assertFalse(panel.downloading)
    }

    @Test
    fun `startup check does nothing when auto check disabled`() = runTest {
        var checks = 0
        viewModel(
            service = mockk {
                coEvery { check(any()) } coAnswers {
                    checks++
                    UpdateCheckResult.UpToDate(UpdateSource.MIRRORCHYAN, "1.0.0")
                }
            },
        )
        advanceUntilIdle()

        assertEquals(0, checks)
    }

    @Test
    fun `startup check stays silent on up to date`() = runTest {
        val viewModel = viewModel(
            service = mockk {
                coEvery { check(any()) } returns
                    UpdateCheckResult.UpToDate(UpdateSource.MIRRORCHYAN, "1.0.0")
            },
            settings = FakeAppSettingsGateway().also { it.setAutoCheckUpdate(true) },
        )
        advanceUntilIdle()

        val panel = latestPanel(viewModel)
        assertNull(panel.updatePrompt)
        assertNull(panel.errorPrompt)
        // 启动期的「已最新」不写进设置页结果行，避免用户没检查过却看到结果
        assertNull(panel.checkResult)
        assertFalse(panel.checking)
    }

    @Test
    fun `startup check failure pops error dialog`() = runTest {
        val viewModel = viewModel(
            service = mockk {
                coEvery { check(any()) } returns
                    UpdateCheckResult.SourceFailed(UpdateSource.MIRRORCHYAN, UpdateCheckFailure.NETWORK)
            },
            settings = FakeAppSettingsGateway().also { it.setAutoCheckUpdate(true) },
        )
        advanceUntilIdle()

        val panel = latestPanel(viewModel)
        assertNotNull(panel.errorPrompt)
        assertNull(panel.updatePrompt)
    }

    @Test
    fun `startup auto download downloads and installs without prompt`() = runTest {
        var downloaded = false
        val viewModel = viewModel(
            service = mockk {
                coEvery { check(any()) } returns
                    UpdateCheckResult.UpdateAvailable(UpdateSource.GITHUB, UpdateInfo("2.0.0"))
                coEvery { resolve(any()) } returns UpdateResolveResult.Resolved(
                    ResolvedUpdate(UpdateSource.GITHUB, "2.0.0", "https://github.com/app.apk", "a".repeat(64)),
                )
            },
            downloader = mockk {
                coEvery { download(any(), any()) } coAnswers {
                    downloaded = true
                    UpdateDownloadResult.Downloaded(
                        DownloadedUpdate("2.0.0", File("update.apk"), "a".repeat(64)),
                    )
                }
            },
            settings = FakeAppSettingsGateway().also {
                it.setAutoCheckUpdate(true)
                it.setAutoDownloadUpdate(true)
                it.updateSource.value = UpdateSource.GITHUB
            },
        )
        advanceUntilIdle()

        assertTrue(downloaded)
        val panel = latestPanel(viewModel)
        assertNull(panel.updatePrompt)
        assertFalse(panel.downloading)
    }

    @Test
    fun `filling cdk triggers a silent check without any prompt`() = runTest {
        var checks = 0
        var validations = 0
        val viewModel = viewModel(
            service = mockk {
                coEvery { resolve(any()) } coAnswers {
                    validations++
                    UpdateResolveResult.Resolved(
                        ResolvedUpdate(UpdateSource.MIRRORCHYAN, "2.0.0", "https://mirror.example.com/app.apk", "a".repeat(64)),
                    )
                }
                coEvery { check(any()) } coAnswers {
                    checks++
                    UpdateCheckResult.UpdateAvailable(UpdateSource.MIRRORCHYAN, UpdateInfo("2.0.0"))
                }
            },
        )
        advanceUntilIdle()
        assertEquals(0, checks)

        viewModel.onIntent(SettingsIntent.SetMirrorchyanCdk("cdk-1"))
        advanceUntilIdle()
        // 静默：查一次但不验证，结果整个丢弃，面板状态一个字段都不动
        assertEquals(1, checks)
        assertEquals(0, validations)
        val panel = latestPanel(viewModel)
        assertNull(panel.updatePrompt)
        assertNull(panel.errorPrompt)
        assertNull(panel.checkResult)

        viewModel.onIntent(SettingsIntent.SetMirrorchyanCdk(""))
        advanceUntilIdle()
        assertEquals(1, checks)
    }

    @Test
    fun `download with invalid cdk pops error dialog`() = runTest {
        val viewModel = viewModel(
            service = mockk {
                coEvery { check(any()) } returns
                    UpdateCheckResult.UpdateAvailable(UpdateSource.MIRRORCHYAN, UpdateInfo("2.0.0"))
                coEvery { resolve(any()) } returns
                    UpdateResolveResult.Failed(UpdateSource.MIRRORCHYAN, UpdateCheckFailure.CDK_INVALID)
            },
            settings = FakeAppSettingsGateway().also { it.mirrorchyanCdk.value = "bad-cdk" },
        )

        viewModel.onIntent(SettingsIntent.CheckUpdate)
        advanceUntilIdle()
        // 检查匿名，CDK 好坏不影响「发现新版本」弹窗
        assertNotNull(latestPanel(viewModel).updatePrompt)

        viewModel.onIntent(SettingsIntent.DownloadUpdate)
        advanceUntilIdle()

        // CDK 业务错误在下载解析时暴露，弹错误 dialog，与更新弹窗互斥
        val panel = latestPanel(viewModel)
        assertNull(panel.updatePrompt)
        assertNotNull(panel.errorPrompt)
        assertFalse(panel.downloading)

        viewModel.onIntent(SettingsIntent.DismissUpdateError)
        advanceUntilIdle()
        assertNull(latestPanel(viewModel).errorPrompt)
    }

    @Test
    fun `manual check pops prompt and dismiss clears it`() = runTest {
        val viewModel = viewModel()

        viewModel.onIntent(SettingsIntent.CheckUpdate)
        advanceUntilIdle()
        assertNotNull(latestPanel(viewModel).updatePrompt)

        viewModel.onIntent(SettingsIntent.DismissUpdatePrompt)
        advanceUntilIdle()
        val panel = latestPanel(viewModel)
        assertNull(panel.updatePrompt)
        // 忽略只清弹窗；checkResult 保留，重新检查会再弹
        assertNotNull(panel.checkResult)
    }

    @Test
    fun `cancel download resets state without error`() = runTest {
        val viewModel = viewModel(
            downloader = mockk {
                coEvery { download(any(), any()) } coAnswers { awaitCancellation() }
            },
            settings = FakeAppSettingsGateway().also { it.updateSource.value = UpdateSource.GITHUB },
        )
        viewModel.onIntent(SettingsIntent.CheckUpdate)
        advanceUntilIdle()
        viewModel.onIntent(SettingsIntent.DownloadUpdate)
        advanceUntilIdle()
        assertTrue(latestPanel(viewModel).downloading)

        viewModel.onIntent(SettingsIntent.CancelDownload)
        advanceUntilIdle()

        // 主动中断：放开 downloading，不上任何错误
        val panel = latestPanel(viewModel)
        assertFalse(panel.downloading)
        assertNull(panel.errorMessage)
        assertNull(panel.errorPrompt)
    }

    @Test
    fun `update settings are locked while downloading`() = runTest {
        val settings = FakeAppSettingsGateway().also { it.updateSource.value = UpdateSource.GITHUB }
        val viewModel = viewModel(
            downloader = mockk {
                coEvery { download(any(), any()) } coAnswers { awaitCancellation() }
            },
            settings = settings,
        )
        viewModel.onIntent(SettingsIntent.CheckUpdate)
        advanceUntilIdle()
        viewModel.onIntent(SettingsIntent.DownloadUpdate)
        advanceUntilIdle()
        assertTrue(latestPanel(viewModel).downloading)

        viewModel.onIntent(SettingsIntent.SetUpdateSource(UpdateSource.MIRRORCHYAN))
        viewModel.onIntent(SettingsIntent.SetMirrorchyanCdk("cdk"))
        viewModel.onIntent(SettingsIntent.SetUpdateChannel(UpdateChannel.BETA))
        viewModel.onIntent(SettingsIntent.SetAutoCheckUpdate(true))
        viewModel.onIntent(SettingsIntent.SetAutoDownloadUpdate(true))
        advanceUntilIdle()

        // 下载中写入口全部拒绝
        assertEquals(UpdateSource.GITHUB, settings.updateSource.value)
        assertEquals("", settings.mirrorchyanCdk.value)
        assertEquals(UpdateChannel.STABLE, settings.updateChannel.value)
        assertFalse(settings.autoCheckUpdate.value)
        assertFalse(settings.autoDownloadUpdate.value)

        // 中断后解锁
        viewModel.onIntent(SettingsIntent.CancelDownload)
        advanceUntilIdle()
        viewModel.onIntent(SettingsIntent.SetUpdateChannel(UpdateChannel.BETA))
        advanceUntilIdle()
        assertEquals(UpdateChannel.BETA, settings.updateChannel.value)
    }

    @Test
    fun `starting download clears prompt`() = runTest {
        val viewModel = viewModel(
            settings = FakeAppSettingsGateway().also { it.updateSource.value = UpdateSource.GITHUB },
        )

        viewModel.onIntent(SettingsIntent.CheckUpdate)
        advanceUntilIdle()
        assertNotNull(latestPanel(viewModel).updatePrompt)

        viewModel.onIntent(SettingsIntent.DownloadUpdate)
        advanceUntilIdle()
        assertNull(latestPanel(viewModel).updatePrompt)
    }

    // uiState 用 stateIn(WhileSubscribed)；直接 .value 会拿 initialValue。订阅一次
    // 等 combine 跑完，再读才有最新 updateOperation
    private suspend fun latestPanel(viewModel: SettingsViewModel): UpdatePanelState =
        viewModel.uiState.first().update

    private fun viewModel(
        service: UpdateService = mockk {
            coEvery { check(any()) } returns
                UpdateCheckResult.UpdateAvailable(UpdateSource.MIRRORCHYAN, UpdateInfo("2.0.0"))
            coEvery { resolve(any()) } returns UpdateResolveResult.Resolved(
                ResolvedUpdate(UpdateSource.MIRRORCHYAN, "2.0.0", "https://mirror.example.com/app.apk", "a".repeat(64)),
            )
        },
        downloader: OkHttpUpdateDownloader = mockk {
            coEvery { download(any(), any()) } returns UpdateDownloadResult.Downloaded(
                DownloadedUpdate("2.0.0", File("update.apk"), "a".repeat(64)),
            )
        },
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
            currentVersion = "1.0.0",
            supportedAbis = listOf("arm64-v8a"),
        )
    }
}
