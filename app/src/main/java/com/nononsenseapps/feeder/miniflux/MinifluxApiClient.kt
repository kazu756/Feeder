package com.nononsenseapps.feeder.miniflux

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.resume

fun normalizedMinifluxUrl(value: String): HttpUrl {
    if (value.isBlank()) throw MinifluxFailure(MinifluxError.MISSING_URL)
    val uri =
        try {
            URI(value)
        } catch (_: Exception) {
            throw MinifluxFailure(MinifluxError.INVALID_URL)
        }
    val url = value.toHttpUrlOrNull()
    if (url == null ||
        uri.scheme?.lowercase() !in setOf("http", "https") ||
        uri.rawAuthority.isNullOrBlank() ||
        uri.rawUserInfo != null ||
        uri.rawQuery != null ||
        uri.rawFragment != null ||
        uri.rawPath !in listOf("", "/") ||
        value.any { it.isWhitespace() || it == '\\' } ||
        !value.contains("://") ||
        url.username.isNotEmpty() ||
        url.password.isNotEmpty()
    ) {
        throw MinifluxFailure(MinifluxError.INVALID_URL)
    }
    return url.newBuilder().encodedPath("/").build()
}

fun validateMinifluxToken(token: String) {
    if (token.isBlank()) throw MinifluxFailure(MinifluxError.MISSING_TOKEN)
    if (token.length > 4096 || token.any { it.code !in 33..126 }) throw MinifluxFailure(MinifluxError.INVALID_TOKEN)
}

/** GET-only diagnostic client. Never inherits Feeder's header logging or cache interceptors. */
class MinifluxApiClient internal constructor(
    private val client: OkHttpClient,
) {
    constructor() : this(
        OkHttpClient
            .Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build(),
    )

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun me(
        base: HttpUrl,
        token: String,
    ): MinifluxAccount = decode(get(base, token, "v1/me"))

    suspend fun feeds(
        base: HttpUrl,
        token: String,
    ): List<MinifluxFeed> = decode(get(base, token, "v1/feeds"))

    suspend fun categories(
        base: HttpUrl,
        token: String,
    ): List<MinifluxCategory> = decode(get(base, token, "v1/categories"))

    suspend fun entries(
        base: HttpUrl,
        token: String,
    ): MinifluxEntries = decode(get(base, token, "v1/entries", sample = true))

    suspend fun version(
        base: HttpUrl,
        token: String,
    ): MinifluxVersion = decode(get(base, token, "v1/version"))

    private inline fun <reified T> decode(body: String): T =
        try {
            json.decodeFromString<T>(body)
        } catch (_: SerializationException) {
            throw MinifluxFailure(MinifluxError.INVALID_RESPONSE)
        } catch (_: IllegalArgumentException) {
            throw MinifluxFailure(MinifluxError.INVALID_RESPONSE)
        }

    private suspend fun get(
        base: HttpUrl,
        token: String,
        path: String,
        sample: Boolean = false,
    ): String =
        withContext(Dispatchers.IO) {
            // Revalidate even for callers constructing an HttpUrl directly.
            val root = normalizedMinifluxUrl(base.toString())
            validateMinifluxToken(token)
            val url =
                root
                    .newBuilder()
                    .addPathSegments(path)
                    .apply {
                        if (sample) {
                            addQueryParameter("limit", "10")
                            addQueryParameter("order", "id")
                            addQueryParameter("direction", "desc")
                        }
                    }.build()
            val call =
                client.newCall(
                    Request
                        .Builder()
                        .url(url)
                        .header("X-Auth-Token", token)
                        .header("Accept", "application/json")
                        .get()
                        .build(),
                )
            suspendCancellableCoroutine<Result<String>> { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(
                    object : Callback {
                        override fun onFailure(
                            call: Call,
                            e: IOException,
                        ) {
                            val error =
                                when (e) {
                                    is SSLException -> MinifluxError.TLS
                                    is InterruptedIOException -> MinifluxError.TIMEOUT
                                    else -> MinifluxError.CONNECTION
                                }
                            continuation.resume(Result.failure<String>(MinifluxFailure(error)))
                        }

                        override fun onResponse(
                            call: Call,
                            response: Response,
                        ) {
                            val result =
                                try {
                                    response.use {
                                        val error =
                                            when (it.code) {
                                                in 200..299 -> null
                                                in 300..399 -> MinifluxError.REDIRECT
                                                401 -> MinifluxError.UNAUTHORIZED
                                                403 -> MinifluxError.FORBIDDEN
                                                404 -> MinifluxError.NOT_FOUND
                                                in 500..599 -> MinifluxError.SERVER
                                                else -> MinifluxError.HTTP
                                            }
                                        if (error != null) throw MinifluxFailure(error)
                                        val body = it.body
                                        val source = body.source()
                                        source.request(MAX_RESPONSE_BYTES + 1)
                                        if (source.buffer.size > MAX_RESPONSE_BYTES) throw MinifluxFailure(MinifluxError.RESPONSE_TOO_LARGE)
                                        Result.success(source.readUtf8())
                                    }
                                } catch (
                                    e: MinifluxFailure,
                                ) {
                                    Result.failure(e)
                                } catch (
                                    _: SSLException,
                                ) {
                                    Result.failure(MinifluxFailure(MinifluxError.TLS))
                                } catch (
                                    _: InterruptedIOException,
                                ) {
                                    Result.failure(MinifluxFailure(MinifluxError.TIMEOUT))
                                } catch (_: IOException) {
                                    Result.failure(MinifluxFailure(MinifluxError.CONNECTION))
                                }
                            continuation.resume(result)
                        }
                    },
                )
            }.getOrThrow()
        }

    companion object {
        private const val MAX_RESPONSE_BYTES = 8L * 1024 * 1024
    }
}
