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
        val result =
            MinifluxDiagnosticResult(
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
            )
        var capability = minifluxStarCapability(null)
        val build =
            step {
                val raw = api.version(base, token)
                // Evaluate original metadata; display sanitization must never enable a malformed version.
                capability = minifluxStarCapability(raw)
                raw.copy(version = safeDisplay(raw.version, token), commit = safeDisplay(raw.commit, token), buildDate = safeDisplay(raw.buildDate, token))
            }
        return result.copy(
            version =
                when (build) {
                    is MinifluxStep.Success -> MinifluxStep.Success(build.value.version)
                    is MinifluxStep.Failed -> build
                    MinifluxStep.NotChecked -> MinifluxStep.NotChecked
                },
            buildInfo = build,
            starCapability = capability,
            // Sibling endpoint availability is diagnostic only, never evidence for starred PUT.
            entryIds =
                step {
                    val ids = api.entryIds(base, token, "unread", 0, limit = 1)
                    if (ids.ids.size > 1 || ids.total < ids.ids.size || ids.ids.any { it <= 0 }) throw MinifluxFailure(MinifluxError.INVALID_RESPONSE)
                    ids.ids.size
                },
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
