package io.panelassistant.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MdnsAdvertisedUrlPolicyTest {
    @Test
    fun acceptsHttpUrlOnlyWhenItResolvesToAdvertiser() {
        val loopback = setOf("127.0.0.1", "0:0:0:0:0:0:0:1")
        assertEquals("http://localhost:8123", safeAdvertisedHaUrl("http://localhost:8123/", loopback))
        assertNull(safeAdvertisedHaUrl("http://example.com:8123", loopback))
    }

    @Test
    fun rejectsCredentialsAndNonHttpSchemes() {
        val loopback = setOf("127.0.0.1", "0:0:0:0:0:0:0:1")
        assertNull(safeAdvertisedHaUrl("http://user:pass@localhost:8123", loopback))
        assertNull(safeAdvertisedHaUrl("file://localhost/etc/passwd", loopback))
    }
}
