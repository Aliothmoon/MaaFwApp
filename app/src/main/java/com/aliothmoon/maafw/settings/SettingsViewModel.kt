package com.aliothmoon.maafw.settings

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aliothmoon.maafw.BuildConfig
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.SystemApkInstaller
import com.aliothmoon.maafw.domain.ProjectMetadata
import com.aliothmoon.maafw.i18n.uiTextOf
import com.aliothmoon.maafw.privileged.PermissionGateway
import com.aliothmoon.maafw.project.ProjectRepository
import com.aliothmoon.maafw.project.ProjectState
import com.aliothmoon.maafw.update.AndroidAbi
import com.aliothmoon.maafw.update.OkHttpUpdateDownloader
import com.aliothmoon.maafw.update.UpdateCheckFailure
import com.aliothmoon.maafw.update.UpdateCheckRequest
import com.aliothmoon.maafw.update.UpdateCheckResult
import com.aliothmoon.maafw.update.UpdateDownloadResult
import com.aliothmoon.maafw.update.UpdateResolveRequest
import com.aliothmoon.maafw.update.UpdateResolveResult
import com.aliothmoon.maafw.update.UpdateService
import com.aliothmoon.maafw.update.UpdateSource
import com.aliothmoon.maafw.update.message
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration.Companion.milliseconds

/**
 * 设置页的 Activity 作用域会话
 *
 * 后端与 app 级设置在这里；运行配置仍由 SessionViewModel 持有，避免两颗状态互相抢写。
 */
