package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.Config
import io.github.maxlyth.hapaneld.control.PowerRepairCapability
import io.github.maxlyth.hapaneld.control.PowerSafetyAcknowledgementDecision
import io.github.maxlyth.hapaneld.control.PowerSafetyAdvisory
import io.github.maxlyth.hapaneld.control.PowerSafetyAdvisoryPolicy
import io.github.maxlyth.hapaneld.control.PowerSafetyAssessment
import io.github.maxlyth.hapaneld.control.PowerSafetyRepairResult
import io.github.maxlyth.hapaneld.security.SensitiveOperation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal fun Route.powerSafetyRoutes(
    config: Config,
    powerSafety: () -> PowerSafetyAssessment,
    powerSafetyAdvisory: () -> PowerSafetyAdvisory,
    onRepairPowerSafety: () -> PowerSafetyRepairResult,
    freshPowerSafetyRepairCapability: () -> PowerRepairCapability,
    snapInvalidate: () -> Unit,
    authorizeSensitive: suspend (ApplicationCall, SensitiveOperation, String, String) -> Boolean,
) {
    get("/power-safety") {
        val advisory = powerSafetyAdvisory()
        call.respondText(
            PowerSafetyPresentation.json(advisory),
            ContentType.Application.Json,
        )
    }
    get("/power-safety/state") {
        call.respondText(powerSafety().level.wireValue + "\n", ContentType.Text.Plain)
    }
    post("/power-safety/repair") {
        if (!authorizeSensitive(
                call,
                SensitiveOperation.POWER_CONFIGURATION,
                exactHttpApprovalPayload(call, sha256Hex(ByteArray(0))),
                "Enable and verify panel power-safety guards",
            )
        ) return@post
        val result = withContext(Dispatchers.IO) { onRepairPowerSafety() }
        snapInvalidate()
        val repairCapability = PowerRepairCapability.values()
            .firstOrNull { it.wireValue == result.privilegedPowerControl }
            ?: PowerRepairCapability.DEGRADED
        val advisory = PowerSafetyAdvisoryPolicy.evaluate(
            result.assessment,
            repairCapability,
            config.powerSafetyAcknowledgementFingerprint,
        )
        val failed = result.status == "failed"
        val wantsJson = call.request.headers[HttpHeaders.Accept]
            ?.contains("application/json", ignoreCase = true) == true
        if (wantsJson) {
            call.respondText(
                PowerSafetyPresentation.repairJson(result, advisory),
                ContentType.Application.Json,
                if (failed) HttpStatusCode.ServiceUnavailable else HttpStatusCode.OK,
            )
        } else {
            val message = PowerSafetyPresentation.repairMessage(result)
            call.respondText(
                configMutationHtml(message).replace(
                    "url=configure",
                    "url=configure#cfg-keep_awake",
                ),
                ContentType.Text.Html,
                if (failed) HttpStatusCode.ServiceUnavailable else HttpStatusCode.OK,
            )
        }
    }
    post("/power-safety/acknowledge") {
        val parameters = receiveBoundedFormParameters(call) ?: return@post
        val requested = parameters["fingerprint"]?.trim().orEmpty()
        if (!PowerSafetyAdvisoryPolicy.isAcknowledgementFingerprint(requested)) {
            call.respondText(
                """{"ok":false,"acknowledged":false,"error":"invalid-fingerprint","message":"The acknowledgement token is invalid; refresh and review the current caution."}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
            return@post
        }
        if (!authorizeSensitive(
                call,
                SensitiveOperation.POWER_SAFETY_ACKNOWLEDGEMENT,
                exactHttpApprovalPayload(call, parameters.canonicalDigest()),
                "Hide one exact unchanged panel power-safety caution",
            )
        ) return@post
        // Re-observe after the request is materialized. The submitted value is only an
        // expected-state token; persisted truth always comes from this server observation.
        val current = withContext(Dispatchers.IO) {
            PowerSafetyAdvisoryPolicy.evaluate(
                powerSafety(),
                freshPowerSafetyRepairCapability(),
                config.powerSafetyAcknowledgementFingerprint,
            )
        }
        val decision = PowerSafetyAdvisoryPolicy.admitAcknowledgement(requested, current)
        val wantsJson = call.request.headers[HttpHeaders.Accept]
            ?.contains("application/json", ignoreCase = true) == true
        val (status, error, message) = when (decision) {
            PowerSafetyAcknowledgementDecision.MALFORMED -> Triple(
                HttpStatusCode.BadRequest,
                "invalid-fingerprint",
                "The acknowledgement token is invalid; refresh and review the current caution.",
            )
            PowerSafetyAcknowledgementDecision.STALE -> Triple(
                HttpStatusCode.Conflict,
                "stale-assessment",
                "Power-safety evidence changed; review the current caution before hiding it.",
            )
            PowerSafetyAcknowledgementDecision.NOT_ACKNOWLEDGEABLE -> Triple(
                HttpStatusCode.Conflict,
                "not-acknowledgeable",
                "This power-safety state cannot be hidden because repair is available or risk is elevated or unknown.",
            )
            PowerSafetyAcknowledgementDecision.ACCEPT -> {
                val fingerprint = requireNotNull(current.acknowledgementFingerprint)
                if (config.commitPowerSafetyAcknowledgement(fingerprint)) {
                    Triple(HttpStatusCode.OK, "", "This unchanged caution is hidden on panel web pages. Diagnostics and installer checks remain unchanged.")
                } else {
                    Triple(HttpStatusCode.ServiceUnavailable, "persistence-failed", "The caution was not hidden because the acknowledgement could not be saved.")
                }
            }
        }
        val acknowledged = decision == PowerSafetyAcknowledgementDecision.ACCEPT && status == HttpStatusCode.OK
        val projected = if (acknowledged) {
            PowerSafetyAdvisoryPolicy.evaluate(
                current.assessment,
                current.repairCapability,
                current.acknowledgementFingerprint,
            )
        } else current
        if (wantsJson) {
            call.respondText(
                JSONObject()
                    .put("ok", acknowledged)
                    .put("acknowledged", acknowledged)
                    .put("error", error.takeIf { it.isNotEmpty() } ?: JSONObject.NULL)
                    .put("message", message)
                    .put("power_safety", JSONObject(PowerSafetyPresentation.json(projected)))
                    .toString(),
                ContentType.Application.Json,
                status,
            )
        } else {
            call.respondText(
                configMutationHtml(message).replace("url=configure", "url=configure#cfg-keep_awake"),
                ContentType.Text.Html,
                status,
            )
        }
    }
}
