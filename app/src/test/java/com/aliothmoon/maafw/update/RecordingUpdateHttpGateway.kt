package com.aliothmoon.maafw.update

class RecordingUpdateHttpGateway(
    vararg responses: UpdateHttpResponse,
) : UpdateHttpGateway {

    private val pendingResponses = ArrayDeque(responses.toList())
    val requests = mutableListOf<Pair<String, Map<String, String>>>()
    var networkError: Exception? = null

    override suspend fun get(url: String, headers: Map<String, String>): UpdateHttpResponse {
        requests += url to headers
        networkError?.let { throw it }
        return pendingResponses.removeFirstOrNull()
            ?: error("No response was queued for $url")
    }
}
