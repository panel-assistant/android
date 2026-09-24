package io.github.maxlyth.hapaneld.device.profile

import io.github.maxlyth.hapaneld.device.DeviceProfile
import org.json.JSONArray
import org.json.JSONObject

/** Immutable identity of one profile revision. [revision] is the lowercase SHA-256 of its YAML. */
data class ProfileRef(val id: String, val revision: String)

/** Where a profile revision came from. Bundled revisions ship in the APK; imported revisions are local. */
enum class ProfileOrigin { BUNDLED, IMPORTED }

/** Author guidance only. Runtime Shizuku installation/readiness remains separate live state. */
enum class ShizukuRecommendation { NONE, OPTIONAL, RECOMMENDED }

enum class ProfileMaturity { DRAFT, EXPERIMENTAL, VERIFIED }

/** Automatic fingerprint matching, or an explicit immutable revision selected by the administrator. */
sealed interface ProfileSelection {
    data object Auto : ProfileSelection
    data class Pinned(val ref: ProfileRef) : ProfileSelection
}

/** Activation is restart-bound so every controller sees one coherent profile for its whole lifetime. */
enum class ProfileActivationPhase { ACTIVE, PENDING, APPLYING, ROLLED_BACK }

data class ProfileActivationState(
    val phase: ProfileActivationPhase,
    val generation: Long = 0,
    val previous: ProfileSelection? = null,
    val desired: ProfileSelection? = null,
    val message: String? = null,
    val presentation: ProfilePresentation? = null,
)

enum class ProfileIssueSeverity { INFO, WARNING, ERROR }

data class ProfileIssue(
    val severity: ProfileIssueSeverity,
    /** Stable schema-style path, for example `hardware.relay_base`. */
    val path: String,
    val message: String,
    val presentation: ProfilePresentation? = null,
)

