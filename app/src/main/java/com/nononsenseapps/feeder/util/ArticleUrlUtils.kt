package com.nononsenseapps.feeder.util

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal fun validHttpArticleUrlOrNull(articleUrl: String?): String? {
    val trimmedUrl = articleUrl?.trim().orEmpty()
    return trimmedUrl.takeIf { it.toHttpUrlOrNull() != null }
}

internal fun articleBaseUrl(
    articleUrl: String?,
    fallbackUrl: String?,
): String = validHttpArticleUrlOrNull(articleUrl) ?: fallbackUrl.orEmpty()
