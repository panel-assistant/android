package io.panelassistant.android.media

import io.panelassistant.android.panelassistant.PanelAssistantVoiceStreamGrant
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
 * the library (its outbound requests, stream start and end events, the PCM it hands over) and records what
 * reaches the library, the session and the speaker.
 */
class VoiceStreamPlayerTest {
    @get:Rule val temp = TemporaryFolder()

    private val serverId = "s".repeat(43)

    /** The library: events and PCM the test queues, and every call the player makes. */
    private class Library : SendspinApi {
        val events = ConcurrentLinkedQueue<Int>()

        /** Requests the library makes of its transport, as [SendspinApi.takeOutbound] returns them. */
        val outbound = java.util.concurrent.LinkedBlockingQueue<ByteArray>()

        /** Every delivery: (transport id, type, payload). */
        val delivered = ConcurrentLinkedQueue<Triple<Int, Int, String>>()
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
        override fun create(stateDir: String, name: String, softwareVersion: String, requiredLeadMs: Int) = 7L
        override fun clientId(handle: Long) = "c".repeat(43)
        override fun connect(handle: Long, url: String) {}
        override fun loop(handle: Long) {}
        override fun nextEvent(handle: Long): Int = events.poll() ?: 0

        /** A mono stream starts at [RATE]. */
        fun start() {
            events += SendspinNative.EVENT_STREAM_START or (1 shl 4) or (RATE shl 8)
        }

        /** The library asks its transport for [type] on connection [id]. */
        fun request(type: Int, id: Int, payload: ByteArray = ByteArray(0)) {
            outbound += byteArrayOf(type.toByte(), (id ushr 24).toByte(), (id ushr 16).toByte(), (id ushr 8).toByte(), id.toByte()) + payload
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
            outbound.poll(minOf(timeoutMs, 5).toLong(), TimeUnit.MILLISECONDS)
        }
        override fun deliver(handle: Long, transportId: Int, type: Int, data: ByteArray?, receiveUs: Long) = native().use {
            delivered += Triple(transportId, type, data?.let { String(it, Charsets.ISO_8859_1) }.orEmpty())
            Unit
        }
    }

    /** The speaker: everything written to it, and how it was opened and closed. */
    private class Speaker : VoiceOutput {
        val bytes = ByteArrayOutputStream()
        val released = CountDownLatch(1)

        /** When set, every write of audio (not silence) blocks until this opens, as a stalled device would. */
        @Volatile var writeGate: CountDownLatch? = null
        val writeBlocked = CountDownLatch(1)

        @Volatile var paused = false
        override fun play() {}
        override fun write(data: ByteArray, offset: Int, length: Int): Int {
            writeGate?.takeIf { (offset until offset + length).any { data[it] != 0.toByte() } }?.let { gate ->
                writeBlocked.countDown()
                gate.await(10, TimeUnit.SECONDS)
            }
            synchronized(bytes) { bytes.write(data, offset, length) }
            return length
        }
        override fun pause() { paused = true }
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

    /** Frames the player sent to the session: (is text, payload). */
    private val sent = ConcurrentLinkedQueue<Pair<Boolean, String>>()

    private fun player(library: Library, speakers: Speakers, joinMs: Long = 2_000L) =
        VoiceStreamPlayer(
            stateDir = temp.newFolder(),
            name = { "Panel" },
            softwareVersion = "test",
            native = library,
            openOutput = speakers.factory,
            writerJoinMs = joinMs,
        ).also { player ->
            player.open(PanelAssistantVoiceStreamGrant(serverId, ByteArray(32))) { frame, text -> sent += text to String(frame, Charsets.ISO_8859_1) }
        }

    /** 20 ms of non-silent mono PCM, distinct per [n]. */
    private fun chunk(n: Int) = ByteArray(RATE / 50 * FRAME_BYTES) { (n + 1).toByte() }

