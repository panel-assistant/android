package io.github.maxlyth.hapaneld.panelassistant

import io.github.maxlyth.hapaneld.HardenedModeRefusalException
import io.github.maxlyth.hapaneld.LiveSettingUnavailableException
import io.github.maxlyth.hapaneld.SensitiveApprovalPendingException
import io.github.maxlyth.hapaneld.panelAssistantCommandResult
import io.github.maxlyth.hapaneld.testsupport.TestSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Native commands run through the bridge's own command handlers, which need the Android service to
 * construct, so the wiring between the transport and those handlers is pinned at its source; the
 * classification of a handler's outcome is tested directly.
 */
class PanelAssistantCommandWiringContractTest {
    // Source-text reason: pins MqttBridge.kt, deleted with the MQTT removal.
    private val bridge by lazy { TestSources.kotlin("MqttBridge.kt").readText() }

    @Test fun mqttCommandsYieldToThePersistedNativeAuthority() {
        val onCommand = bridge.substringAfter("private fun onCommand(").substringBefore("\n    }\n")
        assertTrue(onCommand, onCommand.contains("mqttAcceptsCommand(retired, retained, config.panelAssistantAuthority)"))
        assertTrue(onCommand, onCommand.contains("consumeCommand(topic, payload, MQTT_PEER)"))
    }

    @Test fun nativeCommandsShareTheMqttChannelKeyHandlersAndApprovalUnderTheirOwnPeer() {
        val submit = bridge.substringAfter("internal fun submitPanelAssistantCommand(").substringBefore("\n    }\n")
        assertTrue(submit, submit.contains("val topic = \"ha-paneld/\$panel/\${command.channel}/set\""))
        assertTrue(submit, submit.contains("key = command.channel,"))
        assertTrue(submit, submit.contains("CommandKind.ACTION ->"))
        val admitAt = submit.indexOf("command.admit()")
        val runAt = submit.indexOf("consumeCommand(topic, payload, PANEL_ASSISTANT_PEER)")
        assertTrue(submit, admitAt in 0 until runAt)

        val consume = bridge.substringAfter("private fun consumeCommand(").substringBefore("\n    }\n")
        assertTrue(consume.indexOf("commandPeer.set(peer)") in 0 until consume.indexOf("dispatchCommand(topic, payloadBytes)"))
        assertTrue(consume.contains("commandPeer.remove()"))
        val authorize = bridge.substringAfter("private fun authorizeRemoteSensitive(").substringBefore("\n    }\n")
        assertTrue(authorize, authorize.contains("val peer = commandPeer.get() ?: MQTT_PEER"))
        assertTrue(authorize, authorize.contains("LocalApprovalBroker.instance.request(operation, peer, payload, summary)"))
        assertTrue(bridge.contains("private const val MQTT_PEER = \"mqtt\""))
        assertTrue(bridge.contains("private const val PANEL_ASSISTANT_PEER = \"panel_assistant\""))
    }

    @Test fun aHandlerOutcomeReadsAsItsCommandResult() {
        assertEquals(PanelAssistantCommandResult.Applied, panelAssistantCommandResult(null))
        assertEquals(
            PanelAssistantCommandResult.ApprovalPending("a1"),
            panelAssistantCommandResult(SensitiveApprovalPendingException("a1", "approval required")),
        )
        assertEquals(
            PanelAssistantCommandResult.Refused("refused_hardened"),
            panelAssistantCommandResult(HardenedModeRefusalException("no")),
        )
        assertEquals(
            PanelAssistantCommandResult.Failed("hardware_unavailable"),
            panelAssistantCommandResult(LiveSettingUnavailableException("relay1")),
        )
        assertEquals(
            PanelAssistantCommandResult.Refused("invalid_value"),
            panelAssistantCommandResult(IllegalArgumentException("bad")),
        )
        assertEquals(PanelAssistantCommandResult.Failed("failed"), panelAssistantCommandResult(IllegalStateException("x")))
    }
}
