package io.panelassistant.android

import io.panelassistant.android.mqtt.StateConverger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class LiveSettingAuthorityTest {
    @Test fun `late failure of superseded navbar intent does not schedule newer pending work`() {
        val notifications = mutableListOf<String>()
        val authority = LiveSettingAuthority(setOf("navbar_mode"), onLatePending = notifications::add)
        var complete: ((LiveSettingApplyResult) -> Unit)? = null
        authority.applyOrQueueOutcomeObserved("navbar_mode", "Swipe reveal", "Off") { _, _, _ ->
            LiveSettingApplication(LiveSettingApplyResult.DEFERRED) { complete = it }
        }
        authority.applyOrQueueOutcomeObserved("navbar_mode", "Always on", "Off") { _, _, _ ->
            LiveSettingApplication.immediate(LiveSettingApplyResult.FAILED)
        }
        requireNotNull(complete).invoke(LiveSettingApplyResult.FAILED)

        assertTrue(notifications.isEmpty())
        assertEquals(mapOf("navbar_mode" to "Always on"), authority.pendingSnapshot())
        assertEquals(setOf("navbar_mode"), authority.retryablePendingKeys())
    }

    @Test fun `startup restore refuses a newer durable or pending navbar mode`() {
        listOf(false, true).forEach { pending ->
            val authority = LiveSettingAuthority(setOf("navbar_mode"))
            var durable = "Swipe reveal"
            val captured = durable
            if (pending) {
                authority.applyOrQueueOutcomeObserved("navbar_mode", "Off", durable) { _, _, _ ->
                    LiveSettingApplication.immediate(LiveSettingApplyResult.FAILED)
                }
            } else {
                durable = "Off"
            }
            val result = authority.applyOrQueueOutcomeIf(
                key = "navbar_mode",
                value = captured,
                previousValue = captured,
                expected = { durable == captured && "navbar_mode" !in authority.pendingSnapshot() },
            ) { _, _, _ -> error("stale restore must not actuate") }

            assertEquals(LiveSettingRequestOutcome.REJECTED, result)
            assertEquals(if (pending) mapOf("navbar_mode" to "Off") else emptyMap<String, String>(), authority.pendingSnapshot())
            assertEquals(if (pending) "Swipe reveal" else "Off", durable)
        }
    }

    @Test fun `conditionally admitted startup restore observes late acknowledgement without replaying in flight`() {
        val authority = LiveSettingAuthority(setOf("navbar_mode"))
        var complete: ((LiveSettingApplyResult) -> Unit)? = null
        assertEquals(
            LiveSettingRequestOutcome.FAILED_PENDING,
            authority.applyOrQueueOutcomeIf(
                "navbar_mode", "Swipe reveal", "Swipe reveal", expected = { true },
            ) { _, _, _ ->
                LiveSettingApplication(LiveSettingApplyResult.FAILED) { complete = it }
            },
        )
        assertEquals(mapOf("navbar_mode" to "Swipe reveal"), authority.pendingSnapshot())
        var replays = 0
        authority.replayKeysObserved(setOf("navbar_mode")) { _, _, _, _ ->
            replays++
            LiveSettingApplication.immediate(LiveSettingApplyResult.FAILED)
        }
        assertEquals("an admitted restore must not be enqueued again while it is in flight", 0, replays)
        requireNotNull(complete).invoke(LiveSettingApplyResult.APPLIED)
        assertTrue(authority.pendingSnapshot().isEmpty())
    }

    @Test fun `request outcome distinguishes applied deferred failed pending and rejected`() {
        listOf(
            LiveSettingApplyResult.APPLIED to LiveSettingRequestOutcome.APPLIED,
            LiveSettingApplyResult.DEFERRED to LiveSettingRequestOutcome.DEFERRED,
            LiveSettingApplyResult.FAILED to LiveSettingRequestOutcome.FAILED_PENDING,
        ).forEach { (applyResult, expected) ->
            val authority = LiveSettingAuthority(setOf("touch_sound"))
            assertEquals(
                expected,
                authority.applyOrQueueOutcome("touch_sound", "true", "false") { _, _, _ -> applyResult },
            )
            assertEquals(expected.pending, authority.pendingSnapshot().isNotEmpty())
        }
        assertEquals(
            LiveSettingRequestOutcome.REJECTED,
            LiveSettingAuthority(emptySet()).applyOrQueueOutcome("touch_sound", "true", "false") { _, _, _ ->
                LiveSettingApplyResult.APPLIED
            },
        )
    }

    @Test fun `failed hardware apply keeps desired durable and retained actual until replay converges`() {
        val authority = LiveSettingAuthority(setOf("touch_sound"))
        var desired = "false"
        var actual = "false"
        var retained = "OFF"

        val first = authority.applyOrQueueOutcome("touch_sound", "true", desired) { _, value, _ ->
            desired = value
            LiveSettingApplyResult.FAILED
        }

        assertEquals(LiveSettingRequestOutcome.FAILED_PENDING, first)
        assertEquals("true", desired)
        assertEquals("false", actual)
        assertEquals("OFF", retained)
        assertEquals(mapOf("touch_sound" to "true"), authority.pendingSnapshot())

        authority.replay { _, value, _ ->
            desired = value
            actual = value
            retained = if (value == "true") "ON" else "OFF"
            LiveSettingApplyResult.APPLIED
        }

        assertEquals("true", desired)
        assertEquals("true", actual)
        assertEquals("ON", retained)
        assertTrue(authority.pendingSnapshot().isEmpty())
    }

    @Test fun `applied hardware remains reported pending when durable journal cleanup fails`() {
        val journal = FakeJournal().apply { removesSucceed = false }
        val authority = LiveSettingAuthority(setOf("touch_sound"), journal)

        val outcome = authority.applyOrQueueOutcome("touch_sound", "true", "false") { _, _, _ ->
            LiveSettingApplyResult.APPLIED
        }

        assertEquals(LiveSettingRequestOutcome.FAILED_PENDING, outcome)
        assertEquals(mapOf("touch_sound" to "true"), authority.pendingSnapshot())
    }

    @Test fun `persisted desired and retained actual converge only after successful replay`() {
        // The retain flag is no longer something the converger hands its sender: the MQTT edge owns it,
        // and MqttWireGoldenTest pins touch_sound/state as retained on the wire.
        data class Publication(val payload: String, val done: (Boolean) -> Unit)
        val publications = mutableListOf<Publication>()
        var actual = false
        val converger = StateConverger(
            sender = { _, observation, done -> publications += Publication(mqttStatePayload(observation), done) },
            schedule = { it() },
        )
        converger.register(StateConverger.Channel(
            "touch_sound",
            observe = { StateConverger.Observation.Known(if (actual) "ON" else "OFF") },
        ))
        converger.reconcile("touch_sound")
        publications.last().done(true)

        val journal = FakeJournal()
        val authority = LiveSettingAuthority(setOf("touch_sound"), journal)
        assertEquals(
            LiveSettingRequestOutcome.FAILED_PENDING,
            authority.applyOrQueueOutcome("touch_sound", "true", "false") { _, _, _ ->
                LiveSettingApplyResult.FAILED
            },
        )
        converger.reconcile("touch_sound", force = true)
        publications.last().done(true)
        assertEquals("OFF", publications.last().payload)
        assertEquals("true", journal.values.getValue("touch_sound").value)

        authority.replayKeys(setOf("touch_sound")) { _, _, _, _ -> LiveSettingApplyResult.FAILED }
        assertEquals(mapOf("touch_sound" to "true"), authority.pendingSnapshot())
        authority.replayKeys(setOf("touch_sound")) { _, _, _, _ ->
            actual = true
            converger.reconcile("touch_sound", force = true)
            LiveSettingApplyResult.APPLIED
        }
        publications.last().done(true)

        assertEquals("ON", publications.last().payload)
        assertTrue(authority.pendingSnapshot().isEmpty())
        assertTrue(journal.values.isEmpty())
    }

    @Test fun `replay callback holds no authority lock so discard cannot deadlock`() {
        val authority = LiveSettingAuthority(setOf("auto_sleep"))
        authority.applyOrQueueIf(
            "auto_sleep", "false", "true", fence = 2L, expected = { true },
        ) { _, _, _ -> LiveSettingApplyResult.DEFERRED }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val replay = thread {
            authority.replay { _, _, _, _ ->
                entered.countDown()
                release.await(2, TimeUnit.SECONDS)
                LiveSettingApplyResult.APPLIED
            }
        }
        assertTrue(entered.await(2, TimeUnit.SECONDS))

        val discard = thread { authority.discard("auto_sleep") }
        discard.join(1_000L)
        assertFalse("discard blocked behind replay callback", discard.isAlive)
        release.countDown()
        replay.join(1_000L)

        assertFalse(replay.isAlive)
        assertTrue(authority.pendingSnapshot().isEmpty())
    }

    @Test fun `same key applies serialize in accepted order`() {
        val authority = LiveSettingAuthority(setOf("auto_sleep"))
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val applied = mutableListOf<String>()
        val first = thread {
            authority.applyOrQueue("auto_sleep", "false") { _, value ->
                synchronized(applied) { applied += value }
                firstEntered.countDown()
                releaseFirst.await(2, TimeUnit.SECONDS)
                LiveSettingApplyResult.APPLIED
            }
        }
        assertTrue(firstEntered.await(2, TimeUnit.SECONDS))

        val second = thread {
            authority.applyOrQueue("auto_sleep", "true") { _, value ->
                synchronized(applied) { applied += value }
                LiveSettingApplyResult.APPLIED
            }
        }
        second.join(200L)
        assertTrue("new same-key apply did not wait for the admitted apply", second.isAlive)

        releaseFirst.countDown()
        first.join(1_000L)
        second.join(1_000L)
        assertFalse(first.isAlive)
        assertFalse(second.isAlive)
        assertEquals(listOf("false", "true"), synchronized(applied) { applied.toList() })
        assertTrue(authority.pendingSnapshot().isEmpty())
    }

    @Test fun `replay skips a snapshot superseded before its key is admitted`() {
        val authority = LiveSettingAuthority(setOf("blocker", "target"))
        authority.applyOrQueue("blocker", "old") { _, _ -> LiveSettingApplyResult.DEFERRED }
        authority.applyOrQueue("target", "old") { _, _ -> LiveSettingApplyResult.DEFERRED }
        val blockerEntered = CountDownLatch(1)
        val releaseBlocker = CountDownLatch(1)
        val replayed = mutableListOf<Pair<String, String>>()
        val replay = thread {
            authority.replay { key, value ->
                synchronized(replayed) { replayed += key to value }
                if (key == "blocker") {
                    blockerEntered.countDown()
                    releaseBlocker.await(2, TimeUnit.SECONDS)
                }
                LiveSettingApplyResult.APPLIED
            }
        }
        assertTrue(blockerEntered.await(2, TimeUnit.SECONDS))

        assertTrue(authority.applyOrQueue("target", "new") { _, _ -> LiveSettingApplyResult.DEFERRED })
        releaseBlocker.countDown()
        replay.join(1_000L)

        assertFalse(replay.isAlive)
        assertEquals(listOf("blocker" to "old"), synchronized(replayed) { replayed.toList() })
        assertEquals(mapOf("target" to "new"), authority.pendingSnapshot())
    }

    private class FakeJournal : LiveSettingAuthority.Journal {
        val values = linkedMapOf<String, LiveSettingAuthority.Pending>()
        var writesSucceed = true
        var removesSucceed = true
        override fun load(): Map<String, LiveSettingAuthority.Pending> = values.toMap()
        override fun put(key: String, value: LiveSettingAuthority.Pending): Boolean = writesSucceed.also {
            if (it) values[key] = value
        }
        override fun remove(key: String): Boolean = removesSucceed.also {
            if (it) values.remove(key)
        }
    }

    @Test fun recognisedSettingAppliesImmediately() {
        val authority = LiveSettingAuthority(setOf("brightness_bias"))
        val applied = mutableListOf<Pair<String, String>>()

        assertTrue(authority.applyOrQueue("brightness_bias", "12") { key, value ->
            applied += key to value
            LiveSettingApplyResult.APPLIED
        })

        assertEquals(listOf("brightness_bias" to "12"), applied)
        assertTrue(authority.pendingSnapshot().isEmpty())
    }

    @Test fun unknownSettingIsRejectedWithoutCallingRuntime() {
        val authority = LiveSettingAuthority(setOf("brightness_bias"))

        assertFalse(authority.applyOrQueue("unknown", "12") { _, _ ->
            error("unsupported setting reached runtime")
        })
        assertTrue(authority.pendingSnapshot().isEmpty())
    }

    @Test fun drainingRuntimeQueuesOnlyLatestValueAndReplacementReplaysIt() {
        val authority = LiveSettingAuthority(setOf("brightness_bias"))

        assertTrue(authority.applyOrQueue("brightness_bias", "10") { _, _ -> LiveSettingApplyResult.DEFERRED })
        assertTrue(authority.applyOrQueue("brightness_bias", "20") { _, _ -> LiveSettingApplyResult.DEFERRED })
        assertEquals(mapOf("brightness_bias" to "20"), authority.pendingSnapshot())

        val replayed = mutableListOf<Pair<String, String>>()
        authority.replay { key, value ->
            replayed += key to value
            LiveSettingApplyResult.APPLIED
        }

        assertEquals(listOf("brightness_bias" to "20"), replayed)
        assertTrue(authority.pendingSnapshot().isEmpty())
    }

    @Test fun failedOrThrowingReplayRemainsPendingForNextReplacement() {
        val authority = LiveSettingAuthority(setOf("brightness_bias", "navbar_mode"))
        authority.applyOrQueue("brightness_bias", "5") { _, _ -> LiveSettingApplyResult.DEFERRED }
        authority.applyOrQueue("navbar_mode", "Swipe") { _, _ -> throw IllegalStateException("draining") }

        authority.replay { key, _ ->
            if (key == "brightness_bias") LiveSettingApplyResult.APPLIED else LiveSettingApplyResult.FAILED
        }

        assertEquals(mapOf("navbar_mode" to "Swipe"), authority.pendingSnapshot())
    }

    @Test fun queuedValueSurvivesAuthorityRecreationAndClearsOnlyAfterReplay() {
        val journal = FakeJournal()
        val first = LiveSettingAuthority(setOf("brightness_bias"), journal)
        assertTrue(first.applyOrQueue("brightness_bias", "35") { _, _ -> LiveSettingApplyResult.DEFERRED })
        assertEquals("35", journal.values.getValue("brightness_bias").value)
        assertTrue(journal.values.getValue("brightness_bias").generation.isNotBlank())

        val replacement = LiveSettingAuthority(setOf("brightness_bias"), journal)
        val replayed = mutableListOf<Pair<String, String>>()
        replacement.replay { key, value -> replayed += key to value; LiveSettingApplyResult.APPLIED }

        assertEquals(listOf("brightness_bias" to "35"), replayed)
        assertTrue(journal.values.isEmpty())
        assertTrue(replacement.pendingSnapshot().isEmpty())
    }

    @Test fun failedJournalWriteRefusesAcknowledgementAndDoesNotApply() {
        val journal = FakeJournal().apply { writesSucceed = false }
        val authority = LiveSettingAuthority(setOf("brightness_bias"), journal)
        var called = false

        assertFalse(authority.applyOrQueue("brightness_bias", "7") { _, _ ->
            called = true
            LiveSettingApplyResult.APPLIED
        })
        assertFalse(called)
        assertTrue(authority.pendingSnapshot().isEmpty())
    }

    @Test fun admittedApplyFailureIsNotAcknowledgedButRemainsDurableForReplay() {
        val journal = FakeJournal()
        val authority = LiveSettingAuthority(setOf("brightness_bias"), journal)

        assertFalse(authority.applyOrQueue("brightness_bias", "18") { _, _ ->
            LiveSettingApplyResult.FAILED
        })

        assertEquals("18", journal.values.getValue("brightness_bias").value)
        assertTrue(journal.values.getValue("brightness_bias").generation.isNotBlank())
        assertEquals(mapOf("brightness_bias" to "18"), authority.pendingSnapshot())
    }

    @Test fun discardedLegacyKioskIntentCannotReplayAfterRestart() {
        val journal = FakeJournal()
        val first = LiveSettingAuthority(setOf("kiosk_lock"), journal)
        assertTrue(first.applyOrQueue("kiosk_lock", "true", "false") { _, _, _ ->
            LiveSettingApplyResult.DEFERRED
        })
        assertTrue(first.discard("kiosk_lock"))
        assertTrue(first.pendingSnapshot().isEmpty())
        assertTrue(journal.values.isEmpty())

        var replayed = false
        LiveSettingAuthority(setOf("kiosk_lock"), journal).replay { _, _ ->
            replayed = true
            LiveSettingApplyResult.APPLIED
        }
        assertFalse(replayed)
    }

    @Test fun `failed navbar actuation retains desired mode and prior for retry`() {
        val journal = FakeJournal()
        val authority = LiveSettingAuthority(setOf("navbar_mode"), journal)

        assertFalse(authority.applyOrQueue("navbar_mode", "Always on", "Off") { _, _, _ ->
            LiveSettingApplyResult.FAILED
        })

        assertEquals("Always on", journal.values.getValue("navbar_mode").value)
        assertEquals("Off", journal.values.getValue("navbar_mode").previousValue)
        assertTrue(journal.values.getValue("navbar_mode").generation.isNotBlank())
        assertEquals(mapOf("navbar_mode" to "Always on"), authority.pendingSnapshot())
        assertEquals(mapOf("navbar_mode" to "Off"), authority.pendingPreviousSnapshot())
    }

    @Test fun deferredApplyIsAcknowledgedAndLatestValueRemainsQueued() {
        val authority = LiveSettingAuthority(setOf("brightness_bias"))

        assertTrue(authority.applyOrQueue("brightness_bias", "10") { _, _ -> LiveSettingApplyResult.DEFERRED })
        assertTrue(authority.applyOrQueue("brightness_bias", "20") { _, _ -> LiveSettingApplyResult.DEFERRED })

        assertEquals(mapOf("brightness_bias" to "20"), authority.pendingSnapshot())
    }

    @Test fun deferredLatestValueRetainsOriginalPriorAcrossAuthorityRecreation() {
        val journal = FakeJournal()
        val first = LiveSettingAuthority(setOf("update_channel"), journal)

        assertTrue(first.applyOrQueue("update_channel", "prerelease", "stable") { _, _, _ ->
            LiveSettingApplyResult.DEFERRED
        })
        assertTrue(first.applyOrQueue("update_channel", "stable", "prerelease") { _, _, _ ->
            LiveSettingApplyResult.DEFERRED
        })
        assertEquals(mapOf("update_channel" to "stable"), first.pendingSnapshot())
        assertEquals(mapOf("update_channel" to "stable"), first.pendingPreviousSnapshot())

        val replacement = LiveSettingAuthority(setOf("update_channel"), journal)
        var replayed: Triple<String, String, String?>? = null
        replacement.replay { key, value, previous ->
            replayed = Triple(key, value, previous)
            LiveSettingApplyResult.APPLIED
        }

        assertEquals(Triple("update_channel", "stable", "stable"), replayed)
        assertTrue(replacement.pendingSnapshot().isEmpty())
    }

    @Test fun deferredChannelReplayAfterRestartReceivesPrecommitProvenance() {
        val journal = FakeJournal()
        val first = LiveSettingAuthority(setOf("update_channel"), journal)
        assertTrue(first.applyOrQueue("update_channel", "prerelease", "stable") { _, _, _ ->
            LiveSettingApplyResult.DEFERRED
        })

        val replacement = LiveSettingAuthority(setOf("update_channel"), journal)
        var replayPrevious: String? = null
        replacement.replay { _, _, previous ->
            replayPrevious = previous
            LiveSettingApplyResult.APPLIED
        }

        assertEquals("stable", replayPrevious)
    }

    @Test fun `durable safety fence survives authority recreation`() {
        val journal = FakeJournal()
        val first = LiveSettingAuthority(setOf("auto_sleep"), journal)
        assertTrue(first.applyOrQueueIf(
            "auto_sleep", "false", "true", fence = 42L, expected = { true },
        ) { _, _, _ -> LiveSettingApplyResult.DEFERRED })
        assertEquals(42L, journal.values.getValue("auto_sleep").fence)

        val replacement = LiveSettingAuthority(setOf("auto_sleep"), journal)
        var replayFence: Long? = null
        replacement.replay { _, _, _, fence ->
            replayFence = fence
            LiveSettingApplyResult.APPLIED
        }

        assertEquals(42L, replayFence)
        assertTrue(replacement.pendingSnapshot().isEmpty())
    }
}
