package com.aliothmoon.maafw.telemetry

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Sentry `user.id`：设备上稳定、出不了设备原值的匿名标识，Sentry 靠它数唯一用户
 *
 * 原料是 `ANDROID_ID`（同一签名、同一设备用户下稳定，重装不变），加盐后 SHA-256。
 * 取不到时退到本应用私有目录里的随机 UUID，卸载即失效
 */
object TelemetryUserId {

    private const val SALT = "maafwapp-telemetry-v1:"
    private const val FALLBACK_FILE = "telemetry_install_id"

    /** Android 2.2 时代部分机型共用的坏值，拿它当标识会把一批设备算成同一个人 */
    private const val BROKEN_ANDROID_ID = "9774d56d682e549c"

    @Volatile
    private var cached: String? = null

    fun get(context: Context): String =
        cached ?: synchronized(this) {
            cached ?: hash(usableAndroidId(readAndroidId(context)) ?: fallbackInstallId(context))
                .also { cached = it }
        }

    internal fun hash(raw: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest((SALT + raw).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    internal fun usableAndroidId(raw: String?): String? =
        raw?.trim()?.takeIf { it.isNotEmpty() && !it.equals(BROKEN_ANDROID_ID, ignoreCase = true) }

    @SuppressLint("HardwareIds")
    private fun readAndroidId(context: Context): String? =
        runCatching { Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) }.getOrNull()

    private fun fallbackInstallId(context: Context): String {
        val file = File(context.noBackupFilesDir, FALLBACK_FILE)
        runCatching { file.readText().trim() }.getOrNull()?.takeIf(String::isNotEmpty)?.let { return it }
        val id = UUID.randomUUID().toString()
        runCatching { file.writeText(id) }
        return id
    }
}
