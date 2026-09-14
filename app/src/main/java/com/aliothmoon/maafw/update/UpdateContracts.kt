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
    ANY(""),
    ARM64("arm64"),
    X86_64("amd64"),
    ARM("arm"),
    X86("386"),
}

/** 失败原因自带文案；动态细节由 [UpdateMessages] 拼接。CDK_* 与 INVALID_* 对应 MirrorChyan 业务码 */
enum class UpdateCheckFailure(val message: UiText) {
    MISSING_CONFIGURATION(uiTextOf(R.string.update_fail_missing_configuration)),
    NETWORK(uiTextOf(R.string.update_fail_network)),
    HTTP(uiTextOf(R.string.update_fail_http)),

    /** GitHub 403/429；MirrorChyan 的次数上限走 [CDK_QUOTA_EXHAUSTED] */
    RATE_LIMITED(uiTextOf(R.string.update_fail_rate_limited)),

    /** 7001 */
    CDK_EXPIRED(uiTextOf(R.string.update_fail_cdk_expired)),

    /** 7002 */
    CDK_INVALID(uiTextOf(R.string.update_fail_cdk_invalid)),

    /** 7003 */
    CDK_QUOTA_EXHAUSTED(uiTextOf(R.string.update_fail_cdk_quota_exhausted)),

    /** 7004 */
    CDK_MISMATCHED(uiTextOf(R.string.update_fail_cdk_mismatched)),

    /** 7005 */
    CDK_BLOCKED(uiTextOf(R.string.update_fail_cdk_blocked)),

    /** 客户端前置校验，非服务端业务码：Mirror酱 无 CDK 解析不出下载地址，resolve 前拦下 */
    CDK_REQUIRED(uiTextOf(R.string.update_fail_cdk_required)),

    /** 8001；仅 Mirror酱 业务码产生，文案可点名引导切到 GitHub 源 */
    RESOURCE_NOT_FOUND(uiTextOf(R.string.update_fail_resource_not_found)),

    /** 8002 */
    INVALID_OS(uiTextOf(R.string.update_fail_invalid_os)),

    /** 8003 */
    INVALID_ARCH(uiTextOf(R.string.update_fail_invalid_arch)),

    /** 8004 */
    INVALID_CHANNEL(uiTextOf(R.string.update_fail_invalid_channel)),
    INVALID_RESPONSE(uiTextOf(R.string.update_fail_invalid_response)),
    NO_MATCHING_ASSET(uiTextOf(R.string.update_fail_no_matching_asset)),
    VERSION_INVALID(uiTextOf(R.string.update_fail_version_invalid)),
    UNKNOWN(uiTextOf(R.string.update_fail_unknown)),
}

/**
 * 版本检查的输入；检查永远是匿名的——CDK 只属于下载地址解析（[UpdateResolveRequest]）
 */
data class UpdateCheckRequest(
    val source: UpdateSource,
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
 * 每源一个实现；[source] 是路由键，只被 [UpdateService] 消费，实现自身不读 request.source。
 * 「检查匿名、CDK 只在解析带上」的约束长在两个请求类型上，与实现无关
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
