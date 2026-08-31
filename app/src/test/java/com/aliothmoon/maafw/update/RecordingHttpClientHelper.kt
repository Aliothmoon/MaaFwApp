package com.aliothmoon.maafw.update

import com.aliothmoon.maafw.util.HttpClientHelper
import io.mockk.coEvery
import io.mockk.mockk
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

internal data class FakeHttpResponse(val code: Int, val body: String)

/** 按序吐响应并记录请求；桩在具体 helper 的 get 方法上，不为测试保留接口 */
internal class RecordingHttpClientHelper(
    vararg responses: FakeHttpResponse,
) {
    private val pendingResponses = ArrayDeque(responses.toList())

    /** first = 展开 query 后的完整 URL，second = 请求头 */
    val requests = mutableListOf<Pair<String, Map<String, String>>>()

    val mock: HttpClientHelper = mockk {
        coEvery { get(any(), any(), any()) } coAnswers {
            val fullUrl = firstArg<String>().toHttpUrl().newBuilder().apply {
                secondArg<Map<String, String?>>().forEach { (k, v) -> addQueryParameter(k, v) }
            }.build().toString()
            requests += fullUrl to thirdArg<Map<String, String>>()
            val next = pendingResponses.removeFirstOrNull() ?: error("No response was queued")
            Response.Builder()
                .request(Request.Builder().url(fullUrl).build())
                .protocol(Protocol.HTTP_1_1)
                .code(next.code)
                .message("mock")
                .body(next.body.toResponseBody("application/json; charset=utf-8".toMediaType()))
                .build()
        }
    }
}
