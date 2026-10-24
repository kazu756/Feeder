package com.nononsenseapps.feeder.miniflux

enum class MinifluxStarSupport { SUPPORTED, UNSUPPORTED, UNKNOWN }

enum class MinifluxStarReason {
    SUPPORTED_SEMVER,
    UNSUPPORTED_SEMVER,
    SUPPORTED_KNOWN_BUILD,
    UNSUPPORTED_KNOWN_BUILD,
    UNKNOWN_VERSION,
    UNKNOWN_COMMIT,
    UNKNOWN_AMBIGUOUS_COMMIT,
    UNKNOWN_VERSION_REQUEST_FAILED,
}

data class MinifluxStarCapability(
    val support: MinifluxStarSupport,
    val reason: MinifluxStarReason,
)

/** Stable official version contract, otherwise only an offline, Git-DAG-verified source snapshot. */
internal fun minifluxStarCapability(build: MinifluxVersion?): MinifluxStarCapability {
    if (build == null) return MinifluxStarCapability(MinifluxStarSupport.UNKNOWN, MinifluxStarReason.UNKNOWN_VERSION_REQUEST_FAILED)
    // Strict release triplet only: no v prefix, prerelease, build suffix, leading zeros or coercion of x.
    val parts = Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)").matchEntire(build.version)
    val numbers = parts?.groupValues?.drop(1)?.map { it.toIntOrNull() }
    if (numbers != null && numbers.all { it != null }) {
        val (major, minor, patch) = numbers.map { checkNotNull(it) }
        val supported = major > 2 || major == 2 && (minor > 3 || minor == 3 && patch >= 2)
        return if (supported) {
            MinifluxStarCapability(MinifluxStarSupport.SUPPORTED, MinifluxStarReason.SUPPORTED_SEMVER)
        } else {
            MinifluxStarCapability(MinifluxStarSupport.UNSUPPORTED, MinifluxStarReason.UNSUPPORTED_SEMVER)
        }
    }
    // Missing/malformed version cannot turn arbitrary metadata into evidence of an official dev build.
    if (build.version.isEmpty()) return MinifluxStarCapability(MinifluxStarSupport.UNKNOWN, MinifluxStarReason.UNKNOWN_VERSION)
    return minifluxStarCommitCapability(build.commit, minifluxKnownStarCommits, setOf("9a774083ab4f61f7d604fb950bdd6aff029824a1"))
}

internal fun minifluxStarCommitCapability(
    value: String,
    supported: Set<String>,
    unsupported: Set<String>,
): MinifluxStarCapability {
    val commit = value.lowercase()
    if (!Regex("[0-9a-f]{7,40}").matches(commit)) return MinifluxStarCapability(MinifluxStarSupport.UNKNOWN, MinifluxStarReason.UNKNOWN_COMMIT)
    val matches = (supported + unsupported).filter { it.startsWith(commit) }
    return when {
        matches.size > 1 -> MinifluxStarCapability(MinifluxStarSupport.UNKNOWN, MinifluxStarReason.UNKNOWN_AMBIGUOUS_COMMIT)
        matches.isEmpty() -> MinifluxStarCapability(MinifluxStarSupport.UNKNOWN, MinifluxStarReason.UNKNOWN_COMMIT)
        matches.single() in supported -> MinifluxStarCapability(MinifluxStarSupport.SUPPORTED, MinifluxStarReason.SUPPORTED_KNOWN_BUILD)
        else -> MinifluxStarCapability(MinifluxStarSupport.UNSUPPORTED, MinifluxStarReason.UNSUPPORTED_KNOWN_BUILD)
    }
}

internal fun supportsMinifluxStarSetting(version: MinifluxVersion): Boolean = minifluxStarCapability(version).support == MinifluxStarSupport.SUPPORTED
