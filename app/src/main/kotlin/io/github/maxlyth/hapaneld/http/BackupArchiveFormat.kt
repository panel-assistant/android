package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.DashboardEntityBackupState
import io.github.maxlyth.hapaneld.backup.PanelBackup
import io.github.maxlyth.hapaneld.backup.CompanionRestore
import io.github.maxlyth.hapaneld.config.Migrations
import io.github.maxlyth.hapaneld.dashboard.EntityFilterProtocol
import io.github.maxlyth.hapaneld.util.Json
import io.github.maxlyth.hapaneld.util.InstallPresentation

internal const val MAX_RESTORE_BYTES = 64L * 1024L * 1024L
internal const val MAX_COMPANION_BACKUP_BYTES = CompanionRestore.MAX_AGGREGATE_BYTES
// v2 keeps large profile/entity payloads in separately bounded entries, leaving only config and
// small ownership metadata here. This avoids one multi-tens-of-MiB String + JSONObject allocation.
internal const val MAX_BACKUP_MANIFEST_BYTES = 1L * 1024L * 1024L
internal const val MAX_PROFILE_BACKUP_ENTRY_BYTES = 9L * 1024L * 1024L
internal const val MAX_ENTITY_BACKUP_TEXT_BYTES = 13_000_000L
// Compatibility-only v1 JSON is multiply materialized by JSONObject; keep its heap exposure much
// smaller than the streamed/file-backed v2 manifest. New backups are always v2.
internal const val MAX_LEGACY_BACKUP_JSON_BYTES = 6L * 1024L * 1024L
internal const val BACKUP_STORAGE_MARGIN_BYTES = 64L * 1024L * 1024L
internal const val PROFILE_BACKUP_ENTRY = "profiles/catalog.json"

/** Configuration is tens of kilobytes on real panels; this is headroom, not a target. */
internal const val MAX_STATE_BACKUP_BYTES = 4L * 1024L * 1024L

internal data class ArchiveTextRef(
    val entry: String,
    val size: Long,
    val maxBytes: Long,
    val allowEmpty: Boolean,
)

internal fun archiveTextRef(
    obj: org.json.JSONObject,
    entryKey: String,
    sizeKey: String,
    expectedEntry: String,
    maxBytes: Long,
    allowEmpty: Boolean,
): ArchiveTextRef {
    val entry = obj.opt(entryKey) as? String ?: throw IllegalArgumentException("missing $entryKey")
    require(entry == expectedEntry) { "unexpected $entryKey" }
    val rawSize = obj.opt(sizeKey) as? Number ?: throw IllegalArgumentException("missing $sizeKey")
    val size = rawSize.toLong()
    require(rawSize.toDouble() == size.toDouble())
    val minimum = if (allowEmpty) 0L else 1L
    require(size in minimum..maxBytes)
    return ArchiveTextRef(entry, size, maxBytes, allowEmpty)
}

internal fun declaredArchiveEntries(
    entity: org.json.JSONObject?,
    profiles: org.json.JSONObject?,
    companion: org.json.JSONObject?,
    state: org.json.JSONObject?,
    wakeWords: org.json.JSONObject?,
): Set<String> {
    val entries = ArrayList<String>(8)
    wakeWords?.let { entries += io.github.maxlyth.hapaneld.backup.WakeWordBackup.declaredEntry(it) }
    if (state?.has("entry") == true) {
        entries += archiveTextRef(
            state,
            "entry",
            "size",
            STATE_BACKUP_ENTRY,
            MAX_STATE_BACKUP_BYTES,
            allowEmpty = false,
        ).entry
    }
    if (entity?.has("filter_ids_entry") == true || entity?.has("overrides_entry") == true) {
        entries += archiveTextRef(
            entity,
            "filter_ids_entry",
            "filter_ids_size",
            ENTITY_FILTER_BACKUP_ENTRY,
            MAX_ENTITY_BACKUP_TEXT_BYTES,
            allowEmpty = true,
        ).entry
        entries += archiveTextRef(
            entity,
            "overrides_entry",
            "overrides_size",
            ENTITY_OVERRIDES_BACKUP_ENTRY,
            MAX_ENTITY_BACKUP_TEXT_BYTES,
            allowEmpty = true,
        ).entry
    }
    if (profiles?.has("entry") == true) {
        entries += archiveTextRef(
            profiles,
            "entry",
            "size",
            PROFILE_BACKUP_ENTRY,
            MAX_PROFILE_BACKUP_ENTRY_BYTES,
            allowEmpty = false,
        ).entry
    }
    companion?.optJSONArray("files")?.let { files ->
        for (index in 0 until files.length()) {
            val file = files.optJSONObject(index)
                ?: throw IllegalArgumentException("invalid Companion file metadata")
            entries += (file.opt("entry") as? String)
                ?: throw IllegalArgumentException("missing Companion entry")
        }
    }
    require(entries.size < PanelBackup.MAX_ARCHIVE_ENTRIES)
    require(entries.toSet().size == entries.size)
    return entries.toSet()
}

