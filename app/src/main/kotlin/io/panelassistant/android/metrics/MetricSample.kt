package io.panelassistant.android.metrics

/**
 * One normalized telemetry reading: a named, timestamped, typed value. This is the shape an on-device
 * history store (SQLite, JSON ring) would persist directly — [key], [num]/[text], [unit], [ts] map 1:1
 * onto a `(key TEXT, num REAL, text TEXT, unit TEXT, ts INTEGER)` row, so a future store never has to
 * re-derive structure from a per-consumer JSON blob. Numeric metrics set [num] (usually with a [unit]);
 * string metrics (IP, governor, SELinux mode) set [text]. Exactly one of the two is populated.
 *
 * The type is deliberately dependency-free and store-agnostic: emitting it now is the single forward-compat
 * hook the metric-history store bolts onto later (see [MetricSink]). Names come from [MetricRegistry].
 */
data class MetricSample(
    val key: String,
    val ts: Long,
    val num: Double? = null,
    val text: String? = null,
    val unit: String? = null,
) {
    companion object {
        /** A numeric sample (e.g. cpu %, °C). */
        fun num(key: String, value: Double, ts: Long, unit: String? = null): MetricSample =
            MetricSample(key = key, ts = ts, num = value, unit = unit)

        /** A string sample (e.g. IP address, governor name, SELinux mode). */
        fun text(key: String, value: String, ts: Long): MetricSample =
            MetricSample(key = key, ts = ts, text = value)
    }
}
