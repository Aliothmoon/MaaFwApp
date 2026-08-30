package com.aliothmoon.maafw.update

import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubUpdateSourceCheckerTest {

    private fun checker(gateway: UpdateHttpGateway) = GitHubUpdateSourceChecker(gateway)

    private fun request(
        repository: String? = "maaxyz/example",
        currentVersion: String = "1.0.0",
        abi: AndroidAbi = AndroidAbi.ARM64,
        channel: UpdateChannel = UpdateChannel.STABLE,
        token: String? = null,
    ) = UpdateCheckRequest(
        currentVersion = currentVersion,
        githubRepository = repository,
        abi = abi,
        channel = channel,
        githubToken = token,
    )

    @Test
    fun `highest channel eligible stable release and abi asset are selected`() = runBlocking {
        val gateway = RecordingUpdateHttpGateway(
            UpdateHttpResponse(
                200,
                releases(
                    release("v2.0.0-beta.1", prerelease = true),
                    release(
                        "v1.5.0",
                        assets = assets(
                            asset("app-x86_64.apk", "https://example.com/x86_64"),
                            asset(
                                "app-arm64-v8a.apk",
                                "https://example.com/arm64",
                                digest = "sha256:" + "a".repeat(64),
                            ),
                        ),
                    ),
                    release("v1.2.0"),
                ),
            ),
        )

        val result = checker(gateway).check(request())

        assertEquals(
            SourceCheckResult.UpdateAvailable(
                version = "v1.5.0",
                downloadUrl = "https://example.com/app-arm64-v8a.apk",
                sha256 = "sha256:" + "a".repeat(64),
                releaseNotesUrl = "https://github.com/maaxyz/example/releases/tag/v1.5.0",
                releaseNotes = "Release 1.5.0",
            ),
            result,
        )
        val url = gateway.requests.single().first.toHttpUrl()
        assertEquals("/repos/maaxyz/example/releases", url.encodedPath)
        assertEquals("100", url.queryParameter("per_page"))
        assertEquals("1", url.queryParameter("page"))
    }

    @Test
    fun `one apk is universal`() = runBlocking {
        val gateway = RecordingUpdateHttpGateway(
            UpdateHttpResponse(
                200,
                releases(
                    release(
                        "v2.0.0",
                        assets = assets(asset("MaaFwApp.apk", "https://example.com/universal")),
                    ),
                ),
            ),
        )

        val result = checker(gateway).check(request(abi = AndroidAbi.X86))

        assertEquals(
            "https://example.com/MaaFwApp.apk",
            (result as SourceCheckResult.UpdateAvailable).downloadUrl,
        )
    }

    @Test
    fun `github token uses bearer authorization`() = runBlocking {
        val gateway = RecordingUpdateHttpGateway(
            UpdateHttpResponse(200, releases(release("v1.1.0", assets = assets(asset("app.apk"))))),
        )

        checker(gateway).check(request(token = "token"))

        assertEquals("Bearer token", gateway.requests.single().second["Authorization"])
        assertEquals("2022-11-28", gateway.requests.single().second["X-GitHub-Api-Version"])
    }

    @Test
    fun `api 429 falls back to release html`() = runBlocking {
        val gateway = RecordingUpdateHttpGateway(
            UpdateHttpResponse(429, """{"message":"rate limited"}"""),
            UpdateHttpResponse(
                200,
                releasesHtml(
                    releaseHtmlSection("v2.0.0-beta.1", prerelease = true),
                    releaseHtmlSection("v1.5.0"),
                ),
            ),
            UpdateHttpResponse(
                200,
                assetsHtml(
                    assetHtml("app-x86_64.apk"),
                    assetHtml("app-arm64-v8a.apk", digest = "sha256:" + "a".repeat(64)),
                ),
            ),
        )

        val result = checker(gateway).check(request(token = "token"))

        assertEquals(
            SourceCheckResult.UpdateAvailable(
                version = "v1.5.0",
                downloadUrl = "https://github.com/maaxyz/example/releases/download/v1.5.0/app-arm64-v8a.apk",
                sha256 = "sha256:" + "a".repeat(64),
                releaseNotesUrl = "https://github.com/maaxyz/example/releases/tag/v1.5.0",
                releaseNotes = "Release v1.5.0",
            ),
            result,
        )
        assertEquals("Bearer token", gateway.requests.first().second["Authorization"])
        assertEquals("/repos/maaxyz/example/releases", gateway.requests[0].first.toHttpUrl().encodedPath)
        assertEquals("/maaxyz/example/releases", gateway.requests[1].first.toHttpUrl().encodedPath)
        assertEquals("text/html", gateway.requests[1].second["Accept"])
        assertEquals(null, gateway.requests[1].second["Authorization"])
        assertEquals(
            "/maaxyz/example/releases/expanded_assets/v1.5.0",
            gateway.requests[2].first.toHttpUrl().encodedPath,
        )
    }

    @Test
    fun `api 403 stays rate limited without html fallback`() = runBlocking {
        val gateway = RecordingUpdateHttpGateway(
            UpdateHttpResponse(403, """{"message":"rate limited"}"""),
        )

        assertEquals(
            SourceCheckResult.Failed(UpdateCheckFailure.RATE_LIMITED, "rate limited"),
            checker(gateway).check(request()),
        )
        assertEquals(1, gateway.requests.size)
    }

    @Test
    fun `html fallback can select beta channel release`() = runBlocking {
        val gateway = RecordingUpdateHttpGateway(
            UpdateHttpResponse(429, ""),
            UpdateHttpResponse(200, releasesHtml(releaseHtmlSection("v2.0.0-beta.1", prerelease = true))),
            UpdateHttpResponse(200, assetsHtml(assetHtml("app-universal.apk"))),
        )

        val result = checker(gateway).check(request(channel = UpdateChannel.BETA))

        assertEquals(
            "v2.0.0-beta.1",
            (result as SourceCheckResult.UpdateAvailable).version,
        )
    }

    @Test
    fun `html fallback without apk has no matching asset`() = runBlocking {
        val gateway = RecordingUpdateHttpGateway(
            UpdateHttpResponse(429, ""),
            UpdateHttpResponse(200, releasesHtml(releaseHtmlSection("v1.5.0"))),
            UpdateHttpResponse(200, assetsHtml(assetHtml("app.zip"))),
        )

        assertEquals(
            SourceCheckResult.Failed(UpdateCheckFailure.NO_MATCHING_ASSET),
            checker(gateway).check(request()),
        )
    }

    @Test
    fun `html fallback failure is reported as rate limited`() = runBlocking {
        val gateway = RecordingUpdateHttpGateway(
            UpdateHttpResponse(429, ""),
            UpdateHttpResponse(503, "Service unavailable"),
        )

        assertEquals(
            SourceCheckResult.Failed(
                UpdateCheckFailure.RATE_LIMITED,
                "GitHub API returned HTTP 429; HTML fallback returned HTTP 503",
            ),
            checker(gateway).check(request()),
        )
    }

    @Test
    fun `no apk asset has no matching asset`() = runBlocking {
        val gateway = RecordingUpdateHttpGateway(
            UpdateHttpResponse(
                200,
                releases(
                    release(
                        "v1.1.0",
                        assets = assets(asset("app.zip", "https://example.com/app.zip")),
                    ),
                ),
            ),
        )

        assertEquals(
            SourceCheckResult.Failed(UpdateCheckFailure.NO_MATCHING_ASSET),
            checker(gateway).check(request()),
        )
    }

    @Test
    fun `repository must be owner slash repo`() = runBlocking {
        val gateway = RecordingUpdateHttpGateway()

        assertEquals(
            SourceCheckResult.Failed(
                UpdateCheckFailure.MISSING_CONFIGURATION,
                "GitHub repository must be owner/repo",
            ),
            checker(gateway).check(request(repository = "https://github.com/owner/repo")),
        )
        assertTrue(gateway.requests.isEmpty())
    }

    @Test
    fun `pagination stops after three pages`() = runBlocking {
        val gateway = RecordingUpdateHttpGateway(
            UpdateHttpResponse(200, page(0)),
            UpdateHttpResponse(200, page(100)),
            UpdateHttpResponse(200, page(200)),
        )

        checker(gateway).check(request(currentVersion = "0.0.1"))

        assertEquals(3, gateway.requests.size)
        assertEquals("3", gateway.requests.last().first.toHttpUrl().queryParameter("page"))
    }

    private fun page(firstTag: Int): String =
        releases(*(0 until 100).map { release("${firstTag + it + 1}.0.0") }.toTypedArray())

    private fun releases(vararg values: String): String = "[${values.joinToString(",")}]"

    private fun release(
        tag: String,
        prerelease: Boolean = false,
        assets: String = "[]",
    ): String = """
        {
          "tag_name": "$tag",
          "prerelease": $prerelease,
          "html_url": "https://github.com/maaxyz/example/releases/tag/$tag",
          "body": "Release ${tag.substringAfter('v').substringBefore('-')}",
          "assets": $assets
        }
    """.trimIndent()

    private fun assets(vararg values: String): String = "[${values.joinToString(",")}]"

    private fun asset(
        name: String,
        url: String = "https://api.github.com/repos/maaxyz/example/releases/assets/1",
        digest: String? = null,
    ): String = if (digest == null) {
        """{"name":"$name","url":"$url","browser_download_url":"https://example.com/$name"}"""
    } else {
        """{"name":"$name","url":"$url","browser_download_url":"https://example.com/$name","digest":"$digest"}"""
    }

    private fun releasesHtml(vararg sections: String): String = """
        <html><body>${sections.joinToString("")}</body></html>
    """.trimIndent()

    private fun releaseHtmlSection(
        tag: String,
        prerelease: Boolean = false,
    ): String = """
        <section id="release-$tag">
          <h2 class="sr-only">$tag</h2>
          <a href="/maaxyz/example/releases/tag/$tag">$tag</a>
          ${if (prerelease) """<span class="Label">Pre-release</span>""" else ""}
          <div class="markdown-body"><p>Release $tag</p></div>
          <include-fragment src="https://github.com/maaxyz/example/releases/expanded_assets/$tag"></include-fragment>
        </section>
    """.trimIndent()

    private fun assetsHtml(vararg assets: String): String = """
        <div><ul>${assets.joinToString("")}</ul></div>
    """.trimIndent()

    private fun assetHtml(
        name: String,
        digest: String? = null,
    ): String = """
        <li class="Box-row">
          <a class="Truncate" href="/maaxyz/example/releases/download/v1.5.0/$name">
            <span class="Truncate-text">$name</span>
          </a>
          <span>${digest.orEmpty()}</span>
        </li>
    """.trimIndent()
}
