package io.panelassistant.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A value this panel has no way to apply must stay saved, stay journalled and keep being retried, while
 * the Configure page stops promising it is about to apply.
 *
 * The defect this closes: a root-backed apply on a panel whose root path is absent fails on every boot
 * forever, so "Saved settings waiting to apply: silence_boot_chime" stands permanently over a setting
 * nobody touched. Two earlier fixes were rejected for dropping the intent (a strike count that removed
 * the journal entry) and for inferring permanence from a root probe. Neither may come back: the entry
 * is never removed here, and only an applier that actually ran may report unavailability.
 */
class LiveSettingStallTest {
    private class FakeJournal : LiveSettingAuthority.Journal {
        val values = linkedMapOf<String, LiveSettingAuthority.Pending>()
        /** Durable writes. Every one is a `commit()` on the panel, so a replay that learns nothing new
         *  must not perform one. */
        var puts = 0
        override fun load(): Map<String, LiveSettingAuthority.Pending> = values.toMap()
        override fun put(key: String, value: LiveSettingAuthority.Pending): Boolean {
            values[key] = value
            puts++
            return true
        }
        override fun remove(key: String): Boolean {
            values.remove(key)
            return true
        }
    }

    private fun authority(journal: FakeJournal, boot: String?) =
        LiveSettingAuthority(setOf(KEY), journal) { boot }

    private fun LiveSettingAuthority.replayWith(result: LiveSettingApplyResult) {
        replay { _, _, _ -> result }
    }

    @Test fun `intent survives every boot that cannot apply it`() {
        // The rejected first design removed the journal entry after three failed replays. A later root
        // repair must still apply the value, so no number of unavailable boots may drop it.
        val journal = FakeJournal()
        var authority = authority(journal, "boot-0")
        authority.applyOrQueueOutcome(KEY, "true", "false") { _, _, _ -> LiveSettingApplyResult.UNAVAILABLE }

        repeat(20) { boot ->
            authority = authority(journal, "boot-$boot")
            authority.replayWith(LiveSettingApplyResult.UNAVAILABLE)
            assertEquals(
                "boot $boot dropped durable intent",
                mapOf(KEY to "true"),
                authority.pendingSnapshot(),
            )
        }
        assertTrue(journal.values.containsKey(KEY))
    }

    @Test fun `retries within one boot are one observation`() {
        // Same-process retries consumed strikes in the rejected second design. One boot is one reading
        // of one machine state however many times the applier runs in it.
        val journal = FakeJournal()
        val authority = authority(journal, "boot-a")
        authority.applyOrQueueOutcome(KEY, "true", "false") { _, _, _ -> LiveSettingApplyResult.UNAVAILABLE }
        val afterFirstObservation = journal.puts
        repeat(10) { authority.replayWith(LiveSettingApplyResult.UNAVAILABLE) }

        assertEquals(
            "one boot can never be enough evidence to stall",
            emptySet<String>(),
            authority.pendingStalledSnapshot(),
        )
        assertEquals(1, journal.values.getValue(KEY).unavailableBoots.size)
        // Recording is idempotent by the set, but it must also be silent: each of those ten replays
        // would otherwise commit an identical journal entry to storage for nothing.
        assertEquals(
            "a replay that learns nothing must not write",
            afterFirstObservation,
            journal.puts,
        )
    }

    @Test fun `a second distinct boot stalls the entry without discarding it`() {
        val journal = FakeJournal()
        authority(journal, "boot-a").apply {
            applyOrQueueOutcome(KEY, "true", "false") { _, _, _ -> LiveSettingApplyResult.UNAVAILABLE }
            assertEquals(emptySet<String>(), pendingStalledSnapshot())
        }

        // A reboot: a new process loads the same journal and replays it.
        val next = authority(journal, "boot-b")
        assertEquals(mapOf(KEY to "true"), next.pendingSnapshot())
        next.replayWith(LiveSettingApplyResult.UNAVAILABLE)

        assertEquals(setOf(KEY), next.pendingStalledSnapshot())
        assertEquals(
            "a stalled value is still durable desired state",
            mapOf(KEY to "true"),
            next.pendingSnapshot(),
        )
    }

