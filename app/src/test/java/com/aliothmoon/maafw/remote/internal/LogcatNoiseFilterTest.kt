package com.aliothmoon.maafw.remote.internal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogcatNoiseFilterTest {

    private fun avc(time: String, serial: Int, path: String = "/proc/fas/render") =
        "09-27 04:08:$time 28420 28420 W binder:28420_5: type=1400 audit(0.0:$serial): " +
                "avc:  denied  { ioctl } for  path=\"$path\" dev=\"proc\" ino=4026532244 " +
                "tclass=file permissive=0 app=com.aliothmoon.maafw"

    @Test
    fun repeatedDenialKeepsOnlyFirst() {
        val filter = LogcatNoiseFilter()

        assertTrue(filter.accept(avc("27.333", 32120296)))
        assertFalse(filter.accept(avc("27.349", 32120297)))
        assertFalse(filter.accept(avc("28.333", 32120418)))
    }

    @Test
    fun differentDenialsEachPassOnce() {
        val filter = LogcatNoiseFilter()

        assertTrue(filter.accept(avc("27.333", 1, path = "/proc/fas/render")))
        assertTrue(filter.accept(avc("27.334", 2, path = "/data/local/tmp/maafw")))
        assertFalse(filter.accept(avc("27.335", 3, path = "/data/local/tmp/maafw")))
    }

    @Test
    fun ordinaryLinesAlwaysPass() {
        val filter = LogcatNoiseFilter()
        val line = "10-03 03:36:19.123  6789  6789 I MaaFw   : RemoteService: setup ok"

        assertTrue(filter.accept(line))
        assertTrue(filter.accept(line))
    }

    @Test
    fun rememberedKeysAreBounded() {
        val filter = LogcatNoiseFilter(maxKeys = 2)

        assertTrue(filter.accept(avc("00.001", 1, path = "/a")))
        assertTrue(filter.accept(avc("00.002", 2, path = "/b")))
        assertTrue(filter.accept(avc("00.003", 3, path = "/c")))
        // /a 已被挤出，重新放行一次
        assertTrue(filter.accept(avc("00.004", 4, path = "/a")))
        assertFalse(filter.accept(avc("00.005", 5, path = "/c")))
    }
}
