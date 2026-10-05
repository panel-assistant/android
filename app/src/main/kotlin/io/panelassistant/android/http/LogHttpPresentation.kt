package io.panelassistant.android.http

import io.panelassistant.android.logship.LogShipStatusProjection
import io.panelassistant.android.util.Json

/** One complete log entry, even when its message has embedded newlines, is one SSE event. */
internal fun logSseEvent(entry: String): String =
    entry.lineSequence().joinToString(separator = "\n", postfix = "\n\n") { "data: $it" }

internal fun logShipStatusJson(status: LogShipStatusProjection): String =
    "{\"enabled\":${status.enabled},\"configured\":${status.configured}," +
        "\"text\":${Json.str(status.text)}}"
