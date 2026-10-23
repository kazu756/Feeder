package com.nononsenseapps.feeder.miniflux

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Ciphertext is device-local, excluded from backup, and bound to the normalized server origin. */
class MinifluxCredentialStore(
    context: Context,
    private val alias: String = "feeder_miniflux_credentials_v1",
    fileName: String = "miniflux_credentials_v1",
) {
    private val file = AtomicFile(File(context.noBackupFilesDir, fileName))

    @Synchronized
    fun hasSavedToken(): Boolean = file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()

    @Synchronized
    fun saveToken(
        baseUrl: String,
        token: String,
    ) {
        val origin = normalizedMinifluxUrl(baseUrl).toString()
        validateMinifluxToken(token)
        try {
            val store = keyStore()
            val key =
                store.getKey(alias, null) as? SecretKey ?: KeyGenerator
                    .getInstance("AES", "AndroidKeyStore")
                    .apply {
                        init(
                            KeyGenParameterSpec
                                .Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                                .setKeySize(256)
                                .build(),
                        )
                    }.generateKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(origin.toByteArray(Charsets.UTF_8))
            val plain = token.toByteArray(Charsets.UTF_8)
            val encrypted =
                try {
                    cipher.doFinal(plain)
                } finally {
                    plain.fill(0)
                }
            val stream = file.startWrite()
            try {
                stream.write(cipher.iv + encrypted)
                file.finishWrite(stream)
            } catch (e: Exception) {
                file.failWrite(stream)
                throw e
            }
        } catch (_: Exception) {
            throw MinifluxFailure(MinifluxError.CREDENTIAL_UNAVAILABLE)
        }
    }

    @Synchronized
    fun loadToken(baseUrl: String): String? {
        val origin = normalizedMinifluxUrl(baseUrl).toString()
        if (!hasSavedToken()) return null
        try {
            val bytes = file.readFully()
            require(bytes.size in 29..8192)
            val key = keyStore().getKey(alias, null) as? SecretKey ?: throw IllegalStateException()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            cipher.updateAAD(origin.toByteArray(Charsets.UTF_8))
            val plain = cipher.doFinal(bytes.copyOfRange(12, bytes.size))
            return try {
                plain.toString(Charsets.UTF_8).also(::validateMinifluxToken)
            } finally {
                plain.fill(0)
            }
        } catch (_: Exception) {
            // No original cause/message is allowed across the credential boundary.
            throw MinifluxFailure(MinifluxError.CREDENTIAL_UNAVAILABLE)
        }
    }

    @Synchronized
    fun clearToken() {
        try {
            file.delete()
            if (hasSavedToken()) throw IllegalStateException()
            keyStore().deleteEntry(alias)
        } catch (_: Exception) {
            throw MinifluxFailure(MinifluxError.CREDENTIAL_UNAVAILABLE)
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
}
