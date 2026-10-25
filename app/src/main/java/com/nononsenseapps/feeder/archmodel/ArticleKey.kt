package com.nononsenseapps.feeder.archmodel

/** An article's identity survives mode changes and never relies on the active backend. */
data class ArticleKey(
    val localId: Long,
    val profileId: String? = null,
) {
    init {
        require(profileId == null || Regex("[a-f0-9]{64}").matches(profileId))
    }

    val isMiniflux: Boolean get() = profileId != null
    val stableKey: String get() = "${profileId ?: "rss"}:$localId"
}

interface ArticleCursor : com.nononsenseapps.feeder.db.room.FeedItemCursor {
    val articleKey: ArticleKey
}
