package io.panelassistant.android.http

import io.panelassistant.android.config.Capabilities
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.content.OutgoingContent
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigPostBodyTest {
    @Test fun `legacy and versioned config posts reject declared and chunked overflow before parsing`() =
        testApplication {
            application { routing { installConfigReader("/config"); installConfigReader("/api/v1/config") } }
            val oversized = "not-json" + "x".repeat(TEST_LIMIT.toInt())

            listOf("/config", "/api/v1/config").forEach { path ->
                val declared = client.post(path) { setBody(oversized) }
                assertEquals(path, HttpStatusCode.PayloadTooLarge, declared.status)
                assertEquals("request too large\n", declared.bodyAsText())

                val chunked = client.post(path) { setBody(chunked(oversized.toByteArray())) }
                assertEquals(path, HttpStatusCode.PayloadTooLarge, chunked.status)
                assertEquals("request too large\n", chunked.bodyAsText())
            }
        }

    @Test fun `bounded config reader preserves form and scalar JSON values`() = testApplication {
        application { routing { installConfigReader("/config") } }

        val form = client.post("/config") {
            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
            setBody("friendly_name=Example+Panel&dashboard_zoom=125")
        }
        assertEquals(HttpStatusCode.OK, form.status)
        assertEquals("Example Panel|125", form.bodyAsText())

        val json = client.post("/config") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"friendly_name":"Example Panel","dashboard_zoom":125}""")
        }
        assertEquals(HttpStatusCode.OK, json.status)
        assertEquals("Example Panel|125", json.bodyAsText())
    }

    @Test fun `invalid or structured JSON is rejected without reaching config mutation`() = testApplication {
        application { routing { installConfigReader("/config") } }

        listOf("not-json", """{"friendly_name":{"nested":"value"}}""").forEach { body ->
            val response = client.post("/config") {
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody(body)
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("invalid config body\n", response.bodyAsText())
        }
    }

    @Test fun `admission refuses a capability-gated choice this panel cannot use`() {
        val posted = Parameters.build { append("navbar_mode", "Native") }

        // Default snapshot is all-false, so an unparameterized call is fail-closed.
        val withoutCapability = normalizeConfigPostParameters(posted)
        assertTrue(withoutCapability is ConfigPostParameters.Bad)
        assertEquals(
            "navbar_mode: Native is not available on this panel",
            (withoutCapability as ConfigPostParameters.Bad).reason,
        )

        val withCapability = normalizeConfigPostParameters(posted, Capabilities(hasNativeNavbar = true))
        assertEquals("Native", (withCapability as ConfigPostParameters.Ok).values["navbar_mode"])
    }

    @Test fun `admission still accepts the ungated navbar choices on any panel`() {
        listOf("Off", "Always on", "Swipe reveal").forEach { mode ->
            val result = normalizeConfigPostParameters(Parameters.build { append("navbar_mode", mode) })
            assertEquals(mode, (result as ConfigPostParameters.Ok).values["navbar_mode"])
        }
    }

    @Test fun `direct config admission normalizes every registered value before mutation`() {
        val result = normalizeConfigPostParameters(Parameters.build {
            append("friendly_name", "  Example Panel  ")
            append("dashboard_zoom", "125")
            append("dashboard_fullscreen", "on")
            append("ha_expose_wake_on_wave", "0")
            append("ha_expose_navbar_mode", "1")
            append("tame_vendor_packages", "com.vendor.one, com.vendor.two com.vendor.one")
            append("ha_token_expiry", "42")
            append("mqtt_password", "  exact password  ")
            append("mqtt_address_family", "prefer ipv4")
        }) as ConfigPostParameters.Ok
        assertEquals("Example Panel", result.values["friendly_name"])
        assertEquals("true", result.values["dashboard_fullscreen"])
        assertEquals("false", result.values["ha_expose_wake_on_wave"])
        assertEquals("true", result.values["ha_expose_navbar_mode"])
        assertEquals("com.vendor.one com.vendor.two", result.values["tame_vendor_packages"])
        assertEquals("42", result.values["ha_token_expiry"])
        assertEquals("  exact password  ", result.values["mqtt_password"])
        assertEquals("Prefer IPv4", result.values["mqtt_address_family"])
    }

    @Test fun `direct config admission rejects invalid or amplifying values atomically`() {
        listOf(
            Parameters.build { append("friendly_name", "x".repeat(129)) },
            Parameters.build { append("dashboard_zoom", "301") },
            Parameters.build { append("dashboard_fullscreen", "maybe") },
            Parameters.build { append("ha_expose_missing", "true") },
            Parameters.build { append("tame_vendor_packages", "com.good;reboot") },
            Parameters.build { append("ha_token_expiry", "-1") },
            Parameters.build { append("mqtt_address_family", "IPv7") },
            Parameters.build { append("typo_setting", "true") },
            Parameters.build { append("friendly_name", "one"); append("friendly_name", "two") },
        ).forEach { parameters ->
            assertTrue(normalizeConfigPostParameters(parameters) is ConfigPostParameters.Bad)
        }
    }

    @Test fun `mqtt onboarding discovers ha url only when user has not posted one`() {
        assertTrue(shouldDiscoverHaUrlForMqttOnboarding("", Parameters.build {
            append("mqtt_broker", "tcp://homeassistant.local:1883")
        }))
        assertTrue(shouldDiscoverHaUrlForMqttOnboarding("", Parameters.build {
            append("mqtt_user", "panel")
        }))
        assertFalse(shouldDiscoverHaUrlForMqttOnboarding("http://homeassistant.local:8123", Parameters.build {
            append("mqtt_broker", "tcp://homeassistant.local:1883")
        }))
        assertFalse(shouldDiscoverHaUrlForMqttOnboarding("", Parameters.build {
            append("mqtt_broker", "tcp://homeassistant.local:1883")
            append("ha_url", "")
        }))
        assertFalse(shouldDiscoverHaUrlForMqttOnboarding("", Parameters.build {
            append("friendly_name", "Alpha")
        }))
    }

    @Test fun `automatic brightness minimum preserves the visible floor at config admission`() {
        listOf("4", "99").forEach { value ->
            // Asserted before the cast: an unchecked cast on a narrowed bound raises ClassCastException,
            // which is an error rather than a failure, so the accept case could not be credited as
            // covered by anything that mutates the bound.
            val result = normalizeConfigPostParameters(Parameters.build {
                append("auto_brightness_minimum_percent", value)
            })
            assertTrue("$value must be admitted", result is ConfigPostParameters.Ok)
            assertEquals(value, (result as ConfigPostParameters.Ok).values["auto_brightness_minimum_percent"])
        }
        listOf("3", "100").forEach { value ->
            val result = normalizeConfigPostParameters(Parameters.build {
                append("auto_brightness_minimum_percent", value)
            })
            assertTrue(value, result is ConfigPostParameters.Bad)
        }
    }

    private fun io.ktor.server.routing.Route.installConfigReader(path: String) {
        post(path) {
            val parameters = receiveBoundedConfigParameters(call, TEST_LIMIT) ?: return@post
            call.respondText("${parameters["friendly_name"].orEmpty()}|${parameters["dashboard_zoom"].orEmpty()}")
        }
    }

    private fun chunked(bytes: ByteArray) = object : OutgoingContent.WriteChannelContent() {
        override val contentType: ContentType = ContentType.Application.Json
        override suspend fun writeTo(channel: ByteWriteChannel) { channel.writeFully(bytes) }
    }

    private companion object { const val TEST_LIMIT = 1_024L }
}
