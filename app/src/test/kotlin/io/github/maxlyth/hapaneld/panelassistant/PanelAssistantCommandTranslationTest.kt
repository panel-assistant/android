package io.github.maxlyth.hapaneld.panelassistant

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PanelAssistantCommandTranslationTest {
    private fun describe(channel: String) = requireNotNull(PanelAssistantChannelCatalog.describe(channel)) { channel }

    private fun payload(channel: String, value: Any?) = PanelAssistantCommandTranslation.payload(describe(channel), value)

    @Test fun `switches take booleans and nothing else`() {
        assertEquals("ON", payload("relay3", true))
        assertEquals("OFF", payload("touch_sound", false))
        assertNull(payload("relay3", "ON"))
        assertNull(payload("relay3", 1))
        assertNull(payload("relay3", null))
    }

    @Test fun `numbers stay within the descriptor bounds`() {
        assertEquals("40", payload("volume", 40))
        assertEquals("40", payload("volume", 40.0))
        assertEquals("12.5", payload("volume", 12.5))
        assertNull(payload("volume", 101))
        assertNull(payload("volume", -1))
        assertNull(payload("volume", Double.NaN))
        assertNull(payload("volume", "40"))
    }

    @Test fun `select codes become the labels the handlers already take`() {
        assertEquals("Performance", payload("cpu_governor", "performance"))
        assertEquals("Swipe reveal", payload("navbar", "swipe_reveal"))
        assertNull(payload("cpu_governor", "Performance"))
        assertNull(payload("cpu_governor", "turbo"))
    }

    @Test fun `every option code of every select round-trips to a label`() {
        listOf("cpu_governor", "navbar").forEach { channel ->
            val descriptor = describe(channel)
            requireNotNull(descriptor.options).forEach { code ->
                val label = requireNotNull(PanelAssistantCommandTranslation.payload(descriptor, code)) { "$channel $code" }
                assertEquals(code, descriptor.code(label))
            }
        }
    }

    @Test fun `lights become Home Assistant's JSON light schema, button LEDs a bare switch payload`() {
        assertEquals(
            JSONObject("""{"state":"ON","brightness":180}""").toString(),
            payload("screen", JSONObject().put("on", true).put("brightness", 180)),
        )
        assertEquals(
            JSONObject("""{"state":"ON","color":{"r":1,"g":2,"b":3},"effect":"pulse"}""").toString(),
            payload("led", JSONObject().put("on", true).put("color", JSONObject().put("r", 1).put("g", 2).put("b", 3)).put("effect", "pulse")),
        )
        assertEquals("""{"state":"OFF"}""", payload("screen", JSONObject().put("on", false)))
        assertEquals("ON", payload("button_led2", JSONObject().put("on", true).put("brightness", 10)))
        assertNull(payload("screen", JSONObject().put("brightness", 10)))
        assertNull(payload("screen", JSONObject().put("on", true).put("brightness", 256)))
        assertNull(payload("led", JSONObject().put("on", true).put("effect", "disco")))
        assertNull(payload("led", JSONObject().put("on", true).put("color", JSONObject().put("r", 1))))
        assertNull(payload("screen", true))
    }

    @Test fun `text is bounded and free of control characters`() {
        assertEquals("/lovelace/0", payload("navigate", "/lovelace/0"))
        assertEquals("a".repeat(255), payload("navigate", "a".repeat(255)))
        assertNull(payload("navigate", "a".repeat(256)))
        assertNull(payload("navigate", "/a\nb"))
    }

    @Test fun `update descriptors are never commandable`() {
        assertNull(payload("update_paneld", JSONObject()))
    }
}
