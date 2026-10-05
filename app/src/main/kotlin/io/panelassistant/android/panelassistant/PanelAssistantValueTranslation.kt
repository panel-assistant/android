package io.panelassistant.android.panelassistant

import io.panelassistant.android.mqtt.StateConverger
import org.json.JSONException
import org.json.JSONObject

/** One observation as `report_state` carries it: known with a typed value, or unavailable. */
internal sealed interface PanelAssistantWireValue {
    data class Known(val value: Any) : PanelAssistantWireValue
    data object Unavailable : PanelAssistantWireValue
}

/**
 * Translates the MQTT payload a state sink receives into the typed value of protocol section 7, at the
 * WebSocket edge only. A payload with no truthful typed form translates to null: the caller skips that
 * observation for the native transport and counts it, and MQTT is unaffected.
 */
internal object PanelAssistantValueTranslation {
    /** Every non-text platform uses a literal `unknown` for a value the panel cannot currently read. */
    private const val UNKNOWN = "unknown"
    private const val MAX_ATTRIBUTES = 32
    private const val MAX_ATTRIBUTE_CHARS = 255
    private val CODE = Regex("^[a-z][a-z0-9_]{0,63}$")
    private val CONTROL = Regex("[\\x00-\\x1f\\x7f]")

    fun translate(
        descriptor: PanelAssistantChannelDescriptor,
        observation: StateConverger.Observation.Reportable,
    ): PanelAssistantWireValue? {
        if (descriptor.kind == PanelAssistantValueKind.BUTTON) return null
        val payload = when (observation) {
            StateConverger.Observation.Unavailable -> return PanelAssistantWireValue.Unavailable
            is StateConverger.Observation.Known -> observation.payload
        }
        if (descriptor.kind != PanelAssistantValueKind.TEXT && (payload == UNKNOWN || payload.isEmpty())) {
            return PanelAssistantWireValue.Unavailable
        }
        val value: Any = when (descriptor.kind) {
            PanelAssistantValueKind.BOOLEAN -> when (payload) {
                "ON" -> true
                "OFF" -> false
                else -> null
            }
            PanelAssistantValueKind.NUMBER -> number(payload)
            PanelAssistantValueKind.OPTION -> descriptor.code(payload)
            PanelAssistantValueKind.TEXT -> payload
            PanelAssistantValueKind.LIGHT -> light(payload)
            PanelAssistantValueKind.UPDATE -> update(payload)
            PanelAssistantValueKind.MEDIA -> media(payload)
            PanelAssistantValueKind.BUTTON -> null
        } ?: return null
        return PanelAssistantWireValue.Known(value)
    }

    private fun number(payload: String): Number? =
        payload.toLongOrNull() ?: payload.toDoubleOrNull()?.takeIf { it.isFinite() }

    private fun parse(payload: String): JSONObject? = try {
        JSONObject(payload)
    } catch (_: JSONException) {
        null
    }

    private fun light(payload: String): JSONObject? {
        // Button LEDs publish a bare ON/OFF; every other light publishes Home Assistant's JSON schema.
        when (payload) {
            "ON" -> return JSONObject().put("on", true)
            "OFF" -> return JSONObject().put("on", false)
        }
        val json = parse(payload) ?: return null
        val on = when (json.opt("state")) {
            "ON" -> true
            "OFF" -> false
            else -> return null
        }
        val light = JSONObject().put("on", on)
        if (json.has("brightness")) light.put("brightness", byte(json.opt("brightness")) ?: return null)
        json.optJSONObject("color")?.let { color ->
            val rgb = JSONObject()
            for (channel in listOf("r", "g", "b")) rgb.put(channel, byte(color.opt(channel)) ?: return null)
            light.put("color", rgb)
        }
        if (json.has("effect")) {
            val effect = (json.opt("effect") as? String)?.takeIf(CODE::matches) ?: return null
            light.put("effect", effect)
        }
        return light
    }

    private fun byte(value: Any?): Int? = (value as? Int)?.takeIf { it in 0..255 }

    private val MEDIA_STATES = setOf("idle", "playing", "paused", "buffering")

    private fun media(payload: String): JSONObject? {
        val json = parse(payload) ?: return null
        val state = (json.opt("state") as? String)?.takeIf { it in MEDIA_STATES } ?: return null
        val muted = json.opt("muted") as? Boolean ?: return null
        return JSONObject().put("state", state).put("muted", muted)
    }

    private fun update(payload: String): JSONObject? {
        val json = parse(payload) ?: return null
        fun optionalString(key: String): Any? = when (val value = json.opt(key)) {
            null, JSONObject.NULL -> JSONObject.NULL
            is String -> value
            else -> null
        }
        val inProgress = when (val value = json.opt("in_progress")) {
            null -> false
            is Boolean -> value
            else -> return null
        }
        return JSONObject()
            .put("installed_version", optionalString("installed_version") ?: return null)
            .put("latest_version", optionalString("latest_version") ?: return null)
            .put("release_url", optionalString("release_url") ?: return null)
            .put("in_progress", inProgress)
    }

    /**
     * Flat scalar attributes from an MQTT attributes payload: nested values, non-code keys and strings the
     * integration would refuse are dropped rather than failing the parent observation. Null when the
     * payload is not a JSON object.
     */
    fun attributes(observation: StateConverger.Observation.Reportable?): JSONObject? {
        val payload = (observation as? StateConverger.Observation.Known)?.payload ?: return null
        val json = parse(payload) ?: return null
        val flat = JSONObject()
        for (key in json.keys()) {
            if (flat.length() >= MAX_ATTRIBUTES) break
            if (!CODE.matches(key)) continue
            when (val value = json.opt(key)) {
                JSONObject.NULL, is Boolean -> flat.put(key, value)
                is Int, is Long -> flat.put(key, value)
                is Number -> value.toDouble().takeIf { it.isFinite() }?.let { flat.put(key, it) }
                is String -> if (value.length <= MAX_ATTRIBUTE_CHARS && !CONTROL.containsMatchIn(value)) {
                    flat.put(key, value)
                }
            }
        }
        return flat
    }
}
