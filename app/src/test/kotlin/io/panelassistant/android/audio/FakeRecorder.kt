package io.panelassistant.android.audio

import android.media.MediaRecorder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * A recorder a test can watch and script: every transition it makes is counted, and its reads return
 * the samples and error codes the test offers, in order.
 *
 * With nothing scripted, a read returns [nextRead], or, when [blockWhenIdle] is set, blocks until the
 * test offers something or the recorder is stopped, which is how a real recorder behaves under the
 * fan-out's capture thread. A chunk larger than the read asked for is split across reads.
 */
internal class FakeRecorder(
    override val format: RecorderFormat = RecorderFormat(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16_000, 1),
    private val blockWhenIdle: Boolean = false,
) : PcmRecorder {
    val started = AtomicInteger()
    val stopped = AtomicInteger()
    val released = AtomicInteger()

    @Volatile var recording = false
    @Volatile var startSucceeds = true
    @Volatile var nextRead = 0
    @Volatile var aec: AecState = AecState.REQUESTED_OFF
    @Volatile var routed: String? = null

    private val script = LinkedBlockingQueue<Any>()
    private var current: ShortArray? = null
    private var consumed = 0

    /** Offer interleaved samples for the next reads. */
    fun offer(samples: ShortArray) {
        script.put(samples)
    }

    /** Make the next read return [code], an `AudioRecord` error such as -6. */
    fun offerCode(code: Int) {
        script.put(code)
    }

    override fun startRecording(): Boolean {
        started.incrementAndGet()
        recording = startSucceeds
        return startSucceeds
    }

    override fun read(into: ShortArray, offsetSamples: Int, maxSamples: Int): Int {
        var chunk = current
        if (chunk == null || consumed >= chunk.size) {
            val next = if (blockWhenIdle) {
                try {
                    script.take()
                } catch (interrupted: InterruptedException) {
                    return 0
                }
            } else {
                script.poll() ?: return nextRead
            }
            if (next === STOPPED) return 0
            if (next is Int) return next
            chunk = next as ShortArray
            current = chunk
            consumed = 0
        }
        val count = minOf(maxSamples, chunk.size - consumed)
        System.arraycopy(chunk, consumed, into, offsetSamples, count)
        consumed += count
        return count
    }

    override fun stop() {
        stopped.incrementAndGet()
        recording = false
        if (blockWhenIdle) script.put(STOPPED)
    }

    override fun release() {
        released.incrementAndGet()
    }

    override fun routedDevice(): String? = routed

    override fun aecState(): AecState = aec

    private companion object {
        val STOPPED = Any()
    }
}