/** Optional, additive browser-presentation metadata; compatibility prose remains authoritative. */
data class ProfilePresentation(
    val code: String,
    val params: Map<String, String> = emptyMap(),
) {
    init {
        require(code in SUPPORTED_CODES) { "Unknown profile presentation code." }
        require(params.keys == PARAMS_BY_CODE.getOrDefault(code, emptySet())) {
            "Profile presentation parameters do not match the code contract."
        }
        require(params.size <= MAX_PARAMS) { "Too many profile presentation parameters." }
        params.forEach { (name, value) ->
            require(PARAM.matches(name)) { "Invalid profile presentation parameter name." }
            require(value.length <= MAX_PARAM_LENGTH) { "Profile presentation parameter is too long." }
        }
    }

    companion object {
        private val PARAM = Regex("^[a-z][a-z0-9_]{0,31}$")
        private const val MAX_PARAMS = 8
        private const val MAX_PARAM_LENGTH = 512
        val SUPPORTED_CODES: Set<String> = setOf(
            "preview-token-required",
            "explicit-confirmation-required",
            "expected-catalog-revision-required",
            "invalid-profile-ref",
            "invalid-delete-request",
            "profile-ref-both-forms-supplied",
            "yaml-content-type-required",
            "json-content-type-required",
            "profile-yaml-too-large",
            "profile-action-too-large",
            "profile-body-timeout",
            "invalid-utf8",
            "invalid-json",
            "destructive-operation-in-progress",
            "profile-restart-unavailable",
            "profile-activation-abort-persist-failed",
            "profile-imported",
            "profile-selection-unchanged",
            "profile-selection-staged",
            "profile-revision-deleted",
            "activation-pending",
            "activation-applying-selected",
            "activation-applying-auto-update",
            "activation-applying-bundled-revision",
            "preview-token-invalid",
            "imported-catalog-revision-limit",
            "imported-profile-revision-limit",
            "imported-catalog-byte-limit",
            "catalog-reservation-failed",
            "profile-store-failed",
            "profile-revision-not-found",
            "profile-incompatible",
            "activation-in-progress",
            "selection-persist-failed",
            "rollback-unavailable",
            "bundled-profile-delete-forbidden",
            "referenced-profile-delete-forbidden",
            "profile-delete-failed",
            "catalog-stale",
            "expected-mapping",
            "expected-list",
            "expected-string",
            "expected-boolean",
            "expected-integer",
            "expected-finite-number",
            "expected-integer-or-strategy",
            "expected-32-bit-integer-or-strategy",
            "required-mapping",
            "required-list",
            "required-string",
            "required-boolean",
            "required-integer",
            "unknown-field",
            "unknown-value",
            "unsupported-yaml-type",
            "bounded-text",
            "bounded-text-basic",
            "duplicate-profile-link-url",
            "duplicate-profile-link-label",
            "duplicate-cpu-architecture",
            "unknown-core-driver",
            "unknown-su-form",
            "unknown-led-mechanism",
            "unknown-core-transfer",
            "unknown-screen-off-route",
            "core-version-required",
            "unsupported-schema",
            "invalid-https-url",
            "unsupported-privileged-path",
            "backup-schema-unsupported",
            "backup-revisions-array-required",
            "backup-revision-count-limit",
            "backup-revision-object-required",
            "backup-revision-yaml-required",
            "backup-revision-file-size-limit",
            "backup-duplicate-revision",
            "backup-profile-revision-limit",
            "backup-aggregate-byte-limit",
            "backup-active-object-or-null",
            "backup-selection-object-required",
            "backup-selection-mode-invalid",
            "backup-profile-id-invalid",
            "backup-revision-sha256-invalid",
            "profile-catalog-restore-unavailable",
            "profile-template-unavailable",
            "passive-device-draft-unavailable",
            "passive-report-unavailable",
            "profile-administration-unavailable",
            "profile-source-byte-limit",
            "profile-source-empty",
            "yaml-single-document-required",
            "yaml-nesting-too-deep",
            "yaml-nesting-depth-limit",
            "yaml-string-length-limit",
            "yaml-map-entry-limit",
            "yaml-mapping-key-string-required",
            "yaml-list-entry-limit",
            "yaml-parser-event-limit",
            "profile-id-invalid",
            "semantic-version-required",
            "profile-link-count-limit",
            "unicode-format-controls-forbidden",
            "introduced-year-range",
            "cpu-cluster-count-limit",
            "cpu-core-count-range",
            "cpu-total-count-limit",
            "license-expression-invalid",
            "tested-firmware-bounds",
            "limitations-bounds",
            "match-priority-range",
            "generic-fallback-only",
            "match-group-required",
            "match-group-count-limit",
            "match-predicate-required",
            "match-predicate-count-limit",
            "match-values-count-range",
            "match-value-invalid",
            "dotted-release-version-required",
            "app-su-needs-su-form",
            "su-blpower-needs-app-su",
            "daemon-blpower-sandbox-only",
            "daemon-led-sandbox-only",
            "relay-fallback-count-limit",
            "relay-paths-unique",
            "gpio-block-base-range",
            "gpio-range",
            "room-temperature-offset-range",
            "unknown-core-strategy",
            "unknown-core-strategy-value",
            "density-range",
            "font-scale-range",
            "physical-ppi-range",
            "touch-click-gain-range",
            "evdev-mapping-count-limit",
            "evdev-device-node-invalid",
            "linux-input-code-range",
            "keycode-format-invalid",
            "duplicate-evdev-mapping",
            "unknown-ha-cpu-tier",
            "linux-governor-name-invalid",
            "unknown-webview-artifact",
            "package-count-limit",
            "android-package-name-invalid",
            "duplicate-package-desired-state",
            "package-tag-bounds",
            "package-note-length-limit",
            "recipe-count-limit",
            "duplicate-recipe-selection",
            "unknown-core-recipe",
            "capability-driver-required",
            "unused-driver-declared",
            "draft-todos-recorded-as-limitations",
            "backup-restore-catalog-stale",
            "backup-restore-reservation-failed",
            "backup-revision-store-failed",
            "backup-post-write-coherency-failed",
            "backup-import-partial-selection-unchanged",
            "backup-restored-selection-unchanged",
            "backup-restored-selection-staged",
            "backup-selection-restore-failed",
            "backup-import-selection-stage-failed",
            "backup-restore-rejected-before-mutation",
            "backup-schema-version-unsupported",
            "backup-revision-limit",
            "backup-profile-revision-limit-plan",
            "backup-revision-hash-mismatch",
            "backup-revision-id-mismatch",
            "backup-existing-revision-conflict",
            "backup-restored-catalog-revision-limit",
            "backup-restored-profile-revision-limit",
            "backup-restored-catalog-byte-limit",
            "backup-referenced-revision-unavailable",
            "backup-source-rollback-unavailable",
            "backup-source-active-unavailable",
            "activation-applying-persist-failed",
            "activation-rolled-back-unhealthy-auto",
            "activation-rolled-back-unhealthy-pinned",
            "activation-unhealthy-rollback-complete",
            "activation-unhealthy-rollback-persist-failed",
            "activation-auto-update-stage-failed",
            "activation-rolled-back-unresolved",
            "activation-unresolved-selection-restored",
            "activation-unresolved-rollback-persist-failed",
            "activation-rolled-back-incompatible",
            "activation-incompatible-selection-restored",
            "activation-incompatible-recovery-persist-failed",
            "pinned-successor-held",
            "pinned-revision-retired",
            "repin-persist-failed-auto",
            "repin-persist-failed-pinned",
            "catalog-fallback-invalid-emergency-used",
            "required-profile-read-failed",
            "imported-path-noncanonical",
            "imported-file-size-limit",
            "imported-catalog-count-quota",
            "imported-profile-count-quota",
            "imported-catalog-byte-quota",
            "imported-profile-read-failed",
            "activation-device-mismatch",
            "activation-touchscreen-grab-forbidden",
            "imported-filename-hash-mismatch",
            "imported-document-id-mismatch",
            "duplicate-revision-ignored",
            "pinned-revision-missing",
            "pinned-revision-incompatible",
            "bundled-generic-fallback-missing",
            "ambiguous-automatic-match",
            "emergency-profile-in-use",
        )
        private val PARAMS_BY_CODE: Map<String, Set<String>> = mapOf(
            "profile-imported" to setOf("display_name", "version"),
            "imported-catalog-revision-limit" to setOf("max"),
            "imported-profile-revision-limit" to setOf("id", "max"),
            "imported-catalog-byte-limit" to setOf("max"),
            "unknown-value" to setOf("value"),
            "unsupported-yaml-type" to setOf("type"),
            "bounded-text" to setOf("min", "max"),
            "bounded-text-basic" to setOf("min", "max"),
            "unknown-core-driver" to setOf("value"),
            "unknown-su-form" to setOf("value"),
            "unknown-led-mechanism" to setOf("value"),
            "unknown-core-transfer" to setOf("value"),
            "unknown-screen-off-route" to setOf("value"),
            "core-version-required" to setOf("required", "current"),
            "unsupported-schema" to setOf("actual", "expected"),
            "unsupported-privileged-path" to setOf("allowed"),
            "backup-revision-count-limit" to setOf("max"),
            "backup-profile-revision-limit" to setOf("id"),
            "profile-source-byte-limit" to setOf("max"),
            "yaml-nesting-depth-limit" to setOf("max"),
            "yaml-string-length-limit" to setOf("max"),
            "yaml-map-entry-limit" to setOf("max"),
            "yaml-list-entry-limit" to setOf("max"),
            "unknown-core-strategy-value" to setOf("value"),
            "unknown-webview-artifact" to setOf("value"),
            "unknown-core-recipe" to setOf("value"),
            "capability-driver-required" to setOf("value"),
            "unused-driver-declared" to setOf("value"),
            "backup-schema-version-unsupported" to setOf("actual"),
            "backup-profile-revision-limit-plan" to setOf("id"),
            "backup-restored-profile-revision-limit" to setOf("id"),
            "activation-rolled-back-unhealthy-pinned" to setOf("id", "revision"),
            "activation-incompatible-selection-restored" to setOf("id", "revision"),
            "activation-incompatible-recovery-persist-failed" to setOf("id", "revision"),
            "pinned-successor-held" to setOf("id", "retired_revision", "current_revision"),
            "pinned-revision-retired" to setOf("id", "retired_revision", "current_revision"),
            "repin-persist-failed-pinned" to setOf("id", "revision"),
            "ambiguous-automatic-match" to setOf("priority", "ids"),
            "imported-document-id-mismatch" to setOf("document_id", "storage_id"),
        )

        fun expectedParams(code: String): Set<String>? =
            if (code in SUPPORTED_CODES) PARAMS_BY_CODE.getOrDefault(code, emptySet()) else null
    }
}

