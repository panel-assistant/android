package io.panelassistant.android.sensors

import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** Parses the offset-bearing ISO-8601 timestamps returned by Home Assistant on every Android API. */
internal fun parseHaTimestampEpochMs(raw: String): Long? {
    if (raw.isBlank()) return null
    return try {
        OffsetDateTime.parse(raw, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant().toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }
}
