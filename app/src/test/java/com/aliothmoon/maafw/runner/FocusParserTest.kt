package com.aliothmoon.maafw.runner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 样例取自 MaaFramework `docs/zh_cn/3.3-ProjectInterfaceV2协议.md`「消息模板机制」
 * 与 M9A 的 pipeline 实际写法
 */
class FocusParserTest {

    private fun assertNoFocus(message: String, details: String) {
        assertTrue(FocusParser.parseAll(message, details).isEmpty())
    }

    @Test
    fun `no focus key returns nothing`() {
        assertNoFocus("Node.Action.Starting", """{"task_id":1,"name":"NodeA"}""")
    }

    /**
     * MaaFramework 给**每一条**节点回调都带 `focus` 键，没配模板时值是 null
     *
     * 实测一轮冒烟 222 条回调里 184 条如此。只判键名在不在，等于一条都没筛掉
     */
    @Test
    fun `a null focus field is filtered out without parsing`() {
        val details = """{"focus":null,"name":"SmokeStopApp","reco_id":400000015,"task_id":200000008}"""
        assertNoFocus("Node.Recognition.Starting", details)
    }

    @Test
    fun `other message in the same focus map is not picked up`() {
        val details = """{"name":"NodeA","focus":{"Node.Action.Failed":"炸了"}}"""
        assertNoFocus("Node.Action.Starting", details)
    }

    @Test
    fun `string shorthand defaults to the log channel`() {
        val details = """{"name":"NodeA","focus":{"Node.Action.Starting":"{name} 开始执行"}}"""
        val focus = FocusParser.parseAll("Node.Action.Starting", details).single()
        // 正文原样留着，占位符要等补完的最后一步才替换
        assertEquals("{name} 开始执行", focus.content)
        assertEquals(setOf(FocusChannel.Log), focus.channels)
        assertEquals(mapOf("name" to "NodeA"), focus.placeholders)
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
        val focus = FocusParser.parseAll("Node.Action.Starting", details).single()
        assertEquals("{name} 开始执行，任务 ID: {task_id}", focus.content)
        assertEquals(setOf(FocusChannel.Log, FocusChannel.Toast), focus.channels)
        assertEquals(mapOf("task_id" to "12345", "name" to "NodeA"), focus.placeholders)
    }

    @Test
    fun `single display string is accepted`() {
        val details = """{"focus":{"M":{"content":"x","display":"notification"}}}"""
        assertEquals(setOf(FocusChannel.Notification), FocusParser.parseAll("M", details).single().channels)
    }

    /** modal 要求把 pipeline 卡住等用户点头，回调是单向的，做不到 */
    @Test
    fun `dialog and modal fall back to the log channel`() {
        val details = """{"focus":{"M":{"content":"x","display":["dialog","modal"]}}}"""
        assertEquals(setOf(FocusChannel.Log), FocusParser.parseAll("M", details).single().channels)
    }

    @Test
    fun `unknown display value falls back to the log channel`() {
        val details = """{"focus":{"M":{"content":"x","display":"hologram"}}}"""
        assertEquals(setOf(FocusChannel.Log), FocusParser.parseAll("M", details).single().channels)
    }

    @Test
    fun `missing display defaults to the log channel`() {
        val details = """{"focus":{"M":{"content":"x"}}}"""
        assertEquals(setOf(FocusChannel.Log), FocusParser.parseAll("M", details).single().channels)
    }

    /** 只配 trace 不配 content 的条目没有要展示的东西 */
    @Test
    fun `entry without content is not displayable`() {
        // 只配 trace 的条目仍要产出：上报走它，展示侧靠 displayable 过滤
        val traceOnly = FocusParser.parseAll("M", """{"focus":{"M":{"trace":true}}}""").single()
        assertFalse(traceOnly.displayable)
        assertNoFocus("M", """{"focus":{"M":{"content":"  "}}}""")
    }

    @Test
    fun `unmatched placeholder is passed through verbatim`() {
        val details = """{"name":"NodeA","focus":{"M":"{name} 在 {nowhere} 上"}}"""
        val focus = FocusParser.parseAll("M", details).single()
        assertEquals(
            "NodeA 在 {nowhere} 上",
            substituteFocusPlaceholders(focus.content, focus.placeholders),
        )
    }

