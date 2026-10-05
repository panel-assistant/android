package io.panelassistant.android.audio

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A [MicrophoneSource] a test drives by hand.
 *
 * Deliberately trivial: frames are delivered synchronously on the thread that calls [pushFrame], so
 * a test for a wake-word detector, an Assist pipeline or the lease coordinator never needs a latch,
 * a sleep or a second thread to reason about what its consumer received. [MicrophoneFanOut]'s own
 * threading is proved by `MicrophoneFanOutTest`; the features that lease it should not re-prove it.
 *
 * Copy this class rather than sharing it if a caller needs different behaviour.
 */
class FakeMicrophoneSource : MicrophoneSource {

    /** Every lease ever handed out, in creation order, closed ones included. */
    val leases = CopyOnWriteArrayList<FakeLease>()

    /** Leases still held and not paused, i.e. the ones [pushFrame] delivers to. */
    val activeLeases: List<FakeLease> get() = leases.filter { it.active }

    private val stateFlow = MutableStateFlow<MicState>(MicState.Closed)
    override val state: StateFlow<MicState> = stateFlow.asStateFlow()

    /** How many times the fake "hardware" was opened; incremented on the first lease of each run. */
    var openCount = 0
        private set

    /** How many times the last lease released the fake "hardware". */
    var closeCount = 0
        private set

    override fun lease(
        purpose: MicPurpose,
        priority: Int,
        consumer: PcmConsumer,
        queueFrames: Int,
    ): MicLease {
        val lease = FakeLease(purpose, priority, consumer, queueFrames)
        if (leases.none { !it.closed }) openCount += 1
        leases.add(lease)
        publish()
        return lease
    }

    /** Deliver one frame to every active lease. Returns how many consumers received it. */
    fun pushFrame(
        samples: ShortArray = ShortArray(MicrophoneSource.SAMPLES_PER_FRAME),
        timestampNs: Long = 0L,
    ): Int {
        val targets = activeLeases
        for (lease in targets) {
            lease.consumer.onFrame(PcmFrame(samples.copyOf(), MicrophoneSource.SAMPLE_RATE_HZ, timestampNs))
        }
        return targets.size
    }

    /** Deliver [count] distinct frames; sample `i` of frame `n` is `n`, so a test can spot reordering. */
    fun pushFrames(count: Int) {
        repeat(count) { n ->
            pushFrame(ShortArray(MicrophoneSource.SAMPLES_PER_FRAME) { n.toShort() }, timestampNs = n.toLong())
        }
    }

    /** Report a gap to one lease, as the real source does when a consumer falls behind. */
    fun dropFrames(lease: FakeLease, count: Int) {
        if (lease.active) lease.consumer.onDropped(count)
    }

    /** Put the source into [MicState.Error] so a consumer's recovery path can be exercised. */
    fun failWith(reason: String) {
        errorReason = reason
        publish()
    }

    /** Clear a previously injected error. */
    fun recover() {
        errorReason = null
        publish()
    }

    private var errorReason: String? = null

    private fun publish() {
        val holders = leases.filter { !it.closed }
            .sortedWith(compareByDescending<FakeLease> { it.priority }.thenBy { it.sequence })
            .map { MicHolder(it.purpose, it.priority, it.active) }
        stateFlow.value = when {
            holders.isEmpty() -> MicState.Closed
            errorReason != null -> MicState.Error(errorReason!!, holders)
            else -> MicState.Open(holders)
        }
    }

    inner class FakeLease(
        override val purpose: MicPurpose,
        override val priority: Int,
        val consumer: PcmConsumer,
        val queueFrames: Int,
    ) : MicLease {
        val sequence: Int = leases.size
        var paused = false
            private set
        var closed = false
            private set

        override val active: Boolean get() = !closed && !paused && errorReason == null

        override fun pause() {
            if (closed || paused) return
            paused = true
            publish()
        }

        override fun resume() {
            if (closed || !paused) return
            paused = false
            publish()
        }

        override fun close() {
            if (closed) return
            closed = true
            if (leases.none { !it.closed }) closeCount += 1
            publish()
        }
    }
}
