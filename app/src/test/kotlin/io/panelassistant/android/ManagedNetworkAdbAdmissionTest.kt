package io.panelassistant.android

import io.panelassistant.android.control.AdbController
import io.panelassistant.android.control.FakeRootShell
import io.panelassistant.android.control.HardenedNetworkAdbAdmission
import io.panelassistant.android.control.ManagedNetworkAdb
import io.panelassistant.android.control.NetworkAdbDisableTransitionMarker
import io.panelassistant.android.testsupport.PanelRuntimeRig
import io.panelassistant.android.testsupport.scratchDirectory
import org.junit.Assert.*
import org.junit.Test
import java.io.File

internal class ManagedNetworkAdbAdmissionTest : PanelRuntimeRig() {
    @Test fun managedBootFallbackNeedsFreshIndependentOffProofForEveryHardenedAdmission() {
        val rig = rig()
        val directory = scratchDirectory("managed-adb").toFile()
        var nativeProof = true
        val controller = AdbController(rig.config, NetworkAdbDisableTransitionMarker(File(directory, "pending")), FakeRootShell(), { nativeProof })
        try {
            assertTrue(rig.config.setNetworkAdbEnabled(true))
            ManagedNetworkAdb.managed = true
            val result = controller.commitHardenedWhenRemoteAdbInactive { rig.config.setSecurityMode(Config.SecurityMode.HARDENED) }
            assertEquals(HardenedNetworkAdbAdmission.APPLIED, result)
            assertFalse(rig.config.networkAdbEnabled)
            assertTrue(controller.hardenedRemoteDebugOff())
            nativeProof = false
            assertFalse("stale managed flag cannot authorize Guard after recovery opened", controller.hardenedRemoteDebugOff())
        } finally {
            ManagedNetworkAdb.managed = false
            ManagedNetworkAdb.recoveryHold = false
            rig.close(); directory.deleteRecursively()
        }
    }
}
