package io.panelassistant.android.control

import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Gradle-driven maintainer tool: uses the production policy, rather than a visualization-only model.
 * The top-level lease is fixed unless the input supplies the same `learned_lease` changes observed live. */
class AutoSleepReplayToolTest {
    @Test fun `replay requested historical evidence`() {
        val inputPath = System.getProperty("hapaneld.autoSleepReplay.input").orEmpty()
        val outputPath = System.getProperty("hapaneld.autoSleepReplay.output").orEmpty()
        assumeTrue("replay is only run through the sidecar tool", inputPath.isNotBlank() && outputPath.isNotBlank())

        val input = JSONObject(File(inputPath).readText())
        val sources = input.getJSONArray("sources").let { values ->
            (0 until values.length()).mapTo(linkedSetOf()) { values.getString(it) }
        }
        val leaseMs = leaseMs(input)
        val rows = input.getJSONArray("events")
        val currentStates = sources.associateWithTo(linkedMapOf()) { AutoSleepSourceState.UNAVAILABLE }
        var feedRevision = 0L
        var markerSequence = 0L
        var marker: AutoSleepActivityMarker? = null
        val events = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            val at = row.getLong("at_ms")
            when (row.getString("type")) {
                "hydrate" -> {
                    val supplied = row.getJSONObject("states")
                    sources.forEach { source -> currentStates[source] = state(supplied.getString(source)) }
                    AutoSleepEvent.SourcesHydrated(
                        at, currentStates.toMap(), AutoSleepFeedPosition(1L, feedRevision++), marker,
                    )
                }
                "source" -> {
                    val source = row.getString("source")
                    require(source in currentStates) { "Unknown auto-sleep source: $source" }
                    val next = state(row.getString("state"))
                    if (currentStates[source] != AutoSleepSourceState.ON && next == AutoSleepSourceState.ON) {
                        marker = AutoSleepActivityMarker(++markerSequence, at)
                    }
                    currentStates[source] = next
                    AutoSleepEvent.SourcesHydrated(
                        at, currentStates.toMap(), AutoSleepFeedPosition(1L, feedRevision++), marker,
                    )
                }
                "touch" -> AutoSleepEvent.Touch(at)
                "screen_woken" -> AutoSleepEvent.ScreenWoken(at)
                "learned_lease" -> AutoSleepEvent.LearnedLeaseChanged(
                    at,
                    leaseMs(row),
                )
                "proximity" -> AutoSleepEvent.QualifiedProximity(at)
                "automatic_sleep" -> AutoSleepEvent.AutomaticSleepRecorded(at)
                else -> error("Unknown auto-sleep event type at index $index")
            }
        }
        val until = input.optLong("until_ms", events.lastOrNull()?.atMs ?: 0L)
        val trace = AutoSleepReplay.run(
            sources,
            events,
            until,
            AutoSleepPolicyConfig(learnedLeaseMs = leaseMs),
        )
        val header = "at_ms,cause,output,reason,next_deadline_ms,effective_lease_ms,healthy_sources,unavailable_sources\n"
        val csv = trace.joinToString(separator = "\n", postfix = "\n") { point ->
            listOf(
                point.atMs,
                point.cause.name.lowercase(),
                point.decision.output.name.lowercase(),
                point.decision.reason.name.lowercase(),
                point.decision.nextDeadlineMs ?: "",
                point.decision.effectiveLeaseMs,
                point.decision.healthySourceCount,
                point.decision.unavailableSourceCount,
            ).joinToString(",")
        }
        File(outputPath).apply { parentFile?.mkdirs() }.writeText(header + csv)
    }

    private fun state(raw: String): AutoSleepSourceState = when (raw.trim().lowercase()) {
        "on" -> AutoSleepSourceState.ON
        "off" -> AutoSleepSourceState.OFF
        "unavailable", "unknown" -> AutoSleepSourceState.UNAVAILABLE
        else -> error("Unknown source state: $raw")
    }

    private fun leaseMs(json: JSONObject): Long = if (json.has("lease_ms")) {
        json.getLong("lease_ms")
    } else {
        json.optLong("lease_minutes", 15L) * 60_000L
    }.coerceIn(MIN_AUTO_SLEEP_LEASE_MS, MAX_AUTO_SLEEP_LEASE_MS)
}
