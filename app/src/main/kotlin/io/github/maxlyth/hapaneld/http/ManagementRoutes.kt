package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.storage.StorageHealthSnapshot
import io.github.maxlyth.hapaneld.util.PanelAssistantUpdateLease
import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Passive diagnostics and status share the existing active-read guard for explicit refreshes. */
internal fun Route.managementRoutes(
    diagnostics: () -> String,
    status: (StorageHealthSnapshot, String?) -> String,
    onUpdateOwner: () -> Unit,
    admitActiveRead: suspend (ApplicationCall) -> Boolean,
    refreshUpdates: suspend () -> Unit,
    refreshStorage: suspend () -> StorageHealthSnapshot?,
    cachedStorage: () -> StorageHealthSnapshot,
) {
    get("/diag") {
        call.respondText(
            withContext(Dispatchers.IO) { diagnostics() },
            ContentType.Text.Plain,
        )
    }
    // Health + capabilities as JSON (warnings as ready-to-render HTML) — feeds every
    // variant's Install/health section client-side. ?refresh=1 forces both the GitHub
    // update check and a serialized SQLite observation for this exact response.
    get("/status") {
        // Only the exact agreed value counts; it hides one MQTT entity and grants nothing.
        if (PanelAssistantUpdateLease.declares(call.request.headers[PanelAssistantUpdateLease.HEADER])) {
            onUpdateOwner()
        }
        val updateRefreshRequested = call.request.queryParameters["refresh"] == "1"
        val observationNonce = call.request.queryParameters["database_observation_nonce"]
        val refreshRequested = updateRefreshRequested || observationNonce != null
        if (refreshRequested && !admitActiveRead(call)) return@get
        val statusStorage = withContext(Dispatchers.IO) {
            refreshedStatusStorage(
                refreshRequested = refreshRequested,
                refreshUpdates = {
                    if (updateRefreshRequested) runCatching {
                        refreshUpdates()
                    }
                },
                refreshStorage = refreshStorage,
                cachedStorage = cachedStorage,
            )
        }
        call.respondText(
            withContext(Dispatchers.IO) {
                status(
                    statusStorage.snapshot,
                    databaseObservationProof(refreshRequested, observationNonce, statusStorage),
                )
            },
            ContentType.Application.Json,
        )
    }
}
