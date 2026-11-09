package io.panelassistant.android.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** What one open recorder delivers: the source it opened through and its interleaved PCM16 format. */
data class RecorderFormat(val source: Int, val sampleRateHz: Int, val channels: Int)

/** A capture device whose open recorder can say what format it delivers. */
internal interface FormattedCaptureDevice : PcmCaptureDevice {
    /** The open recorder's format; null before a successful open. */
    val format: RecorderFormat?
}

/**
 * Turns whatever the recorder delivers into the canonical stream: 16 kHz mono PCM16.
 *
 * Reads interleaved PCM16 from [inner] at the rate and channel count the open ladder settled on, discards
 * the first [warmupReads] reads after the open (many HALs deliver silence or a start-up transient until
 * warmed up), mixes two channels down per [downmix], and resamples to 16 kHz through a windowed-sinc
 * polyphase low-pass whose phase and history carry across reads, so the output is the same however the
 * recorder splits its reads. The fan-out above assembles the samples into 160-sample frames.
 *
 * Single-use, like every capture device: built for one generation and closed with it, which is also what
 * resets the warm-up count, the resampler's history and any half-read channel pair after a rebuild.
 */
internal class ConvertingCaptureDevice(
    private val inner: FormattedCaptureDevice,
    private val downmix: CaptureDownmix,
    private val warmupReads: Int,
) : PcmCaptureDevice {

    @Volatile private var channels = 1
    private var raw = ShortArray(0)
    private var rawLength = 0
    private var mono = ShortArray(0)
    private var resampler: PolyphaseResampler? = null
    private var out = ShortArray(0)
    private var outStart = 0
    private var outEnd = 0
    private var warmupLeft = warmupReads

    override fun open(): Boolean {
        if (!inner.open()) return false
        val format = inner.format ?: RecorderFormat(-1, MicrophoneSource.SAMPLE_RATE_HZ, 1)
        channels = format.channels.coerceAtLeast(1)
        // One read is 10 ms at the opened rate, so the conversion's latency stays one frame.
        val framesPerRead = (format.sampleRateHz / 100).coerceAtLeast(1)
        raw = ShortArray(framesPerRead * channels)
        mono = ShortArray(framesPerRead)
        resampler = if (format.sampleRateHz == MicrophoneSource.SAMPLE_RATE_HZ) null
        else PolyphaseResampler(format.sampleRateHz, MicrophoneSource.SAMPLE_RATE_HZ)
        out = ShortArray(framesPerRead + MicrophoneSource.SAMPLES_PER_FRAME)
        warmupLeft = warmupReads
        return true
    }

    override fun read(into: ShortArray, offsetSamples: Int, maxSamples: Int): Int {
        while (outEnd == outStart) {
            val read = inner.read(raw, rawLength, raw.size - rawLength)
            if (read <= 0) return read
            val total = rawLength + read
            val frames = total / channels
            val remainder = total - frames * channels
            if (warmupLeft > 0) {
                warmupLeft -= 1
            } else {
                convert(frames)
            }
            // A read that ends half-way through a channel pair keeps that half for the next read.
            if (remainder > 0) System.arraycopy(raw, frames * channels, raw, 0, remainder)
            rawLength = remainder
        }
        val count = minOf(maxSamples, outEnd - outStart)
        System.arraycopy(out, outStart, into, offsetSamples, count)
        outStart += count
        if (outStart == outEnd) {
            outStart = 0
            outEnd = 0
        }
        return count
    }

    private fun convert(frames: Int) {
        if (channels == 1) System.arraycopy(raw, 0, mono, 0, frames)
        else for (frame in 0 until frames) {
            val base = frame * channels
            mono[frame] = mixDown(raw[base], raw[base + 1], downmix)
        }
        val stage = resampler
        if (stage == null) {
            System.arraycopy(mono, 0, out, 0, frames)
            outStart = 0
            outEnd = frames
        } else {
            outStart = 0
            outEnd = stage.process(mono, frames, out)
        }
    }

    override fun stop() = inner.stop()

    override fun close() = inner.close()

    override fun describe(): ActiveCapture? = inner.describe()?.copy(
        downmix = if (channels == 2) downmix else null,
        warmupReads = warmupReads,
    )

    companion object {
        /**
         * One stereo sample to mono. Dominance takes a channel at least four times louder than the other on
         * its own and averages otherwise, so a dead channel never halves the signal. Compared in [Int], so
         * four times a full-scale sample does not overflow.
         */
        fun mixDown(left: Short, right: Short, mode: CaptureDownmix): Short {
            val l = left.toInt()
            val r = right.toInt()
            val mixed = when (mode) {
                CaptureDownmix.LEFT -> l
                CaptureDownmix.RIGHT -> r
                CaptureDownmix.AVERAGE -> (l + r) / 2
                CaptureDownmix.DOMINANCE -> when {
                    abs(l) >= 4 * abs(r) -> l
                    abs(r) >= 4 * abs(l) -> r
                    else -> (l + r) / 2
                }
            }
            return mixed.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }
}

/**
 * Rational-ratio resampler: a Kaiser-windowed sinc low-pass designed at the upsampled rate and applied in
 * polyphase form, with the input history and the output phase carried across calls.
 *
 * The low-pass passes 7 kHz and reaches its stopband by about 8.4 kHz, with roughly 80 dB of stopband
 * rejection, so content above the 8 kHz output Nyquist does not fold back into the speech band. Every
 * output sample is computed from the same stored history in the same tap order whatever the size of the
 * reads that delivered it, which is what makes a chunked stream equal to the same stream in one call.
 */
internal class PolyphaseResampler(inputRateHz: Int, outputRateHz: Int) {
    private val up: Int
    private val down: Int
    private val taps: Int
    private val phases: Array<FloatArray>

    private var history: FloatArray
    private var length: Int
    private var cursor: Int
    private var phase = 0

    init {
        require(inputRateHz > 0 && outputRateHz > 0) { "rates must be positive" }
        val divisor = gcd(inputRateHz, outputRateHz)
        up = outputRateHz / divisor
        down = inputRateHz / divisor
        val upsampledHz = inputRateHz.toDouble() * up
        val nyquistHz = minOf(inputRateHz, outputRateHz) / 2.0
        val passHz = nyquistHz * PASS_FRACTION
        val stopHz = nyquistHz * STOP_FRACTION
        val cutoff = (passHz + stopHz) / 2.0 / upsampledHz
        val transition = 2.0 * PI * (stopHz - passHz) / upsampledHz
        val designLength = ceil((STOPBAND_DB - 7.95) / (2.285 * transition)).toInt() + 1
        taps = maxOf(ceil(designLength.toDouble() / up).toInt(), down / up + 2)
        val prototypeLength = taps * up
        val beta = 0.1102 * (STOPBAND_DB - 8.7)
        val centre = (prototypeLength - 1) / 2.0
        val prototype = DoubleArray(prototypeLength) { n ->
            val x = n - centre
            val sinc = if (x == 0.0) 2.0 * cutoff else sin(2.0 * PI * cutoff * x) / (PI * x)
            val ratio = 2.0 * n / (prototypeLength - 1) - 1.0
            sinc * besselI0(beta * sqrt((1.0 - ratio * ratio).coerceAtLeast(0.0))) / besselI0(beta)
        }
        // Unity DC gain through each phase on average: the zero-stuffed upsampling divides by `up`.
        val scale = up / prototype.sum()
        phases = Array(up) { p -> FloatArray(taps) { k -> (prototype[p + k * up] * scale).toFloat() } }
        history = FloatArray(taps - 1 + INITIAL_CAPACITY)
        length = taps - 1
        cursor = taps - 1
    }

    /** Resample [count] samples of [input]; writes into [output] and returns how many it wrote. */
    fun process(input: ShortArray, count: Int, output: ShortArray): Int {
        if (length + count > history.size) history = history.copyOf(maxOf(history.size * 2, length + count))
        for (i in 0 until count) history[length + i] = input[i].toFloat()
        length += count
        var written = 0
        while (cursor < length) {
            val coefficients = phases[phase]
            var sum = 0f
            for (k in 0 until taps) sum += coefficients[k] * history[cursor - k]
            output[written++] = sum.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            phase += down
            cursor += phase / up
            phase %= up
        }
        // Keep exactly the history the next output needs.
        val keepFrom = cursor - (taps - 1)
        val kept = length - keepFrom
        System.arraycopy(history, keepFrom, history, 0, kept)
        length = kept
        cursor -= keepFrom
        return written
    }

    private companion object {
        const val PASS_FRACTION = 7_000.0 / 8_000.0
        const val STOP_FRACTION = 8_400.0 / 8_000.0
        const val STOPBAND_DB = 80.0
        const val INITIAL_CAPACITY = 1_024

        fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

        /** Modified Bessel function of the first kind, order zero, by its power series. */
        fun besselI0(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            val half = x / 2.0
            var k = 1
            while (term > 1e-12 * sum) {
                term *= (half / k) * (half / k)
                sum += term
                k += 1
            }
            return sum
        }
    }
}
