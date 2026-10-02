package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.migration.BridgeRelease
import io.github.maxlyth.hapaneld.migration.IdentityMigrationSurface
import io.github.maxlyth.hapaneld.migration.ReleaseToken
import io.github.maxlyth.hapaneld.util.Json
import io.github.maxlyth.hapaneld.util.isLoopbackPeer
import io.github.maxlyth.hapaneld.security.SensitiveOperation
import io.ktor.server.application.ApplicationCall
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.origin
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/**
 * The bridge build's two application-id migration endpoints.
 *
 * `offer` installs and starts the successor now rather than on the next periodic pass; it installs
 * only this release's own successor asset under the pinned signer, like a managed component install.
 * `release` hands the panel to the successor. It answers before the handover happens, because the
 * HTTP server is part of what is handed over; the successor learns the outcome from the bridge's
 * retired marker, not from this reply. Every refusal leaves the panel exactly as it was.
 */
internal fun Route.identityMigrationRoutes(
    surface: IdentityMigrationSurface,
    authorize: suspend (ApplicationCall, SensitiveOperation, String, String) -> Boolean,
) {
    route("/api/v1/successor") {
        post("/offer") {
            val installedOnly = when (call.request.queryParameters.getAll("installed_only")) {
                null -> false
                listOf("1") -> true
                else -> {
                    call.respondText("""{"ok":false,"error":"invalid-installed-only"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                    return@post
                }
            }
            // An install like any other: under hardened security a LAN caller needs on-panel approval.
            if (!authorize(
                    call,
                    SensitiveOperation.APK_INSTALL,
                    "successor-offer",
                    "Install and start the app under its new application id",
                )
            ) return@post
            val outcome = (if (installedOnly) surface.offerInstalledOnly() else surface.offer())
                ?: return@post call.respondText(NOT_A_BRIDGE, ContentType.Application.Json, HttpStatusCode.NotFound)
            call.respondText(
                """{"ok":true,"outcome":${Json.str(outcome::class.simpleName.orEmpty())},"detail":${Json.str(outcome.detail)}}""",
                ContentType.Application.Json,
            )
        }
        post("/release") {
            val (status, body) = releaseReply(
                surface.release(
                    token = call.request.headers[ReleaseToken.HEADER],
                    loopback = isLoopbackPeer(call.request.origin.remoteAddress),
                ),
            )
            call.respondText(body, ContentType.Application.Json, status)
        }
    }
}

private const val NOT_A_BRIDGE = """{"ok":false,"error":"not-a-bridge"}"""

internal fun releaseReply(outcome: BridgeRelease.Outcome): Pair<HttpStatusCode, String> = when (outcome) {
    BridgeRelease.Outcome.Releasing -> HttpStatusCode.Accepted to """{"ok":true,"status":"releasing"}"""
    BridgeRelease.Outcome.AlreadyReleased -> HttpStatusCode.OK to """{"ok":true,"status":"released"}"""
    is BridgeRelease.Outcome.Refused -> {
        val status = when (outcome.refusal) {
            BridgeRelease.Refusal.NOT_A_BRIDGE -> HttpStatusCode.NotFound
            BridgeRelease.Refusal.NOT_LOOPBACK,
            BridgeRelease.Refusal.BAD_TOKEN,
            BridgeRelease.Refusal.UNTRUSTED_SUCCESSOR -> HttpStatusCode.Forbidden
            BridgeRelease.Refusal.HELPER_NOT_CONFIRMED,
            BridgeRelease.Refusal.QUIESCE_UNAVAILABLE,
            BridgeRelease.Refusal.MOVED_BY_PANEL_ASSISTANT -> HttpStatusCode.Conflict
        }
        val detail = outcome.detail?.let { ""","detail":${Json.str(it)}""" }.orEmpty()
        status to """{"ok":false,"error":${Json.str(outcome.refusal.code)}$detail}"""
    }
}
