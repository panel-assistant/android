package io.github.maxlyth.hapaneld.http

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.parseQueryString
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import org.json.JSONObject

/** Bound config mutations before Ktor or JSONObject materializes attacker-controlled form/JSON data. */
internal suspend fun receiveBoundedConfigParameters(
    call: ApplicationCall,
    maxBytes: Long = PaneldServer.MAX_CONFIG_POST_BODY_BYTES,
): Parameters? {
    val body = when (val receipt = receiveBoundedBody(call, maxBytes)) {
        is BoundedBodyReceipt.Received -> String(receipt.bytes, Charsets.UTF_8)
        BoundedBodyReceipt.TooLarge -> {
            call.respondText("request too large\n", status = HttpStatusCode.PayloadTooLarge)
            return null
        }
        BoundedBodyReceipt.TimedOut -> {
            call.respondText("request timeout\n", status = HttpStatusCode.RequestTimeout)
            return null
        }
    }
    return try {
        if (call.request.headers["Content-Type"].orEmpty().substringBefore(';').trim()
                .equals(ContentType.Application.Json.toString(), ignoreCase = true)
        ) {
            val json = JSONObject(body)
            Parameters.build {
                json.keys().forEach { key ->
                    val value = json.get(key)
                    require(value === JSONObject.NULL || value is String || value is Number || value is Boolean)
                    append(key, if (value === JSONObject.NULL) "" else value.toString())
                }
            }
        } else {
            parseQueryString(body)
        }
    } catch (_: Throwable) {
        call.respondText("invalid config body\n", status = HttpStatusCode.BadRequest)
        null
    }
}

/** Materialize the many small form-only control posts under one total-body limit. Ktor's default
 * receiveParameters limit is 50 MiB per field, which is disproportionate on low-memory wall panels. */
internal suspend fun receiveBoundedFormParameters(
    call: ApplicationCall,
    maxBytes: Long = PaneldServer.MAX_SMALL_FORM_POST_BODY_BYTES,
): Parameters? {
    val body = when (val receipt = receiveBoundedBody(call, maxBytes)) {
        is BoundedBodyReceipt.Received -> String(receipt.bytes, Charsets.UTF_8)
        BoundedBodyReceipt.TooLarge -> {
            call.respondText("request too large\n", status = HttpStatusCode.PayloadTooLarge)
            return null
        }
        BoundedBodyReceipt.TimedOut -> {
            call.respondText("request timeout\n", status = HttpStatusCode.RequestTimeout)
            return null
        }
    }
    return parseQueryString(body)
}
