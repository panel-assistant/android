package io.panelassistant.android.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PendingUploadStoreTest {
    @Test
    fun stagedUploadExpiresBeforeCommit() {
        var now = 1_000L
        val store = PendingUploadStore(monotonicMs = { now }, ttlMs = 100L, newToken = { "token" }).apply { open() }
        val lease = granted(store.begin())
        val staged = file("expired.apk")
        store.stage(lease, staged)
        now += 100L

        assertNull(store.claim("token"))
        assertFalse(staged.exists())
        assertTrue(store.begin() is PendingUploadStore.BeginResult.Granted)
    }

    @Test
    fun backwardClockExpiresStagedUploadAndDeletesIt() {
        var now = 1_000L
        val store = PendingUploadStore(monotonicMs = { now }, ttlMs = 100L, newToken = { "token" }).apply { open() }
        val lease = granted(store.begin())
        val staged = file("backward-clock.apk")
        store.stage(lease, staged)

        now = 999L

        assertNull(store.peek("token"))
        assertFalse(staged.exists())
        assertTrue(store.begin() is PendingUploadStore.BeginResult.Granted)
    }

    @get:Rule val temporary = TemporaryFolder()

    private fun file(name: String) = temporary.newFile(name).apply { writeText(name) }

    /** A reservation may supersede the operator's own previous intent, but never a body still arriving
     *  from a client — the panel cannot retract someone else's upload stream. A superseded entry must
     *  be genuinely dead: its token stops resolving and its file is deleted, so nothing can later be
     *  installed under it. */
    /** Panel-side work is bound to the reservation that started it, so every way a reservation ends
     *  also ends the work. Before this was folded into the store, a disable, a shutdown and a replacing
     *  request each left a download running against a panel that had already moved on. */
    @Test fun everyWayAReservationEndsAlsoStopsThePanelWorkBoundToIt() {
        fun freshStoreWithWork(): Pair<PendingUploadStore, io.panelassistant.android.util.DownloadAbort> {
            val store = PendingUploadStore { "token" }.apply { open() }
            val admitted = store.begin("req") as PendingUploadStore.BeginResult.Granted
            return store to admitted.abort!!
        }

        val (disabled, disabledWork) = freshStoreWithWork()
        disabled.clear()
        assertTrue("disabling the capability must stop an in-flight fetch", disabledWork.isAborted)

        val (stopped, stoppedWork) = freshStoreWithWork()
        stopped.close()
        assertTrue("shutting down must stop an in-flight fetch", stoppedWork.isAborted)

        val (restarted, restartedWork) = freshStoreWithWork()
        restarted.open()
        assertTrue("a restarted server must not leave the old fetch running", restartedWork.isAborted)

        val (replaced, replacedWork) = freshStoreWithWork()
        replaced.begin()
        assertTrue("a replacing request must retire the fetch it supersedes", replacedWork.isAborted)

        val (released, releasedWork) = freshStoreWithWork()
        released.abort(granted(released.begin()))
        assertTrue("releasing the reservation must stop its work", releasedWork.isAborted)
    }

    /** The race that matters: bytes can finish arriving while the operator is cancelling. Aborting the
     *  transfer is then too late, so cancellation has to decide the outcome — no token may exist, at
     *  any timing, once the operator has said stop. */
    @Test fun aCancelledReservationCannotStageEvenIfItsBytesAlreadyArrived() {
        val store = PendingUploadStore { "token" }.apply { open() }
        val lease = granted(store.begin("mine"))

        assertTrue(store.cancelPanelWork("mine"))
        assertTrue("the refusal must be attributable to the cancel", store.isCancelled(lease))

        val completed = file("arrived-anyway.apk")
        assertNull("a cancelled reservation must never mint a token", store.stage(lease, completed))
        assertFalse("and its bytes must not survive", completed.exists())
    }

    /** A cancel may only stop the request that started the work, never a replacement. */
    @Test fun panelWorkIsCancellableOnlyByTheRequestThatOwnsIt() {
        val store = PendingUploadStore { "token" }.apply { open() }
        val admitted = store.begin("mine") as PendingUploadStore.BeginResult.Granted
        val abort = admitted.abort!!

        assertFalse("a foreign request must not cancel", store.cancelPanelWork("someone-else"))
        assertFalse(abort.isAborted)
        assertTrue(store.cancelPanelWork("mine"))
        assertTrue(abort.isAborted)
    }

    /** The race the third review found: the owner used to be recorded AFTER the slot was taken, so a
     *  cancel arriving in between found nobody, reported that it had cancelled nothing, and the fetch
     *  went on to complete and publish a token. There is deliberately no attachment step to race with
     *  now — the very first thing a caller can do after reserving is cancel, and it must stick. */
    @Test fun aCancelImmediatelyAfterReservationIsHonouredWithNoAttachmentStepToRace() {
        val store = PendingUploadStore { "token" }.apply { open() }
        val admitted = store.begin("mine") as PendingUploadStore.BeginResult.Granted

        assertNotNull("panel-side work must own an abort from the instant it is admitted", admitted.abort)
        assertTrue("a cancel with no intervening step must be recognised", store.cancelPanelWork("mine"))
        assertTrue("and must actually stop the transfer", admitted.abort!!.isAborted)

        val completed = file("raced.apk")
        assertNull("nothing may be staged for a reservation cancelled this early", store.stage(admitted.lease, completed))
        assertFalse(completed.exists())
    }

    @Test fun beginRefusesAnArrivingBodyButSupersedesTheOperatorsPreviousStagedIntent() {
        var id = 0
        val store = PendingUploadStore { "token-${++id}" }.apply { open() }
        val firstLease = granted(store.begin())
        assertEquals(PendingUploadStore.BeginResult.Busy, store.begin())

        store.abort(firstLease)
        store.abort(firstLease)
        val secondLease = granted(store.begin())
        val staged = store.stage(secondLease, file("staged.apk"))!!

        // A staged entry is the last thing the operator asked for, not a lock held until its TTL.
        val thirdLease = granted(store.begin())
        assertNull("a superseded token must stop resolving", store.peek(staged.token))
        assertNull("a superseded token must not be claimable", store.claim(staged.token))
        assertFalse("a superseded staging file must be deleted", staged.file.exists())

        val replacement = store.stage(thirdLease, file("replacement.apk"))!!
        assertEquals(replacement.file, store.claim(replacement.token)?.file)
        assertTrue(store.begin() is PendingUploadStore.BeginResult.Granted)
    }

    @Test fun abortAfterStageDeletesOnlyTheEntryOwnedByThatLease() {
        val store = PendingUploadStore { "token" }.apply { open() }
        val lease = granted(store.begin())
        val staged = store.stage(lease, file("aborted.apk"))!!

        store.abort(lease)
        store.abort(lease)

        assertFalse(staged.file.exists())
        assertNull(store.claim(staged.token))
        assertTrue(store.begin() is PendingUploadStore.BeginResult.Granted)
    }

    @Test fun closeInvalidatesSlowUploadsAndDeletesCurrentAndFutureFiles() {
        val store = PendingUploadStore { "token" }.apply { open() }
        val lease = granted(store.begin())
        val active = file("active.apk")
        store.stage(lease, active)
        store.close()

        assertFalse(active.exists())
        assertEquals(PendingUploadStore.BeginResult.Closed, store.begin())
        val late = file("late.apk")
        assertNull(store.stage(lease, late))
        assertFalse(late.exists())

        store.open()
        assertTrue(store.begin() is PendingUploadStore.BeginResult.Granted)
        val fromPreviousLifetime = file("previous-lifetime.apk")
        assertNull(store.stage(lease, fromPreviousLifetime))
        assertFalse(fromPreviousLifetime.exists())
    }

    /** The Issue #96 defect class: an inspected upload the operator walked away from. A discard must
     *  retire exactly that entry, deleting its bytes and freeing the slot immediately rather than
     *  after the TTL or the next supersession. */
    @Test fun discardRetiresTheInspectedEntryAndDeletesItsBytes() {
        val store = PendingUploadStore { "token" }.apply { open() }
        val staged = store.stage(granted(store.begin()), file("discarded.apk"))!!

        assertEquals(PendingUploadStore.DiscardResult.DISCARDED, store.discard(staged.token))
        assertFalse("a discarded file must not survive", staged.file.exists())
        assertNull("a discarded token must stop resolving", store.peek(staged.token))
        assertTrue("the slot must be free again", store.begin() is PendingUploadStore.BeginResult.Granted)
    }

    /** After a reload the browser holds no token; the probe's discard reference is the recovery path,
     *  and it must stay honest when the entry is already gone. */
    @Test fun theProbeReferenceDiscardsExactlyTheEntryItDescribes() {
        val store = PendingUploadStore(newDiscardId = { "probe-ref" }, newToken = { "token" }).apply { open() }
        val staged = store.stage(granted(store.begin()), file("reloaded.apk"))!!
        val reference = store.pendingSummary()!!.discardId

        assertEquals(PendingUploadStore.DiscardResult.DISCARDED, store.discard(reference))
        assertFalse(staged.file.exists())
        assertNull("a discarded token must stop resolving", store.peek(staged.token))
        assertEquals(PendingUploadStore.DiscardResult.NOTHING_PENDING, store.discard(reference))
        assertEquals(PendingUploadStore.DiscardResult.NOTHING_PENDING, store.discard("token"))
    }

    /** The probe's reference is deliberately powerless beyond discarding: handing out install
     *  authority to any LAN client that asked "what is pending?" would bypass the token handoff. */
    @Test fun theProbeReferenceCarriesNoInstallAuthority() {
        val store = PendingUploadStore(newDiscardId = { "probe-ref" }, newToken = { "secret-token" }).apply { open() }
        val staged = store.stage(granted(store.begin()), file("probed-authority.apk"))!!
        val reference = store.pendingSummary()!!.discardId

        assertNotEquals("the reference must not BE the commit token", staged.token, reference)
        assertNull("the reference must not read the entry", store.peek(reference))
        assertNull("the reference must not claim the entry", store.claim(reference))
        assertNotNull("while the real token still commits", store.peek(staged.token))
    }

    /** The review's first finding: client A's recovery card probes entry X, client B replaces it with
     *  Y, then A clicks Discard. The stale reference must remove NOTHING — never the replacement A
     *  has not seen — and the replacement must stay fully committable. */
    @Test fun aStaleRecoveryReferenceCannotRemoveAReplacementUpload() {
        var id = 0
        val store = PendingUploadStore(newDiscardId = { "ref-${++id}" }, newToken = { "token-$id" }).apply { open() }
        store.stage(granted(store.begin()), file("first-probe.apk"))!!
        val staleReference = store.pendingSummary()!!.discardId

        val replacement = store.stage(granted(store.begin()), file("replacement.apk"))!!

        assertEquals(PendingUploadStore.DiscardResult.DIFFERENT_PENDING, store.discard(staleReference))
        assertTrue("the replacement must be untouched", replacement.file.exists())
        assertNotNull("and must still be committable", store.peek(replacement.token))

        val freshReference = store.pendingSummary()!!.discardId
        assertNotEquals(staleReference, freshReference)
        assertEquals("a re-probe yields a reference that works", PendingUploadStore.DiscardResult.DISCARDED, store.discard(freshReference))
        assertFalse(replacement.file.exists())
    }

    /** A stray click on a stale preview must never remove an upload someone staged in its place. */
    @Test fun aTokenScopedDiscardNeverRemovesAnEntryItDidNotInspect() {
        var id = 0
        val store = PendingUploadStore { "token-${++id}" }.apply { open() }
        val superseded = store.stage(granted(store.begin()), file("superseded.apk"))!!
        val current = store.stage(granted(store.begin()), file("current.apk"))!!

        assertEquals(PendingUploadStore.DiscardResult.DIFFERENT_PENDING, store.discard(superseded.token))
        assertTrue("the newer entry must be untouched", current.file.exists())
        assertNotNull("and must still be committable", store.peek(current.token))
    }

    /** A claim is final — the commit route takes the operation lane BEFORE claiming, so an entry
     *  leaves this store exactly once, when its install genuinely starts. "Nothing pending" is then
     *  durable truth (nothing can ever reappear), the install's bytes are untouchable, and an
     *  overlapping second upload lives a fully independent life — the interleaving that broke a
     *  shared claim marker (commit B's window erasing commit A's) is unrepresentable by construction,
     *  because there is no window state to share. */
    @Test fun aClaimIsFinalSoNothingPendingIsDurableTruthAcrossOverlappingCommits() {
        var id = 0
        val store = PendingUploadStore(newDiscardId = { "ref-${++id}" }, newToken = { "token-$id" }).apply { open() }
        val a = store.stage(granted(store.begin()), file("commit-a.apk"))!!
        val refA = store.pendingSummary()!!.discardId
        val claimedA = store.claim(a.token)!!

        assertEquals(PendingUploadStore.DiscardResult.NOTHING_PENDING, store.discard(a.token))
        assertEquals(PendingUploadStore.DiscardResult.NOTHING_PENDING, store.discard(refA))
        assertTrue("install A's bytes are untouchable", claimedA.file.exists())
        assertNull("and nothing reappears behind the answer", store.pendingSummary())

        // Upload B overlaps A's running install: pending, probeable and discardable on its own, and
        // neither its life nor its death disturbs A.
        val b = store.stage(granted(store.begin()), file("commit-b.apk"))!!
        assertEquals(PendingUploadStore.DiscardResult.DIFFERENT_PENDING, store.discard(a.token))
        assertTrue(b.file.exists())
        val refB = store.pendingSummary()!!.discardId
        assertEquals(PendingUploadStore.DiscardResult.DISCARDED, store.discard(refB))
        assertFalse(b.file.exists())
        assertTrue("A's install never notices B's lifecycle", claimedA.file.exists())
        assertEquals("and A still never reappears", PendingUploadStore.DiscardResult.NOTHING_PENDING, store.discard(a.token))
    }

    @Test fun durableClaimFailureOrExceptionLeavesTheSameUploadPending() {
        val store = PendingUploadStore { "token" }.apply { open() }
        val staged = store.stage(granted(store.begin()), file("guard-role.apk"))!!

        assertNull(store.claimAfter(staged.token) { false })
        assertEquals(staged.file, store.peek(staged.token)?.file)
        assertTrue(staged.file.exists())

        assertNull(store.claimAfter(staged.token) { error("copy failed") })
        assertEquals(staged.file, store.peek(staged.token)?.file)
        assertTrue(staged.file.exists())

        assertEquals(staged.file, store.claimAfter(staged.token) { true }?.file)
        assertNull(store.peek(staged.token))
    }

    /** Discard owns only the slot's PENDING occupant: a body still arriving is someone's upload
     *  stream the panel cannot retract, and in-flight panel work has its own owner-scoped cancel. */
    @Test fun discardNeverTouchesAnArrivingBodyOrInFlightPanelWork() {
        val store = PendingUploadStore { "token" }.apply { open() }
        val receiving = granted(store.begin())
        assertEquals(PendingUploadStore.DiscardResult.NOTHING_PENDING, store.discard("any-ref"))
        assertNotNull("the receive must go on to stage after a discard", store.stage(receiving, file("still-arriving.apk")))

        val fetching = PendingUploadStore { "token" }.apply { open() }
        val admitted = fetching.begin("req") as PendingUploadStore.BeginResult.Granted
        assertEquals(PendingUploadStore.DiscardResult.NOTHING_PENDING, fetching.discard("any-ref"))
        assertFalse("panel work must not be aborted by a discard", admitted.abort!!.isAborted)
        assertNotNull("and must still be able to stage", fetching.stage(admitted.lease, file("still-fetching.apk")))
    }

    /** Expiry, disable and shutdown each already delete the staged file; a discard arriving after any
     *  of them must find nothing rather than invent a second deletion path. */
    @Test fun expiryDisableAndShutdownAllLeaveNothingForDiscardToFind() {
        var now = 1_000L
        val expired = PendingUploadStore(monotonicMs = { now }, ttlMs = 100L, newToken = { "token" }).apply { open() }
        val expiredEntry = expired.stage(granted(expired.begin()), file("expired-then-discarded.apk"))!!
        now += 100L
        assertEquals(PendingUploadStore.DiscardResult.NOTHING_PENDING, expired.discard(expiredEntry.token))
        assertFalse("expiry itself must have deleted the file", expiredEntry.file.exists())

        val disabled = PendingUploadStore { "token" }.apply { open() }
        val disabledEntry = disabled.stage(granted(disabled.begin()), file("disabled-then-discarded.apk"))!!
        disabled.clear()
        assertEquals(PendingUploadStore.DiscardResult.NOTHING_PENDING, disabled.discard(disabledEntry.token))
        assertFalse(disabledEntry.file.exists())

        val stopped = PendingUploadStore { "token" }.apply { open() }
        val stoppedEntry = stopped.stage(granted(stopped.begin()), file("stopped-then-discarded.apk"))!!
        stopped.close()
        assertEquals(PendingUploadStore.DiscardResult.NOTHING_PENDING, stopped.discard(stoppedEntry.token))
        assertFalse(stoppedEntry.file.exists())
    }

    /** No older request may stage after its ownership is invalidated — even within one server
     *  lifetime, where the epoch cannot tell a released lease from the reservation that replaced it. */
    @Test fun aReleasedLeaseCannotStageOverItsReplacement() {
        val store = PendingUploadStore { "token" }.apply { open() }
        val released = granted(store.begin())
        store.abort(released)
        val current = granted(store.begin())

        val stale = file("stale-lease.apk")
        assertNull("a released lease must stay dead", store.stage(released, stale))
        assertFalse("and its bytes must not survive", stale.exists())
        assertNotNull("while the live reservation still stages", store.stage(current, file("live-lease.apk")))
    }

    /** The probe that drives the recovery UI answers any LAN client, so it describes what is pending
     *  — existence and inspected identity — and nothing more. It reports only genuinely discardable
     *  state: a claimed, expired or vanished entry is not worth advertising. */
    @Test fun pendingSummaryDescribesOnlyADiscardableEntry() {
        val store = PendingUploadStore { "token" }.apply { open() }
        assertNull(store.pendingSummary())

        val identity = UploadedApkIdentity("example.panel", "1.2.3", "ab".repeat(32))
        val staged = store.stage(granted(store.begin()), file("probed.apk"), identity)!!
        assertEquals(identity, store.pendingSummary()?.identity)

        assertNotNull(store.claim(staged.token))
        assertNull("a claimed entry is being installed, not pending — durably", store.pendingSummary())

        val vanished = PendingUploadStore { "token" }.apply { open() }
        val ghost = vanished.stage(granted(vanished.begin()), file("vanishing.apk"), identity)!!
        ghost.file.delete()
        assertNull("a vanished file is not discardable state", vanished.pendingSummary())
    }

    @Test fun pendingSummaryGoesQuietOnExpiry() {
        var now = 1_000L
        val store = PendingUploadStore(monotonicMs = { now }, ttlMs = 100L, newToken = { "token" }).apply { open() }
        val staged = store.stage(granted(store.begin()), file("expiring-probe.apk"), UploadedApkIdentity("p", "1", null))!!
        assertNotNull(store.pendingSummary())
        now += 100L
        assertNull(store.pendingSummary())
        assertFalse("the expiry the probe triggered must delete the file", staged.file.exists())
    }

    private fun granted(result: PendingUploadStore.BeginResult): PendingUploadStore.Lease {
        assertTrue(result is PendingUploadStore.BeginResult.Granted)
        return (result as PendingUploadStore.BeginResult.Granted).lease
    }
}
