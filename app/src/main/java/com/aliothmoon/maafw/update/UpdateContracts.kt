package com.aliothmoon.maafw.update

import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.i18n.UiText
import com.aliothmoon.maafw.i18n.uiTextOf
import java.io.File

/** Update metadata sources. The API never silently switches between them. */
enum class UpdateSource {
    MIRRORCHYAN,
    GITHUB,
}

enum class UpdateChannel {
    STABLE,
    BETA,
}

enum class AndroidAbi(val mirrorArch: String) {
    ARM64("arm64"),
    X86_64("amd64"),
    ARM("arm"),
    X86("386"),
}

/** 失败原因自带文案；动态细节由 [UpdateMessages] 拼接 */
enum class UpdateCheckFailure(val message: UiText) {
    MISSING_CONFIGURATION(uiTextOf(R.string.update_fail_missing_configuration)),
    NETWORK(uiTextOf(R.string.update_fail_network)),
    HTTP(uiTextOf(R.string.update_fail_http)),
    RATE_LIMITED(uiTextOf(R.string.update_fail_rate_limited)),
    RESOURCE_NOT_FOUND(uiTextOf(R.string.update_fail_resource_not_found)),
    INVALID_RESPONSE(uiTextOf(R.string.update_fail_invalid_response)),
    NO_MATCHING_ASSET(uiTextOf(R.string.update_fail_no_matching_asset)),
    VERSION_INVALID(uiTextOf(R.string.update_fail_version_invalid)),
    UNKNOWN(uiTextOf(R.string.update_fail_unknown)),
}

/**
 * 版本检查的输入；检查永远是匿名的——CDK 只属于下载地址解析（[UpdateResolveRequest]）
 */
data class UpdateCheckRequest(
    val currentVersion: String,
    val abi: AndroidAbi,
    val channel: UpdateChannel = UpdateChannel.STABLE,
    val mirrorchyanRid: String? = null,
    val githubRepository: String? = null,
)

/** 检查产物：只回答「有没有新版本」，下载端点由 [UpdateSourceClient.resolve] 在下载时解析 */
data class UpdateInfo(
    val version: String,
    val releaseNotesUrl: String? = null,
    val releaseNotes: String? = null,
)

sealed interface UpdateCheckResult {
    data class UpdateAvailable(
        val source: UpdateSource,
        val info: UpdateInfo,
    ) : UpdateCheckResult

    data class UpToDate(
        val source: UpdateSource,
        val latestVersion: String,
    ) : UpdateCheckResult

    data class SourceFailed(
        val source: UpdateSource,
        val reason: UpdateCheckFailure,
        val detail: UiText? = null,
    ) : UpdateCheckResult
}

/**
 * 下载地址解析的输入；按用户选择的单一源解析，CDK 只在这一步带上
 */
data class UpdateResolveRequest(
    val source: UpdateSource,
    val abi: AndroidAbi,
    val currentVersion: String,
    val channel: UpdateChannel = UpdateChannel.STABLE,
    val mirrorchyanRid: String? = null,
    val mirrorchyanCdk: String? = null,
    val githubRepository: String? = null,
)

/** 解析产物：下载端点与校验值 */
data class ResolvedUpdate(
    val source: UpdateSource,
    val version: String,
    val downloadUrl: String,
    val sha256: String?,
)

sealed interface UpdateResolveResult {
    data class Resolved(val update: ResolvedUpdate) : UpdateResolveResult

    data class Failed(
        val source: UpdateSource,
        val reason: UpdateCheckFailure,
        val detail: UiText? = null,
    ) : UpdateResolveResult
}

/**
 * 每源一个实现，编排见 [UpdateService]；「检查匿名、CDK 只在解析带上」的约束
 * 长在两个请求类型上，与实现无关
 */
interface UpdateSourceClient {
    val source: UpdateSource

    suspend fun check(request: UpdateCheckRequest): UpdateCheckResult

    suspend fun resolve(request: UpdateResolveRequest): UpdateResolveResult
}

enum class UpdateDownloadFailure(val message: UiText) {
    INVALID_URL(uiTextOf(R.string.update_download_fail_invalid_url)),
    NETWORK(uiTextOf(R.string.update_download_fail_network)),
    HTTP(uiTextOf(R.string.update_download_fail_http)),
    STORAGE(uiTextOf(R.string.update_download_fail_storage)),
    INVALID_DIGEST(uiTextOf(R.string.update_download_fail_invalid_digest)),
    DIGEST_MISMATCH(uiTextOf(R.string.update_download_fail_digest_mismatch)),
    UNKNOWN(uiTextOf(R.string.update_download_fail_unknown)),
}

data class DownloadedUpdate(
    val version: String,
    val file: File,
    val sha256: String,
)

sealed interface UpdateDownloadResult {
    data class Downloaded(val update: DownloadedUpdate) : UpdateDownloadResult

    data class Failed(
        val reason: UpdateDownloadFailure,
        val detail: UiText? = null,
    ) : UpdateDownloadResult
}

internal object UpdateDownloadFiles {
    const val DIRECTORY_NAME = "updates"

    fun directory(cacheDir: File): File = File(cacheDir, DIRECTORY_NAME)
}

/** 只把 `.apk` 结尾的 URL 视为可安装产物；查询串与并段不参与判断 */
internal fun String.isApkUrl(): Boolean =
    substringBefore('#').substringBefore('?').endsWith(".apk", ignoreCase = true)

/** api 层到用例层的浅结果：失败即 (reason, detail)，不走异常通道 */
sealed interface UpdateSourceOutcome<out T> {
    data class Ok<out T>(val value: T) : UpdateSourceOutcome<T>
    data class Failed(
        val reason: UpdateCheckFailure,
        val detail: UiText? = null,
    ) : UpdateSourceOutcome<Nothing>
}