/** Privileged or unusually consequential behavior highlighted before a local profile is imported. */
enum class ProfileRisk {
    ROOT_PATHS,
    RELAY_OR_GPIO_WRITES,
    EVDEV_READ,
    EVDEV_GRAB,
    PACKAGE_DISABLE_RECOMMENDATIONS,
    WEBVIEW_INSTALL,
    OVERRIDES_BUNDLED,
}

data class ProfileSummary(
    val ref: ProfileRef,
    val displayName: String,
    val origin: ProfileOrigin,
    val schema: Int,
    val minCoreVersion: String?,
    val matchesThisDevice: Boolean,
    val active: Boolean,
    val selected: Boolean,
    val shizukuRecommendation: ShizukuRecommendation,
    val risks: Set<ProfileRisk> = emptySet(),
    val contentVersion: String = "",
    val author: String? = null,
    val maturity: ProfileMaturity = ProfileMaturity.DRAFT,
    /** True only for content shipped by ha-paneld. Author-declared maturity is not a trust signal. */
    val trustedProvenance: Boolean = false,
    /** False when a stored imported revision cannot be activated by this core version. */
    val compatible: Boolean = true,
    val issues: List<ProfileIssue> = emptyList(),
    val soc: ProfileSoc? = null,
    val links: List<ProfileLink> = emptyList(),
    /**
     * When this imported revision was stored, taken from its immutable file's modification time.
     * Null for bundled content, which has no import event. It orders a profile's own revisions so a
     * picker can show the newest and keep the rest as history; it is not an identity or a trust
     * signal, and a backup restore rewrites it, so equal values must fall back to a stable tiebreak.
     */
    val importedAtEpochMs: Long? = null,
)

