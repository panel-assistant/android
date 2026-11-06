package io.panelassistant.android.http

import io.panelassistant.android.Config
import io.panelassistant.android.control.*
import io.panelassistant.android.guardDbMaintenanceSecurityReady
import io.panelassistant.android.testsupport.withCdpObservation
import io.panelassistant.android.util.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test

class CdpGuardAuthorityTest {
    @Test fun allGuardCallbacksRequireFreshStrictAbsenceBeforeAndAfterAdbProof() {
        PaneldServerHttpFixture().use { fixture ->
            var replies = ArrayDeque<RelayExposureState>()
            var relayReads = 0
            var adbReads = 0
            withCdpObservation(strict = { relayReads++; replies.removeFirst() }) {
                val gate = RemoteDebugSecurityTransitionGate::class.java
                val storeField = gate.getDeclaredField("authorityStore").apply { isAccessible = true }
                val epochField = gate.getDeclaredField("epoch").apply { isAccessible = true }
                val savedStore = storeField.get(null)
                val savedEpoch = epochField.getLong(null)
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                try {
                    RemoteDebugSecurityTransitionGate.install(fixture.directory)
                    var serial = 500L
                    storeField.set(null, RemoteDebugSecurityAuthorityStore(fixture.directory,
                        nextEpoch = { ++serial }, validRegularFile = { it.isFile },
                        chmod0600 = { true }, syncDirectory = { true }))
                    assertTrue(fixture.config.setSecurityMode(Config.SecurityMode.HARDENED))
                    RemoteDebugSecurityTransitionGate.mutate { Unit }
                    assertTrue(RemoteDebugSecurityTransitionGate.sealHardened())
                    val epoch = requireNotNull(RemoteDebugSecurityTransitionGate.hardenedAuthorityEpoch())
                    val dependencies = guardDbBootstrapDependencies(fixture.context, fixture.config, scope,
                        fixture.pending, guardDbAppStaging(fixture.context), remoteDebugOff = { adbReads++; true })
                    val sentinel = GuardDbStartupSentinel(GuardDbSentinelState.INTENT, "1".repeat(64), "2".repeat(64),
                        "a".repeat(64), 1, 1, "b".repeat(64), 2, 2, GuardDbSettingsAuthority.VERSION,
                        3, "c".repeat(64), epoch, 8888, true)
                    val proofs = listOf<Pair<String, () -> Boolean>>(
                        "bootstrap epoch" to { dependencies.securityEpoch() == epoch },
                        "terminal epoch" to { dependencies.terminalRetirement!!.securityEpoch() == epoch },
                        // Android file validation can reject the host sentinel write. Reaching that
                        // write boundary is the positive control; security refusal is separate.
                        "sentinel commit" to { dependencies.commitSentinel(epoch, sentinel) !is GuardDbSentinelCommit.SecurityRefused },
                        "maintenance ready" to { guardDbMaintenanceSecurityReady(epoch,
                            RemoteDebugSecurityTransitionGate::hardenedAuthorityEpoch, CdpRelay::proveAbsent,
                            { adbReads++; true }) },
                    )
                    for ((name, proof) in proofs) {
                        fun observe(first: RelayExposureState, second: RelayExposureState): Boolean {
                            replies = ArrayDeque(listOf(first, second)); relayReads = 0; adbReads = 0
                            return proof()
                        }
                        assertTrue("$name positive control", observe(RelayExposureState.ABSENT, RelayExposureState.ABSENT))
                        assertEquals(name, 2, relayReads); assertEquals(name, 1, adbReads)
                        for (bad in listOf(RelayExposureState.UNKNOWN, RelayExposureState.PRESENT)) {
                            assertFalse("$name before ADB $bad", observe(bad, RelayExposureState.ABSENT))
                            assertEquals(name, 1, relayReads); assertEquals(name, 0, adbReads)
                            assertFalse("$name after ADB $bad", observe(RelayExposureState.ABSENT, bad))
                            assertEquals(name, 2, relayReads); assertEquals(name, 1, adbReads)
                        }
                        assertEquals("$name is read-only", epoch, RemoteDebugSecurityTransitionGate.hardenedAuthorityEpoch())
                    }
                } finally {
                    scope.cancel()
                    storeField.set(null, savedStore); epochField.setLong(null, savedEpoch)
                }
            }
        }
    }

    @Test fun passiveGuardHandoffNeverUsesDisplayOrChangesSecurityEpoch() {
        for (state in RelayExposureState.entries) {
            var reads = 0
            withCdpObservation(strict = { reads++; state }, display = { error("weak display must not authorize Guard") }) {
                val epoch = RemoteDebugSecurityTransitionGate.authorityEpoch()
                repeat(3) { assertEquals(state == RelayExposureState.ABSENT, CdpRelay.proveAbsentForGuardDbHandoff()) }
                assertEquals(3, reads)
                assertEquals(epoch, RemoteDebugSecurityTransitionGate.authorityEpoch())
            }
        }
    }
}
