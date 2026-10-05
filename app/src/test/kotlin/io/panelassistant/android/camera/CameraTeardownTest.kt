package io.panelassistant.android.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The enqueue inversion review found after the foreground fix landed: a lease released under the lock
 * ends one session, and a concurrent acquire can admit and open the next before the finish for the old
 * one is even posted. The old finish then runs against a live camera. Its attempt's own hardware —
 * the capture, its standing and its encoder — is still its to release, but the stream readiness, the
 * in-use light and the stream clients are the new session's, and tearing them down would strand its
 * joiners and — the one that matters — put the light out with the camera open.
 *
 * The encoder is the half that is easy to get wrong in both directions. Had the finish stopped a
 * session-wide encoder, it would have stopped the newer session's; had it skipped a session-wide one,
 * the ended codec would have stayed installed and the newer attempt, finding an encoder already there,
 * would never have started its own. So the codec is the attempt's: an ended attempt closes exactly its
 * own, retracts exactly its own advertisement, and the newer attempt starts fresh whatever order the
 * two run in.
 *
 * Each case runs the objects that decide this together, the way the owner does: the registry for
 * standing, [CameraTeardown] for the session's effects, and attempts that each own their codec.
 * [Camera] mirrors the owner's structure so a case reads as the sequence it models; that the owner
 * really is that sequence is pinned by `CameraForegroundWiringContractTest`.
 */
class CameraTeardownTest {

    /** One attempt's hardware, as the owner's `Attempt` keeps it: its codec and the sets it published. */
    private class Attempt(val id: Long) {
        var codecOpen = false
        var params: String? = null
    }

    private class Camera {
        val promotions = CameraForegroundPromotions()
        var stops = 0
        var lightOn = false
        var generation = 0L
        var current: Attempt? = null

        /** What the transport advertises for DESCRIBE, and which attempt published it. */
        var advertised: String? = null
        var advertisedBy: Long? = null

        /** The session's readiness: null while a joiner would wait, else the sets it would be granted. */
        var readiness: String? = null

        /** A session starts from idle: the generation advances and a settled readiness is replaced. */
        fun admit(): Long {
            generation++
            readiness = null
            return generation
        }

        /** Opens attempt [owner] for the admitted session; [answered] runs its foreground start to completion. */
        fun open(owner: Long, answered: Boolean): Attempt {
            promotions.request(owner) { true }
            if (answered) promotions.started(true)
            lightOn = true
            return Attempt(owner).also { current = it }
        }

        /** `startEncoder`: only the live attempt, and only if it has no codec of its own yet. */
        fun startEncoder(attempt: Attempt): Boolean {
            if (attempt !== current || attempt.codecOpen) return false
            attempt.codecOpen = true
            return true
        }

        /** The codec's listener publishing parameter sets: nothing from a superseded attempt reaches the session. */
        fun publish(attempt: Attempt) {
            if (attempt !== current || !attempt.codecOpen) return
            val sets = "sets#${attempt.id}"
            attempt.params = sets
            advertised = sets
            advertisedBy = attempt.id
            readiness = sets
        }

        /** The session ends: the generation advances and the attempt is detached, its finish yet to run. */
        fun end(): Pair<Attempt?, Long> {
            generation++
            val ended = current
            current = null
            return ended to generation
        }

        /** `Attempt.closeEncoder`: the codec, its sets, and the advertisement if it is still this attempt's. */
        fun closeEncoder(attempt: Attempt) {
            attempt.codecOpen = false
            attempt.params = null
            if (advertisedBy == attempt.id) {
                advertisedBy = null
                advertised = null
            }
        }

        /** What `finishAttempt` does for [ended], whose session ended at [endedGeneration]. */
        fun finish(ended: Attempt?, endedGeneration: Long, stopping: Boolean = false) {
            val ownsGlobals = CameraTeardown.ownsSessionGlobals(stopping, endedGeneration, generation)
            ended?.let { closeEncoder(it) }
            if (ownsGlobals) readiness = null
            if (stopping) promotions.releaseAll { stops++ } else ended?.let { promotions.release(it.id) { stops++ } }
            if (ownsGlobals) lightOn = false
        }
    }

    /** A session that streamed: opened, answered, its encoder running and its sets published. */
    private fun Camera.streamingSession(owner: Long): Attempt {
        admit()
        val attempt = open(owner, answered = true)
        assertTrue(startEncoder(attempt))
        publish(attempt)
        return attempt
    }

