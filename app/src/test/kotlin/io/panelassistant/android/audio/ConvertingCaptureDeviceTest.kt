package io.panelassistant.android.audio

import android.media.MediaRecorder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whatever the recorder delivers, the fan-out receives 16 kHz mono: the converting device discards the
 * warm-up, mixes two channels down and resamples, and its output does not depend on how the recorder
 * happened to split its reads.
 */
class ConvertingCaptureDeviceTest {

    private val sources = mutableListOf<MicrophoneFanOut>()

    @After
    fun shutdown() {
        sources.forEach { runCatching { it.shutdown(2_000L) } }
    }

    private fun device(recorder: FakeRecorder, downmix: CaptureDownmix = CaptureDownmix.DOMINANCE, warmupReads: Int = 0) =
        ConvertingCaptureDevice(AudioRecordCaptureDevice(openRecorder = { recorder }), downmix, warmupReads)

    /** Every sample the device yields until the recorder runs dry, read in [outputChunk]-sized requests. */
    private fun drain(device: PcmCaptureDevice, outputChunk: () -> Int = { MicrophoneSource.SAMPLES_PER_FRAME }): ShortArray {
        assertTrue("the device opens", device.open())
        val out = ArrayList<Short>()
        val buffer = ShortArray(1_024)
        while (true) {
            val read = device.read(buffer, 0, outputChunk().coerceIn(1, buffer.size))
            if (read <= 0) break
            for (i in 0 until read) out.add(buffer[i])
        }
        device.close()
        return out.toShortArray()
    }

    private fun sweep(rate: Int, channels: Int, seconds: Double): ShortArray {
        val frames = (rate * seconds).toInt()
        return ShortArray(frames * channels) { index ->
            val t = (index / channels).toDouble() / rate
            // 100 Hz to 7.9 kHz across the window, louder on the left, so dominance and averaging both occur.
            val phase = 2 * PI * (100 * t + (7_800.0 / (2 * seconds)) * t * t)
            val amplitude = if (channels == 2 && index % 2 == 1) 3_000.0 else 12_000.0
            (amplitude * sin(phase + (index % channels))).roundToInt().toShort()
        }
    }

    private fun mixAll(interleaved: ShortArray, channels: Int, mode: CaptureDownmix): ShortArray =
        if (channels == 1) interleaved.copyOf()
        else ShortArray(interleaved.size / 2) { ConvertingCaptureDevice.mixDown(interleaved[2 * it], interleaved[2 * it + 1], mode) }

    private fun chunked(recorder: FakeRecorder, samples: ShortArray, random: Random) {
        var at = 0
        while (at < samples.size) {
            // Odd sizes split channel pairs; sizes past one read's worth span reads.
            val size = minOf(random.nextInt(1, 1_400), samples.size - at)
            recorder.offer(samples.copyOfRange(at, at + size))
            at += size
        }
    }

    @Test fun aSweepSplitAcrossArbitraryReadsEqualsTheOneShotResult() {
        for ((rate, channels) in listOf(48_000 to 2, 44_100 to 1, 44_100 to 2, 48_000 to 1)) {
            val input = sweep(rate, channels, seconds = 0.75)
            val oneShot = ShortArray(input.size).let { out ->
                val written = PolyphaseResampler(rate, 16_000).process(mixAll(input, channels, CaptureDownmix.DOMINANCE), input.size / channels, out)
                out.copyOf(written)
            }
            val recorder = FakeRecorder(RecorderFormat(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, channels))
            val random = Random(rate + channels)
            chunked(recorder, input, random)
            val streamed = drain(device(recorder)) { random.nextInt(1, 400) }

            assertTrue("$rate/$channels: about a third of the input rate comes out, was ${streamed.size}",
                streamed.size in (oneShot.size - 1)..(oneShot.size + 1) && oneShot.size > 0)
            assertArrayEquals("$rate Hz x$channels: chunked reads change nothing", oneShot, streamed)
        }
    }

    private fun levelDb(rate: Int, toneHz: Int): Double {
        val amplitude = 10_000.0
        val frames = rate / 2
        val input = ShortArray(frames) { (amplitude * sin(2 * PI * toneHz * it / rate)).roundToInt().toShort() }
        val recorder = FakeRecorder(RecorderFormat(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, 1))
        recorder.offer(input)
        val output = drain(device(recorder))
        // Skip the filter's settling time; measure the steady state.
        val steady = output.copyOfRange(1_000, output.size - 200)
        val rms = sqrt(steady.sumOf { it.toDouble() * it } / steady.size)
        return 20 * log10(rms / (amplitude / sqrt(2.0)))
    }

    @Test fun speechBandTonesPassAndContentAboveTheOutputNyquistIsRejected() {
        for (rate in listOf(48_000, 44_100)) {
            val passed = levelDb(rate, 7_000)
            assertTrue("$rate Hz: a 7 kHz tone passes within 1 dB, was $passed dB", passed > -1.0 && passed < 1.0)
            val voice = levelDb(rate, 1_000)
            assertTrue("$rate Hz: a 1 kHz tone passes within 0.5 dB, was $voice dB", voice > -0.5 && voice < 0.5)
            val rejected = levelDb(rate, 12_000)
            assertTrue("$rate Hz: a 12 kHz tone is at least 40 dB down, was $rejected dB", rejected <= -40.0)
        }
    }

