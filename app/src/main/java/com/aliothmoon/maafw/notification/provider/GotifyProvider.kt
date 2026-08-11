package com.aliothmoon.maafw.notification.provider

import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.i18n.uiTextOf
import com.aliothmoon.maafw.notification.NotificationSettingsManager
import kotlinx.serialization.Serializable
import timber.log.Timber
import java.net.URI

class GotifyProvider(
    private val httpClient: NotificationHttpClient,
    private val settingsManager: NotificationSettingsManager,
) : NotificationProvider {

    override val id = "Gotify"

    override suspend fun send(title: String, content: String): NotificationSendResult {
        val settings = settingsManager.current()
        val rawServer = settings.gotifyServer.takeIf { it.isNotBlank() }?.trim()?.trimEnd('/')
            ?: return NotificationSendResult.Failed(
                uiTextOf(R.string.notification_err_gotify_server_empty),
            )
        val token = settings.gotifyToken.takeIf { it.isNotBlank() }
            ?: return NotificationSendResult.Failed(
                uiTextOf(R.string.notification_err_gotify_token_empty),
            )
        val baseUri = runCatching { URI.create("$rawServer/") }.getOrNull()
            ?: return NotificationSendResult.Failed(
                uiTextOf(R.string.notification_err_gotify_scheme),
            )
        // 自建服务地址多半是手填的，漏掉协议头时 OkHttp 抛的是 IllegalArgumentException，
        // 会被外层 runCatching 报成网络失败——那个提示指不到真正要改的地方
        if (baseUri.scheme !in setOf("http", "https")) {
            return NotificationSendResult.Failed(uiTextOf(R.string.notification_err_gotify_scheme))
        }

        val body = providerJson.encodeToString(GotifyRequest(title = title, message = content))

        return runCatching {
            httpClient.post(
                url = baseUri.resolve("message").toString(),
                body = body,
                headers = mapOf("X-Gotify-Key" to token),
            ).use { response ->
                val responseBody = response.body.string()
                if (response.isSuccessful &&
                    providerJson.decodeFromString<GotifyResponse>(responseBody).id != null
                ) {
                    NotificationSendResult.Success
                } else {
                    Timber.w("Gotify rejected: HTTP %d, body=%s", response.code, responseBody)
                    NotificationSendResult.Failed(
                        uiTextOf(R.string.notification_err_http_status, response.code),
                    )
                }
            }
        }.getOrElse {
            Timber.e(it, "Gotify send failed")
            NotificationSendResult.Transient(uiTextOf(R.string.notification_err_network))
        }
    }

    @Serializable
    private data class GotifyRequest(val title: String, val message: String)

    @Serializable
    private data class GotifyResponse(val id: Int? = null)
}
