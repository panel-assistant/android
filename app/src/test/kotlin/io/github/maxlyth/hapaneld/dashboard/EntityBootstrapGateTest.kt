package io.github.maxlyth.hapaneld.dashboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EntityBootstrapGateTest {
    @Test fun emptyAutomaticLearnerHoldsRenderer() {
        assertTrue(shouldHoldRendererForEntityBootstrap(
            learningEnabled = true,
            filterEnabled = false,
        ))
    }

    @Test fun intentionallyEmptyAutomaticFilterMayRenderUnavailableEntities() {
        assertFalse(shouldHoldRendererForEntityBootstrap(
            learningEnabled = true,
            filterEnabled = true,
        ))
    }

    @Test fun populatedAutomaticFilterMayRender() {
        assertFalse(shouldHoldRendererForEntityBootstrap(
            learningEnabled = true,
            filterEnabled = true,
        ))
    }

    @Test fun manualModeRetainsExistingUnfilteredBehaviour() {
        assertFalse(shouldHoldRendererForEntityBootstrap(
            learningEnabled = false,
            filterEnabled = false,
        ))
    }

    @Test fun learnerCompletionOnlyReloadsSelectedBuiltinRenderer() {
        assertTrue(shouldReloadBuiltinAfterEntityFilterChange("builtin", "builtin"))
        assertFalse(shouldReloadBuiltinAfterEntityFilterChange("", "builtin"))
        assertFalse(shouldReloadBuiltinAfterEntityFilterChange("other.renderer", "builtin"))
        // While the wizard's filter question is still open on a first run, the renderer is deliberately
        // held — the answer route performs the one release, so the config commit's own change must not
        // add a second relaunch (observed 350 ms apart on hardware).
        assertFalse(shouldReloadBuiltinAfterEntityFilterChange(
            "builtin", "builtin", setupEntityFilterAnswered = false, setupEverCompleted = false))
        assertTrue(shouldReloadBuiltinAfterEntityFilterChange(
            "builtin", "builtin", setupEntityFilterAnswered = true, setupEverCompleted = false))
        assertTrue(shouldReloadBuiltinAfterEntityFilterChange(
            "builtin", "builtin", setupEntityFilterAnswered = false, setupEverCompleted = true))
    }

}
