package com.nononsenseapps.feeder.miniflux

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
import com.nononsenseapps.feeder.db.room.Converters
import com.nononsenseapps.feeder.db.room.Feed
import com.nononsenseapps.feeder.db.room.FeedItem

@Entity(tableName = "miniflux_profiles", indices = [Index(value = ["baseUrl", "userId"], unique = true)])
data class MinifluxProfile(
    @PrimaryKey val id: String,
    val baseUrl: String,
    val userId: Long,
    val username: String,
    val createdAt: Long,
    val lastSuccessfulSync: Long? = null,
    val changedCheckpoint: String? = null,
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
)

@Dao
interface MinifluxCacheDao {
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
}

/** Entirely separate from AppDatabase and its singleton/DAOs. One physical database per profile. */
@Database(entities = [Feed::class, FeedItem::class, MinifluxProfile::class, MinifluxCachedCategory::class, MinifluxFeedIdentity::class, MinifluxEntryIdentity::class], version = 1)
@TypeConverters(Converters::class)
abstract class MinifluxCacheDatabase : RoomDatabase() {
    abstract fun cacheDao(): MinifluxCacheDao
}
