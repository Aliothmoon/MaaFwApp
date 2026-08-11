package com.aliothmoon.maafw.notification.provider

import android.util.Base64
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.i18n.uiTextOf
import com.aliothmoon.maafw.notification.NotificationSettingsManager
import kotlinx.serialization.Serializable
import timber.log.Timber
import java.net.URLEncoder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 钉钉自定义机器人
 *
 * 机器人安全设置选「加签」时才要填 secret：签名是 `timestamp\nsecret` 的
 * HmacSHA256，再 Base64 + URL 编码挂到查询串上。选「关键词」或「IP 白名单」的留空
 */
class DingTalkProvider(
    private val httpClient: NotificationHttpClient,
    private val settingsManager: NotificationSettingsManager,
) : NotificationProvider {

    override val id = "DingTalk"

    override suspend fun send(title: String, content: String): NotificationSendResult {
        val settings = settingsManager.current()
        val accessToken = settings.dingTalkAccessToken.takeIf { it.isNotEmpty() }
            ?: return NotificationSendResult.Failed(
                uiTextOf(R.string.notification_err_dingtalk_token_empty),
            )

        var url = "https://oapi.dingtalk.com/robot/send?access_token=$accessToken"
        settings.dingTalkSecret.takeIf { it.isNotEmpty() }?.let { secret ->
            val timestamp = System.currentTimeMillis()
            val mac = Mac.getInstance(SIGN_ALGORITHM)
            mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), SIGN_ALGORITHM))
            val signed = mac.doFinal("$timestamp\n$secret".toByteArray(Charsets.UTF_8))
            // NO_WRAP：默认会插换行，带进 URL 就是一个签不上的签名
            val sign = URLEncoder.encode(Base64.encodeToString(signed, Base64.NO_WRAP), "UTF-8")
            url += "&timestamp=$timestamp&sign=$sign"
        }

        val body = providerJson.encodeToString(
            DingTalkRequest(msgtype = "text", text = DingTalkText("$title: $content")),
        )

        return runCatching {
            httpClient.post(url, body).use { response ->
                val responseBody = response.body.string()
                if (response.isSuccessful &&
                    providerJson.decodeFromString<DingTalkResponse>(responseBody).errcode == 0
                ) {
                    NotificationSendResult.Success
                } else {
                    Timber.w("DingTalk rejected: HTTP %d, body=%s", response.code, responseBody)
                    NotificationSendResult.Failed(
                        uiTextOf(R.string.notification_err_http_status, response.code),
                    )
                }
            }
        }.getOrElse {
            Timber.e(it, "DingTalk send failed")
            NotificationSendResult.Transient(uiTextOf(R.string.notification_err_network))
        }
    }

    @Serializable
    private data class DingTalkRequest(val msgtype: String, val text: DingTalkText)

    @Serializable
    private data class DingTalkText(val content: String)

    @Serializable
    private data class DingTalkResponse(val errcode: Int = -1)

    private companion object {
        const val SIGN_ALGORITHM = "HmacSHA256"
    }
}
