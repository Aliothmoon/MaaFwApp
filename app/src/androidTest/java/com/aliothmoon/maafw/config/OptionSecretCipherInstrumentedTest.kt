package com.aliothmoon.maafw.config

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** AndroidKeyStore 只有设备上有，JVM 单测只能桩掉它；这里验真实的加解密往返 */
@RunWith(AndroidJUnit4::class)
class OptionSecretCipherInstrumentedTest {

    @Test
    fun sealThenOpenRoundTrips() {
        val sealed = OptionSecretCipher.seal("p@ss 密码 123")

        assertNotNull(sealed)
        assertFalse("p@ss" in sealed!!)
        assertEquals("p@ss 密码 123", OptionSecretCipher.open(sealed))
    }

    /** 每次随机 IV：同一明文两次加密不能得出同一串 */
    @Test
    fun sameInputSealsDifferently() {
        assertNotEquals(OptionSecretCipher.seal("same"), OptionSecretCipher.seal("same"))
    }

    @Test
    fun tamperedOrForeignInputOpensToNull() {
        val sealed = OptionSecretCipher.seal("value")!!
        val tampered = sealed.dropLast(2) + if (sealed.endsWith("AA")) "BB" else "AA"

        assertNull(OptionSecretCipher.open(tampered))
        assertNull(OptionSecretCipher.open("plain text"))
    }
}
