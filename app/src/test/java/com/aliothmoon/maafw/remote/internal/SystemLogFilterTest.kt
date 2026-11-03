package com.aliothmoon.maafw.remote.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 只带提到死者的行；别的应用被杀只计数，不把包名带出设备 */
class SystemLogFilterTest {

    private val prefix = "com.aliothmoon.maafw:"

    private fun filter(
        vararg lines: String,
        until: Long = 2_000_000_000_000L,
        max: Int = 300,
    ) = SystemLogFilter.filter(
        lines.asSequence(),
        pid = 12345,
        processPrefix = prefix,
        untilMs = until,
        maxLines = max,
    )

    @Test
    fun `keeps lines naming the process or its pid`() {
        val result = filter(
            "1791325260.100  1000  1100 I lowmemorykiller: Kill 'com.aliothmoon.maafw:shizuku_service' (12345), uid 2000",
            "1791325260.200 12345 12360 W MaaCore: last words",
            "1791325260.300  1500  1500 I ActivityManager: Start proc 23456:com.example.other",
        )

        assertEquals(2, result.kept.size)
        assertEquals(0, result.otherKills)
        assertEquals(3, result.scanned)
    }

    @Test
    fun `other kills are counted but not kept`() {
        val result = filter(
            "1791325260.100  1000  1100 I lowmemorykiller: Kill 'com.example.game' (23456), uid 10200",
            "1791325260.200  1500  1500 I am_kill : [0,23457,10201,com.example.chat,900,empty]",
        )

        assertTrue(result.kept.isEmpty())
        assertEquals(2, result.otherKills)
    }

    @Test
    fun `pid must be a whole number`() {
        val result = filter(
            "1791325260.100  1000  1100 I Foo: pid 123456 gone",
            "1791325260.200  1000  1100 I Foo: took 12345ms",
        )

        assertTrue(result.kept.isEmpty())
    }

    @Test
    fun `stops after the window`() {
        val result = filter(
            "1791325260.100 12345 12360 I MaaCore: before",
            "1791325290.100 12345 12360 I MaaCore: after",
            until = 1791325270_000L,
        )

        assertEquals(listOf("1791325260.100 12345 12360 I MaaCore: before"), result.kept)
    }

    @Test
    fun `keeps the latest lines when over the cap`() {
        val result = filter(
            "1791325260.100 12345 12360 I MaaCore: a",
            "1791325260.200 12345 12360 I MaaCore: b",
            "1791325260.300 12345 12360 I MaaCore: c",
            max = 2,
        )

        assertEquals(listOf("b", "c"), result.kept.map { it.substringAfterLast(' ') })
    }

    @Test
    fun `epoch prefix parses and separators are ignored`() {
        assertEquals(1791325260_481L, SystemLogFilter.epochMs("  1791325260.481  1000  1100 I Tag: msg"))
        assertNull(SystemLogFilter.epochMs("--------- beginning of main"))
    }
}
