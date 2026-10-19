package com.nononsenseapps.feeder.commafeed

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed class CommaFeedFailure(
    message: String,
) : IOException(message) {
    class Authentication : CommaFeedFailure("Authentication failed (HTTP 401/403)")

    class Http(
        code: Int,
    ) : CommaFeedFailure("HTTP error $code")

    class InvalidResponse : CommaFeedFailure("Invalid CommaFeed response")

    class Pagination : CommaFeedFailure("Pagination error")
}

class InvalidCommaFeedBaseUrl : IllegalArgumentException("Invalid base URL")

/** GET-only Phase 1 client. No state-changing endpoint is exposed here. */
class CommaFeedRestClient(
    okHttpClient: OkHttpClient,
) {
    private val client =
        okHttpClient
            .newBuilder()
            // The shared debug interceptor logs all headers, including Authorization.
            .apply {
                interceptors().clear()
                networkInterceptors().clear()
            }.cache(null)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()

    fun normalizedBaseUrl(input: String): HttpUrl {
        val url = input.trim().toHttpUrlOrNull() ?: throw InvalidCommaFeedBaseUrl()
        if (url.scheme != "http" && url.scheme != "https") throw InvalidCommaFeedBaseUrl()
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) throw InvalidCommaFeedBaseUrl()
        if (url.encodedQuery != null || url.encodedFragment != null) throw InvalidCommaFeedBaseUrl()
        if (url.encodedPath != "/") throw InvalidCommaFeedBaseUrl()
        return url
    }

    suspend fun fetch(
        baseUrl: String,
        username: String,
        password: String,
        maximum: Int = 1000,
    ): List<RemoteArticle> =
        withContext(Dispatchers.IO) {
            require(maximum in 1..1000)
            val base = normalizedBaseUrl(baseUrl)
            val authorization = Credentials.basic(username, password)
            val result = ArrayList<RemoteArticle>(maximum)
            var offset = 0
            while (result.size < maximum) {
                val limit = minOf(500, maximum - result.size)
                val endpoint =
                    base
                        .newBuilder()
                        .addPathSegments("rest/category/entries")
                        .addQueryParameter("id", "all")
                        .addQueryParameter("readType", "all")
                        .addQueryParameter("order", "desc")
                        .addQueryParameter("offset", offset.toString())
                        .addQueryParameter("limit", limit.toString())
                        .build()
                val request =
                    Request
                        .Builder()
                        .url(endpoint)
                        .get()
                        .header("Authorization", authorization)
                        .build()
                val page =
                    client.newCall(request).execute().use { response ->
                        when (response.code) {
                            401, 403 -> throw CommaFeedFailure.Authentication()
                            in 300..399 -> throw CommaFeedFailure.Http(response.code)
                        }
                        if (!response.isSuccessful) throw CommaFeedFailure.Http(response.code)
                        val body = response.body.string()
                        try {
                            JSONObject(body)
                        } catch (_: JSONException) {
                            throw CommaFeedFailure.InvalidResponse()
                        }
                    }
                val entries = page.optJSONArray("entries") ?: throw CommaFeedFailure.InvalidResponse()
                if (!page.has("hasMore")) throw CommaFeedFailure.InvalidResponse()
                if (page.optInt("offset", -1) != offset || page.optInt("limit", -1) != limit) {
                    throw CommaFeedFailure.Pagination()
                }
                if (entries.length() > limit || (page.optBoolean("hasMore") && entries.length() == 0)) {
                    throw CommaFeedFailure.Pagination()
                }
                for (index in 0 until entries.length()) {
                    val entry = entries.optJSONObject(index) ?: throw CommaFeedFailure.InvalidResponse()
                    val id = entry.optString("id").takeIf { it.isNotBlank() } ?: throw CommaFeedFailure.InvalidResponse()
                    val feedUrl = entry.optString("feedUrl").takeIf { it.isNotBlank() } ?: throw CommaFeedFailure.InvalidResponse()
                    if (!entry.has("read") || !entry.has("markable")) throw CommaFeedFailure.InvalidResponse()
                    try {
                        result +=
                            RemoteArticle(
                                id = id,
                                feedId = entry.optString("feedId").takeIf { it.isNotBlank() },
                                feedUrl = feedUrl,
                                feedTitle = entry.optString("feedName"),
                                guid = entry.optString("guid").takeIf { it.isNotBlank() },
                                url = entry.optString("url").takeIf { it.isNotBlank() },
                                title = entry.optString("title"),
                                publishedDate = entry.optString("date").takeIf { it.isNotBlank() },
                                insertedDate = entry.optString("insertedDate").takeIf { it.isNotBlank() },
                                read = entry.getBoolean("read"),
                                markable = entry.getBoolean("markable"),
                            )
                    } catch (_: JSONException) {
                        throw CommaFeedFailure.InvalidResponse()
                    }
                }
                offset += entries.length()
                if (!page.optBoolean("hasMore")) break
            }
            result
        }
}