enum class ProfileDriverKind { LED, SCREEN, RADIO, RELAY, SENSOR, INPUT, UPDATE, ACCESS }

data class ProfileDriverDescriptor(
    val id: String,
    val kind: ProfileDriverKind,
    val description: String,
    val privileged: Boolean,
    /**
     * Root-helper authority this driver demands. This is the single source for the helper-need
     * decision; [ProfileMetadata.helperAuthorityDemand] is derived from it, so the demand table can
     * never drift from — or fail to cover — the canonical driver table. Deliberately separate from
     * [privileged]: a privileged driver may satisfy its access through app `su` or a trusted-host
     * operation rather than the root helper (see [ProfileHelperAuthorityDemand]).
     */
    val helperDemand: ProfileHelperAuthorityDemand,
)

data class ProfileFieldDescriptor(
    val path: String,
    val type: String,
    val required: Boolean = false,
    val enumValues: List<String> = emptyList(),
    val description: String,
)

/** Stable, typed metadata used by `/profiles/schema`, authoring tools, and generated documentation. */
data class ProfileSchemaDescriptor(
    val schema: Int,
    val maxBytes: Int,
    val fields: List<ProfileFieldDescriptor>,
)

data class ProfileDiff(
    val path: String,
    val before: String?,
    val after: String?,
)

/** Result of parsing an import candidate. [previewToken] is short-lived and bound to the exact raw hash. */
data class ProfilePreview(
    val previewToken: String?,
    val contentSha256: String,
    val expiresAtEpochMs: Long?,
    val summary: ProfileSummary?,
    val issues: List<ProfileIssue>,
    val diffFromActive: List<ProfileDiff>,
    val compatible: Boolean,
)

data class ProfileStatus(
    val catalogRevision: Long,
    val selection: ProfileSelection,
    val active: ProfileSummary?,
    val activation: ProfileActivationState,
    val issues: List<ProfileIssue> = emptyList(),
    /** Previous proven selection available as an explicit one-step rollback target. */
    val lastKnownGood: ProfileSelection? = null,
)

/** One immutable imported revision carried by a full panel backup. */
data class ProfileBackupRevision(
    val ref: ProfileRef,
    val rawYaml: String,
)

/**
 * Bounded profile-catalog component of a full `.hpb` backup. Activation's transient
 * PENDING/APPLYING state is deliberately absent: restore stages [selection] through the normal
 * health-gated activation path and treats the destination's current selection as its rollback target.
 */
