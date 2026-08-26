package com.aliothmoon.maafw.update

import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import timber.log.Timber
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

internal class MirrorChyanUpdateSourceChecker(
    private val gateway: UpdateHttpGateway,
    private val baseUrl: String = "https://mirrorchyan.com",
    private val userAgent: String = "MaaFwApp Android",
) : UpdateSourceChecker {

    override val source: UpdateSource = UpdateSource.MIRROR_CHYAN

    override suspend fun check(request: UpdateCheckRequest): SourceCheckResult = try {
        checkInternal(request)
    } catch (e: CancellationException) {
        throw e
    } catch (e: UpdateSourceException) {
        SourceCheckResult.Failed(e.reason, e.message)
    } catch (_: IllegalArgumentException) {
        SourceCheckResult.Failed(UpdateCheckFailure.NETWORK, "Invalid update source URL")
    } catch (_: IOException) {
        SourceCheckResult.Failed(UpdateCheckFailure.NETWORK)
    } catch (e: Exception) {
        SourceCheckResult.Failed(UpdateCheckFailure.UNKNOWN, e.message)
    }

    private suspend fun checkInternal(request: UpdateCheckRequest): SourceCheckResult {
        val rid = request.mirrorChyanRid?.trim()?.takeIf(String::isNotBlank)
            ?: return SourceCheckResult.Failed(
                UpdateCheckFailure.MISSING_CONFIGURATION,
                "MirrorChyan resource id is missing",
            )
        if (UpdateVersion.parse(request.currentVersion) == null) {
            return SourceCheckResult.Failed(UpdateCheckFailure.VERSION_INVALID, request.currentVersion)
        }

        val response = gateway.get(
            url = buildUrl(rid, request),
            headers = mapOf("User-Agent" to userAgent, "Accept" to "application/json"),
        )
        val root = parseJsonObject(response.body)
        // MirrorChyan 用 HTTP 404 + body {"code":8001,...} 表示「资源不存在」，
        // 服务端约定。先看 body 的业务码再判定 HTTP 状态，否则 8001 会被吞成 HTTP reason，
        // 失去（也跳）过 GitHub fallback 的机会。其它业务码（例如 7003 rate-limited）
        // 同样以 body code 为准。
        val bodyCode = root?.int("code")
        if (bodyCode != null && bodyCode != 0) {
            return SourceCheckResult.Failed(businessFailure(bodyCode), root.string("msg"))
        }
        Timber.tag("UpdateCheck").w(
            "mirrorchyan http=%d bodyCode=%s",
            response.statusCode, bodyCode,
        )
        if (!response.statusCode.isSuccess()) {
            return SourceCheckResult.Failed(
                UpdateCheckFailure.HTTP,
                root?.string("msg") ?: "MirrorChyan returned HTTP ${response.statusCode}",
            )
        }
        if (root == null || bodyCode == null) {
            return SourceCheckResult.Failed(UpdateCheckFailure.INVALID_RESPONSE, "Missing business code")
        }

        val data = root["data"] as? JsonObject
            ?: return SourceCheckResult.Failed(UpdateCheckFailure.INVALID_RESPONSE, "Missing update data")
        val latest = data.string("version_name")
            ?: return SourceCheckResult.Failed(UpdateCheckFailure.INVALID_RESPONSE, "Missing version_name")
        val latestVersion = UpdateVersion.parse(latest)
            ?: return SourceCheckResult.Failed(UpdateCheckFailure.VERSION_INVALID, latest)
        val currentVersion = UpdateVersion.parse(request.currentVersion)
            ?: return SourceCheckResult.Failed(UpdateCheckFailure.VERSION_INVALID, request.currentVersion)
        if (latestVersion <= currentVersion) return SourceCheckResult.UpToDate(latest)

        val downloadUrl = data.string("url")
        if (downloadUrl == null || !downloadUrl.isApkUrl()) {
            return SourceCheckResult.Failed(UpdateCheckFailure.NO_MATCHING_ASSET)
        }
        return SourceCheckResult.UpdateAvailable(
            version = latest,
            downloadUrl = downloadUrl,
            sha256 = data.string("sha256"),
            releaseNotesUrl = null,
            releaseNotes = data.string("release_note"),
        )
    }

    private fun buildUrl(rid: String, request: UpdateCheckRequest): String =
        baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/resources")
            .addPathSegment(rid)
            .addPathSegment("latest")
            .addQueryParameter("channel", request.channel.name.lowercase())
            .addQueryParameter("current_version", request.currentVersion)
            .addQueryParameter("os", "android")
            .addQueryParameter("arch", request.abi.mirrorArch)
            .addQueryParameter("user_agent", userAgent)
            .apply {
                request.mirrorChyanCdk
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                    ?.let { addQueryParameter("cdk", it) }
            }
            .build()
            .toString()

    private fun businessFailure(code: Int): UpdateCheckFailure = when (code) {
        7003 -> UpdateCheckFailure.RATE_LIMITED
        8001 -> UpdateCheckFailure.RESOURCE_NOT_FOUND
        else -> UpdateCheckFailure.UNKNOWN
    }

    private fun Int.isSuccess(): Boolean = this in 200..299

}