    @Test fun `a repaired panel applies the stalled value and clears it`() {
        val journal = FakeJournal()
        authority(journal, "boot-a")
            .applyOrQueueOutcome(KEY, "true", "false") { _, _, _ -> LiveSettingApplyResult.UNAVAILABLE }
        authority(journal, "boot-b").replayWith(LiveSettingApplyResult.UNAVAILABLE)
        assertEquals(setOf(KEY), authority(journal, "boot-b").pendingStalledSnapshot())

        // Root restored, or the permission finally granted: the retry that never stopped now succeeds.
        val repaired = authority(journal, "boot-c")
        repaired.replayWith(LiveSettingApplyResult.APPLIED)

        assertTrue(repaired.pendingSnapshot().isEmpty())
        assertTrue(repaired.pendingStalledSnapshot().isEmpty())
        assertFalse(journal.values.containsKey(KEY))
    }

    @Test fun `transient failure never stalls however many boots it spans`() {
        // Permanence is a claim only the applier may make. A draining bridge, a helper that has not
        // started yet and a denied root command are all FAILED, and must keep saying "waiting to apply"
        // forever rather than quietly becoming "this panel cannot".
        val journal = FakeJournal()
        var authority = authority(journal, "boot-0")
        authority.applyOrQueueOutcome(KEY, "true", "false") { _, _, _ -> LiveSettingApplyResult.FAILED }

        repeat(10) { boot ->
            authority = authority(journal, "boot-$boot")
            authority.replayWith(LiveSettingApplyResult.FAILED)
        }

        assertEquals(mapOf(KEY to "true"), authority.pendingSnapshot())
        assertEquals(emptySet<String>(), authority.pendingStalledSnapshot())
    }

    @Test fun `a transient failure after an unavailable boot neither adds nor removes evidence`() {
        val journal = FakeJournal()
        authority(journal, "boot-a")
            .applyOrQueueOutcome(KEY, "true", "false") { _, _, _ -> LiveSettingApplyResult.UNAVAILABLE }

        val mixed = authority(journal, "boot-b")
        mixed.replayWith(LiveSettingApplyResult.FAILED)
        assertEquals(
            "a failure is not an observation of absence",
            1,
            journal.values.getValue(KEY).unavailableBoots.size,
        )
        assertEquals(emptySet<String>(), mixed.pendingStalledSnapshot())

        mixed.replayWith(LiveSettingApplyResult.UNAVAILABLE)
        assertEquals(setOf(KEY), mixed.pendingStalledSnapshot())
    }

    @Test fun `restating the same desired value keeps the evidence already gathered`() {
        // The Configure form posts durable desired state back on every unrelated save, so a reset here
        // would flip a stalled entry to "waiting to apply" on each save and re-stall it two boots later.
        val journal = FakeJournal()
        authority(journal, "boot-a")
            .applyOrQueueOutcome(KEY, "true", "false") { _, _, _ -> LiveSettingApplyResult.UNAVAILABLE }
        val stalled = authority(journal, "boot-b")
        stalled.replayWith(LiveSettingApplyResult.UNAVAILABLE)
        assertEquals(setOf(KEY), stalled.pendingStalledSnapshot())

        stalled.applyOrQueueOutcome(KEY, "true", "false") { _, _, _ -> LiveSettingApplyResult.UNAVAILABLE }

        assertEquals(
            "restating an intent says nothing new about the hardware",
            setOf(KEY),
            stalled.pendingStalledSnapshot(),
        )
    }

