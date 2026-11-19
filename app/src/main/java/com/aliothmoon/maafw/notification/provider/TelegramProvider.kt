package com.aliothmoon.maafw.notification.provider

import com.aliothmoon.maafw.i18n.uiTextOf
import com.aliothmoon.maafw.notification.NotificationImage
import com.aliothmoon.maafw.notification.NotificationSettingsManager
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.util.HttpClientHelper
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import okhttp3.MultipartBody
import timber.log.Timber

class TelegramProvider(
    private val httpClient: HttpClientHelper,
    private val settingsManager: NotificationSettingsManager,
) : NotificationProvider {

    override val id = "Telegram"

    override val supportsImage = true

    /** sendPhoto 的 caption 只有 1024 字，放不下日志，文字和图片分两条发 */
    override suspend fun send(title: String, content: String, image: NotificationImage): NotificationSendResult {
        val result = send(title, content)
        if (result is NotificationSendResult.Success) sendPhoto(image)
        return result
    }

    private suspend fun sendPhoto(image: NotificationImage) {
        val settings = settingsManager.current()
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", settings.telegramChatId)
            .apply {
                settings.telegramTopicId.takeIf { it.isNotEmpty() }?.let { addFormDataPart("message_thread_id", it) }
            }
            .addFormDataPart("photo", image.fileName, image.toRequestBody())
            .build()
        runCatching {
            httpClient.postMultipart("https://api.telegram.org/bot${settings.telegramBotToken}/sendPhoto", body)
                .use { response ->
                    val responseBody = response.body.string()
                    val ok = runCatching { providerJson.decodeFromString<TelegramResponse>(responseBody).ok }
                        .getOrDefault(false)
                    if (!response.isSuccessful || !ok) {
                        Timber.w("Telegram sendPhoto rejected: HTTP %d, body=%s", response.code, responseBody)
                    }
                }
        }.onFailure { Timber.e(it, "Telegram sendPhoto failed") }
    }

    override suspend fun send(title: String, content: String): NotificationSendResult {
        val settings = settingsManager.current()
        val botToken = settings.telegramBotToken.takeIf { it.isNotEmpty() }
            ?: return NotificationSendResult.Failed(
                uiTextOf(R.string.notification_err_telegram_token_empty),
            )
        val chatId = settings.telegramChatId.takeIf { it.isNotEmpty() }
            ?: return NotificationSendResult.Failed(
                uiTextOf(R.string.notification_err_telegram_chat_empty),
            )

        val url = "https://api.telegram.org/bot$botToken/sendMessage"
        val body = providerJson.encodeToString(
            TelegramRequest(
                chatId = chatId,
                text = "$title: $content".fitChars(MAX_TEXT_LENGTH),
                messageThreadId = settings.telegramTopicId.takeIf { it.isNotEmpty() },
            ),
        )

        return runCatching {
            httpClient.post(url, body).use { response ->
                val responseBody = response.body.string()
                if (response.isSuccessful &&
                    providerJson.decodeFromString<TelegramResponse>(responseBody).ok
                ) {
                    NotificationSendResult.Success
                } else {
                    Timber.w("Telegram rejected: HTTP %d, body=%s", response.code, responseBody)
                    NotificationSendResult.Failed(
                        uiTextOf(R.string.notification_err_http_status, response.code),
                    )
                }
            }
        }.getOrElse {
            Timber.e(it, "Telegram send failed")
            NotificationSendResult.Transient(uiTextOf(R.string.notification_err_network))
        }
    }

    @Serializable
    private data class TelegramRequest(
        @SerialName("chat_id") val chatId: String,
        val text: String,
        @SerialName("message_thread_id") val messageThreadId: String? = null,
    )

    @Serializable
    private data class TelegramResponse(val ok: Boolean = false)

    private companion object {
        /** sendMessage 的 text 上限，超了直接 400 整条发不出去 */
        const val MAX_TEXT_LENGTH = 4096
    }
}
