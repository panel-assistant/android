package io.panelassistant.android.http

import io.panelassistant.android.config.SettingsRegistry
import io.panelassistant.android.panelassistant.PanelAssistantTransportProtocol
import io.panelassistant.android.config.Capabilities
import io.panelassistant.android.i18n.CatalogueLoader
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Emits the mounted production route's Configure responses for the browser renderer test. The browser
 * therefore receives the exact schema and values the app produces, instead of a hand-maintained response.
 */
class MqttCardSchemaFixtureTest {
    @Test fun `mounted schema fixture records native and legacy panels with and without a saved broker`() {
        val nativeBlank = response(PanelAssistantTransportProtocol.AUTHORITY_NATIVE, broker = "")
        val nativeSaved = response(PanelAssistantTransportProtocol.AUTHORITY_NATIVE, broker = SAVED_BROKER)
        val legacyBlank = response(authority = "", broker = "")
        val legacyMqtt = response(PanelAssistantTransportProtocol.AUTHORITY_MQTT, broker = SAVED_BROKER)
        val legacyShadow = response(PanelAssistantTransportProtocol.AUTHORITY_SHADOW, broker = SAVED_BROKER)

        assertEquals("", nativeBlank.getJSONObject("config").getString("mqtt_broker"))
        assertEquals("", legacyBlank.getJSONObject("config").getString("mqtt_broker"))
        for (response in listOf(nativeSaved, legacyMqtt, legacyShadow)) {
            assertEquals(SAVED_BROKER, response.getJSONObject("config").getString("mqtt_broker"))
            assertTrue(response.getJSONArray("schema").length() > 0)
        }
        File("build/test-fixtures/hide-mqtt-card.json").apply {
            parentFile.mkdirs()
            writeText(
                JSONObject()
                    .put("nativeBlank", nativeBlank)
                    .put("nativeSaved", nativeSaved)
                    .put("legacyBlank", legacyBlank)
                    .put("legacyMqtt", legacyMqtt)
                    .put("legacyShadow", legacyShadow)
                    .toString(),
            )
        }
    }

    private fun response(authority: String, broker: String): JSONObject = PaneldServerHttpFixture().use { fixture ->
        fixture.config.setRaw(requireNotNull(SettingsRegistry.spec("friendly_name")), "Fixture panel")
        fixture.config.setRaw(requireNotNull(SettingsRegistry.spec("manufacturer")), "Fixture manufacturer")
        fixture.config.setRaw(requireNotNull(SettingsRegistry.spec("model")), "Fixture model")
        fixture.config.setMqtt(broker, "owner", "secret")
        fixture.config.setPanelAssistantAuthority(authority)
        val values = ConfigValueProjection(
            config = fixture.config,
            configLiveValues = { emptyMap() },
            renderedLiveValues = { emptyMap() },
            pendingLiveSettings = { emptyMap() },
            stalledLiveSettings = { emptySet() },
            proximityJson = { "{}" },
            powerSafetyJson = { "{}" },
            haAreaCatalogJson = { null },
        )
        // Source-text reason: use the shipped English catalogue exactly as the production schema route does.
        val strings = CatalogueLoader { name -> File("src/main/assets", name).readText() }.strings("en")
        val schemaJson = values.schemaJson(strings, Capabilities(), emptyMap(), null, null)
        val configJson = values.configJson()
        lateinit var response: JSONObject
        testApplication {
            application {
                routing {
                    route("/api/v1") {
                        configReadRoutes(
                            currentConfigJson = { configJson },
                            localizedSchema = {
                                LocalizedConfigSchema(
                                    schemaJson,
                                    strings.languages(setOf("settings.")),
                                )
                            },
                        )
                    }
                }
            }
            val schema = client.get("/api/v1/config/schema")
            val config = client.get("/api/v1/config")
            assertEquals(HttpStatusCode.OK, schema.status)
            assertEquals(HttpStatusCode.OK, config.status)
            response = JSONObject().put("schema", JSONArray(schema.bodyAsText())).put("config", JSONObject(config.bodyAsText()))
        }
        response
    }

    private companion object {
        const val SAVED_BROKER = "mqtt://saved-broker.example:1883"
    }
}
