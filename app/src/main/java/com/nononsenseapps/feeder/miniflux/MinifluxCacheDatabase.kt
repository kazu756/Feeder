package com.nononsenseapps.feeder.miniflux

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.TypeConverters
import androidx.room.Update
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.nononsenseapps.feeder.db.room.Converters
import com.nononsenseapps.feeder.db.room.Feed
import com.nononsenseapps.feeder.db.room.FeedItem
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "miniflux_profiles", indices = [Index(value = ["baseUrl", "userId"], unique = true)])
data class MinifluxProfile(
    @PrimaryKey val id: String,
    val baseUrl: String,
    val userId: Long,
    val username: String,
    val createdAt: Long,
    val lastSuccessfulSync: Long? = null,
    val changedCheckpoint: String? = null,
    @ColumnInfo(defaultValue = "NULL") val bootstrapChangedAt: String? = null,
)

@Entity(
    tableName = "miniflux_categories",
    primaryKeys = ["profileId", "remoteId"],
    foreignKeys = [ForeignKey(entity = MinifluxProfile::class, parentColumns = ["id"], childColumns = ["profileId"], onDelete = ForeignKey.CASCADE)],
)
data class MinifluxCachedCategory(
    val profileId: String,
    val remoteId: Long,
    val title: String,
)

