package com.aliothmoon.maafw.settings

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aliothmoon.maafw.BuildConfig
import com.aliothmoon.maafw.domain.ProjectMetadata
import com.aliothmoon.maafw.notification.NotificationPermissionRequester
import com.aliothmoon.maafw.privileged.PermissionGateway
import com.aliothmoon.maafw.project.ProjectRepository
import com.aliothmoon.maafw.project.ProjectState
import com.aliothmoon.maafw.update.AndroidAbi
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 设置页的 Activity 作用域会话
 *
 * 后端与 app 级设置在这里；运行配置仍由 SessionViewModel 持有，避免两颗状态互相抢写。
 */
class SettingsViewModel(
    private val permissionGateway: PermissionGateway,
    private val appSettings: AppSettingsGateway,
    private val projectRepository: ProjectRepository,
    private val updateCheckApi: UpdateCheckApi,
    private val updateDownloader: UpdateDownloadApi,
    private val updateInstaller: UpdateInstallApi,
    private val updateDownloadNotifier: UpdateDownloadNotification,
    private val notificationPermissionRequester: NotificationPermissionRequester,
    private val currentVersion: String = BuildConfig.VERSION_NAME,
    supportedAbis: List<String> = Build.SUPPORTED_ABIS.orEmpty().toList(),
) : ViewModel() {

    private data class UpdateSettingsSnapshot(
        val source: UpdateSource,
        val channel: UpdateChannel,
        val githubToken: String,
        val mirrorChyanCdk: String,
    )

    private val abi = supportedAbis.firstNotNullOfOrNull(::androidAbi) ?: AndroidAbi.ARM64
    private val updateOperation = MutableStateFlow(UpdatePanelState())

    // 用 SharedFlow 当一次性 effect：UI 层 LaunchedEffect 收一次即消费；不让任何 effect 被覆盖
    // 也用 BufferOverflow.DROP_OLDEST 防 UI 层还没挂上 collect 时的丢消息
    private val _effects = MutableSharedFlow<SettingsEffect>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val effects: Flow<SettingsEffect> = _effects.asSharedFlow()

    private val updateSettings = combine(
        appSettings.updateDownloadSource,
        appSettings.updateChannel,
        appSettings.githubToken,
        appSettings.mirrorChyanCdk,
        ::UpdateSettingsSnapshot,
    )

    val uiState: StateFlow<SettingsUiState> = combine(
        permissionGateway.state,
        projectRepository.state,
        updateSettings,
        updateOperation,
    ) { remote, project, settings, operation ->
        SettingsUiState(
            remoteAccess = remote,
            update = operation.copy(
                downloadSource = settings.source,
                channel = settings.channel,
                githubToken = settings.githubToken,
                mirrorChyanCdk = settings.mirrorChyanCdk,
            ),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(),
    )

    fun onIntent(intent: SettingsIntent) {
        when (intent) {
            is SettingsIntent.SetBackend -> viewModelScope.launch {
                permissionGateway.setBackend(intent.backend)
            }

            is SettingsIntent.SetUpdateDownloadSource -> viewModelScope.launch {
                appSettings.setUpdateDownloadSource(intent.source)
            }

            is SettingsIntent.SetUpdateChannel -> viewModelScope.launch {
                appSettings.setUpdateChannel(intent.channel)
            }

            is SettingsIntent.SetGithubToken -> viewModelScope.launch {
                appSettings.setGithubToken(intent.token)
            }

            is SettingsIntent.SetMirrorChyanCdk -> viewModelScope.launch {
                appSettings.setMirrorChyanCdk(intent.cdk)
            }

            SettingsIntent.CheckUpdate -> viewModelScope.launch { checkUpdate() }
            SettingsIntent.DownloadUpdate -> viewModelScope.launch { downloadUpdate() }

            is SettingsIntent.NotificationPermissionResult -> {
                updateOperation.update {
                    it.copy(
                        notificationPermissionDenied = !intent.granted,
                        // 用户刚授权过，清掉「需要通知权限」的红字 errorMessage
                        errorMessage = if (intent.granted) null else it.errorMessage,
                    )
                }
            }
        }
    }

    private suspend fun checkUpdate() {
        if (updateOperation.value.checking || updateOperation.value.downloading) return
        val settings = currentSettings()
        updateOperation.update {
            it.copy(checking = true, checkResult = null, errorMessage = null)
        }
        val metadata = projectMetadata()
        try {
            val result = updateCheckApi.check(
                UpdateCheckRequest(
                    currentVersion = currentVersion,
                    preferredSource = UpdateSource.MIRROR_CHYAN,
                    alternativeSource = UpdateSource.GITHUB,
                    channel = settings.channel,
                    abi = abi,
                    mirrorChyanRid = metadata?.mirrorChyanRid,
                    githubRepository = metadata?.githubRepository,
                ),
            )
            updateOperation.update {
                it.copy(checking = false, checkResult = result, errorMessage = null)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            updateOperation.update { it.copy(checking = false, errorMessage = e.message) }
        }
    }

    private suspend fun downloadUpdate() {
        val current = updateOperation.value
        val settings = currentSettings()
        val credentialMissing = settings.source == UpdateSource.MIRROR_CHYAN && settings.mirrorChyanCdk.isBlank()
        if (current.availableUpdate == null || current.checking || current.downloading || credentialMissing) return

        // 通知权限守卫：Android 13+ 上 POST_NOTIFICATIONS 是运行时权限，拒了之后
        // FGS 起来也只会被系统悄悄吞掉通知。这里在动 notifier 之前先看一眼；拒了就把
        // 状态打上「需开启通知」+ 把请求抛给 UI 层弹系统对话框，下载流程不启动
        if (!notificationPermissionRequester.isGranted()) {
            updateOperation.update {
                it.copy(
                    notificationPermissionDenied = true,
                    errorMessage = "需要授予通知权限才能在通知栏显示下载进度",
                )
            }
            _effects.tryEmit(SettingsEffect.RequestNotificationPermission)
            return
        }

        updateOperation.update {
            it.copy(
                downloading = true,
                downloadedBytes = -1L,
                totalBytes = -1L,
                downloadedVersion = null,
                installerStarted = false,
                errorMessage = null,
                notificationPermissionDenied = false,
            )
        }
        try {
            val metadata = projectMetadata()
            val credentials = UpdateDownloadCredentials(
                githubToken = settings.githubToken.takeIf { settings.source == UpdateSource.GITHUB },
                mirrorChyanCdk = settings.mirrorChyanCdk.takeIf { settings.source == UpdateSource.MIRROR_CHYAN },
            )

            // 下载前按用户选择源重新解析一次：检查结果可能来自另一个源，且下载凭据不能带入首次检查。
            val resolved = updateCheckApi.check(
                UpdateCheckRequest(
                    currentVersion = currentVersion,
                    preferredSource = settings.source,
                    alternativeSource = null,
                    channel = settings.channel,
                    abi = abi,
                    mirrorChyanRid = metadata?.mirrorChyanRid,
                    mirrorChyanCdk = credentials.mirrorChyanCdk,
                    githubRepository = metadata?.githubRepository,
                    githubToken = credentials.githubToken,
                ),
            )
            val update = resolved as? UpdateCheckResult.UpdateAvailable
                ?: error(resolved.errorMessage() ?: "Selected update source has no downloadable APK")

            updateDownloadNotifier.start(update.version, totalBytes = -1L)
            val downloadResult = updateDownloader.download(
                update = update,
                credentials = credentials,
                onProgress = { downloaded, total ->
                    updateDownloadNotifier.progress(update.version, downloaded, total)
                    updateOperation.update {
                        it.copy(downloadedBytes = downloaded, totalBytes = total)
                    }
                },
            )
            when (downloadResult) {
                is UpdateDownloadResult.Downloaded -> {
                    updateDownloadNotifier.complete(downloadResult.update.version)
                    install(downloadResult)
                }
                is UpdateDownloadResult.Failed -> {
                    updateDownloadNotifier.failed(downloadResult.errorMessage())
                    updateOperation.update {
                        it.copy(downloading = false, errorMessage = downloadResult.errorMessage())
                    }
                }
            }
        } catch (e: CancellationException) {
            updateDownloadNotifier.cancel()
            throw e
        } catch (e: Exception) {
            updateDownloadNotifier.failed(e.message ?: "未知错误")
            updateOperation.update { it.copy(downloading = false, errorMessage = e.message) }
        }
    }

    private suspend fun install(result: UpdateDownloadResult.Downloaded) {
        when (val installResult = updateInstaller.install(result.update)) {
            UpdateInstallResult.InstallerStarted -> updateOperation.update {
                it.copy(
                    downloading = false,
                    downloadedVersion = result.update.version,
                    installerStarted = true,
                )
            }

            is UpdateInstallResult.Failed -> updateOperation.update {
                it.copy(downloading = false, errorMessage = installResult.errorMessage())
            }
        }
    }

    private fun currentSettings(): UpdateSettingsSnapshot {
        return UpdateSettingsSnapshot(
            source = appSettings.updateDownloadSource.value,
            channel = appSettings.updateChannel.value,
            githubToken = appSettings.githubToken.value,
            mirrorChyanCdk = appSettings.mirrorChyanCdk.value,
        )
    }

    private suspend fun projectMetadata(): ProjectMetadata? =
        (projectRepository.state.value as? ProjectState.Ready)?.definition?.metadata

    private fun androidAbi(raw: String): AndroidAbi? = when (raw) {
        "arm64-v8a", "aarch64" -> AndroidAbi.ARM64
        "x86_64", "x64" -> AndroidAbi.X86_64
        "armeabi-v7a", "armeabi" -> AndroidAbi.ARM
        "x86", "i386" -> AndroidAbi.X86
        else -> null
    }

    private fun UpdateCheckResult.errorMessage(): String? = when (this) {
        is UpdateCheckResult.UpdateAvailable -> null
        is UpdateCheckResult.UpToDate -> null
        is UpdateCheckResult.SourceFailed ->
            message?.let { "$source: $it" } ?: "$source: ${reason.name}"
    }

    private fun UpdateInstallResult.Failed.errorMessage(): String =
        message?.let { "${reason.name}: $it" } ?: reason.name

    private fun UpdateDownloadResult.Failed.errorMessage(): String =
        message?.let { "${reason.name}: $it" } ?: reason.name
}
