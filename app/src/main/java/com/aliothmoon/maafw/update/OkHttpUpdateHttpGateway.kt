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
    /** 响应体超过上限被截断；api 层按 INVALID_RESPONSE 处理，不当完整响应用 */
    val truncated: Boolean = false,
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
                val (body, truncated) = try {
                    response.readBody()
                } catch (e: Throwable) {
                    Timber.tag("UpdateHttp").w(
                        "HTTP %d GET %s bodyReadFailed=%s",
                        response.code, url, e.message,
                    )
                    throw e
                }
                Timber.tag("UpdateHttp").w(
                    "HTTP %d GET %s bytes=%d truncated=%b head=%s",
                    response.code, url, body.length, truncated,
                    body.take(200).replace("\n", " ").replace("\r", " "),
                )
                UpdateHttpResponse(response.code, body, truncated)
            }
        }
    }

    private fun okhttp3.Response.readBody(): Pair<String, Boolean> {
        val charset = body.contentType()?.charset(StandardCharsets.UTF_8)
            ?: StandardCharsets.UTF_8
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(READ_BUFFER_SIZE)
        var total = 0L
        var truncated = false
        body.byteStream().use { stream ->
            while (true) {
                val remaining = (MAX_RESPONSE_BODY_BYTES + 1 - total).toInt()
                if (remaining <= 0) {
                    truncated = true
                    break
                }
                val read = stream.read(buffer, 0, minOf(buffer.size, remaining))
                if (read < 0) break
                output.write(buffer, 0, read)
                total += read
            }
        }
        return String(output.toByteArray(), charset) to truncated
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
