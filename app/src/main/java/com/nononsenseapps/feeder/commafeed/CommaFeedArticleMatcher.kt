package com.nononsenseapps.feeder.commafeed

/** Exact comparisons only. Any competing evidence makes an article ambiguous. */
object CommaFeedArticleMatcher {
    fun compare(
        local: List<LocalArticle>,
        remote: List<RemoteArticle>,
    ): DiagnosticResult {
        val localFeeds = local.groupBy { it.feedUrl }.mapValues { it.value.map { article -> article.feedId }.distinct() }
        val remoteFeeds = remote.groupBy { it.feedUrl }.mapValues { it.value.map { article -> article.feedId }.distinct() }
        val duplicateRemoteIds = remote.groupBy { it.id }.filterValues { it.size > 1 }.keys
        val edges = mutableMapOf<Long, MutableMap<String, MatchType>>()
        val reverse = mutableMapOf<String, MutableSet<Long>>()
        val ambiguousLocal = mutableSetOf<Long>()
        val ambiguousRemote = mutableSetOf<String>()

        for (l in local) {
            val candidateRemote = remote.filter { it.feedUrl == l.feedUrl }
            if (localFeeds[l.feedUrl]?.size != 1 || remoteFeeds[l.feedUrl]?.size != 1) {
                if (candidateRemote.isNotEmpty()) {
                    ambiguousLocal += l.id
                    ambiguousRemote += candidateRemote.map { it.id }
                }
                continue
            }
            for (r in candidateRemote) {
                val guid = l.guid.isNotBlank() && l.guid == r.guid
                val url = !l.url.isNullOrBlank() && l.url == r.url
                if (guid || url) {
                    edges.getOrPut(l.id) { mutableMapOf() }[r.id] = if (guid) MatchType.GUID else MatchType.URL
                    reverse.getOrPut(r.id) { mutableSetOf() } += l.id
                }
            }
        }

        val matches = mutableListOf<ArticleMatch>()
        for (l in local) {
            val candidates = edges[l.id].orEmpty()
            if (candidates.size > 1) ambiguousLocal += l.id
            for ((remoteId, _) in candidates) {
                if ((reverse[remoteId]?.size ?: 0) > 1 || remoteId in duplicateRemoteIds) {
                    ambiguousLocal += l.id
                    ambiguousRemote += remoteId
                }
            }
            if (l.id in ambiguousLocal || candidates.size != 1) continue
            val (remoteId, type) = candidates.entries.single()
            if ((reverse[remoteId]?.size ?: 0) != 1 || remoteId in duplicateRemoteIds) continue
            val r = remote.singleOrNull { it.id == remoteId } ?: continue
            matches += ArticleMatch(l, r, type)
        }
        for (id in ambiguousLocal) {
            ambiguousRemote += edges[id].orEmpty().keys
        }
        val matchedLocal = matches.map { it.local.id }.toSet()
        val matchedRemote = matches.map { it.remote.id }.toSet()
        val localOnly = local.filter { it.id !in matchedLocal && it.id !in ambiguousLocal }
        val remoteOnly = remote.filter { it.id !in matchedRemote && it.id !in ambiguousRemote }
        val details =
            (
                localOnly.take(15).map { DiagnosticDetail(it.title, it.feedTitle, "Local only", it.read, null) } +
                    remoteOnly.take(15).map { DiagnosticDetail(it.title, it.feedTitle, "Remote only", null, it.read) } +
                    local.filter { it.id in ambiguousLocal }.take(10).map { DiagnosticDetail(it.title, it.feedTitle, "Ambiguous", it.read, null) } +
                    matches.filter { it.local.read != it.remote.read }.take(10).map {
                        DiagnosticDetail(it.local.title, it.local.feedTitle, "Read mismatch", it.local.read, it.remote.read)
                    }
            ).take(50)
        return DiagnosticResult(
            remoteFetched = remote.size,
            matchingFeeds = local.map { it.feedId }.distinct().size,
            localConsidered = local.size,
            matches = matches,
            ambiguousLocal = ambiguousLocal.size,
            ambiguousRemote = remote.count { it.id in ambiguousRemote },
            unmatchedLocal = localOnly.size,
            unmatchedRemote = remoteOnly.size,
            readSame = matches.count { it.local.read == it.remote.read },
            readDifferent = matches.count { it.local.read != it.remote.read },
            details = details,
        )
    }
}
