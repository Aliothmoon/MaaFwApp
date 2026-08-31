package com.aliothmoon.maafw.update

import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.i18n.uiTextFromFramework
import com.aliothmoon.maafw.i18n.uiTextOf
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorChyanUpdateTest {

    private fun api(gateway: RecordingUpdateHttpGateway) =
        MirrorChyanLatestApi(gateway = gateway.mock, userAgent = "MaaFwApp/1.2.3 Android")

    private fun client(gateway: RecordingUpdateHttpGateway) = MirrorChyanUpdateClient(api(gateway))

    private fun checkRequest(
        currentVersion: String = "1.0.0",
        channel: UpdateChannel = UpdateChannel.STABLE,
        abi: AndroidAbi = AndroidAbi.ARM64,
    ) = UpdateCheckRequest(
        currentVersion = currentVersion,
        mirrorchyanRid = "M9A",
        channel = channel,
        abi = abi,
    )

    private fun resolveRequest(
        currentVersion: String = "1.0.0",
        channel: UpdateChannel = UpdateChannel.STABLE,
        abi: AndroidAbi = AndroidAbi.ARM64,
        mirrorchyanCdk: String? = null,
    ) = UpdateResolveRequest(
        source = UpdateSource.MIRRORCHYAN,
        currentVersion = currentVersion,
        mirrorchyanRid = "M9A",
        channel = channel,
        abi = abi,
        mirrorchyanCdk = mirrorchyanCdk,
    )

    private val apkPayload = """
        {
          "code": 0,
          "msg": "ok",
          "data": {
            "version_name": "v1.1.0",
            "url": "https://example.com/M9A-v1.1.0.apk",
            "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "release_note": "Fixed things"
          }
        }
    """.trimIndent()

    @Test
    fun `check reports version and release note without url`() = runBlocking {
        val gateway = RecordingUpdateHttpGateway(UpdateHttpResponse(200, apkPayload))

        assertEquals(
            UpdateCheckResult.UpdateAvailable(
                source = UpdateSource.MIRRORCHYAN,
                info = UpdateInfo(version = "v1.1.0", releaseNotes = "Fixed things"),
            ),
            client(gateway).check(checkRequest()),
        )
        // 检查永远匿名：cdk 不进 query
        assertEquals(null, gateway.requests.single().first.toHttpUrl().queryParameter("cdk"))
    }

    @Test
    fun `check sends android query`() = runBlocking {
        val gateway = RecordingUpdateHttpGateway(
            UpdateHttpResponse(200, """{"code":0,"data":{"version_name":"1.0.0"}}"""),
        )

        client(gateway).check(checkRequest())

        val parsedUrl = gateway.requests.single().first.toHttpUrl()
        assertEquals("M9A", parsedUrl.pathSegments[2])
        assertEquals("stable", parsedUrl.queryParameter("channel"))
        assertEquals("1.0.0", parsedUrl.queryParameter("current_version"))
        assertEquals("android", parsedUrl.queryParameter("os"))
        assertEquals("arm64", parsedUrl.queryParameter("arch"))
        assertEquals("MaaFwApp/1.2.3 Android", parsedUrl.queryParameter("user_agent"))
        assertEquals("MaaFwApp/1.2.3 Android", gateway.requests.single().second["User-Agent"])
    }

    @Test
    fun `same version is up to date`() = runBlocking {
        val gateway = RecordingUpdateHttpGateway(
            UpdateHttpResponse(200, """{"code":0,"data":{"version_name":"1.0.0","url":"https://example.com/app.apk"}}"""),
        )

        assertEquals(
            UpdateCheckResult.UpToDate(UpdateSource.MIRRORCHYAN, "1.0.0"),
            client(gateway).check(checkRequest()),
        )
    }

    @Test
    fun `missing rid fails before network`() = runBlocking {
        val gateway = RecordingUpdateHttpGateway()

        val result = client(gateway).check(checkRequest().copy(mirrorchyanRid = " "))

        assertEquals(
            UpdateCheckResult.SourceFailed(UpdateSource.MIRRORCHYAN, UpdateCheckFailure.MISSING_CONFIGURATION),
            result,
        )
        assertTrue(gateway.requests.isEmpty())
    }

    @Test
    fun `resource business error maps to resource not found`() = runBlocking {
        val gateway = RecordingUpdateHttpGateway(
            UpdateHttpResponse(200, """{"code":8001,"msg":"resource not found"}"""),
        )

        assertEquals(
            UpdateCheckResult.SourceFailed(
                UpdateSource.MIRRORCHYAN,
                UpdateCheckFailure.RESOURCE_NOT_FOUND,
                detail = uiTextFromFramework("resource not found"),
            ),
            client(gateway).check(checkRequest()),
        )
    }

    @Test
    fun `http 404 with business code 8001 still maps to resource not found`() = runBlocking {
        val gateway = RecordingUpdateHttpGateway(
            UpdateHttpResponse(404, """{"code":8001,"msg":"resource not found"}"""),
        )

        assertEquals(
            UpdateCheckResult.SourceFailed(
                UpdateSource.MIRRORCHYAN,
                UpdateCheckFailure.RESOURCE_NOT_FOUND,
                detail = uiTextFromFramework("resource not found"),
            ),
            client(gateway).check(checkRequest()),
        )
    }

    @Test
    fun `http failure without business code keeps http reason`() = runBlocking {
        val gateway = RecordingUpdateHttpGateway(UpdateHttpResponse(502, "Bad Gateway"))

        assertEquals(
            UpdateCheckResult.SourceFailed(
                UpdateSource.MIRRORCHYAN,
                UpdateCheckFailure.HTTP,
                detail = uiTextOf(R.string.update_detail_http_status, 502),
            ),
            client(gateway).check(checkRequest()),
        )
    }

    @Test
    fun `resolve returns url digest and trimmed cdk`() = runBlocking {
        val gateway = RecordingUpdateHttpGateway(UpdateHttpResponse(200, apkPayload))

        assertEquals(
            UpdateResolveResult.Resolved(
                ResolvedUpdate(
                    source = UpdateSource.MIRRORCHYAN,
                    version = "v1.1.0",
                    downloadUrl = "https://example.com/M9A-v1.1.0.apk",
                    sha256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                ),
            ),
            client(gateway).resolve(resolveRequest(mirrorchyanCdk = " cdk-value ")),
        )
        assertEquals(
            "cdk-value",
            gateway.requests.single().first.toHttpUrl().queryParameter("cdk"),
        )
    }

    @Test
    fun `non apk resolve url has no matching asset`() = runBlocking {
        val gateway = RecordingUpdateHttpGateway(
            UpdateHttpResponse(
                200,
                """{"code":0,"data":{"version_name":"1.1.0","url":"https://example.com/app.zip"}}""",
            ),
        )

        assertEquals(
            UpdateResolveResult.Failed(
                UpdateSource.MIRRORCHYAN,
                UpdateCheckFailure.NO_MATCHING_ASSET,
            ),
            client(gateway).resolve(resolveRequest()),
        )
    }

    @Test
    fun `malformed data maps to invalid response`() = runBlocking {
        val gateway = RecordingUpdateHttpGateway(UpdateHttpResponse(200, """{"code":0,"data":""}"""))

        assertEquals(
            UpdateCheckResult.SourceFailed(
                UpdateSource.MIRRORCHYAN,
                UpdateCheckFailure.INVALID_RESPONSE,
            ),
            client(gateway).check(checkRequest()),
        )
    }
}
