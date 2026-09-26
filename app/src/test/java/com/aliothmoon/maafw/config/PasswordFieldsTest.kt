package com.aliothmoon.maafw.config

import com.aliothmoon.maafw.domain.ConfiguredTask
import com.aliothmoon.maafw.domain.ControllerDefinition
import com.aliothmoon.maafw.domain.InputFieldDefinition
import com.aliothmoon.maafw.domain.OptionDefinition
import com.aliothmoon.maafw.domain.OptionValue
import com.aliothmoon.maafw.domain.PipelineType
import com.aliothmoon.maafw.domain.ProjectDefinition
import com.aliothmoon.maafw.domain.RunConfiguration
import com.aliothmoon.maafw.domain.RunConfigurationId
import com.aliothmoon.maafw.domain.UserConfiguration
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** password 字段在内存里是明文、落盘是密文；标记、加解密、打码收集都走同一套遍历 */
class PasswordFieldsTest {

    private val definition = ProjectDefinition(
        name = "p",
        version = "1.0.0",
        controller = ControllerDefinition(),
        resources = emptyList(),
        tasks = emptyList(),
        groups = emptyList(),
        options = mapOf(
            "账号" to OptionDefinition.Input(
                name = "账号",
                label = "账号",
                description = null,
                fields = listOf(field("user", password = false), field("pin", password = true)),
                pipelineOverride = JsonObject(emptyMap()),
            ),
        ),
        templates = emptyList(),
    )

    private fun field(name: String, password: Boolean) = InputFieldDefinition(
        name = name,
        pipelineType = PipelineType.StringType,
        default = "",
        verify = null,
        patternMessage = null,
        description = null,
        password = password,
    )

    private fun config(value: OptionValue): UserConfiguration {
        val id = RunConfigurationId("c")
        return UserConfiguration(
            initialized = true,
            globalOptionValues = mapOf("账号" to value),
            resourceOptionValues = mapOf("官服" to mapOf("账号" to value)),
            configurations = listOf(RunConfiguration(id, "c", listOf(ConfiguredTask("t", optionValues = mapOf("账号" to value))))),
            activeConfigurationId = id,
        )
    }

    private fun UserConfiguration.inputs(): List<OptionValue.Inputs> =
        listOf(
            globalOptionValues.getValue("账号"),
            resourceOptionValues.getValue("官服").getValue("账号"),
            configurations.single().tasks.single().optionValues.getValue("账号"),
        ).map { it as OptionValue.Inputs }

    @Test
    fun `按定义给各作用域补标记，没有要补的返回原对象`() {
        val marked = config(OptionValue.Inputs(mapOf("user" to "u", "pin" to "secret1"))).withPasswordFieldsMarked(definition)

        marked.inputs().forEach { assertEquals(setOf("pin"), it.secretFields) }
        assertSame(marked, marked.withPasswordFieldsMarked(definition))
    }

    @Test
    fun `落盘形态只有密文，读回还原明文`() {
        val plain = config(OptionValue.Inputs(mapOf("user" to "u", "pin" to "secret1"), secretFields = setOf("pin")))
        val sealed = plain.withSecretsSealed { "enc:${it.reversed()}" }

        sealed.inputs().forEach {
            assertEquals(mapOf("user" to "u"), it.values)
            assertEquals(mapOf("pin" to "enc:1terces"), it.sealed)
        }
        assertEquals(plain, sealed.withSecretsOpened { it.removePrefix("enc:").reversed() })
    }

    @Test
    fun `加密失败的值丢掉而不是落明文，解不开的当作没填`() {
        val plain = config(OptionValue.Inputs(mapOf("user" to "u", "pin" to "secret1"), secretFields = setOf("pin")))

        val sealed = plain.withSecretsSealed { null }
        sealed.inputs().forEach { assertFalse("pin" in it.values || "pin" in it.sealed) }

        val opened = plain.withSecretsSealed { "enc" }.withSecretsOpened { null }
        opened.inputs().forEach { assertEquals(mapOf("user" to "u"), it.values) }
    }

    @Test
    fun `打码只收已标记字段的明文`() {
        val value = OptionValue.Inputs(mapOf("user" to "u-plain", "pin" to "secret1"), secretFields = setOf("pin"))
        assertEquals(setOf("secret1"), config(value).passwordPlaintexts())
    }

    @Test
    fun `toString 不带已标记字段的明文`() {
        val value = OptionValue.Inputs(mapOf("user" to "u", "pin" to "secret1"), secretFields = setOf("pin"))
        assertFalse("secret1" in value.toString())
        assertTrue("u" in value.toString())
    }

    @Test
    fun `序列化器落盘不含明文并能读回`() = runTest {
        mockkObject(OptionSecretCipher)
        try {
            every { OptionSecretCipher.seal(any()) } answers { "v1:" + firstArg<String>().reversed() }
            every { OptionSecretCipher.open(any()) } answers { firstArg<String>().removePrefix("v1:").reversed() }
            val plain = config(OptionValue.Inputs(mapOf("user" to "u", "pin" to "secret1"), secretFields = setOf("pin")))

            val bytes = ByteArrayOutputStream().also { UserConfigurationSerializer.writeTo(plain, it) }.toByteArray()
            assertFalse("secret1" in bytes.decodeToString())

            assertEquals(plain, UserConfigurationSerializer.readFrom(ByteArrayInputStream(bytes)))
        } finally {
            unmockkObject(OptionSecretCipher)
        }
    }

    @Test
    fun `旧格式文件照常读`() = runTest {
        val legacy = """
            {"schemaVersion":1,"config":{"initialized":true,
             "globalOptionValues":{"账号":{"type":"inputs","values":{"user":"u","pin":"old"}}}}}
        """.trimIndent()

        val config = UserConfigurationSerializer.readFrom(ByteArrayInputStream(legacy.toByteArray()))

        assertEquals(OptionValue.Inputs(mapOf("user" to "u", "pin" to "old")), config.globalOptionValues.getValue("账号"))
    }
}
