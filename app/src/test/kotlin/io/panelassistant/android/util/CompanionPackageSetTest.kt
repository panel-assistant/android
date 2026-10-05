package io.panelassistant.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the single recognized Companion package set on its one owner ([CompanionInstaller]). Formerly
 * this exact ordered pair lived re-copied in PanelInfo, CompanionDataOperationGate, TameController and
 * the diagnostics dump; those readers now share this constant, so this test guards the dashboard_entity and
 * order every one of them depends on.
 */
class CompanionPackageSetTest {
    private val full = CompanionInstaller.FULL_PKG to "Home Assistant Companion (full)"
    private val minimal = CompanionInstaller.MINIMAL_PKG to "Home Assistant Companion (minimal)"

    private fun choices(vararg packages: String) = CompanionInstaller.rendererChoices(packages.toSet())
        .map { it.packageName to it.label }

    private fun detected(vararg packages: String): Set<String> =
        CompanionInstaller.installedPackages { packageName ->
            check(packageName in packages) { "not installed" }
        }

    @Test fun recognizedSetIsFullThenMinimal() {
        assertEquals("io.homeassistant.companion.android", CompanionInstaller.FULL_PKG)
        assertEquals("io.homeassistant.companion.android.minimal", CompanionInstaller.MINIMAL_PKG)
        assertEquals(
            listOf(CompanionInstaller.FULL_PKG, CompanionInstaller.MINIMAL_PKG),
            CompanionInstaller.SUPPORTED_PACKAGES,
        )
    }

    @Test fun membershipMatchesTheRecognizedSet() {
        assertTrue(CompanionInstaller.FULL_PKG in CompanionInstaller.SUPPORTED_PACKAGES)
        assertTrue(CompanionInstaller.MINIMAL_PKG in CompanionInstaller.SUPPORTED_PACKAGES)
        assertEquals(2, CompanionInstaller.SUPPORTED_PACKAGES.size)
    }

    @Test fun installedPackageProbeChecksBothVariantsAfterAnIndividualFailure() {
        val queried = mutableListOf<String>()
        val installed = CompanionInstaller.installedPackages { packageName ->
            queried += packageName
            check(packageName == CompanionInstaller.MINIMAL_PKG) { "not installed" }
        }

        assertEquals(CompanionInstaller.SUPPORTED_PACKAGES, queried)
        assertEquals(linkedSetOf(CompanionInstaller.MINIMAL_PKG), installed)
    }

    @Test fun installedPackageProbeReturnsNeitherVariant() {
        assertEquals(emptySet<String>(), detected())
    }

    @Test fun installedPackageProbeReturnsTheFullVariant() {
        assertEquals(linkedSetOf(CompanionInstaller.FULL_PKG), detected(CompanionInstaller.FULL_PKG))
    }

    @Test fun installedPackageProbeReturnsTheMinimalVariant() {
        assertEquals(linkedSetOf(CompanionInstaller.MINIMAL_PKG), detected(CompanionInstaller.MINIMAL_PKG))
    }

    @Test fun installedPackageProbeReturnsBothVariantsInStableOrder() {
        assertEquals(
            linkedSetOf(CompanionInstaller.FULL_PKG, CompanionInstaller.MINIMAL_PKG),
            detected(CompanionInstaller.MINIMAL_PKG, CompanionInstaller.FULL_PKG),
        )
    }

    @Test fun rendererChoicesAreEmptyWhenNeitherVariantIsInstalled() {
        assertEquals(emptyList<Pair<String, String>>(), choices())
    }

    @Test fun installedFullCompanionIsAnAvailableRenderer() {
        assertEquals(listOf(full), choices(CompanionInstaller.FULL_PKG))
    }

    @Test fun installedMinimalCompanionIsAnAvailableRenderer() {
        assertEquals(listOf(minimal), choices(CompanionInstaller.MINIMAL_PKG))
    }

    @Test fun bothInstalledCompanionsHaveStableFullThenMinimalOrder() {
        assertEquals(listOf(full, minimal), choices(CompanionInstaller.MINIMAL_PKG, CompanionInstaller.FULL_PKG))
    }

    @Test fun rendererChoicesNeverPromoteAnArbitraryInstalledApp() {
        assertEquals(
            emptyList<CompanionInstaller.RendererChoice>(),
            CompanionInstaller.rendererChoices(setOf("com.example.launchable")),
        )
    }
}
