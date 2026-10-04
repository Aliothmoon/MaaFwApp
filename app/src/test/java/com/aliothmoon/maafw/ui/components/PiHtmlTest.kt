package com.aliothmoon.maafw.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PiHtmlTest {

    @Test
    fun `html blocks pass through with their inline styles`() {
        val html = piBodiesToHtml(listOf("<div style=\"display: flex; gap: 0.75rem;\">\n<a href=\"https://x\">x</a>\n</div>\n\n## 新动态"))
        assertTrue(html, html.contains("<div style=\"display: flex; gap: 0.75rem;\">"))
        assertTrue(html, html.contains("<h2>新动态</h2>"))
    }

    @Test
    fun `a single newline inside a paragraph is a line break like MXU`() {
        assertTrue(piBodiesToHtml(listOf("第一行\n第二行")).contains("第一行<br />\n第二行"))
    }

    @Test
    fun `indented text stays a paragraph and tables render`() {
        val html = piBodiesToHtml(listOf("        GNU GENERAL PUBLIC LICENSE\n\n| a | b |\n|---|---|\n| 1 | 2 |"))
        assertFalse(html, html.contains("<pre>"))
        assertTrue(html, html.contains("<table>"))
    }

    @Test
    fun `several bodies are split by a rule`() {
        val html = piBodiesToHtml(listOf("a", "b"))
        assertTrue(html, html.contains("<p>a</p>\n\n<hr class=\"maa-split\" />\n<p>b</p>"))
    }
}
