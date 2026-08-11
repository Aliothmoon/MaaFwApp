package com.aliothmoon.maafw.notification

import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.i18n.UiText
import com.aliothmoon.maafw.notification.provider.BarkProvider
import com.aliothmoon.maafw.notification.provider.CustomWebhookProvider
import com.aliothmoon.maafw.notification.provider.DingTalkProvider
import com.aliothmoon.maafw.notification.provider.DiscordProvider
import com.aliothmoon.maafw.notification.provider.DiscordWebhookProvider
import com.aliothmoon.maafw.notification.provider.GotifyProvider
import com.aliothmoon.maafw.notification.provider.KookProvider
import com.aliothmoon.maafw.notification.provider.NotificationHttpClient
import com.aliothmoon.maafw.notification.provider.NotificationSendResult
import com.aliothmoon.maafw.notification.provider.QmsgProvider
import com.aliothmoon.maafw.notification.provider.ServerChanProvider
import com.aliothmoon.maafw.notification.provider.SmtpProvider
import com.aliothmoon.maafw.notification.provider.TelegramProvider
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配置不全时应当在**发请求之前**就判失败
 *
 * httpClient 是没有任何 stub 的 mock：真走到网络那一步会当场抛，正好把「漏判空配置」
 * 暴露成测试失败而不是一次线上超时
 */
class NotificationProviderConfigTest {

    private val httpClient = mockk<NotificationHttpClient>()

    private fun manager(settings: NotificationSettings = NotificationSettings()) =
        mockk<NotificationSettingsManager> { coEvery { current() } returns settings }

    private fun assertFailed(result: NotificationSendResult, expectedResId: Int) {
        assertTrue("expected Failed but was $result", result is NotificationSendResult.Failed)
        val message = (result as NotificationSendResult.Failed).message
        assertEquals(expectedResId, (message as UiText.Resource).resId)
    }

    @Test
    fun serverChanEmptyKey() = runBlocking {
        assertFailed(
            ServerChanProvider(httpClient, manager()).send("t", "c"),
            R.string.notification_err_serverchan_key_empty,
        )
    }

    @Test
    fun serverChanSctpBadFormat() = runBlocking {
        assertFailed(
            ServerChanProvider(
                httpClient,
                manager(NotificationSettings(serverChanSendKey = "sctpNOTNUM")),
            ).send("t", "c"),
            R.string.notification_err_serverchan_sctp_fmt,
        )
    }

    @Test
    fun telegramEmptyToken() = runBlocking {
        assertFailed(
            TelegramProvider(httpClient, manager()).send("t", "c"),
            R.string.notification_err_telegram_token_empty,
        )
    }

    @Test
    fun telegramEmptyChat() = runBlocking {
        assertFailed(
            TelegramProvider(
                httpClient,
                manager(NotificationSettings(telegramBotToken = "tok")),
            ).send("t", "c"),
            R.string.notification_err_telegram_chat_empty,
        )
    }

    @Test
    fun discordEmptyToken() = runBlocking {
        assertFailed(
            DiscordProvider(httpClient, manager()).send("t", "c"),
            R.string.notification_err_discord_token_empty,
        )
    }

    @Test
    fun discordEmptyUser() = runBlocking {
        assertFailed(
            DiscordProvider(
                httpClient,
                manager(NotificationSettings(discordBotToken = "tok")),
            ).send("t", "c"),
            R.string.notification_err_discord_user_empty,
        )
    }

    @Test
    fun dingTalkEmptyToken() = runBlocking {
        assertFailed(
            DingTalkProvider(httpClient, manager()).send("t", "c"),
            R.string.notification_err_dingtalk_token_empty,
        )
    }

    @Test
    fun kookEmptyToken() = runBlocking {
        assertFailed(
            KookProvider(httpClient, manager()).send("t", "c"),
            R.string.notification_err_kook_token_empty,
        )
    }

    @Test
    fun kookEmptyTarget() = runBlocking {
        assertFailed(
            KookProvider(
                httpClient,
                manager(NotificationSettings(kookBotToken = "tok")),
            ).send("t", "c"),
            R.string.notification_err_kook_target_empty,
        )
    }