    @Test fun `a genuinely different desired value starts with no evidence`() {
        val journal = FakeJournal()
        authority(journal, "boot-a")
            .applyOrQueueOutcome(KEY, "true", "false") { _, _, _ -> LiveSettingApplyResult.UNAVAILABLE }
        val stalled = authority(journal, "boot-b")
        stalled.replayWith(LiveSettingApplyResult.UNAVAILABLE)
        assertEquals(setOf(KEY), stalled.pendingStalledSnapshot())

        stalled.applyOrQueueOutcome(KEY, "false", "true") { _, _, _ -> LiveSettingApplyResult.FAILED }

        assertEquals(
            "new intent is judged on its own attempts",
            emptySet<String>(),
            stalled.pendingStalledSnapshot(),
        )
        assertEquals(mapOf(KEY to "false"), stalled.pendingSnapshot())
    }

    @Test fun `a stalled OFF is still reported as the desired value`() {
        // Retiring a pending OFF was rejected because it strands divergent hardware, config and MQTT
        // state with nothing left to say so. The entry stays, and stays visible as desired state.
        val journal = FakeJournal()
        authority(journal, "boot-a")
            .applyOrQueueOutcome(KEY, "false", "true") { _, _, _ -> LiveSettingApplyResult.UNAVAILABLE }
        val stalled = authority(journal, "boot-b")
        stalled.replayWith(LiveSettingApplyResult.UNAVAILABLE)

        assertEquals(setOf(KEY), stalled.pendingStalledSnapshot())
        assertEquals(mapOf(KEY to "false"), stalled.pendingSnapshot())
        assertEquals(mapOf(KEY to "true"), stalled.pendingPreviousSnapshot())
    }

    @Test fun `an unreadable boot identity records nothing rather than guessing`() {
        val journal = FakeJournal()
        var authority = authority(journal, null)
        authority.applyOrQueueOutcome(KEY, "true", "false") { _, _, _ -> LiveSettingApplyResult.UNAVAILABLE }
        repeat(10) {
            authority = authority(journal, null)
            authority.replayWith(LiveSettingApplyResult.UNAVAILABLE)
        }

        assertEquals(
            "an observation that cannot be attributed to a boot is not evidence",
            emptySet<String>(),
            authority.pendingStalledSnapshot(),
        )
        assertEquals(mapOf(KEY to "true"), authority.pendingSnapshot())
        // Nothing was recorded at all. Standing in a placeholder would look harmless while the panel
        // never reads its boot id — every boot would share one identity — but it is not: see below.
        assertEquals(emptySet<String>(), journal.values.getValue(KEY).unavailableBoots)
    }

    @Test fun `a boot with no identity cannot combine with a real one to stall an entry`() {
        // The harm of inventing an identity for an unreadable boot: one placeholder plus one genuine
        // boot reaches the threshold, so a value stalls on a single real observation.
        val journal = FakeJournal()
        authority(journal, null)
            .applyOrQueueOutcome(KEY, "true", "false") { _, _, _ -> LiveSettingApplyResult.UNAVAILABLE }

        val readable = authority(journal, "boot-b")
        readable.replayWith(LiveSettingApplyResult.UNAVAILABLE)

        assertEquals(
            "one real boot is one observation whatever the unreadable boot did",
            emptySet<String>(),
            readable.pendingStalledSnapshot(),
        )
        assertEquals(setOf("boot-b"), journal.values.getValue(KEY).unavailableBoots)
    }

    @Test fun `an unavailable apply is pending exactly like any other unapplied value`() {
        val authority = LiveSettingAuthority(setOf(KEY), FakeJournal()) { "boot-a" }
        assertEquals(
            LiveSettingRequestOutcome.FAILED_PENDING,
            authority.applyOrQueueOutcome(KEY, "true", "false") { _, _, _ ->
                LiveSettingApplyResult.UNAVAILABLE
            },
        )
    }

    @Test fun `nothing but an apply request can create a journal entry`() {
        // The original report's other hypothesis was that a changed default mints desired-not-applied
        // state on its own. Construction reads the journal and never writes it, so a default that flips
        // in code cannot put a key here — only a request to apply a value can.
        val journal = FakeJournal()
        val authority = authority(journal, "boot-a")

        assertTrue(authority.pendingSnapshot().isEmpty())
        assertTrue(authority.pendingStalledSnapshot().isEmpty())
        assertTrue(journal.values.isEmpty())

        authority.replayWith(LiveSettingApplyResult.UNAVAILABLE)
        assertTrue("replay over an empty journal writes nothing", journal.values.isEmpty())
    }

