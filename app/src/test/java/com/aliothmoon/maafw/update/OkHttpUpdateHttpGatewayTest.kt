package com.aliothmoon.maafw.update

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.MediaType.Companion.toMediaType
import okio.Buffer
import okio.ForwardingSource
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors

class OkHttpUpdateHttpGatewayTest {

    @Test
    fun `response body is read on an io dispatcher`() {
        val callerThread = Thread.currentThread()
        var readThread: Thread? = null
        val gateway = gateway(
            body = "ok".repeat(1024),
            onRead = { readThread = Thread.currentThread() },
        )

        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            val response = runBlocking(dispatcher) { gateway.get("https://example.com/api") }

            assertEquals(200, response.statusCode)
            assertEquals("ok".repeat(1024), response.body)
            assertTrue(readThread !== callerThread)
            assertTrue(readThread!!.name.startsWith("DefaultDispatcher-worker-"))
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun `response body larger than the limit is truncated`() {
        val gateway = gateway(::oversizedBody)

        val response = runBlocking { gateway.get("https://example.com/api") }

        assertTrue(response.truncated)
        assertEquals(2 * 1024 * 1024 + 1, response.body.toByteArray().size)
    }

    private fun gateway(
        bodyFactory: () -> ResponseBody,
    ): OkHttpUpdateHttpGateway {
        val client = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    chain.proceed(
                        chain.request().newBuilder()
                            .url("https://example.com/api")
                            .build(),
                    ).newBuilder()
                        .code(200)
                        .protocol(Protocol.HTTP_2)
                        .message("ok")
                        .body(bodyFactory())
                        .build()
                },
            )
            .build()
        return OkHttpUpdateHttpGateway(client)
    }

    private fun gateway(
        body: String,
        onRead: () -> Unit = {},
    ): OkHttpUpdateHttpGateway = gateway {
        recordingBody(body, body.length, onRead)
    }

    private fun recordingBody(
        value: String,
        length: Int,
        onRead: () -> Unit,
    ): ResponseBody = object : ResponseBody() {
        private val buffer = Buffer().writeUtf8(value)

        override fun contentType(): MediaType? = "application/json; charset=utf-8".toMediaType()

        override fun contentLength(): Long = length.toLong()

        override fun source() = object : ForwardingSource(buffer) {
            override fun read(sink: okio.Buffer, byteCount: Long): Long {
                onRead()
                return super.read(sink, byteCount)
            }
        }.buffer()
    }

    private fun oversizedBody(): ResponseBody = object : ResponseBody() {
        private val chunk = ByteArray(8 * 1024) { 'a'.code.toByte() }
        private var remaining = 2 * 1024 * 1024 + 1L

        override fun contentType(): MediaType? = "application/json; charset=utf-8".toMediaType()

        override fun contentLength(): Long = remaining

        override fun source() = object : okio.Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (remaining <= 0) return -1
                val count = minOf(byteCount, remaining, chunk.size.toLong()).toInt()
                sink.write(chunk, 0, count)
                remaining -= count
                return count.toLong()
            }

            override fun close() = Unit

            override fun timeout(): Timeout = Timeout()
        }.buffer()
    }
}
