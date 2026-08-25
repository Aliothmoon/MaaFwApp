package com.aliothmoon.maafw.update

data class UpdateHttpResponse(
    val statusCode: Int,
    val body: String,
)

/** HTTP GET boundary for update providers, so source adapters stay JVM-testable. */
internal interface UpdateHttpGateway {
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): UpdateHttpResponse
}
