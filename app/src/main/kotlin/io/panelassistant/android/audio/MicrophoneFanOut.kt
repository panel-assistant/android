package io.panelassistant.android.audio

import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The capture hardware, reduced to the four calls the shared source needs.
 *
 * [open], [read] and [close] are called only from the source's capture thread, in that order.
 * [stop] is the one call another thread makes, and its whole job is to make a [read] that is
 * currently blocked return promptly so the capture thread can leave its loop and release the
 * hardware itself: releasing a recorder underneath a blocked read is undefined, stopping it is not.
 * Everything else about the platform — permissions, buffer sizing, the audio-source constant —
 * stays behind this interface so [MicrophoneFanOut] is testable on a plain JVM.
 */
interface PcmCaptureDevice {
    /** Acquire the hardware. False means "could not open"; the source latches [MicState.Error]. */
    fun open(): Boolean

    /**
     * Fill up to [maxSamples] signed 16-bit samples into [into] starting at [offsetSamples].
     *
     * Returns the number of samples written — which may be fewer than asked for, so the caller
     * accumulates — or `0` when the device has been stopped and capture should end cleanly, or a
     * negative value when the read failed. Blocks until at least one sample is available.
     */
    fun read(into: ShortArray, offsetSamples: Int, maxSamples: Int): Int

    /** Unblock a pending [read] so the capture thread can leave its loop. Idempotent; any thread. */
    fun stop()

    /** Release the hardware. Called by the capture thread as it exits. Idempotent. */
    fun close()

    /**
     * What the open capture is doing, for route reports: called from any thread, and read fresh from the
     * platform on every call. Null when the device cannot say, or is not open.
     */
    fun describe(): ActiveCapture? = null
}

/**
 * Teardown proof for a [MicrophoneSource] that owns threads.
 *
 * Kept off [MicrophoneSource] deliberately: consumers lease and forget, and only whatever owns the
 * source's lifetime — the service boundary — needs to end it and to be told whether it really ended.
 */
interface MicrophoneSourceLifecycle : AutoCloseable {
    /**
     * Release every lease, stop capture and join the threads this source started, waiting at most
     * [timeoutMs] in total. Returns true only when every thread was observed to have exited: a
     * consumer wedged inside [PcmConsumer.onFrame] holds its own delivery thread and yields false,
     * which is exactly the fact the service boundary has to be able to prove.
     */
    fun shutdown(timeoutMs: Long = MicrophoneFanOut.DEFAULT_SHUTDOWN_TIMEOUT_MS): Boolean

    /** [shutdown] with the default budget, discarding the proof. Prefer [shutdown]. */
    override fun close()
}

/**
 * Retry delay after [consecutiveFailures] consecutive capture failures (failed opens, or failed reads that
 * ended a generation): 250 ms doubling to a 5 s ceiling, back to 250 ms once a generation has read
 * [MIC_SUSTAINED_AUDIO_FRAMES] frames.
 */
internal fun micOpenBackoffMs(consecutiveFailures: Int): Long {
    if (consecutiveFailures <= 0) return 0L
    val shift = (consecutiveFailures - 1).coerceAtMost(MIC_OPEN_BACKOFF_MAX_SHIFT)
    val scaled = MIC_OPEN_BACKOFF_BASE_MS shl shift
    return if (scaled > MIC_OPEN_BACKOFF_CEILING_MS) MIC_OPEN_BACKOFF_CEILING_MS else scaled
}

/** A caller-driven retry is admitted only once the backoff window has elapsed. */
internal fun micOpenRetryAllowed(nowMs: Long, nextAllowedAtMs: Long): Boolean = nowMs >= nextAllowedAtMs

internal const val MIC_OPEN_BACKOFF_BASE_MS = 250L
internal const val MIC_OPEN_BACKOFF_CEILING_MS = 5_000L
private const val MIC_OPEN_BACKOFF_MAX_SHIFT = 20

