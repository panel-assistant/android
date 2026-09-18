package io.github.maxlyth.hapaneld.migration

import io.github.maxlyth.hapaneld.migration.SuccessorHandoff.InstalledSuccessor
import io.github.maxlyth.hapaneld.migration.SuccessorHandoff.Outcome
import io.github.maxlyth.hapaneld.util.UpdateChecker
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SuccessorHandoffTest {
    private val successor = "io.panelassistant.android"

    private class FakePorts(
        var retired: Boolean = false,
        var helperRefusals: MutableList<String?> = mutableListOf(null, null),
        var installed: InstalledSuccessor? = null,
        var assetUrl: String? = "https://example.invalid/panel-assistant-v1.apk",
        var installFailure: String? = null,
        var installedAfterInstall: InstalledSuccessor? = InstalledSuccessor("1.0", setOf(SIGNER)),
        var companionWriteSticks: Boolean = true,
        var launchResult: Boolean = true,
        var tokenDelivered: Boolean = true,
    ) : SuccessorHandoff.Ports {
        val events = mutableListOf<String>()
        private var companions: Set<String> = setOf("com.example.kept")

        override fun retired(): Boolean = retired
        override fun helperRefusal(): String? {
            events += "helper"
            return if (helperRefusals.isEmpty()) null else helperRefusals.removeAt(0)
        }
        override fun installedSuccessor(): InstalledSuccessor? = installed
        override fun trustedSigner(): String = SIGNER
        override fun ownVersion(): String = "1.0"
        override fun compareVersions(left: String, right: String): Int? = UpdateChecker.compareVersions(left, right)
        override fun successorAssetUrl(): String? = assetUrl.also { events += "resolve" }
        override suspend fun installSuccessor(url: String): String? {
            events += "install $url"
            if (installFailure == null) installed = installedAfterInstall
            return installFailure
        }
        override fun companionPackages(): Set<String> = companions
        override fun addCompanionPackage(pkg: String) {
            events += "companion $pkg"
            if (companionWriteSticks) companions = companions + pkg
        }
        override fun launchSuccessor(): Boolean = launchResult.also { events += "launch" }
        override fun deliverReleaseToken(): Boolean = tokenDelivered.also { events += "token" }
        fun companionsNow(): Set<String> = companions
    }

    private companion object {
        val SIGNER = "a".repeat(64)
        val OTHER_SIGNER = "b".repeat(64)
    }

    private fun offer(ports: FakePorts) = runBlocking { SuccessorHandoff(ports).offer(successor) }

    @Test fun installsConfirmsBothPreconditionsLaunchesThenDeliversTheTokenInThatOrder() {
        val ports = FakePorts()

        assertEquals(Outcome.Launched, offer(ports))

        assertEquals(
            listOf(
                "helper",
                "resolve",
                "install https://example.invalid/panel-assistant-v1.apk",
                "helper",
                "companion $successor",
                "launch",
                "token",
            ),
            ports.events,
        )
        assertEquals(setOf("com.example.kept", successor), ports.companionsNow())
    }

    @Test fun aRetiredBridgeOffersNothing() {
        val ports = FakePorts(retired = true)

        assertEquals(Outcome.Retired, offer(ports))
        assertEquals(emptyList<String>(), ports.events)
    }

    @Test fun anUnconfirmedHelperLeavesThePanelWithOnePackage() {
        val ports = FakePorts(helperRefusals = mutableListOf("running helper is not the bundled build"))

        assertEquals(Outcome.HelperNotConfirmed("running helper is not the bundled build"), offer(ports))
        assertEquals(listOf("helper"), ports.events)
    }

    @Test fun aHelperReplacedDuringTheInstallStillBlocksTheLaunch() {
        val ports = FakePorts(helperRefusals = mutableListOf(null, "helper did not report a dual-identity status"))

        assertEquals(Outcome.HelperNotConfirmed("helper did not report a dual-identity status"), offer(ports))
        assertFalse(ports.events.contains("launch"))
        assertEquals(setOf("com.example.kept"), ports.companionsNow())
    }

    @Test fun aReleaseWithoutASuccessorAssetIsNotAnError() {
        val ports = FakePorts(assetUrl = null)

        assertEquals(Outcome.NoSuccessorAsset, offer(ports))
        assertEquals(listOf("helper", "resolve"), ports.events)
    }

    @Test fun aFailedInstallDoesNotLaunch() {
        val ports = FakePorts(installFailure = "refused (signer mismatch)")

        assertEquals(Outcome.InstallFailed("refused (signer mismatch)"), offer(ports))
        assertFalse(ports.events.contains("launch"))
    }

    @Test fun anInstallThatDidNotProduceThisVersionDoesNotLaunch() {
        val ports = FakePorts(installedAfterInstall = InstalledSuccessor("0.9", setOf(SIGNER)))

        assertEquals(Outcome.InstallFailed("installed successor was not confirmed"), offer(ports))
        assertFalse(ports.events.contains("launch"))
    }

    @Test fun anInstallThatLeftNoPackageDoesNotLaunch() {
        val ports = FakePorts(installedAfterInstall = null)

        assertEquals(Outcome.InstallFailed("installed successor was not confirmed"), offer(ports))
        assertFalse(ports.events.contains("launch"))
    }

    @Test fun aSuccessorIdUnderAnotherSignerIsNeverLaunchedReplacedOrHandedTheToken() {
        val ports = FakePorts(installed = InstalledSuccessor("1.0", setOf(OTHER_SIGNER)))

        assertEquals(Outcome.UntrustedSuccessor, offer(ports))
        assertEquals(listOf("helper"), ports.events)
    }

    @Test fun aSuccessorWithAnExtraSignerIsUntrusted() {
        val ports = FakePorts(installed = InstalledSuccessor("1.0", setOf(SIGNER, OTHER_SIGNER)))

        assertEquals(Outcome.UntrustedSuccessor, offer(ports))
    }

    @Test fun aSuccessorWithNoSignerIsUntrusted() {
        val ports = FakePorts(installed = InstalledSuccessor("1.0", emptySet()))

        assertEquals(Outcome.UntrustedSuccessor, offer(ports))
    }

    @Test fun aCompanionWriteThatDoesNotReadBackBlocksTheLaunch() {
        val ports = FakePorts(companionWriteSticks = false)

        assertEquals(Outcome.CompanionNotHonoured, offer(ports))
        assertFalse(ports.events.contains("launch"))
    }

    @Test fun anInstalledSuccessorOfThisVersionIsRelaunchedWithoutReinstalling() {
        val ports = FakePorts(installed = InstalledSuccessor("1.0", setOf(SIGNER.uppercase())))

        assertEquals(Outcome.Launched, offer(ports))
        assertEquals(listOf("helper", "helper", "companion $successor", "launch", "token"), ports.events)
    }

    @Test fun anOlderSuccessorIsReplacedBeforeLaunch() {
        val ports = FakePorts(installed = InstalledSuccessor("0.9", setOf(SIGNER)))

        assertEquals(Outcome.Launched, offer(ports))
        assertTrue(ports.events.any { it.startsWith("install ") })
    }

    @Test fun aNewerSuccessorIsKeptAndNeverDowngraded() {
        val ports = FakePorts(installed = InstalledSuccessor("1.1", setOf(SIGNER)))

        assertEquals(Outcome.Launched, offer(ports))
        assertFalse(ports.events.any { it.startsWith("install ") })
    }

    @Test fun aRetryDoesNotWriteTheCompanionTwice() {
        val ports = FakePorts(installed = InstalledSuccessor("1.0", setOf(SIGNER)))
        offer(ports)
        ports.events.clear()

        assertEquals(Outcome.Launched, offer(ports))
        assertEquals(listOf("helper", "helper", "launch", "token"), ports.events)
    }

    @Test fun aFailedStartIsReportedAndDeliversNoToken() {
        val ports = FakePorts(launchResult = false)

        assertEquals(Outcome.LaunchFailed, offer(ports))
        assertFalse(ports.events.contains("token"))
    }

    @Test fun anUndeliveredTokenIsReportedSoTheNextPassRetries() {
        val ports = FakePorts(tokenDelivered = false)

        assertEquals(Outcome.TokenNotDelivered, offer(ports))
    }
}
