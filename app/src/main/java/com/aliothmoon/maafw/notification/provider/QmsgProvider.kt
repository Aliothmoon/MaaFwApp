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
                msg = "$title: $content".fitChars(MAX_MSG_LENGTH),
                qq = settings.qmsgUser,
                bot = settings.qmsgBot,
            ),
        )

        return runCatching {
            httpClient.post("$server/jsend/$key", body).use { response ->
                val responseBody = response.body.string()
                val parsed = runCatching {
                    providerJson.decodeFromString<QmsgResponse>(responseBody)
                }.getOrNull()
                if (response.isSuccessful && parsed?.success == true) {
                    return@use NotificationSendResult.Success
                }
                Timber.w("Qmsg rejected: HTTP %d, body=%s", response.code, responseBody)
                // 业务失败也是 HTTP 200，原因在 reason 里；只报状态码等于没报
                val reason = listOfNotNull(parsed?.reason, parsed?.message).firstOrNull { it.isNotBlank() }
                NotificationSendResult.Failed(
                    if (reason != null) {
                        uiTextOf(R.string.notification_err_rejected, reason)
                    } else {
                        uiTextOf(R.string.notification_err_http_status, response.code)
                    },
                )
            }
        }.getOrElse {
            Timber.e(it, "Qmsg send failed")
            NotificationSendResult.Transient(uiTextOf(R.string.notification_err_network))
        }
    }

    @Serializable
    private data class QmsgRequest(val msg: String, val qq: String, val bot: String)

    /** 拒收时 reason 与 message 是同一句，哪个有取哪个 */
    @Serializable
    private data class QmsgResponse(
        val success: Boolean = false,
        val reason: String? = null,
        val message: String? = null,
    )

    private companion object {
        /** 超了接口回「消息长度不能超过1800个字」 */
        const val MAX_MSG_LENGTH = 1800
    }
}
