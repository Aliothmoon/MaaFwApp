package com.aliothmoon.maafw.settings

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.aliothmoon.maafw.domain.UnlockCredential

/**
 * 老版本的「亮屏解锁开关 + PIN」→ 解锁方式
 *
 * 只有开过开关、且填了 PIN 的才沿用 PIN：开关关着时存下的 PIN 是用户明确不让注入的，
 * 去掉开关不能顺手把它启用。旧键迁完即删，免得以后再被误读
 */
internal object WakeUnlockTypeMigration : DataMigration<Preferences> {

    private val legacyEnabled = stringPreferencesKey("wake_unlock_enabled")

    override suspend fun shouldMigrate(currentData: Preferences): Boolean = legacyEnabled in currentData

    override suspend fun migrate(currentData: Preferences): Preferences =
        currentData.toMutablePreferences().apply {
            if (this[AppSettingsSchema.wakeUnlockType].isNullOrEmpty()) {
                val pinEnabled = currentData[legacyEnabled].toBoolean() &&
                    !currentData[AppSettingsSchema.wakeCredential].isNullOrBlank()
                this[AppSettingsSchema.wakeUnlockType] =
                    if (pinEnabled) UnlockCredential.TYPE_PIN else UnlockCredential.TYPE_SWIPE
            }
            remove(legacyEnabled)
        }.toPreferences()

    override suspend fun cleanUp() = Unit
}
