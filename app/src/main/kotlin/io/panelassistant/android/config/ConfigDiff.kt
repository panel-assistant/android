package io.panelassistant.android.config

/**
 * Pure dry-run diff between the current config and a candidate set of values. Powers the
 * `?dry_run=1` preview on bundle import and the per-revision change summary.
 */
object ConfigDiff {

    /** One field that would change. [from] is null when the key has no current value. */
    data class Change(val key: String, val from: String?, val to: String)

    /** Changes needed to take [current] to [candidate] (keys present in candidate with a differing value). */
    fun diff(current: Map<String, String>, candidate: Map<String, String>): List<Change> =
        candidate.entries
            .filter { (k, v) -> current[k] != v }
            .map { (k, v) -> Change(k, current[k], v) }
            .sortedBy { it.key }
}
