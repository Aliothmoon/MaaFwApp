package com.aliothmoon.maafw.ui.logs

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日志页的时间与体积格式化
 *
 * 固定 `yyyy-MM-dd HH:mm:ss` 而不跟 locale 走：这些页面是排障面，
 * 用户要拿这个时间去对 logcat 与 maa.log，两边格式一致才对得上
 */
internal fun logTimestamp(atMillis: Long): String = TIMESTAMP.format(Date(atMillis))

internal fun logTimeOfDay(atMillis: Long): String = TIME_OF_DAY.format(Date(atMillis))

internal fun formatFileSize(bytes: Long): String = when {
    bytes < KB -> "$bytes B"
    bytes < MB -> "${bytes / KB} KB"
    else -> "${bytes / MB} MB"
}

private const val KB = 1024L
private const val MB = KB * 1024

private val TIMESTAMP = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
private val TIME_OF_DAY = SimpleDateFormat("HH:mm:ss", Locale.US)
