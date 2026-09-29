package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.Config
import io.github.maxlyth.hapaneld.config.SettingsRegistry
import io.github.maxlyth.hapaneld.config.TamePackagePolicy
import io.github.maxlyth.hapaneld.config.Validation
import io.github.maxlyth.hapaneld.control.TameController
import io.github.maxlyth.hapaneld.device.TameCandidate
import io.github.maxlyth.hapaneld.i18n.Strings as AppStrings
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Vendor selection mutations; the shared reconcile authority retains execution and lifecycle ownership. */
internal class TameRoutes(
    private val config: Config,
    private val tame: TameController,
    private val tameProfileCandidates: List<TameCandidate>,
    private val requestTameReconcileAfterCommit: () -> Unit,
    private val snapInvalidate: () -> Unit,
    private val requestStrings: (ApplicationCall) -> AppStrings,
    private val localizedHref: (String, AppStrings) -> String,
    private val authorizeSensitive: suspend (ApplicationCall, SensitiveOperation, String, String) -> Boolean,
    private val respondInstallFormError: suspend (ApplicationCall, AppStrings, String, String, HttpStatusCode) -> Unit,
) {
    // Per-package vendor taming from the Vendor packages card. action=tame adds the package to
    // the blocklist and tames it now; action=untame explicitly enables it, then removes it from
    // the blocklist. The explicit enable also handles firmware-disabled packages which ha-paneld
    // never owned and therefore have no restoration marker. The work is privileged + slow, so it
    // runs off-thread and the browser gets a short auto-reload back to the Install card.
    suspend fun handle(call: ApplicationCall) {
        val strings = requestStrings(call)
        val returnTo = localizedHref("install#cfg-tame", strings)
        val p = receiveBoundedFormParameters(call) ?: return
        // One-click "Tame all recommended" (the profile's defaultTame set) — no pkg needed.
        // Persist the safe installed selection first. The one desired-state owner then converges
        // it; write-ahead overlay ownership makes an interrupted profile restart retryable.
        if (p["action"]?.trim() == "recommended") {
            val recommendedSelections = tame.recommendedSelections(tameProfileCandidates)
            val recommended = recommendedSelections.joinToString("\u0000")
            val digest = sha256Hex((p.canonicalDigest() + "\u0000" + recommended).toByteArray())
            if (!authorizeSensitive(
                    call,
                    SensitiveOperation.PACKAGE_TAME,
                    exactHttpApprovalPayload(call, digest),
                    strings.get("install.tame.approval.recommended"),
                )
            ) return
            val committed = withContext(Dispatchers.IO) {
                updateTameSelection { it.addAll(recommendedSelections) }
            }
            if (!committed) {
                respondInstallFormError(
                    call,
                    strings,
                    "install.tame.error.selection_commit",
                    "vendor selection commit failed",
                    HttpStatusCode.InternalServerError,
                )
                return
            }
            snapInvalidate()
            if (call.request.headers["Accept"]?.contains("application/json") == true) {
                call.respondText(
                    "{" +
                        "\"ok\":true,\"status\":\"started\",\"message\":" + jsonStr(strings.get("install.tame.result.applying_recommended")) + "," +
                        "\"return_to\":" + jsonStr(returnTo) + "}",
                    ContentType.Application.Json,
                )
            } else {
                call.respondText(
                    "<!doctype html><base href=\"/\"><meta charset=utf-8><meta http-equiv=refresh content='2;url=${esc(returnTo)}'>" +
                        "<body style='font-family:system-ui;background:#111;color:#eee;padding:20px'>" +
                        esc(strings.get("install.tame.result.applying_recommended_progress")) + "</body>",
                    ContentType.Text.Html,
                )
            }
            return
        }
        val pkg = p["pkg"]?.trim().orEmpty()
        val untame = p["action"]?.trim() == "untame"
        // Re-enable is always allowed; taming is refused for protected packages (the brick-guard
        // — critical AOSP names, vendor-renamed persistent system services, launchers, the IME)
        // so a hand-typed package name can't disable something the panel needs.
        if (!AndroidInput.isPackage(pkg) || (!untame && tame.isProtected(pkg))) {
            respondInstallFormError(
                call,
                strings,
                "install.tame.error.invalid_or_protected",
                "invalid or protected package",
                HttpStatusCode.BadRequest,
            )
            return
        }
        if (!authorizeSensitive(
                call,
                SensitiveOperation.PACKAGE_TAME,
                exactHttpApprovalPayload(call, p.canonicalDigest()),
                formattedString(
                    strings,
                    "install.tame.approval.package",
                    "action" to strings.get(if (untame) "install.tame.action.reenable" else "install.tame.action.tame"),
                    "package" to pkg,
                ),
            )
        ) return
        if (untame && !withContext(Dispatchers.IO) { tame.reenable(pkg) }) {
            respondInstallFormError(
                call,
                strings,
                "install.tame.error.reenable_failed",
                "could not re-enable package",
                HttpStatusCode.ServiceUnavailable,
            )
            return
        }
        val committed = withContext(Dispatchers.IO) {
            updateTameSelection { selected ->
                if (untame) selected.remove(pkg) else selected.add(pkg)
            }
        }
        if (!committed) {
            respondInstallFormError(
                call,
                strings,
                "install.tame.error.selection_commit",
                "vendor selection commit failed",
                HttpStatusCode.InternalServerError,
            )
            return
        }
        snapInvalidate()
        val result = formattedString(
            strings,
            if (untame) "install.tame.result.reenabling" else "install.tame.result.taming",
            "package" to pkg,
        )
        if (call.request.headers["Accept"]?.contains("application/json") == true) {
            call.respondText(
                "{" +
                    "\"ok\":true,\"status\":\"started\",\"message\":" + jsonStr(result) + "," +
                    "\"return_to\":" + jsonStr(returnTo) + "}",
                ContentType.Application.Json,
            )
        } else {
            call.respondText(
                "<!doctype html><base href=\"/\"><meta charset=utf-8><meta http-equiv=refresh content='2;url=${esc(returnTo)}'>" +
                    "<body style='font-family:system-ui;background:#111;color:#eee;padding:20px'>" +
                    esc(result) + "</body>",
                ContentType.Text.Html,
            )
        }
    }

    /** Atomically update the desired selection and notify its owner before releasing config commit order. */
    private fun updateTameSelection(update: (MutableSet<String>) -> Unit): Boolean =
        config.synchronizedTransaction {
            val selected = config.tameVendorPackages.toCollection(LinkedHashSet())
            update(selected)
            val normalized = when (val validation = TamePackagePolicy.normalize(selected.joinToString(" "))) {
                is Validation.Ok -> validation.normalized
                is Validation.Bad -> return@synchronizedTransaction false
            }
            if (normalized == config.tameVendorPackages.joinToString(" ")) {
                requestTameReconcileAfterCommit()
                return@synchronizedTransaction true
            }
            val spec = requireNotNull(SettingsRegistry.spec("tame_vendor_packages"))
            val editor = config.editor()
            config.stage(editor, spec, normalized)
            config.commit(editor, afterCommit = requestTameReconcileAfterCommit)
        }

    /** JSON-quote a string value (escapes backslash + double-quote). */
    private fun jsonStr(s: String): String = Json.str(s)
}

internal fun Route.tameRoutes(owner: () -> TameRoutes) {
    post("/tame") { owner().handle(call) }
}
