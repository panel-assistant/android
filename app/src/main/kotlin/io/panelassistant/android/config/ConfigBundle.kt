package io.panelassistant.android.config

import io.panelassistant.android.util.Json
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/**
 * A versioned, self-describing config document for export / import / revision snapshots.
 * [serialize] hand-builds compact JSON with sorted `values` keys and a fixed top-level field order
 * so its output is stable and diffable — and byte-exact, because callers hash it (revision digests).
 * [parse] reads that shape back with `org.json`; it is deliberately strict (string-typed `kind` /
 * `exported_at` / `exported_by`, a numeric `schema`, and a `values` object of strings) so a snapshot
 * written by [serialize] round-trips losslessly and any other type — or trailing input — yields null.
 */
data class ConfigBundle(
    val kind: String,
    val schema: Int,
    val exportedAt: String,
    val exportedBy: String,
    val values: Map<String, String>,
) {
    /** Compact JSON; `values` rendered with sorted keys for stable, diffable output. */
    fun serialize(): String {
        val sb = StringBuilder()
        sb.append("{\"kind\":\"").append(esc(kind)).append("\",")
        sb.append("\"schema\":").append(schema).append(',')
        sb.append("\"exported_at\":\"").append(esc(exportedAt)).append("\",")
        sb.append("\"exported_by\":\"").append(esc(exportedBy)).append("\",")
        sb.append("\"values\":{")
        values.entries.sortedBy { it.key }.forEachIndexed { i, (k, v) ->
            if (i > 0) sb.append(',')
            sb.append('"').append(esc(k)).append("\":\"").append(esc(v)).append('"')
        }
        sb.append("}}")
        return sb.toString()
    }

    companion object {
        const val KIND_CONFIG = "ha-paneld-config"
        const val KIND_REVISION = "ha-paneld-revision"

        fun fromValues(
            values: Map<String, String>,
            kind: String = KIND_CONFIG,
            schema: Int = SettingsRegistry.SCHEMA,
            exportedAt: String = "",
            exportedBy: String = "",
        ) = ConfigBundle(kind, schema, exportedAt, exportedBy, values)

        /**
         * Parse the shape produced by [serialize] with `org.json`. Returns null on any malformed input.
         * Strict where [serialize] is: `kind` / `exported_at` / `exported_by` must be JSON strings (absent
         * defaults to ""), `schema` a JSON number (absent defaults to 0), and `values` a JSON object whose
         * every value is a string; a wrong type — or trailing input after the object — makes the parse fail.
         * Unknown top-level keys are ignored.
         */
        fun parse(json: String): ConfigBundle? = runCatching {
            val tokener = JSONTokener(json)
            val obj = tokener.nextValue() as JSONObject
            // Reject trailing input: nextClean() skips whitespace and returns NUL only at end-of-input.
            if (tokener.nextClean().code != 0) throw JSONException("trailing input")

            val values = LinkedHashMap<String, String>()
            if (obj.has("values")) {
                val vo = obj.get("values")
                if (vo !is JSONObject) throw JSONException("values is not an object")
                for (k in vo.keys()) {
                    val v = vo.get(k)
                    if (v !is String) throw JSONException("values[$k] is not a string")
                    values[k] = v
                }
            }
            ConfigBundle(
                kind = str(obj, "kind"),
                schema = if (obj.has("schema")) num(obj, "schema") else 0,
                exportedAt = str(obj, "exported_at"),
                exportedBy = str(obj, "exported_by"),
                values = values,
            )
        }.getOrNull()

        /** Present-and-string → the value; absent → ""; present-and-other-type → throws (parse fails). */
        private fun str(obj: JSONObject, key: String): String {
            if (!obj.has(key)) return ""
            val v = obj.get(key)
            if (v !is String) throw JSONException("$key is not a string")
            return v
        }

        /** Present-and-number → its Int value (truncating); present-and-other-type → throws (parse fails). */
        private fun num(obj: JSONObject, key: String): Int {
            val v = obj.get(key)
            if (v !is Number) throw JSONException("$key is not a number")
            return v.toInt()
        }

        private fun esc(s: String): String = Json.esc(s)
    }
}
