package io.github.maxlyth.hapaneld.media

import org.json.JSONObject
import java.net.URI

/** One `media` channel command (protocol section 18). */
internal sealed interface PanelMediaCommand {
    data class Play(val url: String, val announce: Boolean) : PanelMediaCommand
    data object Pause : PanelMediaCommand
    data object Resume : PanelMediaCommand
    data object Stop : PanelMediaCommand
    data class Mute(val muted: Boolean) : PanelMediaCommand

    companion object {
        private val CONTROL = Regex("[\\x00-\\x20\\x7f]")
        private const val MAX_URL_CHARS = 16 * 1024

        /** The command [value] carries, or null when it is malformed. */
        fun parse(value: Any?): PanelMediaCommand? {
            val json = value as? JSONObject ?: return null
            return when (json.opt("action")) {
                "play" -> {
                    val url = (json.opt("url") as? String)?.takeIf(::absoluteHttp) ?: return null
                    val announce = when (val raw = json.opt("announce")) {
                        null -> false
                        is Boolean -> raw
                        else -> return null
                    }
                    Play(url, announce)
                }
                "pause" -> Pause
                "resume" -> Resume
                "stop" -> Stop
                "mute" -> Mute(json.opt("muted") as? Boolean ?: return null)
                else -> null
            }
        }

        private fun absoluteHttp(url: String): Boolean {
            if (url.length > MAX_URL_CHARS || CONTROL.containsMatchIn(url)) return false
            val uri = runCatching { URI(url) }.getOrNull() ?: return false
            val scheme = uri.scheme?.lowercase() ?: return false
            return (scheme == "http" || scheme == "https") && !uri.host.isNullOrEmpty()
        }
    }
}

/** One prepared-asynchronously stream; every call happens on the player's owner thread. */
internal interface MediaStream {
    fun start()
    fun pause()
    fun release()

    /** Milliseconds, or zero or less when the source has no known end (a live stream). */
    val durationMs: Int
}

/** Opens [url] and starts preparing it; [onPrepared] and [onEnded] (completion or error) arrive on the owner thread. */
internal fun interface MediaStreamFactory {
    fun open(url: String, onPrepared: () -> Unit, onEnded: () -> Unit): MediaStream
}

/**
 * The panel's general-purpose media player: streams a URL directly (an endless stream included), and
 * steps aside for the announcement lane and a voice turn. While either holds, playing media is paused
 * and resumed afterwards; a stream without a known end is re-opened from its URL so it resumes at the
 * live point. Announcements themselves go to the existing lane.
 *
 * Every state change runs through [post] on one owner thread (Android's main looper in production, the
 * thread `MediaPlayer` delivers its callbacks on). [observation] may be read from any thread. The URL
 * is never logged: Home Assistant media URLs carry signed credentials.
 */
internal class PanelMediaPlayer(
    private val streams: MediaStreamFactory,
    private val post: (() -> Unit) -> Unit,
    private val announce: (String) -> Boolean,
    private val cancelAnnouncement: () -> Unit,
    private val muted: () -> Boolean,
    private val setMuted: (Boolean) -> Unit,
    private val log: (String) -> Unit = {},
) {
    enum class Hold { ANNOUNCEMENT, VOICE }

    private enum class Phase { IDLE, BUFFERING, PLAYING, PAUSED }

    // Owner-thread state.
    private var url: String? = null
    private var stream: MediaStream? = null
    private var opened = 0L
    private var prepared = false
    private var userPaused = false
    private val holds = mutableSetOf<Hold>()

    // Read by the convergence pump.
    @Volatile private var phase = Phase.IDLE
    @Volatile private var laneBusy = false
    @Volatile private var listener: () -> Unit = {}

    /** Called after every change the `media` observation can show; must return promptly. */
    fun setChangeListener(onChange: () -> Unit) {
        listener = onChange
    }

    /** `{state, muted}` as section 18 defines it. */
    fun observation(): String {
        val state = when {
            laneBusy -> "playing"
            else -> phase.name.lowercase()
        }
        return JSONObject().put("state", state).put("muted", runCatching(muted).getOrDefault(false)).toString()
    }

    /** Run [command]. False only when an announcement could not be admitted. */
    fun command(command: PanelMediaCommand): Boolean {
        when (command) {
            is PanelMediaCommand.Play -> if (command.announce) return announce(command.url) else owner { play(command.url) }
            PanelMediaCommand.Pause -> owner { pause() }
            PanelMediaCommand.Resume -> owner { resume() }
            PanelMediaCommand.Stop -> {
                owner { end() }
                cancelAnnouncement()
            }
            is PanelMediaCommand.Mute -> {
                setMuted(command.muted)
                listener()
            }
        }
        return true
    }

    /** [source] started or stopped needing the speaker. */
    fun hold(source: Hold, on: Boolean) = owner {
        if (source == Hold.ANNOUNCEMENT) laneBusy = on
        val wasHeld = holds.isNotEmpty()
        if (on) holds += source else holds -= source
        val held = holds.isNotEmpty()
        if (held && !wasHeld && phase == Phase.PLAYING) {
            stream?.pause()
            phase = Phase.PAUSED
        } else if (!held && wasHeld && url != null && !userPaused) {
            proceed()
        }
    }

    private fun owner(block: () -> Unit) = post {
        block()
        listener()
    }

    private fun play(target: String) {
        url = target
        userPaused = false
        open(target)
    }

    private fun pause() {
        if (url == null) return
        userPaused = true
        if (phase == Phase.PLAYING) stream?.pause()
        phase = Phase.PAUSED
    }

    private fun resume() {
        if (url == null || !userPaused) return
        userPaused = false
        if (holds.isEmpty()) proceed()
    }

    /** Continue the current media: start it, or re-open a live stream so it resumes at the live point. */
    private fun proceed() {
        val target = url ?: return
        when {
            !prepared -> phase = Phase.BUFFERING
            (stream?.durationMs ?: 0) <= 0 -> open(target)
            else -> {
                stream?.start()
                phase = Phase.PLAYING
            }
        }
    }

    private fun open(target: String) {
        release()
        val generation = ++opened
        phase = Phase.BUFFERING
        stream = try {
            streams.open(
                target,
                onPrepared = { owner { if (generation == opened) prepared() } },
                onEnded = { owner { if (generation == opened) end() } },
            )
        } catch (error: Exception) {
            // The class only: a platform message can quote the data source.
            log("media stream did not open: ${error.javaClass.simpleName}")
            url = null
            phase = Phase.IDLE
            null
        }
    }

    private fun prepared() {
        prepared = true
        if (userPaused || holds.isNotEmpty()) {
            phase = Phase.PAUSED
            return
        }
        stream?.start()
        phase = Phase.PLAYING
    }

    private fun end() {
        release()
        url = null
        userPaused = false
        phase = Phase.IDLE
    }

    private fun release() {
        opened++
        prepared = false
        stream?.let { runCatching { it.release() } }
        stream = null
    }
}
