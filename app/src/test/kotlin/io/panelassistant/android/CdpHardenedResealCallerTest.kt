package io.panelassistant.android

import io.panelassistant.android.control.AdbController
import io.panelassistant.android.control.CdpRelay
import io.panelassistant.android.control.FakeRootShell
import io.panelassistant.android.control.LocalRelayListenerState
import io.panelassistant.android.control.ManagedNetworkAdb
import io.panelassistant.android.control.NetworkAdbDisableTransitionMarker
import io.panelassistant.android.control.RelayExposureState
import io.panelassistant.android.control.RelayProcessState
import io.panelassistant.android.control.RemoteDebugSecurityAuthorityLoad
import io.panelassistant.android.control.RemoteDebugSecurityAuthorityStore
import io.panelassistant.android.control.RemoteDebugSecurityState
import io.panelassistant.android.control.RemoteDebugSecurityTransitionGate
import io.panelassistant.android.control.relayStatusExposure
import io.panelassistant.android.testsupport.PanelRuntimeRig
import io.panelassistant.android.testsupport.scratchDirectory
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Portable caller proof: all referenced production APIs also exist on the unchanged base. */
internal class CdpHardenedResealCallerTest : PanelRuntimeRig() {
    @Test fun weakRelayStatusCannotPublishHardenedAuthority() {
        val rig = rig()
        val directory = scratchDirectory("cdp-hardened-reseal").toFile()
        try {
            RemoteDebugSecurityTransitionGate.withLock {
                synchronized(CdpRelay) {
                    val gateClass = RemoteDebugSecurityTransitionGate::class.java
                    val storeField = gateClass.getDeclaredField("authorityStore").apply { isAccessible = true }
                    val epochField = gateClass.getDeclaredField("epoch").apply { isAccessible = true }
                    val processField = CdpRelay::class.java.getDeclaredField("process").apply { isAccessible = true }
                    val process = processField.get(null) as RelayProcessState
                    val probeField = RelayProcessState::class.java.getDeclaredField("probe").apply { isAccessible = true }
                    val stopProbeField = RelayProcessState::class.java.getDeclaredField("stopProbe").apply { isAccessible = true }
                    val savedStore = storeField.get(null)
                    val savedEpoch = epochField.getLong(null)
                    val savedProbe = probeField.get(process)
                    val savedStopProbe = stopProbeField.get(process)
                    val savedManaged = ManagedNetworkAdb.managed
                    val savedRecoveryHold = ManagedNetworkAdb.recoveryHold
                    try {
                        var strictExposure = RelayExposureState.ABSENT
                        // Inject observations at the existing acquisition closures. UI status may
                        // report off after refusal; the privileged snapshot still has its own truth.
                        val weakProbe: () -> RelayExposureState = {
                            relayStatusExposure(RelayExposureState.UNKNOWN, LocalRelayListenerState.ABSENT)
                        }
                        val strictProbe: () -> RelayExposureState = { strictExposure }
                        probeField.set(process, weakProbe)
                        stopProbeField.set(process, strictProbe)

                        RemoteDebugSecurityTransitionGate.install(directory)
                        val store = hostAuthorityStore(directory)
                        storeField.set(null, store)
                        ManagedNetworkAdb.managed = true
                        ManagedNetworkAdb.recoveryHold = false
                        assertTrue(rig.config.setSecurityMode(Config.SecurityMode.HARDENED))
                        assertTrue(rig.config.setNetworkAdbEnabled(false))
                        val controller = AdbController(
                            rig.config,
                            NetworkAdbDisableTransitionMarker(File(directory, "pending")),
                            FakeRootShell(),
                            { true },
                        )

                        // Run the positive control first: prove this fixture really publishes and
                        // reloads authority before relying on any rejection assertion.
                        RemoteDebugSecurityTransitionGate.mutate { Unit }
                        val positiveEpoch = RemoteDebugSecurityTransitionGate.authorityEpoch()
                        assertFalse(CdpRelay.running)
                        assertTrue(controller.resealManagedHardened())
                        assertEquals(positiveEpoch, RemoteDebugSecurityTransitionGate.hardenedAuthorityEpoch())
                        val sealed = store.load() as RemoteDebugSecurityAuthorityLoad.Valid
                        assertEquals(RemoteDebugSecurityState.HARDENED, sealed.authority.state)

                        for (exposure in listOf(RelayExposureState.UNKNOWN, RelayExposureState.PRESENT)) {
                            strictExposure = exposure
                            RemoteDebugSecurityTransitionGate.mutate { Unit }
                            assertNull(RemoteDebugSecurityTransitionGate.hardenedAuthorityEpoch())
                            assertFalse("weak status stays off for $exposure", CdpRelay.running)
                            assertFalse("strict $exposure cannot authorize resealing", controller.resealManagedHardened())
                            assertNull(RemoteDebugSecurityTransitionGate.hardenedAuthorityEpoch())
                            val unsealed = store.load() as RemoteDebugSecurityAuthorityLoad.Valid
                            assertEquals(RemoteDebugSecurityState.TRANSITION, unsealed.authority.state)
                        }
                    } finally {
                        probeField.set(process, savedProbe)
                        stopProbeField.set(process, savedStopProbe)
                        storeField.set(null, savedStore)
                        epochField.setLong(null, savedEpoch)
                        ManagedNetworkAdb.managed = savedManaged
                        ManagedNetworkAdb.recoveryHold = savedRecoveryHold
                    }
                }
            }
        } finally {
            rig.close()
            directory.deleteRecursively()
        }
    }

    // Existing store policy callbacks adapt Android stat/chmod to host POSIX APIs. Publication,
    // checksums, directory fsync, fences, and production authority readback still run unchanged.
    private fun hostAuthorityStore(directory: File): RemoteDebugSecurityAuthorityStore {
        val parent = Files.readAttributes(directory.toPath(), PosixFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        val mode0600 = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        var nextEpoch = 100L
        return RemoteDebugSecurityAuthorityStore(
            noBackupFilesDir = directory,
            nextEpoch = { ++nextEpoch },
            validRegularFile = { file ->
                runCatching {
                    val attributes = Files.readAttributes(file.toPath(), PosixFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                    val links = Files.getAttribute(file.toPath(), "unix:nlink", LinkOption.NOFOLLOW_LINKS) as Number
                    attributes.isRegularFile && links.toLong() == 1L && attributes.owner() == parent.owner() &&
                        attributes.group() == parent.group() && attributes.permissions() == mode0600
                }.getOrDefault(false)
            },
            chmod0600 = { file -> runCatching { Files.setPosixFilePermissions(file.toPath(), mode0600); true }.getOrDefault(false) },
            syncDirectory = { parentDirectory ->
                runCatching {
                    FileChannel.open(parentDirectory.toPath(), StandardOpenOption.READ).use { it.force(true) }
                    true
                }.getOrDefault(false)
            },
        )
    }
}
