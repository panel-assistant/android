package io.panelassistant.android.http

import io.panelassistant.android.panelassistant.PanelAssistantTransportFacts
import io.panelassistant.android.security.SensitiveOperation
import io.panelassistant.android.util.Json
import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

internal data class PanelAssistantTransportRouteDependencies(
    val facts: () -> PanelAssistantTransportFacts,
    /** Persists authority `mqtt` and discovery `announce` and re-announces MQTT discovery. */
    val release: () -> Unit,
    val authorize: suspend (ApplicationCall, SensitiveOperation, String, String) -> Boolean,
)

/**
 * The panel-local view and release of the native transport's hold on this panel's entities.
 *
 * The release is the repair when no integration can answer: an integration downgraded to one without the
 * native transport, or an entry removed while the panel never heard of it, leaves a panel that withdrew its
 * MQTT discovery and refuses MQTT commands with no entities anywhere. It re-opens MQTT `/set` commanding, so
 * it takes the same one-shot physical approval in Hardened mode as the other remote actions that loosen who
 * may control the panel; a loopback caller keeps the established exemption. A live integration's next
 * accepted hello still wins and may claim the panel again.
 *
 * Migration scaffolding for moving entities between MQTT and the integration; it is deleted with MQTT.
 */
internal fun Route.panelAssistantTransportRoutes(dependencies: PanelAssistantTransportRouteDependencies) {
    route("/api/v1") {
        get("/panel-assistant/transport") { respondFacts(call, dependencies) }
        post("/panel-assistant/transport/release") {
            if (!dependencies.authorize(
                    call,
                    SensitiveOperation.TRANSPORT_RELEASE,
                    exactHttpApprovalPayload(call, sha256Hex(ByteArray(0))),
                    "Hand this panel's entities and commands back to MQTT",
                )
            ) return@post
            dependencies.release()
            respondFacts(call, dependencies)
        }
    }
}

private suspend fun respondFacts(call: ApplicationCall, dependencies: PanelAssistantTransportRouteDependencies) {
    call.respondText(panelAssistantTransportFactsJson(dependencies.facts()), ContentType.Application.Json)
}

/** Codes and facts only; the panel's pages and any client translate them. */
internal fun panelAssistantTransportFactsJson(facts: PanelAssistantTransportFacts): String =
    "{\"authority\":${Json.str(facts.authority)}," +
        "\"mqtt_discovery\":${Json.str(facts.mqttDiscovery)}," +
        "\"phase\":${Json.str(facts.phase.name.lowercase())}," +
        "\"refusal\":${facts.refusal?.let(Json::str) ?: "null"}}"