/** `AudioRecord.ERROR_DEAD_OBJECT`: the audio server lost the recorder, which never recovers by itself. */
internal const val MIC_READ_DEAD_OBJECT = -6

/**
 * Consecutive failed reads of any other kind that a generation tolerates before it is ended and rebuilt.
 * A transient refusal (`ERROR`, `ERROR_INVALID_OPERATION`) is logged and the read retried; twenty-five
 * in a row with no audio between them is a recorder that has stopped working, and is treated exactly
 * like a dead object. Twenty-five returns at once on a refusing recorder, so this bounds a spin, not
 * a wait.
 */
internal const val MIC_MAX_CONSECUTIVE_READ_ERRORS = 25

/**
 * Frames a generation must deliver, one second of audio, before its capture counts as working again and the
 * backoff starts over. A recorder that dies after every start still delivers a frame or two first, and a
 * single frame resetting the backoff would rebuild it every 250 ms indefinitely.
 */
internal const val MIC_SUSTAINED_AUDIO_FRAMES = 100

/**
 * The platform-free half of the shared microphone: lease table, per-lease queues, fan-out and the
 * closed/open/error state machine. [AndroidMicrophoneSource] is this class plus an `AudioRecord`.
 *
 * **Thread ownership.** One capture thread runs the read loop while at least one lease is held, and
 * each lease gets its own delivery thread. The capture thread never waits on a consumer: it appends
 * the frame it has just read to every held lease's bounded queue — evicting that queue's oldest
 * frame when it is full — and goes straight back to `read`. A consumer that blocks, or throws,
 * therefore costs its own lease frames and nothing else. Per-lease delivery threads are what make
 * that true; one shared delivery thread would stall every consumer behind the slowest of them.
 *
 * **Frame ownership.** The capture thread allocates exactly one array per 10 ms period and hands it
 * over; each delivery thread then copies it before calling [PcmConsumer.onFrame], so the contract's
 * "the array is owned by the consumer once delivered" holds for every lease at once. That copy is
 * 320 bytes per lease per frame and it happens on the delivery thread, never on capture. Skipping it
 * when only one lease is active was considered and rejected: whether a frame is shared is decided at
 * enqueue time and read at delivery time, and a lease closing in between turns the optimisation into
 * two consumers owning one array.
 *
 * **Lock discipline.** `lock` guards the lease table and the published state; each lease's queue has
 * its own monitor. They are always taken in that order, consumer callbacks and thread joins never
 * run under either, and the only work done under `lock` is bookkeeping plus a non-blocking
 * `StateFlow` assignment.
 *
 * **Recovery.** A failed open or a failed read latches [MicState.Error] and keeps the holders. A
 * caller that next leases or resumes retries once the backoff window from [micOpenBackoffMs] has
 * elapsed, so a consumer that reacts to `Error` by resuming cannot spin the hardware. A read that
 * reports a dead object ([MIC_READ_DEAD_OBJECT]), or [MIC_MAX_CONSECUTIVE_READ_ERRORS] other failed
 * reads in a row, also ends the generation, and while any lease is held the source rebuilds the device
 * by itself on a timer, through the same backoff, until a generation reads audio again: an audio server
 * restart must never leave a held wake-word lease deaf. Any other failed read is logged and retried.
 *
 * **Generations.** Every successful open starts a new generation. A lease that took frames from an
 * earlier one is given [PcmConsumer.onReset] before its first frame from the new one, and any frames it
 * still had queued from the old one are discarded: the gap invalidates every piece of state that spans
 * frames. A lease that has not yet taken a frame starts clean and is not reset.
 */
