package io.panelassistant.android

import io.panelassistant.android.control.ControlApplyOutcome
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A failed external actuation must not resurrect the value it replaced.
 *
 * Touch sound persists intent before it actuates, so an MQTT command leaves newer durable truth behind
 * even when the actuation that follows fails. That failure throws, and the throw skips the supersession
 * step that normally retires the older queued HTTP value — so the stale value survives in the journal
 * and startup replay commits it back over the newer one. These cases drive the real
 * [LiveSettingAuthority] through that exact sequence.
 */
class TouchSoundExternalSupersessionTest {
    // Source-text reason: pins MqttBridge.kt, deleted with the MQTT removal.

    /** The inversion itself, end to end on the real authority. */
    @Test fun anUnavailableExternalChangeStillRetiresTheOlderQueuedValue() {
        val config = FakeConfig()
        val authority = LiveSettingAuthority(setOf(KEY))

        // A user turned touch sound off from the panel. WRITE_SETTINGS is refused, so the value is
        // durable and queued rather than applied.
        val http = authority.applyOrQueueOutcome(KEY, "false", null) { _, value, _ ->
            config.commit(value)
            LiveSettingApplyResult.UNAVAILABLE
        }
        assertEquals(LiveSettingRequestOutcome.FAILED_PENDING, http)
        assertEquals("false", config.value)
        assertEquals(mapOf(KEY to "false"), authority.pendingSnapshot())

        // Home Assistant then turns it on. The handler commits first and the actuation fails.
        val committed = dispatchExternal(config, actuation = ControlApplyOutcome.UNAVAILABLE, value = "true")
        assertEquals(KEY, committed)
        assertEquals("true", config.value)

        // The failure path must still retire the older queued value.
        supersededKeyAfterFailedDispatch(topicKey = KEY, committedKey = committed)
            ?.let { assertTrue(authority.discard(it)) }
        assertTrue("the stale queued value survived", authority.pendingSnapshot().isEmpty())

        // And a restart must not be able to put it back.
        authority.replay { _, value, _ -> config.commit(value); LiveSettingApplyResult.APPLIED }
        assertEquals("the older HTTP value was replayed over the newer external one", "true", config.value)
    }

    /**
     * The other half of the rule, and the one that keeps it honest: a command that threw *before*
     * committing anything must leave the queued HTTP value exactly where it was. An unauthorized or
     * refused external command is not a newer truth.
     */
    @Test fun aCommandThatCommittedNothingLeavesTheQueuedValueAlone() {
        val config = FakeConfig()
        val authority = LiveSettingAuthority(setOf(KEY))

        authority.applyOrQueueOutcome(KEY, "false", null) { _, value, _ ->
            config.commit(value)
            LiveSettingApplyResult.UNAVAILABLE
        }
        assertEquals(mapOf(KEY to "false"), authority.pendingSnapshot())

        // Refused before the commit: nothing durable moved.
        val committed = dispatchExternal(config, actuation = null, value = "true")
        assertNull(committed)
        assertEquals("false", config.value)

        assertNull(supersededKeyAfterFailedDispatch(topicKey = KEY, committedKey = committed))
        assertEquals(mapOf(KEY to "false"), authority.pendingSnapshot())
    }

    /** A key committed by an earlier dispatch cannot retire a value the current command never named. */
    @Test fun aKeyFromAnotherCommandIsNeverSuperseded() {
        assertNull(supersededKeyAfterFailedDispatch(topicKey = KEY, committedKey = "silence_boot_chime"))
        assertNull(supersededKeyAfterFailedDispatch(topicKey = null, committedKey = KEY))
        assertEquals(KEY, supersededKeyAfterFailedDispatch(topicKey = KEY, committedKey = KEY))
    }

    /** The production wiring the cases above stand in for. */
    @Test fun theHandlerRecordsItsCommitAndTheFailurePathReadsIt() {
        val bridge = File("src/main/kotlin/io/panelassistant/android/MqttBridge.kt").readText()
        val handler = bridge.substring(
            bridge.indexOf("override fun handleTouchSound(payload: String)"),
            bridge.indexOf("override fun handleWatchdog(payload: String)"),
        )
        val consume = bridge.substring(
            bridge.indexOf("private fun consumeCommand(topic: String, payloadBytes: ByteArray, peer: String)"),
            bridge.indexOf("private fun dispatchCommand(topic: String, payloadBytes: ByteArray)"),
        )

        // Committed, marked, and only then actuated — the order is the fix.
        assertTrue(handler.indexOf("config.commitTouchSound(on)") < handler.indexOf("externalIntentCommitted.set"))
        assertTrue(handler.indexOf("externalIntentCommitted.set") < handler.indexOf("touchSound.apply(on)"))
        // The failure path consults it, and the marker cannot outlive its dispatch.
        assertTrue(consume.contains("supersededKeyAfterFailedDispatch("))
        assertEquals(2, Regex("externalIntentCommitted\\.remove\\(\\)").findAll(consume).count())
    }

    private fun dispatchExternal(
        config: FakeConfig,
        actuation: ControlApplyOutcome?,
        value: String,
    ): String? {
        // Mirrors handleTouchSound: refuse before committing, or commit, mark, then actuate.
        if (actuation == null) return null
        config.commit(value)
        val committed = KEY
        if (!actuation.applied) return committed
        return committed
    }

    private class FakeConfig {
        var value: String = "true"
        fun commit(next: String) {
            value = next
        }
    }

    private companion object {
        const val KEY = "touch_sound"
    }
}
