package com.aliothmoon.maafw.util

import com.aliothmoon.maafw.MaaDispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import timber.log.Timber
import java.io.IOException
import kotlin.coroutines.resumeWithException

/**
 * 进程级共享的 HTTP 出口，update 与 notification 共用
 *
 * 返回原始 [Response] 而不是解析结果：各调用方的成功判据不一样（HTTP 码 / 业务 code /
 * 空响应体），这里判不出通用的对错。调用方负责 `use {}` 关闭；读完整 body 走 [readBody]
 */
class HttpClientHelper(
    private val okHttpClient: OkHttpClient
) {
    companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val CONTENT_TYPE = "Content-Type"
    }


    /**
     * 不要在 [headers] 里传 Accept-Encoding：那会关掉 OkHttp 的透明 gzip 与自动解压，
     * 调用方读到的将是压缩字节
     */
    suspend fun get(
        url: String,
        query: Map<String, String?> = emptyMap(),
        headers: Map<String, String> = emptyMap()
    ): Response {
        val request = Request.Builder().apply {
            val requestUrl = url.toHttpUrl().run {
                if (query.isEmpty()) {
                    this
                } else {
                    newBuilder().also { builder ->
                        for (it in query) {
                            builder.addQueryParameter(it.key, it.value)
                        }
                    }.build()

                }
            }
            url(requestUrl)
        }.apply { headers.forEach { (k, v) -> header(k, v) } }
            .get()
            .build()
            .also { Timber.d("GET ${it.url.host}") }
        return okHttpClient.newCall(request).await()
    }

    suspend inline fun <reified T> getEntity(
        url: String,
        query: Map<String, String?> = emptyMap(),
        headers: Map<String, String> = emptyMap()
    ): T {
        return JsonUtils.common.decodeFromString<T>(get(url, query, headers).readBody())
    }

    /**
     * [headers] 里的 Content-Type 会被拿来当请求体的 media type：
     * OkHttp 的 BridgeInterceptor 在请求体 contentType 非空时会无条件覆盖同名请求头，
     * 单纯 header("Content-Type", ...) 会被静默丢掉
     */
    suspend fun post(
        url: String,
        body: String,
        query: Map<String, String?> = emptyMap(),
        headers: Map<String, String> = emptyMap()
    ): Response {
        val contentTypeEntry = headers.entries
            .firstOrNull { it.key.equals(CONTENT_TYPE, ignoreCase = true) }
        val mediaType = contentTypeEntry?.value?.toMediaTypeOrNull()
            ?: JSON_MEDIA_TYPE.also {
                if (contentTypeEntry != null) {
                    Timber.w("Content-Type 无法解析，按 JSON 发送: %s", contentTypeEntry.value)
                }
            }
        val request = Request.Builder().apply {
            val requestUrl = url.toHttpUrl().run {
                if (query.isEmpty()) {
                    this
                } else {
                    newBuilder().also { builder ->
                        for (it in query) {
                            builder.addQueryParameter(it.key, it.value)
                        }
                    }.build()

                }
            }
            url(requestUrl)
        }.apply {
            headers.forEach { (k, v) -> if (k != contentTypeEntry?.key) header(k, v) }
        }
            .post(body.toRequestBody(mediaType)).build()
        return okHttpClient.newCall(request).await()
    }


    suspend fun postForm(
        url: String,
        params: Map<String, String>,
        headers: Map<String, String> = emptyMap()
    ): Response {
        val formBody = FormBody.Builder().apply {
            params.forEach { (k, v) -> add(k, v) }
        }.build()
        val request = Request.Builder()
            .url(url.toHttpUrl())
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .post(formBody)
            .build()
        return okHttpClient.newCall(request).await()
    }

    fun rawClient(): OkHttpClient = okHttpClient

}

/**
 * enqueue 只把响应头带回来，body 仍是 socket 读——在主线程直接 `body.string()`
 * 会抛 NetworkOnMainThreadException，读完整 body 一律走这里
 */
suspend fun Response.readBody(): String =
    withContext(MaaDispatchers.IO) { use { it.body.string() } }

/**
 * 取消协程时连着取消请求；已经拿到的响应在取消赛跑中要关掉，否则连接漏在池子里
 */
suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, _, _ -> runCatching { response.close() } }
        }
    })
}
