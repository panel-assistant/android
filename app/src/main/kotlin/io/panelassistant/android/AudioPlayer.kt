package io.panelassistant.android

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import io.panelassistant.android.media.AudioPlaybackRun
import io.panelassistant.android.media.AudioPlaybackRunFactory
import io.panelassistant.android.util.BoundedStreams
import io.panelassistant.android.util.closeBody
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Builds one resource-owned URL download and speaker playback run per accepted announcement. */
object AudioPlayer {
    internal const val MAX_AUDIO_BYTES = 32L * 1024L * 1024L
    internal const val TEMP_PREFIX = "ha-paneld-audio-"
    internal const val TEMP_SUFFIX = ".media"

    internal fun factory(cacheDir: File, streams: io.panelassistant.android.media.VoiceStreamSource? = null): AudioPlaybackRunFactory {
        cleanupStale(cacheDir)
        return object : AudioPlaybackRunFactory {
            override fun create(url: String) = createRun(url, false)
            override fun createSpeech(url: String) = createRun(url, true)
            override fun createStream(cue: io.panelassistant.android.media.StreamCue, fallbackUrls: List<String>): AudioPlaybackRun =
                io.panelassistant.android.media.StreamedSpeechRun(
                    streams ?: throw UnsupportedOperationException("this panel has no voice stream"),
                    cue,
                    fallbackUrls,
                    ::createSpeech,
                )
            private fun createRun(url: String, speech: Boolean) = DownloadedAudioRun(
                url = url,
                createTemp = { File.createTempFile(TEMP_PREFIX, TEMP_SUFFIX, cacheDir) },
                transfer = HttpAudioTransfer(MAX_AUDIO_BYTES),
                clip = AndroidAudioClip(),
                preparation = if (speech) AndroidPcmAudioPreparation() else null,
            )
        }
    }

    internal fun cleanupStale(cacheDir: File): Int {
        val stale = cacheDir.listFiles { file ->
            file.isFile && file.name.startsWith(TEMP_PREFIX) && file.name.endsWith(TEMP_SUFFIX)
        }.orEmpty()
        return stale.count { it.delete() }
    }
}

internal interface AudioTransfer : AutoCloseable {
    fun download(url: String, destination: File)
    fun cancel()
}

internal interface AudioClip {
    suspend fun play(file: File)
    fun cancel()
    suspend fun close()
}

internal class DownloadedAudioRun(
    private val url: String,
    private val createTemp: () -> File,
    private val transfer: AudioTransfer,
    private val clip: AudioClip,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val preparation: AudioPlaybackPreparation? = null,
) : AudioPlaybackRun {
    override suspend fun execute() {
        val file = withContext(dispatcher) { createTemp() }
        var prepared: File? = null
        try {
            withContext(dispatcher) { transfer.download(url, file) }
            coroutineContext.ensureActive()
            if (preparation != null) {
                withContext(dispatcher) {
                    val destination = File.createTempFile(AudioPlayer.TEMP_PREFIX, AudioPlayer.TEMP_SUFFIX, file.parentFile)
                    prepared = destination
                    preparation.prepare(file, destination)
                }
            }
            coroutineContext.ensureActive()
            clip.play(prepared ?: file)
        } finally {
            withContext(NonCancellable + dispatcher) {
                runCatching { transfer.close() }
                runCatching { clip.close() }
                prepared?.delete()
                file.delete()
            }
        }
    }

    override fun cancel() {
        runCatching { transfer.cancel() }
        runCatching { preparation?.cancel() }
        runCatching { clip.cancel() }
    }
}

internal class HttpAudioTransfer(
    private val maxBytes: Long,
    private val openConnection: (URL) -> HttpURLConnection = { url -> url.openConnection() as HttpURLConnection },
    private val downloadTimeoutMs: Long = DOWNLOAD_TIMEOUT_MS,
    private val nanoTime: () -> Long = System::nanoTime,
) : AudioTransfer {
    private val cancelled = AtomicBoolean(false)
    private val connection = AtomicReference<HttpURLConnection?>()
    private val input = AtomicReference<InputStream?>()

    init {
        require(maxBytes in 0 until Long.MAX_VALUE)
        require(downloadTimeoutMs > 0L)
    }

    override fun download(url: String, destination: File) {
        val parsed = URL(url)
        require(parsed.protocol == "http" || parsed.protocol == "https") { "audio URL must use http or https" }
        if (cancelled.get()) throw CancellationException("audio transfer cancelled")
        val conn = openConnection(parsed)
        check(connection.compareAndSet(null, conn)) { "audio transfer already started" }
        try {
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.instanceFollowRedirects = true
            if (cancelled.get()) throw CancellationException("audio transfer cancelled")
            val declared = conn.contentLengthLong
            if (declared > maxBytes) {
                conn.closeBody()
                throw io.panelassistant.android.util.ByteLimitExceeded(maxBytes)
            }
            val stream = try {
                conn.inputStream
            } catch (refused: java.io.IOException) {
                conn.closeBody()
                throw refused
            }
            input.set(stream)
            if (cancelled.get()) throw CancellationException("audio transfer cancelled")
            val deadlineNanos = nanoTime() + downloadTimeoutMs * 1_000_000L
            DeadlineInputStream(stream, deadlineNanos, nanoTime).use { source ->
                destination.outputStream().use { output -> BoundedStreams.copy(source, output, maxBytes) }
            }
        } finally {
            runCatching { input.getAndSet(null)?.close() }
            connection.compareAndSet(conn, null)
            runCatching { conn.disconnect() }
        }
    }

    override fun cancel() {
        cancelled.set(true)
        runCatching { input.getAndSet(null)?.close() }
        runCatching { connection.getAndSet(null)?.disconnect() }
    }

    override fun close() = cancel()

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 15_000
        const val DOWNLOAD_TIMEOUT_MS = 120_000L
    }

    private class DeadlineInputStream(
        source: InputStream,
        private val deadlineNanos: Long,
        private val nanoTime: () -> Long,
    ) : FilterInputStream(source) {
        override fun read(): Int {
            checkDeadline()
            return super.read()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            checkDeadline()
            return super.read(buffer, offset, length)
        }

        private fun checkDeadline() {
            if (nanoTime() - deadlineNanos >= 0L) throw AudioDownloadDeadlineExceeded()
        }
    }
}

