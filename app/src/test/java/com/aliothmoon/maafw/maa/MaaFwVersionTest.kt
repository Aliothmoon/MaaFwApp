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
        assertFalse(MaaFwVersion.fillsTouchContact("5.12"))
    }

    @Test
    fun `配对版本起过闸`() {
        assertTrue(MaaFwVersion.fillsTouchContact("5.12.3"))
        assertTrue(MaaFwVersion.fillsTouchContact("v5.12.3"))
        assertTrue(MaaFwVersion.fillsTouchContact(" 5.12.3 "))
        assertTrue(MaaFwVersion.fillsTouchContact("5.12.10"))
        assertTrue(MaaFwVersion.fillsTouchContact("5.13.0"))
        assertTrue(MaaFwVersion.fillsTouchContact("6.0.0"))
    }

    @Test
    fun `预发布按低于所标版本处理`() {
        assertFalse(MaaFwVersion.fillsTouchContact("v5.12.0-beta.1"))
        assertFalse(MaaFwVersion.fillsTouchContact("v5.12.3-rc.1"))
    }

    @Test
    fun `拿不到或解析不了按不支持`() {
        assertFalse(MaaFwVersion.fillsTouchContact(null))
        assertFalse(MaaFwVersion.fillsTouchContact(""))
        assertFalse(MaaFwVersion.fillsTouchContact("garbage"))
        assertFalse(MaaFwVersion.fillsTouchContact("version 5.12.3"))
    }
}