    @Test
    fun discordWebhookEmptyUrl() = runBlocking {
        assertFailed(
            DiscordWebhookProvider(httpClient, manager()).send("t", "c"),
            R.string.notification_err_discord_webhook_empty,
        )
    }

    @Test
    fun smtpEmptyServer() = runBlocking {
        assertFailed(
            SmtpProvider(manager()).send("t", "c"),
            R.string.notification_err_smtp_server_empty,
        )
    }

    @Test
    fun smtpInvalidPort() = runBlocking {
        assertFailed(
            SmtpProvider(
                manager(NotificationSettings(smtpServer = "smtp.x", smtpPort = "abc")),
            ).send("t", "c"),
            R.string.notification_err_smtp_port_invalid,
        )
    }

    @Test
    fun smtpRequireAuthMissingCredentials() = runBlocking {
        assertFailed(
            SmtpProvider(
                manager(
                    NotificationSettings(
                        smtpServer = "smtp.x",
                        smtpPort = "465",
                        smtpFrom = "a@x",
                        smtpTo = "b@x",
                        smtpRequireAuthentication = "true",
                    ),
                ),
            ).send("t", "c"),
            R.string.notification_err_smtp_auth_empty,
        )
    }

    /** barkServer 有默认值，全空配置下首个失败是 Send Key */
    @Test
    fun barkEmptyKey() = runBlocking {
        assertFailed(
            BarkProvider(httpClient, manager()).send("t", "c"),
            R.string.notification_err_bark_key_empty,
        )
    }

    @Test
    fun barkEmptyServer() = runBlocking {
        assertFailed(
            BarkProvider(
                httpClient,
                manager(NotificationSettings(barkServer = "", barkSendKey = "k")),
            ).send("t", "c"),
            R.string.notification_err_bark_server_empty,
        )
    }

    @Test
    fun qmsgEmptyServer() = runBlocking {
        assertFailed(
            QmsgProvider(httpClient, manager()).send("t", "c"),
            R.string.notification_err_qmsg_server_empty,
        )
    }

    @Test
    fun qmsgEmptyKey() = runBlocking {
        assertFailed(
            QmsgProvider(
                httpClient,
                manager(NotificationSettings(qmsgServer = "http://x")),
            ).send("t", "c"),
            R.string.notification_err_qmsg_key_empty,
        )
    }

    @Test
    fun gotifyEmptyServer() = runBlocking {
        assertFailed(
            GotifyProvider(httpClient, manager()).send("t", "c"),
            R.string.notification_err_gotify_server_empty,
        )
    }

    @Test
    fun gotifyEmptyToken() = runBlocking {
        assertFailed(
            GotifyProvider(
                httpClient,
                manager(NotificationSettings(gotifyServer = "http://x")),
            ).send("t", "c"),
            R.string.notification_err_gotify_token_empty,
        )
    }

    /** 漏写协议头是最常见的填法错误；报「网络失败」会把用户指到错的地方 */
    @Test
    fun gotifyNonHttpScheme() = runBlocking {
        assertFailed(
            GotifyProvider(
                httpClient,
                manager(NotificationSettings(gotifyServer = "ftp://x", gotifyToken = "t")),
            ).send("t", "c"),
            R.string.notification_err_gotify_scheme,
        )
    }

    @Test
    fun webhookEmptyUrl() = runBlocking {
        assertFailed(
            CustomWebhookProvider(
                httpClient,
                manager(NotificationSettings(customWebhookBody = "{title}")),
            ).send("t", "c"),
            R.string.notification_err_webhook_url_empty,
        )
    }

    @Test
    fun webhookEmptyBody() = runBlocking {
        assertFailed(
            CustomWebhookProvider(
                httpClient,
                manager(NotificationSettings(customWebhookUrl = "http://x")),
            ).send("t", "c"),
            R.string.notification_err_webhook_body_empty,
        )
    }
}
