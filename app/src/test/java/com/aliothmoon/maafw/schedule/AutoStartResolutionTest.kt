package com.aliothmoon.maafw.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoStartResolutionTest {

    @Test
    fun `oem page resolvable - prefer first resolvable in candidate order`() {
        val target = AutoStartResolution.select(
            resolvableOemIds = listOf("vivo", "huawei"),
            knownRestrictiveManufacturer = false,
        )
        assertEquals(AutoStartTarget.Oem("vivo"), target)
    }

    @Test
    fun `known manufacturer but nothing resolvable - fallback to app details`() {
        val target = AutoStartResolution.select(
            resolvableOemIds = emptyList(),
            knownRestrictiveManufacturer = true,
        )
        assertEquals(AutoStartTarget.AppDetails, target)
    }

    @Test
    fun `unknown manufacturer and nothing resolvable - no guidance`() {
        assertNull(
            AutoStartResolution.select(
                resolvableOemIds = emptyList(),
                knownRestrictiveManufacturer = false,
            )
        )
    }

    @Test
    fun `unknown manufacturer with resolvable oem page - still guide`() {
        val target = AutoStartResolution.select(
            resolvableOemIds = listOf("xiaomi"),
            knownRestrictiveManufacturer = false,
        )
        assertEquals(AutoStartTarget.Oem("xiaomi"), target)
    }

    @Test
    fun `oem page falls back to app details when it cannot be opened`() {
        assertEquals(
            listOf(AutoStartTarget.Oem("vivo"), AutoStartTarget.AppDetails),
            AutoStartResolution.launchOrder(AutoStartTarget.Oem("vivo")),
        )
        assertEquals(
            listOf(AutoStartTarget.AppDetails),
            AutoStartResolution.launchOrder(AutoStartTarget.AppDetails),
        )
    }
}
