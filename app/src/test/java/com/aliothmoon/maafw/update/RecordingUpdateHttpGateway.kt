package com.aliothmoon.maafw.update

import io.mockk.coEvery
import io.mockk.mockk

/** 按序吐响应并记录请求；桩在具体网关的 get 方法上，不为测试保留接口 */
internal class RecordingUpdateHttpGateway(
    vararg responses: UpdateHttpResponse,
) {
    private val pendingResponses = ArrayDeque(responses.toList())
    val requests = mutableListOf<Pair<String, Map<String, String>>>()
    var networkError: Exception? = null

    val mock: OkHttpUpdateHttpGateway = mockk {
        coEvery { get(any(), any()) } coAnswers {
            requests += firstArg<String>() to secondArg()
            networkError?.let { throw it }
            pendingResponses.removeFirstOrNull() ?: error("No response was queued")
        }
    }
}
