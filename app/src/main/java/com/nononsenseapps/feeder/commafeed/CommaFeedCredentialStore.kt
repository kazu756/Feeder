package com.nononsenseapps.feeder.commafeed

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

class CommaFeedCredentialUnavailable : Exception("Saved credential unavailable; enter password again")

/** Only IV and authenticated ciphertext are persisted. No secrets in exception causes or logs. */
class CommaFeedCredentialStore(
    context: Context,
    private val alias: String = "feeder_commafeed_credentials_v1",
    fileName: String = "commafeed_credentials_v1",
) {
    private val file = AtomicFile(File(context.noBackupFilesDir, fileName))

    @Synchronized
    fun hasSavedPassword(): Boolean = file.baseFile.exists()

    @Synchronized
    fun savePassword(
        password: String,
        account: String,
    ) {
        try {
            require(password.isNotEmpty())
            val keyStore = keyStore()
            val key =
                keyStore.getKey(alias, null) as? SecretKey ?: KeyGenerator
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
            // Bind the password to its origin and username, including after settings/backup changes.
            cipher.updateAAD(account.toByteArray(Charsets.UTF_8))
            val plain = password.toByteArray(Charsets.UTF_8)
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
            clearPassword()
            throw CommaFeedCredentialUnavailable()
        }
    }

    @Synchronized
    fun loadPassword(account: String): String? {
        if (!hasSavedPassword()) return null
        try {
            val bytes = file.readFully()
            require(bytes.size > 28)
            val key = keyStore().getKey(alias, null) as? SecretKey ?: throw CommaFeedCredentialUnavailable()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            cipher.updateAAD(account.toByteArray(Charsets.UTF_8))
            val plain = cipher.doFinal(bytes.copyOfRange(12, bytes.size))
            return try {
                plain.toString(Charsets.UTF_8)
            } finally {
                plain.fill(0)
            }
        } catch (_: Exception) {
            clearPassword()
            throw CommaFeedCredentialUnavailable()
        }
    }

    @Synchronized
    fun clearPassword() {
        file.delete()
        // The alias is exclusive to this credential; never delete another feature's keys.
        try {
            keyStore().deleteEntry(alias)
        } catch (_: Exception) {
            // The blob is already gone; an unavailable Keystore must not crash the UI.
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
}