    /**
     * Plays one stream on the library carrying [chunks]: waits until the player has consumed them, ends the
     * stream, and waits until its writer has left.
     */
    private fun Library.stream(chunks: List<ByteArray>) {
        val before = playedFrames.get()
        chunks.forEach { pcm += it }
        start()
        val frames = chunks.sumOf { it.size } / FRAME_BYTES
        await("the player consumed the stream") { playedFrames.get() - before >= frames }
        events += SendspinNative.EVENT_STREAM_END
        await("the stream's writer left") { Thread.getAllStackTraces().keys.none { it.isAlive && it.name == "sendspin-audio" } }
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
        await("$count speaker(s) released (opened ${opened.size}, released ${opened.count { it.released.count == 0L }})") {
            opened.size == count && opened.all { it.released.count == 0L }
        }

    @Test fun theSessionCarriesTheLibrarysConnectionBothWaysInOrder() {
        val library = Library()
        val player = player(library, Speakers())
        library.request(SendspinNative.OPEN, 5, "panel-assistant://session".toByteArray())
        await("the open was answered") { library.delivered.isNotEmpty() }
        assertEquals("the session is already up: the open is answered at once", Triple(5, SendspinNative.OPEN, ""), library.delivered.single())

        library.request(SendspinNative.TEXT, 5, "client/hello".toByteArray())
        library.request(SendspinNative.BINARY, 5, byteArrayOf(1, 2, 3))
        library.request(SendspinNative.TEXT, 4, "a replaced connection".toByteArray())
        library.request(SendspinNative.TEXT, 5, "client/time".toByteArray())
        await("three frames sent") { sent.size >= 3 }
        assertEquals(
            "the library's messages reach the session in order, only for its connection",
            listOf(true to "client/hello", false to "\u0001\u0002\u0003", true to "client/time"),
            sent.toList(),
        )

        val inbound = (1..50).map { n -> (n % 4 != 0) to "server-$n" }
        inbound.forEach { (text, frame) -> player.receive(frame.toByteArray(), text) }
        await("every frame delivered") { library.delivered.size >= 1 + inbound.size }
        assertEquals(
            "every frame from the session reaches the library once, in order, on its connection",
            inbound.map { (text, frame) -> Triple(5, if (text) SendspinNative.TEXT else SendspinNative.BINARY, frame) },
            library.delivered.drop(1),
        )
        player.close()
        assertTrue(library.destroyedLatch.await(10, TimeUnit.SECONDS))
    }

    @Test fun whateverTheLibraryRendersPlaysAndTheSpeakerOpensOnlyWithAudio() {
        val library = Library()
        val speakers = Speakers()
        val player = player(library, speakers)
        library.start()
        Thread.sleep(100)
        assertTrue("a stream with no audio yet opens no speaker", speakers.opened.isEmpty())
        library.pcm += chunk(1)
        library.pcm += chunk(2)
        await("the player consumed the stream") { library.playedFrames.get() >= 2 * chunk(0).size / FRAME_BYTES }
        library.events += SendspinNative.EVENT_STREAM_END
        speakers.awaitReleased(1)
        speakers.only().heardExactly(chunk(1) + chunk(2))

        library.stream(listOf(chunk(3)))
        speakers.awaitReleased(2)
        speakers.opened.last().heardExactly(chunk(3))
        player.close()
        assertTrue(library.destroyedLatch.await(10, TimeUnit.SECONDS))
    }

    @Test fun muteSilencesThePlayingStreamAndOneStartingWhileMutedAndUnmuteLetsTheNextPlay() {
        val library = Library()
        val speakers = Speakers()
        val player = player(library, speakers)
        library.start()
        library.pcm += chunk(1)
        await("the speaker opened") { speakers.opened.isNotEmpty() }
        await("the first audio was written") { speakers.only().written().any { it != 0.toByte() } }
        player.mute()
        await("the speaker paused") { speakers.only().paused }
        val heard = speakers.only().written().size
        library.pcm += chunk(2)
        library.pcm += chunk(3)
        await("the silenced audio was still consumed") { library.playedFrames.get() >= 3 * chunk(0).size / FRAME_BYTES }
        library.events += SendspinNative.EVENT_STREAM_END
        speakers.awaitReleased(1)
        assertEquals("nothing is written once muted", heard, speakers.only().written().size)

        library.stream(listOf(chunk(4)))
        assertEquals("a stream starting while muted opens no speaker", 1, speakers.opened.size)

        player.unmute()
        library.stream(listOf(chunk(5)))
        speakers.awaitReleased(2)
        speakers.opened.last().heardExactly(chunk(5))
        player.close()
        assertTrue(library.destroyedLatch.await(10, TimeUnit.SECONDS))
    }

    @Test fun closingWhileAWriterIsInsideANativeCallDestroysTheClientOnlyAfterTheCallReturns() {
        val library = Library()
        val speakers = Speakers()
        val player = player(library, speakers, joinMs = 50L)
        val gate = CountDownLatch(1)
        library.readGate = gate
        library.start()
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
        val player = player(library, speakers, joinMs = 50L)
        val stall = CountDownLatch(1)
        library.start()
        library.pcm += chunk(0)
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

    private companion object {
        const val RATE = 8_000
        const val FRAME_BYTES = 2
    }
}
