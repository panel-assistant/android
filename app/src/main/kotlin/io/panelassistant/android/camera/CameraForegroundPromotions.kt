package io.panelassistant.android.camera

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Bookkeeping between the camera owner, which asks for camera-typed foreground standing, and the
 * service instances Android creates to carry it. Pure, so the interleavings are unit tests.
 *
 * Three Android rules shape it, and the process died on real hardware for want of the first:
 *
 * - A `startForegroundService` that is stopped before the service has called `startForeground` kills
 *   the whole process. So the owner's `stopService` is never issued while a start is unanswered; the
 *   stop is deferred to that start, which calls `startForeground` first and then stops itself.
 * - A service instance is not a promotion. When a session ends and the next begins at once, the old
 *   instance's `onDestroy` can run after the new promotion's start has been issued. Its destroy must
 *   answer nothing then: the new instance's `onStartCommand` answers the new promotion.
 * - **Standing belongs to the session that asked for it.** The owner tears sessions down out of order:
 *   an ended session's `finishAttempt` can run after the next session has already opened and promoted.
 *   A release therefore names its owner, and one from anybody but the current holder does nothing at
 *   all — it neither withdraws the newer session's claim nor stops the service carrying it. This is
 *   the identity the two-argument shape exists for; a release that could not name its owner would be
 *   indistinguishable from the live session's own.
 *
 * Every decision and the Android call it governs run under one lock. The owner hands the registry the
 * start and stop actions rather than issuing them itself, so a request can never slip between "no start
 * is unanswered" and the `stopService` that conclusion allowed, and a stop can never slip between a
 * request and its `startForegroundService`. Only the newest start answers the waiting promotion and
 * decides whether the service stays, because only the newest start can call `startForeground` for it.
 */
class CameraForegroundPromotions {

    /** One request for standing. The owner waits on it through [await]; true means the service is foreground. */
    class Promotion internal constructor() {
        internal val future = CompletableFuture<Boolean>()
    }

    private val lock = Any()

    /** Starts accepted by `startForegroundService` and not yet answered by an `onStartCommand`. */
    private var unansweredStarts = 0

    /** The promotion whose start is the most recent; the newest `onStartCommand` answers it. */
    private var waiting: Promotion? = null

    /** The owner's latest word: true after an accepted request, false after a release that was its to make. */
    private var wanted = false

    /** Whose standing this is: the owner token of the newest accepted request, or null when nobody's. */
    private var holder: Long? = null

    /**
     * [owner] asks for standing. [start] issues the Android start under the lock and returns whether
     * Android accepted it synchronously. Any promotion still waiting is superseded, and [owner] becomes
     * the holder, so an earlier session's release can no longer touch this one. A refused start never
     * existed: it is uncounted, the promotion is refused at once, and the previous holder and word are
     * restored, so a stop deferred before the request is still honoured by the start it was deferred to.
     */
    fun request(owner: Long, start: () -> Boolean): Promotion = synchronized(lock) {
        waiting?.future?.complete(false)
        val promotion = Promotion()
        val wantedBefore = wanted
        val holderBefore = holder
        waiting = promotion
        wanted = true
        holder = owner
        unansweredStarts++
        val issued = runCatching(start).getOrDefault(false)
        if (!issued) {
            unansweredStarts--
            waiting = null
            wanted = wantedBefore
            holder = holderBefore
            promotion.future.complete(false)
        }
        promotion
    }

    /**
     * The owner waits for the answer, at most [timeoutMs]. The wait and the decision are two steps so
     * the race between them is a unit test: [decide] is what a wait that ran out does.
     */
    fun await(promotion: Promotion, timeoutMs: Long): Boolean =
        decide(promotion, runCatching { promotion.future.get(timeoutMs, TimeUnit.MILLISECONDS) }.getOrNull())

    /**
     * What the owner is told once its wait has ended with [answered], null when the wait ran out. A
     * timeout is decided under the lock against the start's outcome: a start that answered meanwhile is
     * reported as the answer it gave, and one that has not is refused now while its start stays
     * outstanding and stops itself when it runs.
     */
    fun decide(promotion: Promotion, answered: Boolean?): Boolean {
        if (answered != null) return answered
        return synchronized(lock) {
            if (promotion.future.complete(false)) {
                if (waiting === promotion) waiting = null
                false
            } else {
                promotion.future.getNow(false)
            }
        }
    }

    /**
     * [owner] no longer wants standing. **A release by anyone but the current holder does nothing**:
     * the owner tears down out of order, so an ended session's release routinely arrives after a newer
     * session has promoted, and acting on it would withdraw the newer session's claim or stop the
     * service carrying it. When it is the holder's own release, [stop] issues the Android stop under
     * the lock, and only when no start is unanswered; otherwise the stop is deferred to that start.
     */
    fun release(owner: Long, stop: () -> Unit) {
        synchronized(lock) {
            if (holder != owner) return
            holder = null
            wanted = false
            if (unansweredStarts == 0) stop()
        }
    }

    /**
     * The camera subsystem itself is stopping, so standing goes whoever holds it. The only unconditional
     * release, and the one path that is not a session ending: nothing newer can be coming.
     */
    fun releaseAll(stop: () -> Unit) {
        synchronized(lock) {
            holder = null
            wanted = false
            if (unansweredStarts == 0) stop()
        }
    }

    /**
     * An instance's `onStartCommand` ran and called `startForeground`, which succeeded iff [ok]. True
     * when the service should stay up; false when it must stop itself. A start that is not the newest
     * keeps the service and answers nothing: while a newer start is unanswered, both the waiting
     * promotion and the decision belong to that start. The newest start answers the promotion with
     * whether standing was granted and still wanted.
     */
    fun started(ok: Boolean): Boolean = synchronized(lock) {
        unansweredStarts = maxOf(0, unansweredStarts - 1)
        if (unansweredStarts > 0) return true
        val keep = ok && wanted
        if (!keep) holder = null
        waiting?.future?.complete(keep)
        waiting = null
        keep
    }

    /**
     * An instance was destroyed. One that [served] a start answers nothing: whatever is waiting belongs
     * to a later start. One that never served has a start that died with it; the waiting promotion is
     * refused only when no other start remains to answer it.
     */
    fun destroyed(served: Boolean) {
        synchronized(lock) {
            if (served) return
            unansweredStarts = maxOf(0, unansweredStarts - 1)
            if (unansweredStarts == 0) {
                holder = null
                waiting?.future?.complete(false)
                waiting = null
            }
        }
    }

    val hasUnansweredStart: Boolean get() = synchronized(lock) { unansweredStarts > 0 }
    val isWanted: Boolean get() = synchronized(lock) { wanted }
    val standingHolder: Long? get() = synchronized(lock) { holder }
}
