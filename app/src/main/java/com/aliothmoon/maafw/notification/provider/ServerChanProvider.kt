package com.aliothmoon.maafw.notification.provider

import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.i18n.uiTextOf
import com.aliothmoon.maafw.notification.NotificationSettingsManager
import kotlinx.serialization.Serializable
import timber.log.Timber

/**
 * Server 酱
 *
 * 两代 SendKey 打到不同的域名：`sctp<数字>t…` 是 Turbo 版，主机名里带着那串数字；
 * 其余走旧版统一入口
 */
class ServerChanProvider(
    private val httpClient: NotificationHttpClient,
    private val settingsManager: NotificationSettingsManager,
) : NotificationProvider {

    override val id = "ServerChan"

    override suspend fun send(title: String, content: String): NotificationSendResult {
        val settings = settingsManager.current()
        val sendKey = settings.serverChanSendKey.takeIf { it.isNotEmpty() }
            ?: return NotificationSendResult.Failed(
                uiTextOf(R.string.notification_err_serverchan_key_empty),
            )
        // 标题走 text 字段，那头有长度上限也不接受换行
        val normalizedTitle = title.replace("\n", "").take(TITLE_LIMIT)

        val url = if (sendKey.startsWith("sctp")) {
            val match = SCTP_PREFIX.find(sendKey)
                ?: return NotificationSendResult.Failed(
                    uiTextOf(R.string.notification_err_serverchan_sctp_fmt),
                )
            "https://${match.groupValues[1]}.push.ft07.com/send/$sendKey.send"
        } else {
            "https://sctapi.ftqq.com/$sendKey.send"
        }

        return runCatching {
            httpClient.postForm(
                url,
                mapOf("text" to normalizedTitle, "desp" to content),
            ).use { response ->
                val body = response.body.string()
                if (response.isSuccessful &&
                    providerJson.decodeFromString<ServerChanResponse>(body).code == 0
                ) {
                    NotificationSendResult.Success
                } else {
                    Timber.w("ServerChan rejected: HTTP %d, body=%s", response.code, body)
                    NotificationSendResult.Failed(
                        uiTextOf(R.string.notification_err_http_status, response.code),
                    )
                }
            }
        }.getOrElse {
            Timber.e(it, "ServerChan send failed")
            NotificationSendResult.Transient(uiTextOf(R.string.notification_err_network))
        }
    }

    @Serializable
    private data class ServerChanResponse(val code: Int = -1)

    private companion object {
        const val TITLE_LIMIT = 32
        val SCTP_PREFIX = Regex("""^sctp(\d+)t""")
    }
}
