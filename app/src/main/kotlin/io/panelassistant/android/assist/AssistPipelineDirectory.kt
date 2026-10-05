package io.panelassistant.android.assist

/**
 * Home Assistant Assist pipeline catalogue, as seen by the voice-assistant settings surface.
 *
 * The real implementation resolves pipelines from Home Assistant's `assist_pipeline` WebSocket API and
 * is owned by the voice-coordinator lane. This interface is the seam that lets the settings/HTTP surface
 * ship ahead of that work — `GET /api/v1/voice/pipelines` calls [list] through whatever is injected, and
 * a test can substitute a fixed catalogue without touching HTTP plumbing.
 */
interface AssistPipelineDirectory {
    data class Pipeline(val id: String, val name: String)

    sealed interface Result {
        /** The catalogue is known; [preferred] is Home Assistant's default pipeline id. */
        data class Available(val pipelines: List<Pipeline>, val preferred: String) : Result

        /** No Home Assistant connection/credentials exist yet to resolve pipelines from. */
        data class NotConfigured(val reason: String) : Result

        /** Configured, but the pipeline catalogue could not be fetched right now (transport/API error). */
        data class Unavailable(val reason: String) : Result
    }

    suspend fun list(): Result

    companion object {
        /** Default until the real Home-Assistant-backed directory is wired in. */
        val NOT_WIRED: AssistPipelineDirectory = object : AssistPipelineDirectory {
            override suspend fun list(): Result = Result.NotConfigured("voice pipeline directory not wired")
        }
    }
}
