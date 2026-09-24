package io.github.maxlyth.hapaneld.camera

import io.github.maxlyth.hapaneld.testsupport.TestSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Android side of the foreground promotion bookkeeping cannot run on the JVM, so its wiring is
 * pinned by source text, the same treatment the capture callback and processing wiring have. Each
 * assertion is one way the process died, or would have, on an Android 14 panel on 2026-09-01. The
 * interleavings themselves are decided in `CameraForegroundPromotionsTest`; this proves the service
 * and the gate route every Android call through that registry, and that no teardown in the session
 * owner can release standing without naming the attempt whose standing it is.
 */
class CameraForegroundWiringContractTest {

    private val service by lazy { TestSources.kotlin("camera/CameraForegroundService.kt").readText() }
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

    private fun serviceBody(name: String) = body(service, name)

    @Test fun everyStartCallsStartForegroundBeforeItCanStopItself() {
        val start = serviceBody("override fun onStartCommand")
        val foreground = start.indexOf("startForeground(")
        val stop = start.indexOf("stopSelfResult(startId)")
        assertTrue("startForeground is called on every start", foreground >= 0)
        assertTrue("a start that stops itself does so only after startForeground", stop < 0 || stop > foreground)
        assertFalse("no stop ignores its startId: a newer start would be brought down unanswered", "stopSelf()" in service)
        assertTrue("the instance records that it served a start before anything else", start.indexOf("served = true") in 0 until foreground)
    }

    @Test fun theStartAnswersThePromotionsAndStaysOnlyWhenTheyKeepIt() {
        val start = serviceBody("override fun onStartCommand")
        assertTrue("the outcome of startForeground is what answers the promotion", "promotions.started(outcome.isSuccess)" in start)
        assertTrue("a start nobody wants stops itself, naming its own startId", "if (!keep) stopSelfResult(startId)" in start)
    }

    @Test fun aDestroyReportsWhetherTheInstanceServedAStart() {
        assertTrue("promotions.destroyed(served)" in serviceBody("override fun onDestroy"))
    }

    @Test fun aDemoteReleasesInItsOwnersNameFromInsideTheRelease() {
        val demote = serviceBody("override fun demote(owner: Long)")
        val release = demote.indexOf("promotions.release(owner)")
        val stop = demote.indexOf("stopService()")
        assertTrue("the release names the owner whose standing it is", release >= 0)
        assertTrue("the stop is the release's action, under the registry lock", stop > release)
        assertTrue("stopping outright is the only unconditional release", "promotions.releaseAll" in serviceBody("override fun demoteAll"))
    }

    @Test fun aPromoteIssuesItsStartInsideTheRequestAndWaitsThroughTheRegistry() {
        val promote = serviceBody("override fun promote(owner: Long, timeoutMs: Long)")
        val request = promote.indexOf("promotions.request(owner) {")
        val start = promote.indexOf("startForegroundService(")
        assertTrue("the start is the request's action, under the registry lock and in the owner's name", request >= 0 && start > request)
        assertTrue("the wait and its timeout are the registry's decision", "promotions.await(promotion, timeoutMs)" in promote)
        assertFalse("the gate never reads the promotion's answer around the registry", "future" in promote)
        assertFalse("the gate never stops the service from promote", "stopService(" in promote)
    }

    /**
     * The enqueue inversion found after the foreground fix landed: a finish posted for an ended session
     * can run after the next session has opened. The decision is `CameraTeardown`'s, and it must guard
     * exactly the session's effects — the attempt's own codec, hardware and standing are always its own
     * to release, and the readiness and the light are the live session's.
     */
    @Test fun aFinishGuardsOnlyTheSessionGlobalEffectsWithTheTeardownRule() {
        val finish = body(owner, "private fun finishAttempt")
        assertTrue("the rule decides, and reads the live generation", "CameraTeardown.ownsSessionGlobals(" in finish)
        assertTrue("currentGeneration = synchronized(lock) { state.generation }" in finish)
        val encoder = finish.indexOf("stopEncoder(attempt, ownsSession = ownsGlobals)")
        assertTrue("the attempt's codec always comes down; the session's readiness only for the session that owns it", encoder >= 0)
        assertTrue(
            "and the light goes out only for that session",
            "if (ownsGlobals) {\n            if (stopping) indicator.forceHide() else indicator.hide()" in finish,
        )
        val hardware = finish.indexOf("attempt?.release()")
        assertTrue("the attempt's own hardware is released whatever else has started, after its codec", hardware > encoder)
        assertFalse("and is never behind the global guard", "if (ownsGlobals) attempt" in finish)

        val stop = body(owner, "private fun stopEncoder(attempt: Attempt?, ownsSession: Boolean)")
        val close = stop.indexOf("attempt?.closeEncoder()")
        val owned = stop.indexOf("if (!ownsSession) return")
        assertTrue("the codec closes before ownership is even asked", close >= 0 && owned > close)
        assertTrue("what ownership guards is the readiness", stop.indexOf("streamReady = CompletableFuture()") > owned)
    }

