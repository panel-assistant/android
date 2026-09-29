package io.github.maxlyth.hapaneld.assist.wakeword

import org.json.JSONObject

/**
 * One bundled micro-wake-word model, described by the JSON manifest published beside each
 * `.tflite` in `esphome/micro-wake-word-models` (`models/v2/<id>.json`). Bundled copies live under
 * `assets/wakeword/`; see that directory's `LICENSE.txt` and `app/src/main/cpp/microwakeword/THIRD_PARTY.md`.
 */
data class MicroWakeWordModelConfig(
    /** Asset id: the JSON's basename, e.g. `okay_nabu`. */
    val id: String,
    /** Human-readable phrase, e.g. "Okay Nabu". */
    val wakeWord: String,
    val author: String,
    val website: String?,
    /** The `.tflite` file name inside `assets/wakeword/`. */
    val modelFile: String,
    val trainedLanguages: List<String>,
    val version: Int,
    /** Detection threshold on the sliding-window mean probability, 0..1. */
    val probabilityCutoff: Float,
    /** Feature step in milliseconds; one feature frame per step. */
    val featureStepSizeMs: Int,
    /** Number of inferences averaged before comparing against [probabilityCutoff]. */
    val slidingWindowSize: Int,
    /** Interpreter arena the trainer measured (an ESP32 figure; the native engine adds headroom). */
    val tensorArenaSize: Int,
) {
    companion object {
        const val ASSET_DIR = "wakeword"

        /** Parse a manifest. Throws [IllegalArgumentException] on a malformed or non-micro manifest. */
        fun parse(id: String, json: String): MicroWakeWordModelConfig {
            val root = try {
                JSONObject(json)
            } catch (e: org.json.JSONException) {
                throw IllegalArgumentException("wake word manifest $id is not valid JSON", e)
            }
            require(root.optString("type") == "micro") { "wake word manifest $id is not a micro model" }
            val micro = root.optJSONObject("micro")
                ?: throw IllegalArgumentException("wake word manifest $id has no micro section")
            val cutoff = micro.optDouble("probability_cutoff", Double.NaN)
            require(!cutoff.isNaN() && cutoff in 0.0..1.0) { "wake word manifest $id has an invalid probability_cutoff" }
            val step = micro.optInt("feature_step_size", 0)
            require(step > 0) { "wake word manifest $id has an invalid feature_step_size" }
            val window = micro.optInt("sliding_window_size", 0)
            require(window > 0) { "wake word manifest $id has an invalid sliding_window_size" }
            val model = root.optString("model")
            require(model.isNotEmpty() && !model.contains('/')) { "wake word manifest $id has an invalid model file" }
            val languages = root.optJSONArray("trained_languages")?.let { array ->
                List(array.length()) { array.getString(it) }
            } ?: emptyList()
            return MicroWakeWordModelConfig(
                id = id,
                wakeWord = root.optString("wake_word").ifEmpty { id },
                author = root.optString("author"),
                website = root.optString("website").ifEmpty { null },
                modelFile = model,
                trainedLanguages = languages,
                version = root.optInt("version", 0),
                probabilityCutoff = cutoff.toFloat(),
                featureStepSizeMs = step,
                slidingWindowSize = window,
                tensorArenaSize = micro.optInt("tensor_arena_size", 0),
            )
        }
    }
}
