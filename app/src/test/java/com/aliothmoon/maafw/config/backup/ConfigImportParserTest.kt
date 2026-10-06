package com.aliothmoon.maafw.config.backup

import com.aliothmoon.maafw.config.backup.BackupFixtures.definition
import com.aliothmoon.maafw.config.backup.BackupFixtures.mxuEncrypt
import com.aliothmoon.maafw.config.backup.BackupFixtures.mxuShareCode
import com.aliothmoon.maafw.domain.ConfiguredTask
import com.aliothmoon.maafw.domain.OptionValue
import com.aliothmoon.maafw.domain.RunConfiguration
import com.aliothmoon.maafw.domain.RunConfigurationId
import com.aliothmoon.maafw.domain.UserConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalTime

/** 一个入口认三种格式；MXU 的值按它自己加载时的规则清洗 */
class ConfigImportParserTest {

    private val current = UserConfiguration(initialized = true, activeResourceName = "官服")

    private fun parse(text: String, fileName: String? = null, config: UserConfiguration = current) =
        ConfigImportParser.parse(text, fileName, definition, config)

    private fun mxu(text: String, fileName: String? = "mxu-MaaEnd.json"): MxuImport {
        val parsed = parse(text, fileName)
        check(parsed is ConfigImportParse.Parsed) { "unexpected $parsed" }
        return (parsed.import as ConfigImport.FromMxu).result
    }

    private fun failure(text: String, fileName: String? = null): ConfigImportError =
        (parse(text, fileName) as ConfigImportParse.Failed).error

    /** 2026-05 的旧格式：定时只有 hours、没有 enabledByController、password 还是明文 */
    private val legacyConfig = """
        {
          "version": "1.0",
          "instances": [
            {
              "id": "sn7yl0b",
              "name": "全套日常",
              "controllerName": "Win32-Window",
              "resourceName": "B服",
              "savedDevice": { "windowName": "Endfield" },
              "preAction": { "program": "game.exe" },
              "tasks": [
                {
                  "id": "a", "taskName": "Daily", "customName": "日常一", "enabled": true,
                  "optionValues": {
                    "Mode": { "type": "select", "caseName": "B" },
                    "Sub": { "type": "select", "caseName": "s2" },
                    "Toggle": { "type": "switch", "value": true },
                    "Pick": { "type": "checkbox", "caseNames": ["y", "gone"] },
                    "Account": { "type": "input", "values": { "user": "alice", "pin": "plain", "extra": "x" } },
                    "Server": { "type": "select", "caseName": "r2" },
                    "Removed": { "type": "select", "caseName": "a" }
                  }
                },
                { "id": "b", "taskName": "Win32Only", "enabled": true, "optionValues": {} },
                { "id": "c", "taskName": "GoneTask", "enabled": true, "optionValues": {} },
                { "id": "d", "taskName": "__MXU_WEBHOOK__", "enabled": false, "optionValues": {} },
                { "id": "e", "taskName": "__MXU_PRETASK__launcher", "enabled": true, "optionValues": {} }
              ],
              "schedulePolicies": [
                { "id": "p1", "name": "策略 1", "enabled": true, "weekdays": [], "hours": [] },
                { "id": "p2", "name": "策略 2", "enabled": true, "weekdays": [0, 1, 6], "hours": [20, 8] }
              ]
            }
          ],
          "settings": { "theme": "light", "mirrorChyan": { "cdk": "", "cdkEncrypted": "" } },
          "recentlyClosed": [],
          "presetInitialized": true
        }
    """.trimIndent()

