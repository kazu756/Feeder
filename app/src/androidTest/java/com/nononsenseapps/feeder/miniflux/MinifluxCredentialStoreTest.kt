package com.nononsenseapps.feeder.miniflux

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class MinifluxCredentialStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val suffix = UUID.randomUUID().toString()
    private val alias = "feeder_miniflux_test_$suffix"
    private val fileName = "miniflux_test_$suffix"
    private val file = File(context.noBackupFilesDir, fileName)
    private val store = MinifluxCredentialStore(context, alias, fileName)
    private val origin = "https://miniflux.example.com/"
    private val secret = "unique-miniflux-secret-$suffix"

    @After fun cleanup() {
        runCatching { store.clearToken() }
        listOf(file, File(file.path + ".bak"), File(file.path + ".new")).forEach { it.delete() }
        KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
            deleteEntry(alias)
        }
    }

    @Test fun encryptsDecryptsReplacesAndDeletesWithoutPlaintext() {
        assertNull(store.loadToken(origin))
        store.saveToken(origin, secret)
        assertTrue(store.hasSavedToken())
        assertEquals(secret, store.loadToken(origin))
        assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains(secret))
        val previous = file.readBytes()
        store.saveToken(origin, "replacement-$suffix")
        assertEquals("replacement-$suffix", store.loadToken(origin))
        assertFalse(previous.contentEquals(file.readBytes()))
        assertFalse(File(file.path + ".bak").exists())
        assertFalse(File(file.path + ".new").exists())
        assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains(secret))
        store.clearToken()
        assertFalse(store.hasSavedToken())
        assertNull(store.loadToken(origin))
        assertFalse(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias))
    }

    @Test fun corruptedIvCiphertextAndMissingKeyAreRecoverableAndSanitized() {
        for (change in listOf("iv", "ciphertext", "truncated", "key")) {
            store.saveToken(origin, secret)
            when (change) {
                "iv" -> file.writeBytes(file.readBytes().apply { this[0] = (this[0].toInt() xor 1).toByte() })
                "ciphertext" -> file.writeBytes(file.readBytes().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() })
                "truncated" -> file.writeBytes(byteArrayOf(1, 2))
                "key" ->
                    KeyStore.getInstance("AndroidKeyStore").apply {
                        load(null)
                        deleteEntry(alias)
                    }
            }
            val failure = assertFailsWith<MinifluxFailure> { store.loadToken(origin) }
            assertEquals(MinifluxError.CREDENTIAL_UNAVAILABLE, failure.error)
            assertFalse(failure.stackTraceToString().contains(secret))
            assertNull(failure.cause)
            store.clearToken()
        }
    }

    @Test fun credentialIsBoundToOriginAndCanBeReenteredAfterFailure() {
        store.saveToken(origin, secret)
        assertFailsWith<MinifluxFailure> { store.loadToken("https://other.example.com") }
        assertEquals(secret, store.loadToken(origin))
        store.saveToken("http://miniflux.example.com:8080", "new-$suffix")
        assertFailsWith<MinifluxFailure> { store.loadToken(origin) }
        assertEquals("new-$suffix", store.loadToken("http://miniflux.example.com:8080/"))
    }

    @Test fun credentialOperationsDoNotWriteTokenToLogcat() {
        store.saveToken(origin, secret)
        store.loadToken(origin)
        file.writeBytes(byteArrayOf(1))
        assertFailsWith<MinifluxFailure> { store.loadToken(origin) }
        val process = ProcessBuilder("logcat", "-d", "-t", "1000").redirectErrorStream(true).start()
        val logs = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals(0, process.waitFor())
        assertFalse(logs.contains(secret))
    }
}
