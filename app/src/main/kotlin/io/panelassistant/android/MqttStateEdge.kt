package io.panelassistant.android

import io.panelassistant.android.mqtt.StateConverger

/** Where one converger channel lands on MQTT. Known only to the MQTT edge, never to the converger. */
internal data class MqttStateRoute(val topic: String, val retain: Boolean)

internal data class MqttStateChannel(
    val channel: StateConverger.Channel,
    val route: MqttStateRoute,
)

internal fun mqttStateChannel(
    key: String,
    topic: String,
    retain: Boolean = true,
    equivalent: (String, String) -> Boolean = String::equals,
    observe: () -> StateConverger.Observation,
): MqttStateChannel {
    val refreshAfterAckMs = mqttMeasurementRefreshAfterAckMs(key)
    return MqttStateChannel(
        StateConverger.Channel(
            key = key,
            observe = observe,
            equivalent = equivalent,
            refreshEligible = if (refreshAfterAckMs != null) {
                ::mqttMeasurementPayloadIsRefreshable
            } else {
                { true }
            },
            maxSilenceMs = refreshAfterAckMs,
        ),
        MqttStateRoute(topic, retain),
    )
}

/** An observation's exact MQTT payload: unavailable clears the retained value with an empty payload. */
internal fun mqttStatePayload(observation: StateConverger.Observation.Reportable): String =
    when (observation) {
        is StateConverger.Observation.Known -> observation.payload
        StateConverger.Observation.Unavailable -> ""
    }

/**
 * The dispatcher conflation key of a state command topic: its protocol §7 channel, the leaf between
 * `ha-paneld/<panel>/` and `/set`. Null for any topic outside that shape, including an index spelled
 * with non-ASCII digits, which the grammar refuses.
 */
internal fun mqttCommandChannel(panel: String, topic: String): String? {
    val prefix = "ha-paneld/$panel/"
    if (topic.length <= prefix.length + 4 || !topic.startsWith(prefix) || !topic.endsWith("/set")) return null
    return topic.substring(prefix.length, topic.length - 4)
        .takeIf(StateConverger.CHANNEL_ID::matches)
}
