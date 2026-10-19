package com.nononsenseapps.feeder.commafeed

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.Credentials
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class CommaFeedCredentialStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val alias = "feeder_commafeed_test_credentials"
    private val fileName = "commafeed_test_credentials"
    private val store = CommaFeedCredentialStore(context, alias, fileName)
    private val file = File(context.noBackupFilesDir, fileName)
    private val account = "https://example.com/\nuser"

    @After
    fun cleanup() {
        store.clearPassword()
    }

    @Test
    fun roundTripUsesNonExportableKeyAndNoBackupCiphertext() {
        store.savePassword("unique-test-secret", account)
        assertTrue(store.hasSavedPassword())
        assertEquals("unique-test-secret", store.loadPassword(account))
        assertFalse(file.readText().contains("unique-test-secret"))
        assertFalse(file.readText().contains("Authorization"))
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertNull(keyStore.getKey(alias, null).encoded)
        assertEquals(context.noBackupFilesDir.canonicalFile, file.parentFile?.canonicalFile)
    }

    @Test
    fun credentialOperationsDoNotWritePasswordOrBasicValueToLogcat() {
        val secret = "commafeed-log-test-${System.nanoTime()}"
        store.savePassword(secret, account)
        assertEquals(secret, store.loadPassword(account))
        file.writeBytes(byteArrayOf(1))
        assertFailsWith<CommaFeedCredentialUnavailable> { store.loadPassword(account) }
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("logcat -d -t 2000")
        val log = ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
        assertFalse(log.contains(secret))
        assertFalse(log.contains(Credentials.basic("user", secret)))
    }

    @Test
    fun replacingAndClearingRemoveOldCredential() {
        store.savePassword("old-secret", account)
        val first = file.readBytes()
        store.savePassword("new-secret", account)
        assertEquals("new-secret", store.loadPassword(account))
        assertFalse(first.contentEquals(file.readBytes()))
        assertFalse(file.readText().contains("old-secret"))
        store.clearPassword()
        assertFalse(store.hasSavedPassword())
        assertNull(store.loadPassword(account))
        assertFalse(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias))
    }

    @Test
    fun tamperedCiphertextRequiresReentry() {
        assertTamperRejected(15)
    }

    @Test
    fun tamperedIvRequiresReentry() {
        assertTamperRejected(0)
    }

    @Test
    fun missingKeyAndRestoredBlobRequireReentry() {
        store.savePassword("test-secret", account)
        KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
            deleteEntry(alias)
        }
        assertUnavailable()
    }

    @Test
    fun changedOriginOrUsernameCannotReceiveSavedPassword() {
        store.savePassword("test-secret", account)
        assertFailsWith<CommaFeedCredentialUnavailable> { store.loadPassword("https://other.example/\nuser") }
        assertFalse(store.hasSavedPassword())
    }

    private fun assertTamperRejected(index: Int) {
        store.savePassword("test-secret", account)
        val bytes = file.readBytes()
        bytes[index] = (bytes[index].toInt() xor 1).toByte()
        file.writeBytes(bytes)
        assertUnavailable()
    }

    private fun assertUnavailable() {
        val error = assertFailsWith<CommaFeedCredentialUnavailable> { store.loadPassword(account) }
        assertNull(error.cause)
        assertFalse(error.toString().contains("test-secret"))
        assertFalse(error.toString().contains("Authorization"))
        assertFalse(store.hasSavedPassword())
        assertNull(store.loadPassword(account))
    }
}
