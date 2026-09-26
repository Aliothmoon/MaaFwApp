package com.aliothmoon.maafw.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateVersionTest {

    @Test
    fun `missing minor and patch default to zero`() {
        assertEquals(UpdateVersion.parse("v2"), UpdateVersion.parse("2.0.0"))
        assertEquals(UpdateVersion.parse("1.2"), UpdateVersion.parse("1.2.0"))
    }

    @Test
    fun `stable versions outrank prereleases and prereleases follow semver order`() {
        val stable = UpdateVersion.parse("1.0.0")!!
        val beta2 = UpdateVersion.parse("1.0.0-beta.2")!!
        val beta10 = UpdateVersion.parse("1.0.0-beta.10")!!
        val alpha = UpdateVersion.parse("1.0.0-alpha")!!

        assertTrue(stable > beta2)
        assertTrue(beta10 > beta2)
        assertTrue(beta2 > alpha)
    }

    @Test
    fun `build metadata does not affect ordering`() {
        assertEquals(UpdateVersion.parse("1.0.0+1"), UpdateVersion.parse("1.0.0+2"))
    }

    @Test
    fun `release candidates rank above betas by identifier order`() {
        assertTrue(UpdateVersion.parse("1.0.0-rc.1")!! > UpdateVersion.parse("1.0.0-beta.10")!!)
        assertTrue(UpdateVersion.parse("1.0.0")!! > UpdateVersion.parse("1.0.0-rc.1")!!)
    }

    @Test
    fun `stable channel excludes every prerelease and beta channel accepts any`() {
        listOf("1.0.0-alpha.1", "1.0.0-beta.1", "1.0.0-rc.1", "1.0.0-preview").forEach { raw ->
            val version = UpdateVersion.parse(raw)!!
            assertFalse(raw, version.allowedFor(UpdateChannel.STABLE))
            assertTrue(raw, version.allowedFor(UpdateChannel.BETA))
        }
        assertTrue(UpdateVersion.parse("1.0.0")!!.allowedFor(UpdateChannel.STABLE))
        assertTrue(UpdateVersion.parse("1.0.0")!!.allowedFor(UpdateChannel.BETA))
    }

    @Test
    fun `invalid versions cannot be parsed`() {
        assertNull(UpdateVersion.parse("latest"))
        assertNull(UpdateVersion.parse("unknown"))
        // SemVer 不允许数字标识带前导零
        assertNull(UpdateVersion.parse("1.02.0"))
    }
}
