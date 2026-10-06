package com.aliothmoon.maafw.settings

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.aliothmoon.maafw.domain.UnlockCredential
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WakeUnlockTypeMigrationTest {

    private val legacyEnabled = stringPreferencesKey("wake_unlock_enabled")

    private suspend fun migrate(vararg pairs: Preferences.Pair<*>): Preferences {
        val before = mutablePreferencesOf(*pairs)
        return if (WakeUnlockTypeMigration.shouldMigrate(before)) WakeUnlockTypeMigration.migrate(before) else before
    }

    @Test
    fun `an enabled switch with a pin keeps pin unlock`() = runTest {
        val after = migrate(legacyEnabled to "true", AppSettingsSchema.wakeCredential to "1234")

        assertEquals(UnlockCredential.TYPE_PIN, after[AppSettingsSchema.wakeUnlockType])
        assertFalse(legacyEnabled in after)
    }

    /** 开关关着时存下的 PIN 是用户不让注入的，去掉开关也不能顺手启用 */
    @Test
    fun `a pin saved behind a disabled switch is not used`() = runTest {
        val after = migrate(legacyEnabled to "false", AppSettingsSchema.wakeCredential to "1234")

        assertEquals(UnlockCredential.TYPE_SWIPE, after[AppSettingsSchema.wakeUnlockType])
        assertEquals("1234", after[AppSettingsSchema.wakeCredential])
    }

    @Test
    fun `an enabled switch without a pin falls back to no password`() = runTest {
        val after = migrate(legacyEnabled to "true")

        assertEquals(UnlockCredential.TYPE_SWIPE, after[AppSettingsSchema.wakeUnlockType])
    }

    @Test
    fun `an explicit choice is kept and the legacy key still goes away`() = runTest {
        val after = migrate(
            legacyEnabled to "false",
            AppSettingsSchema.wakeUnlockType to UnlockCredential.TYPE_GESTURE,
        )

        assertEquals(UnlockCredential.TYPE_GESTURE, after[AppSettingsSchema.wakeUnlockType])
        assertFalse(legacyEnabled in after)
    }

    @Test
    fun `fresh installs are left alone`() = runTest {
        assertFalse(WakeUnlockTypeMigration.shouldMigrate(mutablePreferencesOf()))
    }
}
