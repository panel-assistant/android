package io.panelassistant.android.util

import java.util.Collections
import org.json.JSONObject

/**
 * Optional, additive presentation metadata for the Install surface. Compatibility prose remains the
 * authoritative API value: if metadata is absent or a consumer cannot use it, it renders that prose exactly.
 *
 * Construction validates the closed v3 vocabulary and snapshots [params], so a producer cannot mutate an
 * already-admitted envelope into a different or oversized value before it reaches the wire.
 */
class InstallPresentation(
    val code: String,
    params: Map<String, String> = emptyMap(),
) {
    val params: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(params))

    init {
        require(code in SUPPORTED_CODES) { "Unknown Install presentation code." }
        require(CODE.matches(code) && code.length <= MAX_CODE_LENGTH) { "Invalid Install presentation code." }
        val required = REQUIRED_PARAMS_BY_CODE[code].orEmpty()
        val allowed = required + OPTIONAL_PARAMS_BY_CODE[code].orEmpty()
        require(this.params.keys.containsAll(required) && allowed.containsAll(this.params.keys)) {
            "Install presentation parameters do not match the code contract."
        }
        require(this.params.size <= MAX_PARAMS) { "Too many Install presentation parameters." }
        this.params.forEach { (name, value) ->
            require(value.length <= MAX_PARAM_LENGTH && validParameter(name, value)) {
                "Invalid Install presentation parameter."
            }
        }
        require(json().toByteArray(Charsets.UTF_8).size <= MAX_SERIALIZED_BYTES) {
            "Install presentation envelope is too large."
        }
    }

    fun json(): String = JSONObject()
        .put("code", code)
        .put("params", JSONObject(params))
        .toString()

    override fun equals(other: Any?): Boolean =
        other is InstallPresentation && code == other.code && params == other.params

    override fun hashCode(): Int = 31 * code.hashCode() + params.hashCode()

    override fun toString(): String = "InstallPresentation(code=$code, params=$params)"

    companion object {
        private val CODE = Regex("^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$")
        private val COUNT = Regex("^(0|[1-9][0-9]{0,9})$")
        private val LARGE_COUNT = Regex("^(0|[1-9][0-9]{0,18})$")
        private val POSITIVE_THREE_DIGITS = Regex("^[1-9][0-9]{0,2}$")
        private val PERCENT = Regex("^(100(?:\\.0+)?|[0-9]{1,2}(?:\\.[0-9]+)?)$")
        private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

        const val MAX_CODE_LENGTH = 64
        const val MAX_PARAMS = 8
        const val MAX_PARAM_LENGTH = 512
        const val MAX_SERIALIZED_BYTES = 2_048

        val SUPPORTED_CODES: Set<String> = Collections.unmodifiableSet(linkedSetOf(
            "version-install",
            "version-upgrade",
            "version-downgrade",
            "operation-working",
            "operation-cancelled",
            "apk-pending-lost",
            "backup-ready",
            "backup-ready-state-unavailable",
            "backup-cancelled",
            "backup-companion-too-large",
            "backup-staging-retained",
            "restore-preview-complete",
            "restore-request-rejected",
            "restore-completed",
            "restore-completed-with-state",
            "restore-completed-state-unavailable",
            "restore-partial",
            "restore-failed",
            "package-uninstalled",
            "package-uninstall-failed",
            "guard-db-retirement-settled",
            "guard-db-candidate-discard-finished",
            "guard-db-candidate-staging-finished",
            "guard-db-arm-not-started",
            "managed-release-unresolved",
            "managed-apk-missing",
            "managed-up-to-date",
            "managed-install-committed",
            "managed-update-committed",
            "managed-downgrade-committed",
            "managed-pinned",
            "managed-safety-cap-refused",
            "managed-manual-downgrade-required",
            "managed-play-managed",
            "managed-no-recommendation",
            "managed-no-newer",
            "managed-attempt-recorded",
            "install-no-permitted-route",
            "install-download-too-large",
            "install-insufficient-storage",
            "install-staging-failed",
            "install-download-failed",
            "install-deferred-saving-state",
            "install-guard-db-owned",
            "install-durable-rejection",
            "install-retryable-failure",
            "component-not-present",
            "profile-catalog-restore-unavailable",
            "backup-import-partial-selection-unchanged",
            "backup-restored-selection-unchanged",
            "backup-restored-selection-staged",
            "backup-selection-restore-failed",
            "backup-import-selection-stage-failed",
            "backup-restore-rejected-before-mutation",
            "destructive-operation-in-progress",
            "profile-restart-unavailable",
            "profile-activation-abort-persist-failed",
            "companion-unsupported-package",
            "companion-payload-invalid",
            "companion-helper-busy",
            "companion-marker-failed",
            "companion-urls-repaired",
            "companion-owner-restored",
            "companion-relaunch-unconfirmed",
            "companion-prior-files-retained",
            "companion-rollback-failed",
            "companion-helper-unavailable",
            "companion-rejected-before-commit",
            "companion-indeterminate",
            "restore-passphrase-required",
            "restore-passphrase-or-bundle-invalid",
            "restore-not-panel-backup",
            "restore-schema-missing",
            "restore-config-missing",
            "restore-config-invalid",
            "restore-legacy-too-large",
            "restore-companion-section-invalid",
            "restore-entity-object-invalid",
            "restore-profiles-object-invalid",
            "restore-state-object-invalid",
            "restore-archive-metadata-invalid",
            "restore-archive-entries-invalid",
            "restore-entity-state-invalid",
            "restore-entity-owner-missing",
            "restore-app-state-invalid",
            "restore-profile-archive-invalid",
            "restore-profile-catalog-invalid",
            "restore-profile-catalog-not-restorable",
            "restore-profile-restore-unavailable",
            "restore-companion-helper-required",
            "status-webview-old",
            "status-no-renderer",
            "status-update-available",
            "status-schema-rollback",
            "status-builtin-renderer-retries-stopped",
            "status-external-renderer-crash-loop",
            "status-companion-url-missing",
            "status-companion-probe-failed",
            "status-zigbee-contained",
            "status-zigbee-containment-incomplete",
            "status-zigbee-runaway",
            "status-zigbee-high-cpu",
            "status-zigbee-not-joined",
            "status-zigbee-legacy-watchdog",
            "status-storage-warning",
            "status-storage-critical",
            "status-storage-database-failure",
            "status-power-at-risk",
            "status-power-caution",
            "status-power-unknown",
            "status-mdns-not-running",
            "status-mdns-stale-address",
            "status-mdns-unresponsive",
            "status-mdns-recovering",
        ))

        private val REQUIRED_PARAMS_BY_CODE: Map<String, Set<String>> = mapOf(
            "operation-working" to setOf("owner"),
            "restore-completed-with-state" to setOf("count"),
            "package-uninstalled" to setOf("package"),
            "package-uninstall-failed" to setOf("package"),
            "managed-release-unresolved" to setOf("component", "channel"),
            "managed-apk-missing" to setOf("component", "version"),
            "managed-up-to-date" to setOf("component", "current"),
            "managed-install-committed" to setOf("component", "version"),
            "managed-update-committed" to setOf("component", "version"),
            "managed-downgrade-committed" to setOf("component", "version"),
            "managed-pinned" to setOf("component", "current", "latest", "cap"),
            "managed-safety-cap-refused" to setOf("component", "version", "cap"),
            "managed-manual-downgrade-required" to setOf("component", "current", "cap"),
            "managed-play-managed" to setOf("component"),
            "managed-no-recommendation" to setOf("component"),
            "managed-no-newer" to setOf("component", "current"),
            "managed-attempt-recorded" to setOf("component", "version", "current"),
            "install-no-permitted-route" to setOf("component"),
            "install-download-too-large" to setOf("component"),
            "install-insufficient-storage" to setOf("component"),
            "install-staging-failed" to setOf("component"),
            "install-download-failed" to setOf("component"),
            "install-deferred-saving-state" to setOf("component"),
            "install-guard-db-owned" to setOf("component"),
            "install-durable-rejection" to setOf("component"),
            "install-retryable-failure" to setOf("component"),
            "companion-urls-repaired" to setOf("count"),
            "status-webview-old" to setOf("current_engine", "target_chromium"),
            "status-update-available" to setOf("component", "current", "latest", "release_url"),
            "status-schema-rollback" to setOf("from_schema", "to_schema"),
            "status-companion-url-missing" to setOf("count"),
            "status-storage-database-failure" to setOf("failure", "operation"),
            "status-mdns-stale-address" to setOf("bound_ip", "lan_ip"),
            "status-mdns-unresponsive" to setOf("attempts", "reason_code"),
            "status-mdns-recovering" to setOf("reason_code"),
        )

        private val STORAGE_PARAMS = setOf(
            "usable_bytes",
            "total_bytes",
            "used_percent",
            "database_bytes",
            "wal_bytes",
        )

        private val OPTIONAL_PARAMS_BY_CODE: Map<String, Set<String>> = mapOf(
            "status-storage-warning" to STORAGE_PARAMS,
            "status-storage-critical" to STORAGE_PARAMS,
            "status-storage-database-failure" to STORAGE_PARAMS,
        )

        private val OWNERS = setOf(
            "paneld",
            "companion",
            "webview",
            "apk",
            "package-uninstall",
            "backup",
            "restore-preview",
            "restore",
            "companion-url-repair",
            "guard-db",
        )
        private val COMPONENTS = setOf("paneld", "companion", "webview", "apk")
        private val CHANNELS = setOf("stable", "prerelease")
        private val FAILURES = setOf("storage-full", "io", "corruption", "busy")
        internal val OPERATIONS = setOf(
            "app-state-write",
            "ambient-history",
            "ambient-history-reset",
            "ambient-history-seed",
            "catalog-access-history",
            "catalog-issue-override",
            "catalog-maintenance",
            "catalog-metric-history",
            "catalog-overrides",
            "catalog-reset",
            "catalog-scope-migration",
            "catalog-status",
            "catalog-sync",
            "dashboard-performance-history",
            "database-checkpoint",
            "database-create",
            "database-downgrade-tripwire",
            "database-preopen-reconcile",
            "database-upgrade",
            "database-vault-read",
            "database-vault-restore",
            "database-version-read",
            "proximity-history",
            "proximity-history-reset",
            "quick-check",
            "storage-health-read",
            "database",
        )
        private val REASONS = setOf(
            "own-advertisement-absent",
            "multicast-socket-failed",
            "teardown-failed",
            "recreation-failed",
            "no-response",
        )

        /** Null is the fail-closed adapter for optional metadata sourced from dynamic values. */
        fun create(code: String, params: Map<String, String> = emptyMap()): InstallPresentation? =
            runCatching { InstallPresentation(code, params) }.getOrNull()

        private fun validParameter(name: String, value: String): Boolean = when (name) {
            "owner" -> value in OWNERS
            "component" -> value in COMPONENTS
            "channel" -> value in CHANNELS
            "count", "from_schema", "to_schema", "attempts" -> COUNT.matches(value)
            "package" -> value.length <= 255 && PACKAGE.matches(value)
            "version", "current", "latest", "cap", "current_engine" -> value.isNotEmpty() && value.length <= 128
            "target_chromium" -> POSITIVE_THREE_DIGITS.matches(value)
            "release_url" -> value.startsWith("https://") && value.length <= MAX_PARAM_LENGTH
            "usable_bytes", "total_bytes", "database_bytes", "wal_bytes" -> LARGE_COUNT.matches(value)
            "used_percent" -> PERCENT.matches(value)
            "failure" -> value in FAILURES
            "operation" -> value in OPERATIONS
            "bound_ip", "lan_ip" -> value.isNotEmpty() && value.length <= 45
            "reason_code" -> value in REASONS
            else -> false
        }
    }
}

/** Exact compatibility prose plus optional locale-neutral metadata selected by its semantic producer. */
data class InstallOperationResult(
    val message: String,
    val presentation: InstallPresentation? = null,
)