internal fun invalidCompanionPayload(reason: String): CompanionRestore.PlanResult.Invalid =
    CompanionRestore.PlanResult.Invalid(reason, InstallPresentation("companion-payload-invalid"))

/** Validate + apply the config half of a backup (reuses the import apply path). Returns keys applied. */
internal data class RestoreConfigPlan(
    val values: Map<String, String>,
    val warnings: List<String>,
    val errors: List<String>,
)

internal fun entityBackupJson(state: DashboardEntityBackupState): String = buildString {
    append("{\"instance_key\":").append(Json.str(state.instanceKey))
    append(",\"instance_origin\":").append(Json.str(state.instanceOrigin))
    append(",\"instance_uuid\":").append(Json.str(state.instanceUuid))
    append(",\"dashboard_path\":").append(Json.str(state.dashboardPath))
    append(",\"filter_ids\":").append(Json.str(state.filterIds))
    append(",\"filter_enabled\":").append(state.filterEnabled)
    append(",\"filter_owner\":").append(Json.str(state.filterOwner))
    append(",\"learning_applied\":").append(state.learningApplied)
    append(",\"applied_owner\":").append(Json.str(state.appliedOwner))
    append(",\"overrides\":").append(Json.str(state.overrides))
    append(",\"override_owner\":").append(Json.str(state.overrideOwner))
    append('}')
}

internal fun entityBackupArchiveJson(
    state: DashboardEntityBackupState,
    filterBytes: Long,
    overrideBytes: Long,
): String = buildString {
    append("{\"instance_key\":").append(Json.str(state.instanceKey))
    append(",\"instance_origin\":").append(Json.str(state.instanceOrigin))
    append(",\"instance_uuid\":").append(Json.str(state.instanceUuid))
    append(",\"dashboard_path\":").append(Json.str(state.dashboardPath))
    append(",\"filter_ids_entry\":").append(Json.str(ENTITY_FILTER_BACKUP_ENTRY))
    append(",\"filter_ids_size\":").append(filterBytes)
    append(",\"filter_enabled\":").append(state.filterEnabled)
    append(",\"filter_owner\":").append(Json.str(state.filterOwner))
    append(",\"learning_applied\":").append(state.learningApplied)
    append(",\"applied_owner\":").append(Json.str(state.appliedOwner))
    append(",\"overrides_entry\":").append(Json.str(ENTITY_OVERRIDES_BACKUP_ENTRY))
    append(",\"overrides_size\":").append(overrideBytes)
    append(",\"override_owner\":").append(Json.str(state.overrideOwner))
    append('}')
}

