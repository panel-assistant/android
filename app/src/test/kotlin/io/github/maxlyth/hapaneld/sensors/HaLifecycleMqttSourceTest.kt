package io.github.maxlyth.hapaneld.sensors

import io.github.maxlyth.hapaneld.haLifecycleFromMqttStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The second lifecycle source: Home Assistant's MQTT birth and will.
 *
 * It exists because Home Assistant refuses the WebSocket lifecycle subscription for a non-administrator
 * user, which is what every panel signs in as — so on real hardware the socket route reports nothing and
 * this one does the work.
 */
class HaLifecycleMqttSourceTest {
    private fun payload(text: String) = text.toByteArray(Charsets.UTF_8)

    // ---- payload mapping -------------------------------------------------------------------------

    @Test fun theWillMapsToAShutdownAndTheBirthToRecovery() {
        assertEquals(HaLifecycleEvent.STOP, haLifecycleFromMqttStatus(payload("offline"), retained = false))
        assertEquals(HaLifecycleEvent.STARTED, haLifecycleFromMqttStatus(payload("online"), retained = false))
    }

    @Test fun caseAndSurroundingWhitespaceDoNotChangeTheMeaning() {
        assertEquals(HaLifecycleEvent.STOP, haLifecycleFromMqttStatus(payload("  OFFLINE\n"), retained = false))
        assertEquals(HaLifecycleEvent.STARTED, haLifecycleFromMqttStatus(payload("Online "), retained = false))
    }

    @Test fun anUnrecognisedPayloadIsIgnoredRatherThanGuessed() {
        listOf("", "  ", "unknown", "offlin", "onlineish", "0", "1").forEach {
            assertNull("payload ${it.trim()} must not be interpreted", haLifecycleFromMqttStatus(payload(it), false))
        }
    }

    /**
     * The defect this guards is specific: a retained birth is replayed to EVERY new subscriber, so a panel
     * reconnecting to the broker would announce "Home Assistant is back online" every single time.
     */
    @Test fun aRetainedMessageIsHistoryAndIsNeverActedOn() {
        assertNull(haLifecycleFromMqttStatus(payload("online"), retained = true))
        assertNull(haLifecycleFromMqttStatus(payload("offline"), retained = true))
    }

    // ---- source authority ------------------------------------------------------------------------

    @Test fun aBrokerWillIsWordedAsGoneOfflineNotAsAShutdown() {
        val ha = HaLifecycle()
        ha.onEvent(HaLifecycleEvent.STOP, HaLifecycleSource.MQTT, 1_000)
        assertEquals(HaLifecycleState.SHUTTING_DOWN, ha.state(1_000))
        assertEquals(HaLifecycleSource.MQTT, ha.snapshot(1_000).source)

        val snap = ha.snapshot(1_000)
        val text = HaLifecycleMessage.text(snap.state, snap.source).orEmpty()
        // The will also fires when Home Assistant merely loses its broker link, so intent is unproven.
        assertTrue("MQTT wording must not claim a deliberate shutdown", text.contains("gone offline"))
        assertTrue(text.contains("Home Assistant"))
    }

    @Test fun theSocketWordingClaimsTheShutdownBecauseItProvesIntent() {
        val ha = HaLifecycle()
        ha.onEvent(HaLifecycleEvent.STOP, HaLifecycleSource.SOCKET, 1_000)
        val snap = ha.snapshot(1_000)
        val text = HaLifecycleMessage.text(snap.state, snap.source).orEmpty()
        assertTrue(text.contains("shutting down"))
        assertNotEquals(
            HaLifecycleMessage.text(HaLifecycleState.SHUTTING_DOWN, HaLifecycleSource.MQTT),
            HaLifecycleMessage.text(HaLifecycleState.SHUTTING_DOWN, HaLifecycleSource.SOCKET),
        )
    }

    @Test fun aLaterBrokerWillDoesNotDowngradeAShutdownTheSocketAlreadyProved() {
        val ha = HaLifecycle()
        ha.onEvent(HaLifecycleEvent.STOP, HaLifecycleSource.SOCKET, 1_000)
        // Both sources report the same outage; the broker's will arrives second because it waits on TCP.
        ha.onEvent(HaLifecycleEvent.STOP, HaLifecycleSource.MQTT, 1_500)
        assertEquals(
            "the authoritative source must win",
            HaLifecycleSource.SOCKET,
            ha.snapshot(1_500).source,
        )
        ha.snapshot(1_500).let { assertTrue(HaLifecycleMessage.text(it.state, it.source).orEmpty().contains("shutting down")) }
    }

    @Test fun aSocketShutdownArrivingAfterABrokerWillUpgradesTheWording() {
        val ha = HaLifecycle()
        ha.onEvent(HaLifecycleEvent.STOP, HaLifecycleSource.MQTT, 1_000)
        ha.onEvent(HaLifecycleEvent.STOP, HaLifecycleSource.SOCKET, 1_100)
        assertEquals(HaLifecycleSource.SOCKET, ha.snapshot(1_100).source)
    }

    // ---- the two sources compose -----------------------------------------------------------------

    @Test fun aBrokerRestartCycleReportsOutageThenRecovery() {
        val ha = HaLifecycle(backOnlineWindowMs = 8_000L)
        ha.onEvent(HaLifecycleEvent.STOP, HaLifecycleSource.MQTT, 1_000)
        assertEquals(HaLifecycleState.SHUTTING_DOWN, ha.state(1_000))
        ha.onEvent(HaLifecycleEvent.STARTED, HaLifecycleSource.MQTT, 40_000)
        assertEquals(HaLifecycleState.BACK_ONLINE, ha.state(40_000))
        assertEquals(HaLifecycleState.NORMAL, ha.state(48_000))
    }

    @Test fun theMqttPathSkipsStartingBecauseTheBirthMeansAlreadyUp() {
        // Documented limitation: the broker carries no equivalent of homeassistant_start, so a panel on
        // the MQTT route goes straight from the outage to recovery with no "starting" stage.
        val ha = HaLifecycle()
        ha.onEvent(HaLifecycleEvent.STOP, HaLifecycleSource.MQTT, 1_000)
        ha.onEvent(HaLifecycleEvent.STARTED, HaLifecycleSource.MQTT, 2_000)
        assertEquals(HaLifecycleState.BACK_ONLINE, ha.state(2_000))
    }

    // ---- the panel bar's own copy ----------------------------------------------------------------

    @Test fun duplicateWillsDoNotReannounce() {
        val ha = HaLifecycle()
        ha.onEvent(HaLifecycleEvent.STOP, HaLifecycleSource.MQTT, 1_000)
        ha.onEvent(HaLifecycleEvent.STOP, HaLifecycleSource.MQTT, 1_500)
        ha.onEvent(HaLifecycleEvent.STOP, HaLifecycleSource.MQTT, 2_000)
        assertEquals(HaLifecycleState.SHUTTING_DOWN, ha.state(2_000))
    }
}