    @Test fun `observations survive the journal round trip`() {
        val journal = FakeJournal()
        authority(journal, "boot-a")
            .applyOrQueueOutcome(KEY, "true", "false") { _, _, _ -> LiveSettingApplyResult.UNAVAILABLE }

        val reloaded = journal.load().getValue(KEY)
        assertEquals(setOf("boot-a"), reloaded.unavailableBoots)
        assertFalse(reloaded.stalled)
        assertTrue(reloaded.copy(unavailableBoots = setOf("boot-a", "boot-b")).stalled)
    }

    @Test fun `observations survive the stored form a reboot actually reads`() {
        // The set living in memory is not what crosses a reboot; this encoding is. An entry that
        // serialises without its boots can never accumulate the second one, and the whole mechanism
        // would be inert on hardware while every in-memory test still passed.
        val stored = LiveSettingAuthority.Pending(
            value = "true",
            previousValue = "false",
            fence = 42L,
            unavailableBoots = setOf("boot-a", "boot-b"),
        )

        val restored = decodeJournalEntry(encodeJournalEntry(stored))

        assertEquals(stored.value, restored.value)
        assertEquals(stored.previousValue, restored.previousValue)
        assertEquals(stored.fence, restored.fence)
        assertEquals(stored.generation, restored.generation)
        assertEquals(setOf("boot-a", "boot-b"), restored.unavailableBoots)
        assertTrue("two stored boots are still a stall after a reboot", restored.stalled)
    }

    @Test fun `a permanently unappliable value does not grow its stored entry every boot`() {
        // Replay never stops, so without a bound the entry would gain one boot identity per reboot for
        // the life of the panel — a journal that grows forever because the hardware never came back.
        val journal = FakeJournal()
        var authority = authority(journal, "boot-0")
        authority.applyOrQueueOutcome(KEY, "true", "false") { _, _, _ -> LiveSettingApplyResult.UNAVAILABLE }

        repeat(40) { boot ->
            authority = authority(journal, "boot-$boot")
            authority.replayWith(LiveSettingApplyResult.UNAVAILABLE)
        }

        assertEquals(
            "evidence stops accumulating once it has answered the question",
            LiveSettingAuthority.STALL_OBSERVATION_BOOTS,
            journal.values.getValue(KEY).unavailableBoots.size,
        )
        assertEquals(setOf(KEY), authority.pendingStalledSnapshot())
        assertEquals(mapOf(KEY to "true"), authority.pendingSnapshot())
    }

    @Test fun `an entry stored before this change decodes with no observations`() {
        val legacy = decodeJournalEntry("""{"value":"true","generation":"g1"}""")
        assertEquals(emptySet<String>(), legacy.unavailableBoots)
        assertFalse(legacy.stalled)

        // Older still: the journal once held the bare desired value with no JSON around it.
        val ancient = decodeJournalEntry("true")
        assertEquals("true", ancient.value)
        assertEquals(emptySet<String>(), ancient.unavailableBoots)
    }

    @Test fun `boot identity is read once at construction and never under a lock`() {
        // Rejected finding 5 against the second design was a root probe running under the authority
        // lock. Reading the identity once, at construction, is what keeps every apply path free of I/O.
        var reads = 0
        val journal = FakeJournal()
        val authority = LiveSettingAuthority(setOf(KEY), journal) { reads++; "boot-a" }
        assertEquals("construction reads it exactly once", 1, reads)

        authority.applyOrQueueOutcome(KEY, "true", "false") { _, _, _ -> LiveSettingApplyResult.UNAVAILABLE }
        authority.replayWith(LiveSettingApplyResult.UNAVAILABLE)
        authority.replayWith(LiveSettingApplyResult.APPLIED)
        authority.discard(KEY)

        assertEquals("no apply, replay or discard path may read it again", 1, reads)
    }

    private companion object {
        const val KEY = "silence_boot_chime"
    }
}
