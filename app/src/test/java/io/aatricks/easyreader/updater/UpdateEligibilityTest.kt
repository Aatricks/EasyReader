package io.aatricks.easyreader.updater

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateEligibilityTest {

    @Test
    fun `numeric comparison handles release prefixes and missing parts`() {
        assertTrue(UpdateEligibility.isVersionNewer("V0.5.9.1", "0.5.9"))
        assertFalse(UpdateEligibility.isVersionNewer("v0.5.9", "0.5.9.0"))
        assertFalse(UpdateEligibility.isVersionNewer("v0.5.8", "0.5.9"))
    }

    @Test
    fun `identical or ahead source does not offer numerically newer release`() {
        assertFalse(
            UpdateEligibility.shouldOfferUpdate("V0.5.9.1", "0.5.9", CommitComparisonStatus.IDENTICAL)
        )
        assertFalse(
            UpdateEligibility.shouldOfferUpdate("V0.5.9.1", "0.5.9", CommitComparisonStatus.AHEAD)
        )
    }

    @Test
    fun `behind source offers numerically newer release`() {
        assertTrue(
            UpdateEligibility.shouldOfferUpdate("V0.5.9.1", "0.5.9", CommitComparisonStatus.BEHIND)
        )
    }

    @Test
    fun `unknown or diverged ancestry falls back to numeric comparison`() {
        assertTrue(UpdateEligibility.shouldOfferUpdate("V0.5.9.1", "0.5.9", null))
        assertTrue(
            UpdateEligibility.shouldOfferUpdate("V0.5.9.1", "0.5.9", CommitComparisonStatus.DIVERGED)
        )
        assertFalse(UpdateEligibility.shouldOfferUpdate("V0.5.9", "0.5.9", null))
    }
}
