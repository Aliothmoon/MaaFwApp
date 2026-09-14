package com.aliothmoon.maafw.notification.provider

import com.aliothmoon.maafw.i18n.uiTextOf
import com.aliothmoon.maafw.notification.NotificationSettingsManager
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.util.HttpClientHelper
import java.net.URI
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import timber.log.Timber

class BarkProvider(
    private val httpClient: HttpClientHelper,
    private val settingsManager: NotificationSettingsManager,
) : NotificationProvider {

    override val id = "Bark"

    override suspend fun send(title: String, content: String): NotificationSendResult {
        val settings = settingsManager.current()
        val barkServer = settings.barkServer.takeIf { it.isNotEmpty() }
            ?: return NotificationSendResult.Failed(
                uiTextOf(R.string.notification_err_bark_server_empty),
            )
        val sendKey = settings.barkSendKey.takeIf { it.isNotEmpty() }
            ?: return NotificationSendResult.Failed(
                uiTextOf(R.string.notification_err_bark_key_empty),
            )

        // 自建服务可能挂在子路径上，用 resolve 而不是拼串：末尾没有斜杠时 resolve 会吃掉最后一段
        val url = URI.create(barkServer.trimEnd('/') + "/").resolve("push").toString()
        val body = providerJson.encodeToString(
            BarkRequest(title = title, body = content, deviceKey = sendKey),
        )

        return runCatching {
            httpClient.post(url, body).use { response ->
                val responseBody = response.body.string()
                if (response.isSuccessful &&
                    providerJson.decodeFromString<BarkResponse>(responseBody).code == 200
                ) {
                    NotificationSendResult.Success
                } else {
                    Timber.w("Bark rejected: HTTP %d, body=%s", response.code, responseBody)
                    NotificationSendResult.Failed(
                        uiTextOf(R.string.notification_err_http_status, response.code),
                    )
                }
            }
        }.getOrElse {
            Timber.e(it, "Bark send failed")
            NotificationSendResult.Transient(uiTextOf(R.string.notification_err_network))
        }
    }

    @Serializable
    private data class BarkRequest(
        val title: String,
        val body: String,
        @SerialName("device_key") val deviceKey: String,
        /** 同一分组在 iOS 通知中心里会折叠成一摞，不至于把整屏刷满 */
        val group: String = "MaaFwApp",
    )

    @Serializable
    private data class BarkResponse(val code: Int = -1)
}
