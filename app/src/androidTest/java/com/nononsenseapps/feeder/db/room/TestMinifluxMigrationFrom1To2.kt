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
import kotlin.test.assertFails

@RunWith(AndroidJUnit4::class)
class TestMinifluxMigrationFrom1To2 {
    @get:Rule val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            MinifluxCacheDatabase::class.java,
            emptyList(),
            FrameworkSQLiteOpenHelperFactory(),
        )

    @Test fun migrationPreservesM2CacheAndValidatesOutboxConstraints() {
        val name = "miniflux_migration_${UUID.randomUUID()}"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val blobs = File(context.cacheDir, name).apply { mkdirs() }
        try {
            helper.createDatabase(name, 1).use { db ->
                insert(db, "feeds", mapOf("id" to 7L, "url" to "https://example.com/rss", "title" to "Feed", "tag" to "News"))
                insert(db, "feed_items", mapOf("id" to 8L, "feed_id" to 7L, "guid" to "miniflux:p:9000", "read_time" to 1234L))
                insert(db, "miniflux_profiles", mapOf("id" to "p", "baseUrl" to "https://example.com/", "userId" to 42L))
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
                        "remoteStatus" to "read",
                        "contentHash" to "hash",
                        "blobReady" to 1L,
                    ),
                )
            }
            blobOutputStream(8, blobs).bufferedWriter().use { it.write("<p>Preserved body</p>") }
            helper.runMigrationsAndValidate(name, 2, true, MinifluxCacheDatabase.MIGRATION_1_2).use { db ->
                db.execSQL("PRAGMA foreign_keys=ON")
                for (table in listOf("feeds", "feed_items", "miniflux_profiles", "miniflux_categories", "miniflux_feed_identity", "miniflux_entry_identity")) {
                    db.query("SELECT COUNT(*) FROM $table").use {
                        it.moveToFirst()
                        assertEquals(1, it.getInt(0))
                    }
                }
                db.query("SELECT localFeedItemId, remoteFeedId, remoteStatus, contentHash, blobReady, readGeneration FROM miniflux_entry_identity WHERE profileId='p' AND remoteEntryId=9000").use {
                    it.moveToFirst()
                    assertEquals(8L, it.getLong(0))
                    assertEquals(5000L, it.getLong(1))
                    assertEquals("read", it.getString(2))
                    assertEquals("hash", it.getString(3))
                    assertEquals(1, it.getInt(4))
                    assertEquals(0L, it.getLong(5))
                }
                db.query("SELECT read_time FROM feed_items WHERE id=8").use {
                    it.moveToFirst()
                    assertEquals(1234L, it.getLong(0))
                }
                db.query("SELECT COUNT(*) FROM miniflux_read_outbox").use {
                    it.moveToFirst()
                    assertEquals(0, it.getInt(0))
                }
                val row = mapOf("profileId" to "p", "remoteEntryId" to 9000L, "desiredStatus" to "unread", "generation" to 1L)
                insert(db, "miniflux_read_outbox", row)
                assertFails { insert(db, "miniflux_read_outbox", row) }
                assertFails { insert(db, "miniflux_read_outbox", row + ("remoteEntryId" to 9999L)) }
                db.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
                db.query("SELECT name FROM sqlite_master WHERE type='index' AND name='index_miniflux_read_outbox_profileId_updatedAt'").use { assertEquals(1, it.count) }
            }
            assertEquals("<p>Preserved body</p>", blobInputStream(8, blobs).bufferedReader().use { it.readText() })
        } finally {
            context.deleteDatabase(name)
            blobs.deleteRecursively()
        }
    }

    /** Supply required v1 fields explicitly, without depending on current entity defaults. */
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
