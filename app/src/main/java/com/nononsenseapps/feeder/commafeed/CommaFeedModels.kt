package com.nononsenseapps.feeder.commafeed

data class LocalArticle(
    val id: Long,
    val feedId: Long,
    val feedUrl: String,
    val feedTitle: String,
    val guid: String,
    val url: String?,
    val title: String,
    val read: Boolean,
)

data class RemoteArticle(
    val id: String,
    val feedId: String?,
    val feedUrl: String,
    val feedTitle: String,
    val guid: String?,
    val url: String?,
    val title: String,
    val publishedDate: String?,
    val insertedDate: String?,
    val read: Boolean,
    val markable: Boolean,
)

enum class MatchType { GUID, URL }

data class ArticleMatch(
    val local: LocalArticle,
    val remote: RemoteArticle,
    val type: MatchType,
)

data class DiagnosticDetail(
    val title: String,
    val feedTitle: String,
    val reason: String,
    val localRead: Boolean?,
    val remoteRead: Boolean?,
)

data class DiagnosticResult(
    val remoteFetched: Int,
    val matchingFeeds: Int,
    val localConsidered: Int,
    val matches: List<ArticleMatch>,
    val ambiguousLocal: Int,
    val ambiguousRemote: Int,
    val unmatchedLocal: Int,
    val unmatchedRemote: Int,
    val readSame: Int,
    val readDifferent: Int,
    val details: List<DiagnosticDetail>,
) {
    val matchedTotal: Int get() = matches.size
    val matchedGuid: Int get() = matches.count { it.type == MatchType.GUID }
    val matchedUrl: Int get() = matches.count { it.type == MatchType.URL }
    val localMatchRate: Double get() = if (localConsidered == 0) 0.0 else matchedTotal * 100.0 / localConsidered
    val remoteMatchRate: Double get() = if (remoteFetched == 0) 0.0 else matchedTotal * 100.0 / remoteFetched
}
