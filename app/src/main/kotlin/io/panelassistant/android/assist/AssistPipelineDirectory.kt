package io.panelassistant.android.assist

/**
 * Home Assistant Assist pipeline catalogue, as seen by the voice-assistant settings surface.
 * `GET /api/v1/voice/pipelines` calls [list]; [HaAssistPipelineDirectory] resolves it from Home
 * Assistant, and a test substitutes a fixed catalogue.
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
}
