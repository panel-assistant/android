package io.github.maxlyth.hapaneld.camera

import io.github.maxlyth.hapaneld.testsupport.TestSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The owner's half of making each identity check atomic with its effect. The owner cannot run on the
 * JVM, so its shape is pinned by source text, the same treatment its other teardown wiring has; the
 * transport's half runs for real in `CameraRtspServerTest`.
 *
 * Two ways to close a check/effect gap, and which one applies depends on whether the effect can call
 * back into the owner. The readiness future and the transport's record of parameter sets cannot, so
 * they happen inside the critical section that checks. Dropping a client can — it closes the client's
 * lease — so it happens outside, and names the generation read in that critical section, which the
 * transport honours. Each assertion below is one gap that was open before this: a replacement session or
 * attempt arriving between the check and the effect had its waiters refused, its clients dropped, or its
 * first joiner granted a superseded encoder's parameter sets.
 */
class CameraEffectAtomicityContractTest {

    private val owner by lazy { TestSources.kotlin("camera/CameraSessionOwner.kt").readText() }

    private fun body(source: String, name: String): String {
        val start = source.indexOf(name)
        assertTrue("$name is present", start >= 0)
        val open = source.indexOf('{', start)
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(open, i + 1)
            }
        }
        error("unbalanced $name")
    }

    /** The first `synchronized(lock)` block in [function], and what follows it. */
    private fun criticalSection(function: String): Pair<String, String> {
        val block = body(function, "synchronized(lock)")
        return block to function.substringAfter(block)
    }

    @Test fun aStreamEndDecidesWhoseTheWaitersAreInTheCriticalSectionThatDetachesThem() {
        val end = body(owner, "private fun endStream(refusal: CameraRefusal, endedGeneration: Long)")
        val (locked, after) = criticalSection(end)
        assertTrue("the ownership rule decides", "CameraTeardown.ownsSessionGlobals(" in locked)
        assertTrue("reading the generation inside the same section", "currentGeneration = state.generation" in locked)
        assertTrue("that detaches the waiters", "if (owns) detachStreamReadyLocked() else null" in locked)
        assertTrue("the clients are dropped outside the lock", "transport.onStreamEnded(endedGeneration)" in after)
        assertFalse("and never skipped on ownership: the transport scopes them to the ended generation", "return" in after)
    }

    @Test fun parameterSetsArePublishedAndTheWaitersWokenInsideTheIdentityCheck() {
        val listener = body(owner, "private fun encoderListener(attempt: Attempt)")
        val (locked, after) = criticalSection(body(listener, "override fun onParameterSets(sets: ParameterSets)"))
        val check = locked.indexOf("if (!state.isCurrent(attempt.id)) return")
        val publish = locked.indexOf("transport.onParameterSets(sets, attempt.id)")
        val wake = locked.indexOf("streamReady.complete(StreamOutcome.Ready(params))")
        assertTrue("the check, the publication and the wake-up are one critical section", check >= 0 && publish > check && wake > publish)
        assertFalse("nothing is applied after the lock is released", "transport." in after || "complete(" in after)
    }

    @Test fun anEncoderFailureSettlesAndEndsOnlyTheSessionItsCheckFound() {
        val listener = body(owner, "private fun encoderListener(attempt: Attempt)")
        val failure = body(listener, "override fun onEncoderError(detail: String)")
        val decision = failure.substringAfter("attempt.closeEncoder()")
        val (locked, after) = criticalSection(decision)
        assertTrue("whose session it is", "if (!state.isCurrent(attempt.id)) return" in locked)
        assertTrue("is decided where the waiters are detached", "settled = detachStreamReadyLocked()" in locked)
        assertTrue("and the generation to end is read", "generation = state.generation" in locked)
        assertTrue("the end names that generation", "transport.onStreamEnded(generation)" in after)
    }

    @Test fun anEncoderRefusalIsDecidedWithItsEffectsNotBeforeTheOpen() {
        val refuse = body(owner, "private fun refuseEncoder(attemptId: Long, detail: String)")
        val (locked, after) = criticalSection(refuse)
        assertTrue("if (!state.isCurrent(attemptId)) return" in locked)
        assertTrue("settled = detachStreamReadyLocked()" in locked)
        assertTrue("generation = state.generation" in locked)
        assertTrue("transport.onStreamEnded(generation)" in after)
        val start = body(owner, "private fun startEncoder(attemptId: Long)")
        assertEquals("both refusals name the attempt they were decided for", 2, Regex("refuseEncoder\\(attemptId, ").findAll(start).count())
    }

    @Test fun aCodecIsInstalledOnlyIntoAnAttemptThatIsStillCurrent() {
        val start = body(owner, "private fun startEncoder(attemptId: Long)")
        val recheck = start.indexOf("if (!state.isCurrent(attemptId)) return@synchronized false")
        val install = start.indexOf("attempt.encoder = opened.encoder")
        assertTrue("the install is re-checked in its own critical section", recheck >= 0 && install > recheck)
        assertTrue("and a codec nobody owns is closed", "runCatching { opened.encoder.close() }" in start)
    }

    @Test fun everyStreamEndNamesAGenerationReadWhereItsDecisionWasMade() {
        val arguments = Regex("transport\\.onStreamEnded\\(([^)]*)\\)").findAll(owner).map { it.groupValues[1] }.toList()
        assertEquals("three stream ends: the session ending, an encoder failure, an encoder refusal", 3, arguments.size)
        assertEquals(setOf("endedGeneration", "generation"), arguments.toSet())
    }
}
