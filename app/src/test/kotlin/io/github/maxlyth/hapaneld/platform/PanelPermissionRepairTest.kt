package io.github.maxlyth.hapaneld.platform

import io.github.maxlyth.hapaneld.AppIdentity
import io.github.maxlyth.hapaneld.camera.CameraCapabilityReason
import io.github.maxlyth.hapaneld.platform.PanelPermissionRepair.Grant
import io.github.maxlyth.hapaneld.platform.PanelPermissionRepair.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelPermissionRepairTest {
    private class Panel(
        val permissions: MutableSet<Grant>,
        private val reply: DaemonLongResult = DaemonLongResult.Reply("OK"),
        private val keepGrants: Boolean = true,
    ) : Daemon {
        val submitted = mutableListOf<String>()
        val observed = mutableSetOf<Grant>()
        fun held(grant: Grant): Boolean {
            observed += grant
            return grant in permissions
        }
        override fun available() = reply != DaemonLongResult.NotSubmitted
        override fun send(cmd: String): String? = error("unexpected $cmd")
        override fun sendBytes(cmd: String): ByteArray? = error("unexpected $cmd")
        override fun sendLong(cmd: String, timeoutMs: Long): DaemonLongResult {
            val grant = Grant.valueOf(cmd.substringAfterLast(' '))
            assertTrue("a missing grant must be read before it is repaired", grant in observed)
            assertFalse("an existing grant must never be submitted", grant in permissions)
            submitted += cmd
            if (keepGrants && reply != DaemonLongResult.NotSubmitted) permissions += grant
            return reply
        }
    }

    private fun repair(panel: Panel, sdk: Int = 34, microphone: Boolean = true, camera: Boolean = true) =
        PanelPermissionRepair.repair(sdk, microphone, camera, panel::held, panel, AppIdentity.SUCCESSOR)

    @Test fun updateRepairsOnlyMissingFeatureGrantsAndARepeatWritesNothing() {
        for (pkg in AppIdentity.ALL) {
            val present = setOf(Grant.NOTIFICATIONS, Grant.OVERLAY, Grant.MICROPHONE)
            val panel = Panel(present.toMutableSet())
            val result = PanelPermissionRepair.repair(34, true, true, panel::held, panel, pkg)
            assertEquals(Grant.entries.toSet(), panel.permissions)
            assertEquals(present.associateWith { Outcome.HELD } +
                (Grant.entries.toSet() - present).associateWith { Outcome.CLAIMED }, result)
            assertEquals(listOf("GRANT $pkg WRITESETTINGS", "GRANT $pkg ACCESSIBILITY", "GRANT $pkg CAMERA"), panel.submitted)
            panel.submitted.clear()
            assertEquals(Grant.entries.associateWith { Outcome.HELD },
                PanelPermissionRepair.repair(34, true, true, panel::held, panel, pkg))
            assertTrue(panel.submitted.isEmpty())
        }
    }

    @Test fun supportedHardwareAndAndroidVersionChooseTheGrants() {
        for (sdk in listOf(32, 33, 34)) for (microphone in listOf(false, true)) for (camera in listOf(false, true)) {
            val panel = Panel(mutableSetOf())
            val expected = setOf(Grant.WRITESETTINGS, Grant.OVERLAY, Grant.ACCESSIBILITY) +
                (if (sdk >= 33) setOf(Grant.NOTIFICATIONS) else emptySet()) +
                (if (microphone) setOf(Grant.MICROPHONE) else emptySet()) +
                (if (camera) setOf(Grant.CAMERA) else emptySet())
            assertEquals(expected.associateWith { Outcome.CLAIMED }, repair(panel, sdk, microphone, camera))
            assertEquals(expected, panel.permissions)
            assertEquals(expected.size, panel.submitted.size)
        }
        assertTrue(PanelPermissionRepair.notificationsHeld(32) { false })
        assertFalse(PanelPermissionRepair.notificationsHeld(33) { false })
    }

    @Test fun missingHelperOrRefusedReadbackDoesNotClaimGrants() {
        for (reply in listOf(DaemonLongResult.NotSubmitted, DaemonLongResult.Reply("ERR"),
            DaemonLongResult.Reply("OK"), DaemonLongResult.Indeterminate)) {
            val panel = Panel(mutableSetOf(), reply, keepGrants = false)
            val expected = if (reply == DaemonLongResult.NotSubmitted) Outcome.NO_HELPER else Outcome.REFUSED
            assertEquals(Grant.entries.associateWith { expected }, repair(panel))
            assertTrue(panel.permissions.isEmpty())
        }
    }

    @Test fun aLostReplyIsReconciledFromAndroidsReadback() {
        val panel = Panel(mutableSetOf(), DaemonLongResult.Indeterminate)
        assertEquals(Grant.entries.associateWith { Outcome.CLAIMED }, repair(panel))
        assertEquals(Grant.entries.toSet(), panel.permissions)
    }

    @Test fun unreadableStateIsNotTreatedAsAMissingGrant() {
        val panel = Panel(Grant.entries.toMutableSet())
        val outcomes = PanelPermissionRepair.repair(34, true, true, { grant ->
            if (grant == Grant.CAMERA) throw SecurityException("readback unavailable")
            panel.held(grant)
        }, panel, AppIdentity.SUCCESSOR)
        assertEquals(Outcome.UNREADABLE, outcomes[Grant.CAMERA])
        assertEquals(5, outcomes.values.count { it == Outcome.HELD })
        assertTrue(panel.submitted.isEmpty())
    }

    @Test fun cameraMetadataCoversDelayedEnumerationWithoutOverridingTheProfile() {
        for (reason in CameraCapabilityReason.entries) for (hardware in listOf(false, true)) {
            val expected = reason == CameraCapabilityReason.PRESENT || reason == CameraCapabilityReason.MISDECLARED ||
                (reason == CameraCapabilityReason.UNDETERMINED && hardware)
            assertEquals(expected, PanelPermissionRepair.cameraRequired(reason, hardware))
        }
    }

    @Test fun accessibilityRequiresOurExactComponentAndTheGlobalEnableFlag() {
        for (pkg in AppIdentity.ALL) {
            val full = "$pkg/${AppIdentity.CODE_PACKAGE}.input.PanelAccessibilityService"
            assertTrue(PanelPermissionRepair.accessibilityHeld(pkg, "com.vendor/.Reader:$full", true))
            assertFalse(PanelPermissionRepair.accessibilityHeld(pkg, full, false))
            assertFalse(PanelPermissionRepair.accessibilityHeld(pkg, "$pkg/.OtherService", true))
            assertFalse(PanelPermissionRepair.accessibilityHeld(pkg, "${pkg}.foreign/.Reader", true))
            assertFalse(PanelPermissionRepair.accessibilityHeld(pkg, null, true))
        }
        assertTrue(PanelPermissionRepair.accessibilityHeld(AppIdentity.LEGACY, "${AppIdentity.LEGACY}/.input.PanelAccessibilityService", true))
        assertFalse(PanelPermissionRepair.accessibilityHeld(AppIdentity.SUCCESSOR, "${AppIdentity.SUCCESSOR}/.input.PanelAccessibilityService", true))
    }
}
