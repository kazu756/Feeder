package com.nononsenseapps.feeder.db.room

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class TestMigrationFrom39To40 {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            AppDatabase::class.java,
            emptyList(),
            FrameworkSQLiteOpenHelperFactory(),
        )

    @Test
    fun migrationCreatesIndependentMappingTableWithUniqueRemoteId() {
        helper.createDatabase("commafeed-migration", 39).close()
        val db = helper.runMigrationsAndValidate("commafeed-migration", 40, true, MIGRATION_39_40)
        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='commafeed_article_mapping'").use {
            assertEquals(1, it.count)
        }
        db.query("PRAGMA index_list('commafeed_article_mapping')").use {
            assertEquals(3, it.count) // remote unique, local FK index, primary-key auto-index
        }
        db.close()
    }
}
