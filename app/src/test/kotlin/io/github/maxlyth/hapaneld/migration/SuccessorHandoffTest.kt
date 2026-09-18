package io.github.maxlyth.hapaneld.migration

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SuccessorHandoffTest {
    private val successor = "io.panelassistant.android"

    private class FakePorts(
        var retired: Boolean = false,
        var helperRefusals: MutableList<String?> = mutableListOf(null, null),
        var installed: String? = null,
        var assetUrl: String? = "https://example.invalid/panel-assistant-v1.apk",
        var installFailure: String? = null,
        var installedAfterInstall: String? = "1.0",
        var companionWriteSticks: Boolean = true,
        var launchResult: Boolean = true,
    ) : SuccessorHandoff.Ports {
        val events = mutableListOf<String>()
        private var companions: Set<String> = setOf("com.example.kept")

        override fun retired(): Boolean = retired
        override fun helperRefusal(): String? {
            events += "helper"
            return if (helperRefusals.isEmpty()) null else helperRefusals.removeAt(0)
        }
        override fun installedSuccessorVersion(): String? = installed
        override fun ownVersion(): String = "1.0"
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
        fun companionsNow(): Set<String> = companions
    }

    private fun offer(ports: FakePorts) = runBlocking { SuccessorHandoff(ports).offer(successor) }

    @Test fun installsConfirmsBothPreconditionsThenLaunchesInThatOrder() {
        val ports = FakePorts()

        assertEquals(SuccessorHandoff.Outcome.Launched, offer(ports))

        assertEquals(
            listOf(
                "helper",
                "resolve",
                "install https://example.invalid/panel-assistant-v1.apk",
                "helper",
                "companion $successor",
                "launch",
            ),
            ports.events,
        )
        assertEquals(setOf("com.example.kept", successor), ports.companionsNow())
    }

    @Test fun aRetiredBridgeOffersNothing() {
        val ports = FakePorts(retired = true)

        assertEquals(SuccessorHandoff.Outcome.Retired, offer(ports))
        assertEquals(emptyList<String>(), ports.events)
    }

    @Test fun anUnconfirmedHelperLeavesThePanelWithOnePackage() {
        val ports = FakePorts(helperRefusals = mutableListOf("running helper is not the bundled build"))

        assertEquals(
            SuccessorHandoff.Outcome.HelperNotConfirmed("running helper is not the bundled build"),
            offer(ports),
        )
        assertEquals(listOf("helper"), ports.events)
    }

    @Test fun aHelperReplacedDuringTheInstallStillBlocksTheLaunch() {
        val ports = FakePorts(helperRefusals = mutableListOf(null, "helper did not report a dual-identity status"))

        assertEquals(
            SuccessorHandoff.Outcome.HelperNotConfirmed("helper did not report a dual-identity status"),
            offer(ports),
        )
        assertEquals(false, ports.events.contains("launch"))
        assertEquals(setOf("com.example.kept"), ports.companionsNow())
    }

    @Test fun aReleaseWithoutASuccessorAssetIsNotAnError() {
        val ports = FakePorts(assetUrl = null)

        assertEquals(SuccessorHandoff.Outcome.NoSuccessorAsset, offer(ports))
        assertEquals(listOf("helper", "resolve"), ports.events)
    }

    @Test fun aFailedInstallDoesNotLaunch() {
        val ports = FakePorts(installFailure = "refused (signer mismatch)")

        assertEquals(SuccessorHandoff.Outcome.InstallFailed("refused (signer mismatch)"), offer(ports))
        assertEquals(false, ports.events.contains("launch"))
    }

    @Test fun anInstallThatDidNotProduceThisVersionDoesNotLaunch() {
        val ports = FakePorts(installedAfterInstall = "0.9")

        assertEquals(
            SuccessorHandoff.Outcome.InstallFailed("installed successor version was not confirmed"),
            offer(ports),
        )
        assertEquals(false, ports.events.contains("launch"))
    }

    @Test fun aCompanionWriteThatDoesNotReadBackBlocksTheLaunch() {
        val ports = FakePorts(companionWriteSticks = false)

        assertEquals(SuccessorHandoff.Outcome.CompanionNotHonoured, offer(ports))
        assertEquals(false, ports.events.contains("launch"))
    }

    @Test fun anInstalledSuccessorOfThisVersionIsRelaunchedWithoutReinstalling() {
        val ports = FakePorts(installed = "1.0")

        assertEquals(SuccessorHandoff.Outcome.Launched, offer(ports))
        assertEquals(listOf("helper", "helper", "companion $successor", "launch"), ports.events)
    }

    @Test fun aSuccessorOfAnotherVersionIsReplacedBeforeLaunch() {
        val ports = FakePorts(installed = "0.9")

        assertEquals(SuccessorHandoff.Outcome.Launched, offer(ports))
        assertEquals(true, ports.events.any { it.startsWith("install ") })
    }

    @Test fun aRetryDoesNotWriteTheCompanionTwice() {
        val ports = FakePorts(installed = "1.0")
        offer(ports)
        ports.events.clear()

        assertEquals(SuccessorHandoff.Outcome.Launched, offer(ports))
        assertEquals(listOf("helper", "helper", "launch"), ports.events)
    }

    @Test fun aFailedStartIsReportedAndRetriedLater() {
        val ports = FakePorts(launchResult = false)

        assertEquals(SuccessorHandoff.Outcome.LaunchFailed, offer(ports))
    }
}
