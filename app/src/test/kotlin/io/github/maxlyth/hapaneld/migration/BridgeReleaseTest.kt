package io.github.maxlyth.hapaneld.migration

import io.github.maxlyth.hapaneld.migration.BridgeRelease.Outcome
import io.github.maxlyth.hapaneld.migration.BridgeRelease.Refusal
import org.junit.Assert.assertEquals
import org.junit.Test

class BridgeReleaseTest {
    private class FakePorts(
        var bridge: Boolean = true,
        var retired: Boolean = false,
        var token: String? = "good",
        var trusted: Boolean = true,
        var helperRefusal: String? = null,
        var quiesceArms: Boolean = true,
        var markerWrites: Boolean = true,
        var homeSets: Boolean = true,
    ) : BridgeRelease.Ports {
        val events = mutableListOf<String>()
        var quiesced: (() -> Unit)? = null

        override fun isBridge(): Boolean = bridge
        override fun retired(): Boolean = retired
        override fun tokenMatches(presented: String?): Boolean = presented != null && presented == token
        override fun successorTrusted(): Boolean = trusted
        override fun helperRefusal(): String? = helperRefusal
        override fun beginQuiesce(onQuiesced: () -> Unit): Boolean {
            events += "quiesce"
            if (quiesceArms) quiesced = onQuiesced
            return quiesceArms
        }
        override fun writeRetiredMarker(): Boolean {
            events += "marker"
            if (markerWrites) retired = true
            return markerWrites
        }
        override fun setHomeToSuccessor(): Boolean = homeSets.also { events += "home" }
        override fun resumeBridge() { events += "resume" }
        override fun endProcess() { events += "end" }
    }

    private fun request(ports: FakePorts, token: String? = "good", loopback: Boolean = true) =
        BridgeRelease(ports).request(token, loopback)

    @Test fun aTrustedRequestIsRefusedBecausePanelAssistantMovesThePanel() {
        val ports = FakePorts()

        assertEquals(Outcome.Refused(Refusal.MOVED_BY_PANEL_ASSISTANT), request(ports))
        assertEquals("the bridge keeps the panel and keeps running", emptyList<String>(), ports.events)
        assertEquals("moved-by-panel-assistant", Refusal.MOVED_BY_PANEL_ASSISTANT.code)
    }

    @Test fun theRetirementRefusalHoldsEvenWhenTheHelperAndShutdownWouldAdmitIt() {
        val ports = FakePorts(helperRefusal = null, quiesceArms = true)

        assertEquals(Outcome.Refused(Refusal.MOVED_BY_PANEL_ASSISTANT), request(ports))
        assertEquals(null, ports.quiesced)
    }

    @Test fun everyRefusalLeavesNoSideEffect() {
        val refusals = mapOf(
            Refusal.NOT_A_BRIDGE to FakePorts(bridge = false),
            Refusal.BAD_TOKEN to FakePorts(token = "other"),
            Refusal.UNTRUSTED_SUCCESSOR to FakePorts(trusted = false),
            Refusal.MOVED_BY_PANEL_ASSISTANT to FakePorts(helperRefusal = "running helper is not the bundled build"),
        )

        refusals.forEach { (refusal, ports) ->
            assertEquals(refusal, (request(ports) as? Outcome.Refused)?.refusal)
            assertEquals("$refusal must not touch the panel", emptyList<String>(), ports.events)
        }
    }

    @Test fun aCallerThatIsNotOnLoopbackIsRefusedEvenWithTheToken() {
        val ports = FakePorts()

        assertEquals(Outcome.Refused(Refusal.NOT_LOOPBACK), request(ports, loopback = false))
        assertEquals(emptyList<String>(), ports.events)
    }

    @Test fun aMissingTokenIsRefused() {
        assertEquals(Outcome.Refused(Refusal.BAD_TOKEN), request(FakePorts(), token = null))
    }

    @Test fun aRetiredBridgeAnswersARetryWithoutQuiescingAgain() {
        val ports = FakePorts(retired = true)

        assertEquals(Outcome.AlreadyReleased, request(ports))
        assertEquals(emptyList<String>(), ports.events)
    }

    @Test fun aRetiredBridgeStillRefusesAWrongToken() {
        assertEquals(Outcome.Refused(Refusal.BAD_TOKEN), request(FakePorts(retired = true), token = "other"))
    }
}
