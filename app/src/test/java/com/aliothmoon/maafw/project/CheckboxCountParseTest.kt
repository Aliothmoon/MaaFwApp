package com.aliothmoon.maafw.project

import com.aliothmoon.maafw.domain.Diagnostic
import com.aliothmoon.maafw.domain.DiagnosticSeverity
import com.aliothmoon.maafw.domain.OptionDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private object VerbatimText : PiTextResolver {
    override fun label(raw: String?): String? = raw
    override fun description(raw: String?): String? = raw
}

/** v2.10.1 checkbox 的 min_count / max_count；写坏的值收敛到能满足的区间，并记 warning */
class CheckboxCountParseTest {

    private fun parse(extra: String): Pair<OptionDefinition.Checkbox, List<Diagnostic>> {
        val content = """
            {
              "option": {
                "o": {
                  "type": "checkbox",
                  "cases": [{ "name": "a" }, { "name": "b" }, { "name": "c" }]
                  $extra
                }
              }
            }
        """.trimIndent()
        val parsed = PiParser.parseFile("interface.json", content, VerbatimText)
        return parsed.options.getValue("o") as OptionDefinition.Checkbox to parsed.diagnostics
    }

    private fun List<Diagnostic>.warnings() = filter { it.severity == DiagnosticSeverity.Warning }

    @Test
    fun `缺省时不限数量`() {
        val (option, diagnostics) = parse("")
        assertEquals(0, option.minCount)
        assertNull(option.maxCount)
        assertTrue(diagnostics.isEmpty())
    }

    @Test
    fun `合法取值原样投影`() {
        val (option, diagnostics) = parse(""", "min_count": 1, "max_count": 2""")
        assertEquals(1, option.minCount)
        assertEquals(2, option.maxCount)
        assertTrue(diagnostics.isEmpty())
    }

    @Test
    fun `min 超过 cases 数收敛到 cases 数`() {
        val (option, diagnostics) = parse(""", "min_count": 5""")
        assertEquals(3, option.minCount)
        assertEquals(1, diagnostics.warnings().size)
    }

    @Test
    fun `负数 min 按 0，负数 max 当不限`() {
        val (option, diagnostics) = parse(""", "min_count": -1, "max_count": -2""")
        assertEquals(0, option.minCount)
        assertNull(option.maxCount)
        assertEquals(2, diagnostics.warnings().size)
    }

    @Test
    fun `max 小于 min 时抬到 min`() {
        val (option, diagnostics) = parse(""", "min_count": 2, "max_count": 1""")
        assertEquals(2, option.minCount)
        assertEquals(2, option.maxCount)
        assertEquals(1, diagnostics.warnings().size)
    }

    @Test
    fun `default_case 条数越界记 warning 但照常作为默认值`() {
        val (option, diagnostics) = parse(""", "max_count": 1, "default_case": ["a", "b"]""")
        assertEquals(listOf("a", "b"), option.defaultCases)
        assertEquals(1, diagnostics.warnings().size)
    }

    @Test
    fun `没写 default_case 不查默认条数`() {
        val (_, diagnostics) = parse(""", "min_count": 1""")
        assertTrue(diagnostics.isEmpty())
    }
}
