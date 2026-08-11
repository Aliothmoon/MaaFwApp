package com.aliothmoon.maafw.notification

import com.aliothmoon.preferences.PrefKey
import com.aliothmoon.preferences.PrefSchema

/**
 * 外部推送的全部配置，单独一个 Preferences DataStore
 *
 * 不并进 `AppSettings`：这里是几十个字段的凭据表，随渠道增删而变；混进去会让每加一个
 * 渠道就动一次 app 全局设置的 schema。也不进 `UserConfiguration`——那是运行配置的聚合根，
 * schemaVersion 不符即整体重置，用户填的 token 不该跟着丢
 *
 * 字段一律 String（与 [com.aliothmoon.maafw.settings.AppSettings] 同一取舍）：
 * `@PrefSchema` 按字段类型选 preferencesKey，布尔以 "true"/"false" 文本落盘，
 * 改默认值不会让老数据变成非法值
 *
 * **凭据是明文**：与 `AppSettings.wakeCredential` 同一处境，加密只能把明文在内存里
 * 晚出现一会儿，挡不住能读到 app 私有目录的攻击者；导出配置时要清空这一整份
 */
@PrefSchema(name = "NotificationSettings")
data class NotificationSettings(
    @PrefKey(default = "true") val sendOnComplete: String = "true",
    @PrefKey(default = "true") val sendOnError: String = "true",
    @PrefKey(default = "false") val sendOnServiceDied: String = "false",
    @PrefKey(default = "false") val includeLogDetails: String = "false",

    /** 已启用渠道的 id，逗号分隔；顺序即发送顺序 */
    @PrefKey(default = "") val enabledProviders: String = "",

    @PrefKey(default = "") val serverChanSendKey: String = "",
    @PrefKey(default = "") val discordBotToken: String = "",
    @PrefKey(default = "") val discordUserId: String = "",
    @PrefKey(default = "") val discordWebhookUrl: String = "",
    @PrefKey(default = "") val smtpServer: String = "",
    @PrefKey(default = "") val smtpPort: String = "",
    @PrefKey(default = "") val smtpUser: String = "",
    @PrefKey(default = "") val smtpPassword: String = "",
    @PrefKey(default = "false") val smtpUseSsl: String = "false",
    @PrefKey(default = "false") val smtpRequireAuthentication: String = "false",
    @PrefKey(default = "") val smtpFrom: String = "",
    @PrefKey(default = "") val smtpTo: String = "",
    @PrefKey(default = "https://api.day.app") val barkServer: String = "https://api.day.app",
    @PrefKey(default = "") val barkSendKey: String = "",
    @PrefKey(default = "") val telegramBotToken: String = "",
    @PrefKey(default = "") val telegramChatId: String = "",
    @PrefKey(default = "") val telegramTopicId: String = "",
    @PrefKey(default = "") val dingTalkAccessToken: String = "",
    @PrefKey(default = "") val dingTalkSecret: String = "",
    @PrefKey(default = "") val kookBotToken: String = "",
    @PrefKey(default = "") val kookTargetId: String = "",
    @PrefKey(default = "false") val kookDirectMessage: String = "false",
    @PrefKey(default = "") val qmsgServer: String = "",
    @PrefKey(default = "") val qmsgKey: String = "",
    @PrefKey(default = "") val qmsgUser: String = "",
    @PrefKey(default = "") val qmsgBot: String = "",
    @PrefKey(default = "") val gotifyServer: String = "",
    @PrefKey(default = "") val gotifyToken: String = "",
    @PrefKey(default = "") val customWebhookUrl: String = "",
    @PrefKey(default = "") val customWebhookHeaders: String = "",
    @PrefKey(default = "") val customWebhookBody: String = "",
)
