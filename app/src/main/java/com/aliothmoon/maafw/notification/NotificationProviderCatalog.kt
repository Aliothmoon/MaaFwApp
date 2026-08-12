package com.aliothmoon.maafw.notification

/**
 * 渠道 id 的权威清单，顺序即设置页的展示顺序
 */
val NOTIFICATION_PROVIDER_ORDER: List<String> = listOf(
    "ServerChan",
    "Telegram",
    "Discord",
    "DingTalk",
    "KOOK",
    "Discord Webhook",
    "SMTP",
    "Bark",
    "Qmsg",
    "Gotify",
    "CustomWebhook",
)
