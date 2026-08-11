package com.aliothmoon.maafw.runner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 样例取自 MaaFramework `docs/zh_cn/3.3-ProjectInterfaceV2协议.md`「消息模板机制」
 * 与 M9A 的 pipeline 实际写法
 */
class FocusParserTest {

    @Test
    fun `no focus key returns null`() {
        assertNull(FocusParser.parse("Node.Action.Starting", """{"task_id":1,"name":"NodeA"}"""))
    }

    @Test
    fun `other message in the same focus map is not picked up`() {
        val details = """{"name":"NodeA","focus":{"Node.Action.Failed":"炸了"}}"""
        assertNull(FocusParser.parse("Node.Action.Starting", details))
    }

    @Test
    fun `string shorthand defaults to the log channel`() {
        val details = """{"name":"NodeA","focus":{"Node.Action.Starting":"{name} 开始执行"}}"""
        val focus = FocusParser.parse("Node.Action.Starting", details)
        assertEquals(FocusMessage("NodeA 开始执行", setOf(FocusChannel.Log)), focus)
    }

    @Test
    fun `object form reads content and display array`() {
        val details = """
            {
              "task_id": 12345,
              "name": "NodeA",
              "focus": {
                "Node.Action.Starting": {
                  "content": "{name} 开始执行，任务 ID: {task_id}",
                  "display": ["log", "toast"]
                }
              }
            }
        """.trimIndent()
        val focus = FocusParser.parse("Node.Action.Starting", details)
        assertEquals(
            FocusMessage(
                "NodeA 开始执行，任务 ID: 12345",
                setOf(FocusChannel.Log, FocusChannel.Toast),
            ),
            focus,
        )
    }

    @Test
    fun `single display string is accepted`() {
        val details = """{"focus":{"M":{"content":"x","display":"notification"}}}"""
        assertEquals(setOf(FocusChannel.Notification), FocusParser.parse("M", details)?.channels)
    }

    /** modal 要求把 pipeline 卡住等用户点头，回调是单向的，做不到 */
    @Test
    fun `dialog and modal fall back to the log channel`() {
        val details = """{"focus":{"M":{"content":"x","display":["dialog","modal"]}}}"""
        assertEquals(setOf(FocusChannel.Log), FocusParser.parse("M", details)?.channels)
    }

    @Test
    fun `unknown display value falls back to the log channel`() {
        val details = """{"focus":{"M":{"content":"x","display":"hologram"}}}"""
        assertEquals(setOf(FocusChannel.Log), FocusParser.parse("M", details)?.channels)
    }

    @Test
    fun `missing display defaults to the log channel`() {
        val details = """{"focus":{"M":{"content":"x"}}}"""
        assertEquals(setOf(FocusChannel.Log), FocusParser.parse("M", details)?.channels)
    }

    /** 只配 trace 不配 content 的条目没有要展示的东西 */
    @Test
    fun `entry without content is not a message`() {
        assertNull(FocusParser.parse("M", """{"focus":{"M":{"trace":true}}}"""))
        assertNull(FocusParser.parse("M", """{"focus":{"M":{"content":"  "}}}"""))
    }

    @Test
    fun `unmatched placeholder is passed through verbatim`() {
        val details = """{"name":"NodeA","focus":{"M":"{name} 在 {nowhere} 上"}}"""
        assertEquals("NodeA 在 {nowhere} 上", FocusParser.parse("M", details)?.content)
    }

    /** 嵌套对象取不出标量，原样留着比塞个 JSON 片段强 */
    @Test
    fun `non-primitive placeholder is passed through verbatim`() {
        val details = """{"reco":{"box":[1,2]},"focus":{"M":"命中 {reco}"}}"""
        assertEquals("命中 {reco}", FocusParser.parse("M", details)?.content)
    }

    @Test
    fun `html shorthand survives untouched`() {
        val body = """<span style=\"color:orange\">跳过探索推图</span>"""
        val details = """{"focus":{"Node.Action.Starting":"$body"}}"""
        assertEquals(
            """<span style="color:orange">跳过探索推图</span>""",
            FocusParser.parse("Node.Action.Starting", details)?.content,
        )
    }

    @Test
    fun `malformed details do not throw`() {
        assertNull(FocusParser.parse("M", """{"focus": {"M": "x" """))
        assertNull(FocusParser.parse("M", """"focus""""))
    }

    @Test
    fun `focus that is not an object is ignored`() {
        assertNull(FocusParser.parse("M", """{"focus":"log"}"""))
    }

    @Test
    fun `empty message never matches`() {
        assertNull(FocusParser.parse("", """{"focus":{"":"x"}}"""))
    }
}
