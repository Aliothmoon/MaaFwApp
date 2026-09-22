package com.aliothmoon.maafw.log

import com.aliothmoon.maafw.domain.ConfiguredTask
import com.aliothmoon.maafw.domain.OptionValue
import com.aliothmoon.maafw.domain.RunConfigurationId
import com.aliothmoon.maafw.domain.RunConfiguration
import com.aliothmoon.maafw.domain.UserConfiguration
import com.aliothmoon.maafw.settings.AppSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportSnapshotsTest {

    @Test
    fun `settings snapshot keeps configuration and redacts credentials`() {
        val settings = AppSettings(
            runMode = "FOREGROUND",
            resolutionPreference = "P1080",
            wakeCredential = "123456",
            mirrorchyanCdk = "secret-cdk",
        )

        val snapshot = Json.parseToJsonElement(ExportSnapshots.settings(settings)).jsonObject

        assertEquals("FOREGROUND", snapshot.getValue("runMode").jsonPrimitive.content)
        assertEquals("P1080", snapshot.getValue("resolutionPreference").jsonPrimitive.content)
        assertEquals("[redacted]", snapshot.getValue("wakeCredential").jsonPrimitive.content)
        assertEquals("[redacted]", snapshot.getValue("mirrorchyanCdk").jsonPrimitive.content)
        assertFalse(snapshot.toString().contains("123456"))
        assertFalse(snapshot.toString().contains("secret-cdk"))
    }

    @Test
    fun `pi config snapshot preserves the persisted aggregate`() {
        val config = UserConfiguration(
            initialized = true,
            activeResourceName = "Main",
            globalOptionValues = mapOf(
                "password" to OptionValue.Inputs(mapOf("value" to "nested-secret")),
            ),
            configurations = listOf(
                RunConfiguration(
                    id = RunConfigurationId("config-1"),
                    name = "Daily",
                    tasks = listOf(
                        ConfiguredTask(
                            taskName = "Fight",
                            optionValues = mapOf(
                                "login" to OptionValue.Inputs(mapOf("api_key" to "pi-secret")),
                                "api-key" to OptionValue.Inputs(mapOf("value" to "hyphen-secret")),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val snapshot = Json.parseToJsonElement(ExportSnapshots.piConfig(config)).jsonObject

        assertEquals(1, snapshot.getValue("schemaVersion").jsonPrimitive.content.toInt())
        val persistedConfig = snapshot.getValue("config").jsonObject
        assertEquals("Main", persistedConfig.getValue("activeResourceName").jsonPrimitive.content)
        assertEquals(
            "[redacted]",
            persistedConfig.getValue("globalOptionValues").jsonObject
                .getValue("password").jsonPrimitive.content,
        )
        assertEquals(
            "config-1",
            persistedConfig.getValue("configurations").jsonArray[0]
                .jsonObject.getValue("id").jsonPrimitive.content,
        )
        assertFalse(snapshot.toString().contains("pi-secret"))
        assertFalse(snapshot.toString().contains("nested-secret"))
        assertFalse(snapshot.toString().contains("hyphen-secret"))
        assertTrue(snapshot.getValue("config").jsonObject
            .getValue("configurations").jsonArray[0]
            .jsonObject.getValue("tasks").jsonArray[0]
            .jsonObject.getValue("optionValues").jsonObject
            .getValue("api-key").jsonPrimitive.content == "[redacted]")
    }
}
