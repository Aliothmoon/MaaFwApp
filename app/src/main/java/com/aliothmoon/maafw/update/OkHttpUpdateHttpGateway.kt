package com.aliothmoon.maafw.update

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class OkHttpUpdateHttpGateway(
    okHttpClient: OkHttpClient = defaultClient(),
) : UpdateHttpGateway {

    private val client = okHttpClient.newBuilder().build()

    override suspend fun get(url: String, headers: Map<String, String>): UpdateHttpResponse {
        val request = Request.Builder()
            .url(url.toHttpUrl())
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .get()
            .build()
        client.newCall(request).await().use { response ->
            return UpdateHttpResponse(response.code, response.body.string())
        }
    }

    private companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
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
