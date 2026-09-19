package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.control.HandBackHomeController
import io.github.maxlyth.hapaneld.control.HandBackHomePolicy
import io.github.maxlyth.hapaneld.security.SensitiveOperation
import io.github.maxlyth.hapaneld.util.AndroidInput
import io.github.maxlyth.hapaneld.util.Json
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The way out of a tamed panel.
 *
 * These two routes are deliberately in their own file rather than inline beside `post("/tame")`: the source
 * slice `TameReenableRouteTest` pins runs from `post("/tame")` to `get("/tame/suggest")` and would otherwise
 * swallow anything added between them, and an extracted `Route.xxxRoutes(dependencies)` is the only shape in
 * this codebase that a real `testApplication` can exercise end to end.
 */
internal data class HandBackHomeRouteDependencies(
    /** Packages this panel's device profile names as ones ha-paneld tames on this hardware. */
    val profileKnownPackages: () -> Set<String>,
    val handBack: (Set<String>) -> HandBackHomeController.Result,
    /** Record that something outside the app disabled `pkg`, so hand-back can reverse it later. */
    val recordTamed: (pkg: String) -> HandBackHomePolicy.RecordOutcome,
    val authorize: suspend (ApplicationCall, SensitiveOperation, String, String) -> Boolean =
        { _, _, _, _ -> true },
)


internal fun Route.handBackHomeRoutes(dependencies: HandBackHomeRouteDependencies) {
    route("/api/v1") {
        /**
         * Give the panel its own home screen back.
         *
         * Takes no parameters on purpose: what may be re-enabled is decided entirely from the panel's own
         * ownership records and device profile, never from anything a caller names. A caller who could name
         * packages could enable something ha-paneld never disabled, which is the one thing this must not do.
         */
        post("/hand-back-home") {
            if (!dependencies.authorize(
                    call,
                    SensitiveOperation.HOME_ROLE_HAND_BACK,
                    exactHttpApprovalPayload(call, ""),
                    "Hand the home screen back and stop being this panel's Home app",
                )
            ) return@post

            val profileKnown = withContext(Dispatchers.IO) { dependencies.profileKnownPackages() }
            when (val result = withContext(Dispatchers.IO) { dependencies.handBack(profileKnown) }) {
                is HandBackHomeController.Result.Refused -> call.respondText(
                    """{"ok":false,"error":${Json.str(refusalCode(result.reason))}}""",
                    ContentType.Application.Json,
                    // A refusal here is a panel state, not a malformed request: nothing was changed and the
                    // caller's next move is to fix the panel, so this is 409 rather than 400.
                    HttpStatusCode.Conflict,
                )
                is HandBackHomeController.Result.Done -> {
                    val outcome = result.outcome
                    call.respondText(
                        buildString {
                            append("""{"ok":""").append(outcome.complete)
                            append(""","restored":""").append(jsonArray(outcome.restored))
                            append(""","adopted":""").append(jsonArray(outcome.adopted))
                            append(""","outstanding":""").append(jsonArray(outcome.outstanding))
                            // Present only when the role genuinely moved, so a caller can key removal on the
                            // field existing rather than on parsing a Boolean it might misread.
                            outcome.homeHandedTo?.let {
                                append(""","home_handed_to":""").append(Json.str(it))
                            }
                            append("}")
                        },
                        ContentType.Application.Json,
                        HttpStatusCode.OK,
                    )
                }
            }
        }

        /**
         * Record that the host provisioner disabled a package over adb.
         *
         * `pm disable-user` run from a laptop leaves no trace on the panel, so without this the panel cannot
         * know which vendor packages it is entitled to hand back — and the vendor launcher is exactly the one
         * whose absence strands the device.
         */
        post("/tame/record") {
            val parameters = receiveBoundedFormParameters(call) ?: return@post
            val pkg = parameters["pkg"]?.trim().orEmpty()
            if (pkg.isEmpty() || !AndroidInput.isPackage(pkg)) {
                call.respondText(
                    """{"ok":false,"error":"bad-package"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
                return@post
            }
            // HOME_ROLE_HAND_BACK, not PACKAGE_TAME: PACKAGE_TAME is embed-exempt, and this route writes
            // the ownership record that decides what a later hand back re-enables and which launcher can
            // receive the HOME role. It carries the consequences of the hand-back, so it carries its gate.
            if (!dependencies.authorize(
                    call,
                    SensitiveOperation.HOME_ROLE_HAND_BACK,
                    exactHttpApprovalPayload(call, parameters.canonicalDigest()),
                    "Record that $pkg was disabled on this panel",
                )
            ) return@post

            val outcome = withContext(Dispatchers.IO) { dependencies.recordTamed(pkg) }
            val status = when (outcome) {
                HandBackHomePolicy.RecordOutcome.RECORDED -> HttpStatusCode.OK
                // Not an error the caller can fix by retrying differently: the panel simply will not claim a
                // package it cannot see was disabled by a user action.
                HandBackHomePolicy.RecordOutcome.NOT_DISABLED -> HttpStatusCode.Conflict
                HandBackHomePolicy.RecordOutcome.UNKNOWN -> HttpStatusCode.ServiceUnavailable
                HandBackHomePolicy.RecordOutcome.FAILED -> HttpStatusCode.ServiceUnavailable
            }
            call.respondText(
                """{"ok":${outcome == HandBackHomePolicy.RecordOutcome.RECORDED},""" +
                    """"result":${Json.str(outcome.name.lowercase().replace('_', '-'))}}""",
                ContentType.Application.Json,
                status,
            )
        }
    }
}

private fun refusalCode(reason: HandBackHomePolicy.Refusal): String = when (reason) {
    HandBackHomePolicy.Refusal.OWNERSHIP_UNREADABLE -> "ownership-unreadable"
    HandBackHomePolicy.Refusal.PACKAGE_STATE_UNKNOWN -> "package-state-unknown"
    HandBackHomePolicy.Refusal.NO_REPLACEMENT_HOME -> "no-replacement-home"
}

private fun jsonArray(values: List<String>): String =
    values.joinToString(prefix = "[", postfix = "]") { Json.str(it) }
