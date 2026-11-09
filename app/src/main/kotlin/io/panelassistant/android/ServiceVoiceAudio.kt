package io.panelassistant.android

import android.content.Context
import android.util.Log
import io.panelassistant.android.device.DeviceProfile
import io.panelassistant.android.media.AudioPlaybackCoordinator
import io.panelassistant.android.util.MonotonicDeadline
import io.panelassistant.android.util.ServiceRuntimeOwner
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The service's voice, microphone, audio playback and media player: the Panel Assistant voice channel,
 * the voice-assistant coordinator, the one shared microphone and its foreground claims and mute watch,
 * the announcement lane and the media player Home Assistant drives.
 *
 * The service calls each hook at the point in its own build, start and teardown sequence where this
 * code ran before it moved here. [config], [profile], [standingDown], [silentBootChime], [runtime],
 * [volume] and [mainHandler] are read when used, never captured: the service creates this owner
 * before any of them exists.
 */
internal class ServiceVoiceAudio(
    private val service: Context,
    private val scope: CoroutineScope,
    private val teardownBoundary: ServiceTeardownBoundary,
    private val notifications: ServiceNotifications,
    private val config: () -> Config,
    private val profile: () -> DeviceProfile,
    private val standingDown: () -> Boolean,
    private val silentBootChime: () -> Boolean,
    private val runtime: () -> ServiceRuntimeOwner<PaneldService.NetworkRuntime>,
    private val volume: () -> io.panelassistant.android.control.VolumeController,
    private val mainHandler: () -> android.os.Handler,
    private val reconfigure: (Set<String>) -> Unit,
) {
    // Voice-assistant phase authority, driven by the coordinator. Home Assistant sees the phase as its
    // satellite entity's state; on the panel it drives the listening indicator.
    private val voiceStateAuthority = io.panelassistant.android.assist.VoiceStateAuthority()
    // The panel's side of its Assist satellite, carried on the Panel Assistant session.
    lateinit var panelAssistantVoice: io.panelassistant.android.panelassistant.PanelAssistantVoice
        private set
    val wakeWordCatalog by lazy { io.panelassistant.android.assist.wakeword.WakeWordCatalog(service) }
    lateinit var audio: AudioPlaybackCoordinator
        private set
    /** Panel Assistant's synchronised voice stream, offered whenever the library loads. */
    val voiceStream by lazy { io.panelassistant.android.panelassistant.PanelAssistantVoiceStream(io.panelassistant.android.media.VoiceStreamPlayer.forService(service, BuildConfig.VERSION_NAME), log = { Log.i(TAG, it) }) }
    lateinit var media: io.panelassistant.android.media.PanelMediaPlayer
        private set
    lateinit var voice: io.panelassistant.android.assist.VoiceAssistantCoordinator
        private set
    lateinit var sharedMicrophone: io.panelassistant.android.assist.ConfiguredMicrophoneSource
        private set
    val microphoneClaims = io.panelassistant.android.audio.MicrophoneForegroundClaims(::updateMicrophoneForeground)
    val microphoneMute = io.panelassistant.android.audio.MicrophoneMuteWatch(
        service, { microphonePresence().offered },
        io.panelassistant.android.assist.publishMicrophoneMute { voice }, portalPrivacy = { profile().id == "meta-portal-10-gen2" })
    // One coalesced restart per burst of voice_* changes: a bundle import writes every key in turn and
    // must not rearm the listener once per key.
    @Volatile private var voiceRestart: kotlinx.coroutines.Job? = null
    private val voicePrefsListener = serviceConfigurationListener(reconfigure) {
        voiceRestart?.cancel()
        voiceRestart = scope.launch {
            kotlinx.coroutines.delay(VOICE_SETTINGS_COALESCE_MS)
            voice.start()
            panelAssistantVoice.configurationChanged()
        }
    }
    /**
     * What this panel tells Home Assistant it hears, or null when it has no microphone to be a satellite
     * with. Read from the panel's own settings and model catalogue, which stay the one store.
     */
    private fun voiceConfiguration(): io.panelassistant.android.panelassistant.PanelAssistantVoiceConfiguration? =
        io.panelassistant.android.panelassistant.PanelAssistantVoiceConfiguration.of(
            if (::voice.isInitialized) voice.microphoneStatus()
            else io.panelassistant.android.audio.MicrophoneStatus(microphonePresence()),
            io.panelassistant.android.assist.VoiceSettings.parse(config().voiceEnabled, config().voiceWakeWords, config().voicePipelines),
            wakeWordCatalog.available(),
            wakeWordCatalog::importedSha256,
        )
    /** The shared microphone's capture route for status surfaces; reads the built source only, never opens one. */
    fun captureRoute(): io.panelassistant.android.audio.CaptureRouteReport? =
        if (::sharedMicrophone.isInitialized) sharedMicrophone.captureRoute() else null

    /** The one place the profile's microphone declaration meets Android's report. */
    fun microphonePresence(of: DeviceProfile = profile()) =
        io.panelassistant.android.audio.MicrophonePresence.of(of.microphoneDeclared, service)

    /**
     * Add or drop the microphone foreground-service type around a microphone lease.
     *
     * Call it on the main thread with `true` as the first lease opens and `false` as the last one is
     * released; it is idempotent and inert until something actually calls it. Returns whether the
     * type is now what was asked for, so a caller that needs the microphone can refuse to start
     * capture it is not allowed to run. Before Android 11 there is no microphone type to claim and
     * no while-in-use restriction to satisfy, so it succeeds without claiming anything.
     *
     * **Android 14 will refuse a microphone foreground service that is started from the background.**
     * A `while-in-use` type may only be claimed from a state that allows it — an activity in the
     * foreground, or one of the documented exemptions — and a claim made from the background throws
     * `ForegroundServiceStartNotAllowedException` (or, on some builds, silently omits the type).
     * That is a policy question about *when* capture may begin, and it belongs to whatever owns the
     * leases, not here: this method reports the failure and leaves the service running with the
     * types it already had.
     */
    internal fun setMicrophoneForegroundActive(active: Boolean): Boolean = microphoneClaims.set("voice", active)

    private fun updateMicrophoneForeground(active: Boolean): Boolean {
        if (standingDown() || teardownBoundary.isStopping) return false
        return notifications.updateMicrophoneForeground(
            active,
            silent = silentBootChime(),
        )
    }

    /** The voice channel Panel Assistant carries; built before the Home Assistant links that carry it. */
    fun buildAnnouncements() {
        panelAssistantVoice = io.panelassistant.android.panelassistant.PanelAssistantVoice(
            scope = scope,
            configuration = ::voiceConfiguration,
            onAnnouncement = { announcement ->
                val done = { stopped: Boolean -> panelAssistantVoice.played(announcement.announceId, stopped) }
                if (::voice.isInitialized) {
                    voice.announce(
                        io.panelassistant.android.assist.VoiceAnnouncement(
                            announcement.url, announcement.preannounceUrl, announcement.listenAfter,
                            stream = announcement.stream, text = announcement.message, done = done,
                        ),
                    )
                } else {
                    done(false)
                }
            },
            log = { message -> Log.i(TAG, message) },
            onColors = { colors -> io.panelassistant.android.assist.VoiceAttention.colors = colors },
        )
    }

    /** The one microphone source voice and the camera stream share. */
    fun buildSharedMicrophone() {
        sharedMicrophone = io.panelassistant.android.assist.ConfiguredMicrophoneSource(
            route = {
                io.panelassistant.android.assist.ConfiguredMicrophoneSource.routeFor(config(), profile().microphoneCapture)
            },
            buildRoute = { route ->
                io.panelassistant.android.audio.AndroidMicrophoneSource(service.applicationContext, route)
                    .also(io.panelassistant.android.audio.MicrophoneAdmission::observe)
            },
            scope = scope,
            onFree = { if (::voice.isInitialized && !teardownBoundary.isStopping) voice.start() },
        )
    }

    /** The media player, the announcement lane and the voice-assistant coordinator. */
    fun buildPlayback() {
        // Home Assistant's media player. Announcements (speech, with the silent tail that keeps the last
        // syllable) go to the announcement lane below; other media streams here.
        media = io.panelassistant.android.media.PanelMediaPlayer(
            streams = { url, onPrepared, onEnded -> AndroidMediaStream(url, onPrepared, onEnded) },
            post = { block -> mainHandler().post(block) },
            announce = { url, stream ->
                if (stream != null && ::voice.isInitialized) voice.announceStreamedMedia(url, stream) else (if (stream != null) audio.submitStreamForGeneration(stream) else audio.submitForGeneration(url, speech = true)) != null
            },
            cancelAnnouncement = { audio.snapshot().let { audio.cancelGeneration(it.generation) } },
            ownerStopped = { if (::voice.isInitialized) voice.ownerStopped() },
            muted = volume()::isMuted,
            setMuted = volume()::setMuted,
            log = { Log.w(TAG, it) },
        )
        media.setChangeListener { runCatching { runtime().current().core.mediaChanged() } }
        audio = AudioPlaybackCoordinator(
            AudioPlayer.factory(service.cacheDir, voiceStream),
            onFailure = { error -> Log.w(TAG, "audio playback failed: ${error.javaClass.simpleName}") },
            onBusyChanged = { busy -> media.hold(io.panelassistant.android.media.PanelMediaPlayer.Hold.ANNOUNCEMENT, busy) },
        )
        // Arms when the setting is on and the panel has a microphone; an unproven one (Android's report
        // alone, which some boards make for silent hardware) is checked by the coordinator first.
        voice = io.panelassistant.android.assist.voiceAssistantCoordinator(
            context = service,
            config = config(),
            scope = scope,
            audio = audio,
            microphone = { microphonePresence() },
            onMicrophoneStatus = { panelAssistantVoice.configurationChanged() },
            muted = { microphoneMute.mute.muted },
            source = { sharedMicrophone.get() },
            foregroundMicrophone = ::setMicrophoneForegroundActive,
            state = voiceStateAuthority,
            engineFactory = io.panelassistant.android.assist.MicroWakeWordEngineFactory(
                wakeWordCatalog::load,
                log = { Log.i(TAG, it) },
                sensitivity = { config().voiceSensitivity },
            ),
            voice = panelAssistantVoice,
            log = { Log.i(TAG, it) },
        )
        voiceStateAuthority.setChangeListener {
            val state = voiceStateAuthority.current()
            io.panelassistant.android.assist.VoiceAttention.phase(state)
            media.hold(io.panelassistant.android.media.PanelMediaPlayer.Hold.VOICE, state.inTurn)
        }
        microphoneMute.start(scope)
    }

    /** Listen for voice settings and start the assistant; called on the runtime lane at startup. */
    fun start() {
        config().registerChangeListener(voicePrefsListener)
        voiceForegroundRetry = { voice.retryStart() }
        scope.launch { if (!teardownBoundary.isStopping) voice.start() }
    }

    /** Re-arm the assistant after the wake-word models change. */
    fun restartVoice() {
        voice.start()
        panelAssistantVoice.configurationChanged()
    }

    /** Stop admitting playback and stop the media player; ordinary teardown and a process boundary alike. */
    fun closeAdmission() {
        if (::audio.isInitialized) beginAudioTeardown(audio::closeAdmission, audio::cancelCurrent)
        if (::media.isInitialized) media.halt()
    }

    /** Drain audio playback on the runtime lane; [drained] is what the finalizer later reads. */
    fun closeAudio(deadline: MonotonicDeadline, drained: AtomicBoolean) {
        val audioWaitMs = minOf(deadline.remainingMs(), AUDIO_SHUTDOWN_MS)
        val audioStopped = runCatching {
            audioWaitMs > 0L && kotlinx.coroutines.runBlocking { audio.close(audioWaitMs) }
        }.getOrDefault(false)
        drained.set(audioStopped)
        if (!audioStopped) {
            Log.w(TAG, "audio cleanup exceeded ${AUDIO_SHUTDOWN_MS}ms")
        }
    }

    fun closeVoice(closeOwnerResult: (String, () -> Boolean) -> Unit, deadline: MonotonicDeadline) {
        if (::voice.isInitialized) {
            voiceForegroundRetry = null
            config().unregisterChangeListener(voicePrefsListener)
            closeOwnerResult("voice assistant") { voice.shutdown(deadline.remainingMs()) }
        }
    }

    fun closeSharedMicrophone(closeOwnerResult: (String, () -> Boolean) -> Unit, deadline: MonotonicDeadline) {
        if (::sharedMicrophone.isInitialized) closeOwnerResult("shared microphone") {
            sharedMicrophone.shutdown(deadline.remainingMs())
        }
    }

    companion object {
        private const val TAG = PaneldService.TAG
        private const val VOICE_SETTINGS_COALESCE_MS = 300L

        // The running service, if any, for the activity's foreground signal. Android refuses a
        // microphone foreground service started from the background, so a claim the platform refused
        // is retried the moment the panel's own activity comes forward, rather than only on the
        // coordinator's slower timer.
        @Volatile private var voiceForegroundRetry: (() -> Unit)? = null

        /** Called by the dashboard when it becomes visible. Inert when no service or no coordinator. */
        fun notifyActivityForeground() {
            voiceForegroundRetry?.invoke()
        }

        private const val AUDIO_SHUTDOWN_MS = 2_000L
    }
}

/** The service's existing configuration subscription also resumes ownership after first setup. */
internal fun serviceConfigurationListener(
    reconfigure: (Set<String>) -> Unit,
    voiceSettingsChanged: () -> Unit,
): android.content.SharedPreferences.OnSharedPreferenceChangeListener =
    android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == HaConnectionRoutes.EPOCH_KEY || key == SETUP_EVER_COMPLETED_PREF) reconfigure(setOf(key))
        if (key != null && key.startsWith("voice_")) voiceSettingsChanged()
    }
