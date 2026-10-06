package com.aliothmoon.maafw.config.backup

import com.aliothmoon.maafw.config.backup.BackupFixtures.definition
import com.aliothmoon.maafw.config.backup.BackupFixtures.mxuEncrypt
import com.aliothmoon.maafw.config.backup.BackupFixtures.mxuEncryptBytes
import com.aliothmoon.maafw.domain.OptionCaseDefinition
import com.aliothmoon.maafw.domain.OptionDefinition
import com.aliothmoon.maafw.domain.OptionValue
import com.aliothmoon.maafw.domain.ThemeMode
import com.aliothmoon.maafw.domain.UserConfiguration
import com.aliothmoon.maafw.schedule.ScheduleType
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** 坏数据、对不上的数据：要么整份拒掉，要么只丢那一项，不能把错的东西导进来 */
class ConfigImportRejectionTest {

    private val current = UserConfiguration(initialized = true)

    private fun parse(text: String, fileName: String? = null) =
        ConfigImportParser.parse(text, fileName, definition, current)

    private fun backupJson(): String = ConfigBackupCodec.encode(
        ConfigBackupCodec.export(current, definition, emptyList(), emptyMap(), BackupAppInfo("a", "1"), 0L),
    )

    private fun mxuTask(optionValues: String) =
        """{"instances":[{"name":"t","tasks":[{"taskName":"Daily","enabled":true,"optionValues":{$optionValues}}]}],"settings":{}}"""

    private fun mxuResult(text: String, onDefinition: com.aliothmoon.maafw.domain.ProjectDefinition = definition): MxuImport {
        val parsed = ConfigImportParser.parse(text, "mxu-MaaEnd.json", onDefinition, current)
        check(parsed is ConfigImportParse.Parsed) { "unexpected $parsed" }
        return (parsed.import as ConfigImport.FromMxu).result
    }

    private fun MxuImport.task() = configurations.single().tasks.single()

    // ---- 格式对、内容坏 ----

    @Test
    fun `备份字段类型写错就整份认不出`() {
        val broken = backupJson().replace(Regex(""""configurations": \[\s*]"""), """"configurations": "oops"""")

        assertEquals(ConfigImportError.Unrecognized, (parse(broken) as ConfigImportParse.Failed).error)
    }

    @Test
    fun `备份缺了必填字段就整份认不出`() {
        val broken = backupJson().replace(Regex(""""exportedAt": \d+,"""), "")

        assertEquals(ConfigImportError.Unrecognized, (parse(broken) as ConfigImportParse.Failed).error)
    }

    @Test
    fun `备份里不认识的枚举值回落默认，其余照常导入`() {
        val schedule = """{"id":"s","name":"n","scheduleType":"FUTURE_TYPE","runConfigurationId":"c","daysOfWeek":[1],"executionTimes":["08:00"]}"""
        val text = backupJson()
            .replace(""""themeMode": "System"""", """"themeMode": "Sepia"""")
            .replace(Regex(""""schedules": \[\s*]"""), """"schedules": [$schedule]""")

        val backup = ((parse(text) as ConfigImportParse.Parsed).import as ConfigImport.Restore).backup

        assertEquals(ThemeMode.System, backup.configuration.themeMode)
        assertEquals(ScheduleType.FIXED_TIME, backup.schedules.single().scheduleType)
        assertEquals("n", backup.schedules.single().name)
    }

    @Test
    fun `MXU 文件结构坏了就整份认不出`() {
        val text = """{"instances":"oops","settings":{}}"""

        assertEquals(ConfigImportError.Unrecognized, (parse(text) as ConfigImportParse.Failed).error)
    }

    // ---- MXU 的密码解不开 ----

    private val pinKey = "MXU-INPUT-MaaEnd-Account-pin"

    private fun accountWithEncrypted(encrypted: String) =
        mxuTask(""""Account":{"type":"input","values":{"user":"u","pin":""},"encryptedValues":{"pin":"$encrypted"}}""")

    @Test
    fun `密文不是合法 Base64 就当没填，不导乱码`() {
        val inputs = mxuResult(accountWithEncrypted("@@not-base64@@")).task().optionValues["Account"] as OptionValue.Inputs

        assertEquals(mapOf("user" to "u"), inputs.values)
        assertEquals(setOf("pin"), inputs.secretFields)
    }

    @Test
    fun `解出来不是合法 UTF-8 就当没填`() {
        val encrypted = mxuEncryptBytes(byteArrayOf(0xC3.toByte(), 0x28, 0xFF.toByte()), pinKey)

        val inputs = mxuResult(accountWithEncrypted(encrypted)).task().optionValues["Account"] as OptionValue.Inputs

        assertNull(inputs.values["pin"])
    }

    @Test
    fun `解出来带控制字符就当没填`() {
        val inputs = mxuResult(accountWithEncrypted(mxuEncrypt("ab\u0001cd", pinKey))).task()
            .optionValues["Account"] as OptionValue.Inputs

        assertNull(inputs.values["pin"])
    }

    @Test
    fun `密文解不开时退回明文字段（旧版 MXU 存的明文）`() {
        val text = mxuTask(""""Account":{"type":"input","values":{"pin":"legacy"},"encryptedValues":{"pin":"@@"}}""")

        assertEquals("legacy", (mxuResult(text).task().optionValues["Account"] as OptionValue.Inputs).values["pin"])
    }

    @Test
    fun `正常的非 ASCII 密码照样解开`() {
        val inputs = mxuResult(accountWithEncrypted(mxuEncrypt("密码 pass ✓", pinKey))).task()
            .optionValues["Account"] as OptionValue.Inputs

        assertEquals("密码 pass ✓", inputs.values["pin"])
    }

    // ---- 选项值对不上 ----

    private fun case(name: String) = OptionCaseDefinition(name, name, null, JsonObject(emptyMap()), emptyList())

    @Test
    fun `switch 的 case 不是 Yes 或 No 这类名字就丢掉`() {
        val onOff = definition.copy(
            options = definition.options +
                ("Toggle" to OptionDefinition.Switch("Toggle", "Toggle", null, listOf(case("On"), case("Off")), "Off")),
        )

        val result = mxuResult(mxuTask(""""Toggle":{"type":"switch","value":true}"""), onOff)

        assertFalse("Toggle" in result.task().optionValues)
        assertEquals(1, result.skipped.options)
    }

    @Test
    fun `选项类型和 PI 对不上就丢掉`() {
        val text = mxuTask(
            """"Mode":{"type":"checkbox","caseNames":["A"]},"Pick":{"type":"select","caseName":"x"},""" +
                """"Account":{"type":"select","caseName":"x"},"Toggle":{"type":"input","values":{}}""",
        )

        val result = mxuResult(text)

        assertEquals(emptyMap<String, OptionValue>(), result.task().optionValues)
        assertEquals(4, result.skipped.options)
    }

    @Test
    fun `认不出的值类型（hotkey、缺 type、缺字段）就丢掉`() {
        val text = mxuTask(
            """"Mode":{"type":"hotkey","values":{"k":"F1"}},"Sub":{"caseName":"s1"},"Toggle":{"type":"switch"}""",
        )

        val result = mxuResult(text)

        assertEquals(emptyMap<String, OptionValue>(), result.task().optionValues)
        assertEquals(3, result.skipped.options)
    }
}
