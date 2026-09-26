package io.github.maxlyth.hapaneld.platform

import io.github.maxlyth.hapaneld.platform.NotificationPermissionRepair.Outcome
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationPermissionRepairTest {
    /** A helper that grants for real: an `OK` reply flips what the package manager reports. */
    private class FakeHelper(private val reply: DaemonLongResult, private val onGrant: () -> Unit = {}) : Daemon {
        val sent = mutableListOf<String>()
        override fun available() = reply != DaemonLongResult.NotSubmitted
        override fun send(cmd: String): String? = error("unexpected $cmd")
        override fun sendBytes(cmd: String): ByteArray? = error("unexpected $cmd")
        override fun sendLong(cmd: String, timeoutMs: Long): DaemonLongResult {
            sent += cmd
            if (reply == DaemonLongResult.Reply("OK")) onGrant()
            return reply
        }
    }

    @Test fun serviceStartClaimsAMissingPermissionThroughTheHelper() {
        for (sdk in listOf(33, 34)) {
            var granted = false
            val helper = FakeHelper(DaemonLongResult.Reply("OK")) { granted = true }
            assertEquals(Outcome.CLAIMED, NotificationPermissionRepair.repair(sdk, { granted }, helper, "io.panelassistant.android"))
            assertEquals(listOf("GRANT io.panelassistant.android NOTIFICATIONS"), helper.sent)
        }
    }

    @Test fun aHeldPermissionOrAnOlderPanelSendsNothing() {
        val held = FakeHelper(DaemonLongResult.Reply("OK"))
        assertEquals(Outcome.HELD, NotificationPermissionRepair.repair(34, { true }, held, "io.panelassistant.android"))
        val older = FakeHelper(DaemonLongResult.Reply("OK"))
        assertEquals(Outcome.HELD, NotificationPermissionRepair.repair(32, { false }, older, "io.panelassistant.android"))
        assertEquals(emptyList<String>(), held.sent + older.sent)
    }

    @Test fun noHelperOrARefusalIsReportedNotClaimed() {
        assertEquals(Outcome.NO_HELPER, NotificationPermissionRepair.repair(34, { false }, FakeHelper(DaemonLongResult.NotSubmitted), "p"))
        assertEquals(Outcome.REFUSED, NotificationPermissionRepair.repair(34, { false }, FakeHelper(DaemonLongResult.Reply("ERR")), "p"))
        assertEquals(Outcome.REFUSED, NotificationPermissionRepair.repair(34, { false }, FakeHelper(DaemonLongResult.Indeterminate), "p"))
        // The helper said OK but Android did not keep it: only the readback counts.
        assertEquals(Outcome.REFUSED, NotificationPermissionRepair.repair(34, { false }, FakeHelper(DaemonLongResult.Reply("OK")), "p"))
    }
}