    @Test fun dominanceTakesAChannelFourTimesLouderAndAveragesOtherwise() {
        val mix = { l: Int, r: Int -> ConvertingCaptureDevice.mixDown(l.toShort(), r.toShort(), CaptureDownmix.DOMINANCE).toInt() }
        assertEquals("left four times louder is taken alone", 1_000, mix(1_000, 250))
        assertEquals("just under four times is averaged", 625, mix(1_000, 251))
        assertEquals("right four times louder is taken alone", -1_000, mix(100, -1_000))
        assertEquals("a dead right channel does not halve the left", 8_000, mix(8_000, 0))
        assertEquals("silence stays silence", 0, mix(0, 0))
        assertEquals("full scale is compared without overflow", 20_479, mix(32_767, 8_192))
        assertEquals("the most negative sample survives", -32_768, mix(-32_768, -32_768))
        assertEquals(650, ConvertingCaptureDevice.mixDown(1_000, 300, CaptureDownmix.AVERAGE).toInt())
        assertEquals(1_000, ConvertingCaptureDevice.mixDown(1_000, 300, CaptureDownmix.LEFT).toInt())
        assertEquals(300, ConvertingCaptureDevice.mixDown(1_000, 300, CaptureDownmix.RIGHT).toInt())
    }

    @Test fun aStereoRecorderIsMixedPerSampleEvenWhenAReadSplitsAPair() {
        val recorder = FakeRecorder(RecorderFormat(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16_000, 2))
        val left = ShortArray(320) { (it * 50).toShort() }
        val interleaved = ShortArray(640) { if (it % 2 == 0) left[it / 2] else 3 }
        var at = 0
        var size = 3
        while (at < interleaved.size) {
            val take = minOf(size, interleaved.size - at)
            recorder.offer(interleaved.copyOfRange(at, at + take))
            at += take
            size = if (size == 3) 1 else 3
        }
        val out = drain(device(recorder, CaptureDownmix.DOMINANCE))
        assertArrayEquals(mixAll(interleaved, 2, CaptureDownmix.DOMINANCE), out)
    }

    @Test fun theWarmUpReadsAfterEveryOpenAreDiscarded() {
        val recorder = FakeRecorder()
        listOf<Short>(1, 2, 3).forEach { value -> recorder.offer(ShortArray(160) { value }) }
        val out = drain(device(recorder, warmupReads = 2))
        assertArrayEquals("only the third read reaches the fan-out", ShortArray(160) { 3 }, out)
    }

    @Test fun aFortyEightKilohertzStereoRecorderReachesEveryLeaseAsCanonicalFrames() {
        val recorders = CopyOnWriteArrayList<FakeRecorder>()
        val route = CaptureRoute(sampleRateHz = 48_000, channels = 2, downmix = CaptureDownmix.DOMINANCE, warmupReads = 1)
        val fan = MicrophoneFanOut(
            deviceFactory = {
                ConvertingCaptureDevice(
                    AudioRecordCaptureDevice({
                        FakeRecorder(RecorderFormat(MediaRecorder.AudioSource.VOICE_RECOGNITION, 48_000, 2), blockWhenIdle = true)
                            .apply {
                                routed = "15:Built-In Mic"
                                aec = AecState.UNAVAILABLE
                            }
                            .also(recorders::add)
                    }),
                    route.downmix,
                    route.warmupReads,
                )
            },
            threadNamePrefix = "test-mic",
            rebuildScheduler = { _, _ -> AutoCloseable {} },
        ).also(sources::add)
        val frames = CopyOnWriteArrayList<ShortArray>()
        val count = AtomicInteger()
        val lease = fan.lease(MicPurpose.WAKE_WORD, consumer = object : PcmConsumer {
            override fun onFrame(frame: PcmFrame) {
                assertEquals(16_000, frame.sampleRate)
                frames.add(frame.samples)
                count.incrementAndGet()
            }
        }, queueFrames = 200)
        awaitTrue("the recorder opens") { recorders.size == 1 && recorders[0].recording }
        // Left carries a 1 kHz tone, right is a dead channel: dominance keeps the tone at full level.
        val tone = ShortArray(48_000 * 2 / 2) { index ->
            if (index % 2 == 1) 0 else (8_000 * sin(2 * PI * 1_000 * (index / 2) / 48_000.0)).roundToInt().toShort()
        }
        recorders[0].offer(ShortArray(960) { 30_000 }) // the warm-up read, which must not be heard
        recorders[0].offer(tone)
        awaitTrue("half a second arrives as 10 ms frames") { count.get() >= 48 }

        assertTrue("every frame is canonical", frames.all { it.size == MicrophoneSource.SAMPLES_PER_FRAME })
        assertTrue("the warm-up burst never reaches a consumer", frames.first().all { it < 20_000 })
        val steady = frames.drop(5).take(40).flatMap { it.asList() }
        val rms = sqrt(steady.sumOf { it.toDouble() * it } / steady.size)
        assertEquals("the dead channel does not halve the tone", 8_000 / sqrt(2.0), rms, 120.0)

        val report = fan.routeReport(route)
        assertEquals(route, report.requested)
        assertEquals(
            ActiveCapture(
                source = MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRateHz = 48_000,
                channels = 2,
                downmix = CaptureDownmix.DOMINANCE,
                routedDevice = "15:Built-In Mic",
                aec = AecState.UNAVAILABLE,
                warmupReads = 1,
            ),
            report.active,
        )
        assertEquals(0, report.rebuilds)
        lease.close()
        assertEquals("a closed microphone reports nothing open", null, fan.routeReport(route).active)
    }

    private fun awaitTrue(what: String, timeoutMs: Long = 5_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && !condition()) Thread.sleep(1L)
        assertTrue("$what — not satisfied within ${timeoutMs}ms", condition())
    }
}
