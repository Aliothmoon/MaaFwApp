package com.aliothmoon.maafw.notification.provider

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resumeWithException

/**
 * 推送渠道共用的 HTTP 出口
 *
 * 只有 POST 两种形态：十个渠道要么发 JSON 要么发表单，没有一个要 GET。
 * 不做成通用 http 层——app 的其余部分不发网络请求（Markwon 自己持有 OkHttp 取图片）
 *
 * 返回原始 [Response] 而不是解析结果：各家的成功判据不一样（HTTP 码 / 业务 code /
 * 空响应体），由 provider 自己判，这里判不出通用的对错。调用方负责 `use {}` 关闭
 */
class NotificationHttpClient(private val okHttpClient: OkHttpClient) {

    suspend fun post(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
    ): Response {
        val request = Request.Builder()
            .url(url.toHttpUrl())
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return okHttpClient.newCall(request).await()
    }

    suspend fun postForm(
        url: String,
        params: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
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

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

/**
 * 取消协程时连着取消请求；已经拿到的响应在取消赛跑中要关掉，否则连接漏在池子里
 */
suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isCancelled) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, _, _ -> runCatching { response.close() } }
        }
    })
}