data class ProfileBackup(
    val schema: Int = SCHEMA,
    val revisions: List<ProfileBackupRevision>,
    val selection: ProfileSelection,
    /** Exact revision observed active when the backup was made; restore uses it for coherency checks only. */
    val active: ProfileRef?,
    /** Source-side rollback identity. The destination's current selection remains the first rollback target. */
    val lastKnownGood: ProfileSelection?,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("schema", schema)
        put("revisions", JSONArray().apply {
            revisions.sortedWith(compareBy<ProfileBackupRevision>({ it.ref.id }, { it.ref.revision }))
                .forEach { revision ->
                    put(JSONObject().apply {
                        put("id", revision.ref.id)
                        put("revision", revision.ref.revision)
                        put("yaml", revision.rawYaml)
                    })
                }
        })
        put("selection", selectionJson(selection))
        put("active", active?.let(::refJson) ?: JSONObject.NULL)
        put("last_known_good", lastKnownGood?.let(::selectionJson) ?: JSONObject.NULL)
    }

    companion object {
        const val SCHEMA = 1

        /** Decode and enforce the transport-level bounds before YAML parsing or filesystem mutation. */
        fun fromJson(value: JSONObject): ProfileBackupDecodeResult {
            val issues = mutableListOf<ProfileIssue>()
            val rawSchema = value.opt("schema")
            val schema = (rawSchema as? Number)?.toInt() ?: -1
            if (rawSchema !is Number || rawSchema.toLong() != schema.toLong() || schema != SCHEMA) {
                issues += backupIssue("profiles.schema", "Unsupported profile backup schema.", "backup-schema-unsupported")
            }
            val revisionsArray = value.optJSONArray("revisions")
            if (revisionsArray == null) {
                issues += backupIssue("profiles.revisions", "Profile backup revisions must be an array.", "backup-revisions-array-required")
            } else if (revisionsArray.length() > ProfileMetadata.MAX_IMPORTED_REVISIONS) {
                issues += backupIssue(
                    "profiles.revisions",
                    "Profile backup exceeds the ${ProfileMetadata.MAX_IMPORTED_REVISIONS}-revision limit.",
                    "backup-revision-count-limit",
                    mapOf("max" to ProfileMetadata.MAX_IMPORTED_REVISIONS.toString()),
                )
            }
            val revisions = mutableListOf<ProfileBackupRevision>()
            val seen = mutableSetOf<ProfileRef>()
            val byId = mutableMapOf<String, Int>()
            var totalBytes = 0L
            if (revisionsArray != null) {
                for (index in 0 until minOf(revisionsArray.length(), ProfileMetadata.MAX_IMPORTED_REVISIONS + 1)) {
                    val item = revisionsArray.optJSONObject(index)
                    if (item == null) {
                        issues += backupIssue("profiles.revisions[$index]", "Revision must be an object.", "backup-revision-object-required")
                        continue
                    }
                    val ref = decodeRef(item, "profiles.revisions[$index]", issues)
                    val raw = item.opt("yaml") as? String
                    if (raw == null) {
                        issues += backupIssue("profiles.revisions[$index].yaml", "Revision YAML is missing.", "backup-revision-yaml-required")
                        continue
                    }
                    val bytes = raw.toByteArray(Charsets.UTF_8).size.toLong()
                    if (bytes > ProfileMetadata.MAX_BYTES) {
                        issues += backupIssue("profiles.revisions[$index].yaml", "Revision exceeds the per-file size limit.", "backup-revision-file-size-limit")
                    }
                    totalBytes = if (Long.MAX_VALUE - totalBytes < bytes) Long.MAX_VALUE else totalBytes + bytes
                    if (ref != null) {
                        if (!seen.add(ref)) {
                            issues += backupIssue("profiles.revisions[$index]", "Duplicate immutable revision.", "backup-duplicate-revision")
                        }
                        val count = byId.getOrDefault(ref.id, 0) + 1
                        byId[ref.id] = count
                        if (count > ProfileMetadata.MAX_IMPORTED_REVISIONS_PER_ID) {
                            issues += backupIssue(
                                "profiles.revisions[$index]",
                                "Profile '${ref.id}' exceeds the per-profile revision limit.",
                                "backup-profile-revision-limit",
                                mapOf("id" to ref.id),
                            )
                        }
                        revisions += ProfileBackupRevision(ref, raw)
                    }
                }
            }
            if (totalBytes > ProfileMetadata.MAX_IMPORTED_BYTES) {
                issues += backupIssue("profiles.revisions", "Profile backup exceeds the aggregate byte limit.", "backup-aggregate-byte-limit")
            }
            val selection = decodeSelection(value.optJSONObject("selection"), "profiles.selection", issues)
            val activeObj = value.optJSONObject("active")
            val active = when {
                !value.has("active") || value.isNull("active") -> null
                activeObj == null -> {
                    issues += backupIssue("profiles.active", "Active revision must be an object or null.", "backup-active-object-or-null")
                    null
                }
                else -> decodeRef(activeObj, "profiles.active", issues)
            }
            val lastKnownGood = when {
                !value.has("last_known_good") || value.isNull("last_known_good") -> null
                else -> decodeSelection(value.optJSONObject("last_known_good"), "profiles.last_known_good", issues)
            }
            val payload = if (issues.any { it.severity == ProfileIssueSeverity.ERROR } || selection == null) null else {
                ProfileBackup(schema, revisions, selection, active, lastKnownGood)
            }
            return ProfileBackupDecodeResult(payload, issues)
        }

        private fun selectionJson(selection: ProfileSelection): JSONObject = JSONObject().apply {
            when (selection) {
                ProfileSelection.Auto -> put("mode", "auto")
                is ProfileSelection.Pinned -> {
                    put("mode", "pinned")
                    put("id", selection.ref.id)
                    put("revision", selection.ref.revision)
                }
            }
        }

        private fun refJson(ref: ProfileRef): JSONObject = JSONObject().apply {
            put("id", ref.id)
            put("revision", ref.revision)
        }

        private fun decodeSelection(
            value: JSONObject?,
            path: String,
            issues: MutableList<ProfileIssue>,
        ): ProfileSelection? {
            if (value == null) {
                issues += backupIssue(path, "Selection must be an object.", "backup-selection-object-required")
                return null
            }
            return when (value.opt("mode") as? String) {
                "auto" -> ProfileSelection.Auto
                "pinned" -> decodeRef(value, path, issues)?.let(ProfileSelection::Pinned)
                else -> {
                    issues += backupIssue("$path.mode", "Selection mode must be 'auto' or 'pinned'.", "backup-selection-mode-invalid")
                    null
                }
            }
        }

        private fun decodeRef(
            value: JSONObject,
            path: String,
            issues: MutableList<ProfileIssue>,
        ): ProfileRef? {
            val id = value.opt("id") as? String ?: ""
            val revision = value.opt("revision") as? String ?: ""
            if (!PROFILE_ID.matches(id) || ".." in id) {
                issues += backupIssue("$path.id", "Profile id is invalid.", "backup-profile-id-invalid")
            }
            if (!REVISION.matches(revision)) {
                issues += backupIssue("$path.revision", "Revision must be a lowercase SHA-256.", "backup-revision-sha256-invalid")
            }
            return if (PROFILE_ID.matches(id) && ".." !in id && REVISION.matches(revision)) {
                ProfileRef(id, revision)
            } else null
        }

        private val PROFILE_ID = Regex("^[a-z0-9](?:[a-z0-9.-]{0,126}[a-z0-9])?$")
        private val REVISION = Regex("^[0-9a-f]{64}$")
    }
}

