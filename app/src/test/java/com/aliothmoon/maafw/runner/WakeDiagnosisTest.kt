package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.domain.UnlockCredential
import com.aliothmoon.maafw.i18n.uiTextOf
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class WakeDiagnosisTest {

    private val yes = uiTextOf(R.string.run_log_wake_yes)
    private val no = uiTextOf(R.string.run_log_wake_no)
    private val unknown = uiTextOf(R.string.run_log_wake_config_unknown)

    /** PIN 只报填没填，不带位数 */
    @Test
    fun `pin setup reports only whether it is filled in`() {
        val text = wakeConfigText(
            unlockType = UnlockCredential.TYPE_PIN,
            credential = UnlockCredential.Pin("1234"),
            gestureRecordedAtMs = null,
            passwordRequired = true,
            before = DeviceAtStart(screenOn = false, keyguardLocked = true),
        )

        assertEquals(
            uiTextOf(
                R.string.run_log_wake_config,
                uiTextOf(R.string.run_log_wake_config_pin_set, uiTextOf(R.string.settings_wake_unlock_type_pin)),
                yes,
                uiTextOf(R.string.run_log_wake_screen_off),
                uiTextOf(R.string.run_log_wake_keyguard_locked),
            ),
            text,
        )
    }

    @Test
    fun `gesture setup carries the recording time`() {
        val text = wakeConfigText(
            unlockType = UnlockCredential.TYPE_GESTURE,
            credential = UnlockCredential.Gesture("{}"),
            gestureRecordedAtMs = 1_700_000_000_000L,
            passwordRequired = true,
            before = null,
            zone = ZoneOffset.UTC,
        )

        assertEquals(
            uiTextOf(
                R.string.run_log_wake_config,
                uiTextOf(
                    R.string.run_log_wake_config_gesture,
                    uiTextOf(R.string.settings_wake_unlock_type_gesture),
                    "2023-11-14 22:13",
                ),
                yes,
                unknown,
                unknown,
            ),
            text,
        )
    }

    /** 选了手势但没录：已退回无密码，摘要要说清 */
    @Test
    fun `a missing gesture is called out`() {
        val text = wakeConfigText(
            unlockType = UnlockCredential.TYPE_GESTURE,
            credential = UnlockCredential.Swipe,
            gestureRecordedAtMs = null,
            passwordRequired = false,
            before = DeviceAtStart(screenOn = true, keyguardLocked = false),
        )

        assertEquals(
            uiTextOf(
                R.string.run_log_wake_config,
                uiTextOf(R.string.run_log_wake_config_gesture_missing, uiTextOf(R.string.settings_wake_unlock_type_gesture)),
                no,
                uiTextOf(R.string.run_log_wake_screen_on),
                uiTextOf(R.string.run_log_wake_keyguard_unlocked),
            ),
            text,
        )
    }
}
