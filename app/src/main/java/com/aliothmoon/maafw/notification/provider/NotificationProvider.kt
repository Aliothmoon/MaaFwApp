package com.aliothmoon.maafw.notification.provider

import com.aliothmoon.maafw.i18n.UiText
import kotlinx.serialization.json.Json

/**
 * 一个外部推送渠道
 *
 * [id] 同时是持久化里的渠道标识与 UI 的分组键，**改名等于把用户已启用的渠道弄丢**
 */
interface NotificationProvider {
    val id: String
    suspend fun send(title: String, content: String): NotificationSendResult
}

/**
 * [Failed] 与 [Transient] 分开：前者是配置或凭据的问题，用户不改就永远发不出去，
 * 每轮都该报；后者是网络抖动，整晚的定时任务里报一串没有意义，只在用户主动测试时露面
 */
sealed interface NotificationSendResult {
    data object Success : NotificationSendResult
    data class Failed(val message: UiText) : NotificationSendResult
    data class Transient(val message: UiText) : NotificationSendResult
}

/** 各家的请求/响应体共用一份宽容配置：字段随服务端版本增删，不该因为多一个键就解不出来 */
internal val providerJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    isLenient = true
}
