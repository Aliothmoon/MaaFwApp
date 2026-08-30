package com.aliothmoon.maafw.update

import com.aliothmoon.maafw.MaaDispatchers
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.MessageDigest

@OptIn(ExperimentalCoroutinesApi::class)
class OkHttpUpdateDownloaderTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun mockDispatchers() {
        mockkObject(MaaDispatchers)
        every { MaaDispatchers.IO } returns dispatcher
    }

    @After
    fun unmockDispatchers() {
        unmockkObject(MaaDispatchers)
    }

    @Test
    fun `downloads an apk and accepts prefixed github digest`() = runTest(dispatcher) {
        val bytes = "update-apk".toByteArray()
        val requests = mutableListOf<okhttp3.Request>()
        val downloader = downloader(bytes, requests)
        val progress = mutableListOf<Pair<Long, Long>>()

        val result = downloader.download(
            update(sha256 = "sha256:" + sha256(bytes)),
            onProgress = { downloaded, total -> progress += downloaded to total },
        )

        val downloaded = result as UpdateDownloadResult.Downloaded
        assertArrayEquals(bytes, downloaded.update.file.readBytes())
        assertTrue(downloaded.update.file.name.endsWith(".apk"))
        assertEquals(sha256(bytes), downloaded.update.sha256)
        assertEquals(listOf(0L to bytes.size.toLong(), bytes.size.toLong() to bytes.size.toLong()), progress)
        assertEquals("MaaFwApp Android", requests.single().header("User-Agent"))
        assertEquals(1, downloaded.update.file.parentFile!!.list()!!.size)
    }

    @Test
    fun `download carries no authorization header`() = runTest(dispatcher) {
        val requests = mutableListOf<okhttp3.Request>()
        val bytes = "mirror-apk".toByteArray()
        val downloader = downloader(bytes, requests)

        val result = downloader.download(
            update = update(
                url = "https://mirror.example.com/app.apk",
                sha256 = sha256(bytes),
                source = UpdateSource.MIRRORCHYAN,
            ),
        )

        assertEquals(UpdateDownloadResult.Downloaded::class, result::class)
        assertEquals(null, requests.single().header("Authorization"))
    }

    @Test
    fun `reuses an already verified apk without another request`() = runTest(dispatcher) {
        val bytes = "cached-apk".toByteArray()
        val requests = mutableListOf<okhttp3.Request>()
        val downloader = downloader(bytes, requests)
        val first = downloader.download(update(sha256 = sha256(bytes)))

        val second = downloader.download(update(sha256 = sha256(bytes)))

        assertEquals((first as UpdateDownloadResult.Downloaded).update.file, (second as UpdateDownloadResult.Downloaded).update.file)
        assertEquals(1, requests.size)
    }

    @Test
    fun `digest mismatch removes partial and final files`() = runTest(dispatcher) {
        val bytes = "wrong-apk".toByteArray()
        val directory = temp.newFolder("updates")
        val downloader = downloader(bytes, directory = directory)

        val result = downloader.download(update(sha256 = "0".repeat(64)))

        assertEquals(UpdateDownloadFailure.DIGEST_MISMATCH, (result as UpdateDownloadResult.Failed).reason)
        assertEquals(emptyList<String>(), directory.list()!!.toList())
    }

    @Test
    fun `http failure returns error before body bytes`() = runTest(dispatcher) {
        val directory = temp.newFolder("updates")
        val downloader = downloader("error".toByteArray(), directory = directory, code = 503)

        val result = downloader.download(update(sha256 = "0".repeat(64)))

        assertEquals(UpdateDownloadFailure.HTTP, (result as UpdateDownloadResult.Failed).reason)
        // 服务器 4xx/5xx 在拿到 body 之前就被 reject,目录里没落盘任何 .part
        assertEquals(emptyList<String>(), directory.list()!!.toList())
    }

    @Test
    fun `missing digest is rejected`() = runTest(dispatcher) {
        val directory = temp.newFolder("updates")
        val requests = mutableListOf<okhttp3.Request>()
        val downloader = downloader("apk".toByteArray(), requests, directory)

        val result = downloader.download(update(sha256 = null))

        assertEquals(UpdateDownloadFailure.INVALID_DIGEST, (result as UpdateDownloadResult.Failed).reason)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `non https url is rejected`() = runTest(dispatcher) {
        val directory = temp.newFolder("updates")
        val downloader = downloader("apk".toByteArray(), directory = directory)

        val result = downloader.download(update(url = "http://example.com/app.apk", sha256 = "0".repeat(64)))

        assertEquals(UpdateDownloadFailure.INVALID_URL, (result as UpdateDownloadResult.Failed).reason)
    }

    @Test
    fun `io failure is mapped to network failure`() = runTest(dispatcher) {
        val directory = temp.newFolder("updates")
        val client = OkHttpClient.Builder()
            .addInterceptor { throw IOException("offline") }
            .build()
        val downloader = OkHttpUpdateDownloader(directory, client)

        val result = downloader.download(update(sha256 = "0".repeat(64)))

        assertEquals(UpdateDownloadFailure.NETWORK, (result as UpdateDownloadResult.Failed).reason)
        // IOException 在还没读到 body 之前抛,目录里没 .part
        assertEquals(emptyList<String>(), directory.list()!!.toList())
    }

    @Test
    fun `sends Range header and appends when server returns 206`() = runTest(dispatcher) {
        val fullBytes = "resume-apk-bytes".toByteArray()  // 17 bytes
        val cut = 6
        val prefix = fullBytes.copyOfRange(0, cut)  // "resume"
        val remaining = fullBytes.copyOfRange(cut, fullBytes.size)  // "-apk-bytes"

        val directory = temp.newFolder("updates")
        val identity = sha256(fullBytes).takeLast(16)
        val partFile = File(directory, "maafw-1.2.3-$identity.apk.part")
        partFile.writeBytes(prefix)

        val requests = mutableListOf<okhttp3.Request>()
        val client = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    requests += chain.request()
                    val range = chain.request().header("Range")
                    if (range == "bytes=$cut-") {
                        Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_2)
                            .code(206)
                            .message("Partial Content")
                            .header("Content-Range", "bytes $cut-${fullBytes.size - 1}/${fullBytes.size}")
                            .body(remaining.toResponseBody(APK_MEDIA_TYPE))
                            .build()
                    } else {
                        Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_2)
                            .code(200)
                            .message("OK")
                            .body(fullBytes.toResponseBody(APK_MEDIA_TYPE))
                            .build()
                    }
                },
            )
            .build()
        val downloader = OkHttpUpdateDownloader(directory, client)

        val result = downloader.download(update(sha256 = sha256(fullBytes)))

        assertEquals(UpdateDownloadResult.Downloaded::class, result::class)
        val downloaded = result as UpdateDownloadResult.Downloaded
        assertArrayEquals(fullBytes, downloaded.update.file.readBytes())
        assertEquals("bytes=$cut-", requests.single().header("Range"))
        // .part 已经被 rename 成 .apk,目录里没有 part 残留
        assertEquals(1, downloaded.update.file.parentFile!!.list()!!.size)
    }

    @Test
    fun `falls back to full download when server ignores Range`() = runTest(dispatcher) {
        val fullBytes = "resume-apk-bytes".toByteArray()
        val directory = temp.newFolder("updates")
        // 预存一段错的字节,模拟上一次失败留下的 .part
        val identity = sha256(fullBytes).takeLast(16)
        val partFile = File(directory, "maafw-1.2.3-$identity.apk.part")
        partFile.writeBytes("STALE-".toByteArray())

        val requests = mutableListOf<okhttp3.Request>()
        val client = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    requests += chain.request()
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_2)
                        .code(200)  // 服务器忽略 Range,从头 200 全量
                        .message("OK")
                        .body(fullBytes.toResponseBody(APK_MEDIA_TYPE))
                        .build()
                },
            )
            .build()
        val downloader = OkHttpUpdateDownloader(directory, client)

        val result = downloader.download(update(sha256 = sha256(fullBytes)))

        assertEquals(UpdateDownloadResult.Downloaded::class, result::class)
        val downloaded = result as UpdateDownloadResult.Downloaded
        assertArrayEquals(fullBytes, downloaded.update.file.readBytes())
        assertEquals("bytes=6-", requests.single().header("Range"))
        // STALE- 前缀不应出现在最终文件里
        assertNull(downloaded.update.file.parentFile!!.listFiles()!!.singleOrNull { it.extension == "part" })
    }

    @Test
    fun `no Range header sent when no existing part`() = runTest(dispatcher) {
        val bytes = "fresh-apk".toByteArray()
        val requests = mutableListOf<okhttp3.Request>()
        val downloader = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    requests += chain.request()
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_2)
                        .code(200)
                        .message("OK")
                        .body(bytes.toResponseBody(APK_MEDIA_TYPE))
                        .build()
                },
            )
            .build()
            .let { OkHttpUpdateDownloader(temp.newFolder("updates"), it) }

        downloader.download(update(sha256 = sha256(bytes)))

        assertNull(requests.single().header("Range"))
    }

    private fun downloader(
        body: ByteArray,
        requests: MutableList<okhttp3.Request> = mutableListOf(),
        directory: File = temp.newFolder("updates"),
        code: Int = 200,
    ): OkHttpUpdateDownloader {
        val client = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    requests += chain.request()
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_2)
                        .code(code)
                        .message("ok")
                        .body(body.toResponseBody(APK_MEDIA_TYPE))
                        .build()
                },
            )
            .build()
        return OkHttpUpdateDownloader(directory, client)
    }

    private fun update(
        url: String = "https://example.com/app.apk",
        sha256: String? = sha256("update-apk".toByteArray()),
        source: UpdateSource = UpdateSource.GITHUB,
    ) = ResolvedUpdate(
        source = source,
        version = "1.2.3",
        downloadUrl = url,
        sha256 = sha256,
    )

    private fun sha256(value: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(value)
            .joinToString("") { byte -> String.format("%02x", byte) }

    private companion object {
        val APK_MEDIA_TYPE = "application/vnd.android.package-archive".toMediaType()
    }
}
