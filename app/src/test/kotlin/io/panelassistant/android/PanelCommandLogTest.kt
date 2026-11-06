package io.panelassistant.android

import io.panelassistant.android.panelassistant.CommandValue
import io.panelassistant.android.panelassistant.PanelAssistantCommand
import io.panelassistant.android.panelassistant.PanelAssistantCommandProcessor
import io.panelassistant.android.panelassistant.PanelAssistantCommandResult
import io.panelassistant.android.security.LocalApprovalBroker
import io.panelassistant.android.testsupport.PanelRuntimeRig
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the panel's log says about a command that ends in an approval wait and one that fails. */
internal class PanelCommandLogTest : PanelRuntimeRig() {
    private class Recorder : CommandLog {
        val infos = CopyOnWriteArrayList<String>()
        val warnings = CopyOnWriteArrayList<Pair<String, Throwable>>()
        override fun info(message: String) { infos += message }
        override fun warn(message: String, error: Throwable) { warnings += message to error }
    }

    private fun submit(rig: Rig, channel: String, value: CommandValue, origin: CommandOrigin): PanelAssistantCommandResult {
        val done = CountDownLatch(1)
        val result = AtomicReference<PanelAssistantCommandResult>()
        rig.commands.submit(PanelAssistantCommand(channel, value, admit = { null }), origin) {
            result.set(it)
            done.countDown()
        }
        assertTrue("$channel finished", done.await(20, TimeUnit.SECONDS))
        return result.get()
    }

    @Test fun aCommandHeldForOnPanelApprovalIsLoggedAsAnApprovalWaitAtInfoWithoutAStackTrace() {
        LocalApprovalBroker.instance.clear()
        val log = Recorder()
        val rig = rig(
            hasCamera = true,
            deviceSecure = false,
            commandLog = log,
            configure = { assertTrue(it.setSecurityMode(Config.SecurityMode.HARDENED)) },
        )
        try {
            val result = submit(rig, "camera_enabled", CommandValue.Switch(true), CommandOrigin.user("u1", "req-1"))
            val pending = LocalApprovalBroker.instance.pending().single()
            assertEquals(PanelAssistantCommandResult.ApprovalPending(pending.id), result)
            assertEquals(
                listOf("command req-1 from user:u1 waits for approval ${pending.id} on the panel (camera_enabled)"),
                log.infos.toList(),
            )
            assertEquals("an approval wait is not a failure line", emptyList<String>(), log.warnings.map { it.first })
        } finally {
            LocalApprovalBroker.instance.clear()
            rig.close()
        }
    }

    @Test fun aCommandThatFailsStillLogsAWarningWithItsStackTrace() {
        val log = Recorder()
        // The helper's reboot never answers and root cannot take a JVM panel down, so the reboot fails.
        val rig = rig(deviceSecure = false, rebootSucceeds = false, commandLog = log)
        try {
            val result = submit(rig, "reboot", CommandValue.Press, CommandOrigin.panelAssistant("req-2"))
            assertEquals(PanelAssistantCommandResult.Failed(PanelAssistantCommandProcessor.CODE_HARDWARE_UNAVAILABLE), result)
            val (message, error) = log.warnings.single()
            assertEquals("command req-2 from panel_assistant failed on reboot", message)
            assertNotNull(error)
            assertNull("no approval wait line for a plain failure", log.infos.firstOrNull { "waits for approval" in it })
        } finally {
            rig.close()
        }
    }
}
