package com.aliothmoon.maafw.maa

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** TouchArgs.contact 版本闸的判定；旧 fw 不填该字段，误读过闸会读到栈残值 */
class MaaFwVersionTest {

    @Test
    fun `低于配对版本不读 contact`() {
        assertFalse(MaaFwVersion.fillsTouchContact("5.11.9"))
        assertFalse(MaaFwVersion.fillsTouchContact("v5.12.2"))
        assertFalse(MaaFwVersion.fillsTouchContact("5.12.3"))
        assertFalse(MaaFwVersion.fillsTouchContact("5.12.10"))
        assertFalse(MaaFwVersion.fillsTouchContact("5.13.0-beta.2"))
        assertFalse(MaaFwVersion.fillsTouchContact("5.13.0-alpha.9"))
        assertFalse(MaaFwVersion.fillsTouchContact("5.12"))
    }

    @Test
    fun `配对版本起过闸`() {
        assertTrue(MaaFwVersion.fillsTouchContact("5.13.0-beta.3"))
        assertTrue(MaaFwVersion.fillsTouchContact("v5.13.0-beta.4"))
        assertTrue(MaaFwVersion.fillsTouchContact("v5.13.0-beta.5"))
        assertTrue(MaaFwVersion.fillsTouchContact(" 5.13.0-rc.1 "))
        assertTrue(MaaFwVersion.fillsTouchContact("5.13.0"))
        assertTrue(MaaFwVersion.fillsTouchContact("5.13.0+android.1"))
        assertTrue(MaaFwVersion.fillsTouchContact("5.13.1-alpha.1"))
        assertTrue(MaaFwVersion.fillsTouchContact("6.0.0"))
    }

    @Test
    fun `SemVer 预发布标识按标准顺序比较`() {
        assertFalse(MaaFwVersion.atLeast("5.13.0-beta.2", "5.13.0-beta.3"))
        assertFalse(MaaFwVersion.atLeast("5.13.0-beta.3.1", "5.13.0-beta.3.a"))
        assertTrue(MaaFwVersion.atLeast("5.13.0-beta.10", "5.13.0-beta.3"))
        assertTrue(MaaFwVersion.atLeast("5.13.0-rc.1", "5.13.0-beta.4"))
        assertTrue(MaaFwVersion.atLeast("5.13.0", "5.13.0-rc.9"))
    }

    @Test
    fun `拿不到或解析不了按不支持`() {
        assertFalse(MaaFwVersion.fillsTouchContact(null))
        assertFalse(MaaFwVersion.fillsTouchContact(""))
        assertFalse(MaaFwVersion.fillsTouchContact("garbage"))
        assertFalse(MaaFwVersion.fillsTouchContact("version 5.12.3"))
        assertFalse(MaaFwVersion.fillsTouchContact("5.13.0-beta.03"))
        assertFalse(MaaFwVersion.fillsTouchContact("5.13.0-beta."))
        assertFalse(MaaFwVersion.fillsTouchContact("05.13.0"))
    }
}
