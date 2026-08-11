package com.aliothmoon.maafw.notification

import com.aliothmoon.maafw.notification.provider.BarkProvider
import com.aliothmoon.maafw.notification.provider.CustomWebhookProvider
import com.aliothmoon.maafw.notification.provider.DingTalkProvider
import com.aliothmoon.maafw.notification.provider.DiscordProvider
import com.aliothmoon.maafw.notification.provider.DiscordWebhookProvider
import com.aliothmoon.maafw.notification.provider.GotifyProvider
import com.aliothmoon.maafw.notification.provider.KookProvider
import com.aliothmoon.maafw.notification.provider.NotificationHttpClient
import com.aliothmoon.maafw.notification.provider.NotificationProvider
import com.aliothmoon.maafw.notification.provider.QmsgProvider
import com.aliothmoon.maafw.notification.provider.ServerChanProvider
import com.aliothmoon.maafw.notification.provider.SmtpProvider
import com.aliothmoon.maafw.notification.provider.TelegramProvider
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 渠道 id 写错不会编译失败，只表现成「开关能打开却永远不发」——由本测试兜住
 *
 * 三处必须一致：[NOTIFICATION_PROVIDER_ORDER]、各 provider 的 id、DI 注册的那份列表。
 * 这里对着前两处；DI 那份漏一个仍要靠人看，但它与本列表在同一个 PR 里改
 */
class NotificationProviderCatalogTest {

    private val http = mockk<NotificationHttpClient>()
    private val settings = mockk<NotificationSettingsManager>()

    private val allProviders: List<NotificationProvider> = listOf(
        ServerChanProvider(http, settings),
        TelegramProvider(http, settings),
        DiscordProvider(http, settings),
        DingTalkProvider(http, settings),
        KookProvider(http, settings),
        DiscordWebhookProvider(http, settings),
        SmtpProvider(settings),
        BarkProvider(http, settings),
        QmsgProvider(http, settings),
        GotifyProvider(http, settings),
        CustomWebhookProvider(http, settings),
    )

    @Test
    fun catalogMatchesProviderIds() {
        assertEquals(NOTIFICATION_PROVIDER_ORDER, allProviders.map { it.id })
    }

    @Test
    fun idsAreUnique() {
        assertEquals(NOTIFICATION_PROVIDER_ORDER.size, NOTIFICATION_PROVIDER_ORDER.toSet().size)
    }

    @Test
    fun enabledProviderIdsDropsBlanks() {
        val settings = NotificationSettings(enabledProviders = ",Bark,, ,Gotify,")
        assertEquals(listOf("Bark", "Gotify"), settings.enabledProviderIds())
    }
}
