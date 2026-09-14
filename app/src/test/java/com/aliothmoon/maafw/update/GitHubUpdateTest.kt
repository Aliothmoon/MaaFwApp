package com.aliothmoon.maafw.update

import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.constant.MiscConstants
import com.aliothmoon.maafw.i18n.uiTextFromFramework
import com.aliothmoon.maafw.i18n.uiTextOf
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubUpdateTest {

    private fun api(gateway: RecordingHttpClientHelper) = GitHubReleasesApi(gateway.mock)

    private fun client(gateway: RecordingHttpClientHelper) = GitHubUpdateClient(api(gateway))

    private fun checkRequest(
        repository: String? = "maaxyz/example",
        currentVersion: String = "1.0.0",
        abi: AndroidAbi = AndroidAbi.ARM64,
        channel: UpdateChannel = UpdateChannel.STABLE,
    ) = UpdateCheckRequest(
        source = UpdateSource.GITHUB,
        currentVersion = currentVersion,
        githubRepository = repository,
        abi = abi,
        channel = channel,
    )

    private fun resolveRequest(
        repository: String? = "maaxyz/example",
        currentVersion: String = "1.0.0",
        abi: AndroidAbi = AndroidAbi.ARM64,
        channel: UpdateChannel = UpdateChannel.STABLE,
    ) = UpdateResolveRequest(
        source = UpdateSource.GITHUB,
        currentVersion = currentVersion,
        githubRepository = repository,
        abi = abi,
        channel = channel,
    )

    @Test
    fun `check picks highest channel eligible release and ignores assets`() = runBlocking {
        val gateway = RecordingHttpClientHelper(
            FakeHttpResponse(
                200,
                releases(
                    release("v2.0.0-beta.1", prerelease = true),
                    release(
                        "v1.5.0",
                        assets = assets(asset("app-x86_64.apk", "https://example.com/x86_64")),
                    ),
                    release("v1.2.0"),
                ),
            ),
        )

        assertEquals(
            UpdateCheckResult.UpdateAvailable(
                source = UpdateSource.GITHUB,
                info = UpdateInfo(
                    version = "v1.5.0",
                    releaseNotesUrl = "https://github.com/maaxyz/example/releases/tag/v1.5.0",
                    releaseNotes = "Release 1.5.0",
                ),
            ),
            client(gateway).check(checkRequest()),
        )
        val url = gateway.requests.single().first.toHttpUrl()
        assertEquals("/repos/maaxyz/example/releases", url.encodedPath)
        assertEquals("100", url.queryParameter("per_page"))
        assertEquals("1", url.queryParameter("page"))
    }

    @Test
    fun `check request is anonymous`() = runBlocking {
        val gateway = RecordingHttpClientHelper(
            FakeHttpResponse(200, releases(release("v1.1.0", assets = assets(asset("app.apk"))))),
        )

        client(gateway).check(checkRequest())

        assertNull(gateway.requests.single().second["Authorization"])
        assertEquals("2022-11-28", gateway.requests.single().second["X-GitHub-Api-Version"])
        // 非 MirrorChyan 的请求不暴露应用身份
        assertEquals(MiscConstants.BROWSER_UA, gateway.requests.single().second["User-Agent"])
    }

    @Test
    fun `api 429 is reported as rate limited without fallback`() = runBlocking {
        val gateway = RecordingHttpClientHelper(
            FakeHttpResponse(429, """{"message":"rate limited"}"""),
        )

        assertEquals(
            UpdateCheckResult.SourceFailed(
                UpdateSource.GITHUB,
                UpdateCheckFailure.RATE_LIMITED,
                detail = uiTextFromFramework("rate limited"),
            ),
            client(gateway).check(checkRequest()),
        )
        assertEquals(1, gateway.requests.size)
    }

    @Test
    fun `api 403 is rate limited`() = runBlocking {
        val gateway = RecordingHttpClientHelper(
            FakeHttpResponse(403, """{"message":"rate limited"}"""),
        )

        assertEquals(
            UpdateCheckResult.SourceFailed(
                UpdateSource.GITHUB,
                UpdateCheckFailure.RATE_LIMITED,
                detail = uiTextFromFramework("rate limited"),
            ),
            client(gateway).check(checkRequest()),
        )
    }

    @Test
    fun `check without eligible release has no matching asset`() = runBlocking {
        val gateway = RecordingHttpClientHelper(
            FakeHttpResponse(200, releases(release("v1.1.0", prerelease = true))),
        )

        assertEquals(
            UpdateCheckResult.SourceFailed(
                UpdateSource.GITHUB,
                UpdateCheckFailure.NO_MATCHING_ASSET,
            ),
            client(gateway).check(checkRequest()),
        )
    }

    @Test
    fun `repository must be owner slash repo`() = runBlocking {
        val gateway = RecordingHttpClientHelper()

        assertEquals(
            UpdateCheckResult.SourceFailed(
                UpdateSource.GITHUB,
                UpdateCheckFailure.MISSING_CONFIGURATION,
            ),
            client(gateway).check(checkRequest(repository = "https://github.com/owner/repo")),
        )
        assertTrue(gateway.requests.isEmpty())
    }

    @Test
    fun `pagination stops after three pages`() = runBlocking {
        val gateway = RecordingHttpClientHelper(
            FakeHttpResponse(200, page(0)),
            FakeHttpResponse(200, page(100)),
            FakeHttpResponse(200, page(200)),
        )

        client(gateway).check(checkRequest(currentVersion = "0.0.1"))

        assertEquals(3, gateway.requests.size)
        assertEquals("3", gateway.requests.last().first.toHttpUrl().queryParameter("page"))
    }

    @Test
    fun `resolve prefers the asset for the device abi`() = runBlocking {
        val gateway = RecordingHttpClientHelper(
            FakeHttpResponse(
                200,
                releases(
                    release(
                        "v1.5.0",
                        assets = assets(
                            asset("app-x86_64.apk", "https://example.com/x86_64"),
                            asset(
                                "app-arm64-v8a.apk",
                                "https://example.com/arm64",
                                digest = "sha256:" + "a".repeat(64),
                            ),
                            asset("app-universal.apk", "https://example.com/universal"),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(
            UpdateResolveResult.Resolved(
                ResolvedUpdate(
                    source = UpdateSource.GITHUB,
                    version = "v1.5.0",
                    downloadUrl = "https://example.com/app-arm64-v8a.apk",
                    sha256 = "sha256:" + "a".repeat(64),
                ),
            ),
            client(gateway).resolve(resolveRequest()),
        )
    }

    @Test
    fun `resolve falls back to universal when device abi variant is missing`() = runBlocking {
        val gateway = RecordingHttpClientHelper(
            FakeHttpResponse(
                200,
                releases(
                    release(
                        "v1.5.0",
                        assets = assets(
                            asset("app-x86_64.apk", "https://example.com/x86_64"),
                            asset(
                                "app-universal.apk",
                                "https://example.com/universal",
                                digest = "sha256:" + "a".repeat(64),
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(
            UpdateResolveResult.Resolved(
                ResolvedUpdate(
                    source = UpdateSource.GITHUB,
                    version = "v1.5.0",
                    downloadUrl = "https://example.com/app-universal.apk",
                    sha256 = "sha256:" + "a".repeat(64),
                ),
            ),
            client(gateway).resolve(resolveRequest()),
        )
    }

    @Test
    fun `resolve without device abi variant nor universal has no matching asset`() = runBlocking {
        val gateway = RecordingHttpClientHelper(
            FakeHttpResponse(
                200,
                releases(
                    release(
                        "v1.5.0",
                        assets = assets(asset("app-x86_64.apk", "https://example.com/x86_64")),
                    ),
                ),
            ),
        )

        assertEquals(
            UpdateResolveResult.Failed(UpdateSource.GITHUB, UpdateCheckFailure.NO_MATCHING_ASSET),
            client(gateway).resolve(resolveRequest()),
        )
    }

    @Test
    fun `a single apk without abi marker is universal`() = runBlocking {
        val gateway = RecordingHttpClientHelper(
            FakeHttpResponse(
                200,
                releases(
                    release("v2.0.0", assets = assets(asset("MaaFwApp.apk", "https://example.com/universal"))),
                ),
            ),
        )

        assertEquals(
            "https://example.com/MaaFwApp.apk",
            (client(gateway).resolve(resolveRequest(abi = AndroidAbi.X86)) as UpdateResolveResult.Resolved)
                .update.downloadUrl,
        )
    }

    @Test
    fun `resolve without apk asset has no matching asset`() = runBlocking {
        val gateway = RecordingHttpClientHelper(
            FakeHttpResponse(
                200,
                releases(
                    release("v1.1.0", assets = assets(asset("app.zip", "https://example.com/app.zip"))),
                ),
            ),
        )

        assertEquals(
            UpdateResolveResult.Failed(UpdateSource.GITHUB, UpdateCheckFailure.NO_MATCHING_ASSET),
            client(gateway).resolve(resolveRequest()),
        )
    }

    @Test
    fun `resolve picks beta channel release`() = runBlocking {
        val gateway = RecordingHttpClientHelper(
            FakeHttpResponse(200, releases(release("v2.0.0-beta.1", prerelease = true, assets = assets(asset("app.apk"))))),
        )

        assertEquals(
            "v2.0.0-beta.1",
            (client(gateway).resolve(resolveRequest(channel = UpdateChannel.BETA)) as UpdateResolveResult.Resolved)
                .update.version,
        )
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
}