@OptIn(FlowPreview::class)
class SettingsViewModel(
    private val permissionGateway: PermissionGateway,
    private val appSettings: AppSettingsGateway,
    private val projectRepository: ProjectRepository,
    private val updateService: UpdateService,
    private val updateDownloader: OkHttpUpdateDownloader,
    private val apkInstaller: SystemApkInstaller,
    private val currentVersion: String = BuildConfig.VERSION_NAME,
    supportedAbis: List<String> = Build.SUPPORTED_ABIS.orEmpty().toList(),
) : ViewModel() {

    private val abi = supportedAbis.firstNotNullOfOrNull(::androidAbi) ?: AndroidAbi.ANY
    private val updateOperation = MutableStateFlow(UpdatePanelState())

    /** 只在 CAS 抢到 downloading 位后登记，取消不会误伤没抢到位的空跑协程 */
    private var downloadJob: Job? = null

    init {
        viewModelScope.launch { startupUpdateCheck() }
        // 对齐 MaaMeow：填写 CDK 时静默触发一次检查，结果丢弃、不弹任何窗
        viewModelScope.launch {
            appSettings.loaded.first { it }
            appSettings.mirrorchyanCdk.drop(1).filter(String::isNotBlank)
                .debounce(CDK_CHECK_DEBOUNCE_MS.milliseconds).collect {
                    val metadata = projectMetadata()
                    updateService.check(
                        UpdateCheckRequest(
                            source = appSettings.updateSource.value,
                            currentVersion = currentVersion,
                            channel = appSettings.updateChannel.value,
                            abi = abi,
                            mirrorchyanRid = metadata?.mirrorchyanRid,
                            githubRepository = metadata?.githubRepository,
                        ),
                    )
                }
        }
    }

    private val updatePanel = combine(
        updateOperation,
        appSettings.updateChannel,
        appSettings.updateSource,
        appSettings.mirrorchyanCdk,
        appSettings.autoCheckUpdate,
    ) { operation, channel, source, cdk, autoCheck ->
        operation.copy(
            channel = channel,
            updateSource = source,
            mirrorchyanCdk = cdk,
            autoCheckUpdate = autoCheck,
        )
    }.combine(appSettings.autoDownloadUpdate) { base, autoDownload ->
        base.copy(autoDownloadUpdate = autoDownload)
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        permissionGateway.state,
        updatePanel,
    ) { remote, update ->
        SettingsUiState(remoteAccess = remote, update = update)
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

            // 下载过程中锁死更新设置：UI 已禁用控件，这里是写入口的二次校验（锁定两层惯例）
            is SettingsIntent.SetUpdateChannel -> viewModelScope.launch {
                if (updateSettingsLocked()) return@launch
                appSettings.setUpdateChannel(intent.channel)
            }

            is SettingsIntent.SetUpdateSource -> viewModelScope.launch {
                if (updateSettingsLocked()) return@launch
                appSettings.setUpdateSource(intent.source)
            }

            is SettingsIntent.SetMirrorchyanCdk -> viewModelScope.launch {
                if (updateSettingsLocked()) return@launch
                appSettings.setMirrorchyanCdk(intent.cdk)
            }

            is SettingsIntent.SetAutoCheckUpdate -> viewModelScope.launch {
                if (updateSettingsLocked()) return@launch
                appSettings.setAutoCheckUpdate(intent.enabled)
            }

            is SettingsIntent.SetAutoDownloadUpdate -> viewModelScope.launch {
                if (updateSettingsLocked()) return@launch
                appSettings.setAutoDownloadUpdate(intent.enabled)
            }

            SettingsIntent.CheckUpdate -> viewModelScope.launch { checkUpdate() }
            SettingsIntent.DownloadUpdate -> viewModelScope.launch { downloadUpdate() }
            SettingsIntent.CancelDownload -> downloadJob?.cancel()
            SettingsIntent.DismissUpdatePrompt -> updateOperation.update { it.copy(updatePrompt = null) }
            SettingsIntent.DismissUpdateError -> updateOperation.update { it.copy(errorPrompt = null) }
        }
    }

    /**
     * 启动自检：等设置读盘与 PI 就绪后查一次；VM 存活期内只跑这一回。
     * 不写 checkResult（首页不出现结果行）；失败照弹错误窗，发现新版本按自动下载开关走
     * 静默下载或弹「发现新版本」dialog
     */
    private suspend fun startupUpdateCheck() {
        appSettings.loaded.first { it }
        if (!appSettings.autoCheckUpdate.value) return
        val metadata = projectRepository.state.filterIsInstance<ProjectState.Ready>()
            .first().definition.metadata
        if (updateOperation.value.checking || updateOperation.value.downloading) return
        updateOperation.update { it.copy(checking = true) }
        val result = updateService.check(
            UpdateCheckRequest(
                source = appSettings.updateSource.value,
                currentVersion = currentVersion,
                channel = appSettings.updateChannel.value,
                abi = abi,
                mirrorchyanRid = metadata.mirrorchyanRid,
                githubRepository = metadata.githubRepository,
            ),
        )
        val available = result as? UpdateCheckResult.UpdateAvailable
        if (available == null) {
            Timber.tag("UpdateCheck")
                .w("startup check found no update: %s", result::class.simpleName)
            updateOperation.update {
                it.copy(checking = false, errorPrompt = result.message()?.let(UpdateErrorPrompt::check))
            }
            return
        }
        updateOperation.update { it.copy(checking = false, checkResult = result) }
        if (appSettings.autoDownloadUpdate.value) {
            downloadUpdate()
        } else {
            updateOperation.update { it.copy(updatePrompt = available) }
        }
    }

    private suspend fun checkUpdate() {
        if (updateOperation.value.checking || updateOperation.value.downloading) return
        updateOperation.update {
            it.copy(checking = true, checkResult = null, errorMessage = null, errorPrompt = null)
        }
        val metadata = projectMetadata()
        // checker 内部已把非取消异常吞成 SourceFailed，这里不需要再兜一层
        val result = updateService.check(
            UpdateCheckRequest(
                source = appSettings.updateSource.value,
                currentVersion = currentVersion,
                channel = appSettings.updateChannel.value,
                abi = abi,
                mirrorchyanRid = metadata?.mirrorchyanRid,
                githubRepository = metadata?.githubRepository,
            ),
        )
        // 错误与更新走同一种呈现（弹窗），二者天然互斥：失败不可能同时是 UpdateAvailable
        updateOperation.update {
            it.copy(
                checking = false,
                checkResult = result,
                errorPrompt = result.message()?.let(UpdateErrorPrompt::check),
                updatePrompt = result as? UpdateCheckResult.UpdateAvailable,
            )
        }
    }

    /** CAS 占 downloading 位；连点与启动自检/手动并发抢不到位就静默放弃 */
    private fun claimDownload(): UpdateCheckResult.UpdateAvailable? {
        val current = updateOperation.value
        val requested = current.availableUpdate ?: return null
        if (current.checking || current.downloading) return null
        val claimed = current.copy(
            downloading = true,
            downloadedBytes = -1L,
            totalBytes = -1L,
            errorMessage = null,
        )
        return if (updateOperation.compareAndSet(current, claimed)) requested else null
    }

    private fun updateSettingsLocked(): Boolean = updateOperation.value.downloading

    private suspend fun downloadUpdate() {
        // 二次触发（连点、启动自检与手动并发）不上错，CAS 抢不到位就静默快速返回
        claimDownload() ?: return
        downloadJob = coroutineContext.job

        val source = appSettings.updateSource.value
        val cdk = appSettings.mirrorchyanCdk.value
        // Mirror酱 无 CDK 必然解析失败；发请求前就地拦下，
        // 免得用户对着一个网络错误猜原因（MirrorChyanUpdateClient.resolve 有同款类型化校验兜其它调用方）
        if (source == UpdateSource.MIRRORCHYAN && cdk.isBlank()) {
            updateOperation.update {
                it.copy(
                    downloading = false,
                    updatePrompt = null,
                    errorPrompt = UpdateErrorPrompt.download(UpdateCheckFailure.CDK_REQUIRED.message),
                )
            }
            return
        }

        updateOperation.update { it.copy(updatePrompt = null) }

        try {
            val metadata = projectMetadata()

            // 按所选更新源现场解析下载端点，CDK 只在这一步带上；
            // CDK 业务错误（7xxx）也只会在这里暴露，与检查错误同走弹窗
            val update = when (val resolved = updateService.resolve(
                UpdateResolveRequest(
                    source = source,
                    channel = appSettings.updateChannel.value,
                    abi = abi,
                    currentVersion = currentVersion,
                    mirrorchyanRid = metadata?.mirrorchyanRid,
                    mirrorchyanCdk = cdk.takeIf(String::isNotBlank),
                    githubRepository = metadata?.githubRepository,
                ),
            )) {
                is UpdateResolveResult.Resolved -> resolved.update
                is UpdateResolveResult.Failed -> {
                    val message = resolved.message()
                        ?: uiTextOf(R.string.settings_update_no_downloadable_apk)
                    updateOperation.update {
                        it.copy(downloading = false, errorPrompt = UpdateErrorPrompt.download(message))
                    }
                    return
                }
            }

            val result = updateDownloader.download(
                update = update,
                onProgress = { downloaded, total ->
                    updateOperation.update {
                        it.copy(downloadedBytes = downloaded, totalBytes = total)
                    }
                },
            )
            when (result) {
                is UpdateDownloadResult.Downloaded -> install(result)
                is UpdateDownloadResult.Failed -> updateOperation.update {
                    it.copy(downloading = false, errorMessage = result.message())
                }
            }
        } catch (e: CancellationException) {
            // 用户主动中断：放开 downloading 位，不上任何错误
            updateOperation.update { it.copy(downloading = false) }
            throw e
        } catch (e: Exception) {
            Timber.e(e, "download update error")
            updateOperation.update {
                it.copy(downloading = false, errorMessage = uiTextOf(R.string.update_fail_unknown))
            }
        }
    }

    private suspend fun install(result: UpdateDownloadResult.Downloaded) {
        when (val installResult = apkInstaller.install(result.update.file)) {
            SystemApkInstaller.Result.Started -> updateOperation.update {
                it.copy(downloading = false)
            }

            is SystemApkInstaller.Result.Failed -> updateOperation.update {
                it.copy(downloading = false, errorMessage = installResult.message())
            }
        }
    }

    private fun projectMetadata(): ProjectMetadata? =
        (projectRepository.state.value as? ProjectState.Ready)?.definition?.metadata

    private fun androidAbi(raw: String): AndroidAbi? = when (raw) {
        "arm64-v8a", "aarch64" -> AndroidAbi.ARM64
        "x86_64", "x64" -> AndroidAbi.X86_64
        "armeabi-v7a", "armeabi" -> AndroidAbi.ARM
        "x86", "i386" -> AndroidAbi.X86
        else -> null
    }

    private companion object {
        const val CDK_CHECK_DEBOUNCE_MS = 1_000L
    }
}
