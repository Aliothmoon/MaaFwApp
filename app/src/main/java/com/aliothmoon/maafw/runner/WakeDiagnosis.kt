package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.domain.UnlockCredential
import com.aliothmoon.maafw.i18n.UiText
import com.aliothmoon.maafw.i18n.uiTextOf
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val RECORDED_AT_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/**
 * 解锁失败时写进运行日志的配置摘要
 * 运行日志可能随推送外发：只报配没配、何时录的；不报步数，点按录的手势步数就是 PIN 位数
 */
internal fun wakeConfigText(
    unlockType: String,
    credential: UnlockCredential,
    gestureRecordedAtMs: Long?,
    passwordRequired: Boolean,
    before: DeviceAtStart?,
    zone: ZoneId = ZoneId.systemDefault(),
): UiText {
    val pin = uiTextOf(R.string.settings_wake_unlock_type_pin)
    val gesture = uiTextOf(R.string.settings_wake_unlock_type_gesture)
    val unknown = uiTextOf(R.string.run_log_wake_config_unknown)
    val setup = when (credential) {
        is UnlockCredential.Pin -> uiTextOf(
            if (credential.isReady) R.string.run_log_wake_config_pin_set else R.string.run_log_wake_config_pin_empty,
            pin,
        )

        is UnlockCredential.Gesture -> uiTextOf(
            R.string.run_log_wake_config_gesture,
            gesture,
            gestureRecordedAtMs?.let { RECORDED_AT_FORMAT.format(Instant.ofEpochMilli(it).atZone(zone)) } ?: unknown,
        )

        // 选了手势但没录成时已退回无密码
        UnlockCredential.Swipe -> if (unlockType == UnlockCredential.TYPE_GESTURE) {
            uiTextOf(R.string.run_log_wake_config_gesture_missing, gesture)
        } else {
            uiTextOf(R.string.settings_wake_unlock_type_swipe)
        }
    }
    return uiTextOf(
        R.string.run_log_wake_config,
        setup,
        uiTextOf(if (passwordRequired) R.string.run_log_wake_yes else R.string.run_log_wake_no),
        before?.let {
            uiTextOf(if (it.screenOn) R.string.run_log_wake_screen_on else R.string.run_log_wake_screen_off)
        } ?: unknown,
        before?.let {
            uiTextOf(if (it.keyguardLocked) R.string.run_log_wake_keyguard_locked else R.string.run_log_wake_keyguard_unlocked)
        } ?: unknown,
    )
}
