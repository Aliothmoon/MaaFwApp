package com.aliothmoon.maafw.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OemPowerHintsTest {

    @Test
    fun `known manufacturers map to their hint`() {
        assertEquals(OemPowerHint.MIUI, OemPowerHints.hintFor("Xiaomi"))
        assertEquals(OemPowerHint.MIUI, OemPowerHints.hintFor("redmi"))
        assertEquals(OemPowerHint.HUAWEI, OemPowerHints.hintFor("HUAWEI"))
        assertEquals(OemPowerHint.HUAWEI, OemPowerHints.hintFor("honor"))
        assertEquals(OemPowerHint.OPPO, OemPowerHints.hintFor("oppo"))
        assertEquals(OemPowerHint.OPPO, OemPowerHints.hintFor("realme"))
        assertEquals(OemPowerHint.OPPO, OemPowerHints.hintFor("OnePlus"))
        assertEquals(OemPowerHint.VIVO, OemPowerHints.hintFor("vivo"))
        assertEquals(OemPowerHint.VIVO, OemPowerHints.hintFor("iQOO"))
        assertEquals(OemPowerHint.SAMSUNG, OemPowerHints.hintFor("samsung"))
        assertEquals(OemPowerHint.MEIZU, OemPowerHints.hintFor("Meizu"))
    }

    @Test
    fun `unknown manufacturers get no hint`() {
        assertNull(OemPowerHints.hintFor("Google"))
        assertNull(OemPowerHints.hintFor(""))
    }
}
