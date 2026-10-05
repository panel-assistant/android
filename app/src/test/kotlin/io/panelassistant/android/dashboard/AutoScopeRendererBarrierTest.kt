package io.panelassistant.android.dashboard

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The exact case the review named: `Auto` selected, a previous sync on record, an enabled allow-list
 * retained for `/alpha`, and the account default now resolving to `/beta`. Scheduling a scan fixes
 * where the panel ends up; this fixes what it may install before the scan answers.
 */
class AutoScopeRendererBarrierTest {

    private val auto = ""

    @Before fun reset() = AutoScopeVerification.invalidate()

    @After fun clear() = AutoScopeVerification.invalidate()

    @Test fun `an office allow-list cannot reach document start while Auto is unverified`() {
        assertTrue(
            "the retained list belongs to a dashboard nobody has vouched for this process",
            shouldHoldRendererForEntityBootstrap(
                learningEnabled = true,
                filterEnabled = true,
                autoScopeUnverified = AutoScopeVerification.unverified(auto),
            ),
        )
    }

    @Test fun `the barrier lifts only once a live resolution has answered`() {
        AutoScopeVerification.markVerified(auto)
        assertFalse(
            shouldHoldRendererForEntityBootstrap(
                learningEnabled = true,
                filterEnabled = true,
                autoScopeUnverified = AutoScopeVerification.unverified(auto),
            ),
        )
    }

    @Test fun `a delayed or failed resolution leaves the renderer held`() {
        // Nothing marks verification on a resolution that never returns or throws, so the absence of a
        // positive signal is what keeps the hold — not a timeout anyone has to remember to arm.
        assertTrue(AutoScopeVerification.unverified(auto))
        assertTrue(
            shouldHoldRendererForEntityBootstrap(
                learningEnabled = true, filterEnabled = true,
                autoScopeUnverified = AutoScopeVerification.unverified(auto),
            ),
        )
    }

    @Test fun `verification is scoped to the setting it answered`() {
        // Answering for Auto must not vouch for a later Auto whose account default has since moved,
        // and invalidation is what re-opens it.
        AutoScopeVerification.markVerified(auto)
        AutoScopeVerification.invalidate()
        assertTrue(AutoScopeVerification.unverified(auto))
    }

    @Test fun `an explicitly configured dashboard is never held for verification`() {
        // Its scope is configured rather than discovered, so a live read could add nothing and holding
        // would strand every explicit panel behind a round trip it does not need.
        for (explicit in listOf("/lovelace", "/alpha/beta")) {
            assertFalse(explicit, AutoScopeVerification.unverified(explicit))
            assertFalse(
                explicit,
                shouldHoldRendererForEntityBootstrap(
                    learningEnabled = true, filterEnabled = true,
                    autoScopeUnverified = AutoScopeVerification.unverified(explicit),
                ),
            )
        }
    }

    @Test fun `an absent allow-list still holds regardless of verification`() {
        AutoScopeVerification.markVerified(auto)
        assertTrue(
            "the pre-existing invariant must survive this change",
            shouldHoldRendererForEntityBootstrap(
                learningEnabled = true, filterEnabled = false,
                autoScopeUnverified = AutoScopeVerification.unverified(auto),
            ),
        )
    }
}
