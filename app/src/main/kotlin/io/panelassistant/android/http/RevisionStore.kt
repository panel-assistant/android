package io.panelassistant.android.http

import io.panelassistant.android.config.ConfigBundle
import io.panelassistant.android.config.RevisionRing
import java.io.File

/**
 * On-panel config revision history: a ring buffer of timestamped config snapshots under
 * `filesDir/config-revisions/`, one `<id>.json` per revision. A snapshot is written on every
 * successful apply (single-field, bulk import, or restore) so a bad change can be rolled back
 * locally — even when the panel is isolated from fleet tooling. Eviction policy is the pure
 * [RevisionRing]; I/O failures are swallowed (history is best-effort, never blocks a config write).
 */
class RevisionStore(filesDir: File) {

    private val dir = File(filesDir, "config-revisions").apply { runCatching { mkdirs() } }

    private fun ids(): List<Long> =
        (dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.mapNotNull { it.name.removeSuffix(".json").toLongOrNull() }
            ?: emptyList()).sorted()

    /** Write [bundle] as a new revision (id = now, monotonic) and evict the oldest past the cap. */
    fun snapshot(bundle: ConfigBundle): Long {
        val existing = ids()
        val id = maxOf(System.currentTimeMillis(), (existing.maxOrNull() ?: 0L) + 1)
        runCatching {
            File(dir, "$id.json").writeText(bundle.serialize())
            RevisionRing.toEvict(existing).forEach { File(dir, "$it.json").delete() }
        }
        return id
    }

    /** All revisions, newest first. */
    fun list(): List<Pair<Long, ConfigBundle>> =
        ids().sortedDescending().mapNotNull { id ->
            runCatching { ConfigBundle.parse(File(dir, "$id.json").readText())?.let { id to it } }.getOrNull()
        }

    fun get(id: Long): ConfigBundle? =
        runCatching { ConfigBundle.parse(File(dir, "$id.json").readText()) }.getOrNull()
}
