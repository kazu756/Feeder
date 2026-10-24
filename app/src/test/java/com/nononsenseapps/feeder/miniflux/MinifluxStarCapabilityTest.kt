package com.nononsenseapps.feeder.miniflux

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MinifluxStarCapabilityTest {
    @Test fun acceptedOnlyAuditedSourceIdentities() {
        assertTrue(supportsMinifluxStarSetting(MinifluxVersion("2.3.x-dev", "bcb2cf2aa29c3d1289fe889fb5fae2c255953bd9")))
        assertTrue(supportsMinifluxStarSetting(MinifluxVersion("2.3.x-dev", "8de58f8")))
    }

    @Test fun stableReleaseBoundaries() {
        for (version in listOf("2.3.2", "2.3.3", "2.3.4", "2.4.0", "3.0.0")) {
            assertEquals(MinifluxStarCapability(MinifluxStarSupport.SUPPORTED, MinifluxStarReason.SUPPORTED_SEMVER), minifluxStarCapability(MinifluxVersion(version)))
        }
        for (version in listOf("2.3.1", "2.2.99", "1.99.99")) {
            assertEquals(MinifluxStarCapability(MinifluxStarSupport.UNSUPPORTED, MinifluxStarReason.UNSUPPORTED_SEMVER), minifluxStarCapability(MinifluxVersion(version)))
        }
    }

    @Test fun devUnknownOrMalformedCommitCannotEnableWrites() {
        for (commit in listOf("", "8de58f", "0000000000000000000000000000000000000000", "8de58f8-dirty", "not-a-commit")) {
            assertFalse(supportsMinifluxStarSetting(MinifluxVersion("2.3.x-dev", commit)))
        }
    }

    @Test fun malformedAndMissingVersionsAndBuildDateAloneStayUnknown() {
        for (version in listOf("", "malformed", "2.3.x-dev", "v2.3.2", "2.3.2-dev", "2.03.2", "2.3.2+custom", "2.3.999999999999999", "2.3.2\n")) {
            assertEquals(MinifluxStarSupport.UNKNOWN, minifluxStarCapability(MinifluxVersion(version, buildDate = "2099-01-01T00:00:00Z")).support)
        }
        assertEquals(MinifluxStarReason.UNKNOWN_VERSION_REQUEST_FAILED, minifluxStarCapability(null).reason)
    }

    @Test fun releaseCommitAndOtherVerifiedDescendantDevBuildsAreAccepted() {
        for (commit in listOf("51f2e0d", "c4d54f8", "238c561", "69756868")) {
            assertEquals(MinifluxStarReason.SUPPORTED_KNOWN_BUILD, minifluxStarCapability(MinifluxVersion("2.3.x-dev", commit)).reason)
        }
        assertEquals(107, minifluxKnownStarCommits.size)
    }

    @Test fun knownOldBuildIsUnsupported() {
        assertEquals(MinifluxStarCapability(MinifluxStarSupport.UNSUPPORTED, MinifluxStarReason.UNSUPPORTED_KNOWN_BUILD), minifluxStarCapability(MinifluxVersion("2.3.x-dev", "9a77408")))
    }

    @Test fun ambiguousShortCommitIsNeverAccepted() {
        val known = setOf("abcdef0123456789012345678901234567890123", "abcdef0123456789012345678901234567890456")
        assertEquals(MinifluxStarReason.UNKNOWN_AMBIGUOUS_COMMIT, minifluxStarCommitCapability("abcdef0", known, emptySet()).reason)
        assertEquals(MinifluxStarSupport.SUPPORTED, minifluxStarCommitCapability(known.first(), known, emptySet()).support)
    }
}
