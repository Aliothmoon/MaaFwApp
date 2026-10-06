package com.aliothmoon.maafw.util

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns

/** SAF 文件的显示名，用来回显「已保存到 xxx」；查不到返回 null，由调用方兜底 */
fun ContentResolver.displayName(uri: Uri): String? = runCatching {
    query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
    }
}.getOrNull()
