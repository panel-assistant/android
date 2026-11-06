package io.panelassistant.android.util

import io.panelassistant.android.util.BundledHelperShellFixtures.incumbentBuild
import io.panelassistant.android.util.BundledHelperShellFixtures.stagedBuild
import org.junit.Assert.assertEquals
import org.junit.Test

/** Exercises the mutation boundary ensureCurrent calls, without Android asset or su launch adapters. */
class BundledHelperReplacementCallerTest {
    private val identity = GuardDbMaintenanceProtocol.SelfIdentity(30, "e".repeat(64), incumbentBuild)
    private val record = requireNotNull(parseBundledLegacyTakeoverRecord(
        "OK LEGACYTAKEOVER 1 system ${"c".repeat(64)} 10 ${"d".repeat(64)} 20 644 " +
            "${"f".repeat(64)} $incumbentBuild ${identity.sha256} ${identity.bytes}",
    ))

    @Test fun `a running app helper stages and retires once then cleans stale custody`() {
        val calls = Calls()
        assertEquals(BundledHelperInstaller.Result.INSTALLED, calls.install())
        assertEquals(listOf("stage", "guarded", "cleanup"), calls.actions)
    }

    @Test fun `released and absent helpers use their own single install authority`() {
        for ((mode, authority) in listOf(
            BundledHelperReplacementMode.RELEASED_LEGACY_TAKEOVER to "legacy",
            BundledHelperReplacementMode.FRESH_INSTALL to "fresh",
        )) {
            val calls = Calls()
            assertEquals(BundledHelperInstaller.Result.INSTALLED, calls.install(mode = mode, self = null))
            assertEquals(listOf("stage", authority), calls.actions)
        }
    }

    @Test fun `a boot helper or changed serving build refuses before staging or retirement`() {
        for (self in listOf(null, identity.copy(buildId = "f".repeat(64)))) {
            val calls = Calls()
            assertEquals(BundledHelperInstaller.Result.REPROVISION_REQUIRED, calls.install(self = self))
            assertEquals(emptyList<String>(), calls.actions)
        }
    }

    @Test fun `the exact live takeover candidate may update across bundled builds`() {
        val calls = Calls()
        assertEquals(BundledHelperInstaller.Result.INSTALLED, calls.install(prior = record))
        assertEquals(listOf("stage", "guarded", "cleanup"), calls.actions)
        for (self in listOf(identity.copy(bytes = 31), identity.copy(sha256 = "0".repeat(64)))) {
            val refused = Calls()
            assertEquals(
                BundledHelperInstaller.Result.REPROVISION_REQUIRED,
                refused.install(self = self, prior = record),
            )
            assertEquals(emptyList<String>(), refused.actions)
        }
        val boot = Calls()
        assertEquals(
            BundledHelperInstaller.Result.REPROVISION_REQUIRED,
            boot.install(mode = BundledHelperReplacementMode.RELEASED_LEGACY_TAKEOVER, self = null, prior = record),
        )
        assertEquals(emptyList<String>(), boot.actions)
    }

    @Test fun `failed staging or unsettled retirement preserves takeover recovery custody`() {
        val unstaged = Calls(stageOk = false)
        assertEquals(BundledHelperInstaller.Result.FAILED, unstaged.install(prior = record))
        assertEquals(listOf("stage"), unstaged.actions)
        for (settlement in BundledHelperReplacementSettlement.entries.filter {
            it != BundledHelperReplacementSettlement.INSTALLED
        }) {
            val calls = Calls(settlement = settlement)
            assertEquals(
                if (settlement == BundledHelperReplacementSettlement.BLOCKED_ACTIVE) {
                    BundledHelperInstaller.Result.BLOCKED_ACTIVE
                } else BundledHelperInstaller.Result.FAILED,
                calls.install(prior = record),
            )
            assertEquals(listOf("stage", "guarded"), calls.actions)
        }
    }

    @Test fun `failed stale custody cleanup cannot report a completed update`() {
        val calls = Calls(cleanupOk = false)
        assertEquals(BundledHelperInstaller.Result.FAILED, calls.install(prior = record))
        assertEquals(listOf("stage", "guarded", "cleanup"), calls.actions)
    }

    private inner class Calls(
        val stageOk: Boolean = true,
        val settlement: BundledHelperReplacementSettlement = BundledHelperReplacementSettlement.INSTALLED,
        val cleanupOk: Boolean = true,
    ) {
        val actions = mutableListOf<String>()
        fun install(
            mode: BundledHelperReplacementMode = BundledHelperReplacementMode.GUARDED_RETIRE,
            self: GuardDbMaintenanceProtocol.SelfIdentity? = identity,
            prior: BundledLegacyTakeoverRecord? = null,
        ) = installBundledHelperReplacement(
            mode, stagedBuild, incumbentBuild, self, prior,
            stage = { actions += "stage"; stageOk },
            guarded = { actions += "guarded"; settlement },
            legacy = { actions += "legacy"; true },
            fresh = { actions += "fresh"; true },
            cleanup = { actions += "cleanup"; cleanupOk },
        )
    }
}
