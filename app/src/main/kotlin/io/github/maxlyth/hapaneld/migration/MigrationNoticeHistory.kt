package io.github.maxlyth.hapaneld.migration

import io.github.maxlyth.hapaneld.panelassistant.PanelAssistantTransportProtocol
import io.github.maxlyth.hapaneld.persistence.ConfigVault

/** Carry only positive notice history, never the legacy transport authority or update-owner lease. */
internal fun migrationNoticeHistoryRows(
    rows: List<ConfigVault.StateRow>,
    migrationRestore: Boolean,
    sameDevice: Boolean,
): List<ConfigVault.StateRow> {
    if (!migrationRestore || !sameDevice) return emptyList()

    val seenAt = rows.asSequence()
        .filter { row ->
            if (row.namespace != "config") return@filter false
            when (row.key) {
                "panel_assistant_authority" ->
                    row.type == "string" &&
                        row.valueText?.let(PanelAssistantTransportProtocol.AUTHORITIES::contains) == true
                "panel_assistant_update_owner_seen_ms" ->
                    row.type == "long" && (row.valueText?.toLongOrNull() ?: 0L) > 0L
                "migration_notice_connection_seen" -> row.type == "boolean" && row.valueText == "1"
                else -> false
            }
        }
        .map { it.updatedAt }
        .maxOrNull() ?: return emptyList()

    return listOf(ConfigVault.StateRow("config", "migration_notice_connection_seen", "boolean", "1", seenAt))
}
