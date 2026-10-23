package com.nononsenseapps.feeder.miniflux

import kotlinx.coroutines.CancellationException

/** Explicitly invoked only; no database, scheduler, or RSS dependency. */
class MinifluxDiagnosticService(
    private val api: MinifluxApiClient,
) {
    suspend fun run(
        baseUrl: String,
        token: String,
    ): MinifluxDiagnosticResult {
        val base = normalizedMinifluxUrl(baseUrl)
        validateMinifluxToken(token)
        val account =
            step {
                api.me(base, token).let { it.copy(username = safeDisplay(it.username, token)) }
            }
        if (account !is MinifluxStep.Success) return MinifluxDiagnosticResult(account)
        return MinifluxDiagnosticResult(
            account = account,
            feeds = step { api.feeds(base, token).size },
            categories = step { api.categories(base, token).size },
            entries =
                step {
                    val entries = api.entries(base, token)
                    if (entries.entries.size > 10 ||
                        entries.total < entries.entries.size ||
                        entries.entries.any { it.id <= 0 || it.feedId <= 0 || it.status !in setOf("read", "unread", "removed") }
                    ) {
                        throw MinifluxFailure(MinifluxError.INVALID_RESPONSE)
                    }
                    entries.entries.size
                },
            version = step { safeDisplay(api.version(base, token).version, token) },
        )
    }

    private suspend fun <T> step(block: suspend () -> T): MinifluxStep<T> =
        try {
            MinifluxStep.Success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: MinifluxFailure) {
            MinifluxStep.Failed(e.error)
        }

    private fun safeDisplay(
        value: String,
        token: String,
    ): String = value.replace(token, "[redacted]").filterNot(Char::isISOControl).take(80)
}