    @Test
    fun `MXU 旧配置：任务、选项、定时按规则转换`() {
        val result = mxu(legacyConfig)

        val configuration = result.configurations.single()
        assertEquals("全套日常", configuration.name)
        assertEquals(listOf("Daily", "Win32Only"), configuration.tasks.map { it.taskName })
        val daily = configuration.tasks[0]
        assertTrue(daily.enabled)
        assertEquals("日常一", daily.customLabel)
        assertEquals(OptionValue.SingleCase("B"), daily.optionValues["Mode"])
        assertEquals(OptionValue.SingleCase("s2"), daily.optionValues["Sub"])
        assertEquals(OptionValue.SingleCase("Yes"), daily.optionValues["Toggle"])
        assertEquals(OptionValue.MultipleCases(listOf("y")), daily.optionValues["Pick"])
        assertEquals(
            OptionValue.Inputs(mapOf("user" to "alice", "pin" to "plain"), secretFields = setOf("pin")),
            daily.optionValues["Account"],
        )
        // 资源选项挪进标签页资源的桶，不留在任务里
        assertFalse("Server" in daily.optionValues)
        assertEquals(mapOf("B服" to mapOf("Server" to OptionValue.SingleCase("r2"))), result.resourceOptionValues)
        // Android 上跑不了的任务照收但不勾
        assertFalse(configuration.tasks[1].enabled)

        val strategy = result.schedules.single()
        assertFalse(strategy.enabled)
        assertEquals(configuration.id.value, strategy.runConfigurationId)
        assertEquals("全套日常 - 策略 2", strategy.name)
        assertEquals(setOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.SATURDAY), strategy.daysOfWeek)
        assertEquals(listOf(LocalTime.of(8, 0), LocalTime.of(20, 0)), strategy.executionTimes)

