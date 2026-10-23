package com.nononsenseapps.feeder.miniflux

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MinifluxAccount(
    val id: Long,
    val username: String,
)

@Serializable
data class MinifluxFeed(
    val id: Long,
    val title: String,
    @SerialName("feed_url") val feedUrl: String,
    val category: MinifluxCategory? = null,
    @SerialName("site_url") val siteUrl: String = "",
)

@Serializable
data class MinifluxCategory(
    val id: Long,
    val title: String,
)

@Serializable
data class MinifluxEntry(
    val id: Long,
    @SerialName("feed_id") val feedId: Long,
    val title: String,
    val status: String,
    val starred: Boolean,
    @SerialName("published_at") val publishedAt: String,
    @SerialName("changed_at") val changedAt: String? = null,
    val url: String,
    val author: String? = null,
    val content: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class MinifluxEntries(
    val total: Long,
    val entries: List<MinifluxEntry>,
)

@Serializable
data class MinifluxVersion(
    val version: String,
)

enum class MinifluxError {
    MISSING_URL,
    INVALID_URL,
    MISSING_TOKEN,
    INVALID_TOKEN,
    CREDENTIAL_UNAVAILABLE,
    CONNECTION,
    TIMEOUT,
    TLS,
    UNAUTHORIZED,
    FORBIDDEN,
    NOT_FOUND,
    SERVER,
    HTTP,
    REDIRECT,
    INVALID_RESPONSE,
    RESPONSE_TOO_LARGE,
}

// Deliberately contains no response text, URL, original exception, or credential.
class MinifluxFailure(
    val error: MinifluxError,
) : Exception(error.name)

sealed interface MinifluxStep<out T> {
    data class Success<T>(
        val value: T,
    ) : MinifluxStep<T>

    data class Failed(
        val error: MinifluxError,
    ) : MinifluxStep<Nothing>

    data object NotChecked : MinifluxStep<Nothing>
}

enum class MinifluxSupport { VERSION_EXPECTED, VERSION_TOO_OLD, UNKNOWN }

data class MinifluxCapabilities(
    val changedAfter: MinifluxSupport,
    val readUnread: MinifluxSupport,
    val starredState: MinifluxSupport,
    val originalContent: MinifluxSupport,
)

fun minifluxCapabilities(version: String?): MinifluxCapabilities {
    // Official API Reference: changed_after 2.0.49, explicit starred 2.3.2,
    // fetch-content 2.0.36. Mutation endpoints are not probed.
    // Custom, prerelease and future-major versions remain unconfirmed.
    val parts = version?.let { Regex("2\\.(\\d+)\\.(\\d+)").matchEntire(it) }
    val minor = parts?.groupValues?.get(1)?.toIntOrNull()
    val patch = parts?.groupValues?.get(2)?.toIntOrNull()

    fun since(
        m: Int,
        p: Int,
    ): MinifluxSupport =
        if (minor == null || patch == null) {
            MinifluxSupport.UNKNOWN
        } else if (minor > m || minor == m && patch >= p) {
            MinifluxSupport.VERSION_EXPECTED
        } else {
            MinifluxSupport.VERSION_TOO_OLD
        }
    return MinifluxCapabilities(since(0, 49), MinifluxSupport.UNKNOWN, since(3, 2), since(0, 36))
}

data class MinifluxDiagnosticResult(
    val account: MinifluxStep<MinifluxAccount>,
    val feeds: MinifluxStep<Int> = MinifluxStep.NotChecked,
    val categories: MinifluxStep<Int> = MinifluxStep.NotChecked,
    val entries: MinifluxStep<Int> = MinifluxStep.NotChecked,
    val version: MinifluxStep<String> = MinifluxStep.NotChecked,
) {
    val capabilities: MinifluxCapabilities
        get() = minifluxCapabilities((version as? MinifluxStep.Success)?.value)
}
