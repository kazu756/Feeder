package com.nononsenseapps.feeder.db.room

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Observations are not a synchronization baseline. Phase 1 never assigns baselineRead. */
@Entity(
    tableName = "commafeed_article_mapping",
    primaryKeys = ["server_key", "local_article_id"],
    indices = [
        Index(value = ["server_key", "remote_article_id"], unique = true),
        Index(value = ["local_article_id"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = FeedItem::class,
            parentColumns = ["id"],
            childColumns = ["local_article_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class CommaFeedArticleMapping(
    @ColumnInfo(name = "server_key") val serverKey: String,
    @ColumnInfo(name = "local_article_id") val localArticleId: Long,
    @ColumnInfo(name = "remote_article_id") val remoteArticleId: String,
    @ColumnInfo(name = "local_feed_id") val localFeedId: Long,
    @ColumnInfo(name = "remote_feed_id") val remoteFeedId: String?,
    @ColumnInfo(name = "match_type") val matchType: String,
    @ColumnInfo(name = "matched_at") val matchedAt: Long,
    @ColumnInfo(name = "last_observed_local_read") val lastObservedLocalRead: Boolean,
    @ColumnInfo(name = "last_observed_remote_read") val lastObservedRemoteRead: Boolean,
    @ColumnInfo(name = "last_observed_at") val lastObservedAt: Long,
    @ColumnInfo(name = "baseline_read") val baselineRead: Boolean? = null,
    @ColumnInfo(name = "pending_desired_read") val pendingDesiredRead: Boolean? = null,
)

@Dao
interface CommaFeedArticleMappingDao {
    @Query("SELECT * FROM commafeed_article_mapping WHERE server_key = :serverKey")
    suspend fun forServer(serverKey: String): List<CommaFeedArticleMapping>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(mapping: CommaFeedArticleMapping)

    @Query("DELETE FROM commafeed_article_mapping WHERE server_key = :serverKey AND local_article_id = :localId")
    suspend fun delete(
        serverKey: String,
        localId: Long,
    )
}
