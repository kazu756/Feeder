package com.nononsenseapps.feeder.archmodel

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance

internal const val MAX_RECENTLY_VIEWED_ARTICLES = 5
internal const val PREF_RECENTLY_VIEWED_ARTICLE_IDS = "pref_recently_viewed_article_ids"

class RecentlyViewedStore(
    override val di: DI,
) : DIAware {
    private val sharedPreferences: SharedPreferences by instance()

    private val _articleIds =
        MutableStateFlow(
            decodeRecentlyViewedArticleIds(
                sharedPreferences.getString(PREF_RECENTLY_VIEWED_ARTICLE_IDS, null),
            ),
        )
    val articleIds = _articleIds.asStateFlow()

    @Synchronized
    fun recordArticle(articleId: Long) {
        if (articleId <= 0L) return

        val updatedIds = updateRecentlyViewedArticleIds(_articleIds.value, articleId)
        _articleIds.value = updatedIds
        sharedPreferences
            .edit()
            .putString(PREF_RECENTLY_VIEWED_ARTICLE_IDS, encodeRecentlyViewedArticleIds(updatedIds))
            .apply()
    }
}

internal fun updateRecentlyViewedArticleIds(
    currentIds: List<Long>,
    openedId: Long,
): List<Long> =
    if (openedId <= 0L) {
        currentIds.filter { it > 0L }.distinct().take(MAX_RECENTLY_VIEWED_ARTICLES)
    } else {
        (listOf(openedId) + currentIds.filter { it > 0L && it != openedId })
            .distinct()
            .take(MAX_RECENTLY_VIEWED_ARTICLES)
    }

internal fun encodeRecentlyViewedArticleIds(articleIds: List<Long>): String =
    articleIds
        .filter { it > 0L }
        .distinct()
        .take(MAX_RECENTLY_VIEWED_ARTICLES)
        .joinToString(",")

internal fun decodeRecentlyViewedArticleIds(value: String?): List<Long> =
    value
        .orEmpty()
        .split(',')
        .mapNotNull { it.trim().toLongOrNull() }
        .filter { it > 0L }
        .distinct()
        .take(MAX_RECENTLY_VIEWED_ARTICLES)
