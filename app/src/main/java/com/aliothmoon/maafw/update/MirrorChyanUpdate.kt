package com.aliothmoon.maafw.update

import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.constant.MiscConstants
import com.aliothmoon.maafw.i18n.uiTextFromFramework
import com.aliothmoon.maafw.i18n.uiTextOf
import com.aliothmoon.maafw.util.HttpClientHelper
import com.aliothmoon.maafw.util.int
import com.aliothmoon.maafw.util.parseJsonObject
import com.aliothmoon.maafw.util.readBody
import com.aliothmoon.maafw.util.string
import timber.log.Timber
import kotlin.coroutines.cancellation.CancellationException

/**
 * MirrorChyan 的 `/api/resources/{rid}/latest`：检查与解析共用端点与解析，
 * 但各自是小用例——检查只比版本，解析只出下载端点
 */
internal class MirrorChyanLatestApi(
    private val helper: HttpClientHelper,
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
    ): UpdateSourceOutcome<Latest> {
        val resp = helper.get(
            apiUrl(rid),
            buildMap {
                put("channel", channel.name.lowercase())
                put("current_version", currentVersion)
                put("os", "android")
                put("arch", abi.mirrorArch)
                put("user_agent", MiscConstants.UA)
                cdk?.trim()?.takeIf(String::isNotBlank)?.let {
                    put("cdk", it)
                }
            }
        )
        val sc = resp.code
        val body = resp.readBody()
        val root = parseJsonObject(body)
        val bodyCode = root?.int("code")
        if (bodyCode != null && bodyCode != 0) {
            val reason = businessFailure(bodyCode)
            Timber.e("latest api error code=%d msg=%s", bodyCode, root.string("msg"))
            // 已知业务码用固定文案；未知码把服务端 msg 原样透出
            return UpdateSourceOutcome.Failed(
                reason,
                detail = root.string("msg")
                    .takeIf { reason == UpdateCheckFailure.UNKNOWN }
                    ?.let(::uiTextFromFramework),
            )
        }
        if (!sc.isSuccess()) {
            val serverMessage = root?.string("msg")
            return UpdateSourceOutcome.Failed(
                UpdateCheckFailure.HTTP,
                detail = serverMessage?.let(::uiTextFromFramework)
                    ?: uiTextOf(R.string.update_detail_http_status, sc),
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

    /** 业务码全集对齐 MaaMeow 的 UpdateError.fromCode；7xxx 只会出现在带 CDK 的 resolve 阶段 */
    private fun businessFailure(code: Int): UpdateCheckFailure = when (code) {
        7001 -> UpdateCheckFailure.CDK_EXPIRED
        7002 -> UpdateCheckFailure.CDK_INVALID
        7003 -> UpdateCheckFailure.CDK_QUOTA_EXHAUSTED
        7004 -> UpdateCheckFailure.CDK_MISMATCHED
        7005 -> UpdateCheckFailure.CDK_BLOCKED
        8001 -> UpdateCheckFailure.RESOURCE_NOT_FOUND
        8002 -> UpdateCheckFailure.INVALID_OS
        8003 -> UpdateCheckFailure.INVALID_ARCH
        8004 -> UpdateCheckFailure.INVALID_CHANNEL
        else -> UpdateCheckFailure.UNKNOWN
    }

    private fun Int.isSuccess(): Boolean = this in 200..299

    private fun apiUrl(rid: String): String = "https://mirrorchyan.com/api/resources/$rid/latest"
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
        // 无 CDK 时服务端不回 url，会被误报成 NO_MATCHING_ASSET，发请求前拦下
        if (request.mirrorchyanCdk.isNullOrBlank()) {
            return UpdateResolveResult.Failed(source, UpdateCheckFailure.CDK_REQUIRED)
        }
        val outcome = api.latest(
            rid,
            request.channel,
            request.abi,
            request.currentVersion,
            cdk = request.mirrorchyanCdk
        )
        val latest = when (outcome) {
            is UpdateSourceOutcome.Failed ->
                return UpdateResolveResult.Failed(source, outcome.reason, outcome.detail)
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
