package com.aliothmoon.maafw.settings

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aliothmoon.maafw.BuildConfig
import com.aliothmoon.maafw.domain.ProjectMetadata
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.SystemApkInstaller
import com.aliothmoon.maafw.i18n.uiTextOf
import com.aliothmoon.maafw.notification.NotificationPermissionRequester
import com.aliothmoon.maafw.privileged.PermissionGateway
import com.aliothmoon.maafw.project.ProjectRepository
import com.aliothmoon.maafw.project.ProjectState
import com.aliothmoon.maafw.update.AndroidAbi
import com.aliothmoon.maafw.update.OkHttpUpdateDownloader

import com.aliothmoon.maafw.update.UpdateCheckRequest
import com.aliothmoon.maafw.update.UpdateResolveRequest
import com.aliothmoon.maafw.update.UpdateResolveResult
import com.aliothmoon.maafw.update.UpdateService
import com.aliothmoon.maafw.update.errorMessage
import com.aliothmoon.maafw.update.UpdateChannel
import com.aliothmoon.maafw.update.UpdateDownloadResult
import com.aliothmoon.maafw.update.UpdateSource
import com.aliothmoon.maafw.notification.UpdateDownloadProgressState
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
    private val updateService: UpdateService,
    private val updateDownloader: OkHttpUpdateDownloader,
    private val apkInstaller: SystemApkInstaller,
    private val updateDownloadNotifier: UpdateDownloadProgressState,
    private val notificationPermissionRequester: NotificationPermissionRequester,
    private val currentVersion: String = BuildConfig.VERSION_NAME,
    supportedAbis: List<String> = Build.SUPPORTED_ABIS.orEmpty().toList(),
) : ViewModel() {

    private data class UpdateSettingsSnapshot(
        val source: UpdateSource,
        val channel: UpdateChannel,
        val mirrorchyanCdk: String,
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
        appSettings.mirrorchyanCdk,
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
                mirrorchyanCdk = settings.mirrorchyanCdk,
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

            is SettingsIntent.SetMirrorchyanCdk -> viewModelScope.launch {
                appSettings.setMirrorchyanCdk(intent.cdk)
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
                if (intent.granted) viewModelScope.launch { downloadUpdate() }
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
            val result = updateService.checkUpdate(
                UpdateCheckRequest(
                    currentVersion = currentVersion,
                    channel = settings.channel,
                    abi = abi,
                    mirrorchyanRid = metadata?.mirrorchyanRid,
                    githubRepository = metadata?.githubRepository,
                ),
            )
            updateOperation.update {
                it.copy(checking = false, checkResult = result, errorMessage = null)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            updateOperation.update {
                it.copy(checking = false, errorMessage = uiTextOf(R.string.update_fail_unknown))
            }
        }
    }

    private suspend fun downloadUpdate() {
        val current = updateOperation.value
        val requestedUpdate = current.availableUpdate ?: return
        val settings = currentSettings()
        val credentialMissing = settings.source == UpdateSource.MIRRORCHYAN && settings.mirrorchyanCdk.isBlank()
        if (current.checking || current.downloading || credentialMissing) return

        // 通知权限守卫：Android 13+ 上 POST_NOTIFICATIONS 是运行时权限，拒了之后
        // FGS 起来也只会被系统悄悄吞掉通知。这里在动 notifier 之前先看一眼；拒了就把
        // 状态打上「需开启通知」+ 把请求抛给 UI 层弹系统对话框，下载流程不启动
        if (!notificationPermissionRequester.isGranted()) {
            updateOperation.update {
                it.copy(
                    notificationPermissionDenied = true,
                    errorMessage = uiTextOf(R.string.settings_update_notification_error),
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

        // FGS 必须趁 Activity 还在前台时提交；下面的二次解析是网络请求，用户点完
        // 下载就退后台后再启动会被系统按后台 FGS 限制拒绝。
        if (!updateDownloadNotifier.start(requestedUpdate.info.version, totalBytes = -1L)) {
            abortDownloadStart()
            return
        }
        try {
            val metadata = projectMetadata()

            // 下载前按用户选择的源解析端点：检查结果可能来自另一个源，CDK 只在这一步带上
            val update = when (val resolved = updateService.resolveDownload(
                UpdateResolveRequest(
                    source = settings.source,
                    channel = settings.channel,
                    abi = abi,
                    currentVersion = currentVersion,
                    mirrorchyanRid = metadata?.mirrorchyanRid,
                    mirrorchyanCdk = settings.mirrorchyanCdk
                        .takeIf { settings.source == UpdateSource.MIRRORCHYAN },
                    githubRepository = metadata?.githubRepository,
                ),
            )) {
                is UpdateResolveResult.Resolved -> resolved.update
                is UpdateResolveResult.Failed -> {
                    val message = resolved.errorMessage()
                        ?: uiTextOf(R.string.settings_update_no_downloadable_apk)
                    updateOperation.update { it.copy(downloading = false, errorMessage = message) }
                    updateDownloadNotifier.failed(message)
                    return
                }
            }

            if (!updateDownloadNotifier.start(update.version, totalBytes = -1L)) {
                abortDownloadStart()
                return
            }
            val downloadResult = updateDownloader.download(
                update = update,
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
            val message = uiTextOf(R.string.update_fail_unknown)
            updateDownloadNotifier.failed(message)
            updateOperation.update { it.copy(downloading = false, errorMessage = message) }
        }
    }

    private suspend fun install(result: UpdateDownloadResult.Downloaded) {
        when (val installResult = apkInstaller.install(result.update.file)) {
            SystemApkInstaller.Result.Started -> updateOperation.update {
                it.copy(
                    downloading = false,
                    downloadedVersion = result.update.version,
                    installerStarted = true,
                )
            }

            is SystemApkInstaller.Result.Failed -> updateOperation.update {
                it.copy(downloading = false, errorMessage = installResult.errorMessage())
            }
        }
    }

    /** 通知服务提交失败：下载中止并提示，两处 start 调用点共用 */
    private fun abortDownloadStart() {
        updateOperation.update {
            it.copy(
                downloading = false,
                errorMessage = uiTextOf(R.string.settings_update_notification_service_failed),
            )
        }
    }

    private fun currentSettings(): UpdateSettingsSnapshot =
        UpdateSettingsSnapshot(
            source = appSettings.updateDownloadSource.value,
            channel = appSettings.updateChannel.value,
            mirrorchyanCdk = appSettings.mirrorchyanCdk.value,
        )

    private suspend fun projectMetadata(): ProjectMetadata? =
        (projectRepository.state.value as? ProjectState.Ready)?.definition?.metadata

    private fun androidAbi(raw: String): AndroidAbi? = when (raw) {
        "arm64-v8a", "aarch64" -> AndroidAbi.ARM64
        "x86_64", "x64" -> AndroidAbi.X86_64
        "armeabi-v7a", "armeabi" -> AndroidAbi.ARM
        "x86", "i386" -> AndroidAbi.X86
        else -> null
    }

}
