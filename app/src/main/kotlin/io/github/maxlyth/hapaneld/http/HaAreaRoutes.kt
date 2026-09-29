package io.github.maxlyth.hapaneld.http

import io.ktor.http.ContentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

internal fun Route.haAreaRoutes(areaJson: suspend () -> String) {
    get("/config/ha-area") {
        call.respondText(areaJson(), ContentType.Application.Json)
    }
}
