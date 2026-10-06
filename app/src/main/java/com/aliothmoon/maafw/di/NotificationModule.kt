package com.aliothmoon.maafw.di

import com.aliothmoon.maafw.i18n.LocalizedTextRenderer
import com.aliothmoon.maafw.notification.ExternalNotificationService
import com.aliothmoon.maafw.notification.NotificationCenter
import com.aliothmoon.maafw.notification.NotificationSettingsManager
import com.aliothmoon.maafw.notification.RunEventNotifier
import com.aliothmoon.maafw.notification.StartAppFailureNotifier
import com.aliothmoon.maafw.notification.live.FocusSequenceStore
import com.aliothmoon.maafw.notification.live.LiveCapabilityProbe
import com.aliothmoon.maafw.notification.live.RunNotificationFactory
import com.aliothmoon.maafw.notification.live.RunNotificationTester
import com.aliothmoon.maafw.notification.live.XmsfNetworkGate
import com.aliothmoon.maafw.notification.provider.BarkProvider
import com.aliothmoon.maafw.notification.provider.CustomWebhookProvider
import com.aliothmoon.maafw.notification.provider.DingTalkProvider
import com.aliothmoon.maafw.notification.provider.DiscordProvider
import com.aliothmoon.maafw.notification.provider.DiscordWebhookProvider
import com.aliothmoon.maafw.notification.provider.GotifyProvider
import com.aliothmoon.maafw.notification.provider.KookProvider
import com.aliothmoon.maafw.notification.provider.QmsgProvider
import com.aliothmoon.maafw.notification.provider.ServerChanProvider
import com.aliothmoon.maafw.notification.provider.SmtpProvider
import com.aliothmoon.maafw.notification.provider.TelegramProvider
import com.aliothmoon.maafw.util.HttpClientHelper
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.module

val notificationModule = module {
    single { NotificationSettingsManager(androidContext()) }
    single { RunEventNotifier(androidContext(), get()) }

    // 运行通知的三种展示方式：前台服务按它们拼进度通知，通知设置页读能力、发测试
    single { LiveCapabilityProbe(androidContext(), get()) }
    single { FocusSequenceStore(androidContext()) }
    single { RunNotificationFactory(androidContext(), get()) }
    single { XmsfNetworkGate(androidContext(), get(), get(named<AppCoroutineScope>())) }
    single { RunNotificationTester(androidContext(), get(), get(), get(), get(named<AppCoroutineScope>())) }

    single {
        StartAppFailureNotifier(
            runnerPort = get(),
            notifyStartAppFailed = get<RunEventNotifier>()::notifyStartAppFailed,
            scope = get(named<AppCoroutineScope>()),
        )
    }

    single {
        val http = get<HttpClientHelper>()
        val settings = get<NotificationSettingsManager>()
        ExternalNotificationService(
            settingsManager = settings,
            providerList = listOf(
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
            ),
            scope = get(named<AppCoroutineScope>()),
        )
    }

    single {
        NotificationCenter(
            eventNotifier = get(),
            external = get(),
            settings = get(),
            recorder = get(),
            renderText = get<LocalizedTextRenderer>()::render,
            servicePort = get(),
        )
    }
}
