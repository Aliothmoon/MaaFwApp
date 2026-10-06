package com.aliothmoon.maafw.settings.search

import com.aliothmoon.maafw.domain.OptionEditorState
import com.aliothmoon.maafw.domain.OptionKind
import com.aliothmoon.maafw.domain.OptionSectionState
import com.aliothmoon.maafw.i18n.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SettingSearchTest {

    // ── 匹配 ────────────────────────────────────────────────────────

    private val dummyLocation = SettingLocation.Section("s", UiText.Verbatim("S"))

    private fun searchable(title: String, keywords: String = "", path: String = "") = SearchableSetting(
        entry = SettingSearchEntry(UiText.Verbatim(title), dummyLocation, anchor = title),
        title = title,
        description = "",
        keywords = keywords,
        path = path,
    )

    @Test
    fun `blank query matches nothing`() {
        assertEquals(emptyList<SearchableSetting>(), SettingSearchMatcher.filter(listOf(searchable("主题")), "  "))
    }

    @Test
    fun `every token must hit title keywords or path`() {
        val theme = searchable("主题", keywords = "深色 夜间")
        val language = searchable("语言", path = "显示设置")

        assertEquals(listOf(theme), SettingSearchMatcher.filter(listOf(theme, language), "深色"))
        assertEquals(listOf(language), SettingSearchMatcher.filter(listOf(theme, language), "显示 语言"))
        assertEquals(emptyList<SearchableSetting>(), SettingSearchMatcher.filter(listOf(theme, language), "深色 语言"))
    }

    @Test
    fun `title hits rank before keyword hits and keep index order otherwise`() {
        val viaKeyword = searchable("外观", keywords = "主题")
        val second = searchable("主题风格")
        val first = searchable("主题")

        assertEquals(
            listOf(second, first, viaKeyword),
            SettingSearchMatcher.filter(listOf(viaKeyword, second, first), "主题"),
        )
    }

    @Test
    fun `matching ignores case`() {
        val shizuku = searchable("启动模式", keywords = "Shizuku Root")

        assertEquals(listOf(shizuku), SettingSearchMatcher.filter(listOf(shizuku), "shizuku"))
    }

    // ── 请求 ────────────────────────────────────────────────────────

    private val entry = SettingSearchIndex.staticEntries.first()

    @Test
    fun `a request goes stale after its ttl`() {
        val request = SettingSearchRequest(entry, requestedAtMs = 1_000)

        assertTrue(request.isFresh(nowMs = 1_000 + SettingSearchRequest.TTL_MS))
        assertFalse(request.isFresh(nowMs = 1_001 + SettingSearchRequest.TTL_MS))
    }

    @Test
    fun `consuming an old request keeps a newer one`() {
        var now = 0L
        val navigator = SettingSearchNavigator(clock = { now })
        navigator.request(entry)
        val old = navigator.pending.value!!
        now = 10
        navigator.request(entry)
        val newer = navigator.pending.value!!

        navigator.consume(old)
        assertSame(newer, navigator.pending.value)

        navigator.consume(newer)
        assertNull(navigator.pending.value)
    }

    // ── PI 选项 ─────────────────────────────────────────────────────

    private fun option(name: String, depth: Int = 0, label: String = name) = OptionEditorState(
        name = name,
        label = label,
        description = null,
        kind = OptionKind.Switch,
        depth = depth,
        value = null,
        cases = emptyList(),
        inputs = emptyList(),
    )

    @Test
    fun `project entries cover top level options and skip children`() {
        val section = OptionSectionState(
            name = "combat",
            label = "战斗",
            description = null,
            icon = null,
            defaultExpand = false,
            options = listOf(option("auto"), option("auto_child", depth = 1)),
        )
        val entries = SettingSearchIndex.projectEntries(
            sections = listOf(section),
            globalOptions = listOf(option("auto"), option("loose")),
            resourceOptions = listOf(option("server")),
            controllerOptions = emptyList(),
        )

        assertEquals(
            listOf(
                SettingAnchors.projectOption(SettingsSections.projectSection("combat"), "auto"),
                SettingAnchors.projectOption(SettingsSections.PI_GLOBAL, "loose"),
                SettingAnchors.projectOption(SettingsSections.PI_RESOURCE, "server"),
            ),
            entries.map { it.anchor },
        )
        // 进了分区的落在分区卡上，没进的落「任务设置」兜底卡
        assertEquals(
            listOf(
                SettingsSections.projectSection("combat"),
                SettingsSections.PI_GLOBAL,
                SettingsSections.PI_RESOURCE,
            ),
            entries.map { (it.location as SettingLocation.Section).sectionKey },
        )
    }

    @Test
    fun `an option shared by two sections gets one anchor per section`() {
        fun section(name: String) = OptionSectionState(
            name = name,
            label = name,
            description = null,
            icon = null,
            defaultExpand = false,
            options = listOf(option("auto")),
        )
        val entries = SettingSearchIndex.projectEntries(
            sections = listOf(section("combat"), section("daily")),
            globalOptions = listOf(option("auto")),
            resourceOptions = emptyList(),
            controllerOptions = emptyList(),
        )

        assertEquals(
            listOf(
                SettingAnchors.projectOption(SettingsSections.projectSection("combat"), "auto"),
                SettingAnchors.projectOption(SettingsSections.projectSection("daily"), "auto"),
            ),
            entries.map { it.anchor },
        )
    }

    @Test
    fun `blank project label falls back to the option name`() {
        val entries = SettingSearchIndex.projectEntries(
            sections = emptyList(),
            globalOptions = listOf(option("raw_name", label = " ")),
            resourceOptions = emptyList(),
            controllerOptions = emptyList(),
        )

        assertEquals(UiText.Verbatim("raw_name"), entries.single().title)
    }

    // ── 索引与界面的契约：没有代码生成，靠扫源码兜住 ─────────────────

    private val mainSources: String by lazy {
        File("src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { it.readText() }
    }

    /** SettingAnchors 里 const val 的「常量名 → 值」；函数生成的 PI 锚点不在这里 */
    private val anchorConstants: Map<String, String> by lazy {
        SettingAnchors::class.java.declaredFields
            .filter { it.type == String::class.java && java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .associate { it.name to it.get(null) as String }
    }

    @Test
    fun `every anchor constant is attached to a target in the ui`() {
        val missing = anchorConstants.keys.filterNot { "SettingSearchTarget(SettingAnchors.$it)" in mainSources }

        assertEquals("anchors without a SettingSearchTarget", emptyList<String>(), missing)
    }

    @Test
    fun `every static entry points at a declared anchor`() {
        val declared = anchorConstants.values.toSet()
        val unknown = SettingSearchIndex.staticEntries.map { it.anchor }.filterNot { it in declared }

        assertEquals(emptyList<String>(), unknown)
    }

    @Test
    fun `every section a static entry lands in reveals itself`() {
        val sectionFields = SettingsSections::class.java.declaredFields
            .filter { it.type == String::class.java && java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .associate { it.get(null) as String to it.name }
        val missing = SettingSearchIndex.staticEntries
            .mapNotNull { (it.location as? SettingLocation.Section)?.sectionKey }
            .distinct()
            .filterNot { key -> "sectionRevealToken(SettingsSections.${sectionFields[key]})" in mainSources }

        assertEquals("sections without a revealToken", emptyList<String>(), missing)
    }

    @Test
    fun `project option anchors are wired for every scope`() {
        listOf("PI_GLOBAL", "PI_RESOURCE", "PI_CONTROLLER").forEach { scope ->
            assertTrue(scope, "SettingAnchors.projectOption(SettingsSections.$scope, it.name)" in mainSources)
            assertTrue(scope, "sectionRevealToken(SettingsSections.$scope)" in mainSources)
        }
        assertTrue("sectionRevealToken(SettingsSections.projectSection(section.name))" in mainSources)
        assertTrue(
            "SettingAnchors.projectOption(SettingsSections.projectSection(section.name), it.name)" in mainSources,
        )
    }

    /** 整张卡自己就是锚点、又默认收起的首页卡：不跟着展开，定位过去只闪一下标题 */
    @Test
    fun `collapsed home cards reveal themselves`() {
        listOf("RESOURCE", "CONTROLLER").forEach { anchor ->
            assertTrue(anchor, "anchorRevealToken(SettingAnchors.$anchor)" in mainSources)
        }
    }

    @Test
    fun `static entry titles are unique`() {
        val titles = SettingSearchIndex.staticEntries.map { it.title }

        assertEquals(titles.size, titles.toSet().size)
    }
}
