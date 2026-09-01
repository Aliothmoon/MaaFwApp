package com.aliothmoon.maafw.project

import com.aliothmoon.maafw.domain.OptionDefinition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 只查表、不读文件的物化钩子；文件形态由 ProjectLoader 负责，这里不进 IO */
private class MapTextResolver(private val translations: Map<String, String>) : PiTextResolver {
    override fun label(raw: String?): String? = raw?.let(::translate)
    override fun description(raw: String?): String? = raw?.let(::translate)
    private fun translate(raw: String): String =
        if (raw.startsWith("$")) translations[raw.substring(1)] ?: raw.substring(1) else raw
}

private fun root(json: String) = Json.parseToJsonElement(json).jsonObject

class PiMetadataTest {

    @Test
    fun `顶层展示字段按 i18n 物化`() {
        val metadata = PiParser.parseMetadata(
            root(
                """
                {
                  "welcome": "${'$'}welcome.body",
                  "description": "一句话说明",
                  "contact": "CONTACT",
                  "license": "LICENSE",
                  "github": "https://example.com/owner/repo"
                }
                """.trimIndent(),
            ),
            MapTextResolver(mapOf("welcome.body" to "欢迎使用")),
        )

        assertEquals("欢迎使用", metadata.welcome)
        assertEquals("一句话说明", metadata.description)
        assertEquals("CONTACT", metadata.contact)
        assertEquals("https://example.com/owner/repo", metadata.github)
    }

    @Test
    fun `github 非 http 形态不投影`() {
        val metadata = PiParser.parseMetadata(
            root("""{ "github": "owner/repo" }"""),
            MapTextResolver(emptyMap()),
        )
        assertNull(metadata.github)
        assertNull(metadata.githubRepository)
    }

    @Test
    fun `github 仓库页解析出 owner repo`() {
        val metadata = PiParser.parseMetadata(
            root("""{ "github": "https://github.com/owner/repo/releases" }"""),
            MapTextResolver(emptyMap()),
        )

        assertEquals("owner/repo", metadata.githubRepository)
    }

    @Test
    fun `mirrorchyan rid 原样投影`() {
        val metadata = PiParser.parseMetadata(
            root("""{ "mirrorchyan_rid": " M9A " }"""),
            MapTextResolver(emptyMap()),
        )

        assertEquals("M9A", metadata.mirrorchyanRid)
    }

    /** 指纹算在原始声明上，否则切一次语言就会让同一份 welcome 再弹一次 */
    @Test
    fun `welcome 指纹不随语言变化`() {
        val source = root("""{ "version": "1.2.0", "welcome": "${'$'}welcome.body" }""")
        val zh = PiParser.parseMetadata(source, MapTextResolver(mapOf("welcome.body" to "欢迎")))
        val en = PiParser.parseMetadata(source, MapTextResolver(mapOf("welcome.body" to "Welcome")))

        assertNotEquals(zh.welcome, en.welcome)
        assertEquals(zh.welcomeFingerprint, en.welcomeFingerprint)
    }

    @Test
    fun `PI 版本变化时指纹跟着变`() {
        val text = MapTextResolver(emptyMap())
        val v1 = PiParser.parseMetadata(root("""{ "version": "1.0.0", "welcome": "hi" }"""), text)
        val v2 = PiParser.parseMetadata(root("""{ "version": "1.1.0", "welcome": "hi" }"""), text)
        assertNotEquals(v1.welcomeFingerprint, v2.welcomeFingerprint)
    }

    @Test
    fun `没有 welcome 就没有指纹`() {
        val metadata = PiParser.parseMetadata(root("""{ "name": "x" }"""), MapTextResolver(emptyMap()))
        assertNull(metadata.welcome)
        assertNull(metadata.welcomeFingerprint)
    }

    @Test
    fun `telemetry 缺 dsn 视为未声明`() {
        assertNull(PiParser.parseTelemetry(root("""{ "telemetry": { "sentry": { "dsn": "" } } }""")))
        assertNull(PiParser.parseTelemetry(root("""{ "telemetry": {} }""")))
        assertNull(PiParser.parseTelemetry(root("""{ }""")))
    }

    @Test
    fun `telemetry 取值与默认值`() {
        val full = PiParser.parseTelemetry(
            root(
                """
                {
                  "telemetry": {
                    "sentry": {
                      "dsn": "https://key@example.com/1",
                      "tracing": false,
                      "traces_sample_rate": 0.25,
                      "environment": "beta"
                    }
                  }
                }
                """.trimIndent(),
            ),
        )!!
        assertEquals("https://key@example.com/1", full.dsn)
        assertEquals(false, full.tracing)
        assertEquals(0.25, full.tracesSampleRate, 0.0)
        assertEquals("beta", full.environment)

        val defaults = PiParser.parseTelemetry(
            root("""{ "telemetry": { "sentry": { "dsn": "https://key@example.com/1" } } }"""),
        )!!
        assertTrue(defaults.tracing)
        assertEquals(1.0, defaults.tracesSampleRate, 0.0)
        assertNull(defaults.environment)
    }

    @Test
    fun `各级 icon 解析并规范化路径`() {
        val parsed = PiParser.parseFile(
            "interface.json",
            root(
                """
                {
                  "group": [{ "name": "g", "icon": "./icon/g.png" }],
                  "task": [{ "name": "t", "entry": "T", "icon": "icon/t.png" }],
                  "preset": [{ "name": "p", "icon": "icon/p.png", "task": [] }],
                  "option": {
                    "o": {
                      "type": "select",
                      "icon": "./icon/o.png",
                      "cases": [{ "name": "c", "icon": "icon/c.webp" }]
                    }
                  }
                }
                """.trimIndent(),
            ),
            MapTextResolver(emptyMap()),
        )

        assertEquals("icon/g.png", parsed.groups.single().icon)
        assertEquals("icon/t.png", parsed.tasks.single().icon)
        assertEquals("icon/p.png", parsed.templates.single().icon)
        val option = parsed.options.getValue("o") as OptionDefinition.Select
        assertEquals("icon/o.png", option.icon)
        assertEquals("icon/c.webp", option.cases.single().icon)
    }

    /** icon 是路径不是文案：走查表的话 $ 开头的路径会被当成 i18n key */
    @Test
    fun `icon 不查翻译表`() {
        val parsed = PiParser.parseFile(
            "interface.json",
            root("""{ "task": [{ "name": "t", "entry": "T", "icon": "${'$'}icon.key" }] }"""),
            MapTextResolver(mapOf("icon.key" to "translated.png")),
        )
        assertEquals("＄icon.key".replace('＄', '$'), parsed.tasks.single().icon)
    }
}
