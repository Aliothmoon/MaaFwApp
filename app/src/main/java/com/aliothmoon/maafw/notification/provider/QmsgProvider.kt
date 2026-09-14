package com.aliothmoon.maafw.notification.provider

import com.aliothmoon.maafw.i18n.uiTextOf
import com.aliothmoon.maafw.notification.NotificationSettingsManager
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.util.HttpClientHelper
import kotlinx.serialization.Serializable
import timber.log.Timber

class QmsgProvider(
    private val httpClient: HttpClientHelper,
    private val settingsManager: NotificationSettingsManager,
) : NotificationProvider {

    override val id = "Qmsg"

    override suspend fun send(title: String, content: String): NotificationSendResult {
        val settings = settingsManager.current()
        val server = settings.qmsgServer.takeIf { it.isNotBlank() }?.trimEnd('/')
            ?: return NotificationSendResult.Failed(
                uiTextOf(R.string.notification_err_qmsg_server_empty),
            )
        val key = settings.qmsgKey.takeIf { it.isNotBlank() }
            ?: return NotificationSendResult.Failed(
                uiTextOf(R.string.notification_err_qmsg_key_empty),
            )
        val body = providerJson.encodeToString(
            QmsgRequest(
                msg = "$title: $content",
                qq = settings.qmsgUser,
                bot = settings.qmsgBot,
            ),
        )

        return runCatching {
            httpClient.post("$server/jsend/$key", body).use { response ->
                val responseBody = response.body.string()
                if (response.isSuccessful &&
                    providerJson.decodeFromString<QmsgResponse>(responseBody).success
                ) {
                    NotificationSendResult.Success
                } else {
                    Timber.w("Qmsg rejected: HTTP %d, body=%s", response.code, responseBody)
                    NotificationSendResult.Failed(
                        uiTextOf(R.string.notification_err_http_status, response.code),
                    )
                }
            }
        }.getOrElse {
            Timber.e(it, "Qmsg send failed")
            NotificationSendResult.Transient(uiTextOf(R.string.notification_err_network))
        }
    }

    @Serializable
    private data class QmsgRequest(val msg: String, val qq: String, val bot: String)

    @Serializable
    private data class QmsgResponse(val success: Boolean = false)
}