internal fun planEntityBackup(obj: org.json.JSONObject): DashboardEntityBackupState {
    fun string(key: String, max: Int, allowNewline: Boolean = false): String {
        val value = obj.opt(key) as? String ?: throw IllegalArgumentException("$key must be a string")
        require(value.length <= max && value.none {
            it.code < 0x20 && !(allowNewline && it == '\n')
        }) { "$key is invalid" }
        return value
    }
    fun bool(key: String): Boolean = obj.opt(key) as? Boolean
        ?: throw IllegalArgumentException("$key must be boolean")
    val ids = EntityFilterProtocol.normalize(
        string("filter_ids", 13_000_000, allowNewline = true).lineSequence().toList(),
    )
        .joinToString("\n")
    val overrideLines = string("overrides", 13_000_000, allowNewline = true)
        .lineSequence().filter(String::isNotBlank).toList()
    val overrideIds = overrideLines.map { line ->
        require(line.firstOrNull() == '+' || line.firstOrNull() == '-') { "invalid override marker" }
        line.drop(1).trim()
    }
    EntityFilterProtocol.normalize(overrideIds)
    return DashboardEntityBackupState(
        instanceKey = string("instance_key", 256),
        instanceOrigin = string("instance_origin", 2_048),
        instanceUuid = string("instance_uuid", 256),
        dashboardPath = string("dashboard_path", 2_048),
        filterIds = ids,
        filterEnabled = bool("filter_enabled"),
        filterOwner = string("filter_owner", 2_560),
        learningApplied = bool("learning_applied"),
        appliedOwner = string("applied_owner", 2_560),
        overrides = overrideLines.sorted().joinToString("\n"),
        overrideOwner = string("override_owner", 2_560),
        // A restored archive is established state, never an in-flight first activation.
        initialActivationPending = false,
    )
}

/**
 * A stored value from an older archive, in the form the current validator can read.
 *
 * `home_dashboard` had no validator before this release, so a backup taken then can hold anything the
 * panel was given, including a whole URL. Validating it verbatim now fails, and because a restore is
 * all-or-nothing that one historical value makes the entire archive unrestorable — precisely when the
 * owner needs it. Canonicalizing first is the same rule the live store applies on upgrade, so an old
 * archive restores to exactly what saving it today would produce. A value that cannot be canonicalized
 * still fails, with its own reason.
 */

internal fun planRestoreConfig(
    cfgObj: org.json.JSONObject,
    schema: Int,
    currentHaOrigin: String?,
    zigbeeRouterConfigured: Boolean,
): RestoreConfigPlan {
    val raw = LinkedHashMap<String, String>()
    for (key in cfgObj.keys()) {
        val value = cfgObj.opt(key)
        if (value == null || value == org.json.JSONObject.NULL || value is org.json.JSONObject || value is org.json.JSONArray) {
            return RestoreConfigPlan(emptyMap(), emptyList(), listOf("$key: expected a scalar setting value"))
        }
        raw[key] = value.toString()
    }
    val (migrated, warnings) = Migrations.migrate(schema, raw)
    val decided = planRestoreSettings(migrated, currentHaOrigin)
    val accepted = LinkedHashMap(decided.accepted)
    val errors = ArrayList(decided.errors)
    val ownershipPreserved = preserveUnconfiguredZigbeeOwnership(
        accepted,
        zigbeeRouterConfigured,
    )
    if (accepted.isEmpty() && errors.isEmpty()) errors += "config object contains no restorable settings"
    return RestoreConfigPlan(
        accepted,
        buildList {
            addAll(warnings)
            if (ownershipPreserved) {
                add("legacy zigbee_router=false skipped to preserve untouched vendor gateway ownership")
            }
        },
        errors,
    )
}

internal const val ENTITY_FILTER_BACKUP_ENTRY = "entity/filter-ids.txt"
internal const val ENTITY_OVERRIDES_BACKUP_ENTRY = "entity/overrides.txt"

/**
 * The complete `app_state` dump. The manifest's `config` block is a projection of declared
 * settings, so it cannot represent a namespace that is not a setting; this entry is the whole
 * table, in the same flat-text codec the config vault uses.
 */
internal const val STATE_BACKUP_ENTRY = "state/app-state.txt"

internal val ENTITY_STATE_CONFIG_KEYS = setOf(
    "dashboard_entity_overrides",
    "dashboard_entity_learning_applied",
)
