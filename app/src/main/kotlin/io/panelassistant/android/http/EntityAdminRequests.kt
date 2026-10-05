package io.panelassistant.android.http

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import org.json.JSONObject

internal const val MAX_ENTITY_ADMIN_BODY_BYTES = 256L * 1024L

/** Entity administration requests are tiny control messages. Bound both declared and chunked bodies
 *  before materializing JSON so a LAN client cannot exhaust a panel's heap. */
internal suspend fun receiveEntityAdminJson(call: ApplicationCall, allowBlank: Boolean = false): JSONObject? {
    val bytes = when (val receipt = receiveBoundedBody(call, MAX_ENTITY_ADMIN_BODY_BYTES)) {
        is BoundedBodyReceipt.Received -> receipt.bytes
        BoundedBodyReceipt.TooLarge -> {
            call.respondText("request too large\n", status = HttpStatusCode.PayloadTooLarge)
            return null
        }
        BoundedBodyReceipt.TimedOut -> {
            call.respondText("request timeout\n", status = HttpStatusCode.RequestTimeout)
            return null
        }
    }
    val text = String(bytes, Charsets.UTF_8)
    return try {
        JSONObject(if (allowBlank && text.isBlank()) "{}" else text)
    } catch (_: Throwable) {
        call.respondText("invalid JSON\n", status = HttpStatusCode.BadRequest)
        null
    }
}
