package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.domain.ControllerDefinition
import com.aliothmoon.maafw.domain.ResourceDefinition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 对齐 MXU 的 `src/utils/piEnv.ts`：整条透传、`$` 前缀递归查表、拿不到就不设 */
class PiAgentEnvTest {

    private fun obj(json: String) = Json.parseToJsonElement(json) as JsonObject

    private fun controller(json: String = """{"name":"ADB","label":"模拟器","type":"Adb"}""") =
        ControllerDefinition(name = "ADB", type = "Adb", raw = obj(json))

    private fun resource(json: String = """{"name":"官服","path":["./resource/base"]}""") =
        ResourceDefinition(name = "官服", paths = listOf("./resource/base"), raw = obj(json))

    private fun build(
        projectVersion: String? = "v0.1.0",
        controller: ControllerDefinition = controller(),
        resource: ResourceDefinition = resource(),
        translations: Map<String, String> = emptyMap(),
        clientVersion: String? = "abc1234",
        clientLanguage: String? = "zh-CN",
    ) = PiAgentEnv.build(
        projectVersion = projectVersion,
        controller = controller,
        resource = resource,
        translations = translations,
        clientVersion = clientVersion,
        clientLanguage = clientLanguage,
    )

    @Test
    fun `按协议注入七项，MAAFW 版本留给特权进程侧`() {
        val env = build()
        assertEquals("v2.5.0", env["PI_INTERFACE_VERSION"])
        assertEquals("MaaFwApp", env["PI_CLIENT_NAME"])
        assertEquals("abc1234", env["PI_CLIENT_VERSION"])
        assertEquals("zh-CN", env["PI_CLIENT_LANGUAGE"])
        assertEquals("v0.1.0", env["PI_VERSION"])
        assertTrue(env.containsKey("PI_CONTROLLER"))
        assertTrue(env.containsKey("PI_RESOURCE"))
        assertFalse("MaaVersion 只有特权进程问得到", env.containsKey("PI_CLIENT_MAAFW_VERSION"))
    }

    /** 条目整条透传，不裁剪成投影里的那几个字段 */
    @Test
    fun `controller 与 resource 整条序列化`() {
        val env = build()
        assertEquals("""{"name":"ADB","label":"模拟器","type":"Adb"}""", env["PI_CONTROLLER"])
        assertEquals("""{"name":"官服","path":["./resource/base"]}""", env["PI_RESOURCE"])
    }

    @Test
    fun `美元前缀递归查表，普通字符串原样保留`() {
        val env = build(
            resource = resource("""{"name":"官服","label":"${'$'}res.cn","nested":{"deep":["${'$'}res.cn","./resource/base"]}}"""),
            translations = mapOf("res.cn" to "中国大陆"),
        )
        assertEquals(
            """{"name":"官服","label":"中国大陆","nested":{"deep":["中国大陆","./resource/base"]}}""",
            env["PI_RESOURCE"],
        )
    }

    /** MXU 的 `translations?.[key] ?? key`：查不到退回 key 本身，不留 `$` */
    @Test
    fun `查不到的 key 退回 key 本身`() {
        val env = build(
            resource = resource("""{"name":"官服","label":"${'$'}missing"}"""),
            translations = emptyMap(),
        )
        assertEquals("""{"name":"官服","label":"missing"}""", env["PI_RESOURCE"])
    }

    /** 不设空串：agent 侧靠「变量在不在」判断 Client 支不支持这套约定 */
    @Test
    fun `拿不到的项整个不设`() {
        val env = build(
            projectVersion = null,
            controller = ControllerDefinition(),
            resource = ResourceDefinition("官服", listOf("./resource/base")),
            clientVersion = null,
            clientLanguage = "",
        )
        assertFalse(env.containsKey("PI_VERSION"))
        assertFalse(env.containsKey("PI_CLIENT_VERSION"))
        assertFalse(env.containsKey("PI_CLIENT_LANGUAGE"))
        assertFalse("PI 没声明 controller 时不该硬拼一条", env.containsKey("PI_CONTROLLER"))
        assertFalse(env.containsKey("PI_RESOURCE"))
        // 常量项与是否有 PI 数据无关
        assertEquals("v2.5.0", env["PI_INTERFACE_VERSION"])
    }
}
