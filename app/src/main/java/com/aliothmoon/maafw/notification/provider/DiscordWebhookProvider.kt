package com.aliothmoon.maafw.notification.provider

import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.i18n.uiTextOf
import com.aliothmoon.maafw.notification.NotificationSettingsManager
import kotlinx.serialization.Serializable
import timber.log.Timber

/**
 * Discord Webhook：不需要 bot，只要一条 webhook 地址
 *
 * 成功的响应体是**空的**（HTTP 204），失败才回一段 JSON——所以判据是「成功且体为空」，
 * 只看 HTTP 码会把限流那种带 JSON 的 200 也算成发出去了
 */
class DiscordWebhookProvider(
    private val httpClient: NotificationHttpClient,
    private val settingsManager: NotificationSettingsManager,
) : NotificationProvider {

    override val id = "Discord Webhook"

    override suspend fun send(title: String, content: String): NotificationSendResult {
        val settings = settingsManager.current()
        val webhookUrl = settings.discordWebhookUrl.takeIf { it.isNotBlank() }
            ?: return NotificationSendResult.Failed(
                uiTextOf(R.string.notification_err_discord_webhook_empty),
            )
        val body = providerJson.encodeToString(
            DiscordWebhookRequest(content = "$title\n$content"),
        )

        return runCatching {
            httpClient.post(webhookUrl, body).use { response ->
                val responseBody = response.body.string()
                if (response.isSuccessful && responseBody.isEmpty()) {
                    return@use NotificationSendResult.Success
                }

                val errorResponse = runCatching {
                    providerJson.decodeFromString<DiscordWebhookErrorResponse>(responseBody)
                }.getOrNull()
                if (errorResponse == null) {
                    Timber.w("Discord Webhook failed with non-JSON response: %s", responseBody)
                } else {
                    Timber.w(
                        "Discord Webhook failed: %s (%s)",
                        errorResponse.message,
                        errorResponse.code,
                    )
                }
                NotificationSendResult.Failed(
                    uiTextOf(R.string.notification_err_http_status, response.code),
                )
            }
        }.getOrElse {
            Timber.e(it, "Discord Webhook send failed")
            NotificationSendResult.Transient(uiTextOf(R.string.notification_err_network))
        }
    }

    @Serializable
    private data class DiscordWebhookRequest(val content: String)

    @Serializable
    private data class DiscordWebhookErrorResponse(
        val message: String? = null,
        val code: Int? = null,
    )
}