data class ProfileBackupDecodeResult(
    val payload: ProfileBackup?,
    val issues: List<ProfileIssue>,
)

data class ProfileBackupRestorePlan(
    val status: ProfileStatus,
    val expectedCatalogRevision: Long,
    val valid: Boolean,
    val toImport: List<ProfileRef>,
    val alreadyPresent: List<ProfileRef>,
    val issues: List<ProfileIssue>,
    val restartRequired: Boolean,
    /**
     * The selection a valid plan stages: the backup's own, or the current bundled revision of a pinned
     * bundled profile whose revision this release does not ship (reported as a `pinned-revision-retired`
     * warning). Null when the plan is invalid.
     */
    val selection: ProfileSelection? = null,
)

enum class ProfileBackupRestoreOutcome { SUCCEEDED, PARTIAL, REJECTED }

/** Structured profile-component outcome for restore progress and API responses. */
data class ProfileBackupRestoreResult(
    val outcome: ProfileBackupRestoreOutcome,
    val status: ProfileStatus,
    val imported: List<ProfileRef>,
    val alreadyPresent: List<ProfileRef>,
    val issues: List<ProfileIssue>,
    val selectionStaged: Boolean,
    val restartRequired: Boolean,
    val message: String,
    val presentation: ProfilePresentation? = null,
)

private fun backupIssue(
    path: String,
    message: String,
    presentationCode: String,
    presentationParams: Map<String, String> = emptyMap(),
) = ProfileIssue(
    ProfileIssueSeverity.ERROR,
    path,
    message,
    ProfilePresentation(presentationCode, presentationParams),
)

sealed interface ProfileMutation {
    data class Success(
        val status: ProfileStatus,
        val restartRequired: Boolean,
        val message: String,
        val presentation: ProfilePresentation? = null,
    ) : ProfileMutation

