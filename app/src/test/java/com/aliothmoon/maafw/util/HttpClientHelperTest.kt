package com.aliothmoon.maafw.util

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors

class HttpClientHelperTest {

    // 冻结 readBody 的存在理由：body 是 socket 读，主线程会抛 NetworkOnMainThreadException
    @Test
    fun `readBody reads the body off the caller thread`() {
        val callerThread = Thread.currentThread()
        var readThread: Thread? = null
        val helper = helper(body = "ok".repeat(1024), onRead = { readThread = Thread.currentThread() })

        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            val body = runBlocking(dispatcher) {
                helper.get("https://example.com/api").readBody()
            }

            assertEquals("ok".repeat(1024), body)
            assertTrue(readThread !== callerThread)
            assertTrue(readThread!!.name.startsWith("DefaultDispatcher-worker-"))
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun `get appends query parameters`() {
        var requestUrl: okhttp3.HttpUrl? = null
        val helper = helper(body = "{}", onRequest = { requestUrl = it.url })

        runBlocking {
            helper.get("https://example.com/api", query = mapOf("channel" to "stable")).close()
        }

        assertEquals("stable", requestUrl!!.queryParameter("channel"))
    }

    private fun helper(
        body: String,
        onRead: () -> Unit = {},
        onRequest: (okhttp3.Request) -> Unit = {},
    ): HttpClientHelper {
        val client = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    onRequest(chain.request())
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_2)
                        .code(200)
                        .message("ok")
                        .body(recordingBody(body, onRead))
                        .build()
                },
            )
            .build()
        return HttpClientHelper(client)
    }

    private fun recordingBody(value: String, onRead: () -> Unit): ResponseBody =
        object : ResponseBody() {
            private val buffer = Buffer().writeUtf8(value)

            override fun contentType(): MediaType = "application/json; charset=utf-8".toMediaType()

            override fun contentLength(): Long = buffer.size

            override fun source() = object : ForwardingSource(buffer) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    onRead()
                    return super.read(sink, byteCount)
                }
            }.buffer()
        }
}