@Entity(
    tableName = "miniflux_feed_identity",
    primaryKeys = ["profileId", "remoteFeedId"],
    indices = [Index(value = ["localFeedId"], unique = true), Index(value = ["profileId", "remoteCategoryId"])],
    foreignKeys = [
        ForeignKey(entity = MinifluxProfile::class, parentColumns = ["id"], childColumns = ["profileId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Feed::class, parentColumns = ["id"], childColumns = ["localFeedId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = MinifluxCachedCategory::class, parentColumns = ["profileId", "remoteId"], childColumns = ["profileId", "remoteCategoryId"]),
    ],
)
data class MinifluxFeedIdentity(
    val profileId: String,
    val remoteFeedId: Long,
    val localFeedId: Long,
    val remoteCategoryId: Long,
    val siteUrl: String,
)

@Entity(
    tableName = "miniflux_entry_identity",
    primaryKeys = ["profileId", "remoteEntryId"],
    indices = [Index(value = ["localFeedItemId"], unique = true), Index(value = ["profileId", "remoteFeedId"])],
    foreignKeys = [
        ForeignKey(entity = MinifluxFeedIdentity::class, parentColumns = ["profileId", "remoteFeedId"], childColumns = ["profileId", "remoteFeedId"]),
        ForeignKey(entity = FeedItem::class, parentColumns = ["id"], childColumns = ["localFeedItemId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class MinifluxEntryIdentity(
    val profileId: String,
    val remoteEntryId: Long,
    val localFeedItemId: Long,
    val remoteFeedId: Long,
    val changedAt: String?,
    val remoteStatus: String,
    val remoteStarred: Boolean,
    val remoteCreatedAt: String?,
    val contentHash: String,
    val blobReady: Boolean = false,
    @ColumnInfo(defaultValue = "0") val readGeneration: Long = 0,
)

@Entity(
    tableName = "miniflux_read_outbox",
    primaryKeys = ["profileId", "remoteEntryId"],
    foreignKeys = [ForeignKey(entity = MinifluxEntryIdentity::class, parentColumns = ["profileId", "remoteEntryId"], childColumns = ["profileId", "remoteEntryId"])],
    indices = [Index(value = ["profileId", "updatedAt"])],
)
data class MinifluxReadOutbox(
    val profileId: String,
    val remoteEntryId: Long,
    val desiredStatus: String,
    val generation: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val lastAttemptAt: Long? = null,
    val attemptCount: Int = 0,
    val lastErrorType: String? = null,
    val lastErrorAt: Long? = null,
)

@Dao
interface MinifluxCacheDao {
    @Query("DELETE FROM miniflux_entry_identity WHERE profileId=:profile AND remoteEntryId=:remote AND localFeedItemId=:local")
    suspend fun cleanupEntryIdentity(
        profile: String,
        remote: Long,
        local: Long,
    ): Int

    @Query("DELETE FROM feed_items WHERE id=:local")
    suspend fun cleanupItem(local: Long): Int

    @Query("SELECT * FROM miniflux_entry_identity WHERE profileId=:profile AND remoteFeedId=:feed")
    suspend fun cleanupEntries(
        profile: String,
        feed: Long,
    ): List<MinifluxEntryIdentity>

    @Query("SELECT * FROM feed_items WHERE feed_id=:feed")
    suspend fun cleanupItems(feed: Long): List<FeedItem>

    @Query(
        "SELECT COUNT(*) FROM miniflux_read_outbox o JOIN miniflux_entry_identity e ON e.profileId=o.profileId AND e.remoteEntryId=o.remoteEntryId WHERE e.profileId=:profile AND e.remoteFeedId=:feed",
    )
    suspend fun cleanupPendingCount(
        profile: String,
        feed: Long,
    ): Int

    @Query("DELETE FROM miniflux_entry_identity WHERE profileId=:profile AND remoteFeedId=:feed")
    suspend fun cleanupEntryIdentities(
        profile: String,
        feed: Long,
    )

    @Query("DELETE FROM miniflux_feed_identity WHERE profileId=:profile AND remoteFeedId=:feed AND localFeedId=:local")
    suspend fun cleanupFeedIdentity(
        profile: String,
        feed: Long,
        local: Long,
    ): Int

    @Query("DELETE FROM feeds WHERE id=:local")
    suspend fun cleanupFeed(local: Long): Int

    @Query("SELECT * FROM miniflux_feed_identity")
    suspend fun reconciliationFeeds(): List<MinifluxFeedIdentity>

    @Query("SELECT * FROM feeds")
    suspend fun reconciliationLocalFeeds(): List<Feed>

    @Query("SELECT * FROM miniflux_entry_identity")
    suspend fun reconciliationEntries(): List<MinifluxEntryIdentity>

    @Query("SELECT * FROM feed_items")
    suspend fun reconciliationItems(): List<FeedItem>

    @Query("SELECT changedAt FROM miniflux_entry_identity")
    suspend fun cachedChangeTimes(): List<MinifluxCachedChangeTime>

    @Query(
        "UPDATE miniflux_profiles SET changedCheckpoint=:checkpoint, bootstrapChangedAt=CASE WHEN :checkpoint IS NULL THEN bootstrapChangedAt ELSE NULL END, lastSuccessfulSync=:completedAt WHERE id=:profile",
    )
    suspend fun completeSync(
        profile: String,
        checkpoint: String?,
        completedAt: Long,
    )

    @Query("UPDATE miniflux_profiles SET bootstrapChangedAt=:lower WHERE id=:profile AND changedCheckpoint IS NULL AND bootstrapChangedAt IS NULL")
    suspend fun freezeBootstrap(
        profile: String,
        lower: String,
    )

    @Query("SELECT * FROM miniflux_read_outbox WHERE profileId=:profile AND remoteEntryId=:remote")
    suspend fun pending(
        profile: String,
        remote: Long,
    ): MinifluxReadOutbox?

    @Query("SELECT * FROM miniflux_read_outbox WHERE profileId=:profile ORDER BY updatedAt, remoteEntryId")
    suspend fun pending(profile: String): List<MinifluxReadOutbox>

    @Query("SELECT * FROM miniflux_read_outbox")
    fun observePending(): Flow<List<MinifluxReadOutbox>>

    @Query("SELECT * FROM miniflux_entry_identity ORDER BY remoteEntryId DESC LIMIT :limit")
    fun observeEntries(limit: Int): Flow<List<MinifluxEntryIdentity>>

    @Upsert suspend fun savePending(row: MinifluxReadOutbox)

    @Query("DELETE FROM miniflux_read_outbox WHERE profileId=:profile AND remoteEntryId=:remote AND generation=:generation")
    suspend fun acknowledge(
        profile: String,
        remote: Long,
        generation: Long,
    ): Int

    @Query("SELECT * FROM miniflux_profiles LIMIT 1")
    suspend fun profile(): MinifluxProfile?

    @Upsert suspend fun saveProfile(profile: MinifluxProfile)

    @Upsert suspend fun saveCategory(category: MinifluxCachedCategory)

    @Query("SELECT * FROM miniflux_feed_identity WHERE profileId=:profile AND remoteFeedId=:remote")
    suspend fun feedIdentity(
        profile: String,
        remote: Long,
    ): MinifluxFeedIdentity?

    @Query("SELECT * FROM feeds WHERE id=:id")
    suspend fun feed(id: Long): Feed?

    @Insert suspend fun insertFeed(feed: Feed): Long

    @Update suspend fun updateFeed(feed: Feed)

    @Insert suspend fun insertFeedIdentity(identity: MinifluxFeedIdentity)

    @Update suspend fun updateFeedIdentity(identity: MinifluxFeedIdentity)

    @Transaction suspend fun saveFeedIdentity(identity: MinifluxFeedIdentity) {
        if (feedIdentity(identity.profileId, identity.remoteFeedId) == null) insertFeedIdentity(identity) else updateFeedIdentity(identity)
    }

    @Query("SELECT * FROM miniflux_entry_identity WHERE profileId=:profile AND remoteEntryId=:remote")
    suspend fun entryIdentity(
        profile: String,
        remote: Long,
    ): MinifluxEntryIdentity?

    @Query("SELECT * FROM feed_items WHERE id=:id")
    suspend fun item(id: Long): FeedItem?

    @Insert suspend fun insertItem(item: FeedItem): Long

    @Update suspend fun updateItem(item: FeedItem)

    @Insert suspend fun insertEntryIdentity(identity: MinifluxEntryIdentity)

    @Update suspend fun updateEntryIdentity(identity: MinifluxEntryIdentity)

    @Transaction suspend fun saveEntryIdentity(identity: MinifluxEntryIdentity) {
        if (entryIdentity(identity.profileId, identity.remoteEntryId) == null) insertEntryIdentity(identity) else updateEntryIdentity(identity)
    }

    @Query("SELECT * FROM miniflux_entry_identity ORDER BY remoteEntryId DESC LIMIT :limit OFFSET :offset")
    suspend fun entries(
        limit: Int,
        offset: Int,
    ): List<MinifluxEntryIdentity>

    @Query("SELECT COUNT(*) FROM miniflux_entry_identity")
    suspend fun entryCount(): Int

    @Query("SELECT COUNT(*) FROM miniflux_feed_identity")
    suspend fun feedCount(): Int

    @Query("SELECT COUNT(*) FROM miniflux_categories")
    suspend fun categoryCount(): Int

    @Query("SELECT * FROM miniflux_entry_identity WHERE localFeedItemId=:id")
    suspend fun identityForLocal(id: Long): MinifluxEntryIdentity?

    @Query("UPDATE miniflux_entry_identity SET blobReady=1 WHERE profileId=:profile AND remoteEntryId=:remote AND contentHash=:hash")
    suspend fun markBlobReady(
        profile: String,
        remote: Long,
        hash: String,
    )
}

data class MinifluxCachedChangeTime(
    val changedAt: String?,
)

/** Entirely separate from AppDatabase and its singleton/DAOs. One physical database per profile. */
@Database(
    entities = [Feed::class, FeedItem::class, MinifluxProfile::class, MinifluxCachedCategory::class, MinifluxFeedIdentity::class, MinifluxEntryIdentity::class, MinifluxReadOutbox::class],
    version = 3,
)
@TypeConverters(Converters::class)
abstract class MinifluxCacheDatabase : RoomDatabase() {
    abstract fun cacheDao(): MinifluxCacheDao

    companion object {
        val MIGRATION_2_3 =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE miniflux_profiles ADD COLUMN bootstrapChangedAt TEXT DEFAULT NULL")
                }
            }

        val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE miniflux_entry_identity ADD COLUMN readGeneration INTEGER NOT NULL DEFAULT 0")
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS miniflux_read_outbox (profileId TEXT NOT NULL, remoteEntryId INTEGER NOT NULL, desiredStatus TEXT NOT NULL, generation INTEGER NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, lastAttemptAt INTEGER, attemptCount INTEGER NOT NULL, lastErrorType TEXT, lastErrorAt INTEGER, PRIMARY KEY(profileId, remoteEntryId), FOREIGN KEY(profileId, remoteEntryId) REFERENCES miniflux_entry_identity(profileId, remoteEntryId) ON UPDATE NO ACTION ON DELETE NO ACTION)",
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_miniflux_read_outbox_profileId_updatedAt ON miniflux_read_outbox (profileId, updatedAt)")
                }
            }
    }
}
