package com.aliothmoon.maafw.notification

/** 外部推送附带的图片，目前只有任务结束时的截图 */
class NotificationImage(val bytes: ByteArray) {
    val mimeType: String get() = "image/jpeg"
    val fileName: String get() = "screenshot.jpg"
}
