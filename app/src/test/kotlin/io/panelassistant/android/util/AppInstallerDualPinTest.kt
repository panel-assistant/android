package io.panelassistant.android.util

import io.panelassistant.android.AppIdentity
import io.panelassistant.android.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

/** The two panel-app pins: one signer, two packages, and neither APK satisfies the other's pin. */
class AppInstallerDualPinTest {
    private val signer = AppInstaller.LEGACY.certSha256

    @Test fun bothIdentitiesPinTheSameReleaseSignerUnderTheirOwnPackage() {
        assertEquals("io.github.maxlyth.hapaneld", AppInstaller.LEGACY.pkg)
        assertEquals("io.panelassistant.android", AppInstaller.SUCCESSOR.pkg)
        assertEquals(
            "ac6193307fb0b70113aae205d7549406f96e063bc5491b67b1d5694a34b0e339",
            AppInstaller.LEGACY.certSha256,
        )
        assertEquals(AppInstaller.LEGACY.certSha256, AppInstaller.SUCCESSOR.certSha256)
    }

    @Test fun ownPinFollowsTheBuildIdentity() {
        assertSame(AppInstaller.LEGACY, AppInstaller.ownPin(AppIdentity.LEGACY))
        assertSame(AppInstaller.SUCCESSOR, AppInstaller.ownPin(AppIdentity.SUCCESSOR))
        assertEquals(BuildConfig.APPLICATION_ID, AppInstaller.HA_PANELD.pkg)
        assertThrows(IllegalStateException::class.java) { AppInstaller.ownPin("com.example.other") }
    }

    @Test fun successorApkSatisfiesOnlyTheSuccessorPin() {
        assertNull(AppInstaller.pinRefusal(AppIdentity.SUCCESSOR, listOf(signer), AppInstaller.SUCCESSOR))
        assertEquals(
            "package io.panelassistant.android not allowlisted",
            AppInstaller.pinRefusal(AppIdentity.SUCCESSOR, listOf(signer), AppInstaller.LEGACY),
        )
    }

    @Test fun legacyApkNeverSatisfiesTheSuccessorPin() {
        assertNull(AppInstaller.pinRefusal(AppIdentity.LEGACY, listOf(signer), AppInstaller.LEGACY))
        assertEquals(
            "package io.github.maxlyth.hapaneld not allowlisted",
            AppInstaller.pinRefusal(AppIdentity.LEGACY, listOf(signer), AppInstaller.SUCCESSOR),
        )
    }

    @Test fun successorPackageUnderAnotherSignerIsRefused() {
        assertEquals(
            "signer mismatch",
            AppInstaller.pinRefusal(AppIdentity.SUCCESSOR, listOf("0".repeat(64)), AppInstaller.SUCCESSOR),
        )
        assertEquals(
            "no signature",
            AppInstaller.pinRefusal(AppIdentity.SUCCESSOR, emptyList(), AppInstaller.SUCCESSOR),
        )
    }

    @Test fun theMigrationTrustsTheReleaseSignerUnlessABuildCarriesAWellFormedOverride() {
        assertEquals(signer, AppInstaller.migrationSigner(""))
        assertEquals(signer, AppInstaller.migrationSigner("not-a-digest"))
        assertEquals(signer, AppInstaller.migrationSigner("D".repeat(64)))
        assertEquals("d".repeat(64), AppInstaller.migrationSigner("d".repeat(64)))
    }

    @Test fun theMigrationPinNamesTheRequestedIdentityUnderTheMigrationSigner() {
        val pin = AppInstaller.migrationPin(AppIdentity.SUCCESSOR)
        assertEquals(AppIdentity.SUCCESSOR, pin.pkg)
        assertEquals(AppInstaller.MIGRATION_SIGNER, pin.certSha256)
        assertEquals(AppIdentity.LEGACY, AppInstaller.migrationPin(AppIdentity.LEGACY).pkg)
        assertThrows(IllegalStateException::class.java) { AppInstaller.migrationPin("com.example.other") }
    }

    @Test fun selfUpdateStaysOnTheReleaseSignerWhateverTheMigrationTrusts() {
        assertEquals(signer, AppInstaller.HA_PANELD.certSha256)
    }

    @Test fun pinnedSignerMatchesWithoutRegardToCase() {
        assertNull(
            AppInstaller.pinRefusal(AppIdentity.SUCCESSOR, listOf(signer.uppercase()), AppInstaller.SUCCESSOR),
        )
    }
}
