package com.aliothmoon.maafw.config.backup

import com.aliothmoon.maafw.config.backup.BackupFixtures.definition
import com.aliothmoon.maafw.domain.ConfiguredTask
import com.aliothmoon.maafw.domain.OptionValue
import com.aliothmoon.maafw.domain.RunConfiguration
import com.aliothmoon.maafw.domain.RunConfigurationId
import com.aliothmoon.maafw.domain.ThemeMode
import com.aliothmoon.maafw.domain.UserConfiguration
import com.aliothmoon.maafw.schedule.ScheduleStrategy
import com.aliothmoon.maafw.schedule.TriggerResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalTime

/** FwApp 自己的备份：导出不带密码与运行时字段，恢复整体覆盖但不清掉本机已填的密码 */
class ConfigBackupCodecTest {

    private val configId = RunConfigurationId("c1")

    private fun account(user: String, pin: String?) = OptionValue.Inputs(
        values = buildMap {
            put("user", user)
            pin?.let { put("pin", it) }
        },
        secretFields = setOf("pin"),
    )

    private val config = UserConfiguration(
        initialized = true,
        themeMode = ThemeMode.Dark,
        activeResourceName = "B服",
        globalOptionValues = mapOf("Global" to OptionValue.SingleCase("g2")),
        resourceOptionValues = mapOf("B服" to mapOf("Server" to OptionValue.SingleCase("r2"))),
        configurations = listOf(
            RunConfiguration(
                configId, "日常",
                listOf(ConfiguredTask("Daily", optionValues = mapOf("Account" to account("alice", "1234")), instanceId = "t1")),
            ),
        ),
        activeConfigurationId = configId,
        welcomeFingerprint = "fp",
    )

    private val schedule = ScheduleStrategy(
        id = "s1",
        name = "早上",
        runConfigurationId = configId.value,
        daysOfWeek = setOf(DayOfWeek.MONDAY),
        executionTimes = listOf(LocalTime.of(8, 0)),
        lastTriggeredAt = 1L,
        lastResult = TriggerResult.STARTED,
        lastResultMessage = "ok",
    )

    private fun export(source: UserConfiguration = config, schedules: List<ScheduleStrategy> = listOf(schedule)) =
        ConfigBackupCodec.export(
            config = source,
            definition = definition,
            schedules = schedules,
            appSettings = mapOf("run_mode" to "FOREGROUND"),
            app = BackupAppInfo("com.example", "1.0"),
            exportedAt = 42L,
        )

    @Test
    fun `导出不带密码与定时的运行时字段，编码后能原样读回`() {
        val backup = export()
        val text = ConfigBackupCodec.encode(backup)

        assertFalse(text.contains("1234"))
        val decoded = ConfigBackupCodec.decode(text)
        assertEquals(backup, decoded)
        val inputs = decoded.configuration.configurations.single().tasks.single().optionValues["Account"] as OptionValue.Inputs
        assertEquals(mapOf("user" to "alice"), inputs.values)
        assertEquals(setOf("pin"), inputs.secretFields)
        val strategy = decoded.schedules.single()
        assertNull(strategy.lastTriggeredAt)
        assertNull(strategy.lastResult)
        assertEquals(BackupProjectInfo("MaaEnd", "v1.0.0"), decoded.project)
    }

    @Test
    fun `还没补过标记的 password 值也不会以明文导出`() {
        val unmarked = config.copy(
            globalOptionValues = mapOf("Account" to OptionValue.Inputs(mapOf("user" to "bob", "pin" to "9999"))),
        )

        assertFalse(ConfigBackupCodec.encode(export(unmarked)).contains("9999"))
    }