    @Test fun aFinishRunningAfterAPendingNewerSessionLeavesItsEncoderAndLightAlone() {
        val c = Camera()
        val old = c.streamingSession(owner = 10L)
        // The old session ends (generation advances), and the next is admitted and opened before the
        // old finish is posted. Its foreground start has been issued but not answered yet, and it has
        // not reached the point of starting its encoder.
        val (ended, endedAt) = c.end()
        c.admit()
        val newer = c.open(owner = 11L, answered = false)
        assertTrue("the newer session's start is still unanswered", c.promotions.hasUnansweredStart)

        c.finish(ended, endedGeneration = endedAt)

        assertFalse("the ended attempt's codec is closed", old.codecOpen)
        assertNull("and its advertisement retracted, since nothing newer has published", c.advertised)
        assertNull("the newer session's readiness is pending, not the ended encoder's sets", c.readiness)
        assertTrue("its light stays on while it has the camera open", c.lightOn)
        assertEquals("its foreground service is not stopped", 0, c.stops)
        assertEquals("and its standing is untouched", 11L, c.promotions.standingHolder)
        assertTrue(c.promotions.isWanted)

        // The defect the review named: the newer attempt must not find an encoder already installed.
        assertTrue("the newer attempt starts its own encoder", c.startEncoder(newer))
        c.publish(newer)
        assertEquals("and its joiners are granted its own sets", "sets#11", c.readiness)
        assertEquals("sets#11", c.advertised)
    }

    @Test fun aFinishRunningAfterAnAnsweredNewerSessionLeavesItsEncoderAndLightAlone() {
        val c = Camera()
        val old = c.streamingSession(owner = 10L)
        val (ended, endedAt) = c.end()
        val newer = c.streamingSession(owner = 11L)
        assertFalse("the newer session's start has been answered", c.promotions.hasUnansweredStart)

        c.finish(ended, endedGeneration = endedAt)

        assertFalse("the ended attempt's codec is closed", old.codecOpen)
        assertTrue("the newer attempt's codec keeps running", newer.codecOpen)
        assertEquals("the advertisement is still the newer encoder's", "sets#11", c.advertised)
        assertEquals(11L, c.advertisedBy)
        assertEquals("and so is the readiness a joiner is granted", "sets#11", c.readiness)
        assertTrue("its light stays on while it has the camera open", c.lightOn)
        assertEquals("its foreground service is not stopped", 0, c.stops)
        assertEquals("and its standing is untouched", 11L, c.promotions.standingHolder)
    }

    @Test fun aJoinerAfterTheNewerSessionStartsWaitsRatherThanBeingGrantedTheEndedEncodersSets() {
        val c = Camera()
        c.streamingSession(owner = 10L)
        assertEquals("sets#10", c.readiness)
        c.end()
        // Admitted before the old finish has run: the finish will not touch a readiness that is no
        // longer its own, so the session itself starts with a pending one.
        c.admit()
        assertNull("a joiner waits for the new session's encoder", c.readiness)
    }

    @Test fun aSupersededCodecsLateParameterSetsNeverReachTheNewerSession() {
        val c = Camera()
        c.admit()
        val old = c.open(owner = 10L, answered = true)
        assertTrue(c.startEncoder(old))
        // Its codec has not published yet when the session ends and the next one opens.
        c.end()
        c.admit()
        c.open(owner = 11L, answered = true)

        c.publish(old)

        assertNull("the newer session's readiness is not settled by an ended codec", c.readiness)
        assertNull("and nothing of it is advertised", c.advertised)
    }

    @Test fun aFinishForTheSessionThatIsStillCurrentTearsEverythingDown() {
        val c = Camera()
        val old = c.streamingSession(owner = 10L)
        // Nothing started since: the generation it ended at is still the current one.
        val (ended, endedAt) = c.end()
        c.finish(ended, endedGeneration = endedAt)
        assertFalse("the codec comes down", old.codecOpen)
        assertNull("its advertisement is retracted", c.advertised)
        assertNull("a settled readiness is replaced", c.readiness)
        assertFalse("the light goes out with the camera", c.lightOn)
        assertEquals("and the foreground service is stopped", 1, c.stops)
    }

    @Test fun aSubsystemStopTearsEverythingDownWhateverHasStartedSince() {
        val c = Camera()
        c.streamingSession(owner = 10L)
        c.end()
        val newer = c.streamingSession(owner = 11L)
        val (ended, endedAt) = c.end()
        c.generation += 5
        c.finish(ended, endedGeneration = endedAt, stopping = true)
        assertFalse("the codec comes down", newer.codecOpen)
        assertNull("nothing newer can be coming, so a settled readiness is replaced", c.readiness)
        assertFalse("and the light goes out", c.lightOn)
        assertEquals("standing goes whoever holds it", 1, c.stops)
    }

    @Test fun theRuleIsGenerationEqualityUnlessTheSubsystemIsStopping() {
        assertTrue("its own generation", CameraTeardown.ownsSessionGlobals(false, endedGeneration = 4L, currentGeneration = 4L))
        assertFalse("a newer session has started", CameraTeardown.ownsSessionGlobals(false, endedGeneration = 4L, currentGeneration = 5L))
        assertTrue("stopping owns them regardless", CameraTeardown.ownsSessionGlobals(true, endedGeneration = 4L, currentGeneration = 5L))
    }
}
