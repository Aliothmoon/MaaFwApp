package com.aliothmoon.maafw.notification.provider

import com.aliothmoon.maafw.notification.NotificationImage
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber

internal fun NotificationImage.toRequestBody(): RequestBody = bytes.toRequestBody(mimeType.toMediaType())

internal fun discordMultipart(content: String, image: NotificationImage): MultipartBody =
    MultipartBody.Builder()
        .setType(MultipartBody.FORM)
        .addFormDataPart("payload_json", buildJsonObject { put("content", content) }.toString())
        .addFormDataPart("files[0]", image.fileName, image.toRequestBody())
        .build()

/**
 * 文字和图片同一次请求发出的渠道用：附件被拒就退回纯文字
 * 网络异常默认不重发，对端可能其实已经收到
 */
/** 图片单独发一条的渠道用：文字发成了才补图，图发不出去不影响结果 */
internal suspend fun NotificationProvider.sendThenAttach(
    title: String,
    content: String,
    attach: suspend () -> Unit,
): NotificationSendResult {
    val result = send(title, content)
    if (result is NotificationSendResult.Success) {
        runCatching { attach() }.onFailure { Timber.e(it, "%s image send failed", id) }
    }
    return result
}

internal suspend fun NotificationProvider.sendWithTextFallback(
    title: String,
    content: String,
    retryOnTransient: Boolean = false,
    sendWithImage: suspend () -> NotificationSendResult,
): NotificationSendResult {
    val result = sendWithImage()
    val retry = result is NotificationSendResult.Failed ||
        (retryOnTransient && result is NotificationSendResult.Transient)
    if (!retry) return result
    Timber.w("%s image send failed, retry without image", id)
    return send(title, content)
}