    @Test
    fun `恢复整体覆盖，本机已填的密码留着，公告指纹这类本机状态不动`() {
        val backup = export()
        val current = UserConfiguration(
            initialized = true,
            welcomeFingerprint = "local",
            configurations = listOf(
                RunConfiguration(
                    RunConfigurationId("other"), "别的",
                    listOf(ConfiguredTask("Daily", optionValues = mapOf("Account" to account("old", "5678")), instanceId = "x")),
                ),
            ),
        )

        val restored = ConfigBackupCodec.restore(backup, current, definition)

        assertEquals(listOf(configId), restored.configurations.map { it.id })
        assertEquals(configId, restored.activeConfigurationId)
        assertEquals(ThemeMode.Dark, restored.themeMode)
        assertEquals("local", restored.welcomeFingerprint)
        val inputs = restored.configurations.single().tasks.single().optionValues["Account"] as OptionValue.Inputs
        // user 取备份里的，pin 备份里没有，沿用本机同名任务填好的
        assertEquals(mapOf("user" to "alice", "pin" to "5678"), inputs.values)
    }

    @Test
    fun `同一个任务实例导回来，按实例 id 对回自己的密码`() {
        val restored = ConfigBackupCodec.restore(export(), config, definition)

        val inputs = restored.configurations.single().tasks.single().optionValues["Account"] as OptionValue.Inputs
        assertEquals("1234", inputs.values["pin"])
    }

    @Test
    fun `重复的配置 id 与任务实例 id 换新，激活配置对不上回落第一份`() {
        val duplicated = RunConfiguration(configId, "副本", listOf(ConfiguredTask("Daily", instanceId = "t1")))
        val backup = export(config.copy(configurations = config.configurations + duplicated))
            .let { it.copy(configuration = it.configuration.copy(activeConfigurationId = RunConfigurationId("gone"))) }

        val restored = ConfigBackupCodec.restore(backup, UserConfiguration(), definition)

        val ids = restored.configurations.map { it.id }
        assertEquals(2, ids.toSet().size)
        assertEquals(2, restored.configurations.flatMap { it.tasks }.map { it.instanceId }.toSet().size)
        assertEquals(ids.first(), restored.activeConfigurationId)
        assertTrue(restored.initialized)
    }

    @Test
    fun `定时绑的配置不在备份里就解绑并关掉`() {
        val orphan = schedule.copy(id = "s2", runConfigurationId = "gone")
        val backup = export(schedules = listOf(schedule, orphan, orphan))

        val restored = ConfigBackupCodec.restoreSchedules(backup, setOf(configId))

        assertTrue(restored[0].enabled)
        assertEquals(configId.value, restored[0].runConfigurationId)
        assertFalse(restored[1].enabled)
        assertEquals("", restored[1].runConfigurationId)
        assertNotEquals(restored[1].id, restored[2].id)
    }

    @Test
    fun `本机填过的密码不被导入的值覆盖也不被清空，本机空着才用导入的`() {
        val device = account("old", "old-pin")

        assertEquals("old-pin", (account("u", "new-pin").withSecretsKeptFrom(device) as OptionValue.Inputs).values["pin"])
        assertEquals("old-pin", (account("u", "").withSecretsKeptFrom(device) as OptionValue.Inputs).values["pin"])
        assertEquals("old-pin", (account("u", null).withSecretsKeptFrom(device) as OptionValue.Inputs).values["pin"])
        assertEquals("new-pin", (account("u", "new-pin").withSecretsKeptFrom(account("old", "")) as OptionValue.Inputs).values["pin"])
    }

    @Test
    fun `备份里没有的选项，本机填过的密码照样留着`() {
        val current = UserConfiguration(
            initialized = true,
            globalOptionValues = mapOf("Account" to account("local", "g-pin")),
            resourceOptionValues = mapOf("官服" to mapOf("Account" to account("local", "r-pin"))),
        )

        val restored = ConfigBackupCodec.restore(export(), current, definition)

        // 只带回密码，其余字段按备份（这里没有）回默认
        assertEquals(mapOf("pin" to "g-pin"), (restored.globalOptionValues["Account"] as OptionValue.Inputs).values)
        assertEquals(OptionValue.SingleCase("g2"), restored.globalOptionValues["Global"])
        assertEquals(mapOf("pin" to "r-pin"), (restored.resourceOptionValues["官服"]?.get("Account") as OptionValue.Inputs).values)
        assertEquals(OptionValue.SingleCase("r2"), restored.resourceOptionValues["B服"]?.get("Server"))
    }
}
