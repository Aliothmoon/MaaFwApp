package com.aliothmoon.maafw.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RomInfoTest {

    private fun props(vararg pairs: Pair<String, String>): (String) -> String {
        val map = mapOf(*pairs)
        return { key -> map[key].orEmpty() }
    }

    @Test
    fun xiaomi_isEitherHyperOsOrMiui() {
        assertTrue(RomInfo.isXiaomi(props("ro.mi.os.version.name" to "OS3.0")))
        assertTrue(RomInfo.isXiaomi(props("ro.miui.ui.version.name" to "V125")))
        assertFalse(RomInfo.isXiaomi(props("ro.vivo.os.name" to "Funtouch")))
    }
}
