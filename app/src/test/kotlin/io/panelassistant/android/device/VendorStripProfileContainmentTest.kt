package io.panelassistant.android.device

import io.panelassistant.android.device.profile.BundledProfileFixtures
import org.junit.Assert.assertTrue
import org.junit.Test

/** Retained vendor adoption profiles must preserve safe home hand-back behavior. */
class VendorStripProfileContainmentTest {

    private val profile = BundledProfileFixtures.profile("tpa10")

    @Test fun `the stock launcher is named by the profile`() {
        // Called out on its own because this is the package whose absence produces FallbackHome, and the
        // other adoption entries must not obscure its required presence.
        assertTrue(
            "tpa10.yaml must name the stock launcher so a tamed panel can be handed its home screen back",
            profile.tameVendorCandidates.any { it.pkg == "com.smartos.xinch.launcher" },
        )
    }

    @Test fun `no profile package is a default tame pick`() {
        // Adoption authority is not a recommendation to disable. The launcher and system UI are listed so a
        // provisioner-tamed panel can be REPAIRED; a `recommended` importance would offer to disable the
        // panel's own home screen from the vendor-packages picker.
        val candidates = profile.tameVendorCandidates
        assertTrue("the profile must offer vendor packages", candidates.isNotEmpty())
        assertTrue(
            "no TPA10 vendor package may be a default tame pick",
            candidates.none { it.defaultTame },
        )
    }
}
