package com.aliothmoon.maafw.update

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
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
        val currentVersion = UpdateVersion.parse(request.currentVersion)
            ?: return SourceCheckResult.Failed(UpdateCheckFailure.VERSION_INVALID, request.currentVersion)
        val repository = request.githubRepository?.trim()
            ?.takeIf(REPOSITORY_PATTERN::matches)
            ?: return SourceCheckResult.Failed(
                UpdateCheckFailure.MISSING_CONFIGURATION,
                "GitHub repository must be owner/repo",
            )

        val releases = mutableListOf<GithubRelease>()
        for (page in 1..MAX_PAGES) {
            val response = gateway.get(buildApiUrl(repository, page), apiHeaders(request))
            if (!response.statusCode.isSuccess()) {
                if (response.statusCode == TOO_MANY_REQUESTS) {
                    return checkViaHtml(repository, request.channel, currentVersion, request.abi)
                }
                return apiFailure(response)
            }

            val parsed = parseJsonArray(response.body)
                ?: return SourceCheckResult.Failed(UpdateCheckFailure.INVALID_RESPONSE)
            if (parsed.isEmpty()) break
            releases += parsed.mapNotNull { it as? JsonObject }.mapNotNull(::release)
            if (parsed.size < PAGE_SIZE) break
        }

        val candidate = releases
            .mapNotNull { eligibleRelease(it, request.channel) }
            .maxByOrNull { it.second }
            ?: return SourceCheckResult.Failed(UpdateCheckFailure.NO_MATCHING_ASSET)
        return resultFor(candidate.first, candidate.second, currentVersion, request.abi)
    }

    private suspend fun checkViaHtml(
        repository: String,
        channel: UpdateChannel,
        currentVersion: UpdateVersion,
        abi: AndroidAbi,
    ): SourceCheckResult {
        val releases = mutableListOf<GithubRelease>()
        for (page in 1..MAX_PAGES) {
            val pageUrl = buildReleasesPageUrl(repository, page)
            val response = gateway.get(pageUrl, htmlHeaders())
            if (!response.statusCode.isSuccess()) {
                throw UpdateSourceException(
                    reason = UpdateCheckFailure.RATE_LIMITED,
                    message = "GitHub API returned HTTP $TOO_MANY_REQUESTS; " +
                            "HTML fallback returned HTTP ${response.statusCode}",
                )
            }

            val document = Jsoup.parse(response.body, pageUrl)
            releases += document.select("section[id^=release-]").mapNotNull(::release)
            if (document.selectFirst("a.next_page[rel=next]") == null) break
        }

        val candidate = releases
            .mapNotNull { eligibleRelease(it, channel) }
            .maxByOrNull { it.second }
            ?: return SourceCheckResult.Failed(UpdateCheckFailure.NO_MATCHING_ASSET)
        val (release, version) = candidate
        if (version <= currentVersion) return SourceCheckResult.UpToDate(release.tag)

        val releaseWithAssets = release.copy(assets = fetchHtmlAssets(repository, release))
        return resultFor(releaseWithAssets, version, currentVersion, abi)
    }

    private suspend fun fetchHtmlAssets(
        repository: String,
        release: GithubRelease,
    ): List<GithubAsset> {
        val assetsUrl = release.assetsUrl ?: buildAssetsUrl(repository, release.tag)
        val response = gateway.get(assetsUrl, htmlHeaders())
        if (!response.statusCode.isSuccess()) {
            throw UpdateSourceException(
                reason = UpdateCheckFailure.RATE_LIMITED,
                message = "GitHub API returned HTTP $TOO_MANY_REQUESTS; " +
                        "HTML asset fallback returned HTTP ${response.statusCode}",
            )
        }

        val document = Jsoup.parse(response.body, assetsUrl)
        return document.select("li.Box-row a[href]").mapNotNull { link ->
            val url = link.absUrl("href").toHttpUrlOrNull() ?: return@mapNotNull null
            if (!url.encodedPath.contains(RELEASE_DOWNLOAD_PATH)) return@mapNotNull null

            val name = link.selectFirst(".Truncate-text")
                ?.text()
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?: url.pathSegments.last()
            val row = link.closest("li") ?: link
            val digest = HTML_DIGEST_PATTERN.find(row.text())?.value
            GithubAsset(
                name = name,
                downloadUrl = url.toString(),
                sha256 = digest?.takeIf(DIGEST_PATTERN::matches),
            )
        }
    }

    private fun apiFailure(response: UpdateHttpResponse): SourceCheckResult.Failed {
        val failure = if (response.statusCode == FORBIDDEN) {
            UpdateCheckFailure.RATE_LIMITED
        } else {
            UpdateCheckFailure.HTTP
        }
        val message = parseJsonObject(response.body)?.string("message")
            ?: "GitHub returned HTTP ${response.statusCode}"
        return SourceCheckResult.Failed(failure, message)
    }

    private fun resultFor(
        candidate: GithubRelease,
        version: UpdateVersion,
        currentVersion: UpdateVersion,
        abi: AndroidAbi,
    ): SourceCheckResult {
        if (version <= currentVersion) return SourceCheckResult.UpToDate(candidate.tag)

        val asset = selectAsset(candidate.assets, abi)
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
        candidate: GithubRelease,
        channel: UpdateChannel,
    ): Pair<GithubRelease, UpdateVersion>? {
        val version = UpdateVersion.parse(candidate.tag) ?: return null
        if (!version.allowedFor(channel)) return null
        if (channel == UpdateChannel.STABLE && candidate.prerelease) return null
        return candidate to version
    }

    private fun release(raw: JsonObject): GithubRelease? {
        val tag = raw.string("tag_name") ?: return null
        return GithubRelease(
            tag = tag,
            htmlUrl = raw.string("html_url"),
            body = raw.string("body"),
            assets = (raw["assets"] as? JsonArray)
                ?.mapNotNull { it as? JsonObject }
                ?.mapNotNull(::asset)
                .orEmpty(),
            assetsUrl = null,
            prerelease = raw.boolean("prerelease") ?: false,
        )
    }

    private fun release(section: Element): GithubRelease? {
        val tag = section.selectFirst("h2.sr-only")
            ?.text()
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: return null
        return GithubRelease(
            tag = tag,
            htmlUrl = section.selectFirst("a[href*=/releases/tag/]")
                ?.absUrl("href")
                ?.takeIf(String::isNotBlank),
            body = section.selectFirst("div.markdown-body")?.text()?.trim(),
            assets = emptyList(),
            assetsUrl = section.selectFirst("include-fragment[src*=expanded_assets]")
                ?.absUrl("src")
                ?.takeIf(String::isNotBlank),
            prerelease = section.selectFirst("span.Label:containsOwn(Pre-release)") != null,
        )
    }

    private fun selectAsset(assets: List<GithubAsset>, abi: AndroidAbi): GithubAsset? {
        val apkAssets = assets.filter(GithubAsset::isApk)
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

    private fun buildApiUrl(repository: String, page: Int): String {
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

    private fun buildReleasesPageUrl(repository: String, page: Int): String =
        GITHUB_WEB_BASE_URL.toHttpUrl().newBuilder()
            .addPathSegments("$repository/releases")
            .addQueryParameter("page", page.toString())
            .build()
            .toString()

    private fun buildAssetsUrl(repository: String, tag: String): String =
        GITHUB_WEB_BASE_URL.toHttpUrl().newBuilder()
            .addPathSegments("$repository/releases/expanded_assets")
            .addPathSegment(tag)
            .build()
            .toString()

    private fun apiHeaders(request: UpdateCheckRequest): Map<String, String> = buildMap {
        put("Accept", "application/vnd.github+json")
        put("X-GitHub-Api-Version", "2022-11-28")
        put("User-Agent", "MaaFwApp")
        request.githubToken?.trim()?.takeIf(String::isNotBlank)?.let {
            put("Authorization", "Bearer $it")
        }
    }

    private fun htmlHeaders(): Map<String, String> = mapOf(
        "Accept" to "text/html",
        "User-Agent" to "MaaFwApp",
    )

    private fun Int.isSuccess(): Boolean = this in 200..299

    private data class GithubRelease(
        val tag: String,
        val htmlUrl: String?,
        val body: String?,
        val assets: List<GithubAsset>,
        val assetsUrl: String?,
        val prerelease: Boolean = false,
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
        const val TOO_MANY_REQUESTS = 429
        const val FORBIDDEN = 403
        const val GITHUB_WEB_BASE_URL = "https://github.com"
        const val RELEASE_DOWNLOAD_PATH = "/releases/download/"
        val REPOSITORY_PATTERN = Regex("""^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$""")
        val DIGEST_PATTERN = Regex("""^sha256:[0-9a-fA-F]{64}$""")
        val HTML_DIGEST_PATTERN = Regex("""sha256:[0-9a-fA-F]{64}""")
        val ABI_ALIASES: Map<AndroidAbi, List<String>> = mapOf(
            AndroidAbi.ARM64 to listOf("arm64-v8a", "arm64", "aarch64"),
            AndroidAbi.X86_64 to listOf("x86_64", "x64", "amd64"),
            AndroidAbi.ARM to listOf("armeabi-v7a", "arm"),
            AndroidAbi.X86 to listOf("x86"),
        )
    }
}
