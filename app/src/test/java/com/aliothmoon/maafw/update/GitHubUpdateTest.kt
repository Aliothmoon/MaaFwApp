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

    private fun client(gateway: RecordingHttpClientHelper, mirror: MirrorChyanLatestApi.Latest? = null) =
        GitHubUpdateClient(api(gateway)) { _, _, _, _ -> mirror }

    private fun mirror(version: String) =
        MirrorChyanLatestApi.Latest(version = version, url = null, sha256 = null, releaseNote = "From Mirror")

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
    fun `check picks highest channel eligible release with an apk for the abi`() = runBlocking {
        val gateway = RecordingHttpClientHelper(
            FakeHttpResponse(
                200,
                releases(
                    release("v2.0.0-beta.1", prerelease = true, assets = assets(asset("app.apk"))),
                    release(
                        "v1.5.0",
                        assets = assets(asset("app-arm64-v8a.apk", "https://example.com/arm64")),
                    ),
                    release("v1.2.0", assets = assets(asset("app.apk"))),
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
    fun `check drops the download section from release notes`() = runBlocking {
        val gateway = RecordingHttpClientHelper(
            FakeHttpResponse(
                200,
                releases(
                    release(
                        "v1.5.0",
                        assets = assets(asset("app.apk")),
                        body = """<!-- downloads:start -->\r\n\r\n| Arch | Android |\r\n| --- | --- |\r\n\r\n""" +
                            """<!-- downloads:end -->\r\n\r\n## 1.5.0\r\n\r\n- Fixed things""",
                    ),
                ),
            ),
        )

        assertEquals(
            "## 1.5.0\r\n\r\n- Fixed things",
            (client(gateway).check(checkRequest()) as UpdateCheckResult.UpdateAvailable).info.releaseNotes,
        )
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
    fun `release without an apk yet is not offered and check falls back`() = runBlocking {
        val gateway = RecordingHttpClientHelper(
            FakeHttpResponse(
                200,
                releases(
                    release("v1.6.0"),
                    release("v1.5.0", assets = assets(asset("app-arm64-v8a.apk", "https://example.com/arm64"))),
                ),
            ),
        )

        assertEquals(
            "v1.5.0",
            (client(gateway).check(checkRequest()) as UpdateCheckResult.UpdateAvailable).info.version,
        )
    }

    @Test
    fun `release whose apk is only for another abi is skipped by check and resolve`() = runBlocking {
        val body = releases(
            release("v1.6.0", assets = assets(asset("app-x86_64.apk", "https://example.com/x86_64"))),
            release("v1.5.0", assets = assets(asset("app-arm64-v8a.apk", "https://example.com/arm64"))),
        )

        assertEquals(
            UpdateCheckResult.UpToDate(UpdateSource.GITHUB, "v1.5.0"),
            client(RecordingHttpClientHelper(FakeHttpResponse(200, body))).check(checkRequest(currentVersion = "1.5.0")),
        )
        assertEquals(
            "v1.5.0",
            (client(RecordingHttpClientHelper(FakeHttpResponse(200, body))).resolve(resolveRequest())
                as UpdateResolveResult.Resolved).update.version,
        )
    }

    @Test
    fun `asset still uploading is ignored`() = runBlocking {
        val gateway = RecordingHttpClientHelper(
            FakeHttpResponse(
                200,
                releases(
                    release("v1.6.0", assets = assets(asset("app-arm64-v8a.apk", state = "open"))),
                    release("v1.5.0", assets = assets(asset("app-arm64-v8a.apk", state = "uploaded"))),
                ),
            ),
        )

        assertEquals(
            "v1.5.0",
            (client(gateway).resolve(resolveRequest()) as UpdateResolveResult.Resolved).update.version,
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
    fun `universal package stays on universal when abi variants exist`() = runBlocking {
        val gateway = RecordingHttpClientHelper(
            FakeHttpResponse(
                200,
                releases(
                    release(
                        "v1.5.0",
                        assets = assets(
                            asset("app-arm64-v8a.apk", "https://example.com/arm64"),
                            asset("app-x86_64.apk", "https://example.com/x86_64"),
                            asset("app-universal.apk", "https://example.com/universal"),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(
            "https://example.com/app-universal.apk",
            (client(gateway).resolve(resolveRequest(abi = AndroidAbi.UNIVERSAL)) as UpdateResolveResult.Resolved)
                .update.downloadUrl,
        )
    }

    @Test
    fun `universal package without universal asset has no matching asset`() = runBlocking {
        val gateway = RecordingHttpClientHelper(
            FakeHttpResponse(
                200,
                releases(
                    release("v1.5.0", assets = assets(asset("app-arm64-v8a.apk", "https://example.com/arm64"))),
                ),
            ),
        )

        assertEquals(
            UpdateResolveResult.Failed(UpdateSource.GITHUB, UpdateCheckFailure.NO_MATCHING_ASSET),
            client(gateway).resolve(resolveRequest(abi = AndroidAbi.UNIVERSAL)),
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

    @Test
    fun `check takes the mirror version without calling github`() = runBlocking {
        val gateway = RecordingHttpClientHelper()

        assertEquals(
            UpdateCheckResult.UpdateAvailable(
                UpdateSource.GITHUB,
                UpdateInfo(
                    version = "v1.6.0",
                    releaseNotesUrl = "https://github.com/maaxyz/example/releases",
                    releaseNotes = "From Mirror",
                ),
            ),
            client(gateway, mirror("v1.6.0")).check(checkRequest()),
        )
        assertEquals(
            UpdateCheckResult.UpToDate(UpdateSource.GITHUB, "v1.0.0"),
            client(gateway, mirror("v1.0.0")).check(checkRequest()),
        )
        assertTrue(gateway.requests.isEmpty())
    }

    @Test
    fun `an unparsable mirror version falls back to github`() = runBlocking {
        val gateway = RecordingHttpClientHelper(
            FakeHttpResponse(200, releases(release("v1.5.0", assets = assets(asset("app-arm64-v8a.apk"))))),
        )

        val result = client(gateway, mirror("nightly")).check(checkRequest())

        assertEquals("v1.5.0", (result as UpdateCheckResult.UpdateAvailable).info.version)
    }

    @Test
    fun `resolve downloads the mirror version even when github has a newer one`() = runBlocking {
        val body = releases(
            release("v1.6.0", assets = assets(asset("app-arm64-v8a.apk"))),
            release("v1.5.0", assets = assets(asset("app-arm64-v8a.apk"))),
        )

        val result = client(RecordingHttpClientHelper(FakeHttpResponse(200, body)), mirror("1.5.0"))
            .resolve(resolveRequest())

        assertEquals("v1.5.0", (result as UpdateResolveResult.Resolved).update.version)
    }

    @Test
    fun `resolve reports no matching asset when github lacks the mirror version`() = runBlocking {
        val body = releases(release("v1.6.0", assets = assets(asset("app-arm64-v8a.apk"))))

        assertEquals(
            UpdateResolveResult.Failed(UpdateSource.GITHUB, UpdateCheckFailure.NO_MATCHING_ASSET),
            client(RecordingHttpClientHelper(FakeHttpResponse(200, body)), mirror("v1.7.0")).resolve(resolveRequest()),
        )
    }

    private fun releases(vararg values: String): String = "[${values.joinToString(",")}]"

    private fun release(
        tag: String,
        prerelease: Boolean = false,
        assets: String = "[]",
        body: String = "Release ${tag.substringAfter('v').substringBefore('-')}",
    ): String = """
        {
          "tag_name": "$tag",
          "prerelease": $prerelease,
          "html_url": "https://github.com/maaxyz/example/releases/tag/$tag",
          "body": "$body",
          "assets": $assets
        }
    """.trimIndent()

    private fun assets(vararg values: String): String = "[${values.joinToString(",")}]"

    private fun asset(
        name: String,
        url: String = "https://api.github.com/repos/maaxyz/example/releases/assets/1",
        digest: String? = null,
        state: String? = null,
    ): String = buildString {
        append("""{"name":"$name","url":"$url","browser_download_url":"https://example.com/$name"""")
        digest?.let { append(""","digest":"$it"""") }
        state?.let { append(""","state":"$it"""") }
        append("}")
    }
}
