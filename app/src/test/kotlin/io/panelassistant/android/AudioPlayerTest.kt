package io.panelassistant.android

import io.panelassistant.android.util.ByteLimitExceeded
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.cert.Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import kotlin.concurrent.thread

@OptIn(ExperimentalCoroutinesApi::class)
class AudioPlayerTest {
    @get:Rule val temporary = TemporaryFolder()

    private class FakeTransfer(
        private val bytes: ByteArray = byteArrayOf(1, 2, 3),
        private val error: Throwable? = null,
    ) : AudioTransfer {
        var cancelCalls = 0
        var closeCalls = 0

        override fun download(url: String, destination: File) {
            error?.let { throw it }
            destination.writeBytes(bytes)
        }

        override fun cancel() {
            cancelCalls++
        }

        override fun close() {
            closeCalls++
        }
    }

    private class FakeClip(private val block: Boolean = false, private val error: Throwable? = null) : AudioClip {
        val started = CompletableDeferred<Unit>()
        var cancelCalls = 0
        var closeCalls = 0
        var observed: ByteArray? = null

        override suspend fun play(file: File) {
            observed = file.readBytes()
            started.complete(Unit)
            error?.let { throw it }
            if (block) awaitCancellation()
        }

        override fun cancel() {
            cancelCalls++
        }

        override suspend fun close() {
            closeCalls++
        }
    }

    @Test fun preparedAudioIsPlayedAndBothFilesAreDeleted() = runTest {
        val clip = FakeClip()
        val preparation = object : AudioPlaybackPreparation {
            override suspend fun prepare(source: File, destination: File) {
                destination.writeBytes(source.readBytes() + byteArrayOf(0, 0))
            }
            override fun cancel() {}
        }
        DownloadedAudioRun("http://example/audio",
            { File.createTempFile(AudioPlayer.TEMP_PREFIX, AudioPlayer.TEMP_SUFFIX, temporary.root) },
            FakeTransfer(), clip, StandardTestDispatcher(testScheduler), preparation).execute()
        assertArrayEquals(byteArrayOf(1, 2, 3, 0, 0), clip.observed)
        assertTrue(temporary.root.listFiles()!!.isEmpty())
    }

    @Test fun failedPreparationCannotPlayOriginalAndDeletesPartialOutput() = runTest {
        val clip = FakeClip()
        val preparation = object : AudioPlaybackPreparation {
            override suspend fun prepare(source: File, destination: File) {
                destination.writeText("partial")
                throw IOException("decode failed")
            }
            override fun cancel() {}
        }
        try {
            DownloadedAudioRun("http://example/audio",
                { File.createTempFile(AudioPlayer.TEMP_PREFIX, AudioPlayer.TEMP_SUFFIX, temporary.root) },
                FakeTransfer(), clip, StandardTestDispatcher(testScheduler), preparation).execute()
            fail("decode failure must propagate")
        } catch (_: IOException) {}
        assertFalse(clip.started.isCompleted)
        assertTrue(temporary.root.listFiles()!!.isEmpty())
    }

    @Test fun cancellationDuringPreparationDeletesBothFilesWithoutPlayback() = runTest {
        val clip = FakeClip()
        var cancelled = false
        val preparation = object : AudioPlaybackPreparation {
            override suspend fun prepare(source: File, destination: File) { awaitCancellation() }
            override fun cancel() { cancelled = true }
        }
        val run = DownloadedAudioRun("http://example/audio",
            { File.createTempFile(AudioPlayer.TEMP_PREFIX, AudioPlayer.TEMP_SUFFIX, temporary.root) },
            FakeTransfer(), clip, StandardTestDispatcher(testScheduler), preparation)
        val job = launch { run.execute() }
        runCurrent()
        assertEquals(2, temporary.root.listFiles()!!.size)
        run.cancel()
        job.cancelAndJoin()
        assertTrue(cancelled)
        assertFalse(clip.started.isCompleted)
        assertTrue(temporary.root.listFiles()!!.isEmpty())
    }

    @Test fun runDeletesItsTemporaryFileAfterSuccessAndFailure() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var successFile: File? = null
        val successTransfer = FakeTransfer()
        val successClip = FakeClip()
        DownloadedAudioRun(
            "http://example/audio",
            { File.createTempFile(AudioPlayer.TEMP_PREFIX, AudioPlayer.TEMP_SUFFIX, temporary.root).also { successFile = it } },
            successTransfer,
            successClip,
            dispatcher,
        ).execute()
        assertArrayEquals(byteArrayOf(1, 2, 3), successClip.observed)
        assertFalse(successFile!!.exists())
        assertEquals(1, successTransfer.closeCalls)
        assertEquals(1, successClip.closeCalls)

