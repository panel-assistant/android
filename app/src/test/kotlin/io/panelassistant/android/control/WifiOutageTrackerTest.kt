package io.panelassistant.android.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiOutageTrackerTest {
    /** Test-owned clocks. Wall and monotonic advance together unless a test moves one alone. */
    private class Clock(
        var wallMs: Long = WALL_START,
        var elapsedMs: Long = 100_000L,
    ) {
        fun advance(ms: Long) {
            wallMs += ms
            elapsedMs += ms
        }
    }

    private class FakeStore(var record: WifiOutageRecord? = null) : WifiOutageStore {
        var saves = 0
        override fun load(): WifiOutageRecord? = record
        override fun save(record: WifiOutageRecord) {
            this.record = record
            saves++
        }
    }

    private fun tracker(clock: Clock, store: WifiOutageStore? = null) = WifiOutageTracker(
        store = store,
        wallClockMs = { clock.wallMs },
        elapsedRealtimeMs = { clock.elapsedMs },
    )

    /** One loss→recovery round trip on a Wi-Fi default network, transport known throughout. */
    private fun Clock.blip(t: WifiOutageTracker, downMs: Long = 5_000L) {
        t.onDefaultLost()
        advance(downMs)
        t.onDefaultAvailable(NET_A); t.onTransportChanged(NET_A, true)
    }

    /** Space successive blips far enough apart that none of them merge. */
    private fun Clock.separate(t: WifiOutageTracker) {
        advance(WifiOutageTracker.MERGE_WINDOW_MS + 1L)
        t.counts()
    }

    @Test fun blipIsCountedAtRecoveryInBothWindows() {
        val clock = Clock()
        val t = tracker(clock)
        t.onDefaultAvailable(NET_A); t.onTransportChanged(NET_A, true)
        clock.blip(t)
        val counts = t.counts()
        assertEquals(1, counts.last24h)
        assertEquals(1, counts.last24h)
    }

    @Test fun openEpisodeIsNotCountedUntilRecovery() {
        val clock = Clock()
        val t = tracker(clock)
        t.onDefaultAvailable(NET_A); t.onTransportChanged(NET_A, true)
        t.onDefaultLost()
        clock.advance(5_000L)
        assertEquals(0, t.counts().last24h)   // still down — nothing to report, nobody listening anyway
        t.onDefaultAvailable(NET_A); t.onTransportChanged(NET_A, true)
        assertEquals(1, t.counts().last24h)
    }

    @Test fun lossOfANonWifiDefaultIsNotAWifiOutage() {
        val clock = Clock()
        val t = tracker(clock)
        t.onDefaultAvailable(NET_A); t.onTransportChanged(NET_A, false)           // Ethernet is the default network
        clock.blip(t)
        assertEquals(0, t.counts().last24h)
        assertNull(t.statusText())
    }

    @Test fun lossBeforeAnyTransportKnowledgeIsNotCounted() {
        val clock = Clock()
        val t = tracker(clock)
        clock.blip(t)                          // no transport ever reported
        assertEquals(0, t.counts().last24h)
    }

    @Test fun transportChangeToNonWifiStopsCounting() {
        val clock = Clock()
        val t = tracker(clock)
        t.onDefaultAvailable(NET_A); t.onTransportChanged(NET_A, true)
        t.onTransportChanged(NET_A, false)            // the default network became Ethernet in place
        clock.blip(t)
        assertEquals(0, t.counts().last24h)
    }

    // ---- unknown recovery transport is parked, never assumed -----------------------------------

    @Test fun unknownRecoveryTransportIsNotCountedUntilTheAuthoritativeAnswerArrives() {
        val clock = Clock()
        val t = tracker(clock)
        t.onDefaultAvailable(NET_A); t.onTransportChanged(NET_A, true)
        t.onDefaultLost()
        clock.advance(4_000L)
        t.onDefaultAvailable(NET_B)             // onAvailable beat the capability callback
        assertEquals(0, t.counts().last24h)     // nothing committed on an assumption
        t.onTransportChanged(NET_B, true)       // …Wi-Fi after all
        val counts = t.counts()
        assertEquals(1, counts.last24h)
    }

    @Test fun anUnknownRecoveryThatTurnsOutToBeEthernetIsNeverCounted() {
        val clock = Clock()
        val store = FakeStore()
        val t = tracker(clock, store)
        t.onDefaultAvailable(NET_A); t.onTransportChanged(NET_A, true)
        t.onDefaultLost()
        clock.advance(4_000L)
        t.onDefaultAvailable(NET_B)
        clock.advance(300L)
        t.onTransportChanged(NET_A, false)            // the replacement was Ethernet/VPN, not Wi-Fi
        assertEquals(0, t.counts().last24h)
        assertEquals(0, store.saves)           // and nothing false was ever persisted
    }

    @Test fun lossWhileARecoveryIsUnresolvedDiscardsTheUnattributableEpisode() {
        val clock = Clock()
        val t = tracker(clock)
        t.onDefaultAvailable(NET_A); t.onTransportChanged(NET_A, true)
        t.onDefaultLost()
        clock.advance(3_000L)
        t.onDefaultAvailable(NET_B)             // transport unknown…
        clock.advance(1_000L)
        t.onDefaultLost()                      // …and gone again before it was ever answered
        clock.advance(2_000L)
        t.onDefaultAvailable(NET_A); t.onTransportChanged(NET_A, true)
        t.onTransportChanged(NET_A, true)
        // The first episode can no longer be attributed to any network, and the second never
        // opened because the transport was unknown when it was lost. Undercount, never invent.
        assertEquals(0, t.counts().last24h)
    }

    @Test fun aLossObservedWhileTheTransportIsUnknownOpensNothing() {
        val clock = Clock()
        val t = tracker(clock)
        t.onDefaultAvailable(NET_B)             // brand-new default, capabilities not yet published
        clock.blip(t)
        assertEquals(0, t.counts().last24h)
    }

    // ---- duplicate callbacks and flap merging ---------------------------------------------------

    @Test fun duplicateCallbacksAreOneEpisode() {
        val clock = Clock()
        val t = tracker(clock)
        t.onDefaultAvailable(NET_A); t.onTransportChanged(NET_A, true)
        t.onDefaultLost()
        clock.advance(2_000L)
        t.onDefaultLost()                      // duplicate loss while already down
        clock.advance(4_000L)
        t.onDefaultAvailable(NET_A); t.onTransportChanged(NET_A, true)
        t.onDefaultAvailable(NET_A); t.onTransportChanged(NET_A, true)             // duplicate recovery
        val counts = t.counts()
        assertEquals(1, counts.last24h)
    }

    @Test fun immediateReflapMergesIntoOneEpisode() {
        val clock = Clock()
        val t = tracker(clock)
        t.onDefaultAvailable(NET_A); t.onTransportChanged(NET_A, true)
        clock.blip(t)
        clock.advance(WifiOutageTracker.MERGE_WINDOW_MS)   // re-loss exactly at the window edge
        clock.blip(t, downMs = 3_000L)
        assertEquals(1, t.counts().last24h)
    }

    @Test fun reflapBeyondTheMergeWindowIsASecondEpisode() {
        val clock = Clock()
        val t = tracker(clock)
        t.onDefaultAvailable(NET_A); t.onTransportChanged(NET_A, true)
        clock.blip(t)
        clock.advance(WifiOutageTracker.MERGE_WINDOW_MS + 1L)
        clock.blip(t)
        assertEquals(2, t.counts().last24h)
    }

    @Test fun continuousFlappingKeepsCountingInsteadOfMergingForever() {
        val clock = Clock()
        val t = tracker(clock)
        t.onDefaultAvailable(NET_A); t.onTransportChanged(NET_A, true)
        // 5 s down / 8 s up all day — the worst possible Wi-Fi. The merge anchor moves only at a
        // COUNTED recovery, so this counts every second cycle instead of chaining merged episodes
        // into a single count forever (which would silence the diagnostic at its worst case).
        repeat(20) {
            t.onDefaultLost()
            clock.advance(5_000L)
            t.onDefaultAvailable(NET_A); t.onTransportChanged(NET_A, true)
            clock.advance(8_000L)
        }
        assertEquals(10, t.counts().last24h)
        assertTrue(t.statusText()!!.endsWith("needs attention"))
    }

    @Test fun aMergedContinuationChangesNeitherTheCountNorTheReportedDuration() {
        val clock = Clock()
        val store = FakeStore()
        val t = tracker(clock, store)
        t.onDefaultAvailable(NET_A); t.onTransportChanged(NET_A, true)
        clock.blip(t, downMs = 5_000L)
        clock.advance(2_000L)
        clock.blip(t, downMs = 3_000L)         // merged continuation of the same disturbance
        val counts = t.counts()
        assertEquals(1, counts.last24h)
        assertEquals(1, store.record!!.episodeStartsWallMs.size)   // and the record agrees
    }

    @Test fun aMergedContinuationReadsTheSameAfterARestart() {
        val clock = Clock()
        val store = FakeStore()
        val first = tracker(clock, store)
        first.onDefaultAvailable(NET_A); first.onTransportChanged(NET_A, true)
        clock.blip(first, downMs = 5_000L)
        clock.advance(2_000L)
        clock.blip(first, downMs = 3_000L)     // merged
        val before = first.statusText()

        val second = tracker(clock, store)     // process restart, same store
        assertEquals(before, second.statusText())
    }

    @Test fun wifiLossRecoveredByEthernetIsDiscardedNotCounted() {
        val clock = Clock()
        val t = tracker(clock)
        t.onDefaultAvailable(NET_A); t.onTransportChanged(NET_A, true)
        t.onDefaultLost()
        clock.advance(4_000L)
        t.onDefaultAvailable(NET_A); t.onTransportChanged(NET_A, false)            // the outage ended by deliberately leaving Wi-Fi
        val counts = t.counts()
        assertEquals(0, counts.last24h)
        clock.blip(t)                          // and losses of the Ethernet default stay ignored
        assertEquals(0, t.counts().last24h)
    }

    // ---- the named windows mean exactly what they say -------------------------------------------

    @Test fun anEpisodeJustInsideTheNamedWindowIsStillCounted() {
        val clock = Clock()
        val t = tracker(clock)
        t.onDefaultAvailable(NET_A); t.onTransportChanged(NET_A, true)
        // Deliberately late in the hour, then read early in the hour 24 hour-numbers later: this
        // is the exact shape whole-hour arithmetic gets wrong. The episode is 23 h 20 m old — well
        // inside "the last 24 h" — but its hour NUMBER is 24 behind, so bucket comparison dropped
        // it up to 59 minutes early, under-reporting the window and the attention threshold.
        clock.advance(50L * 60_000L)
        clock.blip(t)
        clock.advance(23L * 3_600_000L + 20L * 60_000L)
        assertEquals(1, t.counts().last24h)
    }



    @Test fun aReversibleClockJumpMovesTheCountsAndMovesThemBack() {
        val clock = Clock()
        val store = FakeStore()
        val t = tracker(clock, store)
        t.onDefaultAvailable(NET_A); t.onTransportChanged(NET_A, true)
        clock.blip(t)
        assertEquals(1, t.counts().last24h)

        clock.wallMs -= 2L * 24L * 3_600_000L      // NTP correction after a bad RTC
        assertEquals(0, t.counts().last24h)          // now future-dated, so excluded…
        assertEquals(1, store.record!!.episodeStartsWallMs.size)  // …but never deleted

        clock.wallMs += 2L * 24L * 3_600_000L      // clock corrected back
        assertEquals(1, t.counts().last24h)          // and the history is still there
        assertEquals(1, tracker(clock, store).counts().last24h)     // identical after a restart
    }


    // ---- persistence -----------------------------------------------------------------------------

    @Test fun persistedCountsSurviveARestart() {
        val clock = Clock()
        val store = FakeStore()
        val first = tracker(clock, store)
        first.onDefaultAvailable(NET_A); first.onTransportChanged(NET_A, true)
        clock.blip(first)
        clock.advance(WifiOutageTracker.MERGE_WINDOW_MS + 1L)
        clock.blip(first, downMs = 7_000L)

        val second = tracker(clock, store)     // process restart: fresh tracker, same store
        val counts = second.counts()
        assertEquals(2, counts.last24h)
        assertEquals(2, counts.last24h)
    }

    @Test fun episodeOpenAtProcessDeathIsDroppedDeliberately() {
        val clock = Clock()
        val store = FakeStore()
        val first = tracker(clock, store)
        first.onDefaultAvailable(NET_A); first.onTransportChanged(NET_A, true)
        first.onDefaultLost()                  // dies mid-outage — a power cut is not a Wi-Fi blip
        val second = tracker(clock, store)
        assertEquals(0, second.counts().last24h)
    }

    @Test fun storedEpisodesOutsideTheWindowAreExcludedFromCountsWithoutBeingDestroyed() {
        val clock = Clock()
        val store = FakeStore(
            WifiOutageRecord(
                episodeStartsWallMs = listOf(
                    clock.wallMs - 8L * 24L * 3_600_000L,   // 8 days old — out
                    clock.wallMs + 48L * 3_600_000L,        // future-dated — out
                    clock.wallMs - 30L * 60_000L,           // half an hour ago — in both windows
                    clock.wallMs - 48L * 3_600_000L,        // two days ago — 7 d only
                ),
            ),
        )
        val t = tracker(clock, store)
        val counts = t.counts()
        assertEquals(1, counts.last24h)
        assertEquals(1, counts.last24h)
        // Excluded by the window filter, not deleted: the record still holds all four instants.
        assertEquals(4, store.record!!.episodeStartsWallMs.size)
    }

    @Test fun everyCountedEpisodeIsPersistedAsItIsCounted() {
        val clock = Clock()
        val store = FakeStore()
        val t = tracker(clock, store)
        t.onDefaultAvailable(NET_A); t.onTransportChanged(NET_A, true)
        // No read intervenes, and no time passes beyond the episodes themselves: a restart at any
        // point after a count must still see it, so there is no window in which a counted episode
        // lives only in memory.
        repeat(4) {
            clock.blip(t)
            clock.advance(WifiOutageTracker.MERGE_WINDOW_MS + 1L)
        }
        assertEquals(4, store.saves)
        assertEquals(4, store.record!!.episodeStartsWallMs.size)
        assertEquals(4, tracker(clock, store).counts().last24h)   // restart immediately after
    }

    @Test fun aCountedEpisodeSurvivesRestartWithNoInterveningRead() {
        val clock = Clock()
        val store = FakeStore()
        val first = tracker(clock, store)
        first.onDefaultAvailable(NET_A); first.onTransportChanged(NET_A, true)
        clock.blip(first, downMs = 7_000L)
        // Process dies here — nothing read the counts, no interval elapsed.
        val second = tracker(clock, store)
        val counts = second.counts()
        assertEquals(1, counts.last24h)
    }

    @Test fun aCappedWeekReportsAFloorInsteadOfPresentingTheCapAsATotal() {
        val clock = Clock()
        val t = tracker(clock)
        t.onDefaultAvailable(NET_A); t.onTransportChanged(NET_A, true)
        repeat(WifiOutageTracker.MAX_RETAINED_EPISODES + 1) {
            t.onDefaultLost()
            clock.advance(1_000L)
            t.onDefaultAvailable(NET_A); t.onTransportChanged(NET_A, true)
            clock.advance(WifiOutageTracker.MERGE_WINDOW_MS + 1L)
        }
        val counts = t.counts()
        assertEquals(WifiOutageTracker.MAX_RETAINED_EPISODES, counts.last24h)
        assertTrue("the count is a floor once the cap drops an in-window episode", counts.saturated)
        assertTrue(
            "the row must say the number is a floor",
            t.statusText()!!.startsWith("more than "),
        )
    }

    @Test fun anUncappedWeekIsNotReportedAsAFloor() {
        val clock = Clock()
        val t = tracker(clock)
        t.onDefaultAvailable(NET_A); t.onTransportChanged(NET_A, true)
        clock.blip(t)
        assertFalse(t.counts().saturated)
        assertFalse(t.counts().saturated)
        assertFalse(t.statusText()!!.startsWith("more than "))
    }

    @Test fun saturationClearsOnceEveryDroppedEpisodeHasLeftTheWindow() {
        val clock = Clock()
        val t = tracker(clock)
        t.onDefaultAvailable(NET_A); t.onTransportChanged(NET_A, true)
        repeat(WifiOutageTracker.MAX_RETAINED_EPISODES + 1) {
            t.onDefaultLost()
            clock.advance(1_000L)
            t.onDefaultAvailable(NET_A); t.onTransportChanged(NET_A, true)
            clock.advance(WifiOutageTracker.MERGE_WINDOW_MS + 1L)
        }
        assertTrue(t.counts().saturated)
        clock.advance(WifiOutageTracker.WINDOW_MS)
        assertFalse("a week later nothing dropped is still in-window", t.counts().saturated)
    }


    @Test fun aBackwardClockCorrectionAtCapacityKeepsTheCurrentStormVisible() {
        val clock = Clock()
        val t = tracker(clock)
        t.onDefaultAvailable(NET_A); t.onTransportChanged(NET_A, true)
        // Fill retention while the clock is two days fast, then correct it backwards.
        repeat(WifiOutageTracker.MAX_RETAINED_EPISODES) {
            t.onDefaultLost()
            clock.advance(1_000L)
            t.onDefaultAvailable(NET_A); t.onTransportChanged(NET_A, true)
            clock.advance(WifiOutageTracker.MERGE_WINDOW_MS + 1L)
        }
        clock.wallMs -= 2L * 24L * 3_600_000L
        // Episodes happening NOW must survive: eviction drops what was recorded longest ago, so a
        // real current storm cannot be discarded in favour of stale future-dated history.
        repeat(10) {
            t.onDefaultLost()
            clock.advance(1_000L)
            t.onDefaultAvailable(NET_A); t.onTransportChanged(NET_A, true)
            clock.advance(WifiOutageTracker.MERGE_WINDOW_MS + 1L)
        }
        assertEquals(10, t.counts().last24h)
        assertTrue(t.statusText() != null)
    }



    @Test fun anOversizedRecordKeepsItsLowerBoundProvenance() {
        val clock = Clock()
        val oversized = (1..(WifiOutageTracker.MAX_RETAINED_EPISODES + 25))
            .map { clock.wallMs - it * 1_000L }
            .reversed()
        val encoded = encodeWifiOutageRecord(
            WifiOutageRecord(oversized, 0L),
        )
        val parsed = parseWifiOutageRecord(encoded)!!
        assertEquals(WifiOutageTracker.MAX_RETAINED_EPISODES, parsed.episodeStartsWallMs.size)
        assertTrue(
            "truncating an over-cap record must not erase that events were dropped",
            parsed.newestDroppedWallMs > 0L,
        )
        val store = FakeStore(parsed)
        assertTrue("and the counts must present themselves as floors", tracker(clock, store).counts().saturated)
    }

    @Test fun aFutureDatedDropMarkerStillReportsTheCountAsAFloor() {
        val clock = Clock()
        val t = tracker(clock)
        t.onDefaultAvailable(NET_A); t.onTransportChanged(NET_A, true)
        repeat(WifiOutageTracker.MAX_RETAINED_EPISODES + 1) {
            t.onDefaultLost()
            clock.advance(1_000L)
            t.onDefaultAvailable(NET_A); t.onTransportChanged(NET_A, true)
            clock.advance(WifiOutageTracker.MERGE_WINDOW_MS + 1L)
        }
        assertTrue(t.counts().saturated)
        // A rollback leaves the dropped marker dated in the future. An instant we cannot place is
        // still an episode we dropped, so the capped count must not start claiming to be exact.
        clock.wallMs -= 2L * 3_600_000L
        assertTrue("a future-dated marker still means the count is a floor", t.counts().saturated)
    }

    @Test fun timeOnAnotherTransportEndsTheDisturbance() {
        val clock = Clock()
        val t = tracker(clock)
        t.onDefaultAvailable(NET_A); t.onTransportChanged(NET_A, true)
        clock.blip(t)                                   // counted; merge anchor set
        assertEquals(1, t.counts().last24h)
        t.onTransportChanged(NET_A, false)                     // the panel moves to Ethernet/VPN
        clock.advance(2_000L)
        t.onTransportChanged(NET_A, true)                      // and back to Wi-Fi
        clock.advance(2_000L)
        clock.blip(t)
        // Within ten seconds of the earlier counted recovery, but the non-Wi-Fi excursion ended
        // that disturbance: this is a new episode, not a continuation of one that already stopped.
        assertEquals(2, t.counts().last24h)
    }

    @Test fun anOversizedRecordIsRefusedBeforeItIsParsed() {
        // Provenance is present and valid, so ONLY the pre-parse size bound can refuse this record.
        val huge = "{\"version\":${WifiOutageTracker.RECORD_VERSION},\"newest_dropped_wall_ms\":0," +
            "\"episodes\":[" + (1..20_000).joinToString(",") { "1700000000000" } + "]}"
        assertTrue(huge.length > WifiOutageTracker.MAX_RECORD_CHARS)
        // org.json materialises everything it is handed, so the bound has to be applied to the raw
        // input; a record this app wrote cannot approach the limit.
        assertNull(parseWifiOutageRecord(huge))
    }

    @Test fun aSuccessorNetworkCannotResolveItsPredecessorsPendingRecovery() {
        val clock = Clock()
        val t = tracker(clock)
        t.onDefaultAvailable(NET_A); t.onTransportChanged(NET_A, true)
        t.onDefaultLost()
        clock.advance(4_000L)
        t.onDefaultAvailable(NET_B)            // B ended the episode; its transport is unknown
        clock.advance(500L)
        t.onDefaultAvailable(NET_C)            // C replaces B before B was ever answered
        t.onTransportChanged(NET_C, true)      // C is Wi-Fi — but it did not end that episode
        assertEquals("only the network that ended an episode may decide what it was", 0, t.counts().last24h)
    }

    @Test fun aStaleTransportCallbackForAnOldNetworkIsIgnored() {
        val clock = Clock()
        val t = tracker(clock)
        t.onDefaultAvailable(NET_A); t.onTransportChanged(NET_A, true)
        t.onDefaultLost()
        clock.advance(3_000L)
        t.onDefaultAvailable(NET_B)
        t.onTransportChanged(NET_A, true)      // late callback naming the network that is gone
        assertEquals(0, t.counts().last24h)
        t.onTransportChanged(NET_B, true)      // the current network answers
        assertEquals(1, t.counts().last24h)
    }

    @Test fun aRestoredRecordReplacesLiveHistoryInsteadOfBeingOverwritten() {
        val clock = Clock()
        val store = FakeStore()
        val t = tracker(clock, store)
        t.onDefaultAvailable(NET_A); t.onTransportChanged(NET_A, true)
        clock.blip(t)
        assertEquals(1, t.counts().last24h)

        // A settings restore rewrites app_state underneath the running process.
        store.record = WifiOutageRecord(
            episodeStartsWallMs = listOf(clock.wallMs - 60_000L, clock.wallMs - 30_000L, clock.wallMs - 10_000L),
            newestDroppedWallMs = 0L,
        )
        t.adoptRestoredRecord()
        assertEquals("restored history wins over what the live tracker was holding", 3, t.counts().last24h)

        // …and the next counted episode extends the restored history rather than the discarded one.
        clock.advance(WifiOutageTracker.MERGE_WINDOW_MS + 1L)
        t.onDefaultAvailable(NET_A); t.onTransportChanged(NET_A, true)
        clock.blip(t)
        assertEquals(4, t.counts().last24h)
        assertEquals(4, store.record!!.episodeStartsWallMs.size)
    }

    @Test fun droppedEvidenceKeepsTheRowVisibleEvenWhenNothingIsLeftInTheWindow() {
        val clock = Clock()
        val store = FakeStore(
            WifiOutageRecord(episodeStartsWallMs = emptyList(), newestDroppedWallMs = clock.wallMs - 60_000L),
        )
        val t = tracker(clock, store)
        val counts = t.counts()
        assertEquals(0, counts.last24h)
        assertTrue(counts.saturated)
        // Asserted without !!: a suppressed row must fail this as an AssertionError, not blow up
        // with an NPE, which the mutation session correctly refuses to credit as a kill.
        val row = t.statusText()
        assertNotNull("a saturated zero must still be reported", row)
        // Never "0 outages" and never "at least 0": with nothing left in the window the only
        // truthful statement is that the panel dropped more than it can still account for.
        assertEquals(
            "more outages in the last 24 h than the panel kept records for" +
                " — repeated drops; the Wi-Fi link needs attention",
            row,
        )
    }

    @Test fun unreadableDropProvenanceFailsClosedThenAgesOutEvenAcrossRestarts() {
        val clock = Clock()
        val encoded = """{"version":${WifiOutageTracker.RECORD_VERSION},"episodes":[],"newest_dropped_wall_ms":"corrupt"}"""
        val parsed = parseWifiOutageRecord(encoded)!!
        assertEquals(WifiOutageTracker.UNPLACEABLE_DROP_MARKER, parsed.newestDroppedWallMs)
        val store = FakeStore(parsed)
        val first = tracker(clock, store)
        assertTrue("dropped-but-unplaceable must not read as 'nothing was dropped'", first.counts().saturated)
        // The anchor is written down, so restarting cannot slide it forward and keep the panel
        // saturated forever — the floor expires one window after it was FIRST seen.
        assertEquals(clock.wallMs, store.record!!.newestDroppedWallMs)
        clock.advance(WifiOutageTracker.WINDOW_MS / 2)
        assertTrue(tracker(clock, store).counts().saturated)
        clock.advance(WifiOutageTracker.WINDOW_MS)
        assertFalse("it ages out rather than saturating forever", tracker(clock, store).counts().saturated)
    }

    @Test fun aRecordWithoutProvenanceIsNotOursAndStartsClean() {
        val encoded = """{"version":${WifiOutageTracker.RECORD_VERSION},"episodes":[1700000000000]}"""
        assertNull("this app always writes provenance; a record without it is unsupported", parseWifiOutageRecord(encoded))
    }

    @Test fun arbitraryFutureProvenanceCannotPinSaturationOpen() {
        val clock = Clock()
        val store = FakeStore(
            WifiOutageRecord(
                episodeStartsWallMs = emptyList(),
                newestDroppedWallMs = clock.wallMs + 3_650L * 24L * 3_600_000L,   // a decade ahead
            ),
        )
        val t = tracker(clock, store)
        assertTrue(t.counts().saturated)
        assertEquals("the marker is clamped to now, not trusted", clock.wallMs, store.record!!.newestDroppedWallMs)
        clock.advance(WifiOutageTracker.WINDOW_MS + 60_000L)
        assertFalse("so it expires like any other marker", t.counts().saturated)
    }

    @Test fun recordRoundTripsAndUnknownVersionsStartClean() {
        val record = WifiOutageRecord(listOf(1_000L, 2_000L, 3_000L), 500L)
        assertEquals(record, parseWifiOutageRecord(encodeWifiOutageRecord(record)))
        val futureVersion = encodeWifiOutageRecord(record)
            .replace("\"version\":${WifiOutageTracker.RECORD_VERSION}", "\"version\":99")
        assertNull(parseWifiOutageRecord(futureVersion))
        assertNull(parseWifiOutageRecord("not json"))
        assertNull(parseWifiOutageRecord(null))
        assertNull(parseWifiOutageRecord(""))
    }

    // ---- what the user actually reads ------------------------------------------------------------

    @Test fun attentionRuleTripsOnTheObservedBadDayNeverOnASingleBlip() {
        assertFalse(wifiOutageAttention(1))      // one blip: silence
        assertFalse(wifiOutageAttention(5))     // just under both rails
        assertTrue(wifiOutageAttention(6))       // 24 h rail
        assertTrue(wifiOutageAttention(11))     // the measured bad day
    }

    @Test fun statusTextIsNullWhileCleanThenReportsCountsAndDuration() {
        val clock = Clock()
        val t = tracker(clock)
        t.onDefaultAvailable(NET_A); t.onTransportChanged(NET_A, true)
        assertNull(t.statusText())
        clock.blip(t, downMs = 6_000L)
        assertEquals("1 outage in the last 24 h", t.statusText())
    }

    @Test fun statusTextEscalatesAtTheAttentionThreshold() {
        val clock = Clock()
        val t = tracker(clock)
        t.onDefaultAvailable(NET_A); t.onTransportChanged(NET_A, true)
        repeat(WifiOutageTracker.ATTENTION_24H) {
            clock.blip(t, downMs = 4_000L)
            clock.advance(WifiOutageTracker.MERGE_WINDOW_MS + 1L)
        }
        assertEquals(
            "6 outages in the last 24 h — repeated drops; the Wi-Fi link needs attention",
            t.statusText(),
        )
    }

    // ---- chronic: the bar for entering the pasted /diag report -------------------------------------

    @Test fun anOrdinaryDayOfBlipsIsNotChronic() {
        // The panel's own card still shows these. A pasted bug report should not carry them: a
        // handful of four-second blips is not evidence about the bug somebody is reporting.
        for (count in 0 until WifiOutageTracker.ATTENTION_24H) {
            assertFalse(
                "$count outages must not reach a bug report",
                wifiOutageChronic(WifiOutageCounts(last24h = count, saturated = false)),
            )
        }
    }

    @Test fun theAttentionThresholdIsAlsoTheChronicThreshold() {
        // One bar, not two: whatever tells the panel's owner the link needs attention is exactly
        // what a maintainer reading their report needs to know.
        assertTrue(
            wifiOutageChronic(WifiOutageCounts(last24h = WifiOutageTracker.ATTENTION_24H, saturated = false)),
        )
    }

    @Test fun droppedEvidenceIsChronicEvenWhenTheSurvivingCountIsSmall() {
        // Saturation means episodes were evicted at the retention bound, or that provenance was
        // unreadable and failed closed — so the number is a floor, not a total. Reading only the
        // count would omit the line from precisely the panel whose report most needs it.
        assertTrue(wifiOutageChronic(WifiOutageCounts(last24h = 0, saturated = true)))
        assertTrue(wifiOutageChronic(WifiOutageCounts(last24h = 1, saturated = true)))
    }

    // ---- one classification behind both surfaces ---------------------------------------------------
    //
    // The defect these cover: the row derived its attention note from the raw 24-hour count while
    // `/diag` derived its gate from wifiOutageChronic. A saturated panel could therefore print a
    // bare low number on its own card at the same instant the pasted report called it chronic.

    @Test fun anOrdinaryQuietDayIsSilentOrExactAndCarriesNoNote() {
        assertNull(
            "a clean window is a silent row, not a permanent zero",
            wifiOutageStatusText(WifiOutageCounts(last24h = 0, saturated = false)),
        )
        assertEquals(
            "1 outage in the last 24 h",
            wifiOutageStatusText(WifiOutageCounts(last24h = 1, saturated = false)),
        )
        assertEquals(
            "2 outages in the last 24 h",
            wifiOutageStatusText(WifiOutageCounts(last24h = 2, saturated = false)),
        )
    }

    @Test fun aSaturatedLowCountIsAFloorAndNeverReadsAsAQuietDay() {
        // The count survived compaction; the window held more. Stating it as "1 outage" — or even
        // as the arithmetically-true "at least 1 outage" — hands the reader a quiet day.
        for (surviving in 0..2) {
            val text = wifiOutageStatusText(WifiOutageCounts(last24h = surviving, saturated = true))
            assertNotNull("a compacted window must still be reported", text)
            val row = text.orEmpty()
            assertTrue(
                "$surviving surviving must carry the attention note, exactly as /diag would",
                row.endsWith(" — repeated drops; the Wi-Fi link needs attention"),
            )
            assertFalse(
                "$surviving surviving must not be stated as the incident rate",
                row.startsWith("$surviving outage"),
            )
            assertEquals(
                "both surfaces classify one moment identically",
                wifiOutageChronic(WifiOutageCounts(last24h = surviving, saturated = true)),
                row.contains("needs attention"),
            )
        }
        assertEquals(
            "more than 1 outage in the last 24 h — repeated drops; the Wi-Fi link needs attention",
            wifiOutageStatusText(WifiOutageCounts(last24h = 1, saturated = true)),
        )
        assertEquals(
            "more than 2 outages in the last 24 h — repeated drops; the Wi-Fi link needs attention",
            wifiOutageStatusText(WifiOutageCounts(last24h = 2, saturated = true)),
        )
    }

    @Test fun aSaturatedCappedCountKeepsTheNumberBecauseItIsTheStrongestSignalHere() {
        // Dropping the number to fix the low-count case would throw away the loudest reading the
        // tracker can produce.
        assertEquals(
            "more than ${WifiOutageTracker.MAX_RETAINED_EPISODES} outages in the last 24 h" +
                " — repeated drops; the Wi-Fi link needs attention",
            wifiOutageStatusText(
                WifiOutageCounts(last24h = WifiOutageTracker.MAX_RETAINED_EPISODES, saturated = true),
            ),
        )
    }

    @Test fun anUnsaturatedChronicHistoryStaysExactAndStillCarriesTheNote() {
        // Nothing was compacted, so the number IS the rate — the wording must not hedge it — but
        // the count alone already reaches the chronic bar.
        for (count in WifiOutageTracker.ATTENTION_24H..(WifiOutageTracker.ATTENTION_24H + 5)) {
            val counts = WifiOutageCounts(last24h = count, saturated = false)
            assertTrue("$count must be chronic on the count alone", wifiOutageChronic(counts))
            assertEquals(
                "$count outages in the last 24 h — repeated drops; the Wi-Fi link needs attention",
                wifiOutageStatusText(counts),
            )
        }
        // And the count immediately below the bar stays a bare exact statement.
        assertEquals(
            "${WifiOutageTracker.ATTENTION_24H - 1} outages in the last 24 h",
            wifiOutageStatusText(
                WifiOutageCounts(last24h = WifiOutageTracker.ATTENTION_24H - 1, saturated = false),
            ),
        )
    }

    @Test fun theRowReturnsToAnExactStatementOnceTheDroppedEvidenceAgesOut() {
        // The counter "resets" by the window moving on, not by anything being deleted: once every
        // evicted instant is older than 24 h the surviving number is a total again, and the floor
        // wording — and the note it guaranteed — must both go away.
        val clock = Clock()
        val store = FakeStore(
            WifiOutageRecord(
                episodeStartsWallMs = listOf(clock.wallMs - 60_000L),
                newestDroppedWallMs = clock.wallMs - 60_000L,
            ),
        )
        val t = tracker(clock, store)
        assertTrue("a dropped in-window instant saturates", t.counts().saturated)
        assertEquals(
            "more than 1 outage in the last 24 h — repeated drops; the Wi-Fi link needs attention",
            t.statusText(),
        )

        // Move just past the surviving episode and the dropped marker together.
        clock.advance(WifiOutageTracker.WINDOW_MS)
        val after = t.counts()
        assertFalse("the dropped marker has left the window", after.saturated)
        assertEquals("and so has the surviving episode", 0, after.last24h)
        assertNull("a quiet window is silent again", t.statusText())
    }


    private companion object {
        // Aligned to an exact hour boundary so window arithmetic in tests is by inspection.
        const val WALL_START = 472_223L * 3_600_000L
        const val NET_A = 11L
        const val NET_B = 22L
        const val NET_C = 33L
    }
}
