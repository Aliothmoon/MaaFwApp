package com.aliothmoon.maafw.notification.provider

import com.aliothmoon.maafw.i18n.uiTextOf
import com.aliothmoon.maafw.notification.NotificationImage
import com.aliothmoon.maafw.notification.NotificationSettingsManager
import com.aliothmoon.maafw.notification.toPrefBoolean
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.util.HttpClientHelper
import kotlinx.serialization.Serializable
import okhttp3.MultipartBody
import kotlinx.serialization.SerialName
import timber.log.Timber

/**
 * KOOK 机器人推送；频道与私聊只差一个接口地址与 target_id 的含义
 */
class KookProvider(
    private val httpClient: HttpClientHelper,
    private val settingsManager: NotificationSettingsManager,
) : NotificationProvider {

    override val id = "KOOK"

    override val supportsImage = true

    override suspend fun send(title: String, content: String, image: NotificationImage): NotificationSendResult {
        val result = send(title, content)
        if (result is NotificationSendResult.Success) {
            runCatching { sendImage(image) }.onFailure { Timber.e(it, "KOOK image send failed") }
        }
        return result
    }

    private suspend fun sendImage(image: NotificationImage) {
        val settings = settingsManager.current()
        val headers = mapOf("Authorization" to "Bot ${settings.kookBotToken}")
        val url = uploadAsset(image, headers) ?: return
        val body = providerJson.encodeToString(
            KookRequest(type = MSG_TYPE_IMAGE, targetId = settings.kookTargetId, content = url),
        )
        val direct = settings.kookDirectMessage.toPrefBoolean()
        httpClient.post(if (direct) DIRECT_URL else CHANNEL_URL, body, headers = headers).use { response ->
            val responseBody = response.body.string()
            val code = runCatching { providerJson.decodeFromString<KookResponse>(responseBody).code }.getOrDefault(-1)
            if (!response.isSuccessful || code != 0) {
                Timber.w("KOOK image message rejected: HTTP %d, body=%s", response.code, responseBody)
            }
        }
    }

    /** 图片必须由机器人自己上传，外链会报找不到资源 */
    private suspend fun uploadAsset(image: NotificationImage, headers: Map<String, String>): String? {
        val upload = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", image.fileName, image.toRequestBody())
            .build()
        return httpClient.postMultipart(ASSET_URL, upload, headers).use { response ->
            val responseBody = response.body.string()
            val url = runCatching { providerJson.decodeFromString<KookAssetResponse>(responseBody) }
                .getOrNull()
                ?.takeIf { it.code == 0 }
                ?.data?.url
            if (url == null) Timber.w("KOOK asset upload rejected: HTTP %d, body=%s", response.code, responseBody)
            url
        }
    }

    override suspend fun send(title: String, content: String): NotificationSendResult {
        val settings = settingsManager.current()
        val botToken = settings.kookBotToken.takeIf { it.isNotEmpty() }
            ?: return NotificationSendResult.Failed(
                uiTextOf(R.string.notification_err_kook_token_empty),
            )
        val targetId = settings.kookTargetId.takeIf { it.isNotEmpty() }
            ?: return NotificationSendResult.Failed(
                uiTextOf(R.string.notification_err_kook_target_empty),
            )
        val direct = settings.kookDirectMessage.toPrefBoolean()

        val body = providerJson.encodeToString(
            KookRequest(
                type = MSG_TYPE_KMARKDOWN,
                targetId = targetId,
                content = "**$title**\n$content".fitChars(MAX_CONTENT_LENGTH),
            ),
        )

        return runCatching {
            httpClient.post(
                url = if (direct) DIRECT_URL else CHANNEL_URL,
                body = body,
                headers = mapOf("Authorization" to "Bot $botToken"),
            ).use { response ->
                val responseBody = response.body.string()
                val parsed = runCatching {
                    providerJson.decodeFromString<KookResponse>(responseBody)
                }.getOrNull()
                when {
                    response.isSuccessful && parsed?.code == 0 -> NotificationSendResult.Success

                    response.code == 429 || response.code >= 500 -> {
                        Timber.w("KOOK transient: HTTP %d, body=%s", response.code, responseBody)
                        NotificationSendResult.Transient(
                            uiTextOf(R.string.notification_err_http_status, response.code),
                        )
                    }

                    // 鉴权失败、target 不存在都是 HTTP 200 + 业务码，只报 HTTP 码等于没报
                    parsed != null -> {
                        Timber.w("KOOK rejected: HTTP %d, body=%s", response.code, responseBody)
                        NotificationSendResult.Failed(
                            uiTextOf(
                                R.string.notification_err_kook_api,
                                parsed.code,
                                parsed.message.ifBlank { "-" },
                            ),
                        )
                    }

                    else -> {
                        Timber.w("KOOK rejected: HTTP %d, body=%s", response.code, responseBody)
                        NotificationSendResult.Failed(
                            uiTextOf(R.string.notification_err_http_status, response.code),
                        )
                    }
                }
            }
        }.getOrElse {
            Timber.e(it, "KOOK send failed")
            NotificationSendResult.Transient(uiTextOf(R.string.notification_err_network))
        }
    }

    @Serializable
    private data class KookRequest(
        val type: Int,
        @SerialName("target_id") val targetId: String,
        val content: String,
    )

    @Serializable
    private data class KookResponse(val code: Int = -1, val message: String = "")

    @Serializable
    private data class KookAssetResponse(val code: Int = -1, val data: KookAsset? = null)

    @Serializable
    private data class KookAsset(val url: String? = null)

    private companion object {
        const val CHANNEL_URL = "https://www.kookapp.cn/api/v3/message/create"
        const val DIRECT_URL = "https://www.kookapp.cn/api/v3/direct-message/create"
        const val ASSET_URL = "https://www.kookapp.cn/api/v3/asset/create"
        const val MSG_TYPE_IMAGE = 2

        /** KOOK 消息类型 9 = KMarkdown */
        const val MSG_TYPE_KMARKDOWN = 9

        /** 文档建议消息内容不超过 8000 字符 */
        const val MAX_CONTENT_LENGTH = 8000
    }
}
