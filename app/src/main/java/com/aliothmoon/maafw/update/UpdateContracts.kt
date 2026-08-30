package com.aliothmoon.maafw.update

import androidx.annotation.StringRes
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.i18n.UiText
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

enum class UpdateCheckFailure(@param:StringRes val message: Int) {
    MISSING_CONFIGURATION(R.string.update_fail_missing_configuration),
    NETWORK(R.string.update_fail_network),
    HTTP(R.string.update_fail_http),
    RATE_LIMITED(R.string.update_fail_rate_limited),
    RESOURCE_NOT_FOUND(R.string.update_fail_resource_not_found),
    INVALID_RESPONSE(R.string.update_fail_invalid_response),
    NO_MATCHING_ASSET(R.string.update_fail_no_matching_asset),
    VERSION_INVALID(R.string.update_fail_version_invalid),
    UNKNOWN(R.string.update_fail_unknown),
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

/** 检查产物：只回答「有没有新版本」，下载端点由 [UpdateDownloadUrlResolver] 在下载时解析 */
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
        /** 由编排层补上的兜底提示；单源检查不填 */
        val alternativeSource: UpdateSource? = null,
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

/** 每源一个；两个生产实现，编排见 [UpdateService] */
interface UpdateVersionChecker {
    val source: UpdateSource

    suspend fun check(request: UpdateCheckRequest): UpdateCheckResult
}

/** 每源一个；只在下载前调用，检查阶段不出下载端点 */
interface UpdateDownloadUrlResolver {
    val source: UpdateSource

    suspend fun resolve(request: UpdateResolveRequest): UpdateResolveResult
}

enum class UpdateDownloadFailure(@param:StringRes val messageRes: Int) {
    INVALID_URL(R.string.update_download_fail_invalid_url),
    NETWORK(R.string.update_download_fail_network),
    HTTP(R.string.update_download_fail_http),
    STORAGE(R.string.update_download_fail_storage),
    INVALID_DIGEST(R.string.update_download_fail_invalid_digest),
    DIGEST_MISMATCH(R.string.update_download_fail_digest_mismatch),
    UNKNOWN(R.string.update_download_fail_unknown),
}

data class DownloadedUpdate(
    val source: UpdateSource,
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

internal class UpdateSourceException(
    val reason: UpdateCheckFailure,
    /** 技术细节，仅进日志；用户可见的动态细节走 [detail] */
    override val message: String?,
    val detail: UiText? = null,
    override val cause: Throwable? = null,
) : Exception(message, cause)
