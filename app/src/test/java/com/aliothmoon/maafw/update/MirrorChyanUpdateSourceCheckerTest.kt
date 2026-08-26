package com.aliothmoon.maafw.update

import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorChyanUpdateSourceCheckerTest {

    private fun checker(gateway: UpdateHttpGateway) = MirrorChyanUpdateSourceChecker(
        gateway = gateway,
        userAgent = "MaaFwApp/1.2.3 Android",
    )

    private fun request(
        currentVersion: String = "1.0.0",
        channel: UpdateChannel = UpdateChannel.STABLE,
        abi: AndroidAbi = AndroidAbi.ARM64,
        mirrorChyanCdk: String? = null,
    ) = UpdateCheckRequest(
        currentVersion = currentVersion,
        mirrorChyanRid = "M9A",
        channel = channel,
        abi = abi,
        mirrorChyanCdk = mirrorChyanCdk,
    )

    @Test
    fun `success returns apk metadata and android query`() = runBlocking {
        val gateway = RecordingUpdateHttpGateway(
            UpdateHttpResponse(
                200,
                """
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
                """.trimIndent(),
            ),
        )

        val result = checker(gateway).check(request())

        assertEquals(
            SourceCheckResult.UpdateAvailable(
                version = "v1.1.0",
                downloadUrl = "https://example.com/M9A-v1.1.0.apk",
                sha256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                releaseNotesUrl = null,
                releaseNotes = "Fixed things",
            ),
            result,
        )
        val (url, headers) = gateway.requests.single()
        val parsedUrl = url.toHttpUrl()
        assertEquals("M9A", parsedUrl.pathSegments[2])
        assertEquals("stable", parsedUrl.queryParameter("channel"))
        assertEquals("1.0.0", parsedUrl.queryParameter("current_version"))
        assertEquals("android", parsedUrl.queryParameter("os"))
        assertEquals("arm64", parsedUrl.queryParameter("arch"))
        assertEquals("MaaFwApp/1.2.3 Android", parsedUrl.queryParameter("user_agent"))
        assertEquals(null, parsedUrl.queryParameter("cdk"))
        assertEquals("MaaFwApp/1.2.3 Android", headers["User-Agent"])
    }

    @Test
    fun `cdk is sent trimmed when supplied`() = runBlocking {
        val gateway = RecordingUpdateHttpGateway(
            UpdateHttpResponse(200, """{"code":0,"data":{"version_name":"1.0.0"}}"""),
        )

        checker(gateway).check(request(mirrorChyanCdk = " cdk-value "))

        assertEquals(
            "cdk-value",
            gateway.requests.single().first.toHttpUrl().queryParameter("cdk"),
        )
    }

    @Test
    fun `same version is up to date`() = runBlocking {
        val gateway = RecordingUpdateHttpGateway(
            UpdateHttpResponse(
                200,
                """{"code":0,"data":{"version_name":"1.0.0","url":"https://example.com/app.apk"}}""",
            ),
        )

        assertEquals(
            SourceCheckResult.UpToDate("1.0.0"),
            checker(gateway).check(request()),
        )
    }

    @Test
    fun `resource business error maps to resource not found`() = runBlocking {
        val gateway = RecordingUpdateHttpGateway(
            UpdateHttpResponse(200, """{"code":8001,"msg":"resource not found"}"""),
        )

        assertEquals(
            SourceCheckResult.Failed(UpdateCheckFailure.RESOURCE_NOT_FOUND, "resource not found"),
            checker(gateway).check(request()),
        )
    }

    @Test
    fun `malformed success json maps to invalid response`() = runBlocking {
        val gateway = RecordingUpdateHttpGateway(UpdateHttpResponse(200, """{"code":0,"data":""}"""))

        assertEquals(
            SourceCheckResult.Failed(UpdateCheckFailure.INVALID_RESPONSE, "Missing update data"),
            checker(gateway).check(request()),
        )
    }

    @Test
    fun `non apk update url has no matching asset`() = runBlocking {
        val gateway = RecordingUpdateHttpGateway(
            UpdateHttpResponse(
                200,
                """{"code":0,"data":{"version_name":"1.1.0","url":"https://example.com/app.zip"}}""",
            ),
        )

        assertEquals(
            SourceCheckResult.Failed(UpdateCheckFailure.NO_MATCHING_ASSET),
            checker(gateway).check(request()),
        )
    }

    @Test
    fun `missing rid fails before network`() = runBlocking {
        val gateway = RecordingUpdateHttpGateway()

        val result = checker(gateway).check(request().copy(mirrorChyanRid = " "))

        assertEquals(
            SourceCheckResult.Failed(
                UpdateCheckFailure.MISSING_CONFIGURATION,
                "MirrorChyan resource id is missing",
            ),
            result,
        )
        assertTrue(gateway.requests.isEmpty())
    }
}
