package com.aliothmoon.maafw.notification

/**
 * 渠道 id 的权威清单，顺序即设置页的展示顺序
 *
 * 三处必须对得上：各 provider 的 `NotificationProvider.id`、DI 里注册的那份列表、
 * 设置页的配置表单。id 只是字符串，写错不会编译失败，只会表现成「开关打不开」——
 * 由 `NotificationProviderCatalogTest` 盯住
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