    data class Rejected(
        val status: ProfileStatus,
        val issues: List<ProfileIssue>,
    ) : ProfileMutation
}

/**
 * Narrow administrative surface consumed by HTTP/UI code. It exposes no filesystem paths, parser
 * implementation, or mutable profile object. Selecting a revision only stages it; the caller responds
 * first and then requests a controlled process restart when [ProfileMutation.Success.restartRequired].
 */
interface ProfileAdmin {
    fun schema(): ProfileSchemaDescriptor
    fun drivers(): List<ProfileDriverDescriptor>
    fun status(): ProfileStatus
    fun list(): List<ProfileSummary>
    fun preview(rawYaml: String): ProfilePreview
    fun importProfile(rawYaml: String, previewToken: String): ProfileMutation
    fun exportProfile(ref: ProfileRef): String?
    fun select(selection: ProfileSelection, expectedCatalogRevision: Long): ProfileMutation
    fun rollbackToLastKnownGood(expectedCatalogRevision: Long): ProfileMutation
    fun deleteProfile(ref: ProfileRef, expectedCatalogRevision: Long): ProfileMutation

    /** Export the bounded profile component for a full panel backup. Fleet config bundles do not use it. */
    fun exportBackup(): ProfileBackup {
        val current = status()
        val revisions = list().asSequence()
            .filter { it.origin == ProfileOrigin.IMPORTED }
            .mapNotNull { summary -> exportProfile(summary.ref)?.let { ProfileBackupRevision(summary.ref, it) } }
            .sortedWith(compareBy<ProfileBackupRevision>({ it.ref.id }, { it.ref.revision }))
            .toList()
        return ProfileBackup(
            revisions = revisions,
            selection = current.selection,
            active = current.active?.ref,
            lastKnownGood = current.lastKnownGood,
        )
    }

    fun planBackupRestore(payload: ProfileBackup): ProfileBackupRestorePlan = ProfileBackupRestorePlan(
        status = status(),
        expectedCatalogRevision = status().catalogRevision,
        valid = false,
        toImport = emptyList(),
        alreadyPresent = emptyList(),
        issues = listOf(backupIssue("profiles", "Profile catalog restore is unavailable.", "profile-catalog-restore-unavailable")),
        restartRequired = false,
    )

    fun restoreBackup(payload: ProfileBackup, expectedCatalogRevision: Long): ProfileBackupRestoreResult =
        ProfileBackupRestoreResult(
            outcome = ProfileBackupRestoreOutcome.REJECTED,
            status = status(),
            imported = emptyList(),
            alreadyPresent = emptyList(),
            issues = listOf(backupIssue("profiles", "Profile catalog restore is unavailable.", "profile-catalog-restore-unavailable")),
            selectionStaged = false,
            restartRequired = false,
            message = "Profile catalog restore is unavailable.",
            presentation = ProfilePresentation("profile-catalog-restore-unavailable"),
        )
}

/** Build values available without probing hardware. Matching is deliberately limited to these facts. */
data class DeviceFacts(
    val model: String,
    val device: String,
    val productVersion: String,
) {
    fun normalized(): DeviceFacts = DeviceFacts(
        model = model.lowercase(),
        device = device.lowercase(),
        productVersion = productVersion.lowercase(),
    )
}

/** Profile chosen for one service lifetime, plus an activation generation to acknowledge after startup. */
data class ResolvedProfile(
    val profile: DeviceProfile,
    val summary: ProfileSummary,
    val activationGeneration: Long? = null,
    val issues: List<ProfileIssue> = emptyList(),
)

interface ProfileResolver {
    fun resolveForStartup(): ResolvedProfile
    fun markActivationHealthy(generation: Long): Boolean
}

/** Passive evidence only: producing this report must not write hardware, change settings, or run su. */
data class PassiveProfileReport(
    val generatedAtEpochMs: Long,
    val facts: DeviceFacts,
    val observations: List<PassiveProfileObservation>,
    val issues: List<ProfileIssue> = emptyList(),
)

enum class PassiveProfileConfidence { OBSERVED, INFERRED }

data class PassiveProfileObservation(
    /** Candidate schema path, for example `sensors.light_technology`. */
    val path: String,
    val value: String?,
    val source: String,
    val confidence: PassiveProfileConfidence,
    val note: String? = null,
)

data class PassiveProfileDraft(
    val rawYaml: String,
    val report: PassiveProfileReport,
    val issues: List<ProfileIssue>,
)