    /** 嵌套对象取不出标量，不收进替换表，正文里那处原样留着 */
    @Test
    fun `non-primitive placeholder is passed through verbatim`() {
        val details = """{"reco":{"box":[1,2]},"focus":{"M":"命中 {reco}"}}"""
        val focus = FocusParser.parseAll("M", details).single()
        assertEquals(emptyMap<String, String>(), focus.placeholders)
        assertEquals("命中 {reco}", substituteFocusPlaceholders(focus.content, focus.placeholders))
    }

    /** focus 自己是对象，不该混进替换表 */
    @Test
    fun `the focus dictionary itself never becomes a placeholder`() {
        val details = """{"name":"NodeA","focus":{"M":"x"}}"""
        assertEquals(mapOf("name" to "NodeA"), FocusParser.parseAll("M", details).single().placeholders)
    }

    @Test
    fun `html shorthand survives untouched`() {
        val body = """<span style=\"color:orange\">跳过探索推图</span>"""
        val details = """{"focus":{"Node.Action.Starting":"$body"}}"""
        assertEquals(
            """<span style="color:orange">跳过探索推图</span>""",
            FocusParser.parseAll("Node.Action.Starting", details).single().content,
        )
    }

    @Test
    fun `malformed details do not throw`() {
        assertNoFocus("M", """{"focus": {"M": "x" """)
        assertNoFocus("M", """"focus"""")
    }

    @Test
    fun `focus that is not an object is ignored`() {
        assertNoFocus("M", """{"focus":"log"}""")
    }

    @Test
    fun `empty message never matches`() {
        assertNoFocus("", """{"focus":{"":"x"}}""")
    }

    /** V2 协议允许把同一个事件的多段正文写成字符串数组 */
    @Test
    fun `array shorthand becomes one entry per string`() {
        val details = """{"name":"NodeA","focus":{"Node.Action.Starting":["first","second"]}}"""
        val focus = FocusParser.parseAll("Node.Action.Starting", details)
        assertEquals(listOf("first", "second"), focus.map { it.content })
        assertTrue(focus.all { setOf(FocusChannel.Log) == it.channels })
    }

    /** 资源还没跟 V2 换回调名时，focus 里仍会写着旧键 */
    @Test
    fun `pre-v2 event aliases are canonicalized`() {
        val cases = mapOf(
            "Node.Recognition.True" to "Node.Recognition.Succeeded",
            "Node.Recognition.False" to "Node.Recognition.Failed",
            "Node.Action.True" to "Node.Action.Succeeded",
            "Node.Action.False" to "Node.Action.Failed",
        )
        cases.forEach { (resourceKey, callback) ->
            val details = """{"focus":{"$resourceKey":"旧键正文"}}"""
            val focus = FocusParser.parseAll(callback, details).single()
            assertEquals(callback, focus.message)
            assertEquals("旧键正文", focus.content)
        }
    }

    /** pre-V2 用 start/succeeded/failed/toast，而不是按回调名做 focus 键 */
    @Test
    fun `legacy action fields are read`() {
        val details = """
            {
              "name": "NodeA",
              "focus": {
                "start": "旧版开始",
                "succeeded": "旧版成功",
                "failed": "旧版失败",
                "toast": ["提示", "详情"]
              }
            }
        """.trimIndent()

        val starting = FocusParser.parseAll("Node.Action.Starting", details)
        assertEquals(listOf("旧版开始", "提示: 详情"), starting.map { it.content })
        assertEquals(listOf(FocusChannel.Log, FocusChannel.Toast), starting.map { it.channels.single() })

        assertEquals(
            listOf("旧版成功"),
            FocusParser.parseAll("Node.Action.Succeeded", details).map { it.content },
        )
        assertEquals(
            listOf("旧版失败"),
            FocusParser.parseAll("Node.Action.Failed", details).map { it.content },
        )
    }

    /** 更早的 pre-V2 甚至直接把 focus 写成 Starting 的正文 */
    @Test
    fun `legacy direct string focus applies to starting`() {
        val details = """{"name":"NodeA","focus":"旧版正文"}"""
        val focus = FocusParser.parseAll("Node.Action.Starting", details).single()
        assertEquals("旧版正文", focus.content)
        assertEquals(setOf(FocusChannel.Log), focus.channels)
    }
}
