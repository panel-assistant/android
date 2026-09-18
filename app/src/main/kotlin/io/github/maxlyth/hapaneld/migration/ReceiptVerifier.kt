package io.github.maxlyth.hapaneld.migration

import io.github.maxlyth.hapaneld.backup.PanelBackup
import io.github.maxlyth.hapaneld.persistence.BackupIdentity
import io.github.maxlyth.hapaneld.persistence.StateArchiveSection
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * Decides whether the archive the successor pulled is fit to be the only copy of the panel's state.
 *
 * The legacy package is removed on the strength of this receipt, so "the download finished" is not
 * evidence. The archive must be a readable ZIP whose every entry decompresses to its declared size, a
 * panel backup written on this device (the discovery pseudonym equals this app's own), carrying
 * configuration and captured `app_state` rows, with every payload its manifest declares present.
 *
 * An entity filter may legitimately be empty, on a panel that renders with an external app, so the
 * entity section must be present and whole rather than non-empty.
 */
internal object ReceiptVerifier {
    private const val MAX_MANIFEST_BYTES = 1L * 1024L * 1024L

    /** Null when the receipt is acceptable, otherwise the first reason it is not. */
    fun refusal(archive: File, ownDiscoveryId: String?): String? {
        if (!archive.isFile || archive.length() <= 0L) return "receipt is missing"
        val entries = runCatching { readableEntries(archive) }.getOrNull() ?: return "receipt is not a readable archive"
        val manifest = PanelBackup.readManifest(archive, MAX_MANIFEST_BYTES)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return "receipt has no manifest"
        if (manifest.optString("kind") != "ha-paneld-backup") return "receipt is not a panel backup"
        if (!BackupIdentity.sameDevice(manifest, ownDiscoveryId)) return "receipt was not written on this device"
        if ((manifest.optJSONObject("config")?.length() ?: 0) == 0) return "receipt carries no configuration"

        val state = manifest.optJSONObject("state")
        if (StateArchiveSection.restoreStateDisposition(state) != StateArchiveSection.Disposition.RESTORABLE) {
            return "receipt carries no panel state"
        }
        if (requireNotNull(state).optInt("rows", 0) <= 0) return "receipt carries no panel state rows"

        val entity = manifest.optJSONObject("entity_state") ?: return "receipt carries no entity state"
        val declared = buildList {
            add(state.optString("entry"))
            add(entity.optString("filter_ids_entry"))
            add(entity.optString("overrides_entry"))
            manifest.optJSONObject("profiles")?.optString("entry")?.let(::add)
        }
        if (declared.any(String::isEmpty)) return "receipt manifest does not name its payloads"
        declared.firstOrNull { it !in entries }?.let { return "receipt is missing $it" }
        return null
    }

    /** Whether the verified receipt records that the legacy app was connected to MQTT when it wrote it. */
    fun legacyMqttConnected(archive: File): Boolean = runCatching {
        PanelBackup.readManifest(archive, MAX_MANIFEST_BYTES)?.let { BackupIdentity.writerMqttConnected(JSONObject(it)) }
    }.getOrNull() ?: true

    /**
     * Every entry name, after proving the archive whole twice over. `ZipFile` requires an intact central
     * directory, which a truncated download does not have, but never checks an entry's CRC; the
     * streaming reader checks every CRC as it closes the entry, but never looks at the directory. The
     * two listings must also agree, so bytes appended to or missing from either view are refused.
     */
    private fun readableEntries(archive: File): Set<String> {
        val listed = ZipFile(archive).use { zip -> zip.entries().asSequence().map { it.name }.toSet() }
        val streamed = LinkedHashSet<String>()
        val buffer = ByteArray(64 * 1024)
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                while (zip.read(buffer) >= 0) Unit
                zip.closeEntry()
                streamed += entry.name
            }
        }
        require(listed == streamed) { "archive directory and contents disagree" }
        return streamed
    }
}
