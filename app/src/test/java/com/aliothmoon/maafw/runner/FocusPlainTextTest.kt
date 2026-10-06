package com.aliothmoon.maafw.runner

import org.junit.Assert.assertEquals
import org.junit.Test

class FocusPlainTextTest {

    @Test
    fun `text without markup passes through`() {
        assertEquals("刷到第3关", focusPlainText("刷到第3关"))
        assertEquals("第一行\n第二行", focusPlainText("第一行\n第二行"))
        assertEquals("HP < 50 & MP > 10", focusPlainText("HP < 50 & MP > 10"))
    }

    @Test
    fun `inline html tags are dropped and their text kept`() {
        assertEquals(
            "体力不足，已停止",
            focusPlainText("""<span style="color: red; font-weight: bold">体力不足</span>，已停止"""),
        )
        assertEquals("警告", focusPlainText("""<font color="#ff0000">警告</font>"""))
        assertEquals("第一行\n第二行", focusPlainText("第一行<br>第二行"))
        assertEquals("第一行\n第二行", focusPlainText("第一行<br/>第二行"))
    }

    @Test
    fun `block html is stripped line by line`() {
        assertEquals(
            "标题\n正文 a < b",
            focusPlainText("<div><p>标题</p>\n<p>正文&nbsp;a &lt; b</p></div>"),
        )
        assertEquals("可见", focusPlainText("<div><!-- 注释 -->可见</div>"))
        assertEquals("A&unknown;B", focusPlainText("<div>&#65;&unknown;&#x42;</div>"))
    }

    @Test
    fun `markdown markers are dropped`() {
        assertEquals("刷到 第3关 了", focusPlainText("刷到 **第3关** 了"))
        assertEquals("完成", focusPlainText("## 完成"))
        assertEquals("详情", focusPlainText("[详情](https://example.com/a)"))
        assertEquals("StartFight", focusPlainText("`StartFight`"))
        assertEquals("作废", focusPlainText("~~作废~~"))
        assertEquals("一\n二", focusPlainText("- 一\n- 二"))
        assertEquals("a*b", focusPlainText("""a\*b"""))
    }

    @Test
    fun `images leave only their alt text`() {
        assertEquals("掉落：", focusPlainText("掉落：![](file:///sdcard/a.png)"))
        assertEquals("掉落：截图", focusPlainText("掉落：![截图](a.png)"))
        assertEquals("掉落：", focusPlainText("""掉落：<img src="a.png">"""))
        assertEquals("", focusPlainText("![](file:///sdcard/a.png)"))
    }

    @Test
    fun `blank lines between blocks are not kept`() {
        assertEquals("第一段\n第二段", focusPlainText("第一段\n\n\n第二段\n"))
    }
}
