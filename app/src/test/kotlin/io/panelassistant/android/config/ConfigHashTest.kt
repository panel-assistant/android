package io.panelassistant.android.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ConfigHashTest {
    @Test fun stableAndOrderInsensitive() {
        val a = ConfigHash.of(mapOf("a" to "1", "b" to "2"))
        assertEquals("same content, any order, same hash", a, ConfigHash.of(linkedMapOf("b" to "2", "a" to "1")))
        assertEquals("8-hex short form", 8, a.length)
    }

    @Test fun valueChangeChangesHash() {
        assertNotEquals(ConfigHash.of(mapOf("dark_mode" to "true")), ConfigHash.of(mapOf("dark_mode" to "false")))
    }

    @Test fun keyVsValueAmbiguityGuard() {
        // "a=b\n" joined naively could collide "a" to "b=x" with "a=b" to "x" — the separator prevents it.
        assertNotEquals(ConfigHash.of(mapOf("a" to "b=x")), ConfigHash.of(mapOf("a=b" to "x")))
    }
}
