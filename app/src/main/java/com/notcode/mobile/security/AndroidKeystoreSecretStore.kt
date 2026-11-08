package com.notcode.mobile.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AndroidKeystoreSecretStore(context: Context) : SecretStore {
    private val preferences = context.getSharedPreferences(
        "notcode_secrets",
        Context.MODE_PRIVATE,
    )

    override suspend fun put(alias: String, plaintext: ByteArray): Unit =
        withContext(Dispatchers.IO) {
            require(alias.isNotBlank())
            val copy = plaintext.copyOf()
            try {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
                cipher.updateAAD(aad(alias))
                val encrypted = cipher.doFinal(copy)
                val encoded = listOf(
                    FORMAT_VERSION,
                    Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
                    Base64.encodeToString(encrypted, Base64.NO_WRAP),
                ).joinToString(".")
                check(preferences.edit().putString(alias, encoded).commit()) {
                    "Unable to persist encrypted secret"
                }
                encrypted.fill(0)
            } catch (error: Exception) {
                throw SecretStoreException("Unable to encrypt API key", error)
            } finally {
                copy.fill(0)
            }
        }

    override suspend fun get(alias: String): ByteArray? = withContext(Dispatchers.IO) {
        val encoded = preferences.getString(alias, null) ?: return@withContext null
        try {
            val parts = encoded.split('.', limit = 3)
            if (parts.size != 3 || parts[0] != FORMAT_VERSION) {
                throw SecretStoreException("Unsupported encrypted secret format")
            }
            val iv = Base64.decode(parts[1], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[2], Base64.NO_WRAP)
            try {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    getOrCreateKey(),
                    GCMParameterSpec(128, iv),
                )
                cipher.updateAAD(aad(alias))
                cipher.doFinal(ciphertext)
            } finally {
                iv.fill(0)
                ciphertext.fill(0)
            }
        } catch (error: SecretStoreException) {
            throw error
        } catch (error: Exception) {
            preferences.edit().remove(alias).commit()
            throw SecretStoreException(
                "The encrypted API key can no longer be decrypted; enter it again",
                error,
            )
        }
    }

    override suspend fun delete(alias: String): Unit = withContext(Dispatchers.IO) {
        preferences.edit().remove(alias).commit()
    }

    override suspend fun contains(alias: String): Boolean = withContext(Dispatchers.IO) {
        preferences.contains(alias)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(MASTER_KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE,
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                MASTER_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun aad(alias: String): ByteArray =
        "notcode|$alias|apiKey|v1".toByteArray(StandardCharsets.UTF_8)

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val MASTER_KEY_ALIAS = "notcode.provider-secrets.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val FORMAT_VERSION = "1"
    }
}
