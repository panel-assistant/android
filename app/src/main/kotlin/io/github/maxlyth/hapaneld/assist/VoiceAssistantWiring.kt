package io.github.maxlyth.hapaneld.assist

import android.content.Context
import android.media.MediaRecorder
import io.github.maxlyth.hapaneld.Config
import io.github.maxlyth.hapaneld.audio.AndroidMicrophoneSource
import io.github.maxlyth.hapaneld.audio.MicState
import io.github.maxlyth.hapaneld.audio.MicrophoneAdmission
import io.github.maxlyth.hapaneld.audio.MicrophoneSource
import io.github.maxlyth.hapaneld.media.AudioPlaybackCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay

/** The Home Assistant pipeline catalogue, read through one short-lived authenticated socket per call. */
internal class HaAssistPipelineDirectory(private val config: Config) : AssistPipelineDirectory {
    override suspend fun list(): AssistPipelineDirectory.Result {
        if (!config.builtInRendererReady()) {
            return AssistPipelineDirectory.Result.NotConfigured("Home Assistant is not configured")
        }
        return when (val result = AssistPipelineClient(config).listPipelines()) {
            is AssistCatalogResult.Catalog -> AssistPipelineDirectory.Result.Available(
                pipelines = result.catalog.pipelines.map { AssistPipelineDirectory.Pipeline(it.id, it.name) },
                preferred = result.catalog.preferredId.orEmpty(),
            )
            is AssistCatalogResult.Failed -> when (result.error.code) {
                AssistPipelineClient.CODE_NOT_CONFIGURED,
                AssistPipelineClient.CODE_CREDENTIALS_UNAVAILABLE,
                -> AssistPipelineDirectory.Result.NotConfigured(result.error.message)
                else -> AssistPipelineDirectory.Result.Unavailable(result.error.message)
            }
        }
    }
}

/**
 * Plays a reply through the panel's single announcement coordinator and returns only once that
 * playback has actually finished.
 *
 * The coordinator keeps only the newest announcement, so a reply can be replaced before it is heard.
 * That is an ordinary outcome on a panel that also speaks for other reasons, and it is reported as
 * one: this waits for the exact generation it submitted and raises the typed playback fault when
 * that generation ends any way other than completing. Returning normally is the run's proof that the
 * panel actually spoke, so it must never be the answer to "the queue accepted it".
 */
internal class AnnouncementLanePlayback(
    private val audio: AudioPlaybackCoordinator,
    private val pollMs: Long = POLL_MS,
    /** Called once the reply has been accepted for playback, so the panel can report that it is speaking. */
    private val onStarted: () -> Unit = {},
    /** Reports the exact accepted generation to callers that may later cancel only their own audio. */
    private val onGeneration: (Long) -> Unit = {},
) : AssistPlayback {
    override suspend fun play(url: String) {
        // The generation comes back from the submission itself. Reading the snapshot afterwards can
        // return a later announcement's generation, which would leave this run watching, and
        // reporting on, playback that is not its own.
        val generation = audio.submitForGeneration(url, speech = true)
            ?: throw AssistPlaybackException(
                AssistPipelineClient.CODE_PLAYBACK_FAILED,
                "The announcement coordinator is no longer accepting playback",
            )
        onGeneration(generation)
        onStarted()
        while (true) {
            val now = audio.snapshot()
            if (now.generation != generation) {
                throw AssistPlaybackException(
                    AssistPipelineClient.CODE_PLAYBACK_SUPERSEDED,
                    "A later announcement replaced the reply before it finished",
                )
            }
            when (now.state) {
                AudioPlaybackCoordinator.State.QUEUED,
                AudioPlaybackCoordinator.State.ACTIVE,
                -> delay(pollMs)
                AudioPlaybackCoordinator.State.IDLE -> return
                AudioPlaybackCoordinator.State.FAILED -> throw AssistPlaybackException(
                    AssistPipelineClient.CODE_PLAYBACK_FAILED,
                    now.error ?: "The reply failed to play",
                )
                AudioPlaybackCoordinator.State.CLOSED -> throw AssistPlaybackException(
                    AssistPipelineClient.CODE_PLAYBACK_FAILED,
                    "The announcement coordinator closed while the reply was playing",
                )
            }
        }
    }

    private companion object {
        const val POLL_MS = 100L
    }
}

/**
 * Builds the panel's one microphone source on first use and rebuilds it only when the configured
 * audio source changes while nothing holds a lease, so a setting change never takes capture away
 * from a live consumer.
 */
internal class ConfiguredMicrophoneSource(
    private val context: Context,
    private val config: Config,
) {
    private var current: AndroidMicrophoneSource? = null
    private var currentSource: Int = -1
    private var closed = false

    @Synchronized
    fun get(): MicrophoneSource {
        check(!closed) { "microphone source is shut down" }
        val wanted = audioSourceFor(config.voiceAudioSource)
        val existing = current
        if (existing != null && (currentSource == wanted || existing.state.value !is MicState.Closed)) return existing
        existing?.close()
        val built = AndroidMicrophoneSource(context, wanted)
        current = built
        currentSource = wanted
        MicrophoneAdmission.observe(built)
        return built
    }

    @Synchronized
    fun shutdown(timeoutMs: Long): Boolean {
        closed = true
        return current?.shutdown(timeoutMs) ?: true
    }

    companion object {
        fun audioSourceFor(setting: String?): Int = when (setting?.trim()?.lowercase()) {
            "mic" -> MediaRecorder.AudioSource.MIC
            "voice_communication" -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
            else -> MediaRecorder.AudioSource.VOICE_RECOGNITION
        }
    }
}

/** Assemble the coordinator for the running service. */
internal fun voiceAssistantCoordinator(
    context: Context,
    config: Config,
    scope: CoroutineScope,
    audio: AudioPlaybackCoordinator,
    microphoneAvailable: () -> Boolean,
    foregroundMicrophone: (Boolean) -> Boolean,
    state: VoiceStateAuthority,
    engineFactory: WakeWordEngineFactory,
    runner: AssistRunner,
    source: () -> MicrophoneSource,
): VoiceAssistantCoordinator {
    return VoiceAssistantCoordinator(
        scope = scope,
        settings = {
            VoiceSettings.parse(
                config.voiceEnabled,
                config.voiceWakeWords,
                config.voicePipelines,
                config.voiceMicGainDb,
            )
        },
        microphoneAvailable = microphoneAvailable,
        source = source,
        engineFactory = engineFactory,
        runnerFactory = { runner },
        playback = AnnouncementLanePlayback(audio, onStarted = { state.set(VoiceState.RESPONDING) }),
        foregroundMicrophone = foregroundMicrophone,
        state = state,
        attention = VoiceAttention::cue,
    ).also { VoiceAttention.prepare(context) }
}
