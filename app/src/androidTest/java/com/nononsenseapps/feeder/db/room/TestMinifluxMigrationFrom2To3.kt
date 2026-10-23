package com.nononsenseapps.feeder.db.room

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nononsenseapps.feeder.blob.blobInputStream
import com.nononsenseapps.feeder.blob.blobOutputStream
import com.nononsenseapps.feeder.miniflux.MinifluxCacheDatabase
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class TestMinifluxMigrationFrom2To3 {
    @get:Rule val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            MinifluxCacheDatabase::class.java,
            emptyList(),
            FrameworkSQLiteOpenHelperFactory(),
        )

    @Test fun preservedCacheOutboxAndInitializedCheckpointAreValidatedAgainstSchema3() {
        val name = "miniflux_m4_migration_${UUID.randomUUID()}"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val blobs = File(context.cacheDir, name).apply { mkdirs() }
        try {
            helper.createDatabase(name, 2).use { db ->
                insert(db, "feeds", mapOf("id" to 7L, "url" to "https://example.com/rss", "title" to "Feed", "tag" to "News"))
                insert(db, "feed_items", mapOf("id" to 8L, "feed_id" to 7L, "guid" to "miniflux:p:9000", "read_time" to 1234L))
                insert(db, "miniflux_profiles", mapOf("id" to "p", "baseUrl" to "https://example.com/", "userId" to 42L, "lastSuccessfulSync" to 123L))
                insert(db, "miniflux_profiles", mapOf("id" to "q", "baseUrl" to "https://other.example/", "userId" to 43L, "changedCheckpoint" to "2026-10-23T00:00:00Z"))
                insert(db, "miniflux_categories", mapOf("profileId" to "p", "remoteId" to 99L, "title" to "News"))
                insert(db, "miniflux_feed_identity", mapOf("profileId" to "p", "remoteFeedId" to 5000L, "localFeedId" to 7L, "remoteCategoryId" to 99L))
                insert(
                    db,
                    "miniflux_entry_identity",
                    mapOf(
                        "profileId" to "p",
                        "remoteEntryId" to 9000L,
                        "localFeedItemId" to 8L,
                        "remoteFeedId" to 5000L,
                        "remoteStatus" to "unread",
                        "contentHash" to "hash",
                        "blobReady" to 1L,
                        "readGeneration" to 7L,
                    ),
                )
                insert(
                    db,
                    "miniflux_read_outbox",
                    mapOf("profileId" to "p", "remoteEntryId" to 9000L, "desiredStatus" to "read", "generation" to 7L, "attemptCount" to 2L, "lastErrorType" to "TIMEOUT"),
                )
            }
            blobOutputStream(8, blobs).bufferedWriter().use { it.write("<p>Preserved M3 body</p>") }
            helper.runMigrationsAndValidate(name, 3, true, MinifluxCacheDatabase.MIGRATION_2_3).use { db ->
                assertEquals(3, db.version)
                for (table in listOf("feeds", "feed_items", "miniflux_categories", "miniflux_feed_identity", "miniflux_entry_identity", "miniflux_read_outbox")) {
                    db.query("SELECT COUNT(*) FROM $table").use {
                        it.moveToFirst()
                        assertEquals(1, it.getInt(0))
                    }
                }
                db.query("SELECT changedCheckpoint, bootstrapChangedAt, lastSuccessfulSync FROM miniflux_profiles WHERE id='p'").use {
                    it.moveToFirst()
                    assertTrue(it.isNull(0))
                    assertTrue(it.isNull(1))
                    assertEquals(123L, it.getLong(2))
                }
                db.query("SELECT changedCheckpoint, bootstrapChangedAt FROM miniflux_profiles WHERE id='q'").use {
                    it.moveToFirst()
                    assertEquals("2026-10-23T00:00:00Z", it.getString(0))
                    assertTrue(it.isNull(1))
                }
                db.query("SELECT localFeedItemId, remoteFeedId, contentHash, blobReady, readGeneration FROM miniflux_entry_identity WHERE profileId='p' AND remoteEntryId=9000").use {
                    it.moveToFirst()
                    assertEquals(8L, it.getLong(0))
                    assertEquals(5000L, it.getLong(1))
                    assertEquals("hash", it.getString(2))
                    assertEquals(1, it.getInt(3))
                    assertEquals(7L, it.getLong(4))
                }
                db.query("SELECT desiredStatus, generation, attemptCount, lastErrorType FROM miniflux_read_outbox WHERE profileId='p' AND remoteEntryId=9000").use {
                    it.moveToFirst()
                    assertEquals("read", it.getString(0))
                    assertEquals(7L, it.getLong(1))
                    assertEquals(2, it.getInt(2))
                    assertEquals("TIMEOUT", it.getString(3))
                }
                db.query("SELECT read_time FROM feed_items WHERE id=8").use {
                    it.moveToFirst()
                    assertEquals(1234L, it.getLong(0))
                }
                db.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
            }
            assertEquals("<p>Preserved M3 body</p>", blobInputStream(8, blobs).bufferedReader().use { it.readText() })
        } finally {
            context.deleteDatabase(name)
            blobs.deleteRecursively()
        }
    }

    private fun insert(
        db: SupportSQLiteDatabase,
        table: String,
        overrides: Map<String, Any>,
    ) {
        val values = ContentValues()
        db.query("PRAGMA table_info('$table')").use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getInt(3) == 1) {
                    val column = cursor.getString(1)
                    if (cursor.getString(2) == "TEXT") values.put(column, "") else values.put(column, 0L)
                }
            }
        }
        overrides.forEach { (key, value) ->
            when (value) {
                is String -> values.put(key, value)
                is Long -> values.put(key, value)
            }
        }
        db.insert(table, SQLiteDatabase.CONFLICT_ABORT, values)
    }
}
