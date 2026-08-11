package com.aliothmoon.maafw.notification.provider

import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.i18n.uiTextOf
import com.aliothmoon.maafw.notification.NotificationSettingsManager
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import timber.log.Timber

/**
 * Discord 机器人私信
 *
 * 两跳：先拿 bot token 为目标用户开一条私信频道，再往那个频道发消息。
 * 频道 id 不缓存——用户可能换 bot 或换收件人，缓存过期时的表现是「发到了别处」
 */
class DiscordProvider(
    private val httpClient: NotificationHttpClient,
    private val settingsManager: NotificationSettingsManager,
) : NotificationProvider {

    override val id = "Discord"

    override suspend fun send(title: String, content: String): NotificationSendResult {
        val settings = settingsManager.current()
        val botToken = settings.discordBotToken.takeIf { it.isNotBlank() }
            ?: return NotificationSendResult.Failed(
                uiTextOf(R.string.notification_err_discord_token_empty),
            )
        val userId = settings.discordUserId.takeIf { it.isNotBlank() }
            ?: return NotificationSendResult.Failed(
                uiTextOf(R.string.notification_err_discord_user_empty),
            )

        val channelId = when (val dm = createDmChannel(botToken, userId)) {
            is DmChannelResult.Success -> dm.channelId
            DmChannelResult.Rejected -> return NotificationSendResult.Failed(
                uiTextOf(R.string.notification_err_discord_dm_failed),
            )

            DmChannelResult.NetworkError ->
                return NotificationSendResult.Transient(uiTextOf(R.string.notification_err_network))
        }

        return runCatching {
            httpClient.postForm(
                url = "https://discord.com/api/v9/channels/$channelId/messages",
                params = mapOf("content" to "$title\n$content"),
                headers = discordHeaders(botToken),
            ).use { response ->
                if (response.isSuccessful) {
                    NotificationSendResult.Success
                } else {
                    Timber.w(
                        "Discord rejected: HTTP %d, body=%s",
                        response.code,
                        response.body.string(),
                    )
                    NotificationSendResult.Failed(
                        uiTextOf(R.string.notification_err_http_status, response.code),
                    )
                }
            }
        }.getOrElse {
            Timber.e(it, "Discord send failed")
            NotificationSendResult.Transient(uiTextOf(R.string.notification_err_network))
        }
    }

    private suspend fun createDmChannel(botToken: String, userId: String): DmChannelResult {
        val body = providerJson.encodeToString(DiscordCreateChannelRequest(recipientId = userId))

        return runCatching {
            httpClient.post(
                url = "https://discord.com/api/v9/users/@me/channels",
                body = body,
                headers = discordHeaders(botToken),
            ).use { response ->
                if (!response.isSuccessful) return@use DmChannelResult.Rejected

                val id = providerJson
                    .decodeFromString<DiscordCreateChannelResponse>(response.body.string()).id
                if (id != null) DmChannelResult.Success(id) else DmChannelResult.Rejected
            }
        }.getOrElse {
            Timber.e(it, "Discord create DM channel failed")
            DmChannelResult.NetworkError
        }
    }

    /** 不带 User-Agent 会被 Cloudflare 拦在门外 */
    private fun discordHeaders(botToken: String): Map<String, String> = mapOf(
        "Authorization" to "Bot $botToken",
        "User-Agent" to "DiscordBot",
    )

    private sealed interface DmChannelResult {
        data class Success(val channelId: String) : DmChannelResult
        data object Rejected : DmChannelResult
        data object NetworkError : DmChannelResult
    }

    @Serializable
    private data class DiscordCreateChannelRequest(
        @SerialName("recipient_id") val recipientId: String,
    )

    @Serializable
    private data class DiscordCreateChannelResponse(val id: String? = null)
}