        var failedFile: File? = null
        val failedTransfer = FakeTransfer(error = IOException("download failed"))
        try {
            DownloadedAudioRun(
                "http://example/audio",
                { File.createTempFile(AudioPlayer.TEMP_PREFIX, AudioPlayer.TEMP_SUFFIX, temporary.root).also { failedFile = it } },
                failedTransfer,
                FakeClip(),
                dispatcher,
            ).execute()
            fail("download failure must propagate to the coordinator")
        } catch (_: IOException) {
        }
        assertFalse(failedFile!!.exists())
        assertEquals(1, failedTransfer.closeCalls)

        var playerFile: File? = null
        val playerTransfer = FakeTransfer()
        val failedClip = FakeClip(error = IOException("decoder failed"))
        try {
            DownloadedAudioRun(
                "http://example/audio",
                { File.createTempFile(AudioPlayer.TEMP_PREFIX, AudioPlayer.TEMP_SUFFIX, temporary.root).also { playerFile = it } },
                playerTransfer,
                failedClip,
                dispatcher,
            ).execute()
            fail("player failure must propagate to the coordinator")
        } catch (_: IOException) {
        }
        assertFalse(playerFile!!.exists())
        assertEquals(1, playerTransfer.closeCalls)
        assertEquals(1, failedClip.closeCalls)
    }

    @Test fun cancellationClosesBothResourcesAndDeletesTheOwnedFile() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val transfer = FakeTransfer()
        val clip = FakeClip(block = true)
        var file: File? = null
        val run = DownloadedAudioRun(
            "http://example/audio",
            { File.createTempFile(AudioPlayer.TEMP_PREFIX, AudioPlayer.TEMP_SUFFIX, temporary.root).also { file = it } },
            transfer,
            clip,
            dispatcher,
        )
        val job = launch(dispatcher) { run.execute() }
        runCurrent()
        assertTrue(clip.started.isCompleted)
        assertTrue(file!!.exists())

        run.cancel()
        job.cancelAndJoin()
        assertEquals(1, transfer.cancelCalls)
        assertEquals(1, clip.cancelCalls)
        assertEquals(1, transfer.closeCalls)
        assertEquals(1, clip.closeCalls)
        assertFalse(file!!.exists())
    }

    @Test fun startupCleanupDeletesOnlyOwnedAudioFiles() {
        val stale = File(temporary.root, AudioPlayer.TEMP_PREFIX + "old" + AudioPlayer.TEMP_SUFFIX).apply { writeText("x") }
        val unrelated = File(temporary.root, "audio-other.media").apply { writeText("x") }

        assertEquals(1, AudioPlayer.cleanupStale(temporary.root))
        assertFalse(stale.exists())
        assertTrue(unrelated.exists())
    }

    @Test fun transferEnforcesDeclaredAndActualLimitsAndAlwaysDisconnects() {
        val exact = FakeConnection(byteArrayOf(1, 2, 3, 4), 4L)
        val exactFile = temporary.newFile("exact")
        HttpAudioTransfer(4L, openConnection = { exact }).download("http://example/audio", exactFile)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), exactFile.readBytes())
        assertTrue(exact.disconnected)

        val declared = FakeConnection(byteArrayOf(1), 5L)
        try {
            HttpAudioTransfer(4L, openConnection = { declared }).download("http://example/audio", temporary.newFile("declared"))
            fail("declared over-limit input must fail")
        } catch (error: ByteLimitExceeded) {
            assertEquals(4L, error.limit)
        }
        assertTrue(declared.disconnected)

        val undeclared = FakeConnection(byteArrayOf(1, 2, 3, 4, 5), -1L)
        try {
            HttpAudioTransfer(4L, openConnection = { undeclared }).download("http://example/audio", temporary.newFile("undeclared"))
            fail("actual over-limit input must fail")
        } catch (error: ByteLimitExceeded) {
            assertEquals(4L, error.limit)
        }
        assertTrue(undeclared.disconnected)
    }

    @Test fun transferLeavesPlatformHttpsVerificationUntouched() {
        val connection = FakeHttpsConnection(byteArrayOf(1, 2, 3), 3L)
        val platformSocketFactory = connection.sslSocketFactory
        val platformHostnameVerifier = connection.hostnameVerifier
        val destination = temporary.newFile("https")

        HttpAudioTransfer(4L, openConnection = { connection })
            .download("https://example/audio", destination)

        assertArrayEquals(byteArrayOf(1, 2, 3), destination.readBytes())
        assertSame(platformSocketFactory, connection.sslSocketFactory)
        assertSame(platformHostnameVerifier, connection.hostnameVerifier)
        assertTrue(connection.disconnected)
    }

    @Test fun transferStillDownloadsOverPlainHttp() {
        val connection = FakeConnection(byteArrayOf(4, 5, 6), 3L)
        val destination = temporary.newFile("http")

        HttpAudioTransfer(4L, openConnection = { connection })
            .download("http://example/audio", destination)

        assertArrayEquals(byteArrayOf(4, 5, 6), destination.readBytes())
        assertTrue(connection.disconnected)
    }

    @Test fun transferCancellationClosesABlockedStreamAndDisconnects() {
        val blocked = BlockingInputStream()
        val connection = FakeConnection(byteArrayOf(), -1L, blocked)
        val transfer = HttpAudioTransfer(4L, openConnection = { connection })
        val failure = AtomicReference<Throwable?>()
        val worker = thread(start = true, name = "audio-transfer-test") {
            try {
                transfer.download("http://example/audio", temporary.newFile("blocked"))
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        assertTrue(blocked.readStarted.await(1, TimeUnit.SECONDS))

        transfer.cancel()
        worker.join(1_000L)
        assertFalse(worker.isAlive)
        assertTrue(blocked.closed)
        assertTrue(connection.disconnected)
        assertNotNull(failure.get())
    }

    @Test fun transferEnforcesAWholeDownloadDeadlineDespiteContinuedProgress() {
        val clockCalls = AtomicInteger()
        val clock = { if (clockCalls.getAndIncrement() < 2) 0L else 11_000_000L }
        val connection = FakeConnection(byteArrayOf(), -1L, OneByteInputStream(4))
        try {
            HttpAudioTransfer(8L, { connection }, downloadTimeoutMs = 10L, nanoTime = clock)
                .download("http://example/audio", temporary.newFile("deadline"))
            fail("continued progress must not bypass the whole-download deadline")
        } catch (_: AudioDownloadDeadlineExceeded) {
        }
        assertTrue(connection.disconnected)
    }

    @Test fun transferRejectsNonHttpSchemesBeforeOpeningAConnection() {
        var opened = false
        try {
            HttpAudioTransfer(4L, openConnection = {
                opened = true
                FakeConnection(byteArrayOf(), 0L)
            }).download("file:///tmp/audio", temporary.newFile("scheme"))
            fail("non-HTTP audio URL must fail")
        } catch (_: IllegalArgumentException) {
        }
        assertFalse(opened)
    }

    private class FakeConnection(
        private val bytes: ByteArray,
        private val declaredLength: Long,
        private val suppliedInput: InputStream = ByteArrayInputStream(bytes),
    ) : HttpURLConnection(URL("http://example/audio")) {
        var disconnected = false
        override fun connect() {}
        override fun usingProxy() = false
        override fun disconnect() { disconnected = true }
        override fun getContentLengthLong() = declaredLength
        override fun getInputStream(): InputStream = suppliedInput
    }

    private class FakeHttpsConnection(
        private val bytes: ByteArray,
        private val declaredLength: Long,
    ) : HttpsURLConnection(URL("https://example/audio")) {
        var disconnected = false
        override fun connect() {}
        override fun usingProxy() = false
        override fun disconnect() { disconnected = true }
        override fun getContentLengthLong() = declaredLength
        override fun getInputStream(): InputStream = ByteArrayInputStream(bytes)
        override fun getCipherSuite() = "TLS_FAKE_WITH_FAKE_FAKE"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
    }

    private class BlockingInputStream : InputStream() {
        val readStarted = CountDownLatch(1)
        private val released = CountDownLatch(1)
        @Volatile var closed = false

        override fun read(): Int {
            readStarted.countDown()
            released.await()
            throw IOException("closed")
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = read()

        override fun close() {
            closed = true
            released.countDown()
        }
    }

    private class OneByteInputStream(private var remaining: Int) : InputStream() {
        override fun read(): Int = if (remaining-- > 0) 1 else -1

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val value = read()
            if (value < 0) return -1
            buffer[offset] = value.toByte()
            return 1
        }
    }
}
