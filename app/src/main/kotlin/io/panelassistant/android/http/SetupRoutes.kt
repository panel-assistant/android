package io.panelassistant.android.http

import io.panelassistant.android.GuidedSetupPresence
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

internal fun Route.setupRoutes(state: () -> SetupState) {
    post("/setup/attest") {
        state().attest()
        call.respondText("{\"ok\":true}", ContentType.Application.Json)
    }
    post("/setup/identity") {
        state().confirmIdentity()
        call.respondText("{\"ok\":true}", ContentType.Application.Json)
    }
    post("/setup/home-dashboard") {
        state().chooseHomeDashboard()
        call.respondText("{\"ok\":true}", ContentType.Application.Json)
    }
    post("/setup/entity-filter") {
        state().answerEntityFilter()
        call.respondText("{\"ok\":true}", ContentType.Application.Json)
    }
    get("/setup") {
        // Deliberately NOT part of /api/v1/status: that endpoint's inputs are
        // root/daemon-backed stale-while-revalidate reads, so a wizard polling every couple
        // of seconds would keep kicking off privileged refreshes for data it never uses.
        // Everything here is an in-memory read, and SetupStateEndpointContractTest pins it.
        // Generic state readers (provisioning, diagnostics, monitoring) must not suppress
        // recovery restarts. Only the wizard UI sends this explicit heartbeat header.
        if (call.request.headers[SETUP_PRESENCE_HEADER] == SETUP_PRESENCE_ACTIVE) {
            GuidedSetupPresence.noteHeartbeat(android.os.SystemClock.elapsedRealtime())
        }
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        call.respondText(state().setupJourneyJson(), ContentType.Application.Json)
    }
}
