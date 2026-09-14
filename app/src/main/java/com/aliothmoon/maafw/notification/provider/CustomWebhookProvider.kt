package com.aliothmoon.maafw.notification.provider

import com.aliothmoon.maafw.i18n.uiTextOf
import com.aliothmoon.maafw.notification.NotificationSettingsManager
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.util.HttpClientHelper
import java.time.format.DateTimeFormatter
import java.time.LocalDateTime
import timber.log.Timber

/**
 * 自定义 Webhook：请求体是用户写的模板，`{title}` `{content}` `{time}` 三个占位符
 *
 * 模板多半是一段 JSON，所以替换时要按 JSON 字符串的规矩处理换行：标题里的换行直接删掉，
 * 正文里的换成字面量 `\n`——原样塞进去会让整个请求体解不出来
 */
class CustomWebhookProvider(
    private val httpClient: HttpClientHelper,
    private val settingsManager: NotificationSettingsManager,
) : NotificationProvider {

    override val id = "CustomWebhook"

    override suspend fun send(title: String, content: String): NotificationSendResult {
        val settings = settingsManager.current()
        val url = settings.customWebhookUrl.takeIf { it.isNotEmpty() }
            ?: return NotificationSendResult.Failed(
                uiTextOf(R.string.notification_err_webhook_url_empty),
            )
        val bodyTemplate = settings.customWebhookBody.takeIf { it.isNotEmpty() }
            ?: return NotificationSendResult.Failed(
                uiTextOf(R.string.notification_err_webhook_body_empty),
            )

        val now = LocalDateTime.now().format(TIME_FORMAT)
        val body = bodyTemplate
            .replace("{title}", title.replace("\r", "").replace("\n", ""))
            .replace("{content}", content.replace("\r", "").replace("\n", "\\n"))
            .replace("{time}", now)

        // 一行一个 `名: 值`；没有冒号的行整行丢掉，不猜用户想写什么
        val headers = settings.customWebhookHeaders
            .replace("\r", "")
            .lineSequence()
            .mapNotNull { line ->
                val idx = line.indexOf(':')
                if (idx <= 0) null
                else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
            }
            .toMap()

        return runCatching {
            httpClient.post(url, body, headers = headers).use { response ->
                if (response.isSuccessful) {
                    NotificationSendResult.Success
                } else {
                    Timber.w(
                        "CustomWebhook rejected: HTTP %d, body=%s",
                        response.code,
                        response.body.string(),
                    )
                    NotificationSendResult.Failed(
                        uiTextOf(R.string.notification_err_http_status, response.code),
                    )
                }
            }
        }.getOrElse {
            Timber.e(it, "CustomWebhook send failed")
            NotificationSendResult.Transient(uiTextOf(R.string.notification_err_network))
        }
    }

    private companion object {
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}
