package io.panelassistant.android.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsUrlValidationTest {
    @Test
    fun acceptsOnlyCredentialFreeHttpOrigins() {
        assertEquals("https://ha.example.test:8123", normalizeHttpOriginUrl(" https://ha.example.test:8123/ "))
        assertNull(normalizeHttpOriginUrl("javascript:alert(1)"))
        assertNull(normalizeHttpOriginUrl("https://user:secret@ha.example.test"))
        assertNull(normalizeHttpOriginUrl("https:///missing-host"))
        assertNull(normalizeHttpOriginUrl("https://ha.example.test?redirect=elsewhere"))
        assertNull(normalizeHttpOriginUrl("https://ha.example.test#fragment"))
    }
}
