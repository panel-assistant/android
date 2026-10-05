package io.panelassistant.android.http

import io.panelassistant.android.i18n.Strings as AppStrings
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText

/** Browser form failures get a localized, escaped mini-page; API callers retain the stable legacy token. */
internal suspend fun respondInstallFormError(
    call: ApplicationCall,
    strings: AppStrings,
    key: String,
    machineText: String,
    status: HttpStatusCode,
) {
    if (!installFormWantsHtml(call.request.headers["Accept"])) {
        call.respondText("$machineText\n", status = status)
        return
    }
    call.respondText(
        "<!doctype html><base href=\"/\"><meta charset=utf-8><body style='font-family:system-ui;background:#111;color:#eee;padding:20px'>" +
            esc(strings.get(key)) + "</body>",
        ContentType.Text.Html,
        status,
    )
}