internal class AudioDownloadDeadlineExceeded : IOException("audio download deadline exceeded")

/** Keeps all MediaPlayer state transitions on Android's main looper and makes release awaitable. */
internal class AndroidAudioClip : AudioClip {
    private val cancelled = AtomicBoolean(false)
    private val player = AtomicReference<MediaPlayer?>()
    private val owner = Handler(Looper.getMainLooper())

    override suspend fun play(file: File) = withContext(Dispatchers.Main.immediate) {
        playOnOwner(file)
    }

    private suspend fun playOnOwner(file: File) = suspendCancellableCoroutine<Unit> { continuation ->
        val mediaPlayer = MediaPlayer()
        val finished = AtomicBoolean(false)

        fun releaseOwned() {
            if (player.compareAndSet(mediaPlayer, null)) runCatching { mediaPlayer.release() }
        }

        fun finish(error: Throwable? = null) {
            if (!finished.compareAndSet(false, true)) return
            releaseOwned()
            if (!continuation.isActive) return
            when {
                cancelled.get() -> continuation.cancel(CancellationException("audio playback cancelled"))
                error != null -> continuation.resumeWithException(error)
                else -> continuation.resume(Unit)
            }
        }

        if (!player.compareAndSet(null, mediaPlayer)) {
            runCatching { mediaPlayer.release() }
            throw IllegalStateException("audio player already active")
        }
        continuation.invokeOnCancellation {
            cancelled.set(true)
            owner.post { releaseOwned() }
        }
        try {
            mediaPlayer.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            mediaPlayer.setVolume(1f, 1f)
            mediaPlayer.setOnPreparedListener { prepared ->
                if (cancelled.get()) {
                    finish()
                } else {
                    runCatching { prepared.start() }.onFailure(::finish)
                }
            }
            // Prepared audio contains silent frames after speech, keeping the output stream alive
            // through the final syllable. Delaying release after EOS cannot keep that stream running.
            mediaPlayer.setOnCompletionListener { finish() }
            mediaPlayer.setOnErrorListener { _, what, extra ->
                finish(IOException("MediaPlayer error what=$what extra=$extra"))
                true
            }
            mediaPlayer.setDataSource(file.absolutePath)
            if (cancelled.get()) finish() else mediaPlayer.prepareAsync()
        } catch (error: Throwable) {
            finish(error)
        }
    }

    override fun cancel() {
        cancelled.set(true)
        if (Looper.myLooper() == owner.looper) releaseCurrent() else owner.post { releaseCurrent() }
    }

    override suspend fun close() {
        cancelled.set(true)
        if (player.get() == null) return
        withContext(Dispatchers.Main.immediate) { releaseCurrent() }
    }

    private fun releaseCurrent() {
        runCatching { player.getAndSet(null)?.release() }
    }

}

/**
 * One `MediaPlayer` streaming its data source directly, so an endless stream plays. Created on the main
 * looper, which is where `MediaPlayer` then delivers its callbacks. Errors log codes only: the data
 * source is a signed Home Assistant URL.
 */
internal class AndroidMediaStream(
    url: String,
    onPrepared: () -> Unit,
    onEnded: () -> Unit,
) : io.panelassistant.android.media.MediaStream {
    private val player = MediaPlayer()

    init {
        try {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            player.setOnPreparedListener { onPrepared() }
            player.setOnCompletionListener { onEnded() }
            player.setOnErrorListener { _, what, extra ->
                android.util.Log.w("ha-paneld/media", "media stream error what=$what extra=$extra")
                onEnded()
                true
            }
            player.setDataSource(url)
            player.prepareAsync()
        } catch (error: Throwable) {
            runCatching { player.release() }
            throw error
        }
    }

    override fun start() = player.start()
    override fun pause() = player.pause()
    override fun release() = player.release()
    override val durationMs: Int get() = runCatching { player.duration }.getOrDefault(-1)
}
