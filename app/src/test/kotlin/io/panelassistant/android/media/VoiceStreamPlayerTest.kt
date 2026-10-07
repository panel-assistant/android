package io.panelassistant.android.media

import io.panelassistant.android.panelassistant.PanelAssistantVoiceStreamGrant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The voice player as a session runs it, with the native library and the speaker replaced: the test plays
 * the library (stream start and end events, the PCM it hands over) and records what reaches the speaker.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceStreamPlayerTest {
    @get:Rule val temp = TemporaryFolder()

    private val serverId = "s".repeat(43)

    /** The library: events and PCM the test queues, and every call the player makes. */
    private class Library : SendspinApi {
        val events = ConcurrentLinkedQueue<Pair<Int, Long>>()

        @Volatile private var eventValue = 0L
        val pcm = ConcurrentLinkedQueue<ByteArray>()
        val inFlight = AtomicInteger()
        val reads = AtomicInteger()
        val playedFrames = AtomicInteger()

        @Volatile var destroyedWhileInFlight = -1

        @Volatile var callsAfterDestroy = 0

        @Volatile var destroyed = false
        val destroyedLatch = CountDownLatch(1)

        /** When set, the next read blocks inside the "native" call until this opens. */
        @Volatile var readGate: CountDownLatch? = null
        val readBlocked = CountDownLatch(1)

        private fun native(): AutoCloseable {
            if (destroyed) callsAfterDestroy++
            inFlight.incrementAndGet()
            return AutoCloseable { inFlight.decrementAndGet() }
        }

        override val available = true
        override fun create(stateDir: String, name: String, softwareVersion: String, sampleRate: Int, channels: Int, requiredLeadMs: Int) = 7L
        override fun clientId(handle: Long) = "c".repeat(43)
        override fun connect(handle: Long, url: String) {}
        override fun loop(handle: Long) {}
        override fun nextEvent(handle: Long): Int {
            val (event, value) = events.poll() ?: return 0
            eventValue = value
            return event
        }
        override fun eventValue(handle: Long) = eventValue

        /** A stream starts whose first chunk carries server time [startUs]. */
        fun start(startUs: Long) {
            events += (SendspinNative.EVENT_STREAM_START or (2 shl 4) or (RATE shl 8)) to 0L
            events += SendspinNative.EVENT_STREAM_FIRST_CHUNK to startUs
        }
        override fun serverId(handle: Long) = "s".repeat(43)
        override fun read(handle: Long, buffer: ByteBuffer, maxBytes: Int, waitMs: Int): Int = native().use {
            reads.incrementAndGet()
            readGate?.let { gate ->
                readBlocked.countDown()
                gate.await(10, TimeUnit.SECONDS)
            }
            val next = pcm.poll()
            if (next == null) {
                Thread.sleep(waitMs.toLong())
                return 0
            }
            buffer.put(0, next) // as the library does: at the buffer's address, leaving its position
            next.size
        }
        override fun played(handle: Long, frames: Int, finishUs: Long) = native().use {
            playedFrames.addAndGet(frames)
            Unit
        }
        override fun nowUs() = System.nanoTime() / 1_000L
        override fun destroy(handle: Long) {
            destroyedWhileInFlight = inFlight.get()
            destroyed = true
            destroyedLatch.countDown()
        }
        override fun writeRecord(stateDir: String, serverId: String, psk: ByteArray) = true
        override fun takeOutbound(handle: Long, timeoutMs: Int): ByteArray? = native().use {
            Thread.sleep(minOf(timeoutMs, 5).toLong())
            null
        }
        override fun deliver(handle: Long, transportId: Int, type: Int, data: ByteArray?, receiveUs: Long) {
            native().close()
        }
    }

    /** The speaker: everything written to it, and how it was opened and closed. */
    private class Speaker : VoiceOutput {
        val bytes = ByteArrayOutputStream()
        val released = CountDownLatch(1)

        /** When set, every write of audio (not silence) blocks until this opens, as a stalled device would. */
        @Volatile var writeGate: CountDownLatch? = null
        val writeBlocked = CountDownLatch(1)

        override fun play() {}
        override fun write(data: ByteArray, offset: Int, length: Int): Int {
            writeGate?.takeIf { (offset until offset + length).any { data[it] != 0.toByte() } }?.let { gate ->
                writeBlocked.countDown()
                gate.await(10, TimeUnit.SECONDS)
            }
            synchronized(bytes) { bytes.write(data, offset, length) }
            return length
        }
        override fun pause() {}
        override fun flush() {}
        override fun stop() {}
        override fun release() = released.countDown()
        override fun playedFrames() = Long.MAX_VALUE
        override fun finishNs(written: Long, rate: Int) = System.nanoTime()

        fun written(): ByteArray = synchronized(bytes) { bytes.toByteArray() }
    }

    private class Speakers {
        val opened = ConcurrentLinkedQueue<Speaker>()
        val factory: (Int, Int) -> VoiceOutput = { _, _ -> Speaker().also { opened += it } }
        fun only(): Speaker = opened.single()
    }

    private fun player(library: Library, speakers: Speakers, claims: VoiceStreamClaims, joinMs: Long = 2_000L) =
        VoiceStreamPlayer(
            stateDir = temp.newFolder(),
            name = { "Panel" },
            softwareVersion = "test",
            outputRate = { RATE },
            claims = claims,
            sockets = { awaitCancellation() },
            native = library,
            openOutput = speakers.factory,
            writerJoinMs = joinMs,
        ).also { it.open("ws://pa.invalid/api/panel_assistant/sendspin", PanelAssistantVoiceStreamGrant("/p", serverId, ByteArray(32))) }

    /** 20 ms of non-silent stereo PCM, distinct per [n]. */
    private fun chunk(n: Int) = ByteArray(RATE / 50 * FRAME_BYTES) { (n + 1).toByte() }

    /**
     * Plays one stream on the library, first chunk at server time [startUs], carrying [chunks]: waits until the
     * player has consumed them, ends the stream, and waits until its writer has left.
     */
    private fun Library.stream(startUs: Long, chunks: List<ByteArray>) {
        val before = playedFrames.get()
        chunks.forEach { pcm += it }
        start(startUs)
        val frames = chunks.sumOf { it.size } / FRAME_BYTES
        await("the player consumed the stream") { playedFrames.get() - before >= frames }
        events += SendspinNative.EVENT_STREAM_END to 0L
        await("the stream's writer left") { Thread.getAllStackTraces().keys.none { it.isAlive && it.name.startsWith("sendspin-audio-") } }
    }

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) {
                "timed out waiting: $what\n" + Thread.getAllStackTraces().filterKeys { it.name.startsWith("sendspin") }
                    .entries.joinToString("\n") { (t, st) -> t.name + "\n  " + st.take(12).joinToString("\n  ") }
            }
            Thread.sleep(2)
        }
    }

    /** The speaker heard exactly [pcm], in order, then only the silent tail. */
    private fun Speaker.heardExactly(pcm: ByteArray) {
        val audio = written()
        assertArrayEquals("exactly this announcement's audio, in order", pcm, audio.copyOf(pcm.size))
        assertTrue("then only the silent tail", audio.drop(pcm.size).all { it == 0.toByte() })
    }

    private fun Speakers.awaitReleased(count: Int) =
        await("$count speaker(s) released") { opened.size == count && opened.all { it.released.count == 0L } }

    @Test fun underRapidPreemptionTheCancelledAnnouncementsDelayedStreamWritesNoAudioAndTheNewerPlaysItsOwn() = runTest {
        // Rapid preemption: A's event at 100 s (its stream scheduled for 100.5 s), B's event at 100.6 s
        // cancels A before A's stream arrives; then A's delayed stream, then B's (scheduled for 101.1 s).
        var nowNs = 100 * SECOND
        val played = mutableListOf<String>()
        val claims = VoiceStreamClaims(nanoTime = { nowNs })
        val coordinator = AudioPlaybackCoordinator(urlLane(claims, played), StandardTestDispatcher(testScheduler))
        val library = Library()
        val speakers = Speakers()
        val player = player(library, speakers, claims)
        coordinator.submitStreamForGeneration(StreamCue(nowNs, A_US), listOf("a.mp3"))
        runCurrent()
        nowNs += 600_000_000L
        val b = requireNotNull(coordinator.submitStreamForGeneration(StreamCue(nowNs, B_US), listOf("b.mp3")))
        runCurrent()

        library.stream(A_US, listOf(chunk(1), chunk(2)))
        assertTrue("A's delayed stream never opens the speaker", speakers.opened.isEmpty())
        runCurrent()
        assertEquals("B does not finish on A's stream", AudioPlaybackCoordinator.State.ACTIVE, coordinator.snapshot().state)

        library.stream(B_US, listOf(chunk(3), chunk(4)))
        speakers.awaitReleased(1)
        speakers.only().heardExactly(chunk(3) + chunk(4))
        runCurrent()
        assertEquals(AudioPlaybackCoordinator.Snapshot(AudioPlaybackCoordinator.State.IDLE, b), coordinator.snapshot())
        assertEquals("nothing fell back to its URL", emptyList<String>(), played)
        player.close()
        assertTrue(library.destroyedLatch.await(10, TimeUnit.SECONDS))
        assertTrue(coordinator.close(1_000L))
    }

    @Test fun aFallenBackAnnouncementsStreamDelayedPastANewerEventWritesNoAudioAndTheNewerPlaysItsOwn() = runTest {
        var nowNs = 100 * SECOND
        val played = mutableListOf<String>()
        val claims = VoiceStreamClaims(nanoTime = { nowNs })
        val coordinator = AudioPlaybackCoordinator(urlLane(claims, played), StandardTestDispatcher(testScheduler))
        val library = Library()
        val speakers = Speakers()
        val player = player(library, speakers, claims)
        coordinator.submitStreamForGeneration(StreamCue(nowNs, A_US), listOf("a.mp3"))
        runCurrent()
        advanceTimeBy(3_001L)
        runCurrent()
        assertEquals(listOf("a.mp3"), played)

        nowNs += 4 * SECOND
        val b = requireNotNull(coordinator.submitStreamForGeneration(StreamCue(nowNs, B_US), listOf("b.mp3")))
        runCurrent()
        library.stream(A_US, listOf(chunk(1), chunk(2)))
        assertTrue("A's delayed stream never opens the speaker", speakers.opened.isEmpty())
        runCurrent()
        assertEquals("B does not finish on A's stream", AudioPlaybackCoordinator.State.ACTIVE, coordinator.snapshot().state)

        library.stream(B_US, listOf(chunk(3), chunk(4)))
        speakers.awaitReleased(1)
        speakers.only().heardExactly(chunk(3) + chunk(4))
        runCurrent()
        assertEquals(AudioPlaybackCoordinator.Snapshot(AudioPlaybackCoordinator.State.IDLE, b), coordinator.snapshot())
        assertEquals("B played its stream, not its URL", listOf("a.mp3"), played)
        player.close()
        assertTrue(library.destroyedLatch.await(10, TimeUnit.SECONDS))
        assertTrue(coordinator.close(1_000L))
    }

    @Test fun aFallenBackAnnouncementsStreamArrivingAfterTheNewerOnesWritesNoAudio() = runTest {
        var nowNs = 100 * SECOND
        val played = mutableListOf<String>()
        val claims = VoiceStreamClaims(nanoTime = { nowNs })
        val coordinator = AudioPlaybackCoordinator(urlLane(claims, played), StandardTestDispatcher(testScheduler))
        val library = Library()
        val speakers = Speakers()
        val player = player(library, speakers, claims)
        coordinator.submitStreamForGeneration(StreamCue(nowNs, A_US), listOf("a.mp3"))
        runCurrent()
        advanceTimeBy(3_001L)
        runCurrent()

        nowNs += 4 * SECOND
        val b = requireNotNull(coordinator.submitStreamForGeneration(StreamCue(nowNs, B_US), listOf("b.mp3")))
        runCurrent()
        library.stream(B_US, listOf(chunk(3), chunk(4)))
        speakers.awaitReleased(1)
        runCurrent()
        assertEquals("B completes on its own stream", AudioPlaybackCoordinator.Snapshot(AudioPlaybackCoordinator.State.IDLE, b), coordinator.snapshot())

        library.stream(A_US, listOf(chunk(1), chunk(2)))
        assertEquals("A's delayed stream opens no speaker", 1, speakers.opened.size)
        speakers.only().heardExactly(chunk(3) + chunk(4))
        assertEquals(listOf("a.mp3"), played)
        player.close()
        assertTrue(library.destroyedLatch.await(10, TimeUnit.SECONDS))
        assertTrue(coordinator.close(1_000L))
    }

    @Test fun whenAFallenBackAnnouncementsStreamNeverComesTheNewerOnePlaysItsOwnStream() = runTest {
        var nowNs = 100 * SECOND
        val played = mutableListOf<String>()
        val claims = VoiceStreamClaims(nanoTime = { nowNs })
        val coordinator = AudioPlaybackCoordinator(urlLane(claims, played), StandardTestDispatcher(testScheduler))
        val library = Library()
        val speakers = Speakers()
        val player = player(library, speakers, claims)
        coordinator.submitStreamForGeneration(StreamCue(nowNs, A_US), listOf("a.mp3"))
        runCurrent()
        advanceTimeBy(3_001L)
        runCurrent()

        nowNs += 4 * SECOND
        val b = requireNotNull(coordinator.submitStreamForGeneration(StreamCue(nowNs, B_US), listOf("b.mp3")))
        runCurrent()
        library.stream(B_US, listOf(chunk(3), chunk(4)))
        speakers.awaitReleased(1)
        speakers.only().heardExactly(chunk(3) + chunk(4))
        runCurrent()
        assertEquals(AudioPlaybackCoordinator.Snapshot(AudioPlaybackCoordinator.State.IDLE, b), coordinator.snapshot())
        assertEquals("no URL fallback for B", listOf("a.mp3"), played)
        player.close()
        assertTrue(library.destroyedLatch.await(10, TimeUnit.SECONDS))
        assertTrue(coordinator.close(1_000L))
    }

    @Test fun aStreamArrivingAfterItsAnnouncementWasCancelledWritesNoAudio() = runTest {
        val nowNs = 100 * SECOND
        val claims = VoiceStreamClaims(nanoTime = { nowNs })
        val coordinator = AudioPlaybackCoordinator(urlLane(claims), StandardTestDispatcher(testScheduler))
        val library = Library()
        val speakers = Speakers()
        val player = player(library, speakers, claims)
        val generation = requireNotNull(coordinator.submitStreamForGeneration(StreamCue(nowNs, A_US)))
        runCurrent()
        assertTrue(coordinator.cancelGeneration(generation))
        runCurrent()

        library.stream(A_US, listOf(chunk(1), chunk(2), chunk(3)))
        assertTrue("the cancelled announcement's stream never opens the speaker", speakers.opened.isEmpty())
        player.close()
        assertTrue(library.destroyedLatch.await(10, TimeUnit.SECONDS))
        assertTrue(speakers.opened.isEmpty())
        assertTrue(coordinator.close(1_000L))
    }

    @Test fun aSingleAnnouncementPlaysItsStream() = runTest {
        val nowNs = 100 * SECOND
        val claims = VoiceStreamClaims(nanoTime = { nowNs })
        val coordinator = AudioPlaybackCoordinator(urlLane(claims), StandardTestDispatcher(testScheduler))
        val library = Library()
        val speakers = Speakers()
        val player = player(library, speakers, claims)
        val generation = requireNotNull(coordinator.submitStreamForGeneration(StreamCue(nowNs, A_US)))
        runCurrent()
        library.stream(A_US, listOf(chunk(5), chunk(6)))
        speakers.awaitReleased(1)
        speakers.only().heardExactly(chunk(5) + chunk(6))
        runCurrent()
        assertEquals(AudioPlaybackCoordinator.Snapshot(AudioPlaybackCoordinator.State.IDLE, generation), coordinator.snapshot())
        player.close()
        assertTrue(library.destroyedLatch.await(10, TimeUnit.SECONDS))
        assertTrue(coordinator.close(1_000L))
    }

    @Test fun anEventWithoutAStreamStartPlaysItsUrlAndItsStreamWritesNothing() = runTest {
        val nowNs = 100 * SECOND
        val played = mutableListOf<String>()
        val claims = VoiceStreamClaims(nanoTime = { nowNs })
        val coordinator = AudioPlaybackCoordinator(urlLane(claims, played), StandardTestDispatcher(testScheduler))
        val library = Library()
        val speakers = Speakers()
        val player = player(library, speakers, claims)
        val generation = requireNotNull(coordinator.submitStreamForGeneration(StreamCue(nowNs, null), listOf("message.mp3")))
        runCurrent()
        assertEquals(listOf("message.mp3"), played)
        assertEquals(AudioPlaybackCoordinator.Snapshot(AudioPlaybackCoordinator.State.IDLE, generation), coordinator.snapshot())
        library.pcm += chunk(1)
        library.start(A_US)
        Thread.sleep(200)
        assertTrue("the stream an older Panel Assistant sent stays silent", speakers.opened.isEmpty())
        assertEquals("and unread", 0, library.reads.get())
        player.close()
        assertTrue(library.destroyedLatch.await(10, TimeUnit.SECONDS))
        assertTrue(speakers.opened.isEmpty())
        assertTrue(coordinator.close(1_000L))
    }

    @Test fun aStreamNoAnnouncementOwnsNeverOpensTheSpeaker() {
        val library = Library()
        val speakers = Speakers()
        val player = player(library, speakers, VoiceStreamClaims())
        library.pcm += chunk(1)
        library.start(A_US)
        Thread.sleep(200)
        assertTrue("an unowned stream is held silent", speakers.opened.isEmpty())
        assertEquals("and not read", 0, library.reads.get())
        player.close()
        assertTrue(library.destroyedLatch.await(10, TimeUnit.SECONDS))
        assertTrue(speakers.opened.isEmpty())
    }

    /** An announcement naming the stream that starts at [startUs], on its own thread; it returns once that stream ends. */
    private fun own(claims: VoiceStreamClaims, startUs: Long) =
        kotlin.concurrent.thread(isDaemon = true) { kotlinx.coroutines.runBlocking { runCatching { claims.play(StreamCue(System.nanoTime(), startUs)) } } }

    @Test fun closingWhileAWriterIsInsideANativeCallDestroysTheClientOnlyAfterTheCallReturns() {
        val library = Library()
        val speakers = Speakers()
        val claims = VoiceStreamClaims()
        val player = player(library, speakers, claims, joinMs = 50L)
        val gate = CountDownLatch(1)
        library.readGate = gate
        own(claims, A_US)
        library.start(A_US)
        assertTrue(library.readBlocked.await(10, TimeUnit.SECONDS))

        player.close()
        assertFalse("the client outlives the writer's call into it", library.destroyedLatch.await(500, TimeUnit.MILLISECONDS))

        library.readGate = null
        gate.countDown()
        assertTrue("teardown completes once the call returns", library.destroyedLatch.await(10, TimeUnit.SECONDS))
        assertEquals("no native call was in flight when the client was destroyed", 0, library.destroyedWhileInFlight)
        Thread.sleep(50)
        assertEquals("no native call follows the destroy", 0, library.callsAfterDestroy)
    }

    @Test fun closingWhileTheSpeakerIsStalledCompletesAndTheWritersLaterCallsNeverReachTheClient() {
        val library = Library()
        val speakers = Speakers()
        val claims = VoiceStreamClaims()
        val player = player(library, speakers, claims, joinMs = 50L)
        val stall = CountDownLatch(1)
        own(claims, A_US)
        library.start(A_US)
        await("the speaker opened") { speakers.opened.isNotEmpty() }
        speakers.only().writeGate = stall
        library.pcm += chunk(1)
        assertTrue(speakers.only().writeBlocked.await(10, TimeUnit.SECONDS))

        player.close()
        assertTrue("teardown does not wait on a stalled speaker", library.destroyedLatch.await(10, TimeUnit.SECONDS))
        assertEquals(0, library.destroyedWhileInFlight)

        stall.countDown()
        assertTrue("the writer still leaves and releases its speaker", speakers.only().released.await(10, TimeUnit.SECONDS))
        assertEquals("its played report after the destroy never reaches the client", 0, library.callsAfterDestroy)
    }

    @Test fun aHandleRefusesCallsOnceDestroyed() {
        var freed = 0L
        val handle = SendspinHandle(9L) { freed = it }
        assertEquals(9L, handle.call(0L) { it })
        handle.destroy()
        assertEquals(9L, freed)
        assertNull(handle.call(null) { "reached" })
    }

    private fun urlLane(claims: VoiceStreamClaims, played: MutableList<String> = mutableListOf()) = object : AudioPlaybackRunFactory {
        override fun create(url: String) = error("streamed speech never plays as media")
        override fun createSpeech(url: String) = object : AudioPlaybackRun {
            override suspend fun execute() { played += url }
            override fun cancel() {}
        }
        override fun createStream(cue: StreamCue, fallbackUrls: List<String>): AudioPlaybackRun =
            StreamedSpeechRun(claims, cue, fallbackUrls, ::createSpeech)
    }

    private companion object {
        const val RATE = 8_000
        const val FRAME_BYTES = 4
        const val SECOND = 1_000_000_000L
        const val A_US = 1_759_830_000_500_000L
        const val B_US = 1_759_830_001_100_000L
    }
}
