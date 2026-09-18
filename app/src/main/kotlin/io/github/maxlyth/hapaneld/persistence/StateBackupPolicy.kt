package io.github.maxlyth.hapaneld.persistence

import io.github.maxlyth.hapaneld.util.Json

/**
 * Which durable `app_state` rows a backup carries, and which of them a restore may write back.
 *
 * The backup archive used to derive its configuration from `SettingsRegistry.settable()` — a whitelist of
 * declared settings. That captures settings well and everything else not at all: any namespace the app
 * persists but never declares as a setting was silently absent from every backup, and adding one in future
 * would be silently absent too. Measured on a real panel, the `config` namespace was 59 of 76 `app_state`
 * rows; the other 17 were invisible.
 *
 * So the archive now carries a complete dump of the table, and this policy decides what a *restore* is
 * allowed to do with it. Completeness and restorability are deliberately separate questions: the record
 * should be whole even where writing it back would be wrong.
 *
 * Every namespace must be classified. An unclassified one is not restorable, and
 * `StateBackupPolicyTest` fails the build rather than letting a new namespace inherit that silently.
 */
object StateBackupPolicy {
    enum class Disposition {
        /**
         * Restored by the manifest's own validated path (the settings registry, the profile catalog).
         * Writing these rows raw as well would put two authorities on one key and skip validation.
         */
        MANIFEST_OWNED,

        /**
         * Describes the hardware or environment of the panel that wrote it — prior device audio state,
         * sensor calibration, a learned sleep model, a consent grant. Valuable across a reinstall of the
         * same panel, wrong on a different one, so it returns only to the panel of origin.
         */
        DEVICE_LOCAL,

        /** Rebuilt at runtime. Restoring a stale copy is worse than starting clean. */
        TRANSIENT,
    }

    private val DISPOSITIONS: Map<String, Disposition> = mapOf(
        // Already in the manifest, through paths that validate and reconfigure.
        "config" to Disposition.MANIFEST_OWNED,
        "device-profiles" to Disposition.MANIFEST_OWNED,

        // Same panel only.
        "controller-state" to Disposition.DEVICE_LOCAL,
        "auto-sleep-learning" to Disposition.DEVICE_LOCAL,
        "profile-calibration" to Disposition.DEVICE_LOCAL,
        "performance-binding" to Disposition.DEVICE_LOCAL,
        "shizuku-consent" to Disposition.DEVICE_LOCAL,
        "power-safety-acknowledgement" to Disposition.DEVICE_LOCAL,
        // Rolling Wi-Fi outage counts describe this panel's mounting position and network
        // environment — meaningful across a reinstall of the same panel, wrong on any other.
        "wifi-stability" to Disposition.DEVICE_LOCAL,

        // Runtime scratch: a manual brightness override in progress, the live-setting journal, and
        // crash-loop counters whose whole purpose is to describe *this* boot.
        "auto-brightness-runtime" to Disposition.TRANSIENT,
        "live-setting-journal" to Disposition.TRANSIENT,
        "startup-recovery" to Disposition.TRANSIENT,
    )

    val KNOWN_NAMESPACES: Set<String> get() = DISPOSITIONS.keys

    fun disposition(namespace: String): Disposition? = DISPOSITIONS[namespace]

    /**
     * Whether an archive was written by the panel it is being restored to, which is the one condition
     * under which [Disposition.DEVICE_LOCAL] rows may return.
     *
     * An ordinary restore proves it by `panel_id`, exactly as it always has, and ignores the discovery
     * id. A migration-mode restore is the application-id handover: the successor has not adopted the
     * panel id yet, so that comparison would always fail, and it proves the device by the discovery
     * pseudonym alone. A matching panel id is deliberately not an alternative there, because two
     * devices can be given the same name and only one of them wrote the archive.
     */
    fun sameDevice(migrationRestore: Boolean, panelIdMatches: Boolean, discoveryIdMatches: Boolean): Boolean =
        if (migrationRestore) discoveryIdMatches else panelIdMatches

    /**
     * The rows a restore may apply. [samePanel] is [sameDevice]'s answer, which is what makes
     * [Disposition.DEVICE_LOCAL] safe: the common real case is reinstalling onto the panel that
     * produced the backup.
     *
     * Unknown namespaces are withheld. Withholding a row loses a recoverable convenience; writing an
     * unreviewed one can strand hardware state, so the conservative direction is the correct default.
     */
    fun restorableRows(rows: List<ConfigVault.StateRow>, samePanel: Boolean): List<ConfigVault.StateRow> =
        rows.filter { row ->
            when (disposition(row.namespace)) {
                Disposition.DEVICE_LOCAL -> samePanel
                Disposition.MANIFEST_OWNED, Disposition.TRANSIENT, null -> false
            }
        }
}

/**
 * Whether a backup archive carries the panel's durable `app_state`, and what a reader may conclude when
 * it does not.
 *
 * The archive used to answer only "are there rows?", which conflated two opposite facts: a panel that had
 * nothing stored, and a panel whose database could not be read. Both produced a bundle with no `state`
 * section, so a damaged archive was indistinguishable from a complete one and restore reported success
 * having brought back nothing. A capture failure is now written into the manifest, and every reader of an
 * archive resolves the section through [restoreStateDisposition] instead of testing for an entry and
 * falling through to silence.
 *
 * A genuinely empty panel keeps the historical shape: no `state` section at all. That is also what every
 * pre-marker archive carries, so old bundles read back exactly as before.
 */
object StateArchiveSection {
    /**
     * The only value ever written to `state.error`. A fixed code, never the throwable's message: the
     * manifest is bounded and shared, and SQLite failure text carries filesystem paths.
     */
    const val CAPTURE_FAILED = "capture-failed"

    private const val MAX_ERROR_CHARS = 64

    /** What a reader may do with an archive's `state` section. */
    enum class Disposition {
        /** No section: the panel that wrote this archive had no stored rows, or predates the marker. */
        ABSENT,

        /** A payload entry is declared and may be read back. */
        RESTORABLE,

        /** The writer could not read its own database. The archive is incomplete and says so. */
        INCOMPLETE,
    }

    /**
     * The manifest's `state` value, or null when there is nothing to say. A failed capture and a captured
     * [entry] are mutually exclusive by construction: a read that failed produces no payload to point at.
     */
    fun manifestFragment(entry: String, bytes: Long?, rows: Int, captureFailed: Boolean): String? {
        require(!(captureFailed && bytes != null)) { "a failed app_state capture cannot declare a payload" }
        if (captureFailed) return "{\"error\":${Json.str(CAPTURE_FAILED)}}"
        if (bytes == null) return null
        return "{\"entry\":${Json.str(entry)},\"size\":$bytes,\"rows\":$rows}"
    }

    /**
     * Classify an archive's `state` section, or null when it is malformed and the restore must be refused.
     * Malformed means a section claiming neither a payload nor a failure, or claiming both: a writer emits
     * exactly one, so anything else is a truncated, hand-edited or substituted manifest and must not
     * resolve to the silent "nothing to restore" that this section exists to eliminate.
     */
    fun restoreStateDisposition(state: org.json.JSONObject?): Disposition? {
        if (state == null) return Disposition.ABSENT
        val error = state.opt("error")
        if (state.has("entry")) return if (error == null) Disposition.RESTORABLE else null
        val text = error as? String ?: return null
        if (text.isEmpty() || text.length > MAX_ERROR_CHARS) return null
        return Disposition.INCOMPLETE
    }
}
