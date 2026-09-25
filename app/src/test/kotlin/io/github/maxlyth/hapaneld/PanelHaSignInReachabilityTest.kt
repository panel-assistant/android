package io.github.maxlyth.hapaneld

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * First run must be completable from the panel itself, with no browser and no ADB.
 *
 * The deadlock this guards was observed on hardware: saving a Home Assistant URL relaunches
 * DashboardActivity, the readiness gate rejects it because no token exists yet, and it bounces to the
 * QR screen — while the on-panel sign-in that would mint that token sits *behind* the same gate. The
 * panel visibly reloaded several times and never showed a login. A second, quieter instance of the same
 * shape sat behind it: the entity-bootstrap hold waits for data that needs an authenticated connection,
 * and it ran before the sign-in branch, so fixing only the gate would have moved the deadlock rather
 * than removed it.
 *
 * The ordering itself lives in DashboardActivity and is not exercised here; this executes the predicate
 * that decides whether sign-in is pending.
 */
class PanelHaSignInReachabilityTest {
    @Test fun aConfiguredUrlWithoutCredentialsIsTheOnlySignInPendingState() {
        assertTrue(haSignInPending("http://ha.local:8123", "", ""))
        // Either credential is enough to render, so neither is a sign-in-pending state.
        assertFalse(haSignInPending("http://ha.local:8123", "token", ""))
        assertFalse(haSignInPending("http://ha.local:8123", "", "refresh"))
        // No URL means nothing to sign in to: this must still strand to the configure surface.
        assertFalse(haSignInPending("", "", ""))
        assertFalse(haSignInPending("   ", "", ""))
    }
}
