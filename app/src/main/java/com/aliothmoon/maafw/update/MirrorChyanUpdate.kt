package com.aliothmoon.maafw.update

import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.i18n.uiTextFromFramework
import com.aliothmoon.maafw.i18n.uiTextOf
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * MirrorChyan 的 `/api/resources/{rid}/latest`：检查与解析共用端点与解析，
 * 但各自是小用例——检查只比版本，解析只出下载端点
 */
internal class MirrorChyanLatestApi(
    private val gateway: OkHttpUpdateHttpGateway,
    private val baseUrl: String = "https://mirrorchyan.com",
    private val userAgent: String = "MaaFwApp Android",
) {
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
    ): Latest {
        val url = baseUrl.toHttpUrl().newBuilder()
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
        val root = parseJsonObject(response.body)
        val bodyCode = root?.int("code")
        if (bodyCode != null && bodyCode != 0) {
            val serverMessage = root.string("msg")
            throw UpdateSourceException(
                businessFailure(bodyCode),
                serverMessage,
                detail = serverMessage?.let(::uiTextFromFramework),
            )
        }
        if (!response.statusCode.isSuccess()) {
            val serverMessage = root?.string("msg")
            throw UpdateSourceException(
                UpdateCheckFailure.HTTP,
                serverMessage ?: "HTTP ${response.statusCode}",
                detail = serverMessage?.let(::uiTextFromFramework)
                    ?: uiTextOf(R.string.update_detail_http_status, response.statusCode),
            )
        }
        if (root == null || bodyCode == null) {
            throw UpdateSourceException(UpdateCheckFailure.INVALID_RESPONSE, null)
        }
        val data = root["data"] as? kotlinx.serialization.json.JsonObject
            ?: throw UpdateSourceException(UpdateCheckFailure.INVALID_RESPONSE, null)
        val version = data.string("version_name")
            ?: throw UpdateSourceException(UpdateCheckFailure.INVALID_RESPONSE, null)
        return Latest(
            version = version,
            url = data.string("url"),
            sha256 = data.string("sha256"),
            releaseNote = data.string("release_note"),
        )
    }

    private fun businessFailure(code: Int): UpdateCheckFailure = when (code) {
        7003 -> UpdateCheckFailure.RATE_LIMITED
        8001 -> UpdateCheckFailure.RESOURCE_NOT_FOUND
        else -> UpdateCheckFailure.UNKNOWN
    }

    private fun Int.isSuccess(): Boolean = this in 200..299
}

internal class MirrorChyanUpdateVersionChecker(
    private val api: MirrorChyanLatestApi,
) : UpdateVersionChecker {

    override val source: UpdateSource = UpdateSource.MIRRORCHYAN

    override suspend fun check(request: UpdateCheckRequest): UpdateCheckResult = try {
        checkInternal(request)
    } catch (e: CancellationException) {
        throw e
    } catch (e: UpdateSourceException) {
        UpdateCheckResult.SourceFailed(source, e.reason, detail = e.detail)
    } catch (_: IllegalArgumentException) {
        UpdateCheckResult.SourceFailed(source, UpdateCheckFailure.NETWORK)
    } catch (_: IOException) {
        UpdateCheckResult.SourceFailed(source, UpdateCheckFailure.NETWORK)
    } catch (e: Exception) {
        UpdateCheckResult.SourceFailed(source, UpdateCheckFailure.UNKNOWN)
    }

    private suspend fun checkInternal(request: UpdateCheckRequest): UpdateCheckResult {
        val rid = request.mirrorchyanRid?.trim()?.takeIf(String::isNotBlank)
            ?: return UpdateCheckResult.SourceFailed(source, UpdateCheckFailure.MISSING_CONFIGURATION)
        val currentVersion = UpdateVersion.parse(request.currentVersion)
            ?: return UpdateCheckResult.SourceFailed(
                source,
                UpdateCheckFailure.VERSION_INVALID,
                detail = uiTextFromFramework(request.currentVersion),
            )
        val latest = api.latest(rid, request.channel, request.abi, request.currentVersion, cdk = null)
        val latestVersion = UpdateVersion.parse(latest.version)
            ?: return UpdateCheckResult.SourceFailed(
                source,
                UpdateCheckFailure.VERSION_INVALID,
                detail = uiTextFromFramework(latest.version),
            )
        return if (latestVersion <= currentVersion) {
            UpdateCheckResult.UpToDate(source, latest.version)
        } else {
            UpdateCheckResult.UpdateAvailable(source, UpdateInfo(version = latest.version, releaseNotes = latest.releaseNote))
        }
    }
}

internal class MirrorChyanUpdateUrlResolver(
    private val api: MirrorChyanLatestApi,
) : UpdateDownloadUrlResolver {

    override val source: UpdateSource = UpdateSource.MIRRORCHYAN

    override suspend fun resolve(request: UpdateResolveRequest): UpdateResolveResult = try {
        resolveInternal(request)
    } catch (e: CancellationException) {
        throw e
    } catch (e: UpdateSourceException) {
        UpdateResolveResult.Failed(source, e.reason, e.detail)
    } catch (_: IllegalArgumentException) {
        UpdateResolveResult.Failed(source, UpdateCheckFailure.NETWORK)
    } catch (_: IOException) {
        UpdateResolveResult.Failed(source, UpdateCheckFailure.NETWORK)
    } catch (e: Exception) {
        UpdateResolveResult.Failed(source, UpdateCheckFailure.UNKNOWN)
    }

    private suspend fun resolveInternal(request: UpdateResolveRequest): UpdateResolveResult {
        val rid = request.mirrorchyanRid?.trim()?.takeIf(String::isNotBlank)
            ?: return UpdateResolveResult.Failed(source, UpdateCheckFailure.MISSING_CONFIGURATION)
        val latest = api.latest(rid, request.channel, request.abi, request.currentVersion, cdk = request.mirrorchyanCdk)
        val url = latest.url
        if (url == null || !url.isApkUrl()) {
            return UpdateResolveResult.Failed(source, UpdateCheckFailure.NO_MATCHING_ASSET)
        }
        return UpdateResolveResult.Resolved(
            ResolvedUpdate(source = source, version = latest.version, downloadUrl = url, sha256 = latest.sha256),
        )
    }
}
