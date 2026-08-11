package com.aliothmoon.maafw.notification.provider

import androidx.core.text.htmlEncode
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.i18n.uiTextOf
import com.aliothmoon.maafw.notification.NotificationSettingsManager
import com.aliothmoon.maafw.notification.toPrefBoolean
import jakarta.mail.Authenticator
import jakarta.mail.Message
import jakarta.mail.PasswordAuthentication
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import timber.log.Timber
import java.util.Date
import java.util.Properties

/**
 * SMTP 发信；唯一不走 HTTP 的渠道
 *
 * `Transport.send` 是阻塞的，靠调用方（[com.aliothmoon.maafw.notification.ExternalNotificationService]）
 * 已经在 IO 上跑来兜住。超时全部显式给死：默认是无限等，服务器不回时整条发送协程会一直挂着
 */
class SmtpProvider(
    private val settingsManager: NotificationSettingsManager,
) : NotificationProvider {

    override val id = "SMTP"

    override suspend fun send(title: String, content: String): NotificationSendResult {
        val settings = settingsManager.current()
        val server = settings.smtpServer.takeIf { it.isNotBlank() }
            ?: return NotificationSendResult.Failed(
                uiTextOf(R.string.notification_err_smtp_server_empty),
            )
        val port = settings.smtpPort.toIntOrNull()
            ?: return NotificationSendResult.Failed(
                uiTextOf(R.string.notification_err_smtp_port_invalid),
            )
        val from = settings.smtpFrom.takeIf { it.isNotBlank() }
            ?: return NotificationSendResult.Failed(
                uiTextOf(R.string.notification_err_smtp_from_empty),
            )
        val to = settings.smtpTo.takeIf { it.isNotBlank() }
            ?: return NotificationSendResult.Failed(
                uiTextOf(R.string.notification_err_smtp_to_empty),
            )
        val useSsl = settings.smtpUseSsl.toPrefBoolean()
        val requireAuthentication = settings.smtpRequireAuthentication.toPrefBoolean()
        val user = settings.smtpUser
        val password = settings.smtpPassword

        if (requireAuthentication && (user.isBlank() || password.isBlank())) {
            return NotificationSendResult.Failed(
                uiTextOf(R.string.notification_err_smtp_auth_empty),
            )
        }

        val properties = Properties().apply {
            put("mail.transport.protocol", "smtp")
            put("mail.smtp.host", server)
            put("mail.smtp.port", port.toString())
            put("mail.smtp.auth", requireAuthentication.toString())
            put("mail.smtp.ssl.enable", useSsl.toString())
            put("mail.smtp.connectiontimeout", CONNECT_TIMEOUT_MS)
            put("mail.smtp.timeout", READ_TIMEOUT_MS)
            put("mail.smtp.writetimeout", READ_TIMEOUT_MS)
        }

        val authenticator = if (requireAuthentication) {
            object : Authenticator() {
                override fun getPasswordAuthentication() = PasswordAuthentication(user, password)
            }
        } else {
            null
        }

        // 主题不能带换行：MIME 头按行分隔，塞进去等于伪造出一个头（header injection）
        val sanitizedTitle = title.replace("\r", "").replace("\n", "")

        return runCatching {
            val session = Session.getInstance(properties, authenticator)
            val message = MimeMessage(session).apply {
                setFrom(InternetAddress(from))
                setRecipients(Message.RecipientType.TO, InternetAddress.parse(to))
                subject = sanitizedTitle
                sentDate = Date()
                setContent(buildHtmlBody(sanitizedTitle, content), "text/html; charset=UTF-8")
            }
            Transport.send(message)
            NotificationSendResult.Success
        }.getOrElse {
            Timber.e(it, "SMTP send failed")
            NotificationSendResult.Transient(uiTextOf(R.string.notification_err_network))
        }
    }

    /** 正文进的是 HTML，标题与内容都得转义——PI 的任务名里出现 `<` 就会把版面吃掉 */
    private fun buildHtmlBody(title: String, content: String): String {
        val safeTitle = title.htmlEncode()
        val safeContent = content.htmlEncode()
            .replace("\r", "")
            .replace("\n", "<br/>")

        return """
            <html lang="en">
            <body style="font-family: sans-serif; color: #222222; line-height: 1.6;">
                <h2>$safeTitle</h2>
                <p>$safeContent</p>
            </body>
            </html>
        """.trimIndent()
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = "30000"
        const val READ_TIMEOUT_MS = "60000"
    }
}
