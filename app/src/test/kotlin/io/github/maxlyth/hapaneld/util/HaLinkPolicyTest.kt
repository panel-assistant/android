package io.github.maxlyth.hapaneld.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import org.junit.Assert.assertThrows

class HaLinkPolicyTest {
    @Test fun websocketResolutionIsBoundedForUntrustedConfiguredEndpoints() {
        assertTrue(HaLink.MAX_WS_FRAME_BYTES in 1L..(32L * 1024L * 1024L))
        assertTrue(HaLink.WS_RESOLUTION_DEADLINE_MS in 1_000L..30_000L)
    }

    @Test fun deviceLinkResolutionHonorsForceIpv4() {
        // Both resolve entry points take non-defaulted policy parameters, so no caller can omit
        // them; this pins that the policy genuinely reaches the socket: an IPv6-only answer under
        // Force IPv4 must fail with the named policy verdict rather than dial IPv6.
        val v6only = listOf(java.net.InetAddress.getByName("2001:db8::1"))
        try {
            HaLink.deviceIdViaWs(
                "ws://ha.test:9", "token", listOf("panel"),
                preferIpv4 = true, ipv4Only = true, resolver = { v6only },
            )
            org.junit.Assert.fail("expected the Force IPv4 policy verdict")
        } catch (expected: Exception) {
            val chain = generateSequence<Throwable>(expected) { it.cause }.toList()
            assertTrue(
                "failure names the Force IPv4 conflict, was: $chain",
                chain.any { it.message.orEmpty().contains("Force IPv4") },
            )
        }
    }

    @Test fun httpResolutionResponsesAreBoundedBeforeTextDecoding() {
        assertTrue(HaLink.MAX_HTTP_RESPONSE_BYTES in 1L..(4L * 1024L * 1024L))
        assertEquals("{}", HaLink.readHttpBody(ByteArrayInputStream("{}".toByteArray())))
        assertThrows(ByteLimitExceeded::class.java) {
            HaLink.readHttpBody(ByteArrayInputStream(ByteArray(HaLink.MAX_HTTP_RESPONSE_BYTES.toInt() + 1)))
        }
    }

    @Test fun `resolution target retains the exact native base path and normalizes identity`() {
        assertEquals(
            "v1:25:https://native.example/ha:wall_panel",
            HaLink.resolutionTarget("  https://native.example/ha/ ", "Wall Panel"),
        )
    }

    @Test fun `resolution target is safe for SharedPreferences XML`() {
        val target = HaLink.resolutionTarget("https://native.example/ha", "Wall Panel")

        assertEquals(-1, target.indexOf('\u0000'))
    }

    @Test fun `native and mqtt servers cannot share a cached device id`() {
        val native = HaLink.resolutionTarget("https://native.example/ha", "panel")
        val mqtt = HaLink.resolutionTarget("https://mqtt.example", "panel")

        assertNotEquals(native, mqtt)
    }

    @Test fun `stable panel identity wins over an earlier friendly name match`() {
        val response = """
            {"result":{"entities":[
              {"ei":"text.alpha_display_home_dashboard","di":"friendly-device"},
              {"ei":"light.alpha_display_screen","di":"friendly-device"},
              {"ei":"text.wall_panel_home_dashboard","di":"stable-device"},
              {"ei":"light.wall_panel_screen","di":"stable-device"}
            ]}}
        """.trimIndent()

        assertEquals(
            "stable-device",
            HaLink.matchDeviceId(response, listOf("wall_panel", "alpha_display")),
        )
    }

    @Test fun `candidate prefix cannot select an unrelated device before the panel entities`() {
        val response = """
            {"result":{"entities":[
              {"ei":"sensor.alpha_temperature","di":"temperature-device"},
              {"ei":"binary_sensor.alpha_motion","di":"motion-device"},
              {"ei":"text.alpha_home_dashboard","di":"panel-device"},
              {"ei":"light.alpha_screen","di":"panel-device"}
            ]}}
        """.trimIndent()

        assertEquals("panel-device", HaLink.matchDeviceId(response, listOf("alpha")))
    }

    @Test fun `numeric HA collision suffix combines with an exact panel marker`() {
        val response = """
            {"result":[
              {"ei":"light.alpha_screen","di":"unrelated-screen"},
              {"ei":"text.alpha_home_dashboard","di":"panel-device"},
              {"ei":"light.alpha_screen_2","di":"panel-device"}
            ]}
        """.trimIndent()

        assertEquals("panel-device", HaLink.matchDeviceId(response, listOf("alpha")))
    }

    @Test fun `qualified exact collision owner cannot outrank the suffixed panel device`() {
        val response = """
            {"result":[
              {"ei":"text.alpha_home_dashboard","di":"collision-owner"},
              {"ei":"light.alpha_screen","di":"collision-owner"},
              {"ei":"text.alpha_home_dashboard_2","di":"panel-device"},
              {"ei":"light.alpha_screen_2","di":"panel-device"}
            ]}
        """.trimIndent()

        assertNull(HaLink.matchDeviceId(response, listOf("alpha")))
    }

    @Test fun `historical friendly name remains a fallback when stable identity has no candidate`() {
        val response = """
            {"result":{"entities":[
              {"ei":"light.wall_panel_screen","di":"incomplete-stable-device"},
              {"ei":"text.alpha_display_home_dashboard","di":"legacy-device"},
              {"ei":"light.alpha_display_screen","di":"legacy-device"}
            ]}}
        """.trimIndent()

        assertEquals(
            "legacy-device",
            HaLink.matchDeviceId(response, listOf("wall_panel", "alpha_display")),
        )
    }

    @Test fun `one same-prefix known-looking entity is insufficient evidence`() {
        val response = """
            {"result":[
              {"ei":"light.alpha_screen","di":"unrelated-screen"},
              {"ei":"sensor.alpha_temperature","di":"temperature-device"}
            ]}
        """.trimIndent()

        assertNull(HaLink.matchDeviceId(response, listOf("alpha")))
    }

    @Test fun `registry response without a matching panel entity has no device destination`() {
        val response = """
            {"result":[{"ei":"sensor.unrelated_panel_status","di":"other-device"}]}
        """.trimIndent()

        assertNull(HaLink.matchDeviceId(response, listOf("wall_panel")))
    }
}
