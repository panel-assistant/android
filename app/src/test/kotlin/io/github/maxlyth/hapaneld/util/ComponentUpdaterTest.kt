package io.github.maxlyth.hapaneld.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the consolidated resolve -> compare -> decide pipeline. Absorbs the decision assertions that were
 * previously hand-rolled inside SelfUpdater.checkAndUpdate, UpdateChecker.check (paneld + companion branches),
 * CompanionInstaller.shouldInstallTarget and UpdateChecker.filterCurrent, so those callers can route through
 * one pipeline without changing behaviour.
 */
class ComponentUpdaterTest {
    private fun target(v: String) = ComponentUpdater.Target(v, "apk://$v", "notes://$v")

    // ---- isUpdate: the shared compare-and-decide core (identity + variant-normalized) ----

    @Test fun isUpdateTrueWhenStrictlyNewer() {
        assertTrue(ComponentUpdater.isUpdate("0.9.3", "0.9.2"))
        assertTrue(ComponentUpdater.isUpdate("0.9.2", "0.9.2-rc4"))
        assertTrue(ComponentUpdater.isUpdate("0.9.2-rc4", "0.9.2-rc3"))
        assertTrue(ComponentUpdater.isUpdate("0.9.2-rc1", "0.9.2-beta9"))
    }

    @Test fun isUpdateFalseWhenEqualOrOlder() {
        assertFalse(ComponentUpdater.isUpdate("0.9.2", "0.9.2"))
        assertFalse(ComponentUpdater.isUpdate("2026.5.4", "2026.5.4.0")) // trailing-zero equality
        assertFalse(ComponentUpdater.isUpdate("0.9.2-rc3", "0.9.2"))
        assertFalse(ComponentUpdater.isUpdate("0.9.1", "0.9.2"))
    }

    @Test fun isUpdateFailsClosedOnMalformedVersion() {
        // A malformed candidate must never read as newer (would otherwise install junk / flag a false update).
        assertFalse(ComponentUpdater.isUpdate("release-next", "0.9.2"))
        assertFalse(ComponentUpdater.isUpdate("2026.x.4", "2026.5.4"))
    }

    @Test fun forceApplysEvenWhenNotNewer() {
        // The deliberate manual / channel-switch downgrade path (SelfUpdater force, CompanionInstaller force).
        assertTrue(ComponentUpdater.isUpdate("0.9.1", "0.9.2", force = true))
        assertTrue(ComponentUpdater.isUpdate("0.9.2", "0.9.2", force = true))
        assertTrue(ComponentUpdater.isUpdate("release-next", "0.9.2", force = true))
    }

    @Test fun installedNormalizeStripsVariantBeforeComparing() {
        val strip = UpdateChecker::stripVariant
        assertFalse(ComponentUpdater.isUpdate("2026.6.5", "2026.6.5-minimal", installedNormalize = strip))
        assertTrue(ComponentUpdater.isUpdate("2026.6.5", "2026.5.3-minimal", installedNormalize = strip))
        assertFalse(ComponentUpdater.isUpdate("2026.5.4", "2026.5.4-full", installedNormalize = strip))
    }

    @Test fun blankInstalledIsNeverNewerButForceStillApplies() {
        assertFalse(ComponentUpdater.isUpdate("2026.6.5", "", installedNormalize = UpdateChecker::stripVariant))
        assertTrue(ComponentUpdater.isUpdate("2026.6.5", "", force = true))
    }

    // ---- resolveUpdate: the full pipeline (resolve -> compare -> decide) ----

    @Test fun unresolvedWhenLookupReturnsNull() {
        assertEquals(ComponentUpdater.Outcome.Unresolved, ComponentUpdater.resolveUpdate("0.9.2") { null })
    }

    @Test fun upToDateWhenResolvedButNotNewer() {
        assertEquals(ComponentUpdater.Outcome.UpToDate, ComponentUpdater.resolveUpdate("0.9.2") { target("0.9.2") })
        assertEquals(ComponentUpdater.Outcome.UpToDate, ComponentUpdater.resolveUpdate("0.9.3") { target("0.9.2") })
    }

    @Test fun updateWhenResolvedAndNewer() {
        val outcome = ComponentUpdater.resolveUpdate("0.9.2") { target("0.9.3") }
        assertEquals(ComponentUpdater.Outcome.Update(target("0.9.3")), outcome)
    }

    @Test fun forceProducesUpdateEvenWhenNotNewer() {
        assertEquals(
            ComponentUpdater.Outcome.Update(target("0.9.1")),
            ComponentUpdater.resolveUpdate("0.9.2", force = true) { target("0.9.1") },
        )
        // ...but a failed lookup is still Unresolved even under force.
        assertEquals(ComponentUpdater.Outcome.Unresolved, ComponentUpdater.resolveUpdate("0.9.2", force = true) { null })
    }

    @Test fun resolveUpdateAppliesInstalledNormalize() {
        val strip = UpdateChecker::stripVariant
        assertEquals(
            ComponentUpdater.Outcome.UpToDate,
            ComponentUpdater.resolveUpdate("2026.6.5-minimal", installedNormalize = strip) { target("2026.6.5") },
        )
        assertEquals(
            ComponentUpdater.Outcome.Update(target("2026.6.5")),
            ComponentUpdater.resolveUpdate("2026.5.3-minimal", installedNormalize = strip) { target("2026.6.5") },
        )
    }

    @Test fun resolveIsInvokedAtMostOnce() {
        var calls = 0
        ComponentUpdater.resolveUpdate("0.9.2") { calls++; target("0.9.3") }
        assertEquals(1, calls)
    }
}
