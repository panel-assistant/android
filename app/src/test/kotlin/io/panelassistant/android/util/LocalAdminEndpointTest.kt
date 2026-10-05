package io.panelassistant.android.util

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAdminEndpointTest {
    @Test fun externalUrlUsesConfiguredPortAndPreferredIpv4() {
        assertEquals(
            "http://192.0.2.8:9123/configure",
            LocalAdminEndpoint.externalUrl("192.0.2.8", "2001:db8::8", 9123, "/configure"),
        )
    }

    @Test fun externalUrlBracketsIpv6Fallback() {
        assertEquals(
            "http://[2001:db8::8]:9123/",
            LocalAdminEndpoint.externalUrl(null, "2001:db8::8", 9123),
        )
    }

    @Test fun loopbackUrlNormalizesPath() {
        assertEquals("http://127.0.0.1:8888/health", LocalAdminEndpoint.loopbackUrl(8888, "health"))
    }

    @Test fun readinessStopsAtFirstSuccess() = runTest {
        var probes = 0
        val pauses = mutableListOf<Long>()
        val ready = LocalAdminReadiness(attempts = 5, retryDelayMs = 20).await(
            probe = { ++probes == 3 },
            pause = { pauses += it },
        )
        assertTrue(ready)
        assertEquals(3, probes)
        assertEquals(listOf(20L, 20L), pauses)
    }

    @Test fun readinessIsBoundedWhenProbeFailsOrThrows() = runTest {
        var probes = 0
        var pauses = 0
        val ready = LocalAdminReadiness(attempts = 4, retryDelayMs = 0).await(
            probe = {
                probes++
                if (probes % 2 == 0) throw IllegalStateException("not ready")
                false
            },
            pause = { pauses++ },
        )
        assertFalse(ready)
        assertEquals(4, probes)
        assertEquals(3, pauses)
    }

    @Test fun healthReadinessRejectsForeignOrMalformedSuccessResponses() {
        assertTrue(LocalAdminReadiness.isExpectedHealthResponse(200, "ha-paneld 0.9.3 panel=test\n"))
        assertFalse(LocalAdminReadiness.isExpectedHealthResponse(200, "another local service\n"))
        assertFalse(LocalAdminReadiness.isExpectedHealthResponse(503, "ha-paneld unavailable\n"))
    }
}