    /**
     * The revision-5 hold: the encoder was session-wide, so a stale finish that skipped it left the
     * ended codec installed and the newer attempt, finding one there, never started its own — and a
     * stale finish that stopped it would have stopped the newer attempt's. It is the attempt's hardware.
     */
    @Test fun theEncoderIsTheAttemptsOwnHardware() {
        val attempt = body(owner, "private inner class Attempt")
        assertTrue("the codec lives in the attempt", "var encoder: VideoEncoder? = null" in attempt)
        assertTrue("with its pacer", "var encoderPacer: FramePacer? = null" in attempt)
        assertTrue("and the sets it published", "var streamParams: StreamParams? = null" in attempt)
        assertFalse("there is no session-wide encoder to find", "private var encoder" in owner)
        assertFalse("private var streamParams" in owner)

        val close = body(attempt, "fun closeEncoder()")
        assertTrue("closing retracts only this attempt's own advertisement", "retract = advertisedBy == id" in close)
        assertTrue("if (retract) transport.onEncoderStopped(id)" in close)
        val release = body(attempt, "fun release()")
        assertTrue("the attempt's release closes its codec before the capture beneath it", release.indexOf("closeEncoder()") in 0 until release.indexOf("session = null"))

        val start = body(owner, "private fun startEncoder(attemptId: Long)")
        assertTrue("a start is refused only by the attempt's own encoder", "if (attempt.encoder != null) return" in start)
        assertFalse("never by anyone else's", "current?.encoder" in start)
        assertTrue("and installs into the attempt", "attempt.encoder = opened.encoder" in start)
        assertTrue("with a listener that knows whose it is", "encoderListener(attempt)" in start)

        val listener = body(owner, "private fun encoderListener(attempt: Attempt)")
        assertEquals(
            "every callback from a codec checks its attempt is still current before touching the session",
            3,
            Regex("state\\.isCurrent\\(attempt\\.id\\)").findAll(listener).count(),
        )
        val failure = body(listener, "override fun onEncoderError(detail: String)")
        val closed = failure.indexOf("attempt.closeEncoder()")
        assertTrue(
            "a failing codec closes itself before anything asks whose session it is, so a superseded one closes only itself",
            closed >= 0 && closed < failure.indexOf("if (!state.isCurrent(attempt.id)) return"),
        )
    }

    /**
     * The other half of a fresh start: the readiness a joiner waits on is the session's, and the finish
     * of the session that ended will not touch one that is no longer its own. So the new session
     * replaces a settled one itself, where every other session-bound value is already reset.
     */
    @Test fun aNewSessionStartsWithItsOwnStreamReadiness() {
        val begin = body(owner, "private fun beginOpenLocked")
        assertTrue("if (streamReady.isDone) streamReady = CompletableFuture()" in begin)
        assertTrue("alongside the other session-bound values", begin.indexOf("deliveryPacer = FramePacer(boundFps)") in 0 until begin.indexOf("streamReady.isDone"))
        val ready = body(owner, "override fun acquireStream")
        assertTrue("a joiner is granted only the live attempt's sets", "live?.encoder != null && params != null" in ready)
    }

    /**
     * The revision-3 hold: `finishAttempt` demoted without saying whose standing it was releasing, so
     * an ended session tearing down after the next one had promoted took the live session's service
     * down with it. Standing is now released through the attempt that promoted it, which no call site
     * can get wrong, and the one unconditional release is the subsystem stopping outright.
     */
    @Test fun everySessionTeardownReleasesStandingInItsOwnAttemptsName() {
        assertFalse("no teardown releases standing without naming an owner", "foreground.demote()" in owner)
        assertEquals(
            "the attempt is the only thing that releases a session's standing",
            1,
            Regex("foreground\\.demote\\(").findAll(owner).count(),
        )
        assertTrue("and it releases its own", "fun releaseStanding() = foreground.demote(id)" in body(owner, "private inner class Attempt"))
        assertTrue("standing is asked for in the attempt's name", "foreground.promote(attempt.id, FOREGROUND_WAIT_MS)" in owner)
        assertEquals(
            "only one path releases standing unconditionally",
            1,
            Regex("foreground\\.demoteAll\\(\\)").findAll(owner).count(),
        )
        assertTrue(
            "and it is the subsystem stopping, not a session ending",
            "if (stopping) foreground.demoteAll() else attempt?.releaseStanding()" in body(owner, "private fun finishAttempt"),
        )
    }
}
