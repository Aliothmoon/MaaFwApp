package com.aliothmoon.maafw.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import timber.log.Timber
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** HTTP GET 边界：检查源只跟这个具体类打交道；测试用 mockk 桩 get 方法 */
data class UpdateHttpResponse(
    val statusCode: Int,
    val body: String,
)

internal class OkHttpUpdateHttpGateway(
    okHttpClient: OkHttpClient = defaultClient(),
) {

    private val client = okHttpClient.newBuilder().build()

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): UpdateHttpResponse {
        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url.toHttpUrl())
                .apply { headers.forEach { (name, value) -> header(name, value) } }
                .get()
                .build()
            client.newCall(request).await().use { response ->
                val body = try {
                    response.readBody()
                } catch (e: Throwable) {
                    Timber.tag("UpdateHttp").w(
                        "HTTP %d GET %s bodyReadFailed=%s",
                        response.code, url, e.message,
                    )
                    throw e
                }
                Timber.tag("UpdateHttp").w(
                    "HTTP %d GET %s bytes=%d head=%s",
                    response.code, url, body.length,
                    body.take(200).replace("\n", " ").replace("\r", " "),
                )
                UpdateHttpResponse(response.code, body)
            }
        }
    }

    private fun okhttp3.Response.readBody(): String {
        val charset = body.contentType()?.charset(StandardCharsets.UTF_8)
            ?: StandardCharsets.UTF_8
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(READ_BUFFER_SIZE)
        var total = 0L
        body.byteStream().use { stream ->
            while (total <= MAX_RESPONSE_BODY_BYTES) {
                val remaining = (MAX_RESPONSE_BODY_BYTES + 1 - total).toInt()
                val read = stream.read(buffer, 0, minOf(buffer.size, remaining))
                if (read < 0) break
                output.write(buffer, 0, read)
                total += read
            }
        }
        if (total > MAX_RESPONSE_BODY_BYTES) {
            throw UpdateSourceException(
                reason = UpdateCheckFailure.INVALID_RESPONSE,
                message = "Update response exceeds ${MAX_RESPONSE_BODY_BYTES} bytes",
            )
        }
        return String(output.toByteArray(), charset)
    }

    private companion object {
        const val MAX_RESPONSE_BODY_BYTES = 2 * 1024 * 1024
        const val READ_BUFFER_SIZE = 8 * 1024

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}

private suspend fun Call.await() = suspendCancellableCoroutine { continuation ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: okhttp3.Response) {
            continuation.resume(response) { _, _, _ -> response.close() }
        }
    })
    continuation.invokeOnCancellation { cancel() }
}
