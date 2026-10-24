package com.nononsenseapps.feeder.miniflux

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "miniflux_star_outbox",
    primaryKeys = ["profileId", "remoteEntryId"],
    foreignKeys = [ForeignKey(entity = MinifluxEntryIdentity::class, parentColumns = ["profileId", "remoteEntryId"], childColumns = ["profileId", "remoteEntryId"])],
    indices = [Index(value = ["profileId", "updatedAt"])],
)
data class MinifluxStarOutbox(
    val profileId: String,
    val remoteEntryId: Long,
    val desiredStarred: Boolean,
    val generation: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val lastAttemptAt: Long? = null,
    val attemptCount: Int = 0,
    val lastErrorType: String? = null,
    val lastErrorAt: Long? = null,
)
