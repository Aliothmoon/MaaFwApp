package com.aliothmoon.maafw.config

import androidx.datastore.core.CorruptionException
import com.aliothmoon.maafw.domain.UserConfiguration
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class UserConfigurationSerializerTest {

    @Test
    fun `写入的配置文件使用密文并可读回`() = runTest {
        val serializer = UserConfigurationSerializer(FakeCipher())
        val output = ByteArrayOutputStream()
        val configuration = UserConfiguration(
            initialized = true,
            activeResourceName = "secret-resource",
        )

        serializer.writeTo(configuration, output)
        val persisted = output.toByteArray()
        assertFalse(String(persisted, Charsets.ISO_8859_1).contains("secret-resource"))
        assertTrue(persisted.size > FakeCipher.MAGIC.size)

        assertEquals(
            configuration,
            serializer.readFrom(ByteArrayInputStream(persisted)),
        )
    }

    @Test
    fun `旧版明文读取后标记待重写`() = runTest {
        val serializer = UserConfigurationSerializer(FakeCipher())
        val legacy = """{"schemaVersion":1,"config":{"initialized":true}}"""

        assertEquals(
            UserConfiguration(initialized = true),
            serializer.readFrom(ByteArrayInputStream(legacy.encodeToByteArray())),
        )
        assertTrue(serializer.consumeLegacyRead())
        assertFalse(serializer.consumeLegacyRead())
    }

    @Test
    fun `密文校验失败按 DataStore 损坏处理`() = runTest {
        val serializer = UserConfigurationSerializer(FakeCipher())
        val bad = FakeCipher.MAGIC + "not-valid".encodeToByteArray()

        try {
            serializer.readFrom(ByteArrayInputStream(bad))
            fail("Expected CorruptionException")
        } catch (_: CorruptionException) {
            // Expected path.
        }
    }

    private class FakeCipher : UserConfigurationCipher {
        override fun encrypt(plaintext: ByteArray): ByteArray =
            MAGIC + plaintext.map { (it.toInt() xor KEY).toByte() }.toByteArray()

        override fun decrypt(ciphertext: ByteArray): ByteArray {
            require(ciphertext.startsWith(MAGIC))
            return ciphertext.copyOfRange(MAGIC.size, ciphertext.size)
                .map { (it.toInt() xor KEY).toByte() }
                .toByteArray()
        }

        private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
            size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

        companion object {
            val MAGIC = "FAKE".encodeToByteArray()
            private const val KEY = 0x5a
        }
    }
}