class MicrophoneFanOut(
    /**
     * Builds the capture device for one generation. A device is single-use: it is created with the
     * capture thread that owns it and closed when that thread exits, and a later generation gets a
     * fresh one. Nothing reopens a device, so no device needs to reset revocation state, which is
     * what makes a missed stop impossible rather than merely unlikely.
     */
    private val deviceFactory: () -> PcmCaptureDevice,
    /** Clock for the open-retry backoff only; thread joins deliberately use wall time. */
    private val backoffClockMs: () -> Long = System::currentTimeMillis,
    private val nanoTime: () -> Long = System::nanoTime,
    private val threadNamePrefix: String = "ha-paneld-mic",
    /** Seam for tests that need to hold a thread between being admitted and running its body. */
    private val threadFactory: (Runnable, String) -> Thread = { body, name -> Thread(body, name) },
    private val logger: (String, Throwable?) -> Unit = { _, _ -> },
    /**
     * Runs a rebuild after a delay and returns a handle that cancels it. Null schedules on a thread from
     * [threadFactory], which [shutdown] cancels and joins like any other thread this source started.
     */
    rebuildScheduler: ((delayMs: Long, task: () -> Unit) -> AutoCloseable)? = null,
) : MicrophoneSource, MicrophoneSourceLifecycle {

    private val scheduler: (Long, () -> Unit) -> AutoCloseable = rebuildScheduler ?: ::scheduleOnThread

    private val lock = Any()
    private val stateFlow = MutableStateFlow<MicState>(MicState.Closed)
    override val state: StateFlow<MicState> = stateFlow.asStateFlow()

    /** Lease table in creation order; that order is the documented tie-break for equal priorities. */
    private val leases = ArrayList<LeaseImpl>()
    private var nextSequence = 0L

    /**
     * The held leases, as the capture thread sees them. Replaced under [lock], never mutated, and
     * read without a lock on the hot path. Membership is only "held": whether a lease is currently
     * taking frames is [LeaseImpl.deliverable], checked once inside [LeaseImpl.enqueue] so there is
     * a single guard rather than two that can disagree across a pause.
     */
    @Volatile private var targets: Array<LeaseImpl> = emptyArray()

    private var captureThread: Thread? = null

    /** The current generation's device, so a stop from any thread reaches the one that is open. */
    @Volatile private var captureDevice: PcmCaptureDevice? = null

    @Volatile private var capturing = false

    /** True only between a successful [PcmCaptureDevice.open] and the capture thread leaving its loop. */
    @Volatile private var deviceOpen = false

    private var errorReason: String? = null
    private var consecutiveFailures = 0

    /** Set when a generation failed in a way only a rebuild cures; cleared once a generation reads audio. */
    private var rebuildWanted = false
    private var pendingRebuild: AutoCloseable? = null
    @Volatile private var rebuildCount = 0

    /** Counts successful opens; frames carry it so each lease can tell a new generation from the old. */
    private var generation = 0L

    /** Threads started by the default [scheduler]; each removes itself as it exits. */
    private val timerThreads = CopyOnWriteArrayList<Thread>()
    private var nextOpenAllowedAtMs = 0L
    private var shuttingDown = false

    /**
     * A start was refused because the previous capture thread had not finished exiting yet. The
     * lease that closed last and the lease that opened next can overlap by exactly the length of
     * that exit, and without this the newcomer would hold a lease with no capture behind it until
     * something else happened to ask again.
     */
    private var startOwed = false

    /** Every live delivery thread. Each worker removes itself as it exits, so this cannot grow. */
    private val deliveryThreads = CopyOnWriteArrayList<Thread>()

    override fun lease(
        purpose: MicPurpose,
        priority: Int,
        consumer: PcmConsumer,
        queueFrames: Int,
    ): MicLease {
        require(queueFrames > 0) { "queueFrames must be positive, was $queueFrames" }
        val lease = LeaseImpl(purpose, priority, consumer, queueFrames)
        synchronized(lock) {
            check(!shuttingDown) { "microphone source is shut down" }
            lease.sequence = nextSequence++
            leases.add(lease)
            refreshTargetsLocked()
            lease.startWorkerLocked()
            ensureCaptureStartedLocked()
            publishLocked()
        }
        return lease
    }

    override fun shutdown(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs.coerceAtLeast(0L)
        val retiring: List<LeaseImpl>
        val capture: Thread?
        val device: PcmCaptureDevice?
        synchronized(lock) {
            shuttingDown = true
            cancelRebuildLocked()
            retiring = ArrayList(leases)
            leases.clear()
            refreshTargetsLocked()
            capturing = false
            capture = captureThread
            device = captureDevice
            publishLocked()
        }
        for (lease in retiring) lease.retire()
        device?.stop()
        var complete = true
        if (capture != null && !joinBounded(capture, deadline)) complete = false
        for (worker in deliveryThreads) {
            if (!joinBounded(worker, deadline)) complete = false
        }
        for (timer in timerThreads) {
            if (!joinBounded(timer, deadline)) complete = false
        }
        // Nothing is released from here. Every device is created with the thread that owns it and
        // closed by that thread on its way out, including a generation that was refused the hardware,
        // so there is no device left for a teardown to release and none that could be released twice.
        if (!complete) logger("microphone teardown did not complete within ${timeoutMs}ms", null)
        return complete
    }

    override fun close() {
        shutdown(DEFAULT_SHUTDOWN_TIMEOUT_MS)
    }

    /** The route [requested] as it stands now: what is open, read fresh from the device, and the rebuild count. */
    fun routeReport(requested: CaptureRoute): CaptureRouteReport {
        val device = if (deviceOpen) captureDevice else null
        return CaptureRouteReport(requested, runCatching { device?.describe() }.getOrNull(), rebuildCount)
    }

    /** How many times capture has been rebuilt by itself after a dead or failing recorder. */
    val rebuilds: Int get() = rebuildCount

    // ---- capture -------------------------------------------------------------------------------

    /**
     * Caller must hold [lock]. Admits and starts a capture thread when one is owed.
     *
     * The thread is started here, under the lock, rather than handed back to be started once the
     * lock has been released. A thread recorded in [captureThread] but not yet started is not
     * alive, so [shutdown] would join it instantly, report a completed teardown, and the thread
     * would then go on to open the microphone behind it. Starting under the lock runs no foreign
     * code and cannot block: `start()` returns as soon as the thread is alive, which is exactly the
     * property every later join depends on. The thread's own first act is to ask [admitCaptureStart]
     * whether it is still wanted, because being alive is not the same as having been scheduled.
     */
    private fun ensureCaptureStartedLocked() {
        if (shuttingDown || leases.isEmpty()) return
        val existing = captureThread
        if (existing != null) {
            // A live thread already owns the device, or is in the middle of giving it back. Waiting
            // for it here would mean joining under the lock, so the exiting thread is asked to hand
            // capture over as its last act instead.
            if (existing.isAlive) {
                startOwed = true
                return
            }
            captureThread = null
        }
        if (errorReason != null && !micOpenRetryAllowed(backoffClockMs(), nextOpenAllowedAtMs)) return
        errorReason = null
        capturing = true
        // The device and the thread that owns it are created in the same critical section, so there
        // is never a live thread without a device to stop, nor a device nothing will ever close.
        val device = deviceFactory()
        captureDevice = device
        val thread = threadFactory({ captureLoop(device) }, threadNamePrefix)
        thread.isDaemon = true
        captureThread = thread
        thread.start()
    }

    /**
     * Whether this capture thread is still the one the source wants, asked before the hardware is
     * touched. A lease can be released, or the whole source shut down, between a thread being
     * started and it first being scheduled; refusing here is what stops a completed teardown from
     * being followed by a microphone opening.
     */
    private fun admitCaptureStart(): Boolean = synchronized(lock) {
        capturing && !shuttingDown && captureThread === Thread.currentThread()
    }

    private fun captureLoop(device: PcmCaptureDevice) {
        if (!admitCaptureStart()) {
            device.close()
            finishCapture()
            return
        }
        if (!device.open()) {
            failCapture("microphone could not be opened")
            device.close()
            finishCapture()
            return
        }
        val thisGeneration = synchronized(lock) {
            deviceOpen = true
            generation += 1
            publishLocked()
            generation
        }
        var readAudio = false
        var framesRead = 0
        var failedReads = 0
        try {
            while (capturing) {
                // One allocation per 10 ms period: the array handed over to the fan-out. Delivery
                // threads copy it for their own consumer, so nothing else is allocated here.
                val samples = ShortArray(MicrophoneSource.SAMPLES_PER_FRAME)
                val timestampNs = nanoTime()
                var filled = 0
                var ended = false
                while (filled < MicrophoneSource.SAMPLES_PER_FRAME) {
                    val read = device.read(samples, filled, MicrophoneSource.SAMPLES_PER_FRAME - filled)
                    if (read < 0) {
                        failedReads += 1
                        if (read == MIC_READ_DEAD_OBJECT || failedReads >= MIC_MAX_CONSECUTIVE_READ_ERRORS) {
                            failCapture("microphone read failed ($read)", rebuild = true)
                            return
                        }
                        if (failedReads == 1) logger("microphone read failed ($read); retrying", null)
                        continue
                    }
                    if (read == 0) {
                        ended = true
                        break
                    }
                    failedReads = 0
                    filled += read
                }
                if (ended) break
                if (!readAudio) {
                    readAudio = true
                    // Audio flows again: nothing more is owed a rebuild until something fails.
                    synchronized(lock) { rebuildWanted = false }
                }
                if (framesRead < MIC_SUSTAINED_AUDIO_FRAMES && ++framesRead == MIC_SUSTAINED_AUDIO_FRAMES) {
                    // This generation works: the next failure starts the backoff from the beginning.
                    synchronized(lock) { consecutiveFailures = 0 }
                }
                val snapshot = targets
                for (target in snapshot) {
                    target.enqueue(PcmFrame(samples, MicrophoneSource.SAMPLE_RATE_HZ, timestampNs), thisGeneration)
                }
            }
        } finally {
            device.close()
            finishCapture()
        }
    }

    private fun failCapture(reason: String, rebuild: Boolean = false) {
        val delayMs: Long?
        synchronized(lock) {
            deviceOpen = false
            capturing = false
            errorReason = reason
            consecutiveFailures += 1
            val backoff = micOpenBackoffMs(consecutiveFailures)
            nextOpenAllowedAtMs = backoffClockMs() + backoff
            if (rebuild) rebuildWanted = true
            delayMs = if (rebuildWanted && !shuttingDown && leases.isNotEmpty() && pendingRebuild == null) backoff else null
            if (delayMs != null) {
                // Started under the lock for the same reason the capture thread is: a shutdown that has
                // taken the lock after this point sees the handle and cancels it.
                pendingRebuild = scheduler(delayMs, ::rebuild)
            }
            publishLocked()
        }
        logger(if (delayMs != null) "$reason; rebuilding in ${delayMs}ms" else reason, null)
    }

    /** The rebuild timer's task: start a fresh generation if anything still wants the microphone. */
    private fun rebuild() {
        synchronized(lock) {
            pendingRebuild = null
            // A lease or resume may already have started a generation inside the backoff window.
            if (shuttingDown || leases.isEmpty() || !rebuildWanted || capturing) return
            rebuildCount += 1
            // The timer is the backoff; its own firing is what admits the retry.
            nextOpenAllowedAtMs = 0L
            ensureCaptureStartedLocked()
            publishLocked()
        }
    }

    /** Caller must hold [lock]. */
    private fun cancelRebuildLocked() {
        val pending = pendingRebuild ?: return
        pendingRebuild = null
        runCatching { pending.close() }
    }

    /** The default [scheduler]: one thread that sleeps, then runs the task unless it was interrupted. */
    private fun scheduleOnThread(delayMs: Long, task: () -> Unit): AutoCloseable {
        lateinit var thread: Thread
        thread = threadFactory({
            try {
                Thread.sleep(delayMs)
                task()
            } catch (cancelled: InterruptedException) {
                // Cancelled: the lease that wanted the rebuild was released, or the source shut down.
            } finally {
                timerThreads.remove(thread)
            }
        }, "$threadNamePrefix-rebuild")
        thread.isDaemon = true
        timerThreads.add(thread)
        thread.start()
        return AutoCloseable { thread.interrupt() }
    }

    /**
     * The capture thread's last act: retire its own slot, and hand capture on if a lease was taken
     * while it was leaving. The handoff is driven by [startOwed] rather than by "are there leases?",
     * so an exit nobody asked to follow can never restart itself in a loop.
     */
    private fun finishCapture() {
        synchronized(lock) {
            deviceOpen = false
            if (captureThread === Thread.currentThread()) {
                captureThread = null
                captureDevice = null
                capturing = false
            }
            val owed = startOwed
            startOwed = false
            if (owed) ensureCaptureStartedLocked()
            publishLocked()
        }
    }

    // ---- state ---------------------------------------------------------------------------------

    /** Caller must hold [lock]. */
    private fun refreshTargetsLocked() {
        targets = leases.toTypedArray()
    }

    /** Caller must hold [lock]. Assigning a [MutableStateFlow] value runs no consumer code. */
    private fun publishLocked() {
        val ordered = leases.sortedWith(compareByDescending<LeaseImpl> { it.priority }.thenBy { it.sequence })
        val holders = ordered.map { MicHolder(it.purpose, it.priority, it.active) }
        stateFlow.value = when {
            holders.isEmpty() -> MicState.Closed
            errorReason != null -> MicState.Error(errorReason!!, holders)
            else -> MicState.Open(holders)
        }
    }

    private fun joinBounded(thread: Thread, deadlineMs: Long): Boolean {
        val remaining = deadlineMs - System.currentTimeMillis()
        try {
            // join(0) waits forever; a spent budget still gets the shortest real wait, never that.
            thread.join(if (remaining > 0L) remaining else 1L)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return !thread.isAlive
    }

    private fun report(what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            // A consumer is a stranger to this class. Its failure costs its own frame and nothing
            // else: capture keeps its cadence, and every other lease keeps receiving.
            logger("microphone consumer failed during $what", t)
        }
    }

    // ---- lease ---------------------------------------------------------------------------------

    private inner class LeaseImpl(
        override val purpose: MicPurpose,
        override val priority: Int,
        private val consumer: PcmConsumer,
        private val capacity: Int,
    ) : MicLease {
        var sequence = 0L

        private val queueLock = Object()
        private val queue = ArrayDeque<PcmFrame>(capacity)
        private var pendingDrops = 0

        /** Generation of the last frame queued; 0 until this lease has taken one. */
        private var queuedGeneration = 0L
        private var pendingReset = false

        @Volatile private var paused = false
        @Volatile private var closed = false
        @Volatile private var running = true

        /** The single guard on queueing: frames are taken only while this lease is held and unpaused. */
        val deliverable: Boolean get() = !closed && !paused

        override val active: Boolean get() = deliverable && deviceOpen

        /** Caller must hold [lock]; started there for the reason in [ensureCaptureStartedLocked]. */
        fun startWorkerLocked() {
            val thread = threadFactory({ workerLoop() }, "$threadNamePrefix-${purpose.name.lowercase()}-$sequence")
            thread.isDaemon = true
            deliveryThreads.add(thread)
            thread.start()
        }

        fun enqueue(frame: PcmFrame, generation: Long) {
            synchronized(queueLock) {
                if (!deliverable) return
                if (queuedGeneration != 0L && generation != queuedGeneration) {
                    // Frames from before the gap are stale; the consumer starts again from a reset.
                    queue.clear()
                    pendingDrops = 0
                    pendingReset = true
                }
                queuedGeneration = generation
                while (queue.size >= capacity) evictOldestLocked()
                queue.addLast(frame)
                queueLock.notifyAll()
            }
        }

        /** Caller must hold [queueLock]. Drop-oldest: the newest audio is the audio worth keeping. */
        private fun evictOldestLocked() {
            queue.removeFirst()
            pendingDrops += 1
        }

        private fun workerLoop() {
            val self = Thread.currentThread()
            try {
                while (true) {
                    var frame: PcmFrame? = null
                    var drops = 0
                    var reset = false
                    synchronized(queueLock) {
                        while (running && queue.isEmpty()) {
                            try {
                                queueLock.wait()
                            } catch (interrupted: InterruptedException) {
                                self.interrupt()
                                running = false
                            }
                        }
                        if (running) {
                            frame = queue.removeFirst()
                            drops = pendingDrops
                            pendingDrops = 0
                            reset = pendingReset
                            pendingReset = false
                        }
                    }
                    val next = frame ?: return
                    if (reset) report("onReset") { consumer.onReset() }
                    if (drops > 0) report("onDropped") { consumer.onDropped(drops) }
                    // Copy on this thread, so the consumer may keep the array it is handed.
                    val owned = PcmFrame(next.samples.copyOf(), next.sampleRate, next.timestampNs)
                    report("onFrame") { consumer.onFrame(owned) }
                }
            } finally {
                deliveryThreads.remove(self)
            }
        }

        override fun pause() {
            if (closed || paused) return
            synchronized(lock) {
                if (closed || paused) return
                paused = true
                publishLocked()
            }
            // Paused means "not queued", not "dropped": the gap is the holder's own doing, so it is
            // deliberately not reported through onDropped.
            synchronized(queueLock) {
                queue.clear()
                pendingDrops = 0
            }
        }

        override fun resume() {
            if (closed) return
            synchronized(lock) {
                if (closed || !paused) return
                paused = false
                ensureCaptureStartedLocked()
                publishLocked()
            }
        }

        override fun close() {
            val capture: Thread?
            val device: PcmCaptureDevice?
            synchronized(lock) {
                if (closed) return
                closed = true
                leases.remove(this)
                refreshTargetsLocked()
                if (leases.isEmpty()) {
                    capturing = false
                    errorReason = null
                    rebuildWanted = false
                    cancelRebuildLocked()
                    consecutiveFailures = 0
                    nextOpenAllowedAtMs = 0L
                    capture = captureThread
                    // Read under the same lock as the thread: the pair has to describe one
                    // generation, or a stop could be aimed at a device the thread never had.
                    device = captureDevice
                } else {
                    capture = null
                    device = null
                }
                publishLocked()
            }
            release()
            if (capture != null) {
                device?.stop()
                // The capture thread releases the hardware in its own finally, so the lease that
                // closes last can prove the microphone is shut before it returns.
                joinBounded(capture, System.currentTimeMillis() + CAPTURE_JOIN_MS)
            }
        }

        /** Shutdown path: drop the claim without touching the lease table it is being removed from. */
        fun retire() {
            closed = true
            release()
        }

        /**
         * Signal the delivery thread and discard anything queued. Deliberately does not join: a
         * consumer wedged in `onFrame` must not be able to block whoever released the lease, and
         * [shutdown] is where that thread is joined and a failure to join is reported.
         */
        private fun release() {
            synchronized(queueLock) {
                running = false
                queue.clear()
                pendingDrops = 0
                queueLock.notifyAll()
            }
        }
    }

    companion object {
        const val DEFAULT_SHUTDOWN_TIMEOUT_MS = 2_000L
        private const val CAPTURE_JOIN_MS = 2_000L
    }
}
