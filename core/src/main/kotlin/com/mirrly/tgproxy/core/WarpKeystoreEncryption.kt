/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * GNU GPL v3+ <https://www.gnu.org/licenses/>
 */

package com.mirrly.tgproxy.core

import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Криптографический модуль защиты секретных данных и конфигураций профилей WARP / AmneziaWG.
 * Использует алгоритм AES-256-GCM с ключом, защищенным аппаратным Android KeyStore.
 * Обеспечивает выполнение политики Zero Secret Leak и шифрование приватных ключей при сохранении на диск.
 */
object WarpKeystoreEncryption {

    private const val TAG = "WarpKeystoreEncryption"
    private const val ANDROID_KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "mirrly_warp_storage_key_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val GCM_IV_LENGTH_BYTES = 12
    private const val ENCRYPTED_PREFIX = "enc:v1:"

    private val secureRandom = SecureRandom()

    // Резервный ключ для JVM / тестов, если провайдер AndroidKeyStore недоступен
    @Volatile
    private var jvmFallbackKey: SecretKey? = null

    /**
     * Получает или генерирует мастер-ключ AES-256 в AndroidKeyStore.
     */
    @Synchronized
    private fun getOrCreateSecretKey(): SecretKey {
        val hasAndroidKeyStore = java.security.Security.getProvider(ANDROID_KEYSTORE_PROVIDER) != null
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE_PROVIDER)
            keyStore.load(null)

            if (keyStore.containsAlias(KEY_ALIAS)) {
                val entry = keyStore.getEntry(KEY_ALIAS, null)
                if (entry is KeyStore.SecretKeyEntry) {
                    return entry.secretKey
                }
            }

            // Создаем новый ключ в Android KeyStore через рефлексию/KeyGenParameterSpec
            // чтобы корректно компилироваться в чистом Kotlin core-модуле
            val keyGenClass = Class.forName("android.security.keystore.KeyGenParameterSpec\$Builder")
            val keyPropertiesClass = Class.forName("android.security.keystore.KeyProperties")

            val purposeEncrypt = keyPropertiesClass.getField("PURPOSE_ENCRYPT").getInt(null)
            val purposeDecrypt = keyPropertiesClass.getField("PURPOSE_DECRYPT").getInt(null)
            val blockModeGcm = keyPropertiesClass.getField("BLOCK_MODE_GCM").get(null) as String
            val paddingNone = keyPropertiesClass.getField("ENCRYPTION_PADDING_NONE").get(null) as String

            val builderCtor = keyGenClass.getConstructor(String::class.java, Int::class.javaPrimitiveType)
            val builder = builderCtor.newInstance(KEY_ALIAS, purposeEncrypt or purposeDecrypt)

            keyGenClass.getMethod("setBlockModes", Array<String>::class.java).invoke(builder, arrayOf(blockModeGcm))
            keyGenClass.getMethod("setEncryptionPaddings", Array<String>::class.java).invoke(builder, arrayOf(paddingNone))
            keyGenClass.getMethod("setKeySize", Int::class.javaPrimitiveType).invoke(builder, 256)
            keyGenClass.getMethod("setRandomizedEncryptionRequired", Boolean::class.javaPrimitiveType).invoke(builder, true)

            val spec = keyGenClass.getMethod("build").invoke(builder)

            val keyGenerator = KeyGenerator.getInstance("AES", ANDROID_KEYSTORE_PROVIDER)
            keyGenerator.javaClass.getMethod("init", java.security.spec.AlgorithmParameterSpec::class.java).invoke(keyGenerator, spec)
            return keyGenerator.generateKey()
        } catch (e: Exception) {
            if (hasAndroidKeyStore) {
                AppLogger.e(TAG, "Критическая ошибка инициализации AndroidKeyStore: ${e.message}")
                throw IllegalStateException("Failed to initialize AndroidKeyStore key", e)
            }
            // AndroidKeyStore недоступен (например, в среде JVM юнит-тестов)
            return getOrCreateJvmFallbackKey()
        }
    }

    @Synchronized
    private fun getOrCreateJvmFallbackKey(): SecretKey {
        var key = jvmFallbackKey
        if (key == null) {
            val rawKey = ByteArray(32)
            // Псевдослучайный детерминированный ключ для тестового окружения
            val seed = "mirrly_warp_internal_fallback_seed_2026".toByteArray(Charsets.UTF_8)
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val hash = md.digest(seed)
            System.arraycopy(hash, 0, rawKey, 0, 32)
            key = SecretKeySpec(rawKey, "AES")
            jvmFallbackKey = key
        }
        return key
    }

    /**
     * Шифрует произвольную строку (конфигурацию или приватный ключ).
     * Результат имеет вид: `enc:v1:<Base64(IV + Ciphertext)>`.
     */
    fun encrypt(plainText: String): String {
        if (plainText.isBlank()) return plainText
        return try {
            val secretKey = getOrCreateSecretKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val iv: ByteArray
            val cipherBytes: ByteArray

            if (secretKey is SecretKeySpec) {
                val customIv = ByteArray(GCM_IV_LENGTH_BYTES)
                secureRandom.nextBytes(customIv)
                val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, customIv)
                cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec)
                iv = customIv
                cipherBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            } else {
                cipher.init(Cipher.ENCRYPT_MODE, secretKey)
                iv = cipher.iv ?: throw IllegalStateException("Cipher did not generate IV")
                cipherBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            }

            val combined = ByteArray(iv.size + cipherBytes.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(cipherBytes, 0, combined, iv.size, cipherBytes.size)

            val base64Payload = Base64.getEncoder().encodeToString(combined)
            "$ENCRYPTED_PREFIX$base64Payload"
        } catch (e: Exception) {
            AppLogger.e(TAG, "Ошибка AES-GCM шифрования: ${e.message}")
            if (java.security.Security.getProvider(ANDROID_KEYSTORE_PROVIDER) != null) {
                throw IllegalStateException("AES-GCM encryption failed in Android environment", e)
            }
            plainText
        }
    }

    /**
     * Расшифровывает строку, если она была зашифрована (`enc:v1:...`).
     * Если строка не содержит префикса, она возвращается как есть (обратная совместимость).
     */
    fun decrypt(encryptedOrPlain: String): String {
        if (!encryptedOrPlain.startsWith(ENCRYPTED_PREFIX)) {
            return encryptedOrPlain
        }
        return try {
            val base64Payload = encryptedOrPlain.removePrefix(ENCRYPTED_PREFIX)
            val combined = Base64.getDecoder().decode(base64Payload)
            if (combined.size <= GCM_IV_LENGTH_BYTES) {
                return encryptedOrPlain
            }

            val iv = ByteArray(GCM_IV_LENGTH_BYTES)
            val cipherBytes = ByteArray(combined.size - GCM_IV_LENGTH_BYTES)
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH_BYTES)
            System.arraycopy(combined, GCM_IV_LENGTH_BYTES, cipherBytes, 0, cipherBytes.size)

            val secretKey = getOrCreateSecretKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)

            val decryptedBytes = cipher.doFinal(cipherBytes)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Ошибка AES-GCM расшифрования: ${e.message}")
            encryptedOrPlain
        }
    }
}
