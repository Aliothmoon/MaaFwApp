package com.aliothmoon.maafw.config

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import timber.log.Timber
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * PI password 字段的落盘加密：AndroidKeyStore 里一把不可导出的 AES-GCM 密钥
 *
 * 密钥不随备份走：云备份恢复到新机、清数据后恢复出来的密文都解不开，[open] 返回 null，
 * 调用方当作没填、让用户重输一次。协议不要求密文跨设备可解
 */
object OptionSecretCipher {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "maafw.option-secrets"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128
    private const val IV_BYTES = 12

    /** 密文格式带版本前缀，换算法时旧密文还认得出来 */
    private const val FORMAT_PREFIX = "v1:"

    fun seal(plain: String): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        check(cipher.iv.size == IV_BYTES) { "unexpected GCM iv length ${cipher.iv.size}" }
        FORMAT_PREFIX + Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))
    }.onFailure { Timber.e(it, "Seal option secret failed") }.getOrNull()

    fun open(sealed: String): String? {
        if (!sealed.startsWith(FORMAT_PREFIX)) {
            Timber.w("Unknown option secret format, dropped")
            return null
        }
        return runCatching {
            val body = Base64.getDecoder().decode(sealed.removePrefix(FORMAT_PREFIX))
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, body, 0, IV_BYTES))
            String(cipher.doFinal(body, IV_BYTES, body.size - IV_BYTES), Charsets.UTF_8)
        }.onFailure { Timber.w(it, "Open option secret failed, treating it as unset") }.getOrNull()
    }

    @Synchronized
    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }
}
