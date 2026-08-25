package com.aliothmoon.maafw.update

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

internal class GitHubUpdateSourceChecker(
    private val gateway: UpdateHttpGateway,
    private val apiBaseUrl: String = "https://api.github.com",
) : UpdateSourceChecker {

    override val source: UpdateSource = UpdateSource.GITHUB

    override suspend fun check(request: UpdateCheckRequest): SourceCheckResult = try {
        checkInternal(request)
    } catch (e: CancellationException) {
        throw e
    } catch (e: UpdateSourceException) {
        SourceCheckResult.Failed(e.reason, e.message)
    } catch (_: IllegalArgumentException) {
        SourceCheckResult.Failed(UpdateCheckFailure.NETWORK, "Invalid GitHub API URL")
    } catch (_: IOException) {
        SourceCheckResult.Failed(UpdateCheckFailure.NETWORK)
    } catch (e: Exception) {
        SourceCheckResult.Failed(UpdateCheckFailure.UNKNOWN, e.message)
    }

    private suspend fun checkInternal(request: UpdateCheckRequest): SourceCheckResult {
        if (UpdateVersion.parse(request.currentVersion) == null) {
            return SourceCheckResult.Failed(UpdateCheckFailure.VERSION_INVALID, request.currentVersion)
        }
        val repository = request.githubRepository?.trim()
            ?.takeIf(REPOSITORY_PATTERN::matches)
            ?: return SourceCheckResult.Failed(
                UpdateCheckFailure.MISSING_CONFIGURATION,
                "GitHub repository must be owner/repo",
            )

        val releases = mutableListOf<JsonObject>()
        for (page in 1..MAX_PAGES) {
            val response = gateway.get(buildUrl(repository, page), headers(request))
            val parsed = parseJsonArray(response.body)
            if (!response.statusCode.isSuccess()) {
                val failure = if (response.statusCode == 403 || response.statusCode == 429) {
                    UpdateCheckFailure.RATE_LIMITED
                } else {
                    UpdateCheckFailure.HTTP
                }
                val message = parseJsonObject(response.body)?.string("message")
                    ?: "GitHub returned HTTP ${response.statusCode}"
                return SourceCheckResult.Failed(failure, message)
            }
            if (parsed == null) return SourceCheckResult.Failed(UpdateCheckFailure.INVALID_RESPONSE)
            releases += parsed.mapNotNull { it as? JsonObject }
            if (parsed.size < PAGE_SIZE) break
        }

        val release = releases
            .mapNotNull { eligibleRelease(it, request.channel) }
            .maxByOrNull { it.second }
            ?: return SourceCheckResult.Failed(UpdateCheckFailure.NO_MATCHING_ASSET)
        val (candidate, version) = release
        val currentVersion = UpdateVersion.parse(request.currentVersion)
            ?: return SourceCheckResult.Failed(UpdateCheckFailure.VERSION_INVALID, request.currentVersion)
        if (version <= currentVersion) return SourceCheckResult.UpToDate(candidate.tag)

        val asset = selectAsset(candidate.assets, request.abi)
            ?: return SourceCheckResult.Failed(UpdateCheckFailure.NO_MATCHING_ASSET)

        return SourceCheckResult.UpdateAvailable(
            version = candidate.tag,
            downloadUrl = asset.downloadUrl,
            sha256 = asset.sha256,
            releaseNotesUrl = candidate.htmlUrl,
            releaseNotes = candidate.body,
        )
    }

    private fun eligibleRelease(
        raw: JsonObject,
        channel: UpdateChannel,
    ): Pair<GithubRelease, UpdateVersion>? {
        val tag = raw.string("tag_name") ?: return null
        val version = UpdateVersion.parse(tag) ?: return null
        val prerelease = raw.boolean("prerelease") ?: false
        if (!version.allowedFor(channel)) return null
        if (channel == UpdateChannel.STABLE && prerelease) return null
        return GithubRelease(
            tag = tag,
            htmlUrl = raw.string("html_url"),
            body = raw.string("body"),
            assets = (raw["assets"] as? JsonArray)
                ?.mapNotNull { it as? JsonObject }
                .orEmpty(),
        ) to version
    }

    private fun selectAsset(assets: List<JsonObject>, abi: AndroidAbi): GithubAsset? {
        val apkAssets = assets.mapNotNull(::asset).filter(GithubAsset::isApk)
        if (apkAssets.size == 1) return apkAssets.single()
        if (apkAssets.isEmpty()) return null

        val aliases = ABI_ALIASES.getValue(abi)
        return apkAssets
            .mapNotNull { asset ->
                val name = asset.name
                aliases.indexOfFirst { name.matchesAlias(it) }
                    .takeIf { it >= 0 }
                    ?.let { it to asset }
            }
            .minByOrNull { it.first }
            ?.second
    }

    private fun asset(raw: JsonObject): GithubAsset? {
        val apiUrl = raw.string("url")
        val browserUrl = raw.string("browser_download_url")
        val downloadUrl = browserUrl ?: apiUrl ?: return null
        return GithubAsset(
            name = raw.string("name") ?: downloadUrl.substringAfterLast('/'),
            downloadUrl = downloadUrl,
            sha256 = raw.string("digest")?.trim()?.takeIf(DIGEST_PATTERN::matches),
        )
    }

    private fun String.matchesAlias(alias: String): Boolean = Regex(
        """(^|[ _.\-/])${Regex.escape(alias)}($|[ _.\-/])""",
        RegexOption.IGNORE_CASE,
    ).containsMatchIn(this)

    private fun buildUrl(repository: String, page: Int): String {
        val (owner, repo) = repository.split('/')
        return apiBaseUrl.toHttpUrl().newBuilder()
            .addPathSegments("repos")
            .addPathSegment(owner)
            .addPathSegment(repo)
            .addPathSegment("releases")
            .addQueryParameter("per_page", PAGE_SIZE.toString())
            .addQueryParameter("page", page.toString())
            .build()
            .toString()
    }

    private fun headers(request: UpdateCheckRequest): Map<String, String> = buildMap {
        put("Accept", "application/vnd.github+json")
        put("X-GitHub-Api-Version", "2022-11-28")
        put("User-Agent", "MaaFwApp")
        request.githubToken?.trim()?.takeIf(String::isNotBlank)?.let {
            put("Authorization", "Bearer $it")
        }
    }

    private fun Int.isSuccess(): Boolean = this in 200..299

    private data class GithubRelease(
        val tag: String,
        val htmlUrl: String?,
        val body: String?,
        val assets: List<JsonObject>,
    )

    private data class GithubAsset(
        val name: String,
        val downloadUrl: String,
        val sha256: String?,
    ) {
        val isApk: Boolean get() = name.isApkUrl() || downloadUrl.isApkUrl()
    }

    private companion object {
        const val PAGE_SIZE = 100
        const val MAX_PAGES = 3
        val REPOSITORY_PATTERN = Regex("""^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$""")
        val DIGEST_PATTERN = Regex("""^sha256:[0-9a-fA-F]{64}$""")
        val ABI_ALIASES: Map<AndroidAbi, List<String>> = mapOf(
            AndroidAbi.ARM64 to listOf("arm64-v8a", "arm64", "aarch64"),
            AndroidAbi.X86_64 to listOf("x86_64", "x64", "amd64"),
            AndroidAbi.ARM to listOf("armeabi-v7a", "arm"),
            AndroidAbi.X86 to listOf("x86"),
        )
    }
}
