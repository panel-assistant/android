package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.panelassistant.PanelAssistantTransportFacts
import io.github.maxlyth.hapaneld.panelassistant.PanelAssistantTransportPhase
import io.github.maxlyth.hapaneld.security.ApprovalBroker
import io.github.maxlyth.hapaneld.security.SensitiveOperation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class PanelAssistantTransportRoutesTest {

    @Test fun theTransportRouteReportsPersistedFactsAsCodes() = testApplication {
        val store = Store(PanelAssistantTransportFacts("native", "withdraw", PanelAssistantTransportPhase.WAITING, "unknown_command"))
        application { routing { panelAssistantTransportRoutes(store.dependencies { _, _, _, _ -> true }) } }

        val response = client.get("/api/v1/panel-assistant/transport")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(
            """{"authority":"native","mqtt_discovery":"withdraw","phase":"waiting","refusal":"unknown_command"}""",
            response.bodyAsText(),
        )
        assertEquals(0, store.releases)

        store.facts = PanelAssistantTransportFacts("", "", PanelAssistantTransportPhase.CONNECTED, null)
        assertEquals(
            """{"authority":"","mqtt_discovery":"","phase":"connected","refusal":null}""",
            client.get("/api/v1/panel-assistant/transport").bodyAsText(),
        )
    }

    @Test fun anAuthorizedReleasePersistsMqttAndAnnounceAndAnswersTheNewFacts() = testApplication {
        val store = Store(PanelAssistantTransportFacts("native", "withdraw", PanelAssistantTransportPhase.WAITING, "entry_removed"))
        val operations = mutableListOf<SensitiveOperation>()
        application {
            routing {
                panelAssistantTransportRoutes(store.dependencies { _, operation, _, _ -> operations += operation; true })
            }
        }

        val response = client.post("/api/v1/panel-assistant/transport/release")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(1, store.releases)
        assertEquals(listOf(SensitiveOperation.TRANSPORT_RELEASE), operations)
        val body = JSONObject(response.bodyAsText())
        assertEquals("mqtt", body.getString("authority"))
        assertEquals("announce", body.getString("mqtt_discovery"))
    }

    @Test fun hardenedModeHoldsARemoteReleaseForPhysicalApprovalAndRelaxedModeDoesNot() = testApplication {
        val store = Store(PanelAssistantTransportFacts("native", "withdraw", PanelAssistantTransportPhase.WAITING, null))
        var hardened = true
        val broker = ApprovalBroker({ 1_000L }, SecureRandom())
        application {
            routing {
                panelAssistantTransportRoutes(
                    store.dependencies { call, operation, payload, summary ->
                        authorizeSensitiveRequest(call, hardened, REMOTE_PEER, operation, payload, summary, broker)
                    },
                )
            }
        }

        val held = client.post("/api/v1/panel-assistant/transport/release")
        assertEquals(HttpStatusCode.Accepted, held.status)
        assertEquals("approval-required", JSONObject(held.bodyAsText()).getString("error"))
        assertEquals(0, store.releases)
        assertEquals(listOf(SensitiveOperation.TRANSPORT_RELEASE), broker.pending().map { it.operation })

        // The approval is one-shot and bound to the exact request from the same peer.
        assertTrue(broker.approve(broker.pending().single().id))
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/panel-assistant/transport/release").status)
        assertEquals(1, store.releases)

        hardened = false
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/panel-assistant/transport/release").status)
        assertEquals(2, store.releases)
    }

    /** Facts the release rewrites to `mqtt` and `announce`, as the owner's callbacks do. */
    private class Store(var facts: PanelAssistantTransportFacts) {
        var releases = 0

        fun dependencies(
            authorize: suspend (io.ktor.server.application.ApplicationCall, SensitiveOperation, String, String) -> Boolean,
        ) = PanelAssistantTransportRouteDependencies(
            facts = { facts },
            release = {
                releases++
                facts = facts.copy(authority = "mqtt", mqttDiscovery = "announce")
            },
            authorize = authorize,
        )
    }

    private companion object {
        const val REMOTE_PEER = "192.0.2.10"
    }
}
