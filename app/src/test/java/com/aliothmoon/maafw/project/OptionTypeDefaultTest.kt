package com.aliothmoon.maafw.project

import com.aliothmoon.maafw.domain.OptionDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** option.type 按协议可省略，缺省即 select */
class OptionTypeDefaultTest {

    private object Verbatim : PiTextResolver {
        override fun label(raw: String?): String? = raw
        override fun description(raw: String?): String? = raw
    }

    private fun parse(typeField: String) = PiParser.parseFile(
        "interface.json",
        """
            {
              "option": {
                "o": {
                  $typeField
                  "cases": [{ "name": "a" }, { "name": "b" }],
                  "default_case": "b"
                }
              }
            }
        """.trimIndent(),
        Verbatim,
    )

    @Test
    fun `missing type parses as select`() {
        val parsed = parse("")
        val option = parsed.options.getValue("o") as OptionDefinition.Select
        assertEquals(listOf("a", "b"), option.cases.map { it.name })
        assertEquals("b", option.defaultCase)
        assertTrue(parsed.diagnostics.isEmpty())
    }

    @Test
    fun `explicit type still wins`() {
        val parsed = parse(""""type": "switch",""")
        assertTrue(parsed.options.getValue("o") is OptionDefinition.Switch)
    }
}
