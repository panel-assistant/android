package io.github.maxlyth.hapaneld.panelassistant

import io.github.maxlyth.hapaneld.mqtt.StateConverger.Observation
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PanelAssistantValueTranslationTest {

    @Test fun switchesAndBinarySensorsBecomeBooleans() {
        assertEquals(known(true), translate("relay1", "ON"))
        assertEquals(known(false), translate("auto_sleep", "OFF"))
        assertEquals(known(true), translate("proximity", "ON"))
        assertNull(translate("relay1", "on"))
    }

    @Test fun aLiteralUnknownOrEmptyPayloadIsUnavailableOutsideText() {
        assertEquals(PanelAssistantWireValue.Unavailable, translate("zigbee_router", "unknown"))
        assertEquals(PanelAssistantWireValue.Unavailable, translate("cpu_governor", "unknown"))
        assertEquals(PanelAssistantWireValue.Unavailable, translate("diag_cpu", "unknown"))
        assertEquals(PanelAssistantWireValue.Unavailable, translate("diag_wifi_rssi", ""))
        assertEquals(PanelAssistantWireValue.Unavailable, translate("relay2", Observation.Unavailable))
        assertEquals(known(""), translate("home_dashboard", ""))
        assertEquals(known("unknown"), translate("navigate", "unknown"))
    }

    @Test fun numbersAndMeasurementsBecomeFiniteNumbers() {
        assertEquals(known(35L), translate("volume", "35"))
        assertEquals(known(21.5), translate("temperature", "21.5"))
        assertEquals(known(-61L), translate("diag_wifi_rssi", "-61"))
        assertNull(translate("humidity", "NaN"))
        assertNull(translate("humidity", "Infinity"))
        assertNull(translate("volume", "loud"))
    }

    @Test fun selectsAndEnumSensorsBecomeOptionCodes() {
        assertEquals(known("swipe_reveal"), translate("navbar", "Swipe reveal"))
        assertEquals(known("performance"), translate("cpu_governor", "Performance"))
        assertEquals(known("database_failure"), translate("storage_health", "database_failure"))
        assertNull(translate("navbar", "Sideways"))
    }

    @Test fun textAndStringSensorsStayStrings() {
        assertEquals(known("/lovelace/0"), translate("navigate", "/lovelace/0"))
        assertEquals(known("Home Wi-Fi"), translate("diag_wifi_ssid", "Home Wi-Fi"))
    }

    @Test fun lightsBecomeTypedObjects() {
        assertEquals("""{"on":true,"brightness":180}""", json(translate("screen", """{"state":"ON","brightness":180}""")))
        assertEquals("""{"on":false}""", json(translate("screen", """{"state":"OFF"}""")))
        assertEquals(
            """{"on":true,"brightness":90,"color":{"r":1,"g":2,"b":3},"effect":"pulse"}""",
            json(translate("led", """{"state":"ON","color_mode":"rgb","brightness":90,"color":{"r":1,"g":2,"b":3},"effect":"pulse"}""")),
        )
        assertEquals("""{"on":true}""", json(translate("button_led1", "ON")))
        assertNull(translate("screen", """{"state":"DIM"}"""))
        assertNull(translate("screen", """{"state":"ON","brightness":300}"""))
        assertNull(translate("buttons", "not json"))
    }

    @Test fun updatesKeepOnlyTheirFacts() {
        val payload = """{"installed_version":"1.2.3","latest_version":"1.2.4","title":"ha-paneld","release_summary":"Stable channel.","release_url":"https://example.invalid/r","in_progress":true}"""
        assertEquals(
            """{"installed_version":"1.2.3","latest_version":"1.2.4","release_url":"https://example.invalid/r","in_progress":true}""",
            json(translate("update_paneld", payload)),
        )
        assertEquals(
            """{"installed_version":"2026.1.1","latest_version":null,"release_url":null,"in_progress":false}""",
            json(translate("update_companion", """{"installed_version":"2026.1.1","title":"x"}""")),
        )
        assertNull(translate("update_paneld", """{"installed_version":7}"""))
        assertNull(translate("update_paneld", """{"in_progress":"yes"}"""))
    }

    @Test fun attributesKeepFlatScalarsAndDropNestedValues() {
        val flat = PanelAssistantValueTranslation.attributes(
            Observation.Known("""{"is_lower_bound":false,"used_percent":25.5,"failure_operation":null,"quick_check":"ok","nested":{"a":1},"list":[1],"Bad Key":1,"usable_bytes":6000000000}"""),
        )
        assertNotNull(flat)
        assertEquals(
            mapOf("is_lower_bound" to false, "used_percent" to 25.5, "failure_operation" to JSONObject.NULL, "quick_check" to "ok", "usable_bytes" to 6_000_000_000L),
            flat!!.keys().asSequence().associateWith { flat.get(it) },
        )
        assertNull(PanelAssistantValueTranslation.attributes(Observation.Unavailable))
        assertNull(PanelAssistantValueTranslation.attributes(Observation.Known("not json")))
    }

    @Test fun theMediaChannelReportsStateAndMute() {
        assertEquals("""{"state":"paused","muted":true}""", json(translate("media", """{"state":"paused","muted":true}""")))
        assertEquals("""{"state":"idle","muted":false}""", json(translate("media", """{"muted":false,"state":"idle"}""")))
        assertNull(translate("media", """{"state":"stopped","muted":false}"""))
        assertNull(translate("media", """{"state":"playing"}"""))
        assertNull(translate("media", "playing"))
    }

    private fun translate(channel: String, payload: String) = translate(channel, Observation.Known(payload))

    private fun translate(channel: String, observation: Observation.Reportable): PanelAssistantWireValue? =
        PanelAssistantChannelCatalog.describe(channel).let { descriptor ->
            assertNotNull("no descriptor for $channel", descriptor)
            PanelAssistantValueTranslation.translate(descriptor!!, observation)
        }

    private fun known(value: Any) = PanelAssistantWireValue.Known(value)

    /** Canonical JSON text of an object value, with keys in insertion order. */
    private fun json(value: PanelAssistantWireValue?): String {
        val known = value as? PanelAssistantWireValue.Known ?: return value.toString()
        return canonical(known.value)
    }

    private fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().sortedBy { ORDER.indexOf(it).takeIf { i -> i >= 0 } ?: 99 }
            .joinToString(",", "{", "}") { "\"$it\":${canonical(value.get(it))}" }
        JSONObject.NULL -> "null"
        is String -> "\"$value\""
        else -> value.toString()
    }

    private companion object {
        val ORDER = listOf("on", "brightness", "color", "r", "g", "b", "effect", "installed_version", "latest_version", "release_url", "in_progress")
    }
}