        assertEquals(
            MxuSkipped(unknownTasks = listOf("GoneTask"), mxuTasks = 1, preActions = 2, options = 1, schedules = 1),
            result.skipped,
        )
    }

    @Test
    fun `勾选状态优先取当前 controller 记下的那份`() {
        val text = """
            {"instances":[{"name":"t","tasks":[
              {"taskName":"Daily","enabled":true,"enabledByController":{"ADB":false,"Win32-Window":true}}
            ]}],"settings":{}}
        """.trimIndent()

        assertFalse(mxu(text).configurations.single().tasks.single().enabled)
    }

    @Test
    fun `新格式的 password 用项目名派生的 key 解开`() {
        val encrypted = mxuEncrypt("s3cr3t-密码", "MXU-INPUT-MaaEnd-Account-pin")
        val text = """
            {"instances":[{"name":"t","tasks":[{"taskName":"Daily","enabled":true,"optionValues":{
              "Account":{"type":"input","values":{"user":"bob","pin":""},"encryptedValues":{"pin":"$encrypted"}}
            }}]}],"settings":{}}
        """.trimIndent()

        val inputs = mxu(text).configurations.single().tasks.single().optionValues["Account"] as OptionValue.Inputs
        assertEquals(mapOf("user" to "bob", "pin" to "s3cr3t-密码"), inputs.values)
    }

    @Test
    fun `全局选项照收，对不上的值丢掉`() {
        val text = """
            {"instances":[{"name":"t","tasks":[{"taskName":"Daily","enabled":true}]}],"settings":{},
             "globalOptionValues":{"Global":{"type":"select","caseName":"g2"},"Nope":{"type":"select","caseName":"x"}}}
        """.trimIndent()

        val result = mxu(text)
        assertEquals(mapOf("Global" to OptionValue.SingleCase("g2")), result.globalOptionValues)
        assertEquals(1, result.skipped.options)
    }

    @Test
    fun `文件名里的项目名对不上就拒`() {
        assertEquals(
            ConfigImportError.ProjectMismatch("MaaMeow", "MaaEnd"),
            failure(legacyConfig, fileName = "mxu-MaaMeow.json"),
        )
        assertEquals(
            ConfigImportError.ProjectMismatch("MaaEndless", "MaaEnd"),
            failure(legacyConfig, fileName = "mxu-MaaEndless.json"),
        )
    }

    @Test
    fun `MXU 自己的滚动备份、下载重名的文件也认作同一个项目`() {
        listOf("mxu-MaaEnd-20261004-201200.json", "mxu-MaaEnd (1).json", "MXU-MaaEnd.JSON").forEach { name ->
            assertEquals(name, 1, mxu(legacyConfig, fileName = name).configurations.size)
        }
    }

    @Test
    fun `项目名带空格的分享码按整行认`() {
        val spaced = definition.copy(name = "Maa End")
        val wire = """{"t":[{"i":"x","tn":"Daily","e":true,"ov":{}}]}"""

        val parsed = ConfigImportParser.parse(mxuShareCode("Maa End", "t", wire), null, spaced, current)

        assertTrue(parsed is ConfigImportParse.Parsed)
    }

    @Test
    fun `改过名的文件不比项目名，但一个已知任务都没有就算空`() {
        val text = """{"instances":[{"name":"t","tasks":[{"taskName":"Other","enabled":true}]}],"settings":{}}"""

        assertEquals(ConfigImportError.Empty, failure(text, fileName = "backup.json"))
    }

    @Test
    fun `分享码：短键还原、标签名解码`() {
        val wire = """{"cn":"ADB","rn":"官服","t":[{"i":"x","tn":"Daily","cn":"别名","e":true,
            "ov":{"Toggle":{"t":"sw","v":false},"Pick":{"t":"cb","c":["x","y"]},"Mode":{"t":"s","c":"A"},
                  "Account":{"t":"in","v":{"user":"u","pin":""},"ev":{"pin":"${mxuEncrypt("p", "MXU-INPUT-MaaEnd-Account-pin")}"}}}}]}"""

        val parsed = parse(mxuShareCode("MaaEnd", "日常 A+B", wire)) as ConfigImportParse.Parsed
        val import = parsed.import as ConfigImport.FromMxu

        assertEquals(ConfigImport.MxuSource.ShareCode, import.source)
        val configuration = import.result.configurations.single()
        assertEquals("日常 A+B", configuration.name)
        val task = configuration.tasks.single()
        assertEquals("别名", task.customLabel)
        assertEquals(OptionValue.SingleCase("No"), task.optionValues["Toggle"])
        assertEquals(OptionValue.MultipleCases(listOf("x", "y")), task.optionValues["Pick"])
        assertEquals(OptionValue.SingleCase("A"), task.optionValues["Mode"])
        assertEquals("p", (task.optionValues["Account"] as OptionValue.Inputs).values["pin"])
    }

    @Test
    fun `分享码的项目名与版本不对就拒`() {
        val wire = """{"t":[{"i":"x","tn":"Daily","e":true,"ov":{}}]}"""

        assertEquals(ConfigImportError.ProjectMismatch("MaaMeow", "MaaEnd"), failure(mxuShareCode("MaaMeow", "t", wire)))
        assertEquals(ConfigImportError.UnsupportedShareCode, failure(mxuShareCode("MaaEnd", "t", wire, version = "v2")))
        assertEquals(ConfigImportError.Unrecognized, failure("MaaEnd://tab-sharing/v1/t/not-deflate"))
    }

    @Test
    fun `FwApp 备份按 format 认出，版本更高或项目不同就拒`() {
        val backup = ConfigBackupCodec.export(
            UserConfiguration(initialized = true), definition, emptyList(), emptyMap(), BackupAppInfo("a", "1"), 0L,
        )

        assertTrue((parse(ConfigBackupCodec.encode(backup)) as ConfigImportParse.Parsed).import is ConfigImport.Restore)
        assertEquals(ConfigImportError.NewerFormat, failure(ConfigBackupCodec.encode(backup.copy(formatVersion = 99))))
        assertEquals(
            ConfigImportError.ProjectMismatch("Other", "MaaEnd"),
            failure(ConfigBackupCodec.encode(backup.copy(project = BackupProjectInfo("Other")))),
        )
        assertEquals(ConfigImportError.Unrecognized, failure("""{"hello":1}"""))
        assertEquals(ConfigImportError.Unrecognized, failure("not json"))
    }

    @Test
    fun `MXU 导入是追加：全局按键覆盖，本机已填的密码不被空值清掉`() {
        val existingId = RunConfigurationId("mine")
        val existing = current.copy(
            configurations = listOf(RunConfiguration(existingId, "我的", listOf(ConfiguredTask("Daily")))),
            activeConfigurationId = existingId,
            globalOptionValues = mapOf(
                "Global" to OptionValue.SingleCase("g1"),
                "Account" to OptionValue.Inputs(mapOf("pin" to "keep"), secretFields = setOf("pin")),
            ),
        )
        val result = mxu(legacyConfig).copy(
            globalOptionValues = mapOf(
                "Global" to OptionValue.SingleCase("g2"),
                "Account" to OptionValue.Inputs(mapOf("user" to "x", "pin" to "from-mxu"), secretFields = setOf("pin")),
            ),
        )

        val applied = result.applyTo(existing, definition)

        assertEquals(listOf("我的", "全套日常"), applied.configurations.map { it.name })
        assertEquals(existingId, applied.activeConfigurationId)
        assertEquals(OptionValue.SingleCase("g2"), applied.globalOptionValues["Global"])
        assertEquals(mapOf("user" to "x", "pin" to "keep"), (applied.globalOptionValues["Account"] as OptionValue.Inputs).values)
    }
}
