package io.panelassistant.android.util

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class JsonTest {
    @Test fun `string encoding preserves text and escapes every JSON control`() {
        val corpus = listOf("", "plain text", "quote\" and slash\\", "\n\r\t", "é 中文 🏠") +
            (0..31).map { "before${it.toChar()}after" }
        for (text in corpus) {
            val encoded = Json.str(text)
            assertEquals('"', encoded.first())
            assertEquals('"', encoded.last())
            assertFalse(encoded, encoded.any { it < ' ' })
            assertEquals(text, JSONObject("{\"value\":$encoded}").getString("value"))
        }
        assertEquals("\"quote\\\" and slash\\\\\"", Json.str("quote\" and slash\\"))
        assertEquals("\"\\n\\r\\t\"", Json.str("\n\r\t"))
        assertEquals("\"\\u0000\\u001f\"", Json.str("\u0000\u001f"))
    }
}
