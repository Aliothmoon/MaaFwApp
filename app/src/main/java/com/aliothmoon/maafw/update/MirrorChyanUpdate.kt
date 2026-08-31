package com.aliothmoon.maafw.update

import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.i18n.uiTextFromFramework
import com.aliothmoon.maafw.i18n.uiTextOf
import com.aliothmoon.maafw.util.int
import com.aliothmoon.maafw.util.parseJsonObject
import com.aliothmoon.maafw.util.string
import okhttp3.HttpUrl.Companion.toHttpUrl
import timber.log.Timber
import kotlin.coroutines.cancellation.CancellationException

/**
 * MirrorChyan 的 `/api/resources/{rid}/latest`：检查与解析共用端点与解析，
 * 但各自是小用例——检查只比版本，解析只出下载端点
 */
internal class MirrorChyanLatestApi(
    private val gateway: OkHttpUpdateHttpGateway,
    private val userAgent: String,
) {
    companion object {
        private const val BASE_URL = "https://mirrorchyan.com"
    }

    internal data class Latest(
        val version: String,
        val url: String?,
        val sha256: String?,
        val releaseNote: String?,
    )

    /**
     * body 业务码优先于 HTTP 状态：MirrorChyan 用 404 + `{"code":8001}` 表示「资源不存在」，
     * 先看状态码会把 8001 吞成 HTTP 失败，上游失去兜底机会
     */
    suspend fun latest(
        rid: String,
        channel: UpdateChannel,
        abi: AndroidAbi,
        currentVersion: String,
        cdk: String?,
    ): UpdateSourceOutcome<Latest> {
        val url = BASE_URL.toHttpUrl().newBuilder()
            .addPathSegments("api/resources")
            .addPathSegment(rid)
            .addPathSegment("latest")
            .addQueryParameter("channel", channel.name.lowercase())
            .addQueryParameter("current_version", currentVersion)
            .addQueryParameter("os", "android")
            .addQueryParameter("arch", abi.mirrorArch)
            .addQueryParameter("user_agent", userAgent)
            .apply {
                cdk?.trim()?.takeIf(String::isNotBlank)?.let { addQueryParameter("cdk", it) }
            }
            .build()
            .toString()
        val response = gateway.get(url, mapOf("User-Agent" to userAgent, "Accept" to "application/json"))
        if (response.truncated) {
            return UpdateSourceOutcome.Failed(UpdateCheckFailure.INVALID_RESPONSE)
        }
        val root = parseJsonObject(response.body)
        val bodyCode = root?.int("code")
        if (bodyCode != null && bodyCode != 0) {
            val serverMessage = root.string("msg")
            return UpdateSourceOutcome.Failed(
                businessFailure(bodyCode),
                detail = serverMessage?.let(::uiTextFromFramework),
            )
        }
        if (!response.statusCode.isSuccess()) {
            val serverMessage = root?.string("msg")
            return UpdateSourceOutcome.Failed(
                UpdateCheckFailure.HTTP,
                detail = serverMessage?.let(::uiTextFromFramework)
                    ?: uiTextOf(R.string.update_detail_http_status, response.statusCode),
            )
        }
        if (root == null || bodyCode == null) {
            return UpdateSourceOutcome.Failed(UpdateCheckFailure.INVALID_RESPONSE)
        }
        val data = root["data"] as? kotlinx.serialization.json.JsonObject
            ?: return UpdateSourceOutcome.Failed(UpdateCheckFailure.INVALID_RESPONSE)
        val version = data.string("version_name")
            ?: return UpdateSourceOutcome.Failed(UpdateCheckFailure.INVALID_RESPONSE)
        return UpdateSourceOutcome.Ok(
            Latest(
                version = version,
                url = data.string("url"),
                sha256 = data.string("sha256"),
                releaseNote = data.string("release_note"),
            ),
        )
    }

    // 错误检查缺失
    private fun businessFailure(code: Int): UpdateCheckFailure = when (code) {
        7003 -> UpdateCheckFailure.RATE_LIMITED
        8001 -> UpdateCheckFailure.RESOURCE_NOT_FOUND
        else -> UpdateCheckFailure.UNKNOWN
    }

    private fun Int.isSuccess(): Boolean = this in 200..299
}

internal class MirrorChyanUpdateClient(
    private val api: MirrorChyanLatestApi,
) : UpdateSourceClient {

    override val source: UpdateSource = UpdateSource.MIRRORCHYAN

    override suspend fun check(request: UpdateCheckRequest): UpdateCheckResult = try {
        val rid = request.mirrorchyanRid?.trim()?.takeIf(String::isNotBlank)
            ?: return UpdateCheckResult.SourceFailed(source, UpdateCheckFailure.MISSING_CONFIGURATION)
        val currentVersion = UpdateVersion.parse(request.currentVersion)
            ?: return UpdateCheckResult.SourceFailed(
                source,
                UpdateCheckFailure.VERSION_INVALID,
                detail = uiTextFromFramework(request.currentVersion),
            )
        val latest = when (val outcome = api.latest(rid, request.channel, request.abi, request.currentVersion, cdk = null)) {
            is UpdateSourceOutcome.Failed -> return UpdateCheckResult.SourceFailed(source, outcome.reason, detail = outcome.detail)
            is UpdateSourceOutcome.Ok -> outcome.value
        }
        val latestVersion = UpdateVersion.parse(latest.version)
            ?: return UpdateCheckResult.SourceFailed(
                source,
                UpdateCheckFailure.VERSION_INVALID,
                detail = uiTextFromFramework(latest.version),
            )
        if (latestVersion <= currentVersion) {
            UpdateCheckResult.UpToDate(source, latest.version)
        } else {
            UpdateCheckResult.UpdateAvailable(source, UpdateInfo(version = latest.version, releaseNotes = latest.releaseNote))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.tag("UpdateCheck").w(e, "%s check failed", source)
        UpdateCheckResult.SourceFailed(source, UpdateCheckFailure.NETWORK)
    }

    override suspend fun resolve(request: UpdateResolveRequest): UpdateResolveResult = try {
        val rid = request.mirrorchyanRid?.trim()?.takeIf(String::isNotBlank)
            ?: return UpdateResolveResult.Failed(source, UpdateCheckFailure.MISSING_CONFIGURATION)
        val latest = when (val outcome = api.latest(rid, request.channel, request.abi, request.currentVersion, cdk = request.mirrorchyanCdk)) {
            is UpdateSourceOutcome.Failed -> return UpdateResolveResult.Failed(source, outcome.reason, outcome.detail)
            is UpdateSourceOutcome.Ok -> outcome.value
        }
        val url = latest.url
        if (url == null || !url.isApkUrl()) {
            return UpdateResolveResult.Failed(source, UpdateCheckFailure.NO_MATCHING_ASSET)
        }
        UpdateResolveResult.Resolved(
            ResolvedUpdate(source = source, version = latest.version, downloadUrl = url, sha256 = latest.sha256),
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.tag("UpdateResolve").w(e, "%s resolve failed", source)
        UpdateResolveResult.Failed(source, UpdateCheckFailure.NETWORK)
    }
}
