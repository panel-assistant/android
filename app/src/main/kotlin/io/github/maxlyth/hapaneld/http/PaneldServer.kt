package io.github.maxlyth.hapaneld.http

import android.content.Context
import android.os.SystemClock
import android.util.Log
import io.github.maxlyth.hapaneld.canonicalHaOrigin
import io.github.maxlyth.hapaneld.sameOriginDashboardRoute
import io.github.maxlyth.hapaneld.Config
import io.github.maxlyth.hapaneld.NativeLocale
import io.github.maxlyth.hapaneld.sensors.HaLifecycle
import io.github.maxlyth.hapaneld.sensors.HaLifecycleMessage
import io.github.maxlyth.hapaneld.sensors.HaLifecycleRuntime
import io.github.maxlyth.hapaneld.sensors.HaNetworkPathRuntime
import io.github.maxlyth.hapaneld.sensors.PathProbeRuntime
import io.github.maxlyth.hapaneld.sensors.HaPresenceSourceUpdate
import io.github.maxlyth.hapaneld.sensors.HaPanelAreaPrerequisite
import io.github.maxlyth.hapaneld.sensors.HaPanelAreaPrerequisitePhase
import io.github.maxlyth.hapaneld.BuildConfig
import io.github.maxlyth.hapaneld.DashboardEntityBackupState
import io.github.maxlyth.hapaneld.DiscoveryResult
import io.github.maxlyth.hapaneld.GuidedSetupPresence
import io.github.maxlyth.hapaneld.HaAuthOwner
import io.github.maxlyth.hapaneld.HaAuthSnapshot
import io.github.maxlyth.hapaneld.HaDiscovery
import io.github.maxlyth.hapaneld.LiveSettingRequestOutcome
import io.github.maxlyth.hapaneld.PanelStatus
import io.github.maxlyth.hapaneld.panelAssistantDiscoveryId
import io.github.maxlyth.hapaneld.RendererAdmissionPresentation
import io.github.maxlyth.hapaneld.RendererAdmissionRuntime
import io.github.maxlyth.hapaneld.RendererMode
import io.github.maxlyth.hapaneld.RendererResolver
import io.github.maxlyth.hapaneld.dashboardRecoveryPresentation
import io.github.maxlyth.hapaneld.haSignInPending
import io.github.maxlyth.hapaneld.normalizeDashboardEntityPath
import io.github.maxlyth.hapaneld.peersJson
import io.github.maxlyth.hapaneld.stableOwner
import io.github.maxlyth.hapaneld.config.Capabilities
import io.github.maxlyth.hapaneld.config.ConfigBundle
import io.github.maxlyth.hapaneld.config.ConfigDiff
import io.github.maxlyth.hapaneld.config.Migrations
import io.github.maxlyth.hapaneld.backup.PanelBackup
import io.github.maxlyth.hapaneld.backup.CompanionRestore
import io.github.maxlyth.hapaneld.config.Scope
import io.github.maxlyth.hapaneld.config.SettingType
import io.github.maxlyth.hapaneld.config.SettingSpec
import io.github.maxlyth.hapaneld.config.SettingValue
import io.github.maxlyth.hapaneld.config.SettingsRegistry
import io.github.maxlyth.hapaneld.config.TamePackagePolicy
import io.github.maxlyth.hapaneld.config.Validation
import io.github.maxlyth.hapaneld.i18n.AppLocale
import io.github.maxlyth.hapaneld.i18n.CatalogueLoader
import io.github.maxlyth.hapaneld.i18n.Strings as AppStrings
import io.github.maxlyth.hapaneld.camera.AbsentCameraSurface
import io.github.maxlyth.hapaneld.camera.CameraRefusal
import io.github.maxlyth.hapaneld.camera.CameraResolution
import io.github.maxlyth.hapaneld.camera.CameraState
import io.github.maxlyth.hapaneld.camera.CameraSurface
import io.github.maxlyth.hapaneld.camera.SnapshotResult
import io.github.maxlyth.hapaneld.control.AmbientThemeReport
import io.github.maxlyth.hapaneld.control.BuiltinDashboard
import io.github.maxlyth.hapaneld.control.CdpRelay
import io.github.maxlyth.hapaneld.control.AdbController
import io.github.maxlyth.hapaneld.control.AdaptiveLuxCurve
import io.github.maxlyth.hapaneld.control.CompanionDb
import io.github.maxlyth.hapaneld.control.CompanionDataLease
import io.github.maxlyth.hapaneld.control.CompanionDataOperationGate
import io.github.maxlyth.hapaneld.control.CompanionDataOperationState
import io.github.maxlyth.hapaneld.control.DensityController
import io.github.maxlyth.hapaneld.control.DisplaySizingObservation
import io.github.maxlyth.hapaneld.control.InteractiveController
import io.github.maxlyth.hapaneld.control.PrivilegeRoute
import io.github.maxlyth.hapaneld.control.RemoteDebugSecurityTransitionGate
import io.github.maxlyth.hapaneld.control.RemoteDebugAuthorityResult
import io.github.maxlyth.hapaneld.control.PrivilegedRouteObservation
import io.github.maxlyth.hapaneld.control.PowerRepairCapability
import io.github.maxlyth.hapaneld.control.PowerSafetyAcknowledgementDecision
import io.github.maxlyth.hapaneld.control.PowerSafetyAdvisory
import io.github.maxlyth.hapaneld.control.PowerSafetyAdvisoryAction
import io.github.maxlyth.hapaneld.control.PowerSafetyAdvisoryPolicy
import io.github.maxlyth.hapaneld.control.PowerSafetyAssessment
import io.github.maxlyth.hapaneld.control.PowerSafetyMutationPolicy
import io.github.maxlyth.hapaneld.control.PowerSafetyRepairResult
import io.github.maxlyth.hapaneld.control.Su
import io.github.maxlyth.hapaneld.control.SystemController
import io.github.maxlyth.hapaneld.control.HandBackHomeController
import io.github.maxlyth.hapaneld.control.HandBackHomePolicy
import io.github.maxlyth.hapaneld.control.TameController
import io.github.maxlyth.hapaneld.control.TameReconcileResult
import io.github.maxlyth.hapaneld.control.VolumeController
import io.github.maxlyth.hapaneld.control.ZigbeeHealthSnapshot
import io.github.maxlyth.hapaneld.control.ZigbeeHealthState
import io.github.maxlyth.hapaneld.control.zigbeeHealthPresentation
import io.github.maxlyth.hapaneld.control.observePrivilegedRoutes
import io.github.maxlyth.hapaneld.dashboard.EntityCatalogStore
import io.github.maxlyth.hapaneld.dashboard.readThenClose
import io.github.maxlyth.hapaneld.dashboard.EntityFilterProtocol
import io.github.maxlyth.hapaneld.dashboard.EntityFilterTelemetry
import io.github.maxlyth.hapaneld.dashboard.EntityLearningManager
import io.github.maxlyth.hapaneld.dashboard.SchemaReconcileAction
import io.github.maxlyth.hapaneld.device.DeviceProfile
import io.github.maxlyth.hapaneld.device.LedMechanism
import io.github.maxlyth.hapaneld.device.TameCandidate
import io.github.maxlyth.hapaneld.device.profile.PassiveProfileDraft
import io.github.maxlyth.hapaneld.device.profile.PassiveProfileReport
import io.github.maxlyth.hapaneld.device.profile.ProfileAdmin
import io.github.maxlyth.hapaneld.device.profile.ProfileBackup
import io.github.maxlyth.hapaneld.device.profile.ProfileBackupRestoreOutcome
import io.github.maxlyth.hapaneld.device.profile.ProfileBackupRestorePlan
import io.github.maxlyth.hapaneld.device.profile.ProfileBackupRestoreResult
import io.github.maxlyth.hapaneld.logship.LOG_SHIP_STATUS_OFF
import io.github.maxlyth.hapaneld.logship.LogCapture
import io.github.maxlyth.hapaneld.logship.LogShipRecord
import io.github.maxlyth.hapaneld.logship.LogShipStatusProjection
import io.github.maxlyth.hapaneld.logship.LogShipTarget
import io.github.maxlyth.hapaneld.logship.NetworkLogSinkFactory
import io.github.maxlyth.hapaneld.metrics.FeatureCosts
import io.github.maxlyth.hapaneld.persistence.AppState
import io.github.maxlyth.hapaneld.persistence.ConfigVault
import io.github.maxlyth.hapaneld.persistence.StateArchiveSection
import io.github.maxlyth.hapaneld.migration.IdentityMigrationSurface
import io.github.maxlyth.hapaneld.migration.MigrationRestoreAdmission
import io.github.maxlyth.hapaneld.migration.RestoreAttempt
import io.github.maxlyth.hapaneld.migration.claimsRestoreAttempt
import io.github.maxlyth.hapaneld.migration.migrationRestoreAdmission
import io.github.maxlyth.hapaneld.persistence.BackupIdentity
import io.github.maxlyth.hapaneld.persistence.RawPreferenceBackup
import io.github.maxlyth.hapaneld.persistence.StateBackupPolicy
import io.github.maxlyth.hapaneld.metrics.FeatureCostOperation
import io.github.maxlyth.hapaneld.metrics.FeatureCostOutcome
import io.github.maxlyth.hapaneld.provisioning.ProvisioningActivationSnapshot
import io.github.maxlyth.hapaneld.provisioning.ProvisioningReader
import io.github.maxlyth.hapaneld.security.ApprovalBroker
import io.github.maxlyth.hapaneld.security.LocalApprovalBroker
import io.github.maxlyth.hapaneld.security.SensitiveOperation
import io.github.maxlyth.hapaneld.sensors.SensorReporter
import io.github.maxlyth.hapaneld.sensors.HaCurrentUserClient
import io.github.maxlyth.hapaneld.shizuku.ShizukuBridge
import io.github.maxlyth.hapaneld.storage.StorageHealthRuntime
import io.github.maxlyth.hapaneld.storage.StorageHealthSnapshot
import io.github.maxlyth.hapaneld.util.AccessDenialMemo
import io.github.maxlyth.hapaneld.util.DashboardPath
import io.github.maxlyth.hapaneld.util.DashboardTheme
import io.github.maxlyth.hapaneld.util.Cached
import io.github.maxlyth.hapaneld.util.AppInstaller
import io.github.maxlyth.hapaneld.util.AndroidInput
import io.github.maxlyth.hapaneld.util.BoundedStreams
import io.github.maxlyth.hapaneld.util.BoundedDns
import io.github.maxlyth.hapaneld.util.BundledHelperInstaller
import io.github.maxlyth.hapaneld.util.bundledHelperIsCanonical
import io.github.maxlyth.hapaneld.util.CompanionInstaller
import io.github.maxlyth.hapaneld.util.CompanionHelperProtocol
import io.github.maxlyth.hapaneld.util.CompanionOperationStatus
import io.github.maxlyth.hapaneld.util.HelperClient
import io.github.maxlyth.hapaneld.util.GuardDbArmCoordinator
import io.github.maxlyth.hapaneld.util.GuardDbMaintenance
import io.github.maxlyth.hapaneld.util.GuardDbProcessAdmission
import io.github.maxlyth.hapaneld.util.guardDbSettingsAuthorityStore
import io.github.maxlyth.hapaneld.util.guardDbAppStaging
import io.github.maxlyth.hapaneld.util.guardDbBootNonce
import io.github.maxlyth.hapaneld.util.guardDbSentinelStore
import io.github.maxlyth.hapaneld.util.guardDbTerminalRetirementStore
import io.github.maxlyth.hapaneld.util.inspectGuardDbCandidate
import io.github.maxlyth.hapaneld.util.HaLink
import io.github.maxlyth.hapaneld.util.LogShipEndpoint
import io.github.maxlyth.hapaneld.util.isLocalSource
import io.github.maxlyth.hapaneld.util.isLoopbackPeer
import io.github.maxlyth.hapaneld.util.isRoutable
import io.github.maxlyth.hapaneld.util.ByteLimitExceeded
import io.github.maxlyth.hapaneld.util.InstallOutcome
import io.github.maxlyth.hapaneld.util.InstallPresentation
import io.github.maxlyth.hapaneld.util.InstallProgress
import io.github.maxlyth.hapaneld.util.Json
import io.github.maxlyth.hapaneld.util.LatestDispatcher
import io.github.maxlyth.hapaneld.util.GenerationSingleFlight
import io.github.maxlyth.hapaneld.util.RendererPreparationCoordinator
import io.github.maxlyth.hapaneld.util.SelfUpdater
import io.github.maxlyth.hapaneld.util.PanelAssistantDevice
import io.github.maxlyth.hapaneld.util.PanelAssistantUpdateLease
import io.github.maxlyth.hapaneld.util.UpdateChecker
import io.github.maxlyth.hapaneld.util.withStagedFiles
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.parseQueryString
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveStream
import io.ktor.server.request.receiveText
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.Route
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal fun panelBrowserTitle(
    friendlyName: String,
    section: String? = null,
    versionName: String = BuildConfig.VERSION_NAME,
    versionCode: Int = BuildConfig.VERSION_CODE,
): String {
    val panel = friendlyName.trim().ifBlank { "ha-paneld" }
    val suffix = section?.trim().orEmpty()
    val title = if (suffix.isBlank()) panel else "$panel · $suffix"
    return if ('-' in versionName) "$versionCode · $title" else title
}

/** Pure payload boundary for Configure's app inventories. Keeping the two inputs separate proves that
 * a failed broad launchable-app query (represented by an empty list) cannot suppress detected Companion
 * renderer choices. */
internal fun configureAppInventoryJson(
    apps: List<Pair<String, String>>,
    rendererChoices: List<CompanionInstaller.RendererChoice>,
): String {
    val appJson = apps.joinToString(",") { (pkg, label) ->
        "{\"pkg\":${Json.str(pkg)},\"label\":${Json.str(label)}}"
    }
    val rendererJson = rendererChoices.joinToString(",") { choice ->
        "{\"pkg\":${Json.str(choice.packageName)},\"label\":${Json.str(choice.label)}}"
    }
    return "{\"apps\":[$appJson],\"renderers\":[$rendererJson]}"
}

/**
 * Canonical approval payload for a materialized HTTP request. Distinct query-name order is not
 * semantically significant, but duplicate value order is because Ktor's first-value lookup can
 * affect behavior. Group by name while retaining each value list's order, then length-frame every
 * field and collection count so no name/value grouping can share an approval accidentally.
 */
internal fun exactHttpApprovalPayload(
    method: String,
    path: String,
    parameters: List<Pair<String, String>>,
    bodyDigest: String,
): String = buildString {
    fun frame(value: String) {
        append(value.toByteArray(Charsets.UTF_8).size).append(':').append(value)
    }

    val grouped = parameters.groupBy({ it.first }, { it.second }).toSortedMap()
    frame(method.uppercase())
    frame(path)
    frame(grouped.size.toString())
    grouped.forEach { (name, values) ->
        frame(name)
        frame(values.size.toString())
        values.forEach(::frame)
    }
    frame(bodyDigest)
}

internal fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { "%02x".format(it) }

/** Shared sensitive-request decision. Hardened policy is intentionally remote-only: trusted loopback
 * callers retain the established exemption while every non-loopback request remains peer-, operation-
 * and payload-bound through the one-shot approval broker. A request Panel Assistant proved was made by a
 * Home Assistant administrator skips approval for the operations [EmbedProof.EXEMPT] names, and only those. */
internal suspend fun authorizeSensitiveRequest(
    call: ApplicationCall,
    hardened: Boolean,
    peer: String,
    operation: SensitiveOperation,
    payload: String,
    summary: String,
    broker: ApprovalBroker,
    audit: (String) -> Unit = { line -> Log.i("ha-paneld/http", line) },
): Boolean {
    if (!hardened || isLoopbackPeer(peer)) return true
    val proven = call.provenEmbedRequest()
    if (proven != null && proven.exempts(operation)) {
        audit("approved by Home Assistant administrator ${proven.userId} via Panel Assistant: ${operation.name}")
        return true
    }
    val (decision, id) = broker.request(operation, peer, payload, summary)
    if (decision == ApprovalBroker.Decision.APPROVED) return true
    call.respondText(
        "{\"ok\":false,\"error\":\"approval-required\",\"approval_id\":${Json.str(id)}," +
            "\"message\":\"Approve this request physically on the panel, then retry it; it cannot be approved remotely.\"}",
        ContentType.Application.Json,
        HttpStatusCode.Accepted,
    )
    return false
}

/** User-facing remediation for the renderer-specific recovery authority. */
internal fun dashboardRecoveryWarning(state: PanelStatus.DashboardRecoveryState): String? = when (state) {
    PanelStatus.DashboardRecoveryState.NONE -> null
    PanelStatus.DashboardRecoveryState.BUILTIN_RENDERER ->
        "⛔ <b>Built-in renderer stopped retrying</b> after repeated WebView failures. " +
            "Update or repair System WebView, then use Reload dashboard from the panel navbar or Dashboard tab."
    PanelStatus.DashboardRecoveryState.EXTERNAL_RENDERER ->
        "⛔ <b>Dashboard app is crash-looping</b> — the watchdog stopped relaunching it to avoid a restart storm. " +
            "Reinstall or downgrade the dashboard/Companion app (see <a href=\"install\">updates</a>), or reboot the panel."
}

/** Same-order nullable overlay for `/status`; invalid cardinality omits the whole additive field. */
internal fun installWarningPresentationsJson(
    warnings: List<String>,
    presentations: List<InstallPresentation?>,
): String? {
    if (warnings.size != presentations.size || warnings.size > 11) return null
    return presentations.joinToString(separator = ",", prefix = "[", postfix = "]") { it?.json() ?: "null" }
}

internal val PERFORMANCE_WORKLOAD_KEYS = listOf(
    "dashboard_package",
    "home_dashboard",
    "ha_url",
    "dashboard_fullscreen",
    "dashboard_native_kiosk",
    "dashboard_overscroll",
    "dashboard_idle_return_min",
    "dashboard_zoom",
    "dark_mode",
    "dashboard_theme",
    "auto_brightness",
    "auto_brightness_minimum_percent",
    "auto_brightness_response_percent",
    "auto_brightness_ha_entity",
    "cpu_governor",
    "keep_awake",
    "prevent_idle_dim",
)

private val PERFORMANCE_COMPARISON_ID = Regex("^[0-9a-f]{32}$")
private val PERFORMANCE_DEVICE_SECRET = Regex("^[0-9a-f]{64}$")

internal fun validPerformanceDeviceSecret(value: String): Boolean =
    value.matches(PERFORMANCE_DEVICE_SECRET)

internal fun performanceBindingJson(
    comparisonId: String,
    deviceSecret: String,
    panelId: String,
    workload: Map<String, String>,
): String? {
    if (!comparisonId.matches(PERFORMANCE_COMPARISON_ID) || !validPerformanceDeviceSecret(deviceSecret)) return null
    if (workload.keys != PERFORMANCE_WORKLOAD_KEYS.toSet()) return null
    val key = SecretKeySpec(deviceSecret.lowercase().toByteArray(Charsets.UTF_8), "HmacSHA256")
    fun fingerprint(domain: String, value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(key)
        return mac.doFinal("ha-paneld-perf/$domain\u0000$comparisonId\u0000$value".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
    val workloadValue = buildString {
        workload.toSortedMap().forEach { (name, value) ->
            append(name.length).append(':').append(name)
            append(value.length).append(':').append(value)
        }
    }
    return JSONObject()
        .put("comparison_id", comparisonId)
        .put("panel_fingerprint", fingerprint("panel", panelId))
        .put("workload_fingerprint", fingerprint("workload", workloadValue))
        .toString()
}

internal fun Parameters.canonicalDigest(): String {
    val framed = buildString {
        fun frame(value: String) {
            append(value.toByteArray(Charsets.UTF_8).size).append(':').append(value)
        }

        val fields = entries().sortedBy { it.key }
        frame(fields.size.toString())
        fields.forEach { (name, values) ->
            frame(name)
            frame(values.size.toString())
            values.forEach { value ->
                // Parameters.get(name) consumes the first submitted value, so value order is part
                // of the request's behavior and must remain part of its approval identity.
                frame(value)
            }
        }
    }
    return sha256Hex(framed.toByteArray(Charsets.UTF_8))
}

internal fun exactHttpApprovalPayload(call: ApplicationCall, bodyDigest: String): String =
    exactHttpApprovalPayload(
        method = call.request.httpMethod.value,
        path = call.request.uri.substringBefore('?'),
        parameters = call.request.queryParameters.entries()
            .flatMap { (name, values) -> values.map { name to it } },
        bodyDigest = bodyDigest,
    )

/**
 * Keep app-side launch suppression until the helper affirmatively reports that no Companion-data
 * worker can still be mutating files. An unreachable status socket is not evidence that a worker
 * from an earlier, timed-out connection has stopped; the daemon may merely be temporarily unable to
 * accept or answer the probe. The global Companion gate admits only one lease, so retaining it and
 * one low-frequency polling coroutine is resource-bounded even across a prolonged outage.
 *
 * A legacy `UNSUPPORTED` reply is not affirmative while the app marker is armed: a newer helper may
 * have published a durable journal and then died before an older init-managed helper restarted.
 */
internal suspend fun retainCompanionLeaseUntilHelperIdle(
    lease: CompanionDataOperationGate.Lease,
    operationState: CompanionDataOperationState,
    afterRelease: () -> Unit,
    operationStatus: () -> CompanionOperationStatus,
    pollMs: Long,
) {
    try {
        while (true) {
            val status = runCatching(operationStatus).getOrDefault(CompanionOperationStatus.UNAVAILABLE)
            when (status) {
                CompanionOperationStatus.IDLE -> if (operationState.clear()) break
                CompanionOperationStatus.BUSY,
                CompanionOperationStatus.UNSUPPORTED,
                CompanionOperationStatus.UNAVAILABLE -> delay(pollMs.coerceAtLeast(1L))
            }
            if (status == CompanionOperationStatus.IDLE) delay(pollMs.coerceAtLeast(1L))
        }
    } finally {
        lease.close()
    }
    afterRelease()
}

/** Bound config mutations before Ktor or JSONObject materializes attacker-controlled form/JSON data. */
internal suspend fun receiveBoundedConfigParameters(
    call: ApplicationCall,
    maxBytes: Long = PaneldServer.MAX_CONFIG_POST_BODY_BYTES,
): Parameters? {
    val body = when (val receipt = receiveBoundedBody(call, maxBytes)) {
        is BoundedBodyReceipt.Received -> String(receipt.bytes, Charsets.UTF_8)
        BoundedBodyReceipt.TooLarge -> {
            call.respondText("request too large\n", status = HttpStatusCode.PayloadTooLarge)
            return null
        }
        BoundedBodyReceipt.TimedOut -> {
            call.respondText("request timeout\n", status = HttpStatusCode.RequestTimeout)
            return null
        }
    }
    return try {
        if (call.request.headers["Content-Type"].orEmpty().substringBefore(';').trim()
                .equals(ContentType.Application.Json.toString(), ignoreCase = true)
        ) {
            val json = JSONObject(body)
            Parameters.build {
                json.keys().forEach { key ->
                    val value = json.get(key)
                    require(value === JSONObject.NULL || value is String || value is Number || value is Boolean)
                    append(key, if (value === JSONObject.NULL) "" else value.toString())
                }
            }
        } else {
            parseQueryString(body)
        }
    } catch (_: Throwable) {
        call.respondText("invalid config body\n", status = HttpStatusCode.BadRequest)
        null
    }
}

/** Materialize the many small form-only control posts under one total-body limit. Ktor's default
 * receiveParameters limit is 50 MiB per field, which is disproportionate on low-memory wall panels. */
internal suspend fun receiveBoundedFormParameters(
    call: ApplicationCall,
    maxBytes: Long = PaneldServer.MAX_SMALL_FORM_POST_BODY_BYTES,
): Parameters? {
    val body = when (val receipt = receiveBoundedBody(call, maxBytes)) {
        is BoundedBodyReceipt.Received -> String(receipt.bytes, Charsets.UTF_8)
        BoundedBodyReceipt.TooLarge -> {
            call.respondText("request too large\n", status = HttpStatusCode.PayloadTooLarge)
            return null
        }
        BoundedBodyReceipt.TimedOut -> {
            call.respondText("request timeout\n", status = HttpStatusCode.RequestTimeout)
            return null
        }
    }
    return parseQueryString(body)
}

internal data class RemoteActionRouteDependencies(
    val authorizeSensitive: suspend (ApplicationCall, SensitiveOperation, String, String) -> Boolean,
    val admit: suspend (ApplicationCall, String) -> Unit,
)

/** HTTP handler for the software-navbar actions. Dashboard foregrounding remains routine; Reload
 * deliberately restarts a renderer and therefore shares the exact-request physical-approval policy
 * used by the other sensitive process and power operations. */
internal suspend fun handleRemoteAction(call: ApplicationCall, dependencies: RemoteActionRouteDependencies) {
    val parameters = receiveBoundedFormParameters(call) ?: return
    val action = parameters["a"]
    if (action !in REMOTE_ACTIONS) {
        call.respondText("bad-action\n", status = HttpStatusCode.BadRequest)
        return
    }
    val sensitive = when (action) {
        "reload" -> SensitiveOperation.DASHBOARD_RELOAD to "Reload the dashboard renderer"
        "reboot" -> SensitiveOperation.DEVICE_REBOOT to "Reboot this panel"
        else -> null
    }
    if (sensitive != null && !dependencies.authorizeSensitive(
            call,
            sensitive.first,
            exactHttpApprovalPayload(call, parameters.canonicalDigest()),
            sensitive.second,
        )
    ) return
    dependencies.admit(call, action!!)
}

/** One renderer-sensitive execution seam shared by the live queue and endpoint behavior tests. */
internal fun executeRemoteDashboardAction(
    action: String,
    dashboardPackage: String,
    launch: (String) -> Unit,
    reload: (String) -> Unit,
): Boolean = when (action) {
    "dashboard" -> { launch(dashboardPackage); true }
    "reload" -> { reload(dashboardPackage); true }
    else -> false
}

internal val REMOTE_ACTIONS = setOf(
    "back", "recents", "launcher", "admin_launcher", "dashboard", "reload", "reboot", "volup", "voldn",
)

/** Result of validating a direct config POST before any preference or controller mutation. */
internal sealed class ConfigPostParameters {
    data class Ok(val values: Parameters) : ConfigPostParameters()
    data class Bad(val reason: String) : ConfigPostParameters()
}

/** Small HTTP boundary over the service-owned adaptive-brightness runtime. The default is deliberately
 * read-safe and mutation-closed so the UI/API can land before the model, history and HA transport are
 * wired into the service. JSON is produced by the owner to avoid copying its snapshots here. */
internal interface AutoBrightnessHttpApi {
    fun statusJson(): String
    fun historyJson(hours: Int = 168, sensitivity: Int? = null, minimumPercent: Int? = null): String
    fun haSourcesJson(query: String, limit: Int): String
    suspend fun validateHaSource(entityId: String): AutoBrightnessHttpValidation
    suspend fun selectHaSource(entityId: String?): AutoBrightnessHttpAction
    fun resetHistory(): AutoBrightnessHttpAction
    fun resumeFullAuto(): AutoBrightnessHttpAction

    /** Why the Ambient dashboard theme resolves as it does, or null when the runtime cannot say. */
    fun ambientTheme(): AmbientThemeReport? = null

    companion object {
        val UNAVAILABLE: AutoBrightnessHttpApi = object : AutoBrightnessHttpApi {
            override fun statusJson(): String =
                """{"available":false,"state":"unavailable","sourceRevision":null,"detail":"Adaptive brightness runtime is not connected."}"""

            override fun historyJson(hours: Int, sensitivity: Int?, minimumPercent: Int?): String =
                """{"available":false,"hours":$hours,"bucket_minutes":0,"sourceRevision":null,"latestEpochMinute":null,"points":[]}"""

            override fun haSourcesJson(query: String, limit: Int): String =
                """{"available":false,"items":[]}"""

            override suspend fun validateHaSource(entityId: String): AutoBrightnessHttpValidation =
                AutoBrightnessHttpValidation(AutoBrightnessHttpAction.unavailable())

            override suspend fun selectHaSource(entityId: String?): AutoBrightnessHttpAction =
                AutoBrightnessHttpAction.unavailable()

            override fun resetHistory(): AutoBrightnessHttpAction = AutoBrightnessHttpAction.unavailable()
            override fun resumeFullAuto(): AutoBrightnessHttpAction = AutoBrightnessHttpAction.unavailable()
        }
    }
}

internal data class AutoBrightnessHttpValidation(
    val action: AutoBrightnessHttpAction,
    val authOwner: io.github.maxlyth.hapaneld.HaAuthOwner? = null,
)

internal data class AutoBrightnessHttpAction(val statusCode: Int, val json: String) {
    init { require(statusCode in 200..599); require(json.isNotBlank()) }

    companion object {
        fun ok(json: String = """{"ok":true}""") = AutoBrightnessHttpAction(200, json)
        fun unavailable() = AutoBrightnessHttpAction(
            503,
            """{"ok":false,"error":"Adaptive brightness runtime is not connected."}""",
        )
    }
}

internal data class AutoBrightnessHistoryParameters(
    val hours: Int,
    val sensitivity: Int?,
    val minimumPercent: Int?,
)

/** Compact read-only boundary over the service-owned auto-sleep runtime. Configuration continues to
 * use the ordinary schema/config transaction; the runtime owns the coherent bounded status JSON. */
internal interface AutoSleepHttpApi {
    fun statusJson(): String
    suspend fun historyJson(hours: Int = 6): String
    suspend fun prerequisite(): HaPanelAreaPrerequisite
    fun setSourceIncluded(areaKey: String, sourceKey: String, included: Boolean): HaPresenceSourceUpdate

    /** The panel's area changed; the runtime must re-read its configuration. Kept abstract so a service
     * implementation cannot silently compile with a no-op while the running discovery keeps stale room. */
    fun noteAreaChanged()

    companion object {
        val UNAVAILABLE: AutoSleepHttpApi = object : AutoSleepHttpApi {
            override fun statusJson(): String =
                """{"available":false,"enabled":false,"phase":"unavailable","reason":"runtime_unavailable","learned_lease_ms":null,"source_count":0,"manual_suppression":false,"detail":""}"""

            override suspend fun historyJson(hours: Int): String =
                """{"available":false,"hours":$hours,"bucket_ms":60000,"window_start_epoch_ms":null,"window_end_epoch_ms":null,"warmup_ms":3600000,"learned_lease_ms":null,"source_scope":"selected_area_sources","area_sources_only":true,"source_count":0,"exclusions":["past_touch","panel_proximity","manual_override_or_suppression","screen_wake","historical_learning_changes"],"segments":[],"detail":"runtime_unavailable"}"""

            override suspend fun prerequisite() = HaPanelAreaPrerequisite(
                HaPanelAreaPrerequisitePhase.UNAVAILABLE,
                detail = "Auto-sleep Area discovery is unavailable",
            )

            override fun noteAreaChanged() {} // no runtime exists to refresh

            override fun setSourceIncluded(areaKey: String, sourceKey: String, included: Boolean) =
                HaPresenceSourceUpdate.UNAVAILABLE
        }
    }
}

/**
 * The lifecycle suffix on `/health`, rendered from ONE atomic snapshot so the state and its source can
 * never come from different moments. Empty when the panel is not watching or no service owns lifecycle
 * tracking, which keeps the line unchanged for every existing consumer. `ha_src` appears only when a
 * source actually OBSERVED the state: the initial `normal` and a locally noticed `connection_lost` are
 * the panel's own inferences, and naming a source for them would claim an observation nobody made.
 * Pure — unit-tested in `HaLifecycleSurfaceContractTest`.
 */
internal fun haLifecycleHealthToken(watching: Boolean, snap: HaLifecycle.Snapshot?): String {
    if (!watching || snap == null) return ""
    val src = snap.source?.let { " ha_src=${it.name.lowercase()}" }.orEmpty()
    // The refusal rides the same observation because the diagnostics row explains it and that row is
    // now refreshed from this line; deriving it from a second read would reintroduce the divergence
    // between surfaces that the one-shot banner had.
    val refused = if (snap.refused) " ha_refused=1" else ""
    return " ha=${snap.state.wireValue}$src$refused"
}

/** Add Panel Assistant's stable discovery pseudonym without exposing the Android ID itself. */
internal fun panelAssistantDiscoveryHealthToken(androidId: String): String =
    panelAssistantDiscoveryId(androidId)?.let { " did=$it" }.orEmpty()

/** Which installed identity answered: during the application-id migration a panel can hold both. */
internal fun packageHealthToken(packageName: String): String = " pkg=$packageName"

/** The build number beside the version name, so a reader can tell two builds of one release apart. */
internal fun versionCodeHealthToken(versionCode: Int): String = " vc=$versionCode"

internal fun autoSleepHistoryHours(hours: String?): Int {
    val parsed = hours?.toIntOrNull() ?: if (hours == null) 6 else null
    require(parsed != null && parsed in 1..48) { "hours must be between 1 and 48" }
    return parsed
}

internal fun autoSleepRequiresHaAdmission(
    currentEnabled: Boolean,
    currentSource: String,
    requestedEnabled: Boolean,
    requestedSource: String,
): Boolean = requestedEnabled && requestedSource == "home_assistant" &&
    (!currentEnabled || currentSource != "home_assistant")

internal fun autoSleepConfigErrorJson(error: String, message: String): String = JSONObject()
    .put("ok", false)
    .put("error", error)
    .put("message", message)
    .toString()

internal fun autoBrightnessHistoryParameters(
    hours: String?,
    sensitivity: String?,
    minimumPercent: String? = null,
): AutoBrightnessHistoryParameters {
    val boundedHours = if (hours == null) 168 else hours.toIntOrNull()
        ?: throw IllegalArgumentException("hours must be between 1 and 168")
    require(boundedHours in 1..168) { "hours must be between 1 and 168" }
    val boundedSensitivity = sensitivity?.let {
        it.toIntOrNull()?.takeIf { value -> value in 0..100 }
            ?: throw IllegalArgumentException("sensitivity must be between 0 and 100")
    }
    val minimumRange = SettingsRegistry.MINIMUM_AUTOMATIC_PERCENT..SettingsRegistry.MAX_AUTOMATIC_MINIMUM_PERCENT
    val boundedMinimum = minimumPercent?.let {
        it.toIntOrNull()?.takeIf { value -> value in minimumRange }
            ?: throw IllegalArgumentException(
                "minimum_percent must be between ${minimumRange.first} and ${minimumRange.last}",
            )
    }
    return AutoBrightnessHistoryParameters(boundedHours, boundedSensitivity, boundedMinimum)
}

internal fun builtinRendererNeedsConnection(
    currentPackage: String,
    currentHaUrl: String,
    currentHasCredentials: Boolean,
    requestedPackage: String?,
    requestedHaUrl: String?,
    requestHasCredentials: Boolean,
): Boolean {
    if ((requestedPackage ?: currentPackage) != SystemController.BUILTIN_DASHBOARD) return false
    val effectiveUrl = (requestedHaUrl ?: currentHaUrl).trim()
    val effectiveCredentials = effectiveUrl.isNotBlank() && (currentHasCredentials || requestHasCredentials)
    return !effectiveCredentials
}

/** A handover POST after the panel has decided whether the address it was given actually answers. */
internal data class HandoverConfigPost(
    val parameters: Parameters,
    /** Null when this POST carried no handover to settle, so the response says nothing about one. */
    val outcome: HaUrlHandover.Outcome? = null,
    val url: String = "",
)

/**
 * Turn a POST carrying a handed-over Home Assistant URL into the settings write it should become.
 *
 * Rewrites the posted parameters rather than persisting separately, so an accepted address takes
 * exactly the same commit and live-apply path as one a person typed — the renderer reconfigure, the
 * read-back check and the mutation plan all behave identically. This mirrors
 * `augmentPostWithDiscoveredHaUrlForMqttOnboarding`, which fills the same field from discovery.
 *
 * Three outcomes:
 *  - **already configured** — [currentHaUrl] is set, so the handover is dropped and [verify] is never
 *    called. Adoption is re-runnable, and a retry must converge rather than overwrite a working panel
 *    or spend a probe proving something the panel no longer needs.
 *  - **verified** — promoted to `ha_url`, with the handover field and its reason cleared.
 *  - **failed** — the address is kept with its reason, so the wizard can offer a correction that shows
 *    what was tried instead of an empty field.
 *
 * The provenance marker `ha_setup_handover` is deliberately untouched: it rides the POST as an ordinary
 * key and is stored whatever [verify] decides, because an address that did not answer still means Home
 * Assistant deployed this panel.
 *
 * [verify] is a parameter rather than a call so the whole decision is exercisable without a network.
 */
internal suspend fun rewritePostForHandover(
    posted: Parameters,
    currentHaUrl: String,
    verify: suspend (String) -> HaUrlHandover.Outcome,
): HandoverConfigPost {
    val offered = posted["ha_url_handover"]?.trim().orEmpty()
    if (offered.isBlank()) {
        // Saving an address directly is how the correction card is answered, so it closes the failed
        // handover out. Without this the attempted address and its reason would outlive the problem
        // they describe, with no way to clear them: the reason is refused from the network by design,
        // so nothing else could ever retract it.
        if (posted["ha_url"]?.isNotBlank() == true) {
            return HandoverConfigPost(
                Parameters.build {
                    for (name in posted.names()) {
                        if (name == "ha_url_handover") continue
                        posted.getAll(name).orEmpty().forEach { append(name, it) }
                    }
                    append("ha_url_handover", "")
                    append("ha_url_handover_reason", "")
                },
            )
        }
        return HandoverConfigPost(posted)
    }
    val carried = Parameters.build {
        for (name in posted.names()) {
            if (name == "ha_url_handover" || name == "ha_setup_handover") continue
            posted.getAll(name).orEmpty().forEach { append(name, it) }
        }
        // A handed-over address means Home Assistant deployed this panel whether or not the address
        // then answers, so provenance is recorded here rather than left to the caller to remember.
        // Every step that skips a question reads this marker, never the address's presence.
        append("ha_setup_handover", "true")
    }
    if (currentHaUrl.isNotBlank()) return HandoverConfigPost(carried)
    val outcome = verify(offered)
    val augmented = Parameters.build {
        for (name in carried.names()) {
            carried.getAll(name).orEmpty().forEach { append(name, it) }
        }
        if (outcome.verified) {
            append("ha_url", offered)
            append("ha_url_handover", "")
            append("ha_url_handover_reason", "")
        } else {
            append("ha_url_handover", offered)
            append("ha_url_handover_reason", outcome.reason)
        }
    }
    return HandoverConfigPost(augmented, outcome, offered)
}

internal fun shouldDiscoverHaUrlForMqttOnboarding(currentHaUrl: String, posted: Parameters): Boolean {
    if (currentHaUrl.isNotBlank()) return false
    if (posted["ha_url"] != null) return false
    return posted["mqtt_broker"]?.isNotBlank() == true ||
        posted["mqtt_user"] != null ||
        posted["mqtt_password"] != null
}

/** Maps an [io.github.maxlyth.hapaneld.assist.AssistPipelineDirectory.Result] to the exact
 *  `GET /api/v1/voice/pipelines` response, pure so every branch is unit-testable without a routed
 *  request. */
internal fun voicePipelinesResponse(
    result: io.github.maxlyth.hapaneld.assist.AssistPipelineDirectory.Result,
): Pair<HttpStatusCode, String> = when (result) {
    is io.github.maxlyth.hapaneld.assist.AssistPipelineDirectory.Result.Available -> {
        val pipelines = result.pipelines.joinToString(",") {
            "{\"id\":${Json.str(it.id)},\"name\":${Json.str(it.name)}}"
        }
        HttpStatusCode.OK to "{\"pipelines\":[$pipelines],\"preferred\":${Json.str(result.preferred)}}"
    }
    is io.github.maxlyth.hapaneld.assist.AssistPipelineDirectory.Result.NotConfigured ->
        HttpStatusCode.ServiceUnavailable to "{\"error\":\"not-configured\",\"reason\":${Json.str(result.reason)}}"
    is io.github.maxlyth.hapaneld.assist.AssistPipelineDirectory.Result.Unavailable ->
        HttpStatusCode.ServiceUnavailable to "{\"error\":\"unavailable\",\"reason\":${Json.str(result.reason)}}"
}

/** Refuses `POST /api/v1/voice/test` before the trigger is ever called — returns the 409 reason, or
 *  null to proceed. Checked ahead of [io.github.maxlyth.hapaneld.assist.VoiceTestTrigger] so a disabled
 *  or capability-less panel never depends on whether the coordinator lane happens to be wired up. */
internal fun voiceTestRefusal(hasMicrophone: Boolean, voiceEnabled: Boolean): String? = when {
    !hasMicrophone -> "this panel has no microphone capability"
    !voiceEnabled -> "voice assistant is disabled"
    else -> null
}

/** Refuses `GET /api/v1/voice/pipelines` before [io.github.maxlyth.hapaneld.assist.AssistPipelineDirectory]
 *  is ever called — returns the reason to report as the existing `{"error":"unavailable",reason}` 503
 *  shape ([voicePipelinesResponse]'s `Unavailable` branch), or null to proceed to the directory. The
 *  route's docs and OpenAPI both promise this endpoint "requires a microphone-capable panel" — the
 *  directory itself has no live capability signal, so the capability-less case must be checked here,
 *  exactly like [voiceTestRefusal] checks it ahead of the trigger. */
internal fun voicePipelinesRefusal(hasMicrophone: Boolean): String? =
    if (!hasMicrophone) "this panel has no microphone capability" else null

/** Maps a [io.github.maxlyth.hapaneld.assist.VoiceTestTrigger.Result] to the exact
 *  `POST /api/v1/voice/test` response, pure so every branch is unit-testable without a routed request. */
internal fun voiceTestTriggerResponse(
    result: io.github.maxlyth.hapaneld.assist.VoiceTestTrigger.Result,
): Pair<HttpStatusCode, String> = when (result) {
    is io.github.maxlyth.hapaneld.assist.VoiceTestTrigger.Result.Accepted ->
        HttpStatusCode.Accepted to "{\"accepted\":true}"
    is io.github.maxlyth.hapaneld.assist.VoiceTestTrigger.Result.Refused ->
        HttpStatusCode.Conflict to "{\"reason\":${Json.str(result.reason)}}"
    is io.github.maxlyth.hapaneld.assist.VoiceTestTrigger.Result.Unavailable ->
        HttpStatusCode.ServiceUnavailable to "{\"reason\":${Json.str(result.reason)}}"
}

/**
 * Validate and normalize every direct-config value in one pass. Historically the bespoke route
 * normalized only panel_id while malformed booleans became false, numeric values were silently
 * clamped/ignored, and large identity strings could be repeated into every MQTT discovery payload.
 * Keeping this admission step ahead of applyBatch gives form, JSON and registry command paths the
 * same schema semantics and ensures a bad field cannot produce a partial commit.
 *
 * [caps] admits capability-gated ENUM choices (see [SettingSpec.optionRequires]). It defaults to an
 * all-false snapshot so an unparameterized call is fail-closed: a gated choice is refused rather than
 * waved through by a caller that had no snapshot to offer.
 */
internal fun normalizeConfigPostParameters(
    raw: Parameters,
    caps: Capabilities = Capabilities(),
): ConfigPostParameters {
    val normalized = Parameters.build {
        for (rawName in raw.names()) {
            val all = raw.getAll(rawName).orEmpty()
            if (all.size != 1) return ConfigPostParameters.Bad("$rawName: expected one value")
            val rawValue = all.single()
            // The retired sensitivity key is accepted on its old scale and carried onto the new one, the
            // same way the migration carries a stored value. Without this a script or automation written
            // against the previous release does not merely lose that key: this admission step is atomic,
            // so the whole request is refused and every other setting in it is dropped too.
            val renamed = rawName == SettingsRegistry.LEGACY_SENSITIVITY_KEY
            val name = if (renamed) SettingsRegistry.RESPONSE_PERCENT_KEY else rawName
            val value = if (renamed) {
                rawValue.trim().toIntOrNull()?.let { Migrations.rescaleSensitivity(it).toString() } ?: rawValue
            } else {
                rawValue
            }
            val spec = SettingsRegistry.spec(name)
            val accepted = when {
                spec != null -> {
                    if (spec.readOnly) return ConfigPostParameters.Bad("$name: read-only")
                    if (name in SettingsRegistry.directPostExcludedKeys) {
                        val owner = if (name in SettingsRegistry.machineOwnedKeys) {
                            "machine-owned state"
                        } else {
                            "specialized Entities API state"
                        }
                        return ConfigPostParameters.Bad("$name: $owner is not directly postable")
                    }
                    when (val result = SettingValue.validate(spec, value)) {
                        is Validation.Ok -> {
                            // A choice can be valid vocabulary yet unavailable on this hardware. Refuse it
                            // here, where the failure is one explicit 400, rather than letting it persist
                            // and be silently coerced back on the next read.
                            if (spec.optionRequires.isNotEmpty() && result.normalized !in spec.optionsFor(caps)) {
                                return ConfigPostParameters.Bad(
                                    "$name: ${result.normalized} is not available on this panel",
                                )
                            }
                            result.normalized
                        }
                        is Validation.Bad -> return ConfigPostParameters.Bad(result.reason)
                    }
                }
                name.startsWith(SettingsRegistry.HA_EXPOSE_PREFIX) -> {
                    if (SettingsRegistry.parseExposure(name) == null) {
                        return ConfigPostParameters.Bad("$name: unknown exposure setting")
                    }
                    SettingValue.parseBool(value)?.toString()
                        ?: return ConfigPostParameters.Bad("$name: expected a boolean")
                }
                name == "ha_token_expiry" -> {
                    val expiry = value.trim().toLongOrNull()
                        ?: return ConfigPostParameters.Bad("ha_token_expiry: expected an integer")
                    if (expiry < 0L) return ConfigPostParameters.Bad("ha_token_expiry: must be ≥ 0")
                    expiry.toString()
                }
                // The handover keys are a machine channel between the Panel Assistant integration and
                // this panel, not settings. They are deliberately absent from SettingsRegistry so they
                // can never appear on the Configure page, in the settings catalogue, or in a config
                // bundle, exactly as `http_allowed_hosts` is.
                name == "ha_setup_handover" -> {
                    SettingValue.parseBool(value)?.toString()
                        ?: return ConfigPostParameters.Bad("ha_setup_handover: expected a boolean")
                }
                name == "ha_url_handover" -> {
                    val trimmed = value.trim()
                    if (trimmed.isEmpty()) {
                        ""
                    } else {
                        io.github.maxlyth.hapaneld.config.normalizeHttpOriginUrl(trimmed)
                            ?: return ConfigPostParameters.Bad(
                                "ha_url_handover: expected an http:// or https:// URL with no " +
                                    "embedded credentials",
                            )
                    }
                }
                // Refused from the network outright. The panel's own probe decides this and appends it
                // after admission; accepting it here would let the party that supplies an address also
                // declare that address reachable.
                name == "ha_url_handover_reason" ->
                    return ConfigPostParameters.Bad(
                        "ha_url_handover_reason: the panel's own verification writes this",
                    )
                name == "http_allowed_hosts" -> {
                    val trimmed = value.trim()
                    if (trimmed.length > 4_096) {
                        return ConfigPostParameters.Bad("http_allowed_hosts: must be at most 4096 characters")
                    }
                    val hosts = trimmed.split(Regex("[\\s,]+")).filter(String::isNotEmpty)
                    if (hosts.size > 128 || hosts.any { it.length > 253 || it.any(Char::isWhitespace) }) {
                        return ConfigPostParameters.Bad("http_allowed_hosts: expected at most 128 host names")
                    }
                    hosts.distinct().joinToString(" ")
                }
                else -> return ConfigPostParameters.Bad("$name: unknown setting")
            }
            append(name, accepted)
        }
    }
    return ConfigPostParameters.Ok(normalized)
}

/**
 * Turn what the caller said about the Companion into what this panel will actually put in the archive.
 *
 * [CompanionBackupRequest.REQUIRED] and [CompanionBackupRequest.EXCLUDED] are answered exactly and never
 * consult [companionInstalled], so no probe result can quietly downgrade a request that named the login:
 * an explicit `true` on a Companion-free panel still reaches the capture path and still refuses there,
 * with the reason the operator needs. Only an omitted request asks whether there is anything to include,
 * and it asks about installation alone. A panel that *has* the Companion but cannot capture it — a stale
 * helper, a busy helper, a database that will not checkpoint — is not a panel with nothing to include, so
 * omission keeps failing loudly there rather than handing back an archive that silently lacks the login.
 */
internal fun resolveCompanionInclusion(
    request: CompanionBackupRequest,
    companionInstalled: () -> Boolean,
): Boolean = when (request) {
    CompanionBackupRequest.REQUIRED -> true
    CompanionBackupRequest.EXCLUDED -> false
    CompanionBackupRequest.OMITTED -> companionInstalled()
}

/** Conservative disk peak while source entries, archive plaintext and optional ciphertext overlap. */
internal fun backupStagingRequirement(includeCompanion: Boolean, encrypted: Boolean): Long {
    val sources = PaneldServer.MAX_BACKUP_MANIFEST_BYTES +
        2L * PaneldServer.MAX_ENTITY_BACKUP_TEXT_BYTES +
        PaneldServer.MAX_PROFILE_BACKUP_ENTRY_BYTES +
        if (includeCompanion) PaneldServer.MAX_COMPANION_BACKUP_BYTES else 0L
    val archives = PaneldServer.MAX_RESTORE_BYTES * if (encrypted) 2L else 1L
    val archivePeak = sources + archives
    val rawCapturePeak = if (includeCompanion) CompanionHelperProtocol.MAX_BACKUP_STREAM_BYTES else 0L
    return PaneldServer.BACKUP_STORAGE_MARGIN_BYTES + maxOf(archivePeak, rawCapturePeak)
}

internal class BackupStagingRetainedException : Exception("sensitive backup staging file retained")

/** Attempt one bounded cleanup without allowing it to replace an earlier backup failure. */
internal inline fun attemptBackupCleanup(primary: Exception?, cleanup: () -> Unit): Exception? = try {
    cleanup()
    primary
} catch (failure: Exception) {
    if (primary == null) failure else primary.apply {
        if (failure !== this) addSuppressed(failure)
    }
}

internal inline fun <R> withBackupArtifactCleanup(
    plain: File,
    sealed: () -> File?,
    ownedFiles: () -> List<File>,
    block: () -> R,
): R {
    var primary: Exception? = null
    var failed = false
    try {
        return block()
    } catch (error: Exception) {
        failed = true
        primary = error
        primary = attemptBackupCleanup(primary) { plain.delete() }
        primary = attemptBackupCleanup(primary) { sealed()?.delete() }
        throw error
    } finally {
        ownedFiles().forEach { file ->
            primary = attemptBackupCleanup(primary) { file.delete() }
        }
        if (!failed) primary?.let { throw it }
    }
}

internal inline fun <T : java.io.Closeable, R> withBackupCaptureAndPlaintext(
    capture: T?,
    createPlaintext: () -> File,
    block: (T?, File) -> R,
): R {
    var primary: Exception? = null
    try {
        return block(capture, createPlaintext())
    } catch (error: Exception) {
        primary = error
        throw error
    } finally {
        val failure = attemptBackupCleanup(primary) { capture?.close() }
        if (primary == null && failure != null) throw failure
    }
}

internal fun encryptedBackupArtifact(
    plain: File,
    sealed: File,
    stateUnavailable: Boolean = false,
): PanelBackup.Artifact {
    val retained = runCatching {
        plain.delete()
        plain.exists()
    }.getOrDefault(true)
    if (retained) {
        // The encrypted temp is never returned, even if its best-effort cleanup also fails.
        runCatching { sealed.delete() }
        throw BackupStagingRetainedException()
    }
    return PanelBackup.Artifact(sealed, stateUnavailable = stateUnavailable)
}

/** Render one trusted Dashboard control without accepting pre-quoted HTML attribute fragments. */
internal fun dashboardControlButtonHtml(
    action: String,
    labelHtml: String,
    disabledReason: String?,
    style: String = "",
): String {
    require(action.matches(Regex("[a-z_]+"))) { "invalid Dashboard control action" }
    fun attr(value: String): String = value
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    val styleAttr = style.takeIf(String::isNotBlank)?.let { " style=\"${attr(it)}\"" }.orEmpty()
    val titleAttr = disabledReason?.let { " title=\"${attr(it)}\"" }.orEmpty()
    val disabledAttr = if (disabledReason != null) " disabled" else ""
    return "<button class=\"pbtn\"$styleAttr$titleAttr onclick=\"act('$action')\"$disabledAttr>$labelHtml</button>"
}

/**
 * Ktor CIO HTTP surface on :8888. Serves the TTS-announce contract plus a small panel info/config
 * UI at `/` (the device's `configuration_url`, so HA shows a "Visit" link).
 *
 * Routes:
 *   GET  /         panel info + panel_id config form (HTML)
 *   POST /config   set panel_id (form `panel_id`), then live-reconfigure
 *   GET  /health   200 with version + panel id
 *   POST /play     body has a URL (raw or `{"url":"…"}`) -> 200 "playing", background playback;
 *                  no URL -> 400 "no-url"
 *
 * [managementProjection] returns the facts/live/capability view; [onReconfigure] rebuilds service-owned
 * network integrations after config writes. Both are supplied by the service runtime.
 */
internal fun shouldSnapshotConfigSetting(key: String, zigbeeRouterConfigured: Boolean): Boolean =
    key != "zigbee_router" || zigbeeRouterConfigured

internal fun installFormWantsHtml(accept: String?): Boolean =
    accept?.contains("text/html", ignoreCase = true) == true

/** The Companion-dependent parts of the backup card. Empty throughout when the app is absent. */
internal data class BackupCompanionCopy(
    val showLoginChoice: Boolean,
    val explainHelperRequirement: Boolean,
)

/**
 * Decides what the backup card may say about the HA Companion.
 *
 * A panel without the Companion installed has nothing to say about it: the include-login checkbox would
 * offer to back up a login that does not exist, and the "needs the current helper" note would advertise a
 * capability for an absent app. So every mention is gated on the app being [installed], and only the
 * *offer* additionally requires the [helper]. Keeping this pure keeps it directly testable.
 */
internal fun backupCompanionCopy(installed: Boolean, helper: Boolean): BackupCompanionCopy {
    if (!installed) return BackupCompanionCopy(showLoginChoice = false, explainHelperRequirement = false)
    return BackupCompanionCopy(showLoginChoice = helper, explainHelperRequirement = !helper)
}

internal fun projectConfigSnapshot(
    specs: Iterable<SettingSpec>,
    zigbeeRouterConfigured: Boolean,
    excludedKeys: Set<String> = emptySet(),
    effectiveValue: (SettingSpec) -> String,
): LinkedHashMap<String, String> {
    val snapshot = LinkedHashMap<String, String>()
    specs.forEach { spec ->
        if (spec.transient || spec.key in excludedKeys) return@forEach
        if (!shouldSnapshotConfigSetting(spec.key, zigbeeRouterConfigured)) return@forEach
        snapshot[spec.key] = effectiveValue(spec)
    }
    return snapshot
}

internal data class DirectConfigMutationPlan(
    val changedKeys: Set<String>,
    val changedLive: List<Pair<String, String>>,
) {
    val isNoOp: Boolean get() = changedKeys.isEmpty()
    val requiresReconfigure: Boolean get() = changedKeys.any { it !in SettingsRegistry.liveApplyKeys() }
}

/** Direct settings whose real writer is a post-commit subsystem transition rather than Config's
 * registry writer. The list is exact: adding a key requires an owner callback and walker evidence. */
internal val DIRECT_CONFIG_DELEGATED_KEYS: Set<String> = setOf("dashboard_entity_learning")

/**
 * Catalogue-wide persistence floor for ordinary direct-POST settings. The bespoke block which follows
 * still owns coupled credentials, secondary keys and effect planning, but no newly registered ordinary
 * setting can be silently read and dropped: a validated changed value reaches Config automatically.
 */
internal fun stageDirectConfigRegistryValues(
    config: Config,
    posted: Map<String, String>,
    changedKeys: Set<String>,
): Set<String> = buildSet {
    SettingsRegistry.directPostable().forEach { spec ->
        if (spec.liveApply || spec.key in DIRECT_CONFIG_DELEGATED_KEYS || spec.key !in changedKeys) return@forEach
        val raw = posted[spec.key] ?: return@forEach
        val normalized = (SettingValue.validate(spec, raw) as? Validation.Ok)?.normalized ?: return@forEach
        config.setRaw(spec, normalized)
        add(spec.key)
    }
}

/** Route the exact post-commit owner-managed subset through an injected real owner. */
internal fun applyDirectConfigDelegatedSettings(
    posted: Map<String, String>,
    changedKeys: Set<String>,
    apply: (String, String) -> Boolean,
): Set<String> = buildSet {
    DIRECT_CONFIG_DELEGATED_KEYS.forEach { key ->
        if (key in changedKeys && posted[key]?.let { apply(key, it) } == true) add(key)
    }
}

internal data class DirectCredentialEffects(val haChanged: Boolean)

/** Stage the three coupled log destination fields through the endpoint owner which honours a scheme
 * or port embedded in the host value. */
internal fun stageDirectLogShipping(config: Config, posted: Map<String, String>) {
    val enabled = posted["log_ship_enabled"]?.toBooleanStrictOrNull()
    val host = posted["log_ship_host"]
    val port = posted["log_ship_port"]?.toIntOrNull()
    val protocol = posted["log_ship_protocol"]
    if (enabled != null || host != null || port != null || protocol != null) {
        config.setLogShipping(
            enabled ?: config.logShipEnabled,
            host ?: config.logShipHost,
            port ?: config.logShipPort,
            protocol ?: config.logShipProtocol,
        )
    }
}

/** Stage the direct form's coupled credential groups through their real Config owners. Blank secret
 * placeholders preserve existing credentials except where an owner field is explicitly cleared or a
 * hardened origin changes. Kept production-used so mutations to those dependent clears reach the JVM
 * contract instead of surviving behind a per-key writer test. */
internal fun stageDirectCredentialSettings(
    config: Config,
    posted: Map<String, String>,
): DirectCredentialEffects {
    val broker = posted["mqtt_broker"]
    val user = posted["mqtt_user"]
    val mqttAddressFamily = posted["mqtt_address_family"]
    val brokerChanged = broker != null && broker != config.mqttBroker
    val password = when {
        user != null && user.isEmpty() -> ""
        brokerChanged && config.hardenedSecurityEnabled -> posted["mqtt_password"]?.takeIf(String::isNotEmpty) ?: ""
        else -> posted["mqtt_password"]?.takeIf(String::isNotEmpty)
    }
    if (broker != null || user != null || password != null || mqttAddressFamily != null) {
        config.setMqtt(
            broker ?: config.mqttBroker,
            user ?: config.mqttUser,
            password,
            mqttAddressFamily,
        )
    }

    val previousUrl = config.haUrl
    val previousToken = config.haToken
    val previousRefresh = config.haRefreshToken
    val previousExpiry = config.haTokenExpiry
    val previousClientId = config.haClientId
    val url = posted["ha_url"]
    val hardenedOriginChange = config.hardenedSecurityEnabled && url != null &&
        url.trimEnd('/') != previousUrl.trimEnd('/')
    val token = when {
        url != null && url.isEmpty() -> ""
        hardenedOriginChange -> posted["ha_token"]?.takeIf(String::isNotEmpty) ?: ""
        else -> posted["ha_token"]?.takeIf(String::isNotEmpty)
    }
    if (url != null || token != null) config.setHaConnection(url ?: previousUrl, token)

    val clearingHa = url != null && url.isEmpty()
    val refresh = when {
        clearingHa -> ""
        hardenedOriginChange -> posted["ha_refresh_token"]?.takeIf(String::isNotEmpty) ?: ""
        else -> posted["ha_refresh_token"]?.takeIf(String::isNotEmpty)
    }
    refresh?.let(config::setHaRefreshToken)
    val expiry = posted["ha_token_expiry"]?.toLongOrNull() ?: if (hardenedOriginChange) 0L else null
    val clientId = posted["ha_client_id"]?.let { if (clearingHa) "" else it }
        ?: if (hardenedOriginChange) "" else null
    expiry?.let(config::setHaTokenExpiry)
    clientId?.let(config::setHaClientId)
    if (clearingHa) config.setHaRefreshToken("")

    val refreshCleared = token != null && token.isNotEmpty() && refresh == null && previousRefresh.isNotEmpty()
    if (refreshCleared) {
        config.setHaRefreshToken("")
        config.setHaTokenExpiry(0L)
    }
    return DirectCredentialEffects(
        haChanged = (url != null && url != previousUrl) ||
            (token != null && token != previousToken) ||
            (refresh != null && refresh != previousRefresh) || refreshCleared ||
            (expiry != null && expiry != previousExpiry) ||
            (clientId != null && clientId != previousClientId),
    )
}

/** Production-used iteration seam between HTTP planning and the shared service dispatcher. */
internal fun dispatchDirectConfigLiveSettings(
    changedLive: List<Pair<String, String>>,
    dispatch: (String, String) -> Unit,
) {
    changedLive.forEach { (key, value) -> dispatch(key, value) }
}

internal data class DirectConfigOrdinaryOutcomes(
    val applied: Set<String>,
    val rejected: Set<String>,
)

/** Canonical values a successful direct write must read back. Compute this while the transaction still
 * sees pre-commit state: a legacy log host may carry an embedded scheme/port which outranks a partial
 * update, and that precedence is no longer recoverable after the owner canonicalizes the stored host. */
internal fun directConfigExpectedReadBack(
    config: Config,
    posted: Map<String, String>,
): Map<String, String> = posted.toMutableMap().apply {
    LogShipEndpoint.canonicalUpdate(
        posted,
        config.logShipHost,
        config.logShipPort,
        config.logShipProtocol,
    )?.forEach { (key, value) -> if (key in posted) put(key, value) }
}

/** The compatibility preflight proves a plan is admissible; only committed read-back proves its
 * special in-batch writer actually ran. */
internal fun directUpdateChannelCommitted(
    requestedChanged: Boolean,
    changedKeys: Set<String>,
    requested: String?,
    actual: String,
): Boolean = requestedChanged && "update_channel" in changedKeys && requested == actual

/**
 * Describe durable outcomes from committed read-back, never from the planned changed-key set. This is
 * intentionally independent of which writer claimed a key: a read-then-drop handler and a setter whose
 * storage owner failed both compare unequal and therefore cannot be reported as applied.
 */
internal fun directConfigOrdinaryOutcomes(
    config: Config,
    posted: Map<String, String>,
    changedKeys: Set<String>,
    expectedReadBack: Map<String, String> = posted,
): DirectConfigOrdinaryOutcomes {
    val applied = linkedSetOf<String>()
    val rejected = linkedSetOf<String>()
    changedKeys.filterNot { it in SettingsRegistry.liveApplyKeys() }.forEach { key ->
        val expected = expectedReadBack[key] ?: return@forEach
        val spec = SettingsRegistry.spec(key)
        val actual = when {
            key in SettingsRegistry.directPostExcludedKeys -> null
            spec != null -> config.getRaw(spec)
            SettingsRegistry.parseExposure(key) != null -> {
                val exposed = requireNotNull(SettingsRegistry.parseExposure(key))
                config.haExposed(exposed.key, exposed.haExposedByDefault).toString()
            }
            key == "http_allowed_hosts" -> config.httpAllowedHostsRaw
            // Bespoke keys with no spec, read back from their own accessors so a handover is reported
            // applied on the evidence of what was stored rather than of what was planned.
            key == "ha_setup_handover" -> config.haSetupHandover.toString()
            key == "ha_url_handover" -> config.haUrlHandover
            key == "ha_url_handover_reason" -> config.haUrlHandoverReason
            else -> null
        }
        if (actual == expected) applied += key else rejected += key
    }
    return DirectConfigOrdinaryOutcomes(applied, rejected)
}

/** Name post-commit effect failures separately from their already-durable desired setting. */
internal fun directConfigEffectFailureOwner(entityLearningTransitionIncomplete: Boolean): String =
    if (entityLearningTransitionIncomplete) "dashboard_entity_learning_effect" else "renderer"

/** An exact channel candidate prepared without changing configuration, helper state, or packages. */
internal sealed interface SelfUpdateChannelPreflight {
    val message: String
    val presentation: InstallPresentation?

    data class Unresolved(
        override val message: String,
        override val presentation: InstallPresentation? = null,
    ) : SelfUpdateChannelPreflight
    data class UpToDate(
        override val message: String,
        override val presentation: InstallPresentation? = null,
    ) : SelfUpdateChannelPreflight
    data class Refused(
        override val message: String,
        override val presentation: InstallPresentation? = null,
    ) : SelfUpdateChannelPreflight
    class Ready(
        override val message: String,
        val requiresRecovery: Boolean,
        val revalidateForConfigCommit: () -> String?,
        val install: suspend () -> SelfUpdateChannelInstallResult,
        private val discardPrepared: () -> Unit,
        override val presentation: InstallPresentation? = null,
    ) : SelfUpdateChannelPreflight, AutoCloseable {
        override fun close() = discardPrepared()
    }
}

internal data class SelfUpdateChannelInstallResult(
    val message: String,
    val installed: Boolean,
    val presentation: InstallPresentation? = null,
)

internal data class SelfUpdateChannelMutation(
    val requested: String,
    val force: Boolean,
)

/** Only an enabled updater changing channels creates an immediate APK candidate. */
internal fun selfUpdateChannelMutation(
    currentChannel: String,
    currentSelfUpdate: Boolean,
    requestedValues: Map<String, String>,
): SelfUpdateChannelMutation? {
    val requested = requestedValues["update_channel"] ?: return null
    if (requested == currentChannel) return null
    val enabled = requestedValues["self_update"]?.let(SettingValue::parseBool) ?: currentSelfUpdate
    if (!enabled) return null
    return SelfUpdateChannelMutation(
        requested = requested,
        force = currentChannel == "prerelease" && requested == "stable",
    )
}

/** A restore already owns a destructive ticket and cannot hand it off to an asynchronous self-install.
 * Reject every actual channel change, including a bundle that simultaneously disables self-update; this
 * also guarantees a later rollback never needs to resolve/install a candidate under the restore owner. */
internal fun restoreChangesUpdateChannel(
    currentChannel: String,
    accepted: Map<String, String>,
): Boolean = accepted["update_channel"]?.let { it != currentChannel } == true

/** One request-scoped status refresh. A failed/incomplete fresh database observation must not fall back
 * to a previously healthy snapshot, because provisioning treats schema + quick_check as admission proof. */
internal data class RefreshedStatusStorage(
    val snapshot: StorageHealthSnapshot,
    val fresh: Boolean,
)

internal suspend fun refreshedStatusStorage(
    refreshRequested: Boolean,
    refreshUpdates: suspend () -> Unit,
    refreshStorage: suspend () -> StorageHealthSnapshot?,
    cachedStorage: () -> StorageHealthSnapshot,
): RefreshedStatusStorage {
    if (!refreshRequested) return RefreshedStatusStorage(cachedStorage(), fresh = false)
    refreshUpdates()
    val fresh = refreshStorage()
    return RefreshedStatusStorage(fresh ?: StorageHealthSnapshot.UNCHECKED, fresh = fresh != null)
}

internal fun validDatabaseObservationNonce(raw: String?): String? = raw?.takeIf {
    it.length == 32 && it.all { char -> char in '0'..'9' || char in 'a'..'f' }
}

internal fun databaseObservationProof(
    refreshRequested: Boolean,
    rawNonce: String?,
    observation: RefreshedStatusStorage,
): String? = validDatabaseObservationNonce(rawNonce).takeIf { refreshRequested && observation.fresh }

/**
 * The pending keys the Configure page may present as unappliable rather than about to apply.
 *
 * Every stalled key is also a pending key, and the page reads the desired value from `apply_pending`, so
 * a stall reported for anything outside it would mark a row the page is not showing as saved-and-waiting
 * — the value would appear unappliable with nothing to say what value. Ordered so the response is stable.
 */
internal fun stalledApplyKeys(pending: Set<String>, stalled: Set<String>): List<String> =
    stalled.filter { it in pending }.sorted()

/** Server-side equality is the transaction authority; browser dirty tracking is only a UX hint. */
internal fun planDirectConfigMutation(
    posted: Map<String, String>,
    before: Map<String, String>,
): DirectConfigMutationPlan {
    val liveKeys = SettingsRegistry.liveApplyKeys()
    val changed = linkedSetOf<String>()
    posted.forEach { (key, value) ->
        val spec = SettingsRegistry.spec(key)
        val unchangedSecretPlaceholder = spec?.secret == true && value.isEmpty()
        // "Unchanged" means the write would not alter STORED state, which is the canonical form the
        // validator produces compared against the raw value already held. Comparing renderer semantics
        // instead made canonicalization unreachable: replacing a stored `/` or `/?kiosk` with the blank
        // that means "follow the account default" resolves identically, so it was discarded as a no-op
        // and the stale spelling survived — reopening the picker in Custom after the user had chosen and
        // saved Auto. A stored value that is already canonical still compares equal, so re-saving an
        // unchanged setting remains a no-op.
        val equivalent = when (val validated = spec?.let { SettingValue.validate(it, value) }) {
            is Validation.Ok -> validated.normalized == before[key].orEmpty()
            else -> before[key] == value
        }
        if (!unchangedSecretPlaceholder && !equivalent) changed += key
    }
    return DirectConfigMutationPlan(
        changedKeys = changed,
        changedLive = liveKeys.mapNotNull { key ->
            posted[key]?.takeIf { key in changed }?.let { key to it }
        },
    )
}

internal fun configMutationWantsJson(accept: String?, contentType: String?): Boolean =
    accept?.contains("application/json", ignoreCase = true) == true ||
        contentType?.startsWith("application/json", ignoreCase = true) == true

internal fun configMutationHtml(message: String): String {
    val escaped = message.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    return "<!doctype html><base href=\"/\"><meta charset=utf-8>" +
        "<meta http-equiv=refresh content='2;url=configure'>" +
        "<body style='font-family:system-ui;background:#111;color:#eee;padding:20px'>" +
        escaped + "</body>"
}

/** Schema-1 backups made before ownership-aware omission cannot distinguish untouched vendor state
 * from an explicit OFF. On an untouched target, preserve vendor ownership; explicit ON remains safe. */
internal fun preserveUnconfiguredZigbeeOwnership(
    values: MutableMap<String, String>,
    targetConfigured: Boolean,
): Boolean {
    if (targetConfigured || values["zigbee_router"] != "false") return false
    values.remove("zigbee_router")
    return true
}

/** Service-owned values projected from one coherent set of controller observations. */
internal data class ManagementProjection(
    val facts: Map<String, String>,
    val live: Map<String, String>,
    val capabilities: Capabilities,
    val capabilityRows: List<DiagReader.Cap>,
    /** Whether the Wi-Fi instability behind the `Wi-Fi stability` fact is chronic, decided from the
     *  SAME outage read that produced the fact — so the `/diag` gate can never disagree with the
     *  text it is gating. Undefaulted on purpose: a new caller must state it. */
    val wifiChronic: Boolean,
)

internal data class ConfigDiscoverySuggestions(
    val mqttBroker: String = "",
    val haUrl: String = "",
    /** Why [haUrl] is empty, so setup can explain rather than leave the user at a blank field. */
    val haDiscovery: DiscoveryResult = DiscoveryResult(),
)

private const val SETUP_PRESENCE_HEADER = "X-ha-paneld-setup-presence"
private const val SETUP_PRESENCE_ACTIVE = "active"

internal fun logShipStatusJson(status: LogShipStatusProjection): String =
    "{\"enabled\":${status.enabled},\"configured\":${status.configured}," +
        "\"text\":${Json.str(status.text)}}"

/** JSON projected into browser pages. Install, Entities and shared Runtime controls need per-key
 * provenance to distinguish a genuine translation from an English compatibility fallback. */
internal fun browserI18nPayload(strings: AppStrings, prefixes: Set<String>): String {
    val resolved = strings.resolved(prefixes)
    val entries = resolved.entries.joinToString(",") { (key, localized) ->
        "${Json.str(key)}:${Json.str(localized.text)}"
    }
    val provenance = if (
        "entities." in prefixes || "install." in prefixes || "runtime." in prefixes
    ) {
        val languages = resolved.entries.joinToString(",") { (key, localized) ->
            "${Json.str(key)}:${Json.str(localized.language)}"
        }
        ",\"languages\":{$languages}"
    } else ""
    return "{\"locale\":${Json.str(strings.requestedLocale)},\"strings\":{$entries}$provenance}"
        .replace("<", "\\u003c")
        .replace(">", "\\u003e")
        .replace("&", "\\u0026")
        .replace("\u2028", "\\u2028")
        .replace("\u2029", "\\u2029")
}

class PaneldServer internal constructor(
    private val config: Config,
    private val cacheDir: File,
    private val scope: CoroutineScope,
    private val appContext: Context,
    private val sensors: SensorReporter,
    private val profile: DeviceProfile,
    // For the on-screen Controls card (software navbar) on panels with no physical nav bar.
    private val system: SystemController,
    private val volume: VolumeController,
    // Service-owned latest-wins audio lane. False means teardown has closed admission.
    private val playAudio: (String) -> Boolean,
    // Called after this server has written new settings to [config]; the service rebuilds MQTT/mDNS.
    private val onReconfigure: (Set<String>) -> Unit,
    // Applies a single behaviour setting through the MQTT bridge's command path (persist → drive
    // hardware → publish HA state). Lets the config API set the formerly MQTT-only keys identically
    // to an HA command while preserving whether durable desired state is still waiting for actuation.
    private val applySetting: (String, String) -> LiveSettingRequestOutcome,
    private val onProximityCalibration: (String, String) -> Boolean = { _, _ -> false },
    private val pendingLiveSettings: () -> Map<String, String> = { emptyMap() },
    // The subset of the above whose apply path independent boots have found absent. Still durable, still
    // replayed; reported separately only so the Configure page can stop promising it is about to apply.
    private val stalledLiveSettings: () -> Set<String> = { emptySet() },
    // Fresh non-transient controller authorities for config export/diff/concurrency (touch sound,
    // network ADB and Zigbee intent). ManagementProjection owns the broader render/capability view.
    private val configLiveValues: () -> Map<String, String> = { emptyMap() },
    // Facts, live settings and availableWhen capabilities derived from one request-scoped observation.
    private val managementProjection: (PrivilegedRouteObservation) -> ManagementProjection,
    // Per-panel "HA-optimised" density + text-scale suggestions (DeviceProfile), or null.
    private val recommendedDensity: Int? = null,
    private val recommendedFontScale: Float? = null,
    // Vendor-taming: the controller (applies the action) and the profile's curated recommendations (the
    // picker's "Recommended" group).
    private val tame: TameController,
    private val tameProfileCandidates: List<TameCandidate> = emptyList(),
    // Live log viewer sources (Logs tab / SSE stream). App = own-process logcat (no root); system =
    // full logcat via su, gated on Su.available() at request time. Null → the viewer 404s.
    private val logApp: LogCapture? = null,
    private val logSystem: LogCapture? = null,
    // Dashboard WebView console over the CDP relay; served only while log shipping is configured.
    private val logWebView: LogCapture? = null,
    private val webViewConsoleEnabled: () -> Boolean = { false },
    // Dedicated synchronized shipper state. Never route this through the broad management cache:
    // callers and the Dashboard projection need connection failure and recovery as they happen.
    private val logShipStatus: () -> LogShipStatusProjection = {
        LogShipStatusProjection(config.logShipEnabled, config.logShipActive, "unavailable")
    },
    // EFFECTIVE backlight (sysfs actual_brightness via BrightnessController, cached) — the sensors
    // endpoint + Live-state row report what the hardware is doing, not just the Android setting.
    private val effectiveBrightness: () -> Int = { -1 },
    // Repair a Companion server row with an empty internal_url (the HA 2026.7 "Missing Host header"
    // incident). False means the shared destructive-operation lane is busy.
    private val onRepairCompanionUrl: () -> Boolean = { false },
    // Install/update a managed component from the Install tab. name ∈ {paneld, companion, webview};
    // action ∈ {update, reinstall}; version = a specific release tag to install (blank = channel newest).
    // Runs off-thread; progress is reported via InstallProgress. Injected by the service.
    private val onInstallComponent: (String, String, String) -> Boolean = { _, _, _ -> false },
    // A Panel Assistant entry declared on its status poll that it owns the ha-paneld update entity.
    private val onPanelAssistantUpdateOwner: () -> Unit = {},
    // Active channel changes are two-phase: prepare authenticates and database-admits one exact APK
    // without mutation; the server then commits the whole config transaction and hands that same
    // capability back to the service. Null means the admitted change had no APK to install (up to date,
    // or self-update disabled) and still needs its MQTT state re-projected after commit.
    private val prepareSelfUpdateChannel: suspend (String, Boolean) -> SelfUpdateChannelPreflight = { _, _ ->
        SelfUpdateChannelPreflight.Unresolved("self-update channel preflight unavailable")
    },
    private val onSelfUpdateChannelCommitted: (
        SelfUpdateChannelPreflight.Ready?,
        InstallProgress.Ticket?,
        String,
        String,
    ) -> Unit = { prepared, ticket, _, _ ->
        prepared?.close()
        ticket?.let { InstallProgress.finish(it, "self-update handoff unavailable") }
    },
    // One read-only Android power assessment shared by every user and diagnostic surface.
    private val powerSafety: () -> PowerSafetyAssessment,
    // Uncached harmless direct-root capability probe. Explicit acknowledgement and repair paths only;
    // passive rendering derives capability from the bounded management snapshot instead.
    private val freshPowerSafetyRepairCapability: () -> PowerRepairCapability = { PowerRepairCapability.DEGRADED },
    // Explicit repair only. Per-step readback decides whether the result is complete.
    private val onRepairPowerSafety: () -> PowerSafetyRepairResult,
    // Bounded EFR32 health snapshot, or null when this panel has no radio gateway.
    private val radioStatus: () -> ZigbeeHealthSnapshot? = { null },
    /**
     * The MQTT bridge's canonical lifecycle token (a volatile read, so free to poll). Supplied as the raw
     * token rather than the info page's prose because mapping prose back to a state fails silently: an
     * unrecognised string reads as "still connecting", so setup guidance stops with no visible symptom.
     */
    private val mqttState: () -> String = { "" },
    // Reassert Repeater mode through the service-owned serialized Zigbee actuator. Admitted only when
    // the persisted router switch is explicitly ON; false means the runtime lane is unavailable.
    private val onZigbeeJoinRetry: () -> Boolean = { false },
    // LAN ha-paneld peers discovered over mDNS — powers the header panel switcher. Injected by the service
    // (captures the live MdnsAdvertiser field). Blocking browse; called only through [peersCache] off-thread.
    private val peers: () -> List<io.github.maxlyth.hapaneld.Peer> = { emptyList() },
    // mDNS can fail independently of HTTP and MQTT, leaving the panel absent from peer switchers.
    // Legacy text and typed metadata come from one observation so a liveness transition cannot pair
    // one warning with another warning's presentation envelope.
    private val mdnsWarningProjection: () -> Pair<String?, InstallPresentation?> = { null to null },
    // Proposed values for blank MQTT/HA fields. Discovery is blocking, so the route invokes this on IO;
    // values remain unsaved until the user accepts them with the normal Configure Save action.
    private val configDiscoverySuggestions: () -> ConfigDiscoverySuggestions = { ConfigDiscoverySuggestions() },
    // Shared with the service startup path: serializes renderer config commit → atomic Companion borrow →
    // launch, and retries an interrupted built-in switch from its durable blank-URL state.
    private val rendererPreparation: RendererPreparationCoordinator,
    private val entityLearning: EntityLearningManager,
    private val autoBrightnessHttpApi: AutoBrightnessHttpApi = AutoBrightnessHttpApi.UNAVAILABLE,
    private val autoSleepHttpApi: AutoSleepHttpApi = AutoSleepHttpApi.UNAVAILABLE,
    private val haOAuthExchange: (String, String, String) -> HaLink.AuthorizationCodeExchange =
        HaLink::exchangeAuthorizationCode,
    private val companionDataOperationState: CompanionDataOperationState =
        CompanionDataOperationState.from(appContext),
    // Runtime-loadable profiles. Optional during staged integration so the existing service can keep
    // constructing the HTTP server before its repository/restart wiring lands; the tab then reports 503.
    private val profileAdmin: ProfileAdmin? = null,
    private val profileTemplate: () -> String? = { null },
    private val profileDeviceDraft: () -> PassiveProfileDraft? = { null },
    private val profileReport: () -> PassiveProfileReport? = { null },
    private val profileProbe: (String) -> PassiveProfileReport? = { null },
    private val onProfileRestart: () -> Boolean = { false },
    /**
     * Durable panel state was replaced underneath the running process. Live owners of that state
     * must re-read it before they write again, or a restore is silently overwritten by whatever
     * they were already holding in memory.
     */
    private val onDurableStateRestored: () -> Unit = {},
    private val profileRestartAllowed: () -> Boolean = { true },
    private val onProfileRestartAbort: (String) -> Boolean = { false },
    // Application-id migration: the release and offer endpoints on the bridge build, and the
    // migration-mode restore on the successor. NONE is a panel that is not migrating.
    private val identityMigration: IdentityMigrationSurface = IdentityMigrationSurface.NONE,
    private val provisioningReader: ProvisioningReader? = null,
    private val provisioningActivation: () -> ProvisioningActivationSnapshot = {
        error("provisioning activation provider is unavailable")
    },
    // Cheap process-local storage/database-health snapshot. The runtime starts at UNCHECKED, so
    // staged callers and tests that omit this provider retain an explicit, truthful initial state.
    private val storageHealth: () -> StorageHealthSnapshot = { StorageHealthRuntime.snapshot() },
    // Fresh observation uses the service's single serialized SQLite observation owner. Null means the
    // same-request probe stopped or was incomplete; refresh=1 then renders UNCHECKED, never stale health.
    private val refreshStorageHealth: suspend () -> StorageHealthSnapshot? = { null },
    // Camera trial (slice 3): one session owner shared by the snapshot route, /api/v1/status and
    // /api/v1/diag. A stable per-service instance rather than a lambda provider — unlike radioStatus
    // or mqttState it is never reassigned on reconfigure, so no reconfigure-following indirection is
    // needed here. AbsentCameraSurface is the default so a board with no camera owner still compiles
    // and answers `absent` truthfully; the service wires the real session owner once profile.hasCamera.
    private val camera: CameraSurface = AbsentCameraSurface,
    // Home Assistant Assist pipeline catalogue for the Configure voice_pipelines picker. Defaults to a
    // stub reporting not-configured; the voice-coordinator lane injects the real HA-backed directory.
    private val assistPipelines: io.github.maxlyth.hapaneld.assist.AssistPipelineDirectory =
        io.github.maxlyth.hapaneld.assist.AssistPipelineDirectory.NOT_WIRED,
    // One-shot voice-assistant test trigger for POST /api/v1/voice/test. Defaults to a stub reporting
    // unavailable; the voice-coordinator lane injects the real pipeline-runtime trigger.
    private val voiceTest: io.github.maxlyth.hapaneld.assist.VoiceTestTrigger =
        io.github.maxlyth.hapaneld.assist.VoiceTestTrigger.NOT_WIRED,
    // The native transport's persisted authority, discovery value and phase, and the panel-local release
    // that hands entities and commands back to MQTT. Migration scaffolding; deleted with MQTT.
    private val panelAssistantTransportFacts: () -> io.github.maxlyth.hapaneld.panelassistant.PanelAssistantTransportFacts,
    private val releasePanelAssistantTransport: () -> Unit,
) {
    private suspend fun authorizeSensitive(
        call: ApplicationCall,
        operation: SensitiveOperation,
        payload: String,
        summary: String,
    ): Boolean {
        return authorizeSensitiveRequest(
            call = call,
            hardened = config.hardenedSecurityEnabled,
            peer = call.request.origin.remoteAddress,
            operation = operation,
            payload = payload,
            summary = summary,
            broker = LocalApprovalBroker.instance,
        )
    }

    private suspend fun rejectHardenedNetworkAdb(call: ApplicationCall, requested: String?): Boolean {
        if (!config.hardenedSecurityEnabled || SettingValue.parseBool(requested.orEmpty()) != true) {
            return false
        }
        call.respondText(
            """{"ok":false,"error":"network-adb-incompatible-with-hardened-mode","message":"Switch to Relaxed mode before enabling network ADB."}""",
            ContentType.Application.Json,
            HttpStatusCode.Conflict,
        )
        return true
    }

    private suspend fun rejectHardenedDevToolsRelay(call: ApplicationCall): Boolean {
        if (!config.hardenedSecurityEnabled) return false
        call.respondText(
            """{"ok":false,"error":"devtools-incompatible-with-hardened-mode","message":"Switch to Relaxed mode before exposing WebView developer tools to the LAN."}""",
            ContentType.Application.Json,
            HttpStatusCode.Conflict,
        )
        return true
    }

    private fun requestsSoftwareInstallAuthority(value: (String) -> String?): Boolean {
        if (listOf(
                "self_update" to config.selfUpdate,
                "companion_auto_update" to config.companionAutoUpdate,
                "webview_auto_update" to config.webViewAutoUpdate,
            ).any { (key, enabled) -> SettingValue.parseBool(value(key).orEmpty()) == true && !enabled }
        ) return true
        val selfUpdate = SettingValue.parseBool(value("self_update").orEmpty()) ?: config.selfUpdate
        val companionUpdate = SettingValue.parseBool(value("companion_auto_update").orEmpty())
            ?: config.companionAutoUpdate
        return (selfUpdate && value("update_channel")?.let { it != config.updateChannel } == true) ||
            (companionUpdate && value("companion_update_channel")?.let { it != config.companionUpdateChannel } == true)
    }

    // Per-INSTALL build token (changes on every (re)install, not just a version bump) so an open info
    // page can auto-reload after the app is updated — even a same-version dev re-spin. /health carries it.
    private fun buildToken(): String =
        runCatching { appContext.packageManager.getPackageInfo(appContext.packageName, 0).lastUpdateTime.toString() }
            .getOrDefault(Config.VERSION)

    // Panel-info rows blurred by default (screenshot hygiene) — identity + network values a casual share
    // shouldn't leak. "Reveal" un-blurs them. Not access control: the values are still in the page source.
    private val SECRET_FIELDS = setOf("Device ID", "MQTT")
    // Address rows blur ONLY when the value is globally ROUTABLE — an unroutable RFC1918 / ULA / link-local
    // address (e.g. the LAN IPv4, or a ULA v6) has no external use, so it stays visible.
    private val ADDRESS_FIELDS = setOf("Local IP", "Local IPv6")

    /** Appends physical dimensions only when profile evidence selects this panel's physical geometry.
     *  Logical density is a layout setting and must never be used to infer the panel's physical size. */
    private fun displayCell(v: String): String {
        val observation = DisplayGeometryReport.observe(appContext) ?: return esc(v)
        val size = profile.displayGeometry(observation.physicalWidthPx, observation.physicalHeightPx)?.physical
            ?: return esc(v)
        val inchS = "%.1f".format(size.diagonalInches)
        val cmS = "%.1f".format(size.diagonalInches * 2.54)
        val title = "W %.1f × H %.1f cm".format(size.widthMm / 10, size.heightMm / 10)
        return """${esc(v)} · <span class="diag" data-in="$inchS″" data-cm="$cmS cm" """ +
            """title="${esc(title)}" onclick="diagToggle(this)">$inchS″</span>"""
    }

    // Display sizing (density + text scale) via `wm density` / `font_scale` — su panels only.
    private val density = DensityController(canSu = profile.appCanSu)
    private val interactive = InteractiveController(canSu = profile.appCanSu)
    // On-panel config revision history (ring buffer) — written on every successful apply.
    private val revisions = RevisionStore(appContext.filesDir)
    private val performanceBindingSecret: String by lazy {
        val prefs = AppState.preferences(
            appContext,
            "performance-binding",
            "ha-paneld-performance-binding",
        )
        prefs.getString("secret", null)?.takeIf(::validPerformanceDeviceSecret) ?: run {
            val generated = ByteArray(32).also(SecureRandom()::nextBytes)
                .joinToString("") { "%02x".format(it) }
            if (prefs.edit().putString("secret", generated).commit()) generated else ""
        }
    }
    /**
     * The most recent Home Assistant discovery verdict, so `GET /setup` can explain a blank URL without
     * running a browse of its own. A poll must never start a ~4s multicast sweep; discovery happens on the
     * paths that already do it (the suggestion route, and the MQTT-onboarding save) and leaves its result
     * here.
     */
    @Volatile private var lastHaDiscovery: DiscoveryResult = DiscoveryResult()

    private val haOAuthFlow = HaOAuthFlow()
    private val haOAuthStartLock = Any()
    private val haCurrentUser = HaCurrentUserClient(config)
    private val catalogueLoader by lazy { CatalogueLoader(::asset) }

    /** One locale negotiation path for every localized human page and its hydration payload. */
    private fun requestStrings(call: ApplicationCall): AppStrings = resolvedRequestStrings(
        call = call,
        persistedLanguage = config.uiLanguage,
        deviceLanguageTag = java.util.Locale.getDefault().toLanguageTag(),
        allowPseudo = BuildConfig.DEBUG,
        catalogueLoader = catalogueLoader,
    )

    /** Carry admitted browser locale signals through the unfinished-journey redirect without changing
     * their precedence; see [unfinishedSetupLocation]. */
    private fun setupRedirectLocation(call: ApplicationCall): String = unfinishedSetupLocation(
        lang = call.request.queryParameters["lang"],
        haLang = call.request.queryParameters["ha_lang"],
        allowPseudo = BuildConfig.DEBUG,
    )
    // Stored as a stop lambda over a type-inferred server local, so we never have to name Ktor's
    // EmbeddedServer<TEngine, TConfiguration> generic type (which shifts between Ktor versions).
    private var stopServer: (() -> Unit)? = null
    private val inspectLock = Any()
    private val directConfigMutationLock = Any()
    private val haAreaWarmLock = Any()
    @Volatile private var stopping = true
    private var haAreaJob: kotlinx.coroutines.Job? = null
    @Volatile private var haAreaWarmJob: kotlinx.coroutines.Job? = null
    private var haAreaWriteJob: kotlinx.coroutines.Job? = null
    private val tameReconciliation = TameReconcileAuthority(
        readDesired = { config.tameVendorPackages.toSet() },
        reconcile = { desired, stopping ->
            val cost = FeatureCosts.registry.span(FeatureCostOperation.TAME_MUTATION)
            try {
                tame.reconcileBlocklist(desired, stopping).also { result ->
                    cost.work(units = result.attempted.toLong())
                    if (result.retryableFailure) cost.outcome(FeatureCostOutcome.FAILURE)
                }
            } catch (error: Exception) {
                cost.outcome(FeatureCostOutcome.FAILURE)
                Log.w(TAG, "vendor package reconciliation failed", error)
                TameReconcileResult(attempted = 0, retryableFailure = true)
            } finally {
                cost.close()
            }
        },
        stopping = { stopping },
        onBacklogChanged = { pending ->
            FeatureCosts.registry.setBacklog(FeatureCostOperation.TAME_MUTATION, pending)
        },
    )
    private sealed class RemoteControl(val key: String) {
        class Tap(
            val x: Float,
            val y: Float,
            val loopback: Boolean = false,
            val capture: Boolean = false,
            val requestId: Long? = null,
            val executeBeforeElapsedMs: Long? = null,
            val completeBeforeElapsedMs: Long? = null,
            val completion: CompletableDeferred<TapCaptureResult>? = null,
        ) : RemoteControl("tap")
        class Action(val name: String) : RemoteControl(name)
    }
    private val remoteInputSequence = AtomicLong()
    private val remoteControls: LatestDispatcher<String, RemoteControl> = LatestDispatcher(
        threadName = "ha-paneld-remote-control",
        maxPendingKeys = 8,
        consume = { _, command -> executeRemoteControl(command) },
        onDiscard = { _, command ->
            (command as? RemoteControl.Tap)?.completion?.complete(TapCaptureResult.NotExecuted)
        },
    )
    private val clearStorageGate = GenerationSingleFlight()

    private fun executeRemoteControl(command: RemoteControl) {
        FeatureCosts.registry.setBacklog(FeatureCostOperation.REMOTE_INPUT, remoteControls.pendingCount())
        val cost = FeatureCosts.registry.span(FeatureCostOperation.REMOTE_INPUT).work(units = 1)
        val startedAt = SystemClock.elapsedRealtime()
        val ok = try {
            when (command) {
                is RemoteControl.Tap -> executeRemoteTap(command)
                is RemoteControl.Action -> if (executeRemoteDashboardAction(
                        command.name,
                        config.dashboardPackage,
                        launch = { system.launchHome(it) },
                        reload = { system.reloadDashboard(it) },
                    )
                ) true else when (command.name) {
                    "back" -> interactive.back()
                    "recents" -> interactive.recents()
                    "launcher" -> { system.launchLauncher(config.launcherPackage); true }
                    "admin_launcher" -> { system.launchAdminLauncher(); true }
                    "reboot" -> { system.reboot(); true }
                    "volup" -> { volume.step(up = true); true }
                    "voldn" -> { volume.step(up = false); true }
                    else -> false
                }
            }
        } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            (command as? RemoteControl.Tap)?.completion?.complete(TapCaptureResult.CompletionUnknown())
            Log.w(TAG, "remote control execution failed", error)
            false
        }
        if (!ok) cost.outcome(FeatureCostOutcome.FAILURE)
        cost.close()
        (command as? RemoteControl.Tap)?.requestId?.let { requestId ->
            Log.i(TAG, "remote input id=$requestId complete ok=$ok elapsed_ms=" +
                (SystemClock.elapsedRealtime() - startedAt))
        }
    }

    private fun executeRemoteTap(command: RemoteControl.Tap): Boolean {
        if (!command.capture) {
            if (config.hardenedSecurityEnabled && !command.loopback) return false
            return interactive.tapWithRoute(command.x, command.y) != null
        }
        val executeBefore = command.executeBeforeElapsedMs
        val completeBefore = command.completeBeforeElapsedMs
        if (executeBefore == null || completeBefore == null) {
            command.completion?.complete(TapCaptureResult.CompletionUnknown())
            return false
        }
        val result = performTapCapture(
            execution = TapCaptureExecution(
                command.x,
                command.y,
                command.loopback,
                executeBefore,
                completeBefore,
                REMOTE_TAP_CAPTURE_SETTLE_MS,
                REMOTE_SCREENSHOT_WAIT_MS,
            ),
            hardened = { config.hardenedSecurityEnabled },
            nowElapsedMs = SystemClock::elapsedRealtime,
            tap = interactive::tapOnceWithRoute,
            settle = { waitMs ->
                try {
                    Thread.sleep(waitMs)
                    true
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    false
                }
            },
            screenshot = interactive::screenshotOnceWithRoute,
        )
        command.completion?.complete(result)
        if (result is TapCaptureResult.Success) {
            Log.i(TAG, "remote input id=${command.requestId} routes=" +
                "${result.inputRoute.name.lowercase()}/${result.screenshotRoute.name.lowercase()}")
        }
        return result is TapCaptureResult.Success
    }
    private val pendingApks = PendingUploadStore()
    private val guardDbStaging = guardDbAppStaging(appContext)

    fun start() {
        stopping = false
        pendingApks.open()
        // Bind the IPv6 wildcard "::" — on Android this is dual-stack (net.ipv6.bindv6only=0), so the
        // server answers on both IPv6 and IPv4, instead of the IPv4-only default 0.0.0.0.
        val server = scope.embeddedServer(CIO, port = config.httpPort, host = "::") {
            // 0.8.1 security: refuse any request whose SOURCE is not LAN-local. The unauthenticated control
            // surface answers on the panel's globally-routable IPv6 (dual-stack "::"), so without this it can
            // be reached from the internet whenever the home router doesn't firewall inbound IPv6 — and we
            // must not depend on that. Allow loopback / RFC1918 / link-local / ULA; global/public source 403s.
            // (Known limitation to iterate on: a LAN peer reaching the panel via its *global* v6 uses a global
            // source and is also rejected — use IPv4 on-LAN; a same-/64-prefix exception is the next refinement.)
            intercept(ApplicationCallPipeline.Plugins) {
                // OAuth callback URLs carry short-lived state/code query values. Apply privacy headers before
                // any source, CSRF, or Host rejection can finish the pipeline as well as on routed responses.
                if (call.request.uri.substringBefore('?') == HA_OAUTH_CALLBACK_PATH) call.noStoreHaOAuth()
                call.response.headers.append("X-Content-Type-Options", "nosniff")
                call.response.headers.append("X-Frame-Options", "DENY")
                call.response.headers.append("Content-Security-Policy", "frame-ancestors 'none'")
                // Use origin.remoteAddress (the RAW peer IP), NOT remoteHost — remoteHost reverse-resolves to
                // a hostname, and forward-resolving that picks a (possibly global) address that fails the
                // RFC1918 check, 403-ing legitimate LAN clients. Verified: remoteAddress returns 192.168.x etc.
                if (!isLocalSource(call.request.origin.remoteAddress)) {
                    call.respondText("forbidden\n", status = HttpStatusCode.Forbidden)
                    return@intercept finish()
                }
                // Presentation only: parsed once for the page builders, never logged, and never consulted by a
                // guard. Only a valid switch varies the response.
                val embed = call.admitEmbedMode()
                if (GuardDbProcessAdmission.maintenanceRequired()) {
                    // The request which durably created INTENT has already crossed this interceptor.
                    // Every later request belongs to a writer-owning server which is being retired;
                    // the successor's narrow control plane is the sole admitted surface.
                    call.respondText("guard database maintenance owns this process\n", status = HttpStatusCode.Locked)
                    return@intercept finish()
                }
                // While guided setup is waiting on a person, every HTML page follows the panel into the
                // wizard — a laptop tab opened before the first run began otherwise keeps showing the old
                // page and never presents the wizard (hardware review). After the source gate on purpose:
                // page redirects are a LAN-client courtesy, never a response to an unverified peer.
                // Scope: exact page paths only (API, assets, OAuth untouched); a `wiz_escape` cookie —
                // set by the wizard's own "Skip and exit" link — is honoured so the escape hatch cannot
                // become a trap.
                // Panel Assistant's embedded view (EmbedMode) is never redirected: its setup tab stays in
                // the tab bar, and no escape cookie is written on Home Assistant's origin.
                if (embed == null && call.request.uri.substringBefore('?') in WIZARD_REDIRECT_PAGES &&
                    call.request.cookies["wiz_escape"] == null && setupNeedsUser()
                ) {
                    call.respondRedirect(setupRedirectLocation(call))
                    return@intercept finish()
                }
                // CSRF guard: a LAN browser on a malicious page must not be able to silently drive a
                // state-changing endpoint (e.g. POST /config → MQTT takeover). Cross-origin writes carry
                // a mismatched Origin/Referer and are refused; same-origin UI fetches and header-less API
                // clients (curl / HA rest_command) pass. See OriginGuard.
                if (!OriginGuard.allowed(
                        call.request.origin.method.value,
                        call.request.headers["Origin"],
                        call.request.headers["Referer"],
                        call.request.headers["Host"],
                    )
                ) {
                    call.respondText("cross-origin refused\n", status = HttpStatusCode.Forbidden)
                    return@intercept finish()
                }
                // Anti-DNS-rebinding: pin the Host header to unrebindable values (IP / localhost /
                // *.local) + any configured names, so a rebound hostname can't read secrets or drive
                // the surface as "same-origin". See OriginGuard.hostAllowed.
                if (!OriginGuard.hostAllowed(call.request.headers["Host"], config.httpAllowedHosts)) {
                    call.respondText("host not allowed\n", status = HttpStatusCode.Forbidden)
                    return@intercept finish()
                }
                // Panel Assistant's proof that a Home Assistant administrator made this request through the
                // sidebar. Verified after every other guard and before any handler; a present proof that fails
                // is refused here and never falls through to the request's unproven handling.
                if (!call.admitEmbedProof(PanelAssistantEmbedKeys.instance)) return@intercept finish()
            }
            routing {
                handBackHomeRoutes(handBackHomeDependencies())
                controlPlaneRoutes(
                    ControlPlaneRouteDependencies(
                        playAudio = playAudio,
                        installComponent = onInstallComponent,
                        installedComponentVersion = { name ->
                            when (name) {
                                "paneld" -> Config.VERSION
                                "companion" -> CompanionInstaller.installedPkg(appContext)?.let { pkg ->
                                    AppInstaller.installedVersion(appContext, pkg).takeIf { it.isNotBlank() }
                                }
                                "webview" -> runCatching {
                                    android.webkit.WebView.getCurrentWebViewPackage()?.versionName
                                }.getOrNull()
                                else -> null
                            }
                        },
                        buildBackup = { request, passphrase ->
                            withContext(Dispatchers.IO) {
                                buildBackupArtifact(request, passphrase)
                            }
                        },
                        backupFileStem = { config.panelId },
                        authorize = ::authorizeSensitive,
                        apkUpload = ApkUploadRouteDependencies(
                            enabled = { config.apkUploadAllowed },
                            rootAvailable = { rootOk() },
                            pending = pendingApks,
                            createStagingFile = { File.createTempFile("apk-upload-", ".apk", appContext.cacheDir) },
                            inspect = { staged ->
                                withContext(Dispatchers.IO) { AppInstaller.inspect(appContext, staged.absolutePath) }?.let {
                                    UploadedApkIdentity(it.pkg, it.version, it.signerSha256)
                                }
                            },
                            startInstall = { claimed, progress ->
                                val apk = claimed.file
                                val job = scope.launch {
                                    val result = runCatching { AppInstaller.installLocalApk(appContext, apk) }
                                        .fold(
                                            onSuccess = { outcome ->
                                                when (outcome) {
                                                    InstallOutcome.Succeeded -> "OK"
                                                    is InstallOutcome.Failure -> outcome.message
                                                }
                                            },
                                            onFailure = { "error: ${it.message}" },
                                        )
                                    Log.i(TAG, "APK upload install: $result")
                                    InstallProgress.finish(progress, result)
                                }
                                job.invokeOnCompletion { cause -> if (cause != null) apk.delete() }
                                InstallProgress.finishOnFailure(progress, job)
                            },
                        ),
                    ),
                )
                identityMigrationRoutes(identityMigration, ::authorizeSensitive)
                panelAssistantTransportRoutes(
                    PanelAssistantTransportRouteDependencies(
                        facts = panelAssistantTransportFacts,
                        release = releasePanelAssistantTransport,
                        authorize = ::authorizeSensitive,
                    ),
                )
                guardDbBootstrapRoutes(
                    GuardDbBootstrapRouteDependencies(
                        pendingUploads = pendingApks,
                        staging = guardDbStaging,
                        inspectPending = { file -> inspectGuardDbCandidate(appContext, file) },
                        inspectInstalled = {
                            inspectGuardDbCandidate(appContext, File(appContext.applicationInfo.sourceDir))
                        },
                        settingsAuthority = {
                            guardDbSettingsAuthorityStore(appContext).materializeExact()
                        },
                        client = GuardDbMaintenance.client,
                        sentinelStore = guardDbSentinelStore(appContext),
                        bootNonce = ::guardDbBootNonce,
                        monotonicMs = SystemClock::elapsedRealtime,
                        httpPort = { config.httpPort },
                        hardened = { config.hardenedSecurityEnabled },
                        securityEpoch = {
                            RemoteDebugSecurityTransitionGate.withLock {
                                val epoch = RemoteDebugSecurityTransitionGate.hardenedAuthorityEpoch()
                                    ?: return@withLock null
                                val adb = AdbController(appContext, config)
                                epoch.takeIf {
                                    config.hardenedSecurityEnabled && !CdpRelay.running &&
                                        adb.hardenedRemoteDebugOff() && config.hardenedSecurityEnabled &&
                                        !CdpRelay.running &&
                                        RemoteDebugSecurityTransitionGate.hardenedAuthorityEpoch() == epoch
                                }
                            }
                        },
                        commitSentinel = { expectedEpoch, sentinel ->
                            when (val authority = RemoteDebugSecurityTransitionGate.withEpoch(expectedEpoch) {
                                if (sentinel.securityAuthorityEpoch != expectedEpoch ||
                                    RemoteDebugSecurityTransitionGate.hardenedAuthorityEpoch() != expectedEpoch ||
                                    !config.hardenedSecurityEnabled || CdpRelay.running
                                ) {
                                    return@withEpoch GuardDbSentinelCommit.SecurityRefused
                                }
                                val adb = AdbController(appContext, config)
                                if (!adb.hardenedRemoteDebugOff() || !config.hardenedSecurityEnabled ||
                                    CdpRelay.running ||
                                    RemoteDebugSecurityTransitionGate.hardenedAuthorityEpoch() != expectedEpoch
                                ) return@withEpoch GuardDbSentinelCommit.SecurityRefused
                                val store = guardDbSentinelStore(appContext)
                                val written = store.write(sentinel)
                                val load = store.load()
                                if (written && load is io.github.maxlyth.hapaneld.util.GuardDbSentinelLoad.Valid &&
                                    load.sentinel == sentinel
                                ) {
                                    GuardDbSentinelCommit.Committed(load)
                                } else {
                                    GuardDbSentinelCommit.Failed(load)
                                }
                            }) {
                                RemoteDebugAuthorityResult.Changed -> GuardDbSentinelCommit.SecurityRefused
                                is RemoteDebugAuthorityResult.Value -> authority.value
                            }
                        },
                        authorize = { call, operation, payload, summary ->
                            authorizeSensitiveRequest(
                                call = call,
                                hardened = true,
                                peer = call.request.origin.remoteAddress,
                                operation = operation,
                                payload = payload,
                                summary = summary,
                                broker = LocalApprovalBroker.instance,
                            )
                        },
                        prepare = { manifest, schedule ->
                            GuardDbArmCoordinator.prepare(
                                appContext,
                                manifest,
                                schedule,
                            )
                        },
                        contain = {
                            scope.launch {
                                delay(GUARD_DB_ARM_RESPONSE_GRACE_MS)
                                appContext.stopService(
                                    android.content.Intent(appContext, io.github.maxlyth.hapaneld.PaneldService::class.java),
                                )
                                Thread {
                                    Thread.sleep(1_500L)
                                    io.github.maxlyth.hapaneld.GuardDbMaintenanceService.start(appContext)
                                }.start()
                            }
                        },
                        terminalRetirement = GuardDbTerminalRetirementRouteDependencies(
                            client = GuardDbMaintenance.client,
                            store = guardDbTerminalRetirementStore(appContext),
                            hardened = { config.hardenedSecurityEnabled },
                            securityEpoch = {
                                RemoteDebugSecurityTransitionGate.withLock {
                                    val epoch = RemoteDebugSecurityTransitionGate.hardenedAuthorityEpoch()
                                        ?: return@withLock null
                                    val adb = AdbController(appContext, config)
                                    epoch.takeIf {
                                        config.hardenedSecurityEnabled && !CdpRelay.running &&
                                            adb.hardenedRemoteDebugOff() && config.hardenedSecurityEnabled &&
                                            !CdpRelay.running &&
                                            RemoteDebugSecurityTransitionGate.hardenedAuthorityEpoch() == epoch
                                    }
                                }
                            },
                            authorize = { call, operation, payload, summary ->
                                authorizeSensitiveRequest(
                                    call = call,
                                    hardened = true,
                                    peer = call.request.origin.remoteAddress,
                                    operation = operation,
                                    payload = payload,
                                    summary = summary,
                                    broker = LocalApprovalBroker.instance,
                                )
                            },
                        ),
                    ),
                )
                get("/") {
                    val strings = requestStrings(call)
                    call.response.headers.append(HttpHeaders.Vary, HttpHeaders.AcceptLanguage)
                    call.response.headers.append(
                        HttpHeaders.ContentLanguage,
                        strings.languages(setOf("shell.", "dashboard.")).joinToString(", "),
                    )
                    call.respondText(infoHtml(strings, call.embedMode()), ContentType.Text.Html)
                }
                // Static front-end assets (externalised from the Kotlin string so CI can lint them).
                get("/info.js") {
                    call.response.headers.append("Cache-Control", "no-cache")  // assets iterate; always serve fresh
                    call.respondText(asset("info.js"), ContentType.Application.JavaScript)
                }
                get("/info.css") {
                    call.response.headers.append("Cache-Control", "no-cache")
                    call.respondText(asset("info.css"), ContentType.Text.CSS)
                }
                get("/icon.svg") {
                    call.respondText(asset("icon.svg"), ContentType.Image.SVG)
                }
                get("/favicon.svg") {
                    call.respondText(asset("favicon.svg"), ContentType.Image.SVG)
                }
                // Generic bundled-asset server for the redesigned UI (page scripts + vendored libs).
                get("/assets/{f...}") {
                    val rel = call.parameters.getAll("f")?.joinToString("/").orEmpty()
                    val body = if (rel.isEmpty() || rel.contains("..")) null else runCatching { asset(rel) }.getOrNull()
                    if (body == null) {
                        call.respondText("not found\n", status = HttpStatusCode.NotFound)
                    } else {
                        val ct = when {
                            rel.endsWith(".js") -> ContentType.Application.JavaScript
                            rel.endsWith(".css") -> ContentType.Text.CSS
                            rel.endsWith(".svg") -> ContentType.Image.SVG
                            rel.endsWith(".json") -> ContentType.Application.Json
                            else -> ContentType.Text.Plain
                        }
                        call.response.headers.append("Cache-Control", "no-cache")
                        call.respondText(body, ct)
                    }
                }
                // Tabbed multi-page shell. `/` stays the existing dashboard (now with a tab bar); the
                // other tabs are dedicated pages that consume /api/v1.
                get("/configure") {
                    val strings = requestStrings(call)
                    call.response.headers.append(HttpHeaders.Vary, HttpHeaders.AcceptLanguage)
                    call.response.headers.append(
                        HttpHeaders.ContentLanguage,
                        strings.languages(setOf("shell.", "configure.")).joinToString(", "),
                    )
                    call.respondText(
                        page(
                            active = "configure",
                            title = strings.get("shell.nav.configure"),
                            body = configureBody(strings),
                            strings = strings,
                            embed = call.embedMode(),
                        ),
                        ContentType.Text.Html,
                    )
                }
                get("/setup") {
                    val strings = requestStrings(call)
                    val preserveExplicitEnglish = AppLocale.canonical(
                        call.request.queryParameters["lang"],
                        allowPseudo = BuildConfig.DEBUG,
                    ) == AppLocale.ENGLISH
                    call.response.headers.append(HttpHeaders.Vary, HttpHeaders.AcceptLanguage)
                    call.response.headers.append(
                        HttpHeaders.ContentLanguage,
                        strings.languages(setOf("shell.", "setup.")).joinToString(", "),
                    )
                    // No data-cfg, unlike every other page: buildwatch.js reloads /configure when settings
                    // change underneath it, and that same reload mid-step would throw away what the user is
                    // typing. The wizard tracks server state by polling instead. The build token stays, so a
                    // reinstall still refreshes the page.
                    call.respondText(
                        pageShell(
                            active = "setup",
                            sectionTitle = strings.get("shell.nav.setup"),
                            bodyAttrs = """data-build="${buildToken()}"""",
                            rightControls = ghLink(strings),
                            body = setupBody(strings, preserveExplicitEnglish, embedded = call.embedMode() != null),
                            strings = strings,
                            translationPrefixes = setOf("shell.", "setup.", "runtime."),
                            preserveExplicitEnglish = preserveExplicitEnglish,
                            embed = call.embedMode(),
                        ),
                        ContentType.Text.Html,
                    )
                }
                get("/profiles") {
                    val strings = requestStrings(call)
                    call.response.headers.append(HttpHeaders.Vary, HttpHeaders.AcceptLanguage)
                    call.response.headers.append(
                        HttpHeaders.ContentLanguage,
                        strings.languages(setOf("shell.", "configure.hardened.", "profiles.")).joinToString(", "),
                    )
                    call.respondText(
                        page("profiles", strings.get("shell.nav.profile"), profilesBody(strings), strings, call.embedMode()),
                        ContentType.Text.Html,
                    )
                }
                // The experimental remote-control page is withheld from 0.9.2. Keep old bookmarks
                // useful while its tap-injection UX is reviewed for a later release.
                get("/test") { call.respondRedirect("/") }
                get("/install") {
                    val strings = requestStrings(call)
                    call.response.headers.append(HttpHeaders.Vary, HttpHeaders.AcceptLanguage)
                    call.response.headers.append(
                        HttpHeaders.ContentLanguage,
                        (strings.languages(
                            setOf(
                                "shell.",
                                "configure.hardened.",
                                "dashboard.banner.",
                                "install.",
                                "runtime.",
                            ),
                        ) + AppLocale.ENGLISH)
                            .distinct().sorted().joinToString(", "),
                    )
                    call.respondText(
                        withContext(Dispatchers.IO) {
                            page("install", strings.get("shell.nav.install"), installBody(strings), strings, call.embedMode())
                        },
                        ContentType.Text.Html,
                    )
                }
                get("/fleet") {
                    val strings = requestStrings(call)
                    call.response.headers.append(HttpHeaders.Vary, HttpHeaders.AcceptLanguage)
                    call.response.headers.append(
                        HttpHeaders.ContentLanguage,
                        strings.languages(setOf("shell.", "configure.hardened.", "fleet.")).joinToString(", "),
                    )
                    call.respondText(
                        page("fleet", strings.get("shell.nav.fleet"), fleetBody(strings), strings, call.embedMode()),
                        ContentType.Text.Html,
                    )
                }
                get("/logs") {
                    val strings = requestStrings(call)
                    call.response.headers.append(HttpHeaders.Vary, HttpHeaders.AcceptLanguage)
                    call.response.headers.append(
                        HttpHeaders.ContentLanguage,
                        strings.languages(setOf("shell.", "configure.hardened.", "logs.")).joinToString(", "),
                    )
                    call.respondText(
                        page("logs", strings.get("shell.nav.logs"), logsBody(strings), strings, call.embedMode()),
                        ContentType.Text.Html,
                    )
                }
                get("/entities") {
                    val strings = requestStrings(call)
                    call.response.headers.append(HttpHeaders.Vary, HttpHeaders.AcceptLanguage)
                    call.response.headers.append(
                        HttpHeaders.ContentLanguage,
                        (
                            strings.languages(setOf("shell.", "configure.hardened.", "entities.")) +
                                strings.resolve("settings.dashboard_entity_learning.label").language +
                                strings.resolve("configure.group.dashboard").language +
                                AppLocale.ENGLISH
                            )
                            .distinct().sorted().joinToString(", "),
                    )
                    call.respondText(
                        page("entities", strings.get("shell.nav.entities"), entitiesBody(strings), strings, call.embedMode()),
                        ContentType.Text.Html,
                    )
                }
                // Self-contained REST API explorer (no Swagger-UI CDN bundle) + the OpenAPI spec it
                // renders — the spec also imports into Swagger/Postman for fleet tooling.
                get("/api") {
                    val strings = requestStrings(call)
                    val projectionPrefixes = setOf("api.", "configure.hardened.", "shell.hardened.")
                    call.response.headers.append(HttpHeaders.Vary, HttpHeaders.AcceptLanguage)
                    call.response.headers.append(
                        HttpHeaders.ContentLanguage,
                        (strings.languages(projectionPrefixes) + AppLocale.ENGLISH)
                            .distinct().sorted().joinToString(", "),
                    )
                    val html = asset("api.html")
                        .replace(
                            "<title>ha-paneld · REST API</title>",
                            "<title>${esc(panelBrowserTitle(config.friendlyName, "REST API"))}</title>",
                        )
                        .replace("__API_LANG__", esc(strings.requestedLocale))
                        .replace("__API_BACK_HREF__", esc(localizedHref("./", strings)))
                        .replace("__API_I18N_PAYLOAD__", browserI18nPayload(strings, projectionPrefixes))
                    call.respondText(html, ContentType.Text.Html)
                }
                get("/health") {
                    call.respondText("ha-paneld ${Config.VERSION} panel=${config.panelId} build=${buildToken()} cfg=${renderConfigConcurrencyHash()}${panelAssistantDiscoveryHealthToken(config.androidId)}${packageHealthToken(appContext.packageName)}${versionCodeHealthToken(BuildConfig.VERSION_CODE)}${haLifecycleHealthToken()}${haNetworkHealthToken()}\n")
                }
                // Pre-0.8.5 flat machine endpoints → 308 to their /api/v1 homes.
                legacyRedirects()

                // ---- /api/v1 — the canonical machine API (0.8.5 conformity pass). Every machine
                // endpoint lives here; the pre-0.8.5 flat paths 308 to their v1 homes (method + body
                // preserved), except /health and /play which stay REAL at the root too — they're the
                // external "contract" endpoints called by plain curl (no -L) from HA automations and
                // monitors. Human pages + static assets stay top-level. ----
                route("/api/v1") {
                    provisioningReader?.let { reader ->
                        provisioningRoutes(
                            ProvisioningRouteDependencies(
                                reader = reader,
                                activation = provisioningActivation,
                            ),
                        )
                    }
                    profileAdmin?.let { admin ->
                        profileRoutes(
                            ProfileRouteDependencies(
                                admin = admin,
                                requestRestart = onProfileRestart,
                                restartAllowed = profileRestartAllowed,
                                abortPendingRestart = onProfileRestartAbort,
                                readOnly = ProfileRouteReadOnlyProviders(
                                    template = profileTemplate,
                                    deviceDraft = profileDeviceDraft,
                                    latestReport = profileReport,
                                    probe = profileProbe,
                                ),
                                authorize = ::authorizeSensitive,
                            ),
                        )
                    } ?: unavailableProfileRoutes()
                    get("/health") {
                        call.respondText("ha-paneld ${Config.VERSION} panel=${config.panelId} build=${buildToken()} cfg=${renderConfigConcurrencyHash()}${panelAssistantDiscoveryHealthToken(config.androidId)}${packageHealthToken(appContext.packageName)}${versionCodeHealthToken(BuildConfig.VERSION_CODE)}${haLifecycleHealthToken()}${haNetworkHealthToken()}\n")
                    }
                    configReadRoutes(
                        currentConfigJson = ::configJson,
                        localizedSchema = { call ->
                            localizedConfigSchema(
                                call = call,
                                persistedLanguage = config.uiLanguage,
                                deviceLanguageTag = java.util.Locale.getDefault().toLanguageTag(),
                                allowPseudo = BuildConfig.DEBUG,
                                catalogueLoader = catalogueLoader,
                                render = ::configSchemaJson,
                            )
                        },
                    )
                    installDirectConfigPostRoute()
                    get("/config/home-dashboards") {
                        val catalog = entityLearning.homeDashboardCatalog()
                        val items = catalog.items.joinToString(",") { dashboard ->
                            "{\"path\":${jsonStr(dashboard.path)},\"title\":${jsonStr(dashboard.title)}," +
                                "\"icon\":${jsonStr(dashboard.icon)},\"group\":${jsonStr(dashboard.group)}}"
                        }
                        // `default` reports whether the ACCOUNT carries a real server-side default dashboard
                        // (HA ≥ 2025.12 stores the profile picker's choice per user). When it does not, the
                        // pickers demote "follow the account's default" and recommend nominating one.
                        val default = "{\"explicit\":${catalog.default.explicit}," +
                            "\"path\":${jsonStr(catalog.default.path)}}"
                        call.respondText(
                            "{\"queried\":${catalog.queried},\"items\":[$items],\"default\":$default}",
                            ContentType.Application.Json,
                        )
                    }
                    get("/config/ha-area") {
                        // Registry LISTS are readable by any authenticated HA user; `admin` tells the
                        // pickers whether editing is honest to offer (moving a device is admin-only).
                        val snapshot = captureHaAreaSnapshot()
                        val catalog = applyHaAreaPrecedence(snapshot, haAreaCatalogFor(snapshot))
                        val areas = catalog.areas.joinToString(",") { area ->
                            "{\"area_id\":${jsonStr(area.areaId)},\"name\":${jsonStr(area.name)}," +
                                "\"icon\":${jsonStr(area.icon)}}"
                        }
                        val device = "{\"found\":${catalog.device.found}," +
                            "\"area_id\":${jsonStr(catalog.device.areaId)}," +
                            "\"area_name\":${jsonStr(catalog.device.areaName)}}"
                        call.respondText(
                            "{\"areas\":[$areas],\"device\":$device,\"admin\":${catalog.admin}," +
                                "\"queried\":${catalog.queried},\"requested\":${jsonStr(config.haArea)}," +
                                "\"ha_username\":${jsonStr(catalog.haUsername)}}",
                            ContentType.Application.Json,
                        )
                    }
                    get("/logship/status") {
                        // Passive read of what the shipper is actually doing, including Dashboard state.
                        // Distinct from probe-log-sink, which transmits: this one only reports, so it
                        // is safe to poll while a page is open.
                        call.respondText(logShipStatusJson(logShipStatus()), ContentType.Application.Json)
                    }
                    get("/config/probe-broker") {
                        // Pre-flight from the PANEL's network vantage — the only one that matters — so
                        // the wizard can name an unresolvable host or closed port in ~2s instead of
                        // committing the save and discovering it minutes into the connect workflow.
                        // Read-only: resolves and touches a TCP port, changes nothing.
                        val url = call.request.queryParameters["url"].orEmpty()
                        val body = withContext(Dispatchers.IO) { probeBrokerJson(url) }
                        call.respondText(body, ContentType.Application.Json)
                    }
                    post("/config/probe-log-sink") {
                        // Same pre-flight idea as probe-broker, from the panel's own network vantage,
                        // but deliberately a POST rather than a GET: unlike the broker probe this one
                        // TRANSMITS a caller-supplied record to a caller-supplied host and port, which
                        // is neither safe nor idempotent, and as a GET it would be a CSRF-reachable
                        // packet emitter aimed at the panel's LAN. POST puts it behind the shared
                        // state-changing OriginGuard admission applied to every mutating route.
                        val params = receiveBoundedFormParameters(call) ?: return@post
                        val port = selectLogSinkProbePort(params["port"], config.logShipPort)
                        if (port == null) {
                            call.respondText(
                                """{"ok":false,"error":"invalid-port"}""",
                                ContentType.Application.Json,
                            )
                            return@post
                        }
                        val body = withContext(Dispatchers.IO) {
                            probeLogSinkJson(
                                host = params["host"] ?: config.logShipHost,
                                port = port,
                                protocol = params["protocol"] ?: config.logShipProtocol,
                                panelId = config.panelId,
                            )
                        }
                        call.respondText(body, ContentType.Application.Json)
                    }
                    get("/config/discovery") {
                        val needsMqtt = config.mqttBroker.isBlank()
                        val needsHa = config.haUrl.isBlank()
                        val found = if (needsMqtt || needsHa) {
                            withContext(Dispatchers.IO) { configDiscoverySuggestions() }
                                .also { lastHaDiscovery = it.haDiscovery }
                        } else ConfigDiscoverySuggestions()
                        val mqtt = found.mqttBroker.takeIf { needsMqtt && config.mqttBroker.isBlank() }.orEmpty()
                        val ha = found.haUrl.takeIf { needsHa && config.haUrl.isBlank() }.orEmpty()
                        call.respondText(
                            "{\"mqtt_broker\":${jsonStr(mqtt)},\"ha_url\":${jsonStr(ha)}}",
                            ContentType.Application.Json,
                        )
                    }
                    post("/setup/attest") {
                        // A human at the panel (or looking at it) confirms the dashboard is actually
                        // showing. Bound to the current configuration fingerprint, so changing the URL,
                        // renderer or account silently voids it and the journey re-arms — nothing to
                        // expire, nothing to clean up.
                        config.setupRenderAttestation = setupProofFingerprint()
                        config.setupEverCompleted = true
                        call.respondText("{\"ok\":true}", ContentType.Application.Json)
                    }
                    post("/setup/identity") {
                        // Separate from POST /config on purpose. The panel name is an ordinary setting and
                        // goes through the usual validated path; what this records is that a human saw the
                        // consequence and accepted it, which is not a setting and must never appear on the
                        // Configure form, in a config bundle or as a Home Assistant entity.
                        config.setupIdentityConfirmed = true
                        call.respondText("{\"ok\":true}", ContentType.Application.Json)
                    }
                    post("/setup/home-dashboard") {
                        // Records that the dashboard question was answered — including "follow the account's
                        // default", which leaves home_dashboard blank and is otherwise indistinguishable from
                        // never having been asked. The value itself is an ordinary setting and has already
                        // arrived via POST /config; no renderer side-effect here, because the first load stays
                        // held until the entity-filter answer that necessarily follows this step.
                        config.setupHomeDashboardChosen = true
                        // The entity-filter question is next, and it needs a COUNT — but on a fresh panel
                        // every scan trigger is gated on dashboard_entity_learning, the very setting that
                        // question asks about, so nothing would ever produce one and the card showed a
                        // green "0 entities" on hardware. This answer is the moment the count becomes
                        // needed; kick a catalog scan for it. A POST side effect on purpose: GET /setup
                        // must stay cheap and write-free. syncNow is already the ungated manual-refresh
                        // path and populates only the rebuildable catalog.
                        if (!config.dashboardEntityLearningEnabled && effectiveDashboardIsBuiltin() &&
                            entityLearning.scanProgress() == null && entityLearning.catalogCount() == null &&
                            (config.haToken.isNotBlank() || config.haRefreshToken.isNotBlank())
                        ) {
                            entityLearning.syncNow("entity-filter-count")
                        }
                        call.respondText("{\"ok\":true}", ContentType.Application.Json)
                    }
                    post("/setup/entity-filter") {
                        // Records that the question was ANSWERED, which is what releases the renderer's
                        // first load. Turning the filter on is an ordinary setting and goes through POST
                        // /config first, so by the time this arrives the filter is already committed and the
                        // panel's first render is the filtered one — the entire point of asking before it
                        // loads. Recording the answer here rather than inferring it from the setting is what
                        // lets a user decline and still get a dashboard.
                        config.setupEntityFilterAnswered = true
                        if (effectiveDashboardIsBuiltin()) {
                            // The renderer has been sitting on the pre-render surface waiting for exactly
                            // this. Nothing watches an internal pref, so release it explicitly.
                            scope.launch {
                                runCatching {
                                    rendererPreparation.prepareIfNeeded()
                                    system.launchHome(SystemController.BUILTIN_DASHBOARD)
                                }.onFailure { Log.w(TAG, "entity-filter answer: renderer release failed", it) }
                            }
                        }
                        call.respondText("{\"ok\":true}", ContentType.Application.Json)
                    }
                    get("/setup") {
                        // Deliberately NOT part of /api/v1/status: that endpoint's inputs are
                        // root/daemon-backed stale-while-revalidate reads, so a wizard polling every couple
                        // of seconds would keep kicking off privileged refreshes for data it never uses.
                        // Everything here is an in-memory read, and SetupStateEndpointContractTest pins it.
                        // Generic state readers (provisioning, diagnostics, monitoring) must not suppress
                        // recovery restarts. Only the wizard UI sends this explicit heartbeat header.
                        if (call.request.headers[SETUP_PRESENCE_HEADER] == SETUP_PRESENCE_ACTIVE) {
                            GuidedSetupPresence.noteHeartbeat(android.os.SystemClock.elapsedRealtime())
                        }
                        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
                        call.respondText(setupJourneyJson(), ContentType.Application.Json)
                    }
                    haOAuthRoutes(
                        HaOAuthRouteDependencies(
                            panelPort = config.httpPort,
                            start = { haUrl, panelOrigin -> startHaOAuth(haUrl, panelOrigin) },
                            startWithContext = { haUrl, panelOrigin, context ->
                                startHaOAuth(haUrl, panelOrigin, context)
                            },
                            startContext = { selection ->
                                val strings = catalogueLoader.strings(selection.locale)
                                HaOAuthStartContext(
                                    locale = selection.locale,
                                    returnSurface = selection.returnSurface,
                                    preserveExplicitEnglish = selection.preserveExplicitEnglish,
                                    copy = haOAuthCallbackCopy(strings),
                                    contentLanguages = strings.languages(setOf("oauth.callback.")).toSet(),
                                )
                            },
                            allowPseudoLocale = BuildConfig.DEBUG,
                            claim = haOAuthFlow::claim,
                            exchange = { attempt, code -> withContext(Dispatchers.IO) {
                                haOAuthExchange(attempt.haUrl, code, attempt.clientId)
                            } },
                            complete = ::completeHaOAuth,
                            status = haCurrentUser::status,
                            // Same journey rule as the QR: an unfinished panel returns to guided setup so
                            // the wizard can show the render-proof/completion step, a finished one to
                            // Configure. Evaluated at callback time, after the token has committed.
                            successReturnPath = {
                                if (!setupNeedsUser()) "/configure#cfg-ha_url" else "/setup"
                            },
                        ),
                    )
                    // Versioned config bundle: backup (export) and validated restore/deploy (import).
                    get("/config/export") { handleConfigExport(call) }
                    post("/config/import") { handleConfigImport(call) }
                    // Restore a .hpb bundle (raw body; passphrase in the X-Backup-Passphrase header so it
                    // never lands in a query log). ?dry_run=1 decrypts + reports the contents WITHOUT writing.
                    // A real restore is DESTRUCTIVE (rewrites config; force-stops + rewrites the Companion DB).
                    post("/restore") { handleRestore(call) }
                    // On-panel revision history + rollback.
                    get("/config/revisions") { call.respondText(revisionsJson(), ContentType.Application.Json) }
                    post("/config/revisions/{id}/restore") {
                        val id = call.parameters["id"]?.toLongOrNull()
                        if (id == null) call.respondText("bad-id\n", status = HttpStatusCode.BadRequest)
                        else handleRevisionRestore(call, id)
                    }
                    get("/perf") {
                        if (!admitActiveRead(call)) return@get
                        PerfReader.touch()
                        call.respondText(PerfReader.json(), ContentType.Application.Json)
                    }
                    get("/perf/binding") {
                        if (!admitActiveRead(call)) return@get
                        val comparisonId = call.request.queryParameters["comparison_id"].orEmpty()
                        if (!comparisonId.matches(PERFORMANCE_COMPARISON_ID)) return@get call.respondText(
                            "{\"error\":\"invalid comparison_id\"}",
                            ContentType.Application.Json,
                            HttpStatusCode.BadRequest,
                        )
                        val binding = performanceBindingJson(
                            comparisonId = comparisonId,
                            deviceSecret = performanceBindingSecret,
                            panelId = config.panelId,
                            workload = performanceWorkloadValues(),
                        ) ?: return@get call.respondText(
                            "{\"error\":\"stable device identity unavailable\"}",
                            ContentType.Application.Json,
                            HttpStatusCode.ServiceUnavailable,
                        )
                        call.respondText(binding, ContentType.Application.Json)
                    }
                    // Sparse A/B harvesters use this projection without activating the 2 s sampler whose
                    // own CPU and process probes would perturb the feature burden being measured.
                    get("/perf/costs") {
                        call.respondText(FeatureCosts.json(), ContentType.Application.Json)
                    }
                    get("/perf/history") {
                        if (!admitActiveRead(call)) return@get
                        val hours = call.request.queryParameters["hours"]?.toIntOrNull() ?: 24
                        call.respondText(entityLearning.performanceHistoryJson(hours), ContentType.Application.Json)
                    }
                    get("/auto-brightness") {
                        call.response.headers.append("Cache-Control", "no-store")
                        call.respondText(autoBrightnessHttpApi.statusJson(), ContentType.Application.Json)
                    }
                    get("/auto-sleep") {
                        call.respondText(autoSleepHttpApi.statusJson(), ContentType.Application.Json)
                    }
                    get("/auto-sleep/prerequisite") {
                        if (!admitActiveRead(call)) return@get
                        val result = autoSleepHttpApi.prerequisite()
                        call.respondText(
                            JSONObject()
                                .put("eligible", result.eligible)
                                .put("phase", result.phase.name.lowercase())
                                .put("area_name", result.areaName)
                                .put("detail", result.detail.take(240))
                                .toString(),
                            ContentType.Application.Json,
                        )
                    }
                    get("/auto-sleep/history") {
                        if (!admitActiveRead(call)) return@get
                        val hours = runCatching {
                            autoSleepHistoryHours(call.request.queryParameters["hours"])
                        }.getOrElse {
                            return@get call.respondText(
                                "${it.message ?: "invalid history query"}\n",
                                status = HttpStatusCode.BadRequest,
                            )
                        }
                        call.respondText(autoSleepHttpApi.historyJson(hours), ContentType.Application.Json)
                    }
                    post("/auto-sleep/source") {
                        val obj = receiveEntityAdminJson(call) ?: return@post
                        val areaKey = obj.optString("area_key").trim()
                        val sourceKey = obj.optString("source_key").trim()
                        val includedValue = obj.opt("included")
                        if (!OPAQUE_AUTO_SLEEP_KEY.matches(areaKey) ||
                            !OPAQUE_AUTO_SLEEP_KEY.matches(sourceKey) || includedValue !is Boolean
                        ) {
                            return@post call.respondText(
                                "area_key, source_key and included are required\n",
                                status = HttpStatusCode.BadRequest,
                            )
                        }
                        when (autoSleepHttpApi.setSourceIncluded(areaKey, sourceKey, includedValue)) {
                            HaPresenceSourceUpdate.UPDATED -> call.respondText(
                                """{"ok":true,"included":$includedValue}""",
                                ContentType.Application.Json,
                            )
                            HaPresenceSourceUpdate.STALE -> call.respondText(
                                "activity sources changed; reload and try again\n",
                                status = HttpStatusCode.Conflict,
                            )
                            HaPresenceSourceUpdate.COMMIT_FAILED -> call.respondText(
                                "configuration commit failed\n",
                                status = HttpStatusCode.InternalServerError,
                            )
                            HaPresenceSourceUpdate.UNAVAILABLE -> call.respondText(
                                "activity sources are unavailable\n",
                                status = HttpStatusCode.Conflict,
                            )
                        }
                    }
                    get("/auto-brightness/history") {
                        call.response.headers.append("Cache-Control", "no-store")
                        if (!admitActiveRead(call)) return@get
                        val request = runCatching {
                            autoBrightnessHistoryParameters(
                                call.request.queryParameters["hours"],
                                call.request.queryParameters["sensitivity"],
                                call.request.queryParameters["minimum_percent"],
                            )
                        }.getOrElse {
                            return@get call.respondText(
                                "${it.message ?: "invalid history query"}\n",
                                status = HttpStatusCode.BadRequest,
                            )
                        }
                        call.respondText(
                            autoBrightnessHttpApi.historyJson(
                                request.hours,
                                request.sensitivity,
                                request.minimumPercent,
                            ),
                            ContentType.Application.Json,
                        )
                    }
                    get("/auto-brightness/sources") {
                        val query = call.request.queryParameters["q"].orEmpty().trim().take(100)
                        val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 100).coerceIn(1, 200)
                        call.respondText(
                            autoBrightnessHttpApi.haSourcesJson(query, limit),
                            ContentType.Application.Json,
                        )
                    }
                    post("/auto-brightness/source") {
                        val obj = receiveEntityAdminJson(call, allowBlank = true) ?: return@post
                        if (!obj.has("entity_id")) {
                            return@post call.respondText(
                                "entity_id is required (null selects the panel sensor)\n",
                                status = HttpStatusCode.BadRequest,
                            )
                        }
                        val raw = obj.opt("entity_id")
                        val selected = when (raw) {
                            JSONObject.NULL -> null
                            is String -> {
                                val spec = requireNotNull(SettingsRegistry.spec("auto_brightness_ha_entity"))
                                when (val accepted = SettingValue.validate(spec, raw)) {
                                    is Validation.Ok -> accepted.normalized.ifBlank { null }
                                    is Validation.Bad -> return@post call.respondText(
                                        "${accepted.reason}\n",
                                        status = HttpStatusCode.BadRequest,
                                    )
                                }
                            }
                            else -> return@post call.respondText(
                                "entity_id must be a string or null\n",
                                status = HttpStatusCode.BadRequest,
                            )
                        }
                        respondAutoBrightnessAction(call, autoBrightnessHttpApi.selectHaSource(selected))
                    }
                    post("/auto-brightness/reset") {
                        respondAutoBrightnessAction(call, autoBrightnessHttpApi.resetHistory())
                    }
                    post("/auto-brightness/resume") {
                        respondAutoBrightnessAction(call, autoBrightnessHttpApi.resumeFullAuto())
                    }
                    // Experimental built-in-renderer entity filter. The exact ids are accepted at runtime
                    // but never echoed, logged, or included in config exports; status is count+hash.
                    get("/dashboard/entity-filter") {
                        call.respondText(entityFilterStatusJson(), ContentType.Application.Json)
                    }
                    post("/dashboard/entity-filter") { handleEntityFilterPost(call) }
                    get("/dashboard/entities") {
                        call.respondText(
                            entityLearning.entitiesJson(
                                call.request.queryParameters["q"].orEmpty(),
                                call.request.queryParameters["filter"] ?: "active",
                                call.request.queryParameters["limit"]?.toIntOrNull() ?: 100,
                                call.request.queryParameters["offset"]?.toIntOrNull() ?: 0,
                                call.request.queryParameters["sort"] ?: "entity_id",
                                call.request.queryParameters["dir"] ?: "asc",
                            ),
                            ContentType.Application.Json,
                        )
                    }
                    get("/dashboard/entities/sync") { call.respondText(entityLearning.statusJson(), ContentType.Application.Json) }
                    get("/dashboard/entities/issues") {
                        call.respondText(entityLearning.issuesJson(), ContentType.Application.Json)
                    }
                    post("/dashboard/entities/issues") {
                        val obj = receiveEntityAdminJson(call) ?: return@post
                        if (!obj.has("fingerprint") || !obj.has("ignored")) {
                            return@post call.respondText(
                                "fingerprint and ignored are required\n", status = HttpStatusCode.BadRequest,
                            )
                        }
                        val response = runCatching {
                            entityLearning.setIssueIgnored(obj.optString("fingerprint"), obj.optBoolean("ignored"))
                        }.getOrElse {
                            return@post call.respondText("invalid issue override: ${it.message}\n", status = HttpStatusCode.BadRequest)
                        }
                        call.respondText(response, ContentType.Application.Json)
                    }
                    post("/dashboard/entities/sync") {
                        if (entityLearning.syncNow("manual")) {
                            call.respondText(entityLearning.statusJson(), ContentType.Application.Json, HttpStatusCode.Accepted)
                        } else call.respondText("synchronization already running\n", status = HttpStatusCode.Conflict)
                    }
                    post("/dashboard/entities/activate") {
                        val obj = receiveEntityAdminJson(call, allowBlank = true) ?: return@post
                        val response = runCatching { entityLearning.activate(obj.optBoolean("confirm", false)) }.getOrElse {
                            return@post call.respondText("activation failed: ${it.message}\n", status = HttpStatusCode.BadRequest)
                        }
                        val status = if (JSONObject(response).optBoolean("confirmation_required")) HttpStatusCode.Conflict else HttpStatusCode.OK
                        call.respondText(response, ContentType.Application.Json, status)
                    }
                    post("/dashboard/entities/policy") {
                        val obj = receiveEntityAdminJson(call) ?: return@post
                        if (!obj.has("auto_static") || !obj.has("auto_runtime")) {
                            return@post call.respondText("auto_static and auto_runtime are required\n", status = HttpStatusCode.BadRequest)
                        }
                        val response = runCatching {
                            entityLearning.setPromotionPolicy(obj.optBoolean("auto_static"), obj.optBoolean("auto_runtime"))
                        }.getOrElse {
                            return@post call.respondText("invalid policy: ${it.message}\n", status = HttpStatusCode.BadRequest)
                        }
                        call.respondText(response, ContentType.Application.Json)
                    }
                    post("/dashboard/entities/override") {
                        val obj = receiveEntityAdminJson(call) ?: return@post
                        val response = runCatching {
                            entityLearning.setOverride(
                                obj.optString("entity_id"), obj.optString("override"), obj.optBoolean("force", false),
                            )
                        }.getOrElse {
                            return@post call.respondText("invalid override: ${it.message}\n", status = HttpStatusCode.BadRequest)
                        }
                        val status = if (JSONObject(response).optBoolean("confirmation_required")) HttpStatusCode.Conflict else HttpStatusCode.OK
                        call.respondText(response, ContentType.Application.Json, status)
                    }
                    post("/dashboard/entities/overrides") {
                        val obj = receiveEntityAdminJson(call) ?: return@post
                        val ids = obj.optJSONArray("entity_ids")?.let { array ->
                            (0 until array.length()).map { array.optString(it) }
                        }.orEmpty()
                        val response = runCatching {
                            entityLearning.setOverrides(
                                ids, obj.optBoolean("all_candidates", false), obj.optString("override"), obj.optBoolean("force", false),
                            )
                        }.getOrElse {
                            return@post call.respondText("invalid bulk override: ${it.message}\n", status = HttpStatusCode.BadRequest)
                        }
                        val status = if (JSONObject(response).optBoolean("confirmation_required")) HttpStatusCode.Conflict else HttpStatusCode.OK
                        call.respondText(response, ContentType.Application.Json, status)
                    }
                    post("/dashboard/entities/reset") {
                        val obj = receiveEntityAdminJson(call, allowBlank = true) ?: return@post
                        val response = runCatching {
                            entityLearning.resetEvidence(
                                confirm = obj.optBoolean("confirm", false),
                                clearFilter = obj.optBoolean("clear_filter", false),
                            )
                        }.getOrElse {
                            return@post call.respondText("reset failed: ${it.message}\n", status = HttpStatusCode.Conflict)
                        }
                        val status = if (JSONObject(response).optBoolean("confirmation_required")) HttpStatusCode.Conflict else HttpStatusCode.OK
                        call.respondText(response, ContentType.Application.Json, status)
                    }
                    get("/dashboard/entities/export") {
                        call.response.headers.append("Content-Disposition", "attachment; filename=ha-paneld-entities.json")
                        call.respondTextWriter(ContentType.Application.Json) {
                            entityLearning.writeExportJson(this)
                        }
                    }
                    get("/proximity") { call.respondText(sensors.proximityJson(), ContentType.Application.Json) }
                    // Live Sensors card: last-published values + live extras. Volume is the current
                    // media-stream percent; brightness is the system setting (0-255, -1 unknown).
                    get("/sensors") {
                        // Effective backlight first (reflects firmware dims); raw setting as fallback.
                        val bright = effectiveBrightness().takeIf { it >= 0 } ?: runCatching {
                            android.provider.Settings.System.getInt(appContext.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS)
                        }.getOrDefault(-1)
                        call.respondText(
                            """{${sensors.valuesJson()},"volume_pct":${runCatching { volume.getPercent() }.getOrDefault(-1)},"brightness":$bright}""",
                            ContentType.Application.Json,
                        )
                    }
                    // Home Assistant Assist pipelines for the Configure voice_pipelines picker. Delegates to
                    // an injectable directory (the voice-coordinator lane's real HA-backed implementation;
                    // the stub default reports 503 not-configured) rather than talking to Home Assistant here.
                    // The response is decided by the pure voicePipelinesResponse() so it is unit-testable
                    // without a routed request.
                    get("/voice/pipelines") {
                        val caps = liveCapabilities(snapStaleOk().caps)
                        val refusal = voicePipelinesRefusal(hasMicrophone = caps.hasMicrophone)
                        if (refusal != null) {
                            call.respondText(
                                "{\"error\":\"unavailable\",\"reason\":${Json.str(refusal)}}",
                                ContentType.Application.Json,
                                HttpStatusCode.ServiceUnavailable,
                            )
                            return@get
                        }
                        val (status, body) = voicePipelinesResponse(assistPipelines.list())
                        call.respondText(body, ContentType.Application.Json, status)
                    }
                    // One-shot voice-assistant test run. Refused with 409 before ever reaching the trigger
                    // when the panel has no microphone capability or voice_enabled is off, so a disabled
                    // panel never depends on whether the coordinator lane happens to be wired up. The
                    // refusal check and the trigger-result mapping are both pure (voiceTestRefusal(),
                    // voiceTestTriggerResponse()) so every branch is unit-testable without a routed request.
                    post("/voice/test") {
                        val caps = liveCapabilities(snapStaleOk().caps)
                        val refusal = voiceTestRefusal(hasMicrophone = caps.hasMicrophone, voiceEnabled = config.voiceEnabled)
                        if (refusal != null) {
                            call.respondText(
                                "{\"reason\":${Json.str(refusal)}}",
                                ContentType.Application.Json,
                                HttpStatusCode.Conflict,
                            )
                            return@post
                        }
                        val (status, body) = voiceTestTriggerResponse(voiceTest.trigger())
                        call.respondText(body, ContentType.Application.Json, status)
                    }
                    // LAN ha-paneld panels for the header panel switcher — a cheap, non-blocking snapshot of
                    // the live mDNS roster (a background listener keeps it converged + fresh; see browsePeers).
                    get("/peers") {
                        call.respondText(peersJson(peers()), ContentType.Application.Json)
                    }
                    // Hydration payload for the dashboard (see infoJson) — the one place the probe
                    // suite actually runs; cached + single-flight, so concurrent viewers share it.
                    get("/info") {
                        val strings = requestStrings(call)
                        call.response.headers.append(HttpHeaders.Vary, HttpHeaders.AcceptLanguage)
                        call.response.headers.append(
                            HttpHeaders.ContentLanguage,
                            strings.languages(setOf("dashboard.")).joinToString(", "),
                        )
                        call.respondText(withContext(Dispatchers.IO) { infoJson(strings) }, ContentType.Application.Json)
                    }
                    get("/diag") {
                        call.respondText(
                            withContext(Dispatchers.IO) { diagStaleOk() },
                            ContentType.Text.Plain,
                        )
                    }
                    // Live log tail as Server-Sent Events (?source=app|system|webview). Feeds the Logs tab;
                    // also curl-able (`curl -N .../api/v1/logs/stream`). Lines are pre-redacted.
                    get("/logs/stream") {
                        if (admitActiveRead(call)) handleLogStream(call)
                    }
                    // Health + capabilities as JSON (warnings as ready-to-render HTML) — feeds every
                    // variant's Install/health section client-side. ?refresh=1 forces both the GitHub
                    // update check and a serialized SQLite observation for this exact response.
                    get("/status") {
                        // Only the exact agreed value counts; it hides one MQTT entity and grants nothing.
                        if (PanelAssistantUpdateLease.declares(call.request.headers[PanelAssistantUpdateLease.HEADER])) {
                            onPanelAssistantUpdateOwner()
                        }
                        val updateRefreshRequested = call.request.queryParameters["refresh"] == "1"
                        val observationNonce = call.request.queryParameters["database_observation_nonce"]
                        val refreshRequested = updateRefreshRequested || observationNonce != null
                        if (refreshRequested && !admitActiveRead(call)) return@get
                        val statusStorage = withContext(Dispatchers.IO) {
                            refreshedStatusStorage(
                                refreshRequested = refreshRequested,
                                refreshUpdates = {
                                    if (updateRefreshRequested) runCatching {
                                        UpdateChecker.check(
                                            appContext,
                                            config.updateChannel,
                                            config.companionUpdateChannel,
                                            profile.companionMaxVersion,
                                        )
                                    }
                                },
                                refreshStorage = refreshStorageHealth,
                                cachedStorage = storageHealth,
                            )
                        }
                        call.respondText(
                            withContext(Dispatchers.IO) {
                                statusJson(
                                    statusStorage.snapshot,
                                    databaseObservationProof(refreshRequested, observationNonce, statusStorage),
                                )
                            },
                            ContentType.Application.Json,
                        )
                    }
                    get("/power-safety") {
                        val advisory = powerSafetyAdvisory(snapStaleOk().privilege)
                        call.respondText(
                            PowerSafetyPresentation.json(advisory),
                            ContentType.Application.Json,
                        )
                    }
                    get("/power-safety/state") {
                        call.respondText(powerSafety().level.wireValue + "\n", ContentType.Text.Plain)
                    }
                    post("/power-safety/repair") {
                        if (!authorizeSensitive(
                                call,
                                SensitiveOperation.POWER_CONFIGURATION,
                                exactHttpApprovalPayload(call, sha256Hex(ByteArray(0))),
                                "Enable and verify panel power-safety guards",
                            )
                        ) return@post
                        val result = withContext(Dispatchers.IO) { onRepairPowerSafety() }
                        snapInvalidate()
                        val repairCapability = PowerRepairCapability.values()
                            .firstOrNull { it.wireValue == result.privilegedPowerControl }
                            ?: PowerRepairCapability.DEGRADED
                        val advisory = PowerSafetyAdvisoryPolicy.evaluate(
                            result.assessment,
                            repairCapability,
                            config.powerSafetyAcknowledgementFingerprint,
                        )
                        val failed = result.status == "failed"
                        val wantsJson = call.request.headers[HttpHeaders.Accept]
                            ?.contains("application/json", ignoreCase = true) == true
                        if (wantsJson) {
                            call.respondText(
                                PowerSafetyPresentation.repairJson(result, advisory),
                                ContentType.Application.Json,
                                if (failed) HttpStatusCode.ServiceUnavailable else HttpStatusCode.OK,
                            )
                        } else {
                            val message = PowerSafetyPresentation.repairMessage(result)
                            call.respondText(
                                configMutationHtml(message).replace(
                                    "url=configure",
                                    "url=configure#cfg-keep_awake",
                                ),
                                ContentType.Text.Html,
                                if (failed) HttpStatusCode.ServiceUnavailable else HttpStatusCode.OK,
                            )
                        }
                    }
                    post("/power-safety/acknowledge") {
                        val parameters = receiveBoundedFormParameters(call) ?: return@post
                        val requested = parameters["fingerprint"]?.trim().orEmpty()
                        if (!PowerSafetyAdvisoryPolicy.isAcknowledgementFingerprint(requested)) {
                            call.respondText(
                                """{"ok":false,"acknowledged":false,"error":"invalid-fingerprint","message":"The acknowledgement token is invalid; refresh and review the current caution."}""",
                                ContentType.Application.Json,
                                HttpStatusCode.BadRequest,
                            )
                            return@post
                        }
                        if (!authorizeSensitive(
                                call,
                                SensitiveOperation.POWER_SAFETY_ACKNOWLEDGEMENT,
                                exactHttpApprovalPayload(call, parameters.canonicalDigest()),
                                "Hide one exact unchanged panel power-safety caution",
                            )
                        ) return@post
                        // Re-observe after the request is materialized. The submitted value is only an
                        // expected-state token; persisted truth always comes from this server observation.
                        val current = withContext(Dispatchers.IO) {
                            PowerSafetyAdvisoryPolicy.evaluate(
                                powerSafety(),
                                freshPowerSafetyRepairCapability(),
                                config.powerSafetyAcknowledgementFingerprint,
                            )
                        }
                        val decision = PowerSafetyAdvisoryPolicy.admitAcknowledgement(requested, current)
                        val wantsJson = call.request.headers[HttpHeaders.Accept]
                            ?.contains("application/json", ignoreCase = true) == true
                        val (status, error, message) = when (decision) {
                            PowerSafetyAcknowledgementDecision.MALFORMED -> Triple(
                                HttpStatusCode.BadRequest,
                                "invalid-fingerprint",
                                "The acknowledgement token is invalid; refresh and review the current caution.",
                            )
                            PowerSafetyAcknowledgementDecision.STALE -> Triple(
                                HttpStatusCode.Conflict,
                                "stale-assessment",
                                "Power-safety evidence changed; review the current caution before hiding it.",
                            )
                            PowerSafetyAcknowledgementDecision.NOT_ACKNOWLEDGEABLE -> Triple(
                                HttpStatusCode.Conflict,
                                "not-acknowledgeable",
                                "This power-safety state cannot be hidden because repair is available or risk is elevated or unknown.",
                            )
                            PowerSafetyAcknowledgementDecision.ACCEPT -> {
                                val fingerprint = requireNotNull(current.acknowledgementFingerprint)
                                if (config.commitPowerSafetyAcknowledgement(fingerprint)) {
                                    Triple(HttpStatusCode.OK, "", "This unchanged caution is hidden on panel web pages. Diagnostics and installer checks remain unchanged.")
                                } else {
                                    Triple(HttpStatusCode.ServiceUnavailable, "persistence-failed", "The caution was not hidden because the acknowledgement could not be saved.")
                                }
                            }
                        }
                        val acknowledged = decision == PowerSafetyAcknowledgementDecision.ACCEPT && status == HttpStatusCode.OK
                        val projected = if (acknowledged) {
                            PowerSafetyAdvisoryPolicy.evaluate(
                                current.assessment,
                                current.repairCapability,
                                current.acknowledgementFingerprint,
                            )
                        } else current
                        if (wantsJson) {
                            call.respondText(
                                JSONObject()
                                    .put("ok", acknowledged)
                                    .put("acknowledged", acknowledged)
                                    .put("error", error.takeIf { it.isNotEmpty() } ?: JSONObject.NULL)
                                    .put("message", message)
                                    .put("power_safety", JSONObject(PowerSafetyPresentation.json(projected)))
                                    .toString(),
                                ContentType.Application.Json,
                                status,
                            )
                        } else {
                            call.respondText(
                                configMutationHtml(message).replace("url=configure", "url=configure#cfg-keep_awake"),
                                ContentType.Text.Html,
                                status,
                            )
                        }
                    }
                    // Dismiss a component update from the DASHBOARD banner only (per-version; re-surfaces when
                    // a newer release ships). The Install tab still lists it. See Config.ignoreUpdate.
                    post("/updates/ignore") {
                        val p = receiveBoundedFormParameters(call) ?: return@post
                        val label = p["label"]?.trim().orEmpty()
                        val version = p["version"]?.trim().orEmpty()
                        if (label.isEmpty() || version.isEmpty())
                            call.respondText("""{"ok":false}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                        else { config.ignoreUpdate(label, version); call.respondText("""{"ok":true}""", ContentType.Application.Json) }
                    }
                    // Recent installable versions for a component's picker (name ∈ {paneld,companion};
                    // channel ∈ {stable,prerelease}). Up to 10, newest first, each with a release-notes URL.
                    get("/install/versions") {
                        val name = call.request.queryParameters["name"]?.trim().orEmpty()
                        val channel = call.request.queryParameters["channel"]?.trim()?.ifEmpty { "stable" } ?: "stable"
                        val vers = withContext(Dispatchers.IO) {
                            when (name) {
                                "paneld" -> SelfUpdater.versions(channel)
                                "companion" -> CompanionInstaller.versions(
                                    channel,
                                    maxVersion = profile.companionMaxVersion,
                                )
                                else -> emptyList()
                            }
                        }
                        val installedVersion = when (name) {
                            "paneld" -> Config.VERSION
                            "companion" -> CompanionInstaller.installedPkg(appContext)?.let {
                                AppInstaller.installedVersion(appContext, it)
                            }
                            else -> null
                        }
                        val arr = vers.joinToString(",") { v ->
                            val candidate = if (name == "companion") UpdateChecker.stripVariant(v.version) else v.version
                            val installed = installedVersion?.let {
                                if (name == "companion") UpdateChecker.stripVariant(it) else it
                            }
                            val comparison = installed?.let { UpdateChecker.compareVersions(candidate, it) }
                            val (action, presentation) = when {
                                comparison == null || comparison == 0 ->
                                    "Install" to InstallPresentation("version-install")
                                comparison > 0 ->
                                    "Upgrade" to InstallPresentation("version-upgrade")
                                else ->
                                    "Downgrade" to InstallPresentation("version-downgrade")
                            }
                            """{"version":${jsonStr(v.version)},"tag":${jsonStr(v.tag)},"notes":${jsonStr(v.notesUrl)},"installable":${v.installable},"action":${jsonStr(action)},"apk":${jsonStr(v.apkUrl ?: "")},"presentations":{"action":${presentation.json()}}}"""
                        }
                        call.respondText("""{"channel":${jsonStr(channel)},"versions":[$arr]}""", ContentType.Application.Json)
                    }
                    get("/install/status") { call.respondText(InstallProgress.json(), ContentType.Application.Json) }
                    // Enable/disable the APK-upload capability (the card's toggle).
                    post("/install/apk/allow") {
                        val on = (receiveBoundedFormParameters(call) ?: return@post)["on"]
                            ?.let { it == "true" || it == "1" } ?: true
                        config.setApkUploadAllowed(on)
                        if (!on) pendingApks.clear()
                        call.respondText("""{"ok":true,"allowed":$on}""", ContentType.Application.Json)
                    }
                    // Removable apps (third-party + updated-system; excludes ha-paneld + stock system apps,
                    // which pm can't uninstall anyway) for the Uninstall card's picker.
                    get("/packages") { call.respondText(withContext(Dispatchers.IO) { packagesJson() }, ContentType.Application.Json) }
                    // Launchable apps plus the supported installed Companion renderer choices —
                    // populates the Configure tab's Dashboard-app / Launcher-app pickers.
                    get("/apps") { call.respondText(withContext(Dispatchers.IO) { launchableAppsJson() }, ContentType.Application.Json) }
                    // Uninstall a package over root. Guarded: never ha-paneld itself; the picker only offers
                    // removable apps. `pm uninstall` (system/vendor apps aren't removable, only disable-able
                    // via taming — a separate, safer path).
                    post("/uninstall") {
                        if (!Su.availableCachedIsolated()) return@post call.respondText(
                            """{"ok":false,"error":"no-root"}""", ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
                        val parameters = receiveBoundedFormParameters(call) ?: return@post
                        val pkg = parameters["pkg"]?.trim().orEmpty()
                        val protected = pkg == appContext.packageName || pkg == io.github.maxlyth.hapaneld.util.WebViewInstaller.WEBVIEW_PKG
                        if (pkg.isEmpty() || protected || !AndroidInput.isPackage(pkg) ||
                            pkg !in removablePackages().mapTo(hashSetOf()) { it.first })
                            return@post call.respondText("""{"ok":false,"error":"bad-package"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                        if (!authorizeSensitive(
                                call,
                                SensitiveOperation.PACKAGE_UNINSTALL,
                                exactHttpApprovalPayload(call, parameters.canonicalDigest()),
                                "Uninstall $pkg",
                            )
                        ) return@post
                        val progress = InstallProgress.start(
                            "Uninstall",
                            InstallPresentation("operation-working", mapOf("owner" to "package-uninstall")),
                        ) ?: return@post call.respondText(
                            """{"ok":false,"error":"busy"}""", ContentType.Application.Json, HttpStatusCode.Conflict)
                        var progressResult = "uninstall cancelled"
                        var progressPresentation: InstallPresentation? = InstallPresentation("operation-cancelled")
                        try {
                            val (out, path) = withContext(Dispatchers.IO) {
                                Su.runOutput("pm uninstall $pkg")?.trim() to
                                    Su.runOutput("pm path $pkg 2>/dev/null")?.trim()
                            }
                            // Empty stdout can be a real persistent-shell success, but null means the probe failed.
                            val ok = uninstallSucceeded(out, path)
                            progressResult = if (ok) "uninstalled $pkg" else "uninstall failed: $pkg"
                            progressPresentation = InstallPresentation(
                                if (ok) "package-uninstalled" else "package-uninstall-failed",
                                mapOf("package" to pkg),
                            )
                            if (ok) Log.i(TAG, "uninstalled $pkg")
                            val result = out?.ifEmpty { if (ok) "removed" else "uninstall failed" } ?: "uninstall failed"
                            call.respondText("""{"ok":$ok,"result":${jsonStr(result)}}""", ContentType.Application.Json)
                        } finally {
                            InstallProgress.finish(
                                progress,
                                progressResult,
                                presentation = progressPresentation,
                            )
                        }
                    }
                    // EFR32 radio status (Install-tab Radio card). {present, status}. present=false → no radio.
                    get("/radio") {
                        val st = withContext(Dispatchers.IO) { radioStatus() }
                        val body = if (st == null) """{"present":false,"status":"none"}""" else JSONObject()
                            .put("present", true)
                            .put("router_configured", config.zigbeeRouterConfigured)
                            .put("router_enabled", config.zigbeeRouterConfigured && config.zigbeeRouterEnabled)
                            .put("status", st.publicSummary())
                            .put("state", st.state.wireValue)
                            .put("attributes", JSONObject(st.mqttAttributes()))
                            .toString()
                        call.respondText(body, ContentType.Application.Json)
                    }
                    post("/radio/join") {
                        val st = radioStatus()
                        when {
                            st == null -> call.respondText(
                                """{"status":"unavailable"}""",
                                ContentType.Application.Json,
                                HttpStatusCode.NotFound,
                            )
                            !config.zigbeeRouterConfigured || !config.zigbeeRouterEnabled ->
                                call.respondText(
                                    """{"status":"disabled"}""",
                                    ContentType.Application.Json,
                                    HttpStatusCode.Conflict,
                                )
                            onZigbeeJoinRetry() -> call.respondText(
                                """{"status":"started"}""",
                                ContentType.Application.Json,
                            )
                            else -> call.respondText(
                                """{"status":"busy"}""",
                                ContentType.Application.Json,
                                HttpStatusCode.ServiceUnavailable,
                            )
                        }
                    }
                    // Auto-heal the System WebView (download + install the profile's recommended build).
                    // Fire-and-forget: the install runs off-thread (large download); the client refreshes.
                    post("/webview/heal") {
                        if (!authorizeSensitive(
                                call,
                                SensitiveOperation.APK_INSTALL,
                                exactHttpApprovalPayload(call, sha256Hex(ByteArray(0))),
                                "Reinstall the recommended System WebView",
                            )
                        ) return@post
                        val status = if (onInstallComponent("webview", "reinstall", "")) "started" else "busy"
                        call.respondText("""{"status":"$status"}""", ContentType.Application.Json)
                    }
                    // Clear the built-in renderer's browsing data (localStorage/IndexedDB/caches/cookies)
                    // — the remote heal for a corrupted-storage dashboard that survives plain reloads.
                    // Sign-in is NOT stored there (the external-auth bridge holds the token in Config),
                    // so this never logs the panel out. Relaunches the built-in renderer when it's the
                    // active dashboard so it comes back on a clean slate. WebView APIs are UI-thread-only.
                    post("/dashboard/clear-storage") {
                        if (!authorizeSensitive(
                                call,
                                SensitiveOperation.DASHBOARD_STORAGE_CLEAR,
                                exactHttpApprovalPayload(call, sha256Hex(ByteArray(0))),
                                "Clear the built-in dashboard's browsing data",
                            )
                        ) return@post
                        val token = clearStorageGate.claim()
                        if (token == null) {
                            call.respondText(
                                """{"status":"busy"}""",
                                ContentType.Application.Json,
                                if (stopping) HttpStatusCode.ServiceUnavailable else HttpStatusCode.Conflict,
                            )
                            return@post
                        }
                        val posted = android.os.Handler(android.os.Looper.getMainLooper()).post storage@{
                            val cost = FeatureCosts.registry.span(FeatureCostOperation.DASHBOARD_STORAGE_CLEAR)
                            try {
                                if (!clearStorageGate.isCurrent(token) || stopping) {
                                    cost.outcome(FeatureCostOutcome.CANCELLED)
                                    return@storage
                                }
                                android.webkit.WebStorage.getInstance().deleteAllData()
                                android.webkit.CookieManager.getInstance().removeAllCookies(null)
                                // The HTTP resource cache is per-application but only reachable through a
                                // WebView instance — a throwaway one clears it for every WebView we host
                                // (a corrupted cached asset is exactly what this heal exists for).
                                runCatching {
                                    android.webkit.WebView(appContext).apply {
                                        settings.allowContentAccess = false
                                        settings.allowFileAccess = false
                                        clearCache(true)
                                        destroy()
                                    }
                                }
                                if (config.dashboardPackage == "builtin" && !stopping) {
                                    // Privileged-first relaunch (BAL rules block a plain startActivity
                                    // from a service context) — off the main thread, it may shell out.
                                    scope.launch {
                                        runCatching {
                                            system.reloadDashboard(
                                                SystemController.BUILTIN_DASHBOARD,
                                                reason = "clearing the dashboard’s stored data",
                                            )
                                        }
                                    }
                                }
                            } catch (error: Exception) {
                                cost.outcome(FeatureCostOutcome.FAILURE)
                                Log.w(TAG, "dashboard storage clear failed", error)
                            } finally {
                                clearStorageGate.finish(token)
                                cost.close()
                            }
                        }
                        if (!posted) {
                            clearStorageGate.finish(token)
                            FeatureCosts.registry.recordDropped(FeatureCostOperation.DASHBOARD_STORAGE_CLEAR)
                            call.respondText(
                                """{"status":"stopping"}""",
                                ContentType.Application.Json,
                                HttpStatusCode.ServiceUnavailable,
                            )
                            return@post
                        }
                        call.respondText(
                            """{"status":"started"}""",
                            ContentType.Application.Json,
                            HttpStatusCode.Accepted,
                        )
                    }
                    // Repair a Companion server row with an empty internal_url (HA 2026.7 "Missing Host
                    // header" incident). Fire-and-forget: the repair force-stops + relaunches the Companion
                    // off-thread; invalidate the health cache so the warning clears on the next poll.
                    post("/companion/repair-url") {
                        if (!authorizeSensitive(
                                call,
                                SensitiveOperation.COMPANION_REPAIR,
                                exactHttpApprovalPayload(call, sha256Hex(ByteArray(0))),
                                "Repair and relaunch the Home Assistant Companion",
                            )
                        ) return@post
                        val started = onRepairCompanionUrl()
                        if (started) companionServerCache.invalidate()
                        call.respondText("""{"status":"${if (started) "started" else "busy"}"}""", ContentType.Application.Json)
                    }
                    // Per-panel Canvas dashboard layout (opaque Gridstack JSON, stored in Config).
                    get("/ui/layout") {
                        call.respondText("""{"layout":${jsonStr(config.uiDashboardLayout)}}""", ContentType.Application.Json)
                    }
                    post("/ui/layout") {
                        config.uiDashboardLayout = (receiveBoundedFormParameters(
                            call,
                            MAX_CONFIG_POST_BODY_BYTES,
                        ) ?: return@post)["layout"].orEmpty()
                        call.respondText("""{"ok":true}""", ContentType.Application.Json)
                    }
                    // Inject a tap at device pixel (x,y). capture=1 is the Dashboard overlay PoC's
                    // combined one-tap/settled-screenshot operation; omission preserves legacy 202 admission.
                    post("/input") {
                        val q = receiveBoundedFormParameters(call) ?: return@post
                        val x = q["x"]?.trim()?.toFloatOrNull()
                        val y = q["y"]?.trim()?.toFloatOrNull()
                        val capture = q["capture"]?.let(SettingValue::parseBool) ?: false
                        if (x == null || y == null || !x.isFinite() || !y.isFinite() ||
                            x < 0f || y < 0f || x > Int.MAX_VALUE || y > Int.MAX_VALUE
                        ) {
                            call.respondText("bad-coords\n", status = HttpStatusCode.BadRequest)
                        } else if (q["capture"] != null && SettingValue.parseBool(q["capture"].orEmpty()) == null) {
                            call.respondText("bad-capture\n", status = HttpStatusCode.BadRequest)
                        } else {
                            respondRemoteAdmission(call, RemoteControl.Tap(x, y, capture = capture))
                        }
                    }
                    // On-screen Controls card (software navbar) for panels with no physical nav bar.
                    post("/action") {
                        handleRemoteAction(
                            call,
                            RemoteActionRouteDependencies(
                                authorizeSensitive = ::authorizeSensitive,
                                admit = { request, action ->
                                    respondRemoteAdmission(request, RemoteControl.Action(action))
                                },
                            ),
                        )
                    }
                    // Debug-only sensor trace (RAM ring buffer, on by default) for fit-testing the
                    // auto-brightness + proximity filters. CSV by default (drop into a plot); ?format=json
                    // for programmatic use / a future on-panel chart. Not an HA/MQTT surface.
                    get("/sensortrace") {
                        if (call.request.queryParameters["format"] == "json") {
                            call.respondText(io.github.maxlyth.hapaneld.sensors.SensorTrace.toJson(), ContentType.Application.Json)
                        } else {
                            call.respondText(io.github.maxlyth.hapaneld.sensors.SensorTrace.toCsv(), ContentType("text", "csv"))
                        }
                    }
                    // Live panel screenshot via root `screencap` (LAN-only like the rest of this surface).
                    // Embedded scaled in the info page + linkable full-size; also usable as an HA camera
                    // still_image_url. The card asks for ?cached=1 first so it can show the last successful
                    // capture immediately, then requests a fresh image in the background. A successful live
                    // capture atomically replaces the app-private placeholder; failed captures leave it intact.
                    get("/screenshot.png") {
                        if (!admitActiveRead(call)) return@get
                        val cachedId = call.request.queryParameters["cached"]
                        if (cachedId != null) {
                            call.response.headers.append("Cache-Control", "private, max-age=31536000, immutable")
                            val cached = withContext(Dispatchers.IO) { readCachedScreenshot(cachedId) }
                            if (cached != null) call.respondBytes(cached, ContentType.Image.PNG)
                            else call.respondText("screenshot-unavailable\n", status = HttpStatusCode.ServiceUnavailable)
                            return@get
                        }
                        call.response.headers.append("Cache-Control", "no-store")
                        val png = withContext(Dispatchers.IO) { interactive.screenshot() }
                        if (png != null && png.isNotEmpty()) {
                            withContext(Dispatchers.IO) { cacheScreenshot(png) }?.let {
                                call.response.headers.append("X-ha-paneld-Screenshot-Id", it)
                            }
                            call.respondBytes(png, ContentType.Image.PNG)
                        } else {
                            call.respondText("screenshot-unavailable\n", status = HttpStatusCode.ServiceUnavailable)
                        }
                    }
                    // Camera trial. The camera opens only for the duration of this request and closes
                    // when no other subscriber
                    // remains, so a caller must expect the open cost on every snapshot. No detail beyond
                    // the refusal token in the body — the finer classification lives in /api/v1/status.
                    get("/camera/snapshot.jpg") {
                        if (!admitActiveRead(call, allowLegacyNavigation = true)) return@get
                        val requestedRaw = call.request.queryParameters["res"]
                        val requested = when {
                            requestedRaw == null -> null
                            else -> CameraResolution.parse(requestedRaw) ?: run {
                                call.respondText(
                                    "unknown res '$requestedRaw' (480p|720p|1080p)\n",
                                    status = HttpStatusCode.BadRequest,
                                )
                                return@get
                            }
                        }
                        call.response.headers.append("Cache-Control", "no-store")
                        when (val result = withContext(Dispatchers.IO) { camera.snapshot(requested) }) {
                            is SnapshotResult.Jpeg -> call.respondBytes(result.bytes, ContentType.Image.JPEG)
                            is SnapshotResult.Refused -> call.respondText(
                                "${result.reason.token}\n",
                                status = HttpStatusCode.fromValue(
                                    CameraRefusal.snapshotStatusCode(result.reason),
                                ),
                            )
                        }
                    }
                    // Exactly the object `/api/v1/status` carries under `camera`, served alone so the
                    // Dashboard's camera card can poll it every couple of seconds without rebuilding
                    // the whole status document. One projection, one renderer: the bytes are produced
                    // by the same `statusJson()`, so the card and the status object cannot drift.
                    //
                    // Unadmitted for the same reason `/sensors` is: there is no work here to gate. The
                    // call reads the session's own state under its lock and never opens the camera, so
                    // an idle panel stays at zero cost, and the identical bytes are already readable
                    // from `/api/v1/status` — this adds no exposure, only a cheaper way to ask.
                    get("/camera/status") {
                        call.response.headers.append("Cache-Control", "no-store")
                        call.respondText(camera.presentation().statusJson(), ContentType.Application.Json)
                    }
                    get("/openapi.json") {
                        call.respondText(asset("openapi.json"), ContentType.Application.Json)
                    }
                    post("/proximity/calibration") {
                        if (!proximityUiRequestAllowed(
                                call.request.headers["Origin"], call.request.headers["Referer"],
                                call.request.headers["Host"], call.request.headers["Sec-Fetch-Site"],
                                call.request.headers["X-Proximity-UI"],
                            )) {
                            call.respondText("Start proximity setup from this panel's HTML UI.\n", status = HttpStatusCode.Forbidden)
                            return@post
                        }
                        val parameters = receiveBoundedFormParameters(call) ?: return@post
                        val action = parameters["action"].orEmpty()
                        if (action !in setOf("start", "cancel", "reset", "heartbeat")) {
                            call.respondText("Unsupported calibration action.\n", status = HttpStatusCode.BadRequest)
                            return@post
                        }
                        if (!sensors.hasProximity()) {
                            call.respondText(PROXIMITY_SOURCE_REQUIRED, ContentType.Application.Json, HttpStatusCode.Conflict)
                            return@post
                        }
                        val id = parameters["sessionId"].orEmpty()
                        val accepted = withContext(Dispatchers.IO) { onProximityCalibration(action, id) }
                        call.response.headers.append("Cache-Control", "no-store")
                        call.respondText(sensors.proximityJson(), ContentType.Application.Json,
                            if (accepted) HttpStatusCode.Accepted else HttpStatusCode.Conflict)
                    }
                    post("/proximity/teach") {
                        call.respondText("Use on-panel proximity setup from the HTML UI.\n", status = HttpStatusCode.Gone)
                    }
                    post("/proximity/test") {
                        call.respondText("Use on-panel proximity setup from the HTML UI.\n", status = HttpStatusCode.Gone)
                    }
                    post("/proximity/relearn") {
                        call.respondText("Use Reset to profile from the HTML UI.\n", status = HttpStatusCode.Gone)
                    }
                    post("/proximity/capture") {
                        call.respondText(RETIRED_PROXIMITY_OPERATION, ContentType.Application.Json, HttpStatusCode.Gone)
                    }
                    post("/proximity/threshold") {
                        call.respondText(RETIRED_PROXIMITY_OPERATION, ContentType.Application.Json, HttpStatusCode.Gone)
                    }
                    post("/proximity/sensitivity") {
                        call.respondText(RETIRED_PROXIMITY_OPERATION, ContentType.Application.Json, HttpStatusCode.Gone)
                    }
                    post("/proximity/reset") {
                        call.respondText(RETIRED_PROXIMITY_OPERATION, ContentType.Application.Json, HttpStatusCode.Gone)
                    }
                    // Per-package vendor taming from the Vendor packages card. action=tame adds the package to
                    // the blocklist and tames it now; action=untame explicitly enables it, then removes it from
                    // the blocklist. The explicit enable also handles firmware-disabled packages which ha-paneld
                    // never owned and therefore have no restoration marker. The work is privileged + slow, so it
                    // runs off-thread and the browser gets a short auto-reload back to the Install card.
                    post("/tame") {
                        val strings = requestStrings(call)
                        val returnTo = localizedHref("install#cfg-tame", strings)
                        val p = receiveBoundedFormParameters(call) ?: return@post
                        // One-click "Tame all recommended" (the profile's defaultTame set) — no pkg needed.
                        // Persist the safe installed selection first. The one desired-state owner then converges
                        // it; write-ahead overlay ownership makes an interrupted profile restart retryable.
                        if (p["action"]?.trim() == "recommended") {
                            val recommendedSelections = tame.recommendedSelections(tameProfileCandidates)
                            val recommended = recommendedSelections.joinToString("\u0000")
                            val digest = sha256Hex((p.canonicalDigest() + "\u0000" + recommended).toByteArray())
                            if (!authorizeSensitive(
                                    call,
                                    SensitiveOperation.PACKAGE_TAME,
                                    exactHttpApprovalPayload(call, digest),
                                    strings.get("install.tame.approval.recommended"),
                                )
                            ) return@post
                            val committed = withContext(Dispatchers.IO) {
                                updateTameSelection { it.addAll(recommendedSelections) }
                            }
                            if (!committed) {
                                respondInstallFormError(
                                    call,
                                    strings,
                                    "install.tame.error.selection_commit",
                                    "vendor selection commit failed",
                                    HttpStatusCode.InternalServerError,
                                )
                                return@post
                            }
                            snapInvalidate()
                            if (call.request.headers["Accept"]?.contains("application/json") == true) {
                                call.respondText(
                                    "{" +
                                        "\"ok\":true,\"status\":\"started\",\"message\":" + jsonStr(strings.get("install.tame.result.applying_recommended")) + "," +
                                        "\"return_to\":" + jsonStr(returnTo) + "}",
                                    ContentType.Application.Json,
                                )
                            } else {
                                call.respondText(
                                    "<!doctype html><base href=\"/\"><meta charset=utf-8><meta http-equiv=refresh content='2;url=${esc(returnTo)}'>" +
                                        "<body style='font-family:system-ui;background:#111;color:#eee;padding:20px'>" +
                                        esc(strings.get("install.tame.result.applying_recommended_progress")) + "</body>",
                                    ContentType.Text.Html,
                                )
                            }
                            return@post
                        }
                        val pkg = p["pkg"]?.trim().orEmpty()
                        val untame = p["action"]?.trim() == "untame"
                        // Re-enable is always allowed; taming is refused for protected packages (the brick-guard
                        // — critical AOSP names, vendor-renamed persistent system services, launchers, the IME)
                        // so a hand-typed package name can't disable something the panel needs.
                        if (!AndroidInput.isPackage(pkg) || (!untame && tame.isProtected(pkg))) {
                            respondInstallFormError(
                                call,
                                strings,
                                "install.tame.error.invalid_or_protected",
                                "invalid or protected package",
                                HttpStatusCode.BadRequest,
                            )
                            return@post
                        }
                        if (!authorizeSensitive(
                                call,
                                SensitiveOperation.PACKAGE_TAME,
                                exactHttpApprovalPayload(call, p.canonicalDigest()),
                                formattedString(
                                    strings,
                                    "install.tame.approval.package",
                                    "action" to strings.get(if (untame) "install.tame.action.reenable" else "install.tame.action.tame"),
                                    "package" to pkg,
                                ),
                            )
                        ) return@post
                        if (untame && !withContext(Dispatchers.IO) { tame.reenable(pkg) }) {
                            respondInstallFormError(
                                call,
                                strings,
                                "install.tame.error.reenable_failed",
                                "could not re-enable package",
                                HttpStatusCode.ServiceUnavailable,
                            )
                            return@post
                        }
                        val committed = withContext(Dispatchers.IO) {
                            updateTameSelection { selected ->
                                if (untame) selected.remove(pkg) else selected.add(pkg)
                            }
                        }
                        if (!committed) {
                            respondInstallFormError(
                                call,
                                strings,
                                "install.tame.error.selection_commit",
                                "vendor selection commit failed",
                                HttpStatusCode.InternalServerError,
                            )
                            return@post
                        }
                        snapInvalidate()
                        val result = formattedString(
                            strings,
                            if (untame) "install.tame.result.reenabling" else "install.tame.result.taming",
                            "package" to pkg,
                        )
                        if (call.request.headers["Accept"]?.contains("application/json") == true) {
                            call.respondText(
                                "{" +
                                    "\"ok\":true,\"status\":\"started\",\"message\":" + jsonStr(result) + "," +
                                    "\"return_to\":" + jsonStr(returnTo) + "}",
                                ContentType.Application.Json,
                            )
                        } else {
                            call.respondText(
                                "<!doctype html><base href=\"/\"><meta charset=utf-8><meta http-equiv=refresh content='2;url=${esc(returnTo)}'>" +
                                    "<body style='font-family:system-ui;background:#111;color:#eee;padding:20px'>" +
                                    esc(result) + "</body>",
                                ContentType.Text.Html,
                            )
                        }
                    }
                    // The "Find a package…" picker pop-up content: an on-demand, grouped list of packages a
                    // non-expert might want to control — Recommended (profile) / Other apps / Using the most
                    // CPU. Lazy (only built when the dialog opens) and excludes what's already tamed (the card).
                    get("/tame/suggest") {
                        if (!admitActiveRead(call)) return@get
                        val strings = requestStrings(call)
                        PerfReader.touch()   // keep the CPU sampler warm so the "most CPU" group can populate
                        val groups = runCatching {
                            tame.suggestionGroups(tameProfileCandidates, config.tameVendorPackages.toSet(), PerfReader.topNames())
                        }.getOrDefault(emptyList())
                        val frag = if (groups.isEmpty())
                            """<p class="note">${esc(strings.get("install.tame.suggest.none_found"))}</p>"""
                        else groups.joinToString("\n") { g ->
                            val items = if (g.items.isEmpty())
                                """<p class="note" style="margin:0 0 4px;color:#666">${esc(strings.get("install.tame.suggest.none"))}</p>"""
                            else g.items.joinToString("\n") { tameRowHtml(it, strings = strings) }
                            """<h4 style="margin:14px 0 1px">${esc(localizedTameGroupTitle(g.title, strings))}</h4>""" +
                                """<p class="note" style="margin:0 0 4px">${esc(localizedTameGroupHint(g.hint, strings))}</p>$items"""
                        }
                        // One-click "Tame all recommended", shown only when there's an active recommended pick.
                        val hasRec = groups.any { g -> g.items.any { it.recommended && !it.blocked && !it.disabled && it.installed } }
                        val recBtn = if (hasRec)
                            """<form method="post" action="${localizedHref("api/v1/tame", strings)}" style="margin:0 0 12px"><input type="hidden" name="action" value="recommended"><button type="submit"${hardenedApprovalA11yAttrs(strings = strings)} style="background:#2e6b3f;border-color:#2e6b3f">✓ ${esc(strings.get("install.tame.suggest.all_recommended"))}</button> <span class="note" style="font-size:.8em">${esc(strings.get("install.tame.suggest.recommended_hint"))}</span></form>"""
                            else ""
                        call.respondText(recBtn + frag, ContentType.Text.Html)
                    }
                    get("/display") {
                        // The factory base is the `wm density` reset reference the sizing control restores;
                        // the framework's stable density stands in only where that read is unavailable.
                        val observation = withContext(Dispatchers.IO) {
                            DisplayGeometryReport.observe(appContext)?.let { framework ->
                                framework.copy(factoryBaseDpi = densityCache.get().base ?: framework.factoryBaseDpi)
                            }
                        }
                        if (observation == null) {
                            call.respondText("""{"error":"display-unavailable"}""", ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
                            return@get
                        }
                        val profiled = profile.displayGeometry(observation.physicalWidthPx, observation.physicalHeightPx)
                        call.respondText(
                            DisplayGeometryReport.json(observation, profiled, recommendedDensity).toString(),
                            ContentType.Application.Json,
                        )
                    }
                    post("/display/density") {
                        val strings = requestStrings(call)
                        val p = receiveBoundedFormParameters(call) ?: return@post
                        val action = p["action"]                          // "reset" | "rec" (buttons)
                        val d = p["density"]?.trim()?.toIntOrNull()       // custom density (Apply)
                        val f = p["font"]?.trim()?.toFloatOrNull()        // custom font scale (Apply)
                        if (!authorizeSensitive(
                                call,
                                SensitiveOperation.DISPLAY_CONFIGURATION,
                                exactHttpApprovalPayload(call, p.canonicalDigest()),
                                strings.get("install.display.approval"),
                            )
                        ) return@post
                        val ok = when (action) {
                            "reset" -> DensityController.allApplied(density.reset(), density.resetFontScale())
                            "rec" -> DensityController.allApplied(
                                recommendedDensity?.let { density.set(it) },
                                recommendedFontScale?.let { density.setFontScale(it) },
                            )
                            else -> {  // Apply: set whichever fields were provided
                                DensityController.allApplied(
                                    d?.let { density.set(it) },
                                    f?.let { density.setFontScale(it) },
                                )
                            }
                        }
                        // Prime the density cache with the KNOWN result so the redirected Install card shows it
                        // at once — reading `wm density` back immediately after a change can still return the
                        // pre-write override for a second or two (the change is async), which flashed a stale
                        // value on the page until a manual reload. Only the just-changed field could race, so
                        // we take the value we set (d / recommendedDensity / base) and only re-read the
                        // unchanged fields (which are stable).
                        val observedSizing = density.observeSizing()
                        val base = observedSizing.base
                        val postDpi = when (action) {
                            "reset" -> base
                            "rec" -> recommendedDensity ?: observedSizing.current
                            else -> d ?: observedSizing.current
                        }
                        val postFont = when (action) {
                            "reset" -> 1.0f
                            "rec" -> recommendedFontScale ?: observedSizing.fontScale
                            else -> f ?: observedSizing.fontScale
                        }
                        snapInvalidate()
                        if (ok) densityCache.set(DisplaySizingObservation(postDpi, base, postFont))
                        val message = if (ok) {
                            strings.get("install.display.result.applied")
                        } else {
                            strings.get("install.display.result.failed")
                        }
                        val returnTo = localizedHref("install#cfg-display", strings)
                        val responseStatus = if (ok) HttpStatusCode.OK else HttpStatusCode.InternalServerError
                        if (call.request.headers["Accept"]?.contains("application/json") == true) {
                            call.respondText(
                                "{" +
                                    "\"ok\":$ok,\"status\":\"${if (ok) "applied" else "apply-failed"}\"," +
                                    "\"message\":${jsonStr(message)},\"return_to\":${jsonStr(returnTo)}}",
                                ContentType.Application.Json,
                                responseStatus,
                            )
                        } else {
                            call.respondText(
                                "<!doctype html><base href=\"/\"><meta charset=utf-8>" +
                                    (if (ok) "<meta http-equiv=refresh content='1;url=${esc(returnTo)}'>" else "") +
                                    "<body style='font-family:system-ui;background:#111;color:#eee;padding:20px'>" +
                                    esc(message) + (if (ok) "…" else " <a href='${esc(returnTo)}' style='color:#9cf'>${esc(strings.get("install.display.return"))}</a>") + "</body>",
                                ContentType.Text.Html,
                                responseStatus,
                            )
                        }
                    }
                    // 1-click WebView DevTools: expose the dashboard's CDP socket to the LAN (root relay)
                    // so the user can chrome://inspect with no adb. See CdpRelay.
                    get("/inspect") {
                        val status = when {
                            CdpRelay.running -> "started"
                            config.hardenedSecurityEnabled -> "hardened-disabled"
                            else -> "off"
                        }
                        call.respondText(inspectJson(status), ContentType.Application.Json)
                    }
                    post("/inspect/start") {
                        if (rejectHardenedDevToolsRelay(call)) return@post
                        if (!authorizeSensitive(
                                call,
                                SensitiveOperation.DEVTOOLS_ENABLE,
                                exactHttpApprovalPayload(call, sha256Hex(ByteArray(0))),
                                "Expose this panel's WebView developer tools to the LAN",
                            )
                        ) return@post
                        val status = synchronized(inspectLock) {
                            if (stopping) "off" else CdpRelay.start(appContext)
                        }
                        call.respondText(inspectJson(status), ContentType.Application.Json)
                    }
                    post("/inspect/stop") {
                        synchronized(inspectLock) {
                            if (CdpRelay.running) CdpRelay.stop()
                            if (config.hardenedSecurityEnabled) AdbController(appContext, config).reassert()
                        }
                        call.respondText(inspectJson("off"), ContentType.Application.Json)
                    }
                }
            }
        }
        // Treat the bind as required startup, not a best-effort sidecar. A failure must close this
        // generation's admission and reach the service runtime owner, which records FAILED and prevents
        // a staged profile from being marked healthy without its management/control surface.
        try {
            startOwnedHttpServer(
                start = { server.start(wait = false) },
                stop = { server.stop(500, 1500) },
                closeIngress = pendingApks::close,
            )
            stopServer = { server.stop(500, 1500) }
            startHaAreaConvergence()
            Log.i(TAG, "HTTP listening on :${config.httpPort}")
        } catch (e: Exception) {
            // HTTP is part of the service's required control plane. Propagate a bind/start failure to the
            // runtime owner so this generation becomes FAILED and a staged profile cannot be marked healthy.
            stopping = true
            Log.e(TAG, "HTTP bind on :${config.httpPort} failed", e)
            throw e
        }
    }

    /**
     * Populate the dashboard's last-known observation after critical startup work has completed.
     * Binding the HTTP control plane itself remains free of privileged or hardware probes.
     * The caller supplies the existing IO scope so post-critical work can remain intentionally ordered.
     */
    internal fun prewarm() {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        var managementSucceeded = false
        var companionSucceeded = false
        runPrewarmPhases(
            isStopping = { stopping },
            management = {
                managementSucceeded = runCatching { snapCache.get() }
                    .onFailure { Log.w(TAG, "management snapshot prewarm failed", it) }
                    .isSuccess
            },
            companion = {
                companionSucceeded = runCatching {
                    val observed = companionServerCache.get()
                    observed.preferredUrl?.let { config.setHaBaseUrl(it) }
                    check(observed.probe != CompanionDb.Probe.FAILED) { "Companion servers table is unreadable" }
                }.onFailure { Log.w(TAG, "Companion server observation prewarm failed", it) }
                    .isSuccess
            },
        )
        if (managementSucceeded && companionSucceeded) {
            Log.i(TAG, "management prewarm completed in " +
                "${android.os.SystemClock.elapsedRealtime() - startedAt}ms")
        }
    }

    /** True when every directly owned HTTP resource proves terminal. Ktor request jobs are children of
     * the service scope and are proved separately by the service's terminal scope drain. */
    fun stop(): Boolean {
        stopping = true
        haAreaJob?.cancel()
        haAreaJob = null
        synchronized(haAreaWarmLock) {
            haAreaWarmJob?.cancel()
            haAreaWarmJob = null
        }
        haAreaWriteJob?.cancel()
        haAreaWriteJob = null
        return stopHttpOwners(
            closeOperationAdmission = clearStorageGate::close,
            closeUploadIngress = pendingApks::close,
            stopEngine = {
                stopServer?.invoke()
                stopServer = null
            },
            // Serialize against an admitted start: teardown either prevents it or waits and then kills it.
            stopRelay = {
                synchronized(inspectLock) {
                    val stopped = !CdpRelay.running || CdpRelay.stop()
                    if (stopped && config.hardenedSecurityEnabled) AdbController(appContext, config).reassert()
                    stopped
                }
            },
            drainTameMutations = { tameReconciliation.closeAndJoin(TAME_SHUTDOWN_MS) },
            drainRemoteControls = { remoteControls.closeAndJoin(REMOTE_CONTROL_SHUTDOWN_MS) },
            onIncomplete = { step, error ->
                if (error == null) Log.w(TAG, "$step cleanup did not complete")
                else Log.w(TAG, "$step cleanup failed", error)
            },
        )
    }

    // The panel's physical resolution as a CSS aspect-ratio (e.g. "750/1334") so the Screenshot card can
    // reserve the exact box and not reflow when the image arrives. Sane portrait fallback if unavailable.
    private fun screenAspectRatio(): String = try {
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        val dm = android.util.DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
        if (dm.widthPixels > 0 && dm.heightPixels > 0) "${dm.widthPixels}/${dm.heightPixels}" else "3/4"
    } catch (e: Throwable) { "3/4" }

    /** 308 for a legacy flat path — preserves method, body and query so pre-0.8.5 tooling keeps working. */
    private suspend fun legacy(call: ApplicationCall, new: String) {
        val q = call.request.uri.substringAfter('?', "")
        val loc = if (q.isEmpty()) new else "$new?$q"
        call.response.headers.append("Location", loc)
        call.respondText("moved-permanently: $loc\n", status = HttpStatusCode.PermanentRedirect)
    }

    /** Every pre-0.8.5 flat machine endpoint → its /api/v1 home. GET+POST both registered — 308
     *  preserves the method, so the right verb reaches the real handler either way. */
    private fun io.ktor.server.routing.Route.legacyRedirects() {
        val map = mapOf(
            "/perf" to "/api/v1/perf",
            "/action" to "/api/v1/action",
            "/diag" to "/api/v1/diag",
            "/sensortrace" to "/api/v1/sensortrace",
            "/screenshot.png" to "/api/v1/screenshot.png",
            "/openapi.json" to "/api/v1/openapi.json",
            "/proximity" to "/api/v1/proximity",
            "/proximity/capture" to "/api/v1/proximity/capture",
            "/proximity/threshold" to "/api/v1/proximity/threshold",
            "/proximity/sensitivity" to "/api/v1/proximity/sensitivity",
            "/proximity/reset" to "/api/v1/proximity/reset",
            "/proximity/teach" to "/api/v1/proximity/teach",
            "/proximity/test" to "/api/v1/proximity/test",
            "/proximity/relearn" to "/api/v1/proximity/relearn",
            "/config" to "/api/v1/config",
            "/tame" to "/api/v1/tame",
            "/tame/suggest" to "/api/v1/tame/suggest",
            "/density" to "/api/v1/display/density",
            "/inspect" to "/api/v1/inspect",
            "/inspect/start" to "/api/v1/inspect/start",
            "/inspect/stop" to "/api/v1/inspect/stop",
        )
        for ((old, new) in map) {
            get(old) { legacy(call, new) }
            post(old) { legacy(call, new) }
        }
    }

    // ---- live log stream (SSE) ----

    /** Tail a [LogCapture] to the client as Server-Sent Events. Backlog first (ring snapshot while
     *  capture is already running for the shipper / another viewer, else a one-shot `logcat -d`
     *  dump), then live lines. A per-connection drop-oldest channel means a stalled browser can
     *  never back-pressure the capture; a 15s `: ping` comment detects dead peers so the
     *  subscription (and with it the logcat subprocess) is released. */
    private suspend fun handleLogStream(call: ApplicationCall) {
        val cap = when (val src = call.request.queryParameters["source"] ?: "app") {
            "app" -> logApp
            "system" -> if (withContext(Dispatchers.IO) { Su.availableCachedIsolated() }) logSystem else {
                call.respondText("system log needs root\n", status = HttpStatusCode.ServiceUnavailable)
                return
            }
            "webview" -> if (webViewConsoleEnabled()) logWebView else {
                call.respondText(
                    "webview console needs log shipping configured\n",
                    status = HttpStatusCode.ServiceUnavailable,
                )
                return
            }
            else -> {
                call.respondText("unknown source '$src' (app|system|webview)\n", status = HttpStatusCode.BadRequest)
                return
            }
        }
        if (cap == null) {
            call.respondText("log viewer unavailable\n", status = HttpStatusCode.NotFound)
            return
        }
        val viewer = when (val admission = cap.admitViewer()) {
            is LogCapture.ViewerAdmission.Accepted -> admission.lease
            LogCapture.ViewerAdmission.CapacityExceeded -> {
                call.response.headers.append("Retry-After", "5")
                call.respondText("too many live log viewers\n", status = HttpStatusCode.TooManyRequests)
                return
            }
            LogCapture.ViewerAdmission.Unavailable -> {
                call.respondText("log viewer unavailable\n", status = HttpStatusCode.ServiceUnavailable)
                return
            }
        }
        try {
            // Backlog BEFORE subscribing: a few ms of lines can fall in the gap, which beats the visible
            // duplicates the opposite order produces (the dump overlaps the live stream's first lines).
            val backlog = withContext(Dispatchers.IO) { cap.initialBacklog() }
            val chan = Channel<String>(capacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST)
            val sub = cap.subscribe { chan.trySend(it) }
            try {
                call.response.headers.append("Cache-Control", "no-cache")
                call.respondTextWriter(ContentType.Text.EventStream) {
                    for (line in backlog) write("data: $line\n\n")
                    flush()
                    while (true) {
                        val line = withTimeoutOrNull(15_000) { chan.receive() }
                        write(if (line == null) ": ping\n\n" else "data: $line\n\n")
                        flush()
                    }
                }
            } finally {
                runCatching { sub.close() }
                chan.close()
            }
        } finally {
            runCatching { viewer.close() }
        }
    }

    /** Protect GET routes whose generation starts material work from opaque cross-origin browser loads. */
    private suspend fun admitActiveRead(
        call: ApplicationCall,
        allowLegacyNavigation: Boolean = false,
    ): Boolean {
        if (OriginGuard.activeReadAllowed(
                origin = call.request.headers["Origin"],
                referer = call.request.headers["Referer"],
                host = call.request.headers["Host"],
                fetchSite = call.request.headers["Sec-Fetch-Site"],
                accept = call.request.headers["Accept"],
                userAgent = call.request.headers["User-Agent"],
                allowLegacyNavigation = allowLegacyNavigation,
            )
        ) return true
        // Name what was actually wrong. The old text said "cross-origin" for every refusal, including
        // requests carrying no origin information at all — a misdiagnosis that sends the reader hunting
        // a CORS misconfiguration that does not exist.
        val site = call.request.headers["Sec-Fetch-Site"]?.trim()?.lowercase()
        val message = when {
            site == "cross-site" || site == "same-site" ->
                "refused: this panel does not serve active reads to another site."
            else ->
                "refused: this active read could not be verified as same-origin. Open the address " +
                    "directly, or use the link on the panel's Configure page."
        }
        call.respondText("$message\n", status = HttpStatusCode.Forbidden)
        return false
    }

    // ---- tabbed multi-page shell ----

    private fun hardenedApprovalA11yAttrs(
        conditional: Boolean = false,
        strings: AppStrings = catalogueLoader.strings(AppLocale.ENGLISH),
    ): String {
        val description = if (conditional) "hardened-approval-conditional-description" else "hardened-approval-description"
        val title = strings.get(
            if (conditional) "configure.hardened.setting_approval" else "configure.hardened.action_approval",
        )
        return """ aria-describedby="$description" title="${esc(title)}""""
    }

    private fun hardenedApprovalAttrs(
        conditional: Boolean = false,
        strings: AppStrings = catalogueLoader.strings(AppLocale.ENGLISH),
    ): String =
        """ data-hardened-approval${if (conditional) "=\"conditional\"" else ""}${hardenedApprovalA11yAttrs(conditional, strings)}"""

    private fun hardenedApprovalCardTitle(
        title: String,
        badge: String = "",
        conditional: Boolean = false,
        strings: AppStrings = catalogueLoader.strings(AppLocale.ENGLISH),
    ): String {
        val description = if (conditional) "hardened-approval-section-conditional-description" else "hardened-approval-section-description"
        val explanation = strings.get(
            if (conditional) "shell.hardened.section_conditional" else "shell.hardened.section",
        )
        val marker = if (conditional) "=\"conditional\"" else ""
        return """<h2 data-hardened-approval$marker aria-describedby="$description" title="${esc(explanation)}">$title$badge</h2>"""
    }

    private fun hardenedApprovalDescription(strings: AppStrings): String =
        """<span id="hardened-approval-description" class="sr-only">${esc(strings.get("configure.hardened.action_approval"))}</span>""" +
            """<span id="hardened-approval-conditional-description" class="sr-only">${esc(strings.get("configure.hardened.setting_approval"))}</span>""" +
            """<span id="hardened-approval-section-description" class="sr-only">${esc(strings.get("shell.hardened.section"))}</span>""" +
            """<span id="hardened-approval-section-conditional-description" class="sr-only">${esc(strings.get("shell.hardened.section_conditional"))}</span>"""

    private fun hardenedApprovalKey(top: Boolean = false, strings: AppStrings): String =
        """<p class="hardened-approval-key${if (top) " top" else ""}">${esc(strings.get("shell.hardened.key"))}</p>"""

    /** The shared tab bar; [active] highlights the current page. */
    private fun localizedHref(path: String, strings: AppStrings): String {
        if (strings.requestedLocale == AppLocale.ENGLISH) return path
        val fragmentAt = path.indexOf('#')
        val address = if (fragmentAt < 0) path else path.substring(0, fragmentAt)
        val fragment = if (fragmentAt < 0) "" else path.substring(fragmentAt)
        val separator = if ('?' in address) '&' else '?'
        return "$address${separator}lang=${esc(strings.requestedLocale)}$fragment"
    }

    /** Setup is the only server page whose browser code carries an explicit locale between journey
     * steps. Keep an explicit English override in its server-rendered links too, while naturally
     * negotiated English remains URL-clean everywhere. */
    private fun setupHref(path: String, strings: AppStrings, preserveExplicitEnglish: Boolean): String {
        if (!preserveExplicitEnglish || strings.requestedLocale != AppLocale.ENGLISH) {
            return localizedHref(path, strings)
        }
        val fragmentAt = path.indexOf('#')
        val address = if (fragmentAt < 0) path else path.substring(0, fragmentAt)
        val fragment = if (fragmentAt < 0) "" else path.substring(fragmentAt)
        val separator = if ('?' in address) '&' else '?'
        return "$address${separator}lang=${esc(AppLocale.ENGLISH)}$fragment"
    }

    private fun navBar(
        active: String,
        strings: AppStrings,
        preserveExplicitEnglish: Boolean = false,
        hiddenTabs: Set<String> = emptySet(),
    ): String {
        // Hiding is presentation for Panel Assistant's sidebar, never access control: the route still answers.
        fun tab(id: String, href: String, label: String): String = if (id in hiddenTabs) "" else
            """<a href="${setupHref(href, strings, preserveExplicitEnglish)}"${if (id == active) " class=\"active\"" else ""}>${esc(label)}</a>"""
        // The guided setup tab exists only while the journey is unfinished, then disappears — a healthy
        // panel's navigation is exactly what it was before the wizard existed. Placed first because on an
        // unfinished panel it IS the primary destination (the QR points at it).
        val setup = if (setupNeedsUser()) {
            tab("setup", "setup", strings.get("shell.nav.setup"))
        } else ""
        return "<div class=\"nav\">" +
            setup +
            tab("dashboard", "./", strings.get("shell.nav.dashboard")) +
            tab("configure", "configure", strings.get("shell.nav.configure")) +
            tab("profiles", "profiles", strings.get("shell.nav.profile")) +
            tab("entities", "entities", strings.get("shell.nav.entities")) +
            tab("install", "install", strings.get("shell.nav.install")) +
            // Keep the dormant /fleet route available to old bookmarks without presenting the
            // placeholder as a near-term product commitment.
            tab("logs", "logs", strings.get("shell.nav.logs")) +
            (if ("api" in hiddenTabs) "" else """<a href="${setupHref("api", strings, preserveExplicitEnglish)}">API</a>""") +
            "</div>"
    }

    private fun entitiesBody(strings: AppStrings): String = if (!config.dashboardEntityLearningEnabled || !effectiveDashboardIsBuiltin()) {
        val disabled = entityOwnedMarkup(
            strings.get("entities.disabled.body"),
            linkedMapOf(
                "{setting}" to "<b>${esc(strings.get("settings.dashboard_entity_learning.label"))}</b>",
                "{configure}" to "<b>${esc(strings.get("shell.nav.configure"))}</b>",
                "{dashboard}" to "<b>${esc(strings.get("configure.group.dashboard"))}</b>",
            ),
        )
        """<div class="cards"><div class="card"><h2>${esc(strings.get("entities.disabled.title"))} <small>· ${esc(strings.get("entities.disabled.badge"))}</small></h2>
        <p>$disabled</p></div></div>"""
    } else """
        <div class="cards entity-cards">
          <div class="card"><h2>${esc(strings.get("entities.filter.title"))}</h2>
            <div id="entity-status">${esc(strings.get("entities.filter.loading"))}</div>
            <div style="display:flex;gap:8px;flex-wrap:wrap;margin-top:12px">
              <button class="pbtn" id="entity-sync">${esc(strings.get("entities.filter.scan"))}</button>
              <button class="pbtn" id="entity-activate" disabled>${esc(strings.get("entities.filter.checking"))}</button>
              <button class="pbtn" id="entity-reset" type="button">${esc(strings.get("entities.filter.reset"))}</button>
              <a class="pbtn" href="api/v1/dashboard/entities/export">${esc(strings.get("entities.filter.export"))}</a>
            </div>
            <div id="entity-action-result" class="entity-action-result muted" role="status" aria-live="polite"></div>
            <fieldset class="entity-policy"><legend>${esc(strings.get("entities.policy.legend"))}</legend>
              <label><input type="checkbox" id="entity-auto-static"> ${esc(strings.get("entities.policy.static"))}</label>
              <label><input type="checkbox" id="entity-auto-runtime"> ${entityOwnedMarkup(strings.get("entities.policy.runtime"), linkedMapOf("hass.states" to "<code>hass.states</code>"))}</label>
              <p class="muted">${esc(strings.get("entities.policy.note"))}</p>
            </fieldset>
          </div>
          <div class="entity-search-row">
            <label class="sr-only" for="entity-search">${esc(strings.get("entities.search.label"))}</label>
            <input id="entity-search" type="search" autocomplete="off" placeholder="${esc(strings.get("entities.search.placeholder"))}" aria-describedby="entity-search-status">
            <div id="entity-search-status" class="entity-search-status muted" role="status" aria-live="polite"></div>
          </div>
          <div class="card entity-issues" id="entity-issues"><h2>${esc(strings.get("entities.issues.title"))}</h2>
            <div id="entity-issues-summary" class="muted" role="status" aria-live="polite">${esc(strings.get("entities.issues.checking"))}</div>
            <div id="entity-issues-list" class="entity-issues-list"></div>
            <section id="entity-dynamic" class="entity-dynamic" hidden>
              <h3>${esc(strings.get("entities.dynamic.title"))}</h3>
              <p class="muted">${entityOwnedMarkup(strings.get("entities.dynamic.body"), linkedMapOf("{{ ... }}" to "<code>{{ ... }}</code>", "{% ... %}" to "<code>{% ... %}</code>"))}</p>
              <div id="entity-dynamic-list" class="entity-dynamic-list"></div>
            </section>
            <button class="pbtn" id="entity-issues-rescan" type="button">${esc(strings.get("entities.issues.rescan"))}</button>
          </div>
          ${entityTableHtml("current", "entities.table.current", "subscribed", strings)}
          ${entityTableHtml("suggested", "entities.table.suggested", "candidate", strings)}
          ${entityTableHtml("review", "entities.table.review", "review", strings)}
        </div>
        <script src="assets/entities.js"></script>
    """.trimIndent()

    private fun entityTableHtml(
        id: String,
        keyPrefix: String,
        filter: String,
        strings: AppStrings,
    ): String {
        val keys = when (keyPrefix) {
            "entities.table.current" -> Triple(
                "entities.table.current.title",
                "entities.table.current.short",
                "entities.table.current.note",
            )
            "entities.table.suggested" -> Triple(
                "entities.table.suggested.title",
                "entities.table.suggested.short",
                "entities.table.suggested.note",
            )
            "entities.table.review" -> Triple(
                "entities.table.review.title",
                "entities.table.review.short",
                "entities.table.review.note",
            )
            else -> error("unknown Entities table: $keyPrefix")
        }
        return """
      <div class="card entity-list" data-filter="$filter" data-table="$id" data-short-key="${esc(keys.second)}"><h2>${esc(strings.get(keys.first))}</h2>
        <p class="muted">${esc(strings.get(keys.third))}</p>
        <div class="entity-bulk" style="display:flex;align-items:center;gap:8px;flex-wrap:wrap;margin-bottom:10px">
          <button class="pbtn" data-bulk="pinned">${esc(strings.get("entities.bulk.pin_selected"))}</button><button class="pbtn" data-bulk="auto">${esc(strings.get("entities.bulk.auto_selected"))}</button><button class="pbtn" data-bulk="forced_exclude">${esc(strings.get("entities.bulk.exclude_selected"))}</button>
          ${if (filter == "candidate") "<button class=\"pbtn\" data-all-candidates=\"true\">${esc(strings.get("entities.bulk.pin_all_suggested"))}</button>" else ""}<span class="muted entity-selected">${esc(strings.get("entities.selection.none"))}</span>
        </div>
        <div class="tablewrap"><table class="entity-table"><thead><tr><th class="col-select"><input type="checkbox" class="entity-select-page" aria-label="${esc(strings.get("entities.table.select_page"))}"></th><th class="col-entity"><button data-sort="entity_id">${esc(strings.get("entities.table.entity"))}</button></th><th class="col-access"><button data-sort="access_1h">${esc(strings.get("entities.table.accesses"))} <small>${esc(strings.get("entities.table.period_tooltip"))}</small></button></th><th class="col-rate"><button data-sort="rate_1h_bps">${esc(strings.get("entities.table.data_rate"))} <small>${esc(strings.get("entities.table.bytes_per_second"))} · ${esc(strings.get("entities.table.period_tooltip"))}</small></button></th><th class="col-reason"><button data-sort="reasons">${esc(strings.get("entities.table.reason"))}</button></th><th class="col-last"><button data-sort="last_access_at">${esc(strings.get("entities.table.last_access"))}</button></th><th class="col-override"><button data-sort="override">${esc(strings.get("entities.table.override"))}</button></th></tr></thead><tbody></tbody></table></div>
        <div style="display:flex;align-items:center;gap:8px;flex-wrap:wrap;margin-top:10px">
          <button class="pbtn entity-prev">${esc(strings.get("entities.pagination.previous"))}</button><button class="pbtn entity-next">${esc(strings.get("entities.pagination.next"))}</button><span class="muted entity-msg">${esc(strings.get("entities.pagination.loading"))}</span>
        </div>
      </div>
    """.trimIndent()
    }

    /** Insert only server-owned emphasis/code elements while escaping every translated byte around them. */
    private fun entityOwnedMarkup(text: String, replacements: Map<String, String>): String = buildString {
        var offset = 0
        while (offset < text.length) {
            val next = replacements.keys
                .mapNotNull { marker -> text.indexOf(marker, offset).takeIf { it >= 0 }?.let { it to marker } }
                .minByOrNull { it.first }
            if (next == null) {
                append(esc(text.substring(offset)))
                break
            }
            append(esc(text.substring(offset, next.first)))
            append(replacements.getValue(next.second))
            offset = next.first + next.second.length
        }
    }

    /** The GitHub-repository icon link shown in the header of every :8888 surface. */
    private fun ghLink(strings: AppStrings = catalogueLoader.strings(AppLocale.ENGLISH)): String =
        """<a class="gh" href="$REPO_URL" target="_blank" rel="noopener" title="${esc(strings.get("shell.github.title"))}" aria-label="GitHub"><svg viewBox="0 0 24 24"><path d="$GH_ICON"/></svg></a>"""

    /**
     * The one page shell shared by every :8888 surface. The tabbed pages (page()) and the dashboard
     * (infoHtml()) render byte-identical chrome — doctype, theme-pin script, header, nav bar and the
     * buildwatch reload bar — through this single builder; only the per-surface deltas are passed in:
     * the body data-attributes, the header right-hand controls, the body markup itself, and any extra
     * scripts loaded ahead of the shared switcher/buildwatch pair.
     */
    private fun pageShell(
        active: String,
        sectionTitle: String? = null,
        bodyAttrs: String,
        rightControls: String,
        body: String,
        extraScripts: String = "",
        strings: AppStrings = catalogueLoader.strings(AppLocale.ENGLISH),
        translationPrefixes: Set<String> = setOf("shell."),
        preserveExplicitEnglish: Boolean = false,
        embed: EmbedMode? = null,
    ): String {
        // Capture panel identity once so title, switcher metadata and visible name cannot disagree if a
        // concurrent config save replaces the live identity while this response is being rendered.
        val rawPanelId = config.panelId
        val rawFriendlyName = config.friendlyName
        val panelId = esc(rawPanelId)
        val friendlyName = esc(rawFriendlyName)
        val title = esc(panelBrowserTitle(rawFriendlyName, sectionTitle))
        // Embedded in Panel Assistant's sidebar, Home Assistant owns the top menu: the header and the mDNS
        // switcher (whose links leave the proxy) are omitted, and the tab bar and everything below it stay.
        // Only validated enum values reach the markup.
        val themeAttr = embed?.theme?.let { """ data-theme="$it"""" }.orEmpty()
        val embedAttr = if (embed != null) " data-embedded" else ""
        // Embedded keeps only the panel identity install.js names downloads from, as escaped attributes.
        val header = if (embed != null) """<span id="pswitch" hidden data-self-id="$panelId" data-self-name="$friendlyName"></span>""" else """<div class="hdr"><button id="navburger" class="navburger pbtn" aria-label="${esc(strings.get("shell.menu.label"))}">☰</button><h1><img src="icon.svg" class="logo" alt=""><span class="brand">ha-paneld</span> <small id="pswitch" data-self-id="$panelId" data-self-name="$friendlyName"><span class="sep">·</span>$friendlyName</small></h1>
 <span style="display:flex;gap:10px;align-items:center">$rightControls</span></div>
"""
        val switcher = if (embed != null) "" else """<!-- Load switcher.js immediately after the header it measures so responsive collapse finishes before page
     content is parsed and publishes the final header height without causing a post-paint card-wall shift. -->
<script src="assets/switcher.js"></script>
"""
        return """<!doctype html><html lang="${esc(strings.requestedLocale)}"$themeAttr><head><base href="/"><meta charset="utf-8">
<script>/* ?theme=light|dark pins the UI theme for testing (else the browser preference rules) */
(function(){var m=location.search.match(/[?&]theme=(dark|light)\b/);if(m)document.documentElement.setAttribute("data-theme",m[1])})();</script>
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>$title</title>
<link rel="icon" type="image/svg+xml" href="favicon.svg">
<link rel="stylesheet" href="info.css">
<script id="ha-i18n" type="application/json">${browserI18nPayload(strings, translationPrefixes)}</script>
<script src="assets/i18n.js"></script></head><body $bodyAttrs$embedAttr><div class="wrap">
<div class="topbar">$header${navBar(active, strings, preserveExplicitEnglish, embed?.hiddenTabs.orEmpty())}</div>
$switcher<div id="halifebar" class="setup" style="display:none"></div>
<div id="hanetbar" class="setup" style="display:none"></div>
<div id="verbar" class="setup" style="display:none">⟳ ${esc(strings.get("shell.new_version.installed"))} — <a href="#" onclick="location.reload();return false">${esc(strings.get("shell.action.reload"))}</a> ${esc(strings.get("shell.new_version.refresh_suffix"))}</div>
$body
$extraScripts<script src="assets/power-safety.js"></script>
<script src="assets/buildwatch.js"></script>
</div></body></html>"""
    }

    /** Shared page shell (header + tab bar + body) for the non-dashboard tabs. */
    private fun page(
        active: String,
        title: String,
        body: String,
        strings: AppStrings = catalogueLoader.strings(AppLocale.ENGLISH),
        embed: EmbedMode? = null,
    ): String {
        val haLink = if (config.haLinkUrl.isNotBlank())
            """<a class="pbtn" href="${esc(config.haLinkUrl)}" target="_blank" rel="noopener">${esc(strings.get("shell.open_in_ha"))}</a>""" else ""
        val approvalKey = if (active in setOf("configure", "install")) {
            hardenedApprovalKey(top = active == "install", strings = strings)
        } else {
            ""
        }
        val approvalKeyBefore = approvalKey.takeIf { active == "install" }.orEmpty()
        val approvalKeyAfter = approvalKey.takeIf { active != "install" }.orEmpty()
        // While setup is unfinished, Configure carries a `commissioning` body class: a first-time user on
        // the full settings wall may not know a Save button exists at all, so an unsaved change there gets
        // a throb (see info.css). Pure function of journey state — no dismissal memory, so it can never
        // stick on, and a finished panel's Configure is byte-identical to before the wizard existed.
        // Urgency treatment only while a person actually owes an action; a configured panel whose render proof
        // is merely being re-earned after a restart must not get a throbbing Save button.
        val commissioning = active == "configure" && setupNeedsUser()
        return pageShell(
            active = active,
            sectionTitle = title,
            bodyAttrs = (if (commissioning) """class="commissioning" """ else "") +
                """data-build="${buildToken()}" data-cfg="${renderConfigConcurrencyHash()}"""",
            rightControls = "$haLink${ghLink(strings)}",
            body = """${hardenedApprovalDescription(strings)}
$approvalKeyBefore
$body
$approvalKeyAfter""",
            strings = strings,
            translationPrefixes = setOf("shell.", "$active.", "runtime."),
            embed = embed,
        )
    }

    /**
     * The guided setup page — the surface the panel's QR code points at, and the primary way a new panel
     * is commissioned.
     *
     * A separate route rather than a mode of Configure. Configure is a schema-driven wall of every setting
     * with one save-everything bar, which is the right tool for an owner changing one thing and the wrong
     * one for somebody who has never seen this product: nothing there says which four fields matter, in
     * what order, or that a save is required at all. It is also pinned by contract tests that assert its
     * source text, so folding a wizard into it would put unrelated risk on the page every existing user
     * relies on.
     *
     * The markup here is only a frame. Steps are rendered by setup.js from GET /api/v1/setup, so the panel
     * and the browser read the same authority and cannot disagree about what comes next.
     */
    private fun setupBody(strings: AppStrings, preserveExplicitEnglish: Boolean, embedded: Boolean = false): String = """
<div class="wiz" id="wiz">
  <ol class="wiz-dots" id="wiz-dots" aria-label="${esc(strings.get("setup.frame.progress_label"))}"></ol>
  <div id="wiz-step" class="wiz-step" role="region" aria-live="polite" aria-atomic="false">
    <p class="muted">${esc(strings.get("setup.frame.loading"))}</p>
  </div>
  <p class="wiz-escape"><a href="${setupHref("configure", strings, preserveExplicitEnglish)}"${if (embedded) "" else """ onclick="document.cookie='wiz_escape=1;path=/;max-age=3600'""""}>${esc(strings.get("setup.frame.skip_exit"))}</a></p>
</div>
<script src="assets/setup.js"></script>"""

    /** Configure tab — schema-driven, save-together settings only. */
    private fun configureBody(strings: AppStrings): String {
        val proximityLearningEnabled = sensors.hasProximity()
        val proximityMount = if (proximityLearningEnabled) """<div id="proximity-learning-mount" hidden></div>""" else ""
        val proximityScript = if (proximityLearningEnabled) """<script src="assets/proximity-learning.js"></script>""" else ""
        val setup = configureSetupBanners(strings)
        return """
<!-- Basic/Advanced tab bar hidden until every setting is assigned a Basic/Advanced tier; with it hidden
     the form shows ALL settings (configure.js defaults `advanced=true`), so nothing is lost. The tier
     machinery (SettingSpec.tier + cfgTab) stays in place — restore the bar once tiers are curated. -->
<div class="cfg-tabs" style="display:none"><button id="tab-basic" onclick="cfgTab(false)">${esc(strings.get("configure.tab.basic"))}</button><button id="tab-adv" class="on" onclick="cfgTab(true)">${esc(strings.get("configure.tab.advanced"))}</button></div>
$setup
<div id="cfg-status" class="muted" style="margin-bottom:10px">${esc(strings.get("configure.status.loading"))}</div>
<div id="cfg-all-cards">
<div id="cfg-groups" class="cards" data-card-size-page="configure" data-card-size-epoch="1" data-card-size-restore="1" data-card-size-proximity="${if (proximityLearningEnabled) "1" else "0"}"></div>
$proximityMount</div>
<div id="savebar" class="savebar" role="region" aria-label="${esc(strings.get("configure.unsaved.label"))}" hidden><button id="savebtn" type="button" disabled onclick="cfgSave()">${esc(strings.get("configure.action.save"))}</button><span id="cfg-msg" class="muted" role="status" aria-live="polite" aria-atomic="true"></span></div>
<script src="assets/card-size-memory.js"></script>
<script src="assets/card-column-alignment.js"></script>
<script src="assets/configure.js"></script>
$proximityScript"""
    }

    private fun configureSetupBanners(strings: AppStrings): String {
        val management = snapStaleOk()
        val power = localizedPowerSafetyBanner(
            powerSafetyAdvisory(management.privilege),
            inlineRepair = true,
            strings = strings,
        )
        // Someone landing on the full settings wall mid-commissioning (an old bookmark, the QR from a
        // build that pointed here) should learn the guided path exists — once setup completes this line
        // vanishes with the rest of the wizard surface.
        val resume = if (setupNeedsUser()) {
            """<div class="setup info">${esc(strings.get("configure.setup.question"))} <a href="${localizedHref("setup", strings)}"><b>${esc(strings.get("configure.setup.link"))}</b></a> ${esc(strings.get("configure.setup.explanation"))}</div>"""
        } else ""
        // MQTT verification runs asynchronously after the save returns, and the Configure tab is where the
        // user actually is while it happens — but it showed nothing, so a save that was still being checked
        // looked like a save that had done nothing. SetupBanner already derives this state and is already
        // rendered on the dashboard; surfacing it here too costs nothing and keeps one authority.
        val mqtt = management.facts["MQTT"] ?: "disabled"
        SetupBanner.progress(mqtt, config.mqttBroker.isNotBlank(), dashboardSetupStepPending(), mqttState())?.let { progress ->
            return power + resume + """<div class="setup">⟳ ${esc(localizedSetupProgress(progress, strings))}</div>"""
        }
        if (haSignInNeededForEffectiveDashboard()) {
            return power + resume + """<div class="setup">🏠 <b>${esc(strings.get("configure.setup.ha_signin.title"))}</b> ${esc(strings.get("configure.setup.ha_signin.body"))}</div>"""
        }
        val noRenderer = healthFindings(healthInputs(), "", emptyList()).any { it.kind == HealthAudit.Kind.NO_RENDERER }
        // Only a panel past setup runs a filtered dashboard, so only this path can carry the strategy note.
        if (!noRenderer) return power + resume + strategySelectorAllowedBanner(strings)
        return power + resume + """<div class="setup">ℹ <b>${esc(strings.get("configure.setup.renderer.title"))}</b> ${esc(strings.get("configure.setup.renderer.body"))} <small>${esc(strings.get("configure.setup.renderer.note"))}</small></div>"""
    }

    // Issue #133 follow-up. With a strategy dashboard's check allowed, cards for entities outside the
    // subscription never appear and nothing on the panel can list them, so say so where settings are
    // changed and send the reader to the Entities page, which explains what to pin. A failed read of the
    // entity store must not take the Configure page down with it.
    private fun strategySelectorAllowedBanner(strings: AppStrings): String =
        if (!runCatching { entityLearning.strategySelectorAllowed() }.getOrDefault(false)) "" else
            """<div class="setup info">ℹ <b>${esc(strings.get("configure.setup.strategy_allowed.title"))}</b> ${esc(strings.get("configure.setup.strategy_allowed.body"))} <a href="${localizedHref("entities", strings)}">${esc(strings.get("configure.setup.strategy_allowed.link"))}</a>.</div>"""

    /** Runtime profile authoring. All content is hydrated through the guarded /api/v1/profile routes. */
    private fun profilesBody(strings: AppStrings): String = """
<link rel="stylesheet" href="assets/profiles.css">
<main class="profile-page">
  <div class="profile-toolbar" aria-label="${esc(strings.get("profiles.toolbar.actions_label"))}">
    <div class="profile-pickers">
      <label for="profile-select" class="muted">${esc(strings.get("profiles.toolbar.revision"))}</label>
      <select id="profile-select" aria-label="${esc(strings.get("profiles.toolbar.revision_label"))}"><option>${esc(strings.get("profiles.status.loading_catalog"))}</option></select>
      <label class="profile-revisions muted" for="profile-revisions"><input id="profile-revisions" type="checkbox">${esc(strings.get("profiles.toolbar.show_superseded"))}</label>
    </div>
    <div class="profile-actions">
      <div class="profile-action-group" aria-label="${esc(strings.get("profiles.toolbar.editing_label"))}">
        <button class="pbtn" id="profile-new" type="button">${esc(strings.get("profiles.action.new"))}</button>
        <button class="pbtn" id="profile-edit" type="button" disabled>${esc(strings.get("profiles.action.edit"))}</button>
        <button class="pbtn" id="profile-fork" type="button" disabled>${esc(strings.get("profiles.action.fork"))}</button>
        <label class="pbtn" for="profile-import">${esc(strings.get("profiles.action.import"))}<input id="profile-import" type="file" accept=".yaml,.yml,application/yaml,text/yaml" hidden></label>
        <button class="pbtn" id="profile-export" type="button">${esc(strings.get("profiles.action.export"))}</button>
      </div>
      <span class="profile-action-break" aria-hidden="true"></span>
      <div class="profile-action-group" aria-label="${esc(strings.get("profiles.toolbar.review_label"))}">
        <button class="pbtn primary" id="profile-validate" type="button" disabled>${esc(strings.get("profiles.action.validate_yaml"))}</button>
        <button class="pbtn" id="profile-compare" type="button" disabled>${esc(strings.get("profiles.action.compare"))}</button>
      </div>
      <div class="profile-action-group" aria-label="${esc(strings.get("profiles.toolbar.activation_label"))}">
        <button class="pbtn primary" id="savebtn" type="button" disabled>${esc(strings.get("profiles.action.save_revision"))}</button>
        <button class="pbtn primary" id="profile-activate" type="button"${hardenedApprovalAttrs(strings = strings)} disabled>${esc(strings.get("profiles.action.activate"))}</button>
        <button class="pbtn" id="profile-auto" type="button"${hardenedApprovalAttrs(strings = strings)} disabled>${esc(strings.get("profiles.action.use_automatic"))}</button>
        <button class="pbtn" id="profile-rollback" type="button"${hardenedApprovalAttrs(strings = strings)} disabled>${esc(strings.get("profiles.action.rollback"))}</button>
        <button class="pbtn danger" id="profile-delete" type="button" disabled>${esc(strings.get("profiles.action.delete"))}</button>
      </div>
    </div>
  </div>
  <div id="profile-badges" class="profile-badges" aria-label="${esc(strings.get("profiles.state.label"))}"></div>
  <nav id="profile-links" class="profile-links" aria-label="${esc(strings.get("profiles.references.label"))}" hidden></nav>
  <div id="profile-status" class="profile-status" role="status" aria-live="polite">${esc(strings.get("profiles.status.loading_catalog"))}</div>
  <div class="profile-workspace">
    <section class="profile-editor-pane" aria-labelledby="profile-editor-title">
      <div class="profile-editor-head"><h2 id="profile-editor-title">${esc(strings.get("profiles.editor.title"))}</h2><span id="profile-editor-meta" class="profile-editor-meta"></span></div>
      <div id="profile-editor"></div>
    </section>
    <aside class="profile-inspector" aria-labelledby="profile-inspector-title">
      <div class="profile-inspector-head"><h2 id="profile-inspector-title">${esc(strings.get("profiles.inspector.title"))}</h2></div>
      <div class="profile-inspector-body">
        <section><h3>${esc(strings.get("profiles.section.catalog_runtime"))}</h3><div id="profile-catalog-issues" class="profile-issues"></div></section>
        <section><h3>${esc(strings.get("profiles.section.validation"))}</h3><div id="profile-issues" class="profile-issues"></div></section>
        <div class="profile-guidance" id="profile-shizuku-guidance" hidden>
          <p><b>${esc(strings.get("profiles.shizuku.title"))}</b></p>
          <p>${esc(strings.get("profiles.shizuku.body"))}</p>
          <p><a href="$SHIZUKU_GUIDE_DOC" target="_blank" rel="noopener">${esc(strings.get("profiles.shizuku.guide"))}</a></p>
        </div>
        <section><h3>${esc(strings.get("profiles.section.compared_active"))}</h3><div id="profile-diff" class="profile-diff"></div></section>
        <section><h3>${esc(strings.get("profiles.section.observed"))}</h3><p class="profile-report-note">${esc(strings.get("profiles.observed.note"))}</p><div id="profile-report" class="profile-report"></div></section>
        <div class="profile-draft" id="profile-generic-draft" hidden>
          <p><b>${esc(strings.get("profiles.generic.title"))}</b> ${esc(strings.get("profiles.generic.body"))}</p>
          <p><button class="pbtn" id="profile-draft" type="button">${esc(strings.get("profiles.action.generate_draft"))}</button> <button class="pbtn" id="profile-use-draft" type="button" hidden>${esc(strings.get("profiles.action.copy_draft"))}</button></p>
        </div>
      </div>
    </aside>
  </div>
</main>
<div id="profile-modal" class="profile-modal" role="dialog" aria-modal="true" aria-labelledby="profile-modal-title" hidden>
  <div class="profile-modal-card"><h2 id="profile-modal-title">${esc(strings.get("profiles.modal.default_title"))}</h2><pre id="profile-modal-detail"></pre>
    <div class="profile-modal-actions"><button class="pbtn" id="profile-modal-cancel" type="button">${esc(strings.get("profiles.action.cancel"))}</button><button class="pbtn primary" id="profile-modal-confirm" type="button">${esc(strings.get("profiles.action.confirm"))}</button></div>
  </div>
</div>
<script src="assets/vendor/profile-editor/codemirror.js"></script>
<script src="assets/profiles.js"></script>"""

    /** Request-scoped snapshot of the two health inputs several render surfaces consult — the real WebView
     *  engine status and whether any dashboard renderer is present. Captured ONCE per render so the banner,
     *  facts card and diagnostics rows on one page can't disagree about the WebView. Benign normalization of
     *  a within-render race (the probes are cached + stable across a render-millisecond; making the reads
     *  consistent can never surface a warning that a fresh read wouldn't have). */
    private class HealthInputs(
        val webView: PanelInfo.WebViewStatus,
        val hasRenderer: Boolean,
        val brokerConfigured: Boolean,
    )

    /**
     * Whether the system WebView is too old, resolved once per process.
     *
     * `GET /api/v1/setup` is polled every two seconds during setup, and reading the true engine version can
     * load the WebView provider to get its user agent — far too expensive to repeat on a poll. Caching is
     * exactly right rather than merely cheap: a WebView swap restarts this process (see `autoUpdateWebView`),
     * so the value cannot change underneath the cache, and the answer after a successful update is read by
     * the new process. Routed through [healthInputs] so the probe keeps its single call site, which is the
     * discipline HealthWarningAuthoritySourceTest exists to hold — surfaces that probe independently drift.
     */
    private val webViewTooOldOnce: Boolean by lazy { healthInputs().webView.tooOld }

    private fun healthInputs(): HealthInputs = HealthInputs(
        PanelInfo.webViewStatus(appContext),
        PanelInfo.dashboardRenderers(appContext, config.dashboardPackage, config.haUrl).isNotEmpty(),
        config.mqttBroker.isNotBlank(),
    )

    /** HealthAudit findings for a render surface. The shared (WebView-too-old, no-renderer) inputs come from
     *  the request snapshot; [webViewDisplay] (the version string to show) and [updates] stay per-surface —
     *  the Install tab passes no updates, GET /api/v1/status the unfiltered list, and the dashboard banner
     *  the ignore-filtered list. */
    /** The schema-version detail to warn about when a downgrade reset config to defaults (the last
     *  reconcile was PRESERVED_FRESH), else null. Stable after boot — the reconcile runs once at store
     *  construction — so the warning clears only on the next start at the current schema. */
    private fun schemaRollbackVersions(): Pair<Int, Int>? {
        // Suppressed when the config vault refilled the fresh store: this warning exists to tell an owner
        // their settings may have reset and to check them, and once they have been recovered that is both
        // untrue and actionless. A warning demanding no action teaches people to ignore warnings. The
        // event itself remains visible in diagnostics.
        if (EntityCatalogStore.lastConfigRestore != null) return null
        return EntityCatalogStore.lastSchemaReconcile
            ?.takeIf { it.action == SchemaReconcileAction.PRESERVED_FRESH }
            ?.let { it.fromVersion to it.toVersion }
    }

    private fun schemaRollbackDetail(): String? = schemaRollbackVersions()
        ?.let { (from, to) -> "schema $from → $to" }

    private fun healthFindings(
        h: HealthInputs,
        webViewDisplay: String,
        updates: List<UpdateChecker.UpdateInfo>,
    ): List<HealthAudit.Finding> = HealthAudit.evaluate(
        webViewTooOld = h.webView.tooOld,
        webViewDisplay = webViewDisplay,
        hasRenderer = h.hasRenderer,
        brokerConfigured = h.brokerConfigured,
        updates = updates,
        schemaRolledBack = schemaRollbackDetail() != null,
        schemaRollbackDetail = schemaRollbackDetail() ?: "",
    )

    /** Install tab — software-management hub: setup warnings, managed component versions, radio firmware,
     *  on-demand health audit, and config backup. (The Capabilities card lives on the Dashboard.) */
    private fun installBody(strings: AppStrings): String {
        val management = snapStaleOk()
        val companion = companionServersStaleOk()
        // Engine-aware WebView age check (a Cromite swap reports the stale OEM package version).
        val h = healthInputs()
        val wv = h.webView
        val root = management.privilege.rootControlReady
        val installer = management.privilege.typedShellControlReady
        val su = management.privilege.directSuReady
        val displaySizing = densityCache.peek() ?: DisplaySizingObservation(
            current = management.densityCur,
            base = management.densityBase,
            fontScale = management.fontScale,
        )
        val companionHelper = companionHelperCache.get()
        // Same finding set as the dashboard banner (HealthAudit). Update findings are surfaced by the
        // Managed-components card below, so the top warnings show only the render-blocking states.
        val problems = healthFindings(h, wv.display, emptyList())
        // Auto-heal offer: if the profile ships a known-good WebView and we have root/daemon to install it,
        // the too-old warning gets a one-tap "Update WebView now" button (POST /api/v1/webview/heal).
        val canHeal = wv.tooOld && profile.recommendedWebView != null && root
        // A missing dashboard app can be self-healed by installing the minimal HA Companion over root — a
        // Play-managed full Companion would already count as a renderer, so NO_RENDERER + root ⇒ safe.
        val canInstallCompanion = installer
        // Two warnings not modelled by HealthAudit (crash-looping dashboard, Companion blank internal_url)
        // — shared with the dashboard banner. Here (Install tab, install.js loaded) they get inline buttons.
        val powerAdvisory = powerSafetyAdvisory(management.privilege)
        val extra = localizedPowerSafetyBanner(
            powerAdvisory,
            inlineRepair = true,
            strings = strings,
        ) +
            adHocWarnings(management, companion, inlineRepair = true, strings = strings)
        val warnings = extra + problems.joinToString("") { installWarning(it, canHeal, canInstallCompanion, strings) }
        val allGood = if (h.brokerConfigured && problems.isEmpty() && extra.isEmpty() && !powerAdvisory.assessment.warning) """<div class="card" data-layout-key="ready"><p class="note">✓ ${esc(strings.get("install.ready"))}</p></div>""" else ""
        return """$warnings
<div class="cards" id="install-cards" data-card-size-page="install" data-card-size-epoch="1" data-card-size-restore="1">
${componentsCardHtml(wv, root, installer, strings)}
${apkCardHtml(root, strings)}
${uninstallCardHtml(su, strings)}
<div class="card" id="radiocard" data-layout-key="radio-firmware" style="display:none"><h2>${esc(strings.get("install.radio.title"))}</h2>
<table><tr><th>${esc(strings.get("install.radio.efr32"))}</th><td id="radio-status">…</td></tr>
<tr><th>${esc(strings.get("install.radio.gateway_health"))}</th><td id="radio-health">…</td></tr></table>
<p class="note">${esc(strings.get("install.radio.note_prefix"))} <a href="${localizedHref("configure#cfg-zigbee_join", strings)}">${esc(strings.get("install.radio.configure_join"))}</a>. <span class="muted">${esc(strings.get("install.radio.thread_planned"))}</span></p></div>
<div class="card" data-layout-key="health-audit"><h2>${esc(strings.get("install.audit.title"))}</h2>
<p class="note">${esc(strings.get("install.audit.description"))}</p>
<button class="pbtn" onclick="healthAudit(this)">${esc(strings.get("install.audit.run"))}</button>
<div id="audit-out" style="margin-top:10px"></div>
<p class="note"><a href="api/v1/diag" target="_blank" style="color:#9cf">⭳ ${esc(strings.get("install.audit.diagnostics"))}</a> — ${esc(strings.get("install.audit.diagnostics_help"))}</p></div>
${tameCardHtml(root, strings)}
${displayCardHtml(management.privilege.typedShellControlReady, displaySizing, strings)}
${backupCardHtml(companionHelper, CompanionInstaller.installedPkg(appContext) != null, strings)}
$allGood</div>
<script src="assets/card-size-memory.js"></script>
<script src="assets/card-column-alignment.js"></script>
<script src="assets/install.js"></script>"""
    }

    /** One top-of-tab warning for a render-blocking finding (WebView old / no dashboard app). WebView gets
     *  the inline "Update WebView now" heal button when [canHeal]; a missing renderer gets a one-tap
     *  "Install HA Companion" button when [canInstallCompanion]. */
    private fun installWarning(
        f: HealthAudit.Finding,
        canHeal: Boolean,
        canInstallCompanion: Boolean,
        strings: AppStrings,
    ): String = when (f.kind) {
        HealthAudit.Kind.WEBVIEW_OLD ->
            """<div class="setup crit">⚠ <b>${esc(strings.get("install.warning.webview_old.title"))}</b> (${esc(f.detail)}) — ${esc(strings.get("install.warning.webview_old.body"))} <a href="$WEBVIEW_DOC" target="_blank" rel="noopener">${esc(strings.get("install.warning.webview_old.help"))}</a> (${esc(formattedString(strings, "install.warning.webview_old.target", "version" to PanelHealth.MIN_CHROMIUM.toString()))}).""" +
                (if (canHeal) """<div style="margin-top:10px"><button class="pbtn"${hardenedApprovalAttrs(strings = strings)} onclick="healWebView(this)">⬇ ${esc(strings.get("install.warning.webview_old.update"))}</button> <span id="wv-heal" class="muted"></span></div>""" else "") +
                """</div>"""
        HealthAudit.Kind.NO_RENDERER ->
            """<div class="setup">ℹ <b>${esc(strings.get("install.warning.no_renderer.title"))}</b> ${esc(strings.get("install.warning.no_renderer.prefix"))} <a href="${localizedHref("configure", strings)}">${esc(strings.get("shell.nav.configure"))}</a>${esc(strings.get("install.warning.no_renderer.suffix"))}""" +
                (if (canInstallCompanion) """<div style="margin-top:10px"><button class="pbtn"${hardenedApprovalAttrs(strings = strings)} onclick="installComp('companion','update',this)">⬇ ${esc(strings.get("install.warning.no_renderer.install_companion"))}</button> <span class="muted">${esc(strings.get("install.warning.no_renderer.progress"))}</span></div>""" else "") +
                """</div>"""
        HealthAudit.Kind.UPDATE -> "" // shown in the Managed-components card, not as a top warning
        HealthAudit.Kind.SCHEMA_ROLLED_BACK ->
            """<div class="setup crit">⚠ <b>${esc(strings.get("install.warning.schema_rollback.title"))}</b> — ${esc(strings.get("install.warning.schema_rollback.prefix"))} <a href="${localizedHref("configure", strings)}">${esc(strings.get("shell.nav.configure"))}</a>${esc(strings.get("install.warning.schema_rollback.suffix"))}</div>"""
    }

    /** Managed-components card. ha-paneld + HA Companion get a channel + version picker (default channel
     *  from Configure; up to 10 recent versions hydrated by install.js) with a release-notes link and an
     *  Install-selected-version button. The System WebView is a single known-good build (heal/up-to-date).
     *  All actions POST /api/v1/install/component and poll /api/v1/install/status. */
    private fun componentsCardHtml(
        wv: PanelInfo.WebViewStatus,
        root: Boolean,
        installer: Boolean,
        strings: AppStrings,
    ): String {
        val paneldCur = Config.VERSION
        val compPkg = CompanionInstaller.installedPkg(appContext)
        val compFull = compPkg == CompanionInstaller.FULL_PKG
        val compCur = compPkg?.let { AppInstaller.installedVersion(appContext, it) }?.takeIf { it.isNotBlank() }
        val rec = profile.recommendedWebView

        val paneldRow = pickerRow("paneld", "ha-paneld", paneldCur, config.updateChannel, installer, strings)
        // A Play-managed FULL Companion must never be touched by ha-paneld — show it read-only.
        val compRow = if (compFull)
            simpleRow("HA Companion", compCur, """<span class="muted">${esc(strings.get("install.components.play_managed"))}</span>""", strings)
        else pickerRow("companion", "HA Companion", compCur, config.companionUpdateChannel, installer, strings)
        val wvAction = when {
            wv.playManaged -> """<span class="muted">${esc(strings.get("install.components.google_play_managed"))}</span>"""
            wv.tooOld && rec != null && root -> """<button class="pbtn"${hardenedApprovalA11yAttrs(strings = strings)} onclick="installComp('webview','update',this)">⬇ ${esc(strings.get("install.components.update_webview"))}</button>"""
            wv.tooOld && rec != null -> """<span class="muted">${esc(strings.get("install.components.root_update_required"))}</span>"""
            wv.tooOld -> """<span class="muted">${esc(strings.get("install.components.no_known_build"))}</span>"""
            else -> """<span class="muted">${esc(strings.get("install.components.up_to_date"))}</span>"""
        }
        val installNote = if (installer) "" else """<p class="note">⚠ ${esc(strings.get("install.components.privileged_unavailable"))}</p>"""
        val title = if (installer || (wv.tooOld && rec != null && root)) {
            hardenedApprovalCardTitle(esc(strings.get("install.components.title")), conditional = true, strings = strings)
        } else {
            "<h2>${esc(strings.get("install.components.title"))}</h2>"
        }
        return """<div class="card" data-layout-key="managed-components">$title
$paneldRow
$compRow
${simpleRow("System WebView", wv.display, wvAction, strings)}
$installNote
<p class="note">${esc(strings.get("install.components.channel_prefix"))} <a href="${localizedHref("configure", strings)}">${esc(strings.get("shell.nav.configure"))}</a>${esc(strings.get("install.components.channel_suffix"))}</p>
<p class="note" id="comp-msg"></p></div>"""
    }

    /** Backup & restore card: an ENCRYPTED device-state bundle (ha-paneld config + optionally the HA
     *  Companion login) with a passphrase; restore shows a decrypt preview before the destructive apply.
     *  Also links the plain config-only bundle (for cloning settings between panels). */
    private fun backupCardHtml(
        companionHelper: Boolean,
        companionInstalled: Boolean,
        strings: AppStrings,
    ): String {
        val companion = backupCompanionCopy(installed = companionInstalled, helper = companionHelper)
        val compRow = when {
            companion.showLoginChoice -> """<label style="display:flex;flex-direction:row;gap:8px;align-items:center;font-size:.85rem"><input type="checkbox" id="bk-comp" checked> ${esc(strings.get("install.backup.companion.include_login"))}</label>"""
            companion.explainHelperRequirement -> """<p class="note">${esc(strings.get("install.backup.companion.helper_required"))}</p>"""
            else -> ""
        }
        val descriptionKey = if (companion.showLoginChoice) "install.backup.description.with_companion" else "install.backup.description.config_only"
        val restoreKey = if (companion.showLoginChoice) "install.backup.restore.description.with_companion" else "install.backup.restore.description.config_only"
        return """<div class="card" data-layout-key="backup-restore">${hardenedApprovalCardTitle(esc(strings.get("install.backup.title")), conditional = true, strings = strings)}
<p class="note">${esc(strings.get(descriptionKey))}</p>
<div style="display:flex;flex-direction:column;gap:8px;max-width:440px">
$compRow
<input type="password" id="bk-pw" placeholder="${esc(strings.get("install.backup.passphrase.placeholder"))}">
<label style="display:flex;flex-direction:row;gap:8px;align-items:flex-start;font-size:.85rem;color:#c88"><input type="checkbox" id="bk-plain"> ${esc(strings.get("install.backup.plaintext_zip"))}</label>
<button class="pbtn"${hardenedApprovalA11yAttrs(strings = strings)} onclick="doBackup(this)">⭳ ${esc(strings.get("install.backup.download"))}</button>
</div>
<hr style="border:0;border-top:1px solid #2a2a2a;margin:14px 0">
<p class="note"><b>${esc(strings.get("install.backup.restore.title"))}</b> ${esc(strings.get(restoreKey))}</p>
<div style="display:flex;flex-direction:column;gap:8px;max-width:440px">
<input type="password" id="rs-pw" placeholder="${esc(strings.get("install.backup.restore.passphrase_placeholder"))}">
<label class="pbtn" style="cursor:pointer">⭱ ${esc(strings.get("install.backup.restore.choose"))}<input type="file" id="rs-file" accept=".hpb,.zip,application/octet-stream,application/zip" style="display:none" onchange="restorePick(this)"></label>
<div id="rs-preview"></div>
</div>
<p class="note" id="bk-msg"></p>
<hr style="border:0;border-top:1px solid #2a2a2a;margin:14px 0">
<p class="note"><b>${esc(strings.get("install.backup.config_bundle.title"))}</b> ${esc(strings.get("install.backup.config_bundle.description"))}</p>
<div style="display:flex;gap:10px;flex-wrap:wrap;align-items:center">
 <a class="pbtn" href="api/v1/config/export">⭳ ${esc(strings.get("install.backup.config_bundle.export"))}</a>
 <button class="pbtn" type="button"${hardenedApprovalA11yAttrs(strings = strings)} onclick="configExport(true,this)">⭳ ${esc(strings.get("install.backup.config_bundle.export_secrets"))}</button>
 <label class="pbtn"${hardenedApprovalA11yAttrs(strings = strings)} style="cursor:pointer">⭱ ${esc(strings.get("install.backup.config_bundle.import"))}<input type="file" id="cfg-import-file" accept="application/json" style="display:none" onchange="configImport(this)"></label>
</div>
<p id="cfg-export-result" class="note" role="status" aria-live="polite"></p>
<pre id="cfg-import-result" class="muted" style="white-space:pre-wrap;margin-top:10px"></pre></div>"""
    }

    /** "Install an APK" card (Install tab). ⚠ Root-installs an arbitrary user-supplied APK over the
     *  unauthenticated LAN-trust :8888 — carries a prominent in-card security warning, an enable toggle
     *  (config.apkUploadAllowed), and a parse-then-confirm flow (see install.js). Root/helper-gated.
     *
     *  Two sources feed one review: a local file, or a link the panel fetches itself. The link exists
     *  because a phone browser may refuse to offer a downloaded APK to the file picker at all, which
     *  leaves upload-only administrators with no route. Both end at the same inspected staged file and
     *  the same confirm-before-install button. */
    private fun apkCardHtml(root: Boolean, strings: AppStrings): String {
        val body = if (!root) {
            """<p class="note">⚠ ${esc(strings.get("install.apk.root_unavailable"))}</p>"""
        } else {
            val allowed = config.apkUploadAllowed
            """<div class="setup">⚠ <b>${esc(strings.get("install.apk.security_title"))}</b> ${esc(strings.get("install.apk.security_warning"))} """ +
                """<small>(${esc(strings.get("install.apk.security_future_auth"))})</small></div>
<label style="display:flex;flex-direction:row;gap:8px;align-items:center;margin:10px 0"><input type="checkbox" id="apk-allow" ${if (allowed) "checked" else ""} onchange="apkAllow(this)"> ${esc(strings.get("install.apk.enable"))}</label>
<div id="apk-ui"${if (allowed) "" else " style=\"display:none\""}>
<label class="pbtn" style="cursor:pointer">⭱ ${esc(strings.get("install.apk.choose"))}<input type="file" id="apk-file" accept=".apk,application/vnd.android.package-archive" style="display:none" onchange="apkPick(this)"></label>
<label style="margin-top:10px">${esc(strings.get("install.apk.fetch_label"))}<input type="url" id="apk-url" inputmode="url" autocomplete="off" spellcheck="false" placeholder="https://example.com/app.apk"></label>
<button class="pbtn" style="margin-top:8px" onclick="apkFetchUrl()">⇩ ${esc(strings.get("install.apk.fetch"))}</button>
<div id="apk-preview" style="margin-top:10px"></div>
</div>"""
        }
        // Both actions in this card are approval-gated in Hardened mode — fetching, because it aims the
        // panel at a destination someone chose remotely, and installing — so the card title carries the
        // shield rather than each control repeating it.
        val title = if (root) hardenedApprovalCardTitle(esc(strings.get("install.apk.title")), strings = strings) else "<h2>${esc(strings.get("install.apk.title"))}</h2>"
        return """<div class="card" data-layout-key="apk-install">$title
<p class="note">${esc(strings.get("install.apk.description"))}</p>
$body
<p class="note" id="apk-msg"></p></div>"""
    }

    /** "Uninstall an app" card. Lists only removable apps (see packagesJson) so the picker can't strand the
     *  panel; the endpoint additionally refuses ha-paneld itself. Root-gated. */
    private fun uninstallCardHtml(root: Boolean, strings: AppStrings): String {
        val body = if (!root) """<p class="note">⚠ ${esc(strings.get("install.uninstall.root_unavailable"))}</p>"""
        else """<p class="note">${esc(strings.get("install.uninstall.description_prefix"))} <a href="${localizedHref("install#cfg-tame", strings)}">${esc(strings.get("install.uninstall.tame_link"))}</a>${esc(strings.get("install.uninstall.description_suffix"))}</p>
<div style="display:flex;gap:8px;align-items:center;flex-wrap:wrap">
<select id="uninst-pkg" style="min-width:220px;background:#1c1c1c;color:#eee;border:1px solid #444;border-radius:7px;padding:5px 8px"><option>${esc(strings.get("install.shared.loading"))}</option></select>
<button class="pbtn"${hardenedApprovalA11yAttrs(strings = strings)} onclick="doUninstall(this)">${esc(strings.get("install.uninstall.action"))}</button>
</div>
<p class="note" id="uninst-msg"></p>"""
        val title = if (root) hardenedApprovalCardTitle(esc(strings.get("install.uninstall.title")), strings = strings) else "<h2>${esc(strings.get("install.uninstall.title"))}</h2>"
        return """<div class="card" data-layout-key="uninstall-app">$title
$body</div>"""
    }

    /** Removable apps (third-party or updated-system) for the Uninstall picker, sorted by label. Stock
     *  system apps + ha-paneld are excluded — pm can't uninstall stock system apps (only disable), and
     *  self-uninstall would kill the tool. */
    /** All apps with a launcher entry, plus supported installed Companion renderers. The former feeds
     *  the generic Launcher-app picker; the latter is derived independently from the authoritative
     *  Companion package catalogue so arbitrary launchable apps never become Dashboard choices. */
    private fun launchableAppsJson(): String {
        val pm = appContext.packageManager
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        val apps = runCatching {
            pm.queryIntentActivities(intent, 0)
                .mapNotNull { it.activityInfo?.applicationInfo }
                .associate {
                    val pkg = it.packageName
                    val label = if (pkg == appContext.packageName) {
                        "Panel admin (ha-paneld)"
                    } else {
                        runCatching { pm.getApplicationLabel(it).toString() }.getOrDefault(pkg)
                    }
                    pkg to label
                }
                .toList()
                .sortedBy { it.second.lowercase(java.util.Locale.ROOT) }
        }.getOrDefault(emptyList())
        val rendererChoices = CompanionInstaller.rendererChoices(CompanionInstaller.installedPackages(appContext))
        return configureAppInventoryJson(apps, rendererChoices)
    }

    private fun packagesJson(): String {
        val apps = removablePackages()
        val arr = apps.joinToString(",") { (pkg, label) -> "{\"pkg\":${jsonStr(pkg)},\"label\":${jsonStr(label)}}" }
        return "{\"packages\":[$arr]}"
    }

    /** The server re-evaluates the same policy used by the picker; UI filtering is never authorization. */
    /**
     * Wire the hand-back routes to this panel's real taming state.
     *
     * The device profile is what authorises adopting a package carrying no ownership marker, so it is read
     * from the active profile here rather than accepted from a caller: a panel may only hand back the vendor
     * apps its own hardware profile names.
     */
    private fun handBackHomeDependencies(): HandBackHomeRouteDependencies =
        HandBackHomeRouteDependencies(
            profileKnownPackages = { tameProfileCandidates.mapTo(hashSetOf()) { it.pkg } },
            handBack = { profileKnown ->
                HandBackHomeController(
                    ownedMarkers = tame::ownedMarkerSnapshot,
                    packageStates = tame::handBackPackageStates,
                    homeCandidates = tame::handBackHomeCandidates,
                    clearDesiredState = {
                        // Both keys, and before the role moves: the reconciler re-asserts the desired
                        // blocklist on every wake, and `launcher_package` naming ha-paneld keeps the
                        // admin-home repair tick putting the role straight back.
                        runCatching {
                            config.setTameVendorPackages("")
                            config.setLauncherPackage("")
                        }.isSuccess
                    },
                    restoreOwned = tame::restoreEveryOwnedPackage,
                    enable = tame::adoptAndEnable,
                    setHome = { component -> system.setHomeActivity(component) },
                    observeHome = tame::observeDefaultHome,
                    ownPackage = appContext.packageName,
                ).handBack(profileKnown)
            },
            recordTamed = { pkg ->
                val outcome = tame.recordExternallyTamed(pkg)
                // An ownership marker outside the desired set is what the reconciler restores, so a recorded
                // package has to join the desired set or the next wake would undo the provisioner's work.
                if (outcome == HandBackHomePolicy.RecordOutcome.RECORDED) {
                    runCatching {
                        val desired = config.tameVendorPackages.toMutableList()
                        if (pkg !in desired) {
                            desired += pkg
                            config.setTameVendorPackages(desired.joinToString(" "))
                        }
                    }
                }
                outcome
            },
            authorize = { call, operation, payload, summary ->
                authorizeSensitive(call, operation, payload, summary)
            },
        )
    private fun removablePackages(): List<Pair<String, String>> {
        val pm = appContext.packageManager
        val homePackage = runCatching {
            pm.resolveActivity(
                android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME),
                0,
            )?.activityInfo?.packageName
        }.getOrNull()
        val excluded = setOfNotNull(
            appContext.packageName,
            io.github.maxlyth.hapaneld.util.WebViewInstaller.WEBVIEW_PKG,
            config.dashboardPackage.takeIf { it.isNotBlank() && it != SystemController.BUILTIN_DASHBOARD },
            config.launcherPackage.takeIf(String::isNotBlank),
            homePackage,
        )
        return runCatching {
            pm.getInstalledApplications(0)
                .filter { it.packageName !in excluded }
                .filter {
                    it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0 ||
                        it.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
                }
                .map { it.packageName to runCatching { pm.getApplicationLabel(it).toString() }.getOrDefault(it.packageName) }
                .sortedBy { it.second.lowercase(java.util.Locale.ROOT) }
        }.getOrDefault(emptyList())
    }

    /** A component row with a channel + version picker (versions hydrated by install.js), a release-notes
     *  link, and an Install button — for the GitHub-hosted components (ha-paneld, HA Companion). The
     *  channel select defaults to [defaultChannel] (the Configure-tab setting). */
    private fun pickerRow(
        name: String,
        label: String,
        installed: String?,
        defaultChannel: String,
        installer: Boolean,
        strings: AppStrings,
    ): String {
        fun sel(v: String) = if (defaultChannel == v) " selected" else ""
        return """<div class="comprow" data-name="${esc(name)}">
<div class="compname"><b>${esc(label)}</b> <span class="muted">${if (installed != null) """${esc(strings.get("install.shared.installed"))} <span class="cver">${esc(installed)}</span>""" else """<span class="cver">${esc(strings.get("install.shared.not_installed"))}</span>"""}</span></div>
<div class="comppick">
<label class="muted">${esc(strings.get("install.components.channel"))} <select class="cchan" onchange="loadVersions('$name')"><option value="stable"${sel("stable")}>${esc(strings.get("install.components.stable"))}</option><option value="prerelease"${sel("prerelease")}>${esc(strings.get("install.components.prerelease"))}</option></select></label>
<label class="muted">${esc(strings.get("install.shared.version"))} <select class="cvsel" onchange="verChanged('$name')"><option>${esc(strings.get("install.shared.loading"))}</option></select></label>
<a class="gh gh-inline cnotes" target="_blank" rel="noopener" title="${esc(strings.get("install.components.release_notes"))}" aria-label="${esc(strings.get("install.components.release_notes"))}" style="visibility:hidden"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="$GH_ICON"/></svg></a>
${if (installer) """<button class="pbtn cinstall"${hardenedApprovalA11yAttrs(strings = strings)} onclick="installSel('$name',this)" data-root="1" disabled>${esc(strings.get("install.components.install"))}</button>"""
        else """<a class="pbtn cdl" style="display:none" target="_blank" rel="noopener" title="${esc(strings.get("install.components.download_apk_help"))}">⬇ ${esc(strings.get("install.components.download_apk"))}</a>"""}
</div></div>"""
    }

    /** A component row with no picker — installed version + a single action/state (System WebView, or a
     *  Play-managed Companion). */
    private fun simpleRow(label: String, installed: String?, action: String, strings: AppStrings): String =
        """<div class="comprow">
<div class="compname"><b>${esc(label)}</b> <span class="muted">${if (installed != null) """${esc(strings.get("install.shared.installed"))} <span class="cver">${esc(installed)}</span>""" else """<span class="cver">${esc(strings.get("install.shared.not_installed"))}</span>"""}</span></div>
<div class="comppick">$action</div></div>"""

    /** Logs tab — live log tail over SSE. App source always; system source needs root (gated live). */
    private fun logsBody(strings: AppStrings): String {
        // Deliberately NOT inside a `.cards` masonry container — the log card wants the full page width.
        return """
<div class="card"><h2>${esc(strings.get("logs.title"))} <small id="lg-state" class="muted">· ${esc(strings.get("logs.state.connecting"))}</small></h2>
<div class="log-toolbar">
 <span class="log-source"><button id="lg-src-app" class="pbtn on" onclick="lgSource('app')">${esc(strings.get("logs.source.app"))}</button><button id="lg-src-system" class="pbtn" onclick="lgSource('system')" title="${esc(strings.get("logs.source.system_root_check"))}">${esc(strings.get("logs.source.system"))}</button><button id="lg-src-webview" class="pbtn" onclick="lgSource('webview')" title="${esc(strings.get("logs.source.webview_hint"))}">${esc(strings.get("logs.source.webview"))}</button></span>
 <select id="lg-level" onchange="lgRender()" title="${esc(strings.get("logs.level.minimum"))}">
  <option value="V" selected>${esc(strings.get("logs.level.verbose"))}</option><option value="D">${esc(strings.get("logs.level.debug"))}</option><option value="I">${esc(strings.get("logs.level.info"))}</option>
  <option value="W">${esc(strings.get("logs.level.warning"))}</option><option value="E">${esc(strings.get("logs.level.error"))}</option>
 </select>
 <input id="lg-filter" class="log-filter" placeholder="${esc(strings.get("logs.filter.placeholder"))}" oninput="lgRender()">
 <span class="log-actions">
  <label class="log-follow muted"><input type="checkbox" id="lg-follow" checked> ${esc(strings.get("logs.action.follow"))}</label>
  <button id="lg-pause" class="pbtn" onclick="lgPause()">⏸ ${esc(strings.get("logs.action.pause"))}</button>
  <button class="pbtn" onclick="lgClear()">${esc(strings.get("logs.action.clear"))}</button>
 </span>
</div>
<div id="lg-out" class="logview" onscroll="lgScrolled()"></div>
<p class="note">${esc(strings.get("logs.note.sources"))}
${esc(strings.get("logs.note.privacy"))}
${esc(strings.get("logs.note.raw_stream"))} <code>curl -N http://&lt;panel&gt;:${esc(config.httpPort.toString())}/api/v1/logs/stream</code></p></div>
<script src="assets/logs.js"></script>"""
    }

    /** Fleet tab — placeholder (discovery hooks exist; the roster lands later). */
    private fun fleetBody(strings: AppStrings): String = """
<div class="cards"><div class="card"><h2>${esc(strings.get("fleet.title"))} <small>· ${esc(strings.get("fleet.state.coming_soon"))}</small></h2>
<p class="note">${esc(strings.get("fleet.note.roster"))}
${esc(strings.get("fleet.note.discovery_prefix"))} (<code>${esc(Config.MDNS_SERVICE_TYPE)}</code>) ${esc(strings.get("fleet.note.discovery_suffix"))}</p>
<p class="note">${esc(strings.get("fleet.note.direct"))} <code>http://&lt;its-ip&gt;:${esc(config.httpPort.toString())}/</code>.</p></div></div>"""

    /** One renderer-aware warning shared by JSON status and the Dashboard/Install banners. */
    private fun dashboardRecoveryState(): PanelStatus.DashboardRecoveryState =
        PanelStatus.dashboardRecoveryState(
            config.dashboardPackage,
            appContext.packageName,
            SystemClock.elapsedRealtime(),
        )

    private fun dashboardRecoveryWarning(): String? = dashboardRecoveryWarning(dashboardRecoveryState())

    /** Health + capabilities as JSON for the variant UIs. Warnings are ready-to-render HTML fragments. */
    private fun statusJson(): String = statusJson(storageHealth(), databaseObservationNonce = null)

    private fun statusJson(
        storageSnapshot: StorageHealthSnapshot,
        databaseObservationNonce: String? = null,
    ): String {
        val management = snapStaleOk()
        val powerAdvisory = powerSafetyAdvisory(management.privilege)
        val companion = companionServersStaleOk()
        val radio = radioStatus()
        val storage = HealthAudit.storage(storageSnapshot)
        // Engine-aware WebView age check (a Cromite swap reports the stale OEM package version). Same finding
        // set as the dashboard banner + Install tab (HealthAudit); the audit lists ALL available updates
        // (not the ignore-filtered view — Ignore only silences the dashboard banner). Plus two warnings not
        // modelled by HealthAudit: renderer recovery suppression and a Companion with a blank internal_url.
        val h = healthInputs()
        val currentUpdates = UpdateChecker.current(appContext)
        val findings = healthFindings(h, h.webView.display, currentUpdates)
        val warns = mutableListOf<String>()
        val warningPresentations = mutableListOf<InstallPresentation?>()
        fun addWarning(warning: String?, presentation: InstallPresentation?) {
            if (warning == null) return
            warns += warning
            warningPresentations += presentation
        }
        val recoveryState = dashboardRecoveryState()
        addWarning(dashboardRecoveryWarning(recoveryState), dashboardRecoveryPresentation(recoveryState))
        // Same companion internal-URL decision as the dashboard/Install banner (CompanionDb.warning); this
        // surface presents it as bare JSON strings (no Ignore/repair buttons), so the copy stays distinct.
        when (val w = CompanionDb.warning(config.dashboardPackage, companion, management.privilege.directSuReady)) {
            is CompanionDb.Warning.NeedsRepair -> addWarning(
                "⚠ <b>Home Assistant Companion has no internal URL</b> (${w.affected} server${if (w.affected == 1) "" else "s"}) — " +
                    "the dashboard can fail with \"Missing 'Host' header\". Repair it on the Install tab.",
                InstallPresentation.create("status-companion-url-missing", mapOf("count" to w.affected.toString())),
            )
            CompanionDb.Warning.ProbeFailed -> addWarning(
                "⚠ <b>Home Assistant Companion settings could not be inspected</b> — " +
                    "ha-paneld will retain any last-known result and retry automatically.",
                InstallPresentation("status-companion-probe-failed"),
            )
            null -> {}
        }
        radio?.let { z ->
            addWarning(
                zigbeeWarning(z),
                zigbeeHealthPresentation(z, config.zigbeeRouterConfigured && config.zigbeeRouterEnabled),
            )
        }
        addWarning(storage.warningHtml(), storage.warningPresentation)
        addWarning(
            PowerSafetyPresentation.statusWarningHtml(powerAdvisory),
            PowerSafetyPresentation.warningPresentation(powerAdvisory),
        )
        val mdns = runCatching(mdnsWarningProjection).getOrNull()
        addWarning(mdns?.first, mdns?.second)
        val rollback = schemaRollbackVersions()
        findings.forEach { finding ->
            addWarning(
                statusWarning(finding),
                HealthAudit.presentation(
                    finding,
                    targetChromium = PanelHealth.MIN_CHROMIUM,
                    fromSchema = rollback?.first,
                    toSchema = rollback?.second,
                    updateComponent = finding.update?.component,
                ),
            )
        }
        val capColor = mapOf("ok" to "#48c774", "degraded" to "#d9a528", "none" to "#d04a3b")
        // Stale-while-revalidate keeps status polling fast while ensuring a status-only client still
        // admits one background refresh instead of preserving an old capability view indefinitely.
        val caps = management.capabilityRows.joinToString(",") { c ->
            "{\"name\":${jsonStr(c.name)},\"note\":${jsonStr(c.note)},\"color\":${jsonStr(capColor[c.status] ?: "#888")}}"
        }
        val zigbee = radio?.let {
            JSONObject(it.mqttAttributes()).put("state", it.state.wireValue).toString()
        } ?: "null"
        // `renderer` is emitted UNCONDITIONALLY, including for an external or unconfigured renderer,
        // because a consumer must never have to infer applicability from an absent field. A fleet
        // check that reads a missing object as "nothing to worry about" restates the very failure
        // this object exists to expose: a blank panel that every check still reports as green.
        // `camera` follows the exact same rule for a board with no camera at all — CameraPresentation
        // .absent() is emitted rather than the field being omitted.
        val storageProof = databaseObservationNonce?.let {
            "\"database_observation_nonce\":${jsonStr(it)},"
        }.orEmpty()
        val presentationOverlay = installWarningPresentationsJson(warns, warningPresentations)
            ?.let { "\"warning_presentations\":$it," }
            .orEmpty()
        return "{\"warnings\":[${warns.joinToString(",") { jsonStr(it) }}]," + presentationOverlay +
            "\"capabilities\":[$caps]," +
            storageProof +
            "\"panel_assistant_update\":${UpdateChecker.panelAssistantUpdateJson(currentUpdates)}," +
            // Additive, presentation-only, and read from state the panel already holds.
            "\"panel_assistant_device\":${
                PanelAssistantDevice.json(
                    config.friendlyName,
                    config.manufacturer,
                    config.model,
                    config.haArea,
                )
            }," +
            "\"zigbee_gateway\":$zigbee,\"storage_health\":${storage.statusJson()}," +
            // `ha_network` follows the same unconditional rule: idle with measuring=false when no
            // socket is held, never absent.
            "\"ha_network\":${HaNetworkPathRuntime.statusJson()}," +
                "\"ha_path_probe\":${PathProbeRuntime.statusJson()}," +
            "\"renderer\":${rendererAdmission().statusJson()}," +
            "\"camera\":${camera.presentation().statusJson()}," +
            "\"power_safety\":${PowerSafetyPresentation.json(powerAdvisory)}}"
    }

    /** A health finding as a one-line HTML warning for GET /api/v1/status (no Ignore button; updates keep
     *  a direct download link — this is the machine-readable audit, not the dashboard banner). */
    private fun statusWarning(f: HealthAudit.Finding): String = when (f.kind) {
        HealthAudit.Kind.WEBVIEW_OLD ->
            "⚠ <b>System WebView is too old</b> (${esc(f.detail)}) — the Home Assistant dashboard may render blank. " +
                "<a href=\"$WEBVIEW_DOC\" target=\"_blank\" rel=\"noopener\">How &amp; why to update</a> (target Chromium ${PanelHealth.MIN_CHROMIUM}+)."
        HealthAudit.Kind.NO_RENDERER ->
            "ℹ <b>MQTT is configured. Next: choose a dashboard renderer.</b> Select ha-paneld's built-in renderer, install the Home Assistant Companion app, or configure another dashboard package."
        HealthAudit.Kind.UPDATE -> f.update!!.let { u ->
            "⬆ <b>${esc(u.label)}</b> ${esc(u.latestVersion)} is available (installed ${esc(u.currentVersion)}) — " +
                "<a href=\"${esc(u.releaseUrl)}\" target=\"_blank\" rel=\"noopener\">download</a>"
        }
        HealthAudit.Kind.SCHEMA_ROLLED_BACK ->
            "⚠ <b>Newer database preserved after a version downgrade</b> (${esc(f.detail)}) — this build opened a " +
                "fresh state store because its schema is older; some settings may have reset. The previous database " +
                "is preserved on the panel for recovery. Check Configure or restore a backup."
    }


    /** The pencil that marks a value as CONFIGURABLE (vs a static fact) and deep-links to the exact
     *  setting/card on the Configure tab (`/configure#<anchor>` scrolls + flashes it). */
    private fun cfgIcon(anchor: String, strings: AppStrings): String =
        """&nbsp;<a class="cfglink" href="${localizedHref("configure#$anchor", strings)}" title="${esc(strings.get("dashboard.link.edit_configure"))}" aria-label="${esc(strings.get("dashboard.link.edit"))}">✎</a>"""

    private fun installIcon(anchor: String, strings: AppStrings): String =
        """&nbsp;<a class="cfglink" href="${localizedHref("install#$anchor", strings)}" title="${esc(strings.get("dashboard.link.open_install"))}" aria-label="${esc(strings.get("dashboard.link.open"))}">✎</a>"""

    /** What the "auto" (blank) package settings actually resolved to — shown as `auto (label)` in the
     *  dashboard rows and as the Configure-field placeholder, so "auto" is never a mystery. When no
     *  launcher app resolves, the Launcher key falls back to ha-paneld's own admin launcher (see
     *  SystemController.launchLauncher) — say so instead of leaving a "—" that reads like a dead key. */
    private fun autoHints(strings: AppStrings): Map<String, String> = buildMap {
        system.resolveDashboard("").takeIf { it.isNotBlank() }?.let {
            put("dashboard_package", dashboardRendererAutoLabel(it, strings))
        }
        put("launcher_package", system.resolvedLauncher("") ?: "ha-paneld admin launcher")
        // Unset home_dashboard = reload/boot land on whatever HA's frontend picks. On this path,
        // resolve the HA user's profile default (or the system fallback) in-band so the UI shows
        // a concrete target instead of an abstract description.
        put("home_dashboard", strings.get("dashboard.value.ha_default_view"))
    }

    private fun dashboardRendererAutoLabel(resolved: String, strings: AppStrings): String =
        if (resolved == SystemController.BUILTIN_DASHBOARD) strings.get("dashboard.value.builtin_renderer") else resolved

    /** One read-only dashboard row for a registry setting: label → current value + the edit pencil.
     *  Null when the setting doesn't exist on this panel (capability-gated). */
    private fun settingRowHtml(
        key: String,
        live: Map<String, String>,
        caps: Capabilities,
        strings: AppStrings,
        hints: Map<String, String> = emptyMap(),
        valueFormatter: SettingRowFormatter? = null,
    ): String? {
        val spec = SettingsRegistry.spec(key) ?: return null
        if (!spec.availableWhen(caps)) return null
        val raw = effectiveValue(spec, live)
        // NOTE the ordering: secret and BOOL specs resolve before [valueFormatter] is consulted, so a
        // formatter attached to one of those keys would be dead code. [SettingRowFormatter.of] refuses
        // to build one, so that is now unrepresentable rather than merely documented. Live state does
        // not belong on a setting row at all — put it on a fact row (see CONTEXT_KEYS).
        val shown = when {
            spec.secret -> if (raw.isNotEmpty()) strings.get("dashboard.value.set") else "—"
            spec.type == SettingType.BOOL -> strings.get(if (raw.toBoolean()) "dashboard.value.on" else "dashboard.value.off")
            raw.isBlank() -> hints[key]?.let {
                formattedString(strings, "dashboard.value.auto_detail", "value" to it)
            } ?: "—"
            // The built-in renderer sentinel has no package label — show its friendly name, not "builtin".
            raw == SystemController.BUILTIN_DASHBOARD -> strings.get("dashboard.value.builtin_renderer")
            else -> valueFormatter?.formatFor(key, raw) ?: raw
        }
        return """<tr><th>${esc(strings.get(spec.labelKey))}</th><td>${esc(shown)}${cfgIcon("cfg-$key", strings)}</td></tr>"""
    }

    // ---- dashboard snapshot (probe results) + hydration ---------------------------------------------
    //
    // Rendering `/` used to gather every root/probe value inline — ~8 serialized su round-trips
    // (zigbee status, CPU tier, network-ADB ×2, touch sound, `wm density` ×3, su presence), a 12+s
    // blank page on PX30 panels. The probes now funnel through ONE cached snapshot: `/` renders
    // whatever is last known instantly (placeholders on a cold start). Post-critical prewarm and stale
    // management endpoints all enter the same at-most-once-per-TTL, single-flight cache supplier.

    /** Everything the dashboard shows that costs a root/probe round-trip, gathered once. */
    private class Snap(
        val facts: Map<String, String>,
        val live: Map<String, String>,
        val caps: Capabilities,
        val capabilityRows: List<DiagReader.Cap>,
        val privilege: PrivilegedRouteObservation,
        val densityCur: Int?,
        val densityBase: Int?,
        val fontScale: Float,
        val wifiChronic: Boolean,
    )

    // The density trio is shared with the Configure tab's Display card (the bulk of ITS slow render).
    private val densityCache = Cached(DENSITY_TTL_MS) { density.observeSizing() }
    private val companionHelperCache = Cached(SU_TTL_MS) {
        val companionSupported = HelperClient.supportsCompanionData()
        val bundledBuildMatches = HelperClient.matchesBundledHelper()
        bundledHelperIsCanonical(
            bundledBuildMatches = bundledBuildMatches,
            companionSupported = companionSupported,
            guardSupported = companionSupported && bundledBuildMatches && GuardDbMaintenance.client.supported(),
        )
    }
    private fun ensureCompanionHelper(): Boolean {
        val result = BundledHelperInstaller.ensureCurrent(appContext)
        val ready = result in setOf(
            BundledHelperInstaller.Result.ALREADY_CURRENT,
            BundledHelperInstaller.Result.INSTALLED,
        )
        if (result == BundledHelperInstaller.Result.REPROVISION_REQUIRED) {
            Log.w(TAG, "root helper matches this release but is not canonical; reprovision required")
        }
        if (ready) companionHelperCache.invalidate()
        return ready
    }
    private fun rootOk(): Boolean = Su.availableCachedIsolated() || HelperClient.available()

    private val screenshotCacheDir: File
        get() = File(appContext.filesDir, "panel-screenshots")

    private val screenshotCachePointer: File
        get() = File(screenshotCacheDir, "current")

    private fun storedScreenshotCacheId(): String? = runCatching {
        screenshotCachePointer.readText().trim().takeIf {
            it.matches(Regex("[0-9a-f]{64}")) && File(screenshotCacheDir, "$it.png").isFile
        }
    }.getOrNull()

    private fun screenshotCacheId(): String? {
        storedScreenshotCacheId()?.let { return it }
        val legacy = File(appContext.filesDir, "last-panel-screenshot.png")
        val bytes = runCatching { legacy.takeIf { it.isFile && it.length() > 0 }?.readBytes() }.getOrNull()
            ?: return null
        cacheScreenshot(bytes)
        legacy.delete()
        return storedScreenshotCacheId()
    }

    private fun screenshotPlaceholderUrl(): String? =
        screenshotCacheId()?.let { "api/v1/screenshot.png?cached=$it" }

    private fun readCachedScreenshot(id: String): ByteArray? = runCatching {
        if (!id.matches(Regex("[0-9a-f]{64}"))) return@runCatching null
        File(screenshotCacheDir, "$id.png").takeIf { it.isFile && it.length() > 0 }?.readBytes()
    }.getOrNull()

    @Synchronized
    private fun cacheScreenshot(png: ByteArray): String? =
        runCatching {
            val dir = screenshotCacheDir.apply { mkdirs() }
            val id = MessageDigest.getInstance("SHA-256")
                .digest(png)
                .joinToString("") { "%02x".format(it) }
            val target = File(dir, "$id.png")
            if (!target.isFile) {
                atomicReplace(File(dir, "$id.png.new"), target, png)
            }
            val previous = storedScreenshotCacheId()
            atomicReplace(File(dir, "current.new"), screenshotCachePointer, "$id\n".toByteArray())
            dir.listFiles()
                ?.filter { it.extension == "png" && it.nameWithoutExtension !in setOf(id, previous) }
                ?.forEach { it.delete() }
            id
        }.getOrNull()

    private fun atomicReplace(staged: File, target: File, bytes: ByteArray) {
        staged.writeBytes(bytes)
        try {
            Files.move(
                staged.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: Throwable) {
            target.writeBytes(bytes)
            staged.delete()
        }
    }

    // One servers-table read supplies both the header URL fallback and repair warning. Warm routes use
    // stale-while-revalidate so an expired SQLite observation never blocks rendering.
    private val companionServerCache: Cached<CompanionDb.ServerObservation> by lazy {
        Cached(COMPANION_URL_TTL_MS) {
            val observed = if (Su.availableCachedIsolated()) {
                CompanionDb.observeServers(appContext, Su)
            } else if (CompanionInstaller.installedPkg(appContext) == null) {
                CompanionDb.ServerObservation.EMPTY
            } else {
                CompanionDb.ServerObservation.UNKNOWN
            }
            CompanionDb.retainLastKnownServerObservation(companionServerCache.peek(), observed)
        }
    }

    private fun privilegeObservation(): PrivilegedRouteObservation = observePrivilegedRoutes(
        directSuProbe = { Su.availableCachedIsolated() },
        helperRootProbe = HelperClient::available,
        shizukuSnapshot = ShizukuBridge::snapshot,
    ).also { AccessDenialMemo.app.onCapabilitySignal(listOf(it.directSuReady, it.helperRootReady, it.shizuku.ready)) }

    private val snapCache = Cached(SNAP_TTL_MS) {
        val privilege = privilegeObservation()
        val management = managementProjection(privilege)
        val d = densityCache.getWithSupplier { density.observeSizing(privilege) }
        Snap(
            facts = management.facts,
            live = management.live,
            caps = management.capabilities,
            capabilityRows = management.capabilityRows,
            privilege = privilege,
            densityCur = d.current,
            densityBase = d.base,
            fontScale = d.fontScale,
            wifiChronic = management.wifiChronic,
        )
    }
    private val diagCache = Cached(DIAG_TTL_MS) {
        val management = checkNotNull(snapCache.peek()) {
            "diagnostics require the management snapshot to be built first"
        }
        DiagReader.dump(
            appContext,
            profile,
            management.facts,
            radioStatus(),
            privilege = management.privilege,
            capabilityRows = management.capabilityRows,
            displaySizing = DiagReader.DisplaySizingEvidence(
                management.densityBase,
                management.densityCur,
                management.fontScale,
            ),
            storage = storageHealth(),
            powerSafety = powerSafety(),
            renderer = rendererAdmission(),
            camera = camera.presentation(),
            wifiStabilityChronic = management.wifiChronic,
            haNetwork = HaNetworkPathRuntime.diagnosticLine(),
            haPathProbe = PathProbeRuntime.diagnosticLine(),
        )
    }

    /**
     * The renderer/Home Assistant admission projection, built LIVE on every read rather than through
     * [snapCache].
     *
     * Two reasons, both learned the hard way. The state changes during an outage, so a
     * stale-while-revalidate copy would answer a "is the dashboard up?" question with a value from
     * before it went down — the same defect that made the lifecycle row live. And a deployment check
     * judges staleness from `observed_age_ms`, so an age measured against a cached capture would be
     * an age of the cache, not of the observation.
     */
    private fun rendererAdmission(): RendererAdmissionPresentation {
        val pkg = config.dashboardPackage
        // Only an Ambient panel pays for the runtime read; every other policy reports nothing from it.
        val ambient = if (config.dashboardTheme == DashboardTheme.AMBIENT) autoBrightnessHttpApi.ambientTheme() else null
        val mode = when {
            SystemController.isBuiltinSelection(pkg, appContext.packageName) -> RendererMode.BUILTIN
            pkg.isBlank() -> RendererMode.NONE
            else -> RendererMode.EXTERNAL
        }
        return RendererAdmissionPresentation.of(
            mode = mode,
            haUrl = config.haUrl,
            addressFamilyPolicy = config.mqttAddressFamily,
            live = RendererAdmissionRuntime.current(),
            nowElapsedMs = android.os.SystemClock.elapsedRealtime(),
            processStartElapsedMs = android.os.Process.getStartElapsedRealtime(),
            packageUpdatedAtMs = packageUpdatedAtMs(),
            nowWallMs = System.currentTimeMillis(),
            themePolicy = config.dashboardTheme,
            themeEffectivePolicy = config.dashboardThemeEffective,
            ambientReason = ambient?.reason,
            ambientLevel = ambient?.level,
        )
    }

    /**
     * When this app package was last installed or replaced, or null when the package manager would
     * not say. Same source as [buildToken], read as a number rather than an opaque token because a
     * deployment check has to do arithmetic with it.
     *
     * The failure is deliberately not distinguished from an unset value, and deliberately does not
     * fail the status request: this is one figure on a health surface whose whole purpose is to keep
     * answering while things are wrong. Swallowing it is safe because it is reported as null and
     * every consumer treats null as "cannot prove it" — a deployment check refuses a panel that
     * cannot name its own install rather than passing it — so the quiet path is the strict one, not
     * a way through.
     */
    private fun packageUpdatedAtMs(): Long? =
        runCatching { appContext.packageManager.getPackageInfo(appContext.packageName, 0).lastUpdateTime }
            .getOrNull()

    /** Call after any write that changes probed state (config apply/import/restore, density, tame),
     *  so the next render doesn't show pre-write values for a TTL. */
    private fun snapInvalidate() {
        snapCache.invalidate()
        diagCache.invalidate()
        densityCache.invalidate()
    }

    /** Empirical proximity mode can change without a config write. Drop the stale capability view so
     *  the next Configure/dashboard request reflects learned reporting eligibility immediately. */
    internal fun invalidateCapabilitySnapshot() {
        snapCache.invalidate()
        diagCache.invalidate()
    }

    /** Storage is sampled live by status/UI; only the bounded diagnostic dump can retain an old value. */
    internal fun invalidateStorageHealthDiagnostics() {
        diagCache.invalidate()
    }

    /** Last management-request privilege proof for passive safety work. Never starts a fresh probe. */
    internal fun lastPrivilegeObservation(): PrivilegedRouteObservation? = snapCache.peek()?.privilege

    /** Ranged proximity is learned from live samples and can change between cached hardware probes.
     *  Overlay that cheap live fact so stale-while-revalidate can never expose wake UI for one request
     *  after eligibility is lost. Other capabilities retain their bounded cached probe semantics. */
    private fun liveCapabilities(cached: Capabilities): Capabilities =
        cached.copy(
            hasProximity = sensors.hasProximity(),
            hasLearnedProximity = sensors.hasLearnedProximity(),
        )

    /** Last-known snapshot with a background refresh when stale — never blocks once built, so the
     *  Configure endpoints (form values, schema capabilities, Display card) render instantly like
     *  the dashboard. Blocks only before the start-up pre-warm has ever completed. */
    private fun snapStaleOk(): Snap {
        return snapCache.staleWhileRevalidate { refresh, releaseAdmission ->
            if (stopping) return@staleWhileRevalidate false
            val job = scope.launch(Dispatchers.IO) { runCatching { refresh() } }
            job.invokeOnCompletion { releaseAdmission() }
            !job.isCancelled
        }
    }

    /** Presentation capability from the existing bounded privilege snapshot. Fresh root probing remains
     * confined to the explicit repair operation, so opening a page cannot add a multi-second su probe. */
    private fun powerSafetyAdvisory(privilege: PrivilegedRouteObservation): PowerSafetyAdvisory {
        val capability = when {
            privilege.directSuReady -> PowerRepairCapability.DIRECT_ROOT
            profile.appCanSu -> PowerRepairCapability.DEGRADED
            else -> PowerRepairCapability.APP_ONLY
        }
        return PowerSafetyAdvisoryPolicy.evaluate(
            powerSafety(),
            capability,
            config.powerSafetyAcknowledgementFingerprint,
        )
    }

    private fun companionServersStaleOk(): CompanionDb.ServerObservation =
        companionServerCache.staleWhileRevalidate { refresh, releaseAdmission ->
            if (stopping) return@staleWhileRevalidate false
            val job = scope.launch(Dispatchers.IO) { runCatching { refresh() } }
            job.invokeOnCompletion { releaseAdmission() }
            !job.isCancelled
        }

    /** Dashboard rendering never performs the cold root/SQLite read; startup prewarm owns that path. */
    private fun companionServersForRender(): CompanionDb.ServerObservation? =
        companionServerCache.peek()?.let { companionServersStaleOk() }

    /** Complete last-known support report. Its own expensive probes run only in the single-flight
     * refresh, never in a warm HTTP response and never by forcing a simultaneous facts refresh. */
    private fun diagStaleOk(): String {
        // The documented cold path may block, but builds the facts snapshot first so the first complete
        // report is coherent. Once a report exists, both refreshes happen sequentially in the background.
        if (diagCache.peek() == null) {
            snapCache.get()
            return diagCache.get()
        }
        return diagCache.staleWhileRevalidate { refresh, releaseAdmission ->
            if (stopping) return@staleWhileRevalidate false
            val job = scope.launch(Dispatchers.IO) {
                runCatching { snapCache.get() }
                runCatching { refresh() }
            }
            job.invokeOnCompletion { releaseAdmission() }
            !job.isCancelled
        }
    }

    private val NET_KEYS = listOf("Local IP", "Local IPv6", "HTTP port", "MQTT", "mDNS", "Network ADB")
    private val HA_LIFECYCLE_FACT = "HA lifecycle"
    private val HA_NETWORK_FACT = "HA network path"
    private val HA_RENDERER_FACT = "HA renderer"
    private val CAMERA_FACT = "Camera"

    // Order is the render order of the Runtime diagnostics card. "Wi-Fi stability" leads because it is
    // absent on a healthy panel and only ever appears when the network under everything else on this
    // card has been dropping out — so when it IS shown it explains the rows below it, and reading it
    // last is reading it too late. "HA renderer" follows it for the same reason one place down: it is
    // the panel's headline outcome — whether the dashboard is actually up — and every row below it
    // describes machinery that exists to keep it up. "HA network path" sits between them: it is the
    // measured path to the server every row below depends on, and the likeliest reason a dashboard
    // that IS rendered still feels broken.
    private val CONTEXT_KEYS = listOf(
        "Wi-Fi stability", HA_NETWORK_FACT, HA_RENDERER_FACT, "MQTT state", "State convergence", "Local-state sync",
        "App database", "Security mode", "Audio playback", CAMERA_FACT, "Log shipping", HA_LIFECYCLE_FACT,
    )
    private val BEHAVIOUR_FACT_KEYS = setOf(
        "Keep panel responsive", "Prevent idle dim", "Android dashboard lock", "Navbar",
    )
    // Rows whose values are DECLARED by the DeviceProfile, so wrong data points a contributor straight
    // at the fix: Platform/SoC=profile identity, LED=ledMechanism, sensor tech=proximityTech/lightTech,
    // Zigbee=zigbeeGatewayDir, Relays=relayBase, CPU profile=cpuGovernors.
    private fun infoKeys(s: Snap): List<String> =
        s.facts.keys.filter {
            it !in NET_KEYS && it !in PROFILE_FACT_KEYS && it !in CONTEXT_KEYS && it !in BEHAVIOUR_FACT_KEYS
        }

    private fun factLabel(key: String, strings: AppStrings): String {
        val suffix = when (key) {
            "panel_id" -> "panel_id"
            "Android" -> "android"
            "Firmware" -> "firmware"
            "Device" -> "device"
            "Device ID" -> "device_id"
            "CPU" -> "cpu"
            "RAM" -> "ram"
            "Storage" -> "storage"
            "Display" -> "display"
            "System WebView" -> "system_webview"
            "HA Companion" -> "ha_companion"
            "Friendly name" -> "friendly_name"
            "HTTP port" -> "http_port"
            "Local IP" -> "local_ip"
            "Local IPv6" -> "local_ipv6"
            "MQTT" -> "mqtt"
            "MQTT state" -> "mqtt_timing"
            "Security mode" -> "security_mode"
            "mDNS" -> "mdns"
            "Platform" -> "platform"
            "SoC" -> "soc"
            "Model" -> "model"
            "LED" -> "led"
            "Light sensor" -> "light_sensor"
            "Proximity" -> "proximity"
            "Navbar" -> "navbar"
            "Zigbee" -> "zigbee"
            "Relays" -> "relays"
            "CPU profile" -> "cpu_profile"
            "Network ADB" -> "network_adb"
            "Log shipping" -> "log_shipping"
            "Audio playback" -> "audio_playback"
            "App database" -> "app_database"
            "Wi-Fi stability" -> "wifi_stability"
            HA_NETWORK_FACT -> "ha_network_path"
            HA_RENDERER_FACT -> "ha_renderer"
            "State convergence" -> "state_convergence"
            "Local-state sync" -> "local_state_sync"
            CAMERA_FACT -> "camera"
            HA_LIFECYCLE_FACT -> "ha_lifecycle"
            "System WebView reporting" -> "webview_reporting"
            else -> return key
        }
        return strings.get("dashboard.fact.$suffix")
    }

    private fun contextRowsHtml(s: Snap, h: HealthInputs, strings: AppStrings): String {
        val rows = CONTEXT_KEYS.mapNotNull { key ->
            // The lifecycle state changes DURING an outage, so this row is rendered from the live
            // snapshot rather than the stale-while-revalidate facts cache AND is then kept current by
            // the same ten-second `/health` poll that drives the banner — one observation feeding every
            // lifecycle surface. A server-rendered advisory banner used to sit alongside it; it was
            // DELETED rather than synchronised, because a one-shot render cannot retract itself and left
            // an outage warning on screen after recovery.
            // The lifecycle row is rendered even when there is nothing to say yet — as an empty cell the
            // poll can fill. Omitting it meant a panel that began watching AFTER the page was rendered
            // (the watch waits for the renderer to settle) had no element to populate, so the row could
            // never appear without a reload: a surface that can only ever go from present to absent.
            // The renderer row is live for the same reason as the lifecycle row and one more: its
            // whole subject is a state that changes while the page is open. Routing it through the
            // facts cache would let a panel that went blank a minute ago keep saying "rendered" for a
            // TTL — precisely the reassuring-but-wrong answer this row exists to stop giving.
            val current = when (key) {
                HA_LIFECYCLE_FACT -> HaLifecycleRuntime.statusText() ?: ""
                // Live and always present for the same reasons as the lifecycle row: the verdict
                // changes while the page is open, and the poll fills the cell from the same `/health`
                // observation that drives the banner. One read of the one state owner.
                HA_NETWORK_FACT -> HaNetworkPathRuntime.statusText() ?: ""
                HA_RENDERER_FACT -> rendererAdmission().statusText()
                // The camera row is live for the same reason, and it is also where a person reads the
                // stream URL off the panel — with the warning that travels beside it, because the place
                // the URL is copied from is the place somebody is about to paste it into a card on this
                // very panel. A panel whose profile declares no camera has nothing to say and no row.
                CAMERA_FACT -> camera.presentation().takeIf { it.state != CameraState.ABSENT }?.summary
                else -> s.facts[key]
            }
            // Log shipping earns a live row only while it is on; when it is off the Behaviour card's
            // "Ship logs" already says so, and a permanent "off" here is noise.
            current?.takeUnless { key == "Log shipping" && it == LOG_SHIP_STATUS_OFF }?.let { value ->
                val label = factLabel(key, strings)
                val cellId = when (key) {
                    HA_LIFECYCLE_FACT -> " id=\"halifecell\""
                    HA_NETWORK_FACT -> " id=\"hanetcell\""
                    else -> ""
                }
                "<tr><th>${esc(label)}</th><td$cellId>${esc(localizedRuntimeValue(key, value, strings))}</td></tr>"
            }
        }.toMutableList()
        h.webView.reportingQuirk?.let {
            rows += "<tr><th>${esc(factLabel("System WebView reporting", strings))}</th><td>${esc(it)}</td></tr>"
        }
        return rows.joinToString("\n")
    }

    /** Translate only closed, exact runtime states. Evidence-bearing and backend-origin detail remains verbatim. */
    private fun runtimeValue(value: String, strings: AppStrings): String = when (value) {
        "on" -> strings.get("dashboard.value.on")
        "off" -> strings.get("dashboard.value.off")
        "none" -> strings.get("dashboard.value.none")
        "unavailable" -> strings.get("dashboard.common.unavailable")
        "Relaxed" -> strings.get("dashboard.runtime.security_relaxed")
        "Hardened · high-impact remote actions need physical on-panel approval" ->
            strings.get("dashboard.runtime.security_hardened")
        "idle" -> strings.get("dashboard.runtime.audio_idle")
        "queued" -> strings.get("dashboard.runtime.audio_queued")
        "active" -> strings.get("dashboard.runtime.audio_active")
        "closed" -> strings.get("dashboard.runtime.audio_closed")
        "watching" -> strings.get("dashboard.runtime.ha_watching")
        "connection lost" -> strings.get("dashboard.runtime.ha_connection_lost")
        "watching; Home Assistant does not permit WebSocket lifecycle events for this user" ->
            strings.get("dashboard.runtime.ha_events_refused")
        "failed" -> strings.get("dashboard.runtime.audio_failed")
        "disabled" -> strings.get("dashboard.runtime.disabled")
        "not measured; this panel holds no authenticated Home Assistant socket" ->
            strings.get("dashboard.runtime.ha_network_not_measured")
        "external renderer · Home Assistant connection not observed by ha-paneld" ->
            strings.get("dashboard.runtime.external_renderer_unobserved")
        else -> runtimeValueFamily(value, strings)
    }

    private fun localizedRuntimeValue(key: String, value: String, strings: AppStrings): String = when (key) {
        HA_RENDERER_FACT -> localizedRendererRuntime(value, strings)
        "MQTT state" -> localizedMqttRuntime(value, strings)
        "App database" -> localizedDatabaseRuntime(value, strings)
        CAMERA_FACT -> localizedCameraRuntime(value, strings)
        else -> runtimeValue(value, strings)
    }

    private fun localizedRendererRuntime(value: String, strings: AppStrings): String {
        if (!value.startsWith("built-in · ")) return runtimeValue(value, strings)
        val admitted = Regex("^(.*) · admitted ([^·]+) ago$").matchEntire(value)
        var summary = (admitted?.groupValues?.get(1) ?: value).removePrefix("built-in · ")
        val themeSuffix = RendererAdmissionPresentation.OVERRIDDEN_SUMMARY_SUFFIX
        val themeOverridden = summary.endsWith(themeSuffix)
        if (themeOverridden) summary = summary.removeSuffix(themeSuffix)
        val translated = when (summary) {
            "dashboard rendered" -> strings.get("dashboard.runtime.renderer.rendered")
            "dashboard rendered; admitted on a previously verified Home Assistant version" ->
                strings.get("dashboard.runtime.renderer.rendered_cached")
            "admitted on a previously verified Home Assistant version; the dashboard has not connected" ->
                strings.get("dashboard.runtime.renderer.admitted_cached")
            "admitted; the dashboard has not connected yet" ->
                strings.get("dashboard.runtime.renderer.admitted_waiting")
            "checking Home Assistant compatibility" -> strings.get("dashboard.runtime.renderer.checking")
            "no admission observed for the current renderer" -> strings.get("dashboard.runtime.renderer.unobserved")
            else -> return value // Classified blocked detail is uncommon diagnostic evidence.
        }.let {
            if (themeOverridden) it + strings.get("dashboard.runtime.renderer.theme_override_suffix") else it
        }
        val status = formattedString(strings, "dashboard.runtime.renderer.builtin", "summary" to translated)
        return admitted?.let {
            formattedString(strings, "dashboard.runtime.renderer.admitted_age", "status" to status, "age" to it.groupValues[2])
        } ?: status
    }

    private fun localizedMqttRuntime(value: String, strings: AppStrings): String {
        if (value == "disabled") return strings.get("dashboard.runtime.disabled")
        if (value == "config-error · invalid or unsupported broker URL") {
            return strings.get("dashboard.runtime.mqtt.config_error")
        }
        val match = Regex(
            "^([^·]+) · ((?:TLS|TCP)(?:/IPv[46])?) · last-ok ([^·]+) · last-auth ([^·]+) · (.+) · family (.+)$",
        ).matchEntire(value) ?: return value
        val state = localizedMqttState(match.groupValues[1].trim(), strings) ?: return value
        val lastOk = localizedMqttAge(match.groupValues[3].trim(), strings) ?: return value
        val lastAuth = localizedMqttAge(match.groupValues[4].trim(), strings) ?: return value
        val authRaw = match.groupValues[5].trim()
        val auth = if (authRaw == "auth-ok") {
            authRaw
        } else {
            val authMatch = Regex("^(connected|auth-retrying|auth-failed) · rejects (\\d+) · attempt (\\d+) · next (.+)$")
                .matchEntire(authRaw) ?: return value
            val authState = localizedMqttState(authMatch.groupValues[1], strings) ?: return value
            val next = when (val raw = authMatch.groupValues[4]) {
                "none" -> strings.get("dashboard.value.none")
                else -> Regex("^(\\d+)s$").matchEntire(raw)?.let {
                    formattedString(strings, "dashboard.runtime.mqtt.seconds", "seconds" to it.groupValues[1])
                } ?: return value
            }
            formattedString(
                strings,
                "dashboard.runtime.mqtt.auth_retry",
                "state" to authState,
                "rejects" to authMatch.groupValues[2],
                "attempt" to authMatch.groupValues[3],
                "next" to next,
            )
        }
        val family = when (val raw = match.groupValues[6].trim()) {
            "Prefer IPv4" -> strings.get("dashboard.runtime.mqtt.family_prefer_ipv4")
            "Force IPv4" -> strings.get("dashboard.runtime.mqtt.family_force_ipv4")
            else -> Regex("^Automatic \\(next (IPv[46])\\)$").matchEntire(raw)?.let {
                formattedString(strings, "dashboard.runtime.mqtt.family_automatic", "family" to it.groupValues[1])
            } ?: return value
        }
        return formattedString(
            strings,
            "dashboard.runtime.mqtt.status",
            "state" to state,
            "transport" to match.groupValues[2],
            "lastOk" to lastOk,
            "lastAuth" to lastAuth,
            "auth" to auth,
            "family" to family,
        )
    }

    private fun localizedMqttState(value: String, strings: AppStrings): String? = when (value) {
        "connected", "announcing", "auth-retrying", "auth-failed", "unreachable", "connecting", "discovering", "disconnected" ->
            strings.get("dashboard.runtime.mqtt.state.${value.replace('-', '_')}")
        else -> null
    }

    private fun localizedMqttAge(value: String, strings: AppStrings): String? = when (value) {
        "never" -> strings.get("dashboard.runtime.mqtt.age_never")
        else -> Regex("^(\\d+)s ago$").matchEntire(value)?.let {
            formattedString(strings, "dashboard.runtime.mqtt.age_seconds_ago", "seconds" to it.groupValues[1])
        }
    }

    private fun localizedDatabaseRuntime(value: String, strings: AppStrings): String {
        val translated = value.split(" · ").map { segment ->
            Regex("^(.+) used$").matchEntire(segment)?.let {
                formattedString(strings, "dashboard.runtime.database.used", "bytes" to it.groupValues[1])
            } ?: Regex("^(.+) on disk$").matchEntire(segment)?.let {
                formattedString(strings, "dashboard.runtime.database.on_disk", "bytes" to it.groupValues[1])
            } ?: Regex("^schema (\\d+)$").matchEntire(segment)?.let {
                formattedString(strings, "dashboard.runtime.database.schema", "version" to it.groupValues[1])
            } ?: return value
        }
        return translated.joinToString(" · ")
    }

    private fun localizedCameraRuntime(value: String, strings: AppStrings): String {
        when (value) {
            "camera off" -> return strings.get("dashboard.runtime.camera.off")
            "camera on, but Android has not granted the permission" ->
                return strings.get("dashboard.runtime.camera.permission_needed")
            "camera stopping" -> return strings.get("dashboard.runtime.camera.stopping")
        }
        if (!value.contains("; ")) return value
        val stateRaw = value.substringBeforeLast("; ")
        val stream = localizedCameraStream(value.substringAfterLast("; "), strings) ?: return value
        when {
            stateRaw == "camera opening" ->
                return formattedString(strings, "dashboard.runtime.camera.opening", "stream" to stream)
            stateRaw == "camera closed; nobody is watching" ->
                return formattedString(strings, "dashboard.runtime.camera.idle", "stream" to stream)
            stateRaw.startsWith("camera gave up after ") -> {
                val match = Regex("^camera gave up after (\\d+) failures \\(([^)]+)\\)$").matchEntire(stateRaw) ?: return value
                return formattedString(
                    strings,
                    "dashboard.runtime.camera.degraded",
                    "count" to match.groupValues[1],
                    "fault" to match.groupValues[2],
                    "stream" to stream,
                )
            }
            stateRaw.startsWith("camera open for ") -> {
                val match = Regex("^camera open for (\\d+) clients?(?: \\((\\d+) streaming\\))?$").matchEntire(stateRaw)
                    ?: return value
                val clients = match.groupValues[1]
                val streaming = match.groupValues[2]
                val key = when {
                    clients == "1" && streaming.isEmpty() -> "dashboard.runtime.camera.live_one"
                    clients != "1" && streaming.isEmpty() -> "dashboard.runtime.camera.live_many"
                    clients == "1" -> "dashboard.runtime.camera.live_one_streaming"
                    else -> "dashboard.runtime.camera.live_many_streaming"
                }
                return formattedString(
                    strings,
                    key,
                    "count" to clients,
                    "streaming" to streaming,
                    "stream" to stream,
                )
            }
            else -> return value
        }
    }

    private fun localizedCameraStream(value: String, strings: AppStrings): String? = when (value) {
        "stream not listening" -> strings.get("dashboard.runtime.camera.stream_not_listening")
        else -> Regex("^stream listening on port (\\d+)$").matchEntire(value)?.let {
            formattedString(strings, "dashboard.runtime.camera.stream_port", "port" to it.groupValues[1])
        } ?: Regex("^stream at (rtsp://.+) \\(not for this panel's own dashboard\\)$").matchEntire(value)?.let {
            formattedString(strings, "dashboard.runtime.camera.stream_url", "url" to it.groupValues[1])
        }
    }

    private fun runtimeValueFamily(value: String, strings: AppStrings): String {
        if (value.startsWith("failed · ")) {
            return formattedString(
                strings,
                "dashboard.runtime.audio_failed_detail",
                "detail" to value.removePrefix("failed · "),
            )
        }
        listOf(
            "healthy; " to "dashboard.runtime.ha_network_healthy",
            "slow; " to "dashboard.runtime.ha_network_slow",
            "severely degraded; " to "dashboard.runtime.ha_network_severe",
        ).forEach { (prefix, key) ->
            if (value.startsWith(prefix)) {
                return formattedString(strings, key, "evidence" to value.removePrefix(prefix))
            }
        }
        Regex("^(\\d+) channels · (\\d+) dirty · (\\d+) in-flight · (\\d+) unknown · ack (\\d+)/(\\d+) · pending (.+)$")
            .matchEntire(value)
            ?.let { match ->
                val pending = match.groupValues[7].let {
                    if (it == "none") strings.get("dashboard.value.none") else it
                }
                return formattedString(
                    strings,
                    "dashboard.runtime.convergence",
                    "channels" to match.groupValues[1],
                    "dirty" to match.groupValues[2],
                    "inFlight" to match.groupValues[3],
                    "unknown" to match.groupValues[4],
                    "successes" to match.groupValues[5],
                    "failures" to match.groupValues[6],
                    "pending" to pending,
                )
            }
        return value
    }

    private fun formattedString(strings: AppStrings, key: String, vararg values: Pair<String, String>): String =
        values.fold(strings.get(key)) { text, (name, value) -> text.replace("{$name}", value) }

    /** Browser form failures get a localized, escaped mini-page; API callers retain the stable legacy token. */
    private suspend fun respondInstallFormError(
        call: ApplicationCall,
        strings: AppStrings,
        key: String,
        machineText: String,
        status: HttpStatusCode,
    ) {
        if (!installFormWantsHtml(call.request.headers["Accept"])) {
            call.respondText("$machineText\n", status = status)
            return
        }
        call.respondText(
            "<!doctype html><base href=\"/\"><meta charset=utf-8><body style='font-family:system-ui;background:#111;color:#eee;padding:20px'>" +
                esc(strings.get(key)) + "</body>",
            ContentType.Text.Html,
            status,
        )
    }

    private fun localizedSetupNeeds(needs: List<String>, strings: AppStrings): String = needs.joinToString(
        separator = " ${strings.get("dashboard.banner.setup_needs.joiner")} ",
    ) { need ->
        when (need) {
            "MQTT configuration" -> strings.get("dashboard.banner.setup_needs.mqtt_configuration")
            "valid MQTT credentials" -> strings.get("dashboard.banner.setup_needs.valid_credentials")
            "valid MQTT credentials (the broker rejected them)" ->
                strings.get("dashboard.banner.setup_needs.rejected_credentials")
            "a reachable MQTT broker" -> strings.get("dashboard.banner.setup_needs.reachable_broker")
            "a valid MQTT broker URL" -> strings.get("dashboard.banner.setup_needs.valid_broker_url")
            else -> need
        }
    }

    private fun localizedSetupProgress(progress: String, strings: AppStrings): String {
        val next = " The dashboard setup step appears next."
        val suffix = if (progress.endsWith(next)) " ${strings.get("shell.setup_progress.next")}" else ""
        val base = progress.removeSuffix(next)
        val translated = when (base) {
            "MQTT settings saved — verifying the broker connection. This can take a short while after saving." ->
                strings.get("shell.setup_progress.verifying")
            "MQTT connected — publishing Home Assistant discovery." ->
                strings.get("shell.setup_progress.publishing")
            else -> base
        }
        return translated + suffix
    }

    private fun localizedProximitySummary(summary: String, strings: AppStrings): String = when (summary) {
        "No proximity source" -> strings.get("dashboard.proximity.no_source")
        "Waiting for the proximity source's first trustworthy reading" ->
            strings.get("dashboard.proximity.waiting_first_reading")
        "Checking the previous learned range against live readings" ->
            strings.get("dashboard.proximity.checking_previous_range")
        "Learning the clear-room baseline" -> strings.get("dashboard.proximity.learning_baseline")
        "Baseline learned; waiting for complete near-and-clear movements" ->
            strings.get("dashboard.proximity.waiting_movements")
        "Adapting safely to a changed proximity signal" -> strings.get("dashboard.proximity.adapting")
        "Presence ready; learning deliberate wake gestures" -> strings.get("dashboard.proximity.learning_gestures")
        "Waiting for trustworthy proximity readings" -> strings.get("dashboard.proximity.waiting_readings")
        else -> {
            val ready = Regex("^Ready · ([a-z_]+) · normalized 0–100$").matchEntire(summary)
            if (ready != null) {
                val mode = when (ready.groupValues[1]) {
                    "binary" -> strings.get("dashboard.proximity.mode.binary")
                    "graded" -> strings.get("dashboard.proximity.mode.graded")
                    else -> ready.groupValues[1]
                }
                formattedString(strings, "dashboard.proximity.ready", "mode" to mode)
            } else {
                summary
            }
        }
    }

    /**
     * Whether setup genuinely still owes the user a dashboard/renderer step.
     *
     * The MQTT progress banner may only promise "the dashboard setup step appears next" when that is true.
     * Every MQTT reconnect re-announces discovery, including the one after an ordinary upgrade, so without
     * this a fully configured panel was promised a step that did not exist. Derived from the journey's
     * RENDERER stage rather than from `dashboard_package` directly, so a blocked renderer — an uninstalled
     * foreign app, or an engine too old to render — still counts as outstanding, which it is.
     */
    /** Whether setup is waiting on a person — the one gate every first-run affordance shares. */
    private fun setupNeedsUser(): Boolean = SetupJourney.evaluate(setupJourneyInputs()).needsUser

    private fun dashboardSetupStepPending(): Boolean =
        SetupJourney.evaluate(setupJourneyInputs()).step(SetupJourney.Stage.RENDERER).status !=
            SetupJourney.Status.SATISFIED

    /** The setup / health / update banners — everything above the cards. Needs the facts map (MQTT
     *  state), so on a cold start it hydrates with the rest. */
    /**
     * The lifecycle suffix on `/health`. Appended rather than given its own endpoint because every page
     * already polls `/health` every ten seconds through `buildwatch.js`, so this needs no new route and
     * no second poll loop. Absent entirely when the panel is not watching, which keeps the line unchanged
     * for every existing consumer.
     */
    private fun haLifecycleHealthToken(): String =
        haLifecycleHealthToken(HaLifecycleRuntime.watching, HaLifecycleRuntime.snapshot())

    /**
     * The network-path tokens ride the same `/health` line and the same ten-second poll as the
     * lifecycle token, so the banner, the diagnostics row and the native chip all render one
     * observation. Empty while no service owns the monitor or no socket is held.
     */
    private fun haNetworkHealthToken(): String = HaNetworkPathRuntime.healthToken()

    private fun bannersHtml(s: Snap, h: HealthInputs, strings: AppStrings): String {
        val storage = HealthAudit.storage(storageHealth())
        val mqtt = s.facts["MQTT"] ?: "disabled"
        // Pure decision (unit-tested in SetupBannerTest) — note a CONFIGURED broker that's merely
        // mid-(re)connect must not be reported as missing.
        val needs = SetupBanner.needs(mqtt, config.mqttBroker.isNotBlank(), config.mqttUser.isNotBlank())
        val setup = if (needs.isNotEmpty())
            """<div class="setup">⚠ ${esc(strings.get("dashboard.banner.setup_needs.prefix"))} <a href="${localizedHref("configure", strings)}">${esc(localizedSetupNeeds(needs, strings))}</a> ${esc(strings.get("dashboard.banner.setup_needs.suffix"))}</div>"""
        else ""
        // Commissioning progress only while somebody is actually commissioning. `announcing` is transient but
        // recurs on every bridge reconnect — an HA restart, a broker blip, a panel waking — so on a finished
        // panel this banner kept reappearing to narrate a step that was done months ago. Reported twice from
        // deployed panels. The Configure tab keeps it unconditionally: there it is feedback for a save the user just
        // made, which is the reason it was added.
        val mqttProgress = if (!setupNeedsUser()) "" else {
            SetupBanner.progress(mqtt, config.mqttBroker.isNotBlank(), dashboardSetupStepPending(), mqttState())?.let {
                """<div class="setup">⟳ ${esc(localizedSetupProgress(it, strings))}</div>"""
            }.orEmpty()
        }
        val haSetup = if (haSignInNeededForEffectiveDashboard()) haSignInBanner(strings) else ""
        val proximityState = JSONObject(sensors.proximityJson())
        val proximityLearning = ProximityStatusBanner.titleKey(
            enabled = config.wakeOnWave,
            present = proximityState.optBoolean("present", false),
            phase = proximityState.optString("phase"),
            health = proximityState.optString("health"),
            active = proximityState.optBoolean("sessionActive", false),
            wakeReady = proximityState.optBoolean("wakeReady", false),
        )?.let { title ->
            """<div class="setup">👋 <b>${esc(strings.get(title))}</b>. """ +
                """${esc(strings.get("dashboard.banner.proximity_learning.touch_available"))} <a href="${localizedHref("configure#cfg-proximity-learning", strings)}">${esc(strings.get("dashboard.banner.proximity_learning.action"))}</a>.</div>"""
        }.orEmpty()
        // Panel-health + update findings: states that stop the panel rendering the dashboard as expected but
        // that the info map otherwise reports neutrally. Soft + best-effort — ha-paneld runs fine regardless.
        // The WebView verdict is from the REAL engine version (WebView UA), not the stamped package version
        // (cached, so cheap). Shared decision — see HealthAudit; updates are filtered by the per-version
        // dismissals so an "Ignore this version" click stays hidden until a newer release ticks it back.
        val findings = healthFindings(h, s.facts["System WebView"] ?: "", UpdateChecker.current(appContext, config.ignoredUpdates))
        // Order: storage/database safety first, then actively-broken render states, render findings
        // (WebView / renderer / updates), and finally the needs-config setup notice. On the dashboard the
        // ad-hoc warnings link to the Install tab for the fix (their one-tap buttons live there, with install.js).
        // The lifecycle banner leads: while Home Assistant is going away or coming back, that explains
        // most of what else the page is about to report.
        return localizedStorageBanner(storage, strings) + localizedPowerSafetyBanner(
            advisory = powerSafetyAdvisory(s.privilege),
            inlineRepair = true,
            strings = strings,
        ) +
            adHocWarnings(s, companionServersForRender(), inlineRepair = false, strings = strings) +
            findings.joinToString("") { bannerFor(it, strings) } + proximityLearning + haSetup + mqttProgress + setup
    }

    private fun effectiveDashboardIsBuiltin(): Boolean =
        system.resolveDashboard(config.dashboardPackage) == SystemController.BUILTIN_DASHBOARD

    // Shares haSignInPending with the renderer, so what the browser advertises as the next step and what
    // the panel actually does when it starts cannot drift apart.
    private fun haSignInNeededForEffectiveDashboard(): Boolean =
        effectiveDashboardIsBuiltin() &&
            haSignInPending(config.haUrl, config.haToken, config.haRefreshToken)

    private fun haSignInBanner(strings: AppStrings): String =
        """<div class="setup">🏠 <b>${esc(strings.get("dashboard.banner.ha_sign_in.title"))}</b> """ +
            """${esc(strings.get("dashboard.banner.ha_sign_in.explanation"))} """ +
            """<a href="${localizedHref("configure#cfg-ha-oauth", strings)}">${esc(strings.get("dashboard.banner.ha_sign_in.action"))}</a>.</div>"""

    /** Render-blocking warnings not modelled by HealthAudit: a crash-looping dashboard app, and Companion
     *  server inspection/blank-internal-URL findings — the latter only when Companion is the active renderer
     *  ([CompanionDb.warningApplies]). Shown on BOTH the dashboard
     *  banner and the Install tab as high-severity (`crit`). [inlineRepair] adds the one-tap repair button
     *  (Install tab, where install.js is loaded); the dashboard links to the Install tab for the action. */
    private fun adHocWarnings(
        management: Snap,
        companion: CompanionDb.ServerObservation?,
        inlineRepair: Boolean,
        strings: AppStrings = catalogueLoader.strings(AppLocale.ENGLISH),
    ): String = buildString {
        radioStatus()?.let { z ->
            zigbeeWarning(z)?.let { warning ->
                append(
                    """<div class="setup${if (z.state in setOf(ZigbeeHealthState.RUNAWAY, ZigbeeHealthState.CONTAINMENT_FAILED)) " crit" else ""}">""",
                )
                append(localizedZigbeeWarning(z, warning, strings))
                append("</div>")
            }
        }
        if (io.github.maxlyth.hapaneld.control.BuiltinDashboard.authLatched) append(
            """<div class="setup crit">⛔ <b>${esc(strings.get("dashboard.banner.auth_rejected.title"))}</b> — """ +
                """${esc(strings.get("dashboard.banner.auth_rejected.explanation"))} """ +
                """<a href="${localizedHref("configure#cfg-ha-oauth", strings)}">${esc(strings.get("dashboard.banner.auth_rejected.action"))}</a>; """ +
                """${esc(strings.get("dashboard.banner.auth_rejected.reload_suffix"))}</div>""",
        )
        val recoveryState = dashboardRecoveryState()
        dashboardRecoveryWarning(recoveryState)?.let { warning ->
            append("""<div class="setup crit">${localizedRecoveryWarning(recoveryState, warning, strings)}</div>""")
        }
        // Shared companion internal-URL decision (CompanionDb.warning); this surface renders it as a banner
        // with the one-tap repair button ([inlineRepair], Install tab) or an Install-tab link (dashboard).
        when (val w = CompanionDb.warning(config.dashboardPackage, companion, management.privilege.directSuReady)) {
            is CompanionDb.Warning.NeedsRepair -> {
                val action = if (inlineRepair)
                    """<div style="margin-top:10px"><button class="pbtn"${hardenedApprovalAttrs()} onclick="repairCompUrl(this)">⚙ ${esc(strings.get("dashboard.banner.companion_url.repair"))}</button> <span id="cu-fix" class="muted"></span></div>"""
                else """ <a href="${localizedHref("install", strings)}">${esc(strings.get("dashboard.banner.companion_url.install_action"))}</a>"""
                val summaryKey = if (w.affected == 1) {
                    "dashboard.banner.companion_url.summary_one"
                } else {
                    "dashboard.banner.companion_url.summary_many"
                }
                append(
                    """<div class="setup crit">⚠ <b>${esc(strings.get("dashboard.banner.companion_url.title"))}</b> """ +
                        """${esc(formattedString(strings, summaryKey, "count" to w.affected.toString()))} """ +
                        """<i>"Missing 'Host' header"</i>. ${esc(strings.get("dashboard.banner.companion_url.explanation"))}$action</div>""",
                )
            }
            CompanionDb.Warning.ProbeFailed -> append(
                """<div class="setup">⚠ <b>${esc(strings.get("dashboard.banner.companion_probe_failed.title"))}</b> — """ +
                    """${esc(strings.get("dashboard.banner.companion_probe_failed.explanation"))}</div>""",
            )
            null -> {}
        }
        // Built-in renderer zoomed off 100% (usually carried over from the Companion's "Page zoom"). App
        // zoom is a compatibility lever; the cleaner way to size the dashboard is the panel display density
        // — so we only nudge when that's actually available (rooted / helper daemon). No root = app zoom is
        // the only sizing tool, so stay quiet. densityBase comes from the shared snapshot (no su round-trip).
        val zoom = config.dashboardZoom
        if ((config.dashboardPackage.isBlank() || config.dashboardPackage == SystemController.BUILTIN_DASHBOARD) && zoom != 100 && management.densityBase != null) {
            // Reset is a plain form POST (no JS), so it works on the dashboard banner too — not just the
            // Install tab. The message already links to the Display-sizing card.
            append(
                """<div class="setup">⚠ <b>${esc(formattedString(strings, "dashboard.banner.zoom.title", "zoom" to zoom.toString()))}</b> """ +
                    """${esc(strings.get("dashboard.banner.zoom.explanation"))} """ +
                    """<a href="${localizedHref("install#cfg-display", strings)}">${esc(strings.get("dashboard.banner.zoom.display_density"))}</a>, """ +
                    """${esc(strings.get("dashboard.banner.zoom.action_suffix"))}""" +
                    """ <form method="post" action="api/v1/config" style="display:inline">""" +
                    """<input type="hidden" name="dashboard_zoom" value="100">""" +
                    """<button class="pbtn" type="submit">${esc(strings.get("dashboard.banner.zoom.reset"))}</button></form></div>""",
            )
        }
    }

    private fun zigbeeWarning(snapshot: ZigbeeHealthSnapshot): String? = zigbeeWarningText(
        snapshot,
        configuredOn = config.zigbeeRouterConfigured && config.zigbeeRouterEnabled,
    )

    /** Use only a catalogue record actually resolved in the requested locale; otherwise retain exact HTML. */
    private fun translatedText(strings: AppStrings, key: String): String? = runCatching { strings.resolve(key) }
        .getOrNull()
        ?.takeIf { it.language != AppLocale.ENGLISH }
        ?.text

    private fun localizedRecoveryWarning(
        state: PanelStatus.DashboardRecoveryState,
        fallbackHtml: String,
        strings: AppStrings,
    ): String {
        val key = when (state) {
            PanelStatus.DashboardRecoveryState.NONE -> return fallbackHtml
            PanelStatus.DashboardRecoveryState.BUILTIN_RENDERER -> "runtime.renderer_recovery.builtin"
            PanelStatus.DashboardRecoveryState.EXTERNAL_RENDERER -> "runtime.renderer_recovery.external"
        }
        return translatedText(strings, key)?.let { "⛔ ${esc(it)}" } ?: fallbackHtml
    }

    private fun localizedZigbeeWarning(
        snapshot: ZigbeeHealthSnapshot,
        fallbackHtml: String,
        strings: AppStrings,
    ): String {
        val presentation = zigbeeHealthPresentation(
            snapshot,
            config.zigbeeRouterConfigured && config.zigbeeRouterEnabled,
        ) ?: return fallbackHtml
        val key = when (presentation.code) {
            "status-zigbee-contained" -> "runtime.zigbee.warning.contained"
            "status-zigbee-containment-incomplete" -> "runtime.zigbee.warning.containment_failed"
            "status-zigbee-runaway" -> "runtime.zigbee.warning.runaway"
            "status-zigbee-high-cpu" -> "runtime.zigbee.warning.degraded_high_cpu"
            "status-zigbee-not-joined" -> "runtime.zigbee.warning.degraded_unjoined"
            "status-zigbee-legacy-watchdog" -> "runtime.zigbee.warning.legacy_watchdog"
            else -> return fallbackHtml
        }
        val translated = translatedText(strings, key) ?: return fallbackHtml
        val action = if (presentation.code == "status-zigbee-not-joined") {
            val label = translatedText(strings, "runtime.zigbee.warning.resolve")
                ?: strings.get("shell.nav.configure")
            " <a href=\"${localizedHref("configure#cfg-zigbee_join", strings)}\">${esc(label)}</a>"
        } else ""
        return "${if (presentation.code in setOf("status-zigbee-contained", "status-zigbee-containment-incomplete", "status-zigbee-runaway")) "⛔" else "⚠"} ${esc(translated)}$action"
    }

    private fun localizedStorageBanner(
        storage: HealthAudit.StoragePresentation,
        strings: AppStrings,
    ): String {
        val fallback = storage.bannerHtml()
        val presentation = storage.warningPresentation ?: return fallback
        val key = when (presentation.code) {
            "status-storage-warning" -> "install.presentation.status_storage_warning"
            "status-storage-critical" -> "install.presentation.status_storage_critical"
            "status-storage-database-failure" -> "install.presentation.status_storage_database_failure"
            else -> return fallback
        }
        val translated = translatedText(strings, key) ?: return fallback
        val rendered = presentation.params.entries.fold(translated) { text, (name, value) ->
            text.replace("{$name}", value)
        }
        val critical = presentation.code != "status-storage-warning"
        return "<div class=\"setup${if (critical) " crit" else ""}\">${esc(rendered)}</div>"
    }

    private fun localizedPowerSafetyBanner(
        advisory: PowerSafetyAdvisory,
        inlineRepair: Boolean,
        strings: AppStrings,
    ): String {
        val fallback = PowerSafetyPresentation.bannerHtml(advisory, inlineRepair)
        if (fallback.isEmpty()) return fallback
        val presentation = PowerSafetyPresentation.warningPresentation(advisory) ?: return fallback
        val levelKey = when (presentation.code) {
            "status-power-at-risk" -> "runtime.power_safety.level.at_risk"
            "status-power-caution" -> "runtime.power_safety.level.caution"
            "status-power-unknown" -> "runtime.power_safety.level.unknown"
            else -> return fallback
        }
        val level = translatedText(strings, levelKey) ?: return fallback
        val summaryKey = when (presentation.code) {
            "status-power-at-risk" -> "runtime.power_safety.summary.at_risk"
            "status-power-caution" -> "runtime.power_safety.summary.caution"
            "status-power-unknown" -> "runtime.power_safety.summary.unknown"
            else -> return fallback
        }
        val summary = translatedText(strings, summaryKey) ?: return fallback
        val actionKey = when (advisory.action) {
            PowerSafetyAdvisoryAction.NONE -> "runtime.power_safety.action.review"
            PowerSafetyAdvisoryAction.REPAIR -> when (advisory.repairCapability.wireValue) {
                "direct_root" -> "runtime.power_safety.action.repair_direct"
                "degraded" -> "runtime.power_safety.action.repair_degraded"
                else -> "runtime.power_safety.action.repair_limited"
            }
            PowerSafetyAdvisoryAction.ACKNOWLEDGE -> if (advisory.acknowledged) {
                "runtime.power_safety.action.acknowledged"
            } else {
                "runtime.power_safety.action.acknowledgeable"
            }
            PowerSafetyAdvisoryAction.MANUAL_ONLY -> "runtime.power_safety.action.manual"
        }
        val actionText = translatedText(strings, actionKey) ?: return fallback
        val control = when {
            !inlineRepair -> " <a href=\"${localizedHref("configure#cfg-keep_awake", strings)}\">${esc(strings.get("shell.nav.configure"))} →</a>"
            advisory.action == PowerSafetyAdvisoryAction.REPAIR -> {
                val label = translatedText(strings, "runtime.power_safety.button.repair") ?: "Repair power safety"
                val title = translatedText(strings, "runtime.power_safety.button.repair_title")
                    ?: "Repair is explicit, read-back verified, and never reboots the panel"
                """ <form method="post" action="api/v1/power-safety/repair" data-power-safety-repair style="display:inline"><button class="pbtn" type="submit" data-hardened-approval title="${esc(title)}">${esc(label)}</button> <span class="power-safety-repair-result" role="status" aria-live="polite"></span></form>"""
            }
            advisory.action == PowerSafetyAdvisoryAction.ACKNOWLEDGE -> {
                val fingerprint = requireNotNull(advisory.acknowledgementFingerprint)
                val label = translatedText(strings, "runtime.power_safety.button.hide") ?: "Hide this caution"
                val title = translatedText(strings, "runtime.power_safety.button.hide_title")
                    ?: "Hide this unchanged caution in panel web pages; Hardened mode requires physical approval"
                """ <form method="post" action="api/v1/power-safety/acknowledge" data-power-safety-acknowledge style="display:inline"><input type="hidden" name="fingerprint" value="${esc(fingerprint)}"><button class="pbtn" type="submit" data-hardened-approval title="${esc(title)}">${esc(label)}</button> <span class="power-safety-acknowledge-result" role="status" aria-live="polite"></span></form>"""
            }
            else -> ""
        }
        val critical = presentation.code == "status-power-at-risk"
        return "<div class=\"setup${if (critical) " crit" else ""}\" data-power-safety-banner>" +
            "${if (critical) "⛔" else "⚠"} <b>${esc(level)}</b> — ${esc(summary)} ${esc(actionText)}$control</div>"
    }

    /** One dashboard banner for a health finding. Update findings link to the Install tab (where the user
     *  manages versions) and carry an "Ignore this version" button — a per-version dismissal that stays
     *  hidden until a newer release ships (see Config.ignoreUpdate / UpdateChecker.visible). */
    private fun bannerFor(f: HealthAudit.Finding, strings: AppStrings): String = when (f.kind) {
        HealthAudit.Kind.WEBVIEW_OLD ->
            """<div class="setup crit">⚠ <b>${esc(strings.get("dashboard.banner.webview_old.title"))}</b> (${esc(f.detail)}) — """ +
                """${esc(strings.get("dashboard.banner.webview_old.explanation"))} <a href="$WEBVIEW_DOC" target="_blank" rel="noopener">""" +
                """${esc(strings.get("dashboard.banner.webview_old.update_action"))}</a> """ +
                """${esc(formattedString(strings, "dashboard.banner.webview_old.target", "version" to PanelHealth.MIN_CHROMIUM.toString()))}. """ +
                """<small>${esc(strings.get("dashboard.banner.webview_old.engine_note"))}</small> """ +
                """<a href="${localizedHref("install", strings)}">${esc(strings.get("dashboard.banner.manage_install"))}</a></div>"""
        HealthAudit.Kind.NO_RENDERER ->
            """<div class="setup">ℹ <b>${esc(strings.get("dashboard.banner.no_renderer.title"))}</b> """ +
                """${esc(strings.get("dashboard.banner.no_renderer.configure_prefix"))} <a href="${localizedHref("configure", strings)}">${esc(strings.get("shell.nav.configure"))}</a> """ +
                """${esc(strings.get("dashboard.banner.no_renderer.explanation"))} <small>${esc(strings.get("dashboard.banner.no_renderer.note"))}</small></div>"""
        HealthAudit.Kind.UPDATE -> {
            val u = f.update!!
            """<div class="setup info" data-update="${esc(u.label)}" data-version="${esc(u.latestVersion)}">""" +
                """⬆ <b>${esc(u.label)}</b> ${esc(formattedString(strings, "dashboard.banner.update.available", "latest" to u.latestVersion, "current" to u.currentVersion))} — """ +
                """<a href="${localizedHref("install", strings)}">${esc(strings.get("dashboard.banner.manage_install"))}</a> """ +
                """<button class="pbtn" onclick="ignoreUpdate(this)">${esc(strings.get("dashboard.banner.update.ignore"))}</button></div>"""
        }
        HealthAudit.Kind.SCHEMA_ROLLED_BACK ->
            """<div class="setup crit">⚠ <b>${esc(strings.get("dashboard.banner.schema_rollback.title"))}</b> (${esc(f.detail)}) — """ +
                """${esc(strings.get("dashboard.banner.schema_rollback.explanation"))} """ +
                """<a href="${localizedHref("configure", strings)}">${esc(strings.get("dashboard.banner.schema_rollback.configure_action"))}</a> """ +
                """${esc(strings.get("dashboard.banner.schema_rollback.or_restore"))} <a href="${localizedHref("install", strings)}">${esc(strings.get("shell.nav.install"))}</a>.</div>"""
    }

    /** Table rows for one facts card (Panel information / Networking / ha-paneld profile). */
    private fun factRowsHtml(s: Snap, keys: List<String>, h: HealthInputs, strings: AppStrings): String {
        val webViewTooOld = h.webView.tooOld
        return keys.filter { s.facts.containsKey(it) }.joinToString("\n") { k ->
            val v = s.facts.getValue(k)
            // Version: plain text + a compact GitHub releases icon (a hyperlinked version reads ugly).
            val cell = if (k == "ha-paneld") {
                """${esc(v)}&nbsp;<a class="gh gh-inline" href="$RELEASES_URL" target="_blank" rel="noopener" """ +
                    """title="${esc(strings.get("dashboard.fact.releases_on_github"))}" aria-label="${esc(strings.get("dashboard.fact.releases_on_github"))}"><svg viewBox="0 0 24 24"><path d="$GH_ICON"/></svg></a>"""
            } else if (k == "Display") {
                displayCell(v)
            } else if (k == "System WebView" && webViewTooOld) {
                """<span style="color:#f5c451">${esc(v)} ⚠</span>"""
            } else if (k in SECRET_FIELDS || (k in ADDRESS_FIELDS && isRoutable(v))) {
                // Blurred by default so a casual screenshot doesn't leak it; "Reveal" un-blurs (screenshot
                // hygiene, not access control — the value is still in the page source).
                """<span class="secret">${esc(v)}</span>"""
            } else {
                esc(v)
            }
            // Facts backed by a setting get the ✎ marker (configurable vs static at a glance),
            // deep-linking to the exact row on the Configure tab.
            val edit = FACT_CFG[k]?.let { cfgIcon(it, strings) } ?: ""
            "<tr><th>${esc(factLabel(k, strings))}</th><td>$cell$edit</td></tr>"
        }
    }

    // Live control states (what HA's control entities currently show) — controls, not config.
    private fun liveRowsHtml(strings: AppStrings): String {
        val led = config.lastLed.split(",").mapNotNull { it.toIntOrNull() }
        val ledShown = if (led.size == 5 && led[0] == 1) "${strings.get("dashboard.value.on")} · rgb(${led[2]},${led[3]},${led[4]}) @ ${led[1]}" else strings.get("dashboard.value.off")
        val brightness = effectiveBrightness().takeIf { it >= 0 } ?: runCatching {
            android.provider.Settings.System.getInt(appContext.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS)
        }.getOrNull()
        val brightnessShown = brightness?.coerceIn(0, 255)?.let { value ->
            "${(value * 100 + 127) / 255}% ($value)"
        } ?: "?"
        return listOf(
            strings.get("dashboard.live.screen_brightness") to brightnessShown,
            strings.get("dashboard.live.volume") to "${volume.getPercent()}%",
            strings.get("dashboard.live.navigate") to config.lastNavigate.ifEmpty { "/" },
            strings.get("dashboard.live.led") to ledShown,
        ).joinToString("\n") { (k, v) -> """<tr><th>${esc(k)}</th><td>${esc(v)}</td></tr>""" }
    }

    private fun behaviourRowsHtml(s: Snap, strings: AppStrings): String = listOf(
        "wake_on_wave", "prevent_idle_dim", "watchdog_enabled", "kiosk_lock", "touch_sound",
        "silence_boot_chime", "keep_awake", "navbar_mode", "log_ship_enabled",
        "home_dashboard", "ha_area", "dashboard_package", "launcher_package",
    ).let { keys ->
        val hints = autoHints(strings)
        val caps = liveCapabilities(s.caps)
        keys.mapNotNull { key ->
            // A deliberately overridden area must say so wherever the value is shown; at rest it is
            // otherwise indistinguishable from an adopted value.
            val areaFormatter: SettingRowFormatter? =
                if (key == "ha_area" && config.haAreaUserOverride) {
                    SettingRowFormatter.of(key) { raw ->
                        formattedString(strings, "dashboard.value.local_override", "value" to raw)
                    }
                } else {
                    null
                }
            settingRowHtml(key, s.live, caps, strings, hints, areaFormatter)
        }
    }.joinToString("\n")

    // Display and install-backed values, each deep-linking to its owning surface.
    private fun displayRowsHtml(s: Snap, strings: AppStrings): String {
        return listOf(
            "auto_brightness", "auto_brightness_minimum_percent", "auto_brightness_response_percent", "auto_brightness_ha_entity",
        ).mapNotNull { key ->
            val formatter: SettingRowFormatter? = when (key) {
                "auto_brightness_minimum_percent" -> SettingRowFormatter.of(key) { raw ->
                    raw.toIntOrNull()?.coerceIn(0, 100)?.let { percent ->
                        "$percent% (${AdaptiveLuxCurve.percentToBrightness(percent)})"
                    } ?: raw
                }
                "auto_brightness_response_percent" -> SettingRowFormatter.of(key) { raw ->
                    raw.toIntOrNull()?.coerceIn(0, 100)?.let { "$it%" } ?: raw
                }
                else -> null
            }
            settingRowHtml(key, s.live, liveCapabilities(s.caps), strings, valueFormatter = formatter)
        }
            .joinToString("\n") + "\n" + listOfNotNull(
            s.densityCur?.let { """<tr><th>${esc(strings.get("dashboard.display.logical_density"))}</th><td>$it dpi (${esc(strings.get("dashboard.display.factory_base"))} ${s.densityBase ?: "?"})${installIcon("cfg-display", strings)}</td></tr>""" },
            s.densityCur?.let { """<tr><th>${esc(strings.get("dashboard.display.text_size"))}</th><td>${s.fontScale}${installIcon("cfg-display", strings)}</td></tr>""" },
            sensors.proximitySummary().takeIf { sensors.hasProximity() }?.let {
                """<tr><th>${esc(strings.get("settings.wake_on_wave.label"))}</th><td>${esc(localizedProximitySummary(it, strings))}${cfgIcon("cfg-wake_on_wave", strings)}</td></tr>"""
            },
            """<tr><th>${esc(strings.get("dashboard.display.tamed_packages"))}</th><td>${esc(config.tameVendorPackagesRaw.ifBlank { strings.get("dashboard.value.none") })}${installIcon("cfg-tame", strings)}</td></tr>""",
        ).joinToString("\n")
    }

    private fun updatesRowsHtml(s: Snap, strings: AppStrings): String = listOf("self_update", "update_channel", "companion_auto_update")
        .mapNotNull { settingRowHtml(it, s.live, liveCapabilities(s.caps), strings) }.joinToString("\n")

    private fun capRowsHtml(capabilities: List<DiagReader.Cap>, strings: AppStrings): String {
        val capColor = mapOf("ok" to "#48c774", "degraded" to "#d9a528", "none" to "#d04a3b")
        return capabilities.joinToString("\n") { c ->
            val col = capColor[c.status] ?: "#888"
            """<tr><th>${esc(capabilityName(c.name, strings))}</th><td><span style="color:$col">●</span> ${esc(capabilityNote(c.note, strings))}</td></tr>"""
        }
    }

    private fun capabilityName(name: String, strings: AppStrings): String = when (name) {
        "Root (su)" -> strings.get("dashboard.capability.root_su")
        "Helper daemon" -> strings.get("dashboard.capability.helper_daemon")
        "Shizuku enhanced access" -> strings.get("dashboard.capability.shizuku")
        "Verified app update / screenshot / display" -> strings.get("dashboard.capability.verified_operations")
        "Screen brightness" -> strings.get("dashboard.capability.screen_brightness")
        "Screen on/off" -> strings.get("dashboard.capability.screen_power")
        "RGB LED" -> strings.get("dashboard.capability.rgb_led")
        "Hardware buttons" -> strings.get("dashboard.capability.hardware_buttons")
        "Reboot / reload / launcher" -> strings.get("dashboard.capability.system_actions")
        else -> name
    }

    private fun capabilityNote(note: String, strings: AppStrings): String = when (note) {
        "available through root or the helper daemon" -> strings.get("dashboard.capability.note.root_or_helper")
        "available through locally approved Shizuku access; app updates remain signer-verified" ->
            strings.get("dashboard.capability.note.shizuku_verified")
        "needs supported privileged panel access" -> strings.get("dashboard.capability.note.needs_privileged_access")
        "available directly to ha-paneld" -> strings.get("dashboard.capability.note.su_direct")
        "not available directly to ha-paneld — privileged actions are routed through the helper daemon" ->
            strings.get("dashboard.capability.note.helper_routed")
        "not available directly to ha-paneld — see the individual capability rows below" ->
            strings.get("dashboard.capability.note.su_unavailable")
        "WRITE_SETTINGS granted" -> strings.get("dashboard.capability.note.write_settings_granted")
        "backlight control via helper daemon; Android setting is unchanged" ->
            strings.get("dashboard.capability.note.brightness_helper")
        "backlight control via su; Android setting is unchanged" ->
            strings.get("dashboard.capability.note.brightness_su")
        "available" -> strings.get("dashboard.capability.note.available")
        "needs su or the helper daemon" -> strings.get("dashboard.capability.note.needs_su_or_helper")
        "true backlight-off via su bl_power" -> strings.get("dashboard.capability.note.backlight_off_su")
        "true backlight-off via the helper daemon" -> strings.get("dashboard.capability.note.backlight_off_helper")
        "Rockchip /dev/ledjni (app-direct, no root)" -> strings.get("dashboard.capability.note.rockchip_led_direct")
        "Rockchip /dev/ledjni ioctl via the helper daemon (root)" ->
            strings.get("dashboard.capability.note.rockchip_led_helper")
        "sysfs LED via the helper daemon" -> strings.get("dashboard.capability.note.sysfs_led_helper")
        "no reachable LED node; needs the root helper daemon (install needs su once)" ->
            strings.get("dashboard.capability.note.led_unreachable")
        "Android sleep via KEYCODE_SLEEP; Home Assistant always wakes it, a local touch only where this panel's touchscreen is a platform wake source" ->
            strings.get("dashboard.capability.note.android_sleep")
        "accessibility key capture enabled" -> strings.get("dashboard.capability.note.buttons_accessibility")
        "blocked: installed manager signer is not trusted" -> strings.get("dashboard.capability.note.shizuku_untrusted")
        "manager missing; re-run provisioning with --shizuku" -> strings.get("dashboard.capability.note.shizuku_missing")
        "ready as shell UID; local typed operations only" -> strings.get("dashboard.capability.note.shizuku_ready")
        "disabled in ha-paneld; on the panel open Configure → toolbar overflow → Enhanced access → Enable" ->
            strings.get("dashboard.capability.note.shizuku_disabled")
        "enabled in ha-paneld, but the Shizuku service is stopped; open Shizuku and start its service" ->
            strings.get("dashboard.capability.note.shizuku_stopped")
        "service running; request and approve ha-paneld access locally" ->
            strings.get("dashboard.capability.note.shizuku_permission")
        "access denied; grant ha-paneld under Shizuku → Authorized applications" ->
            strings.get("dashboard.capability.note.shizuku_grant")
        "connecting to the locally approved Shizuku service" -> strings.get("dashboard.capability.note.shizuku_connecting")
        "blocked: unexpected Shizuku service identity or protocol" -> strings.get("dashboard.capability.note.shizuku_incompatible")
        "Shizuku could not be connected; retry from the on-panel Enhanced access dialog" ->
            strings.get("dashboard.capability.note.shizuku_failed")
        else -> capabilityNoteFamily(note, strings)
    }

    private fun capabilityNoteFamily(note: String, strings: AppStrings): String {
        val preferredPrefix = "adds no capability while root or the helper daemon provides the preferred route; "
        if (note.startsWith(preferredPrefix)) {
            return strings.get("dashboard.capability.note.preferred_route_prefix") + " " +
                capabilityNote(note.removePrefix(preferredPrefix), strings)
        }
        val daemon = Regex("^(running|NEEDED but not running) — (.+)$").matchEntire(note)
        if (daemon != null) {
            val state = strings.get(
                if (daemon.groupValues[1] == "running") {
                    "dashboard.capability.note.daemon_running"
                } else {
                    "dashboard.capability.note.daemon_needed"
                },
            )
            val detail = daemon.groupValues[2]
            val translated = when {
                detail == "the privileged control path on this sandbox-walled panel; without it, root-only controls remain unavailable" ->
                    strings.get("dashboard.capability.note.daemon_sandbox_path")
                detail == "required for the profiled button backlight" ->
                    strings.get("dashboard.capability.note.daemon_button_backlight")
                detail == "required for this profile's daemon-backed hardware" ->
                    strings.get("dashboard.capability.note.daemon_hardware")
                else -> Regex("^required for (\\d+) profiled physical button\\(s\\), even though ordinary privileged actions can use su$")
                    .matchEntire(detail)
                    ?.let { match ->
                        formattedString(
                            strings,
                            "dashboard.capability.note.daemon_buttons",
                            "count" to match.groupValues[1],
                        )
                    }
                    ?: detail
            }
            return formattedString(
                strings,
                "dashboard.capability.note.daemon_state",
                "state" to state,
                "detail" to translated,
            )
        }
        val dimReason = note.removePrefix("DIM ONLY — ").takeIf { it.length != note.length }
        if (dimReason != null) {
            val reason = when (dimReason) {
                "needs su or the helper daemon to inject KEYCODE_SLEEP" ->
                    strings.get("dashboard.capability.note.dim_keycode_sleep")
                "this panel's profile selects the brightness-zero route, which never powers the backlight down" ->
                    strings.get("dashboard.capability.note.dim_brightness_zero")
                "the backlight stays powered; needs su or the helper daemon for a real off" ->
                    strings.get("dashboard.capability.note.dim_backlight_powered")
                else -> dimReason
            }
            return formattedString(strings, "dashboard.capability.note.dim_only", "reason" to reason)
        }
        if (note.startsWith("needs WRITE_SETTINGS: ")) {
            return formattedString(
                strings,
                "dashboard.capability.note.needs_write_settings",
                "command" to note.removePrefix("needs WRITE_SETTINGS: "),
            )
        }
        if (note.startsWith("enable (no root): ")) {
            return formattedString(
                strings,
                "dashboard.capability.note.buttons_enable_no_root",
                "command" to note.removePrefix("enable (no root): "),
            )
        }
        val buttonPatterns = listOf(
            Regex("^accessibility key capture plus (\\d+) profiled physical button\\(s\\) on a verified helper stream$") to
                "dashboard.capability.note.buttons_accessibility_verified",
            Regex("^(\\d+) profiled physical button\\(s\\) on a verified helper stream$") to
                "dashboard.capability.note.buttons_verified",
            Regex("^accessibility key capture plus (\\d+) profiled physical button\\(s\\) on an unverified stream \\((.+)\\)$") to
                "dashboard.capability.note.buttons_accessibility_unverified_stream",
            Regex("^(\\d+) profiled physical button\\(s\\) are streaming but unverified \\((.+)\\)$") to
                "dashboard.capability.note.buttons_unverified_stream",
            Regex("^accessibility key capture works; (\\d+) profiled physical button\\(s\\) are not verified \\((.+)\\)$") to
                "dashboard.capability.note.buttons_accessibility_not_verified",
            Regex("^(\\d+) profiled physical button\\(s\\) are not verified \\((.+)\\)$") to
                "dashboard.capability.note.buttons_not_verified",
        )
        buttonPatterns.forEach { (pattern, key) ->
            pattern.matchEntire(note)?.let { match ->
                val values = mutableListOf("count" to match.groupValues[1])
                if (match.groupValues.size > 2) values += "detail" to match.groupValues[2]
                return formattedString(strings, key, *values.toTypedArray())
            }
        }
        return note
    }

    /** Visible "this needs root" banner for a root-gated card/control group — shown (never hidden) so a
     *  no-root user sees the feature and what root would unlock, next to controls rendered disabled. */
    private fun rootLockBanner(unlocks: String, strings: AppStrings): String =
        """<div class="setup rootlock">🔒 ${esc(formattedString(strings, "install.lock.root_required", "detail" to unlocks))}</div>"""

    private fun privilegedLockBanner(unlocks: String, strings: AppStrings): String =
        """<div class="setup rootlock">🔒 ${esc(formattedString(strings, "install.lock.privileged_required", "detail" to unlocks))}</div>"""

    /** The Controls-card button rows. [s] null (cold shell) → everything disabled as "checking…";
     *  hydration swaps in the capability-gated real state. */
    private fun controlsHtml(s: Snap?, strings: AppStrings): String {
        // Controls buttons: render but DISABLE (not hide, not silently-broken) when the action's capability
        // is missing — back/recents accept Accessibility or Shizuku input; launcher/reboot need root.
        val a11yOk = s?.facts?.get("Nav actions (a11y)") == "yes"
        val navigation = ControlAvailability.navigation(
            accessibilityReady = a11yOk,
            shizukuReady = s?.privilege?.shizuku?.ready == true,
            hasRecents = profile.hasRecents,
        )
        // Recents is only real where the firmware has an overview screen — KEYCODE_APP_SWITCH no-ops on
        // single-purpose panels, so the policy gates it on the profile rather than show a dead one.
        val rootOk = s?.privilege?.rootControlReady == true
        val checking = s == null
        fun pbtn(
            action: String,
            label: String,
            ok: Boolean,
            needsKey: String,
            style: String = "",
            disabledTitle: String? = null,
        ): String {
            val disabledReason = when {
                checking -> strings.get("dashboard.controls.checking_capabilities")
                !ok -> strings.get(needsKey)
                disabledTitle != null -> disabledTitle
                else -> null
            }
            return dashboardControlButtonHtml(action, label, disabledReason, style)
        }
        // "Launcher" opens the best real home-screen launcher; "Admin launcher" always opens ha-paneld's
        // own. When no separate launcher exists (e.g. the vendor kiosk is tamed), "Launcher" would just
        // fall through to the admin launcher — so DISABLE it rather than show two buttons that do the same
        // thing. resolvedLauncher() is a cheap PackageManager query (no root).
        val hasDistinctLauncher = !checking &&
            (system.resolvedLauncher(config.launcherPackage)?.let { it != appContext.packageName } == true)
        // Launcher / Admin launcher / Reboot need root; a disabled button's tooltip is invisible on a
        // touch panel, so add a visible note that accurately reflects the remaining navigation routes.
        val rootNote = if (!checking && !rootOk)
            """<div class="setup rootlock" style="margin:0 0 8px">🔒 ${esc(strings.get("dashboard.controls.root_required_note"))}</div>""" else ""
        return """$rootNote<div class="ctlrow">
 ${pbtn("back", "←<span class=\"lbl\"> ${esc(strings.get("dashboard.controls.back"))}</span>", navigation.backEnabled, "dashboard.controls.input_required")}
 ${pbtn("recents", "▢<span class=\"lbl\"> ${esc(strings.get("dashboard.controls.recents"))}</span>", navigation.recentsEnabled, "dashboard.controls.input_required")}
 ${pbtn("launcher", "⊞<span class=\"lbl\"> ${esc(strings.get("dashboard.controls.launcher"))}</span>", rootOk, "dashboard.controls.root_required", "margin-left:auto", disabledTitle = if (hasDistinctLauncher) null else strings.get("dashboard.controls.no_separate_launcher"))}
 ${pbtn("admin_launcher", "⚙<span class=\"lbl\"> ${esc(strings.get("dashboard.controls.admin_launcher"))}</span>", rootOk, "dashboard.controls.root_required")}
</div>
<div class="ctlrow ctlrow-secondary">
 ${pbtn("dashboard", "⌂<span class=\"lbl\"> ${esc(strings.get("dashboard.controls.dashboard"))}</span>", !checking, "dashboard.controls.unavailable")}
 ${pbtn("reload", "↻ ${esc(strings.get("dashboard.controls.reload"))}", !checking, "dashboard.controls.unavailable", "border-color:#7a6330;color:#f5cf82")}
 ${pbtn("reboot", "⟳ ${esc(strings.get("dashboard.controls.reboot"))}", rootOk, "dashboard.controls.root_required", "margin-left:auto;border-color:#7a3a2a;color:#f5a08a")}
</div>"""
    }

    /** Hydration payload for the dashboard: ready-to-inject HTML fragments, rendered by the same
     *  functions as the warm server render so the two paths can't drift. Builds the snapshot (this
     *  is where the probe cost actually lands — once per TTL). */
    private fun infoJson(strings: AppStrings): String {
        val s = snapCache.get()
        // One health snapshot for this render — the banner, facts card and diagnostics rows below all read
        // the same WebView/renderer verdict rather than each re-probing (which could otherwise disagree).
        val h = healthInputs()
        val cards = listOf(
            "livetbl" to liveRowsHtml(strings),
            "behavtbl" to behaviourRowsHtml(s, strings),
            "disptbl" to displayRowsHtml(s, strings),
            "updtbl" to updatesRowsHtml(s, strings),
            "infotbl" to factRowsHtml(s, infoKeys(s), h, strings),
            "nettbl" to factRowsHtml(s, NET_KEYS, h, strings),
            "proftbl" to factRowsHtml(s, profileFactKeys(profile, s.facts), h, strings),
            "contexttbl" to contextRowsHtml(s, h, strings),
            "captbl" to capRowsHtml(s.capabilityRows, strings),
        ).joinToString(",") { (k, v) -> "\"$k\":${jsonStr(v)}" }
        return """{"banners":${jsonStr(bannersHtml(s, h, strings))},"shot":${s.privilege.typedShellControlReady},"shotCached":${jsonStr(screenshotPlaceholderUrl() ?: "")},"versionCode":${BuildConfig.VERSION_CODE},"package":${jsonStr(BuildConfig.APPLICATION_ID)},"controls":${jsonStr(controlsHtml(s, strings))},"cards":{$cards}}"""
    }

    private fun infoHtml(strings: AppStrings, embed: EmbedMode? = null): String {
        // Stale-while-revalidate: render the last-known snapshot instantly (placeholders if none yet)
        // and let the page hydrate/refresh from /api/v1/info when the snapshot is missing or old.
        val s = snapCache.peek()
        // One health snapshot shared by every warm branch below (banner + facts + diagnostics), captured
        // lazily so a cold shell (s == null, nothing rendered warm) still probes nothing.
        val h: HealthInputs by lazy(LazyThreadSafetyMode.NONE) { healthInputs() }
        val hydrate = s == null || snapCache.ageMs() > SNAP_TTL_MS
        val placeholder = """<tr><td style="color:#888">${esc(strings.get("dashboard.status.reading"))}</td></tr>"""
        // One facts/value card: cold → placeholder rows (hydration fills or hides); warm → rows, and
        // an EMPTY card is omitted exactly as before.
        fun tcard(id: String, title: String, rows: String?, pre: String = "", post: String = ""): String = when {
            rows == null -> """<div class="card" data-layout-key="$id"><h2>${esc(title)}</h2>$pre<table id="$id">$placeholder</table>$post</div>"""
            rows.isBlank() -> ""
            else -> """<div class="card" data-layout-key="$id"><h2>${esc(title)}</h2>$pre<table id="$id">$rows</table>$post</div>"""
        }
        val profileReferences = profile.profileLinks.joinToString(" · ") { link ->
            val host = runCatching { java.net.URI(link.url).host }.getOrNull().orEmpty()
            val destination = host.takeIf { it.isNotBlank() }
                ?.let { """ · <bdi class="profile-reference-host" dir="ltr">${esc(it)}</bdi>""" }
                .orEmpty()
            """<a href="${esc(link.url)}" target="_blank" rel="noopener noreferrer" referrerpolicy="no-referrer"><bdi class="profile-reference-label" dir="auto">${esc(link.label)}</bdi>$destination</a>"""
        }.takeIf { it.isNotBlank() }?.let { """<br><span class="profile-reference-links">$it</span>""" }.orEmpty()
        val profNote = """<p class="note">${esc(strings.get("dashboard.profile_note.prefix"))} <a href="$DEVICE_PROFILES_DOC" target="_blank" rel="noopener" style="color:#9cf">${esc(strings.get("dashboard.profile_note.link"))}</a>.$profileReferences</p>"""
        val capNote = """<p class="note"><a href="api/v1/diag" target="_blank" style="color:#9cf">⭳ ${esc(strings.get("dashboard.diagnostics_dump.link"))}</a> — ${esc(strings.get("dashboard.diagnostics_dump.explanation"))}</p>"""
        // A cold shell can safely show the app-private last-successful capture before the capability
        // probes finish. It must not request a new capture until hydration confirms a privileged route.
        val cachedShot = screenshotPlaceholderUrl()
        val shotTitle = """<h2>${esc(strings.get("dashboard.card.screenshot"))} <small>· ${esc(strings.get("dashboard.card.live_panel"))}</small><a class="card-title-action" href="#" onclick="refreshScreenshot(this.closest('.card'));return false" title="${esc(strings.get("dashboard.screenshot.capture_title"))}">↻ ${esc(strings.get("dashboard.action.refresh"))}</a></h2>"""
        val shotInner = { src: String? ->
            val source = src?.let { """src="${esc(it)}"""" } ?: ""
            """<a class="shot" href="api/v1/screenshot.png" target="_blank" rel="noopener" title="${esc(strings.get("dashboard.screenshot.open_full_size"))}" data-error-label="${esc(strings.get("dashboard.screenshot.unavailable"))}" style="aspect-ratio:${screenAspectRatio()}"><img $source alt="${esc(strings.get("dashboard.screenshot.alt"))}" onload="this.parentElement.classList.add('loaded')" onerror="this.parentElement.classList.add('failed')"></a>"""
        }
        val shotCard = when {
            s == null && cachedShot != null ->
                """<div class="card" id="shotcard" data-layout-key="screenshot" data-capture-ok="0">$shotTitle${shotInner(cachedShot)}</div>"""
            s == null ->
                """<div class="card" id="shotcard" data-layout-key="screenshot" data-capture-ok="0" style="display:none">$shotTitle${shotInner(null)}</div>"""
            s.privilege.typedShellControlReady ->
                """<div class="card" id="shotcard" data-layout-key="screenshot" data-capture-ok="1">$shotTitle${shotInner(cachedShot)}</div>"""
            else -> ""
        }
        // The camera card is a live measurement surface, so the server renders the shell and nothing
        // else: rows written here would be a reading from page-render time that the card could not
        // retract, which is the defect the lifecycle banner was deleted for. The poll owns every row.
        // A board whose profile declares no camera gets no card rather than an empty one — the same
        // rule the Camera row in Runtime diagnostics already follows.
        val cameraCard = if (camera.presentation().state == CameraState.ABSENT) "" else
            """<div class="card" data-layout-key="camera-stream"><h2>${esc(strings.get("dashboard.camera.title"))} <small id="camhdr"></small></h2>
<table id="camtbl"><tr><td style="color:#888">${esc(strings.get("dashboard.status.reading"))}</td></tr></table>
<p class="note">${esc(strings.get("dashboard.camera.note"))} ${esc(strings.get("dashboard.camera.settings_on"))} <a href="${localizedHref("configure", strings)}">${esc(strings.get("dashboard.camera.configure_link"))}</a>.</p></div>"""
        val infoHaLink = if (config.haLinkUrl.isNotBlank())
            """<a class="pbtn" href="${esc(config.haLinkUrl)}" target="_blank" rel="noopener" title="${esc(strings.get("dashboard.open_in_ha.title"))}">${esc(strings.get("shell.open_in_ha"))}</a>""" else ""
        val revealBtn = """<button id="revbtn" class="pbtn" onclick="toggleReveal()" title="${esc(strings.get("dashboard.reveal.title"))}">${esc(strings.get("dashboard.action.reveal"))}</button>"""
        return pageShell(
            active = "dashboard",
            sectionTitle = null,
            bodyAttrs = """data-ver="${Config.VERSION}" data-build="${buildToken()}" data-cfg="${renderConfigConcurrencyHash()}" data-hydrate="${if (hydrate) "1" else "0"}" data-hardened="${if (config.hardenedSecurityEnabled) "1" else "0"}"""",
            rightControls = "$infoHaLink$revealBtn ${ghLink(strings)}",
            embed = embed,
            extraScripts = """<script src="assets/card-size-memory.js"></script>
<script src="assets/card-column-alignment.js"></script>
<script src="info.js"></script>
""",
            body = """<div id="bannerzone">${s?.let { bannersHtml(it, h, strings) } ?: ""}</div>
<div class="cards" id="dashboard-cards" data-card-size-page="dashboard" data-card-size-epoch="1" data-card-size-restore="1">
<div class="card" data-layout-key="controls"><h2>${esc(strings.get("dashboard.card.controls"))} <small>· ${esc(strings.get("dashboard.card.software_nav_bar"))}</small></h2>
<div id="ctlzone">${controlsHtml(s, strings)}</div></div>
${tcard("infotbl", strings.get("dashboard.card.panel_information"), s?.let { factRowsHtml(it, infoKeys(it), h, strings) })}
$shotCard
${tcard("nettbl", strings.get("dashboard.card.networking"), s?.let { factRowsHtml(it, NET_KEYS, h, strings) })}
${tcard("proftbl", strings.get("dashboard.card.profile"), s?.let { factRowsHtml(it, profileFactKeys(profile, it.facts), h, strings) }, post = profNote)}
${tcard("contexttbl", strings.get("dashboard.card.runtime_diagnostics"), s?.let { contextRowsHtml(it, h, strings) })}
${tcard("captbl", strings.get("dashboard.card.capabilities"), s?.let { capRowsHtml(it.capabilityRows, strings) }, post = capNote)}
<div class="card" data-layout-key="responsiveness"><h2>${esc(strings.get("dashboard.card.responsiveness"))} <small id="smhdr"></small></h2>
<canvas id="respchart" width="600" height="150" style="height:150px"></canvas>
<div class="leg"><span style="color:#d04a3b">▬</span> ${esc(strings.get("dashboard.chart.interaction_latency"))}&nbsp;&nbsp;<span style="color:#4a9eff">▬</span> ${esc(strings.get("dashboard.chart.state_updates"))}&nbsp;&nbsp;<span style="color:#f5a623">▬</span> ${esc(strings.get("dashboard.chart.main_thread_blocking"))} · ~4 min</div>
<table id="smtbl"><tr><td style="color:#888">${esc(strings.get("dashboard.status.measuring"))}</td></tr></table></div>
<div class="card" data-layout-key="ha-state-stream"><h2>${esc(strings.get("dashboard.card.ha_state_stream"))} <small>· ${esc(strings.get("dashboard.card.builtin_renderer"))}</small></h2>
<table id="streamtbl"><tr><td style="color:#888">${esc(strings.get("dashboard.status.waiting_state_traffic"))}</td></tr></table>
<table class="dt" id="noisyentities"><tr><td style="color:#888">${esc(strings.get("dashboard.status.waiting_entity_contributors"))}</td></tr></table>
<p class="note">${esc(strings.get("dashboard.ha_stream.note"))} <a href="${localizedHref("entities", strings)}">${esc(strings.get("dashboard.ha_stream.open_diagnostics"))}</a>.</p></div>
<div class="card" data-layout-key="sensors"><h2>${esc(strings.get("dashboard.card.sensors"))} <small id="sensage"></small></h2>
<table id="senstbl"><tr><td style="color:#888">${esc(strings.get("dashboard.status.reading"))}</td></tr></table>
<p class="note">${esc(strings.get("dashboard.sensors.note"))}</p></div>
$cameraCard
<div class="card" data-layout-key="performance"><h2>${esc(strings.get("dashboard.card.performance"))} <small id="perfage"></small></h2>
<div style="color:#666;font-size:.78rem;margin-bottom:8px">${esc(strings.get("dashboard.performance.samples_note"))}</div>
<canvas id="perfchart" width="600" height="96" style="height:96px"></canvas>
<div class="leg"><span style="color:#4a9eff">■</span> CPU&nbsp;&nbsp;<span style="color:#48c774">■</span> RAM&nbsp;&nbsp;<span style="color:#f5a623">■</span> GPU (${esc(strings.get("dashboard.chart.percent_used"))}) · ~4&nbsp;min</div>
<table id="perf"><tr><td style="color:#888">${esc(strings.get("dashboard.status.sampling"))}</td></tr></table></div>
<div class="card" data-layout-key="top-processes"><h2>${esc(strings.get("dashboard.card.top_processes"))} <span class="top-process-modes" role="group" aria-label="${esc(strings.get("dashboard.processes.rank_by"))}"><button type="button" class="top-process-mode on" data-mode="cpu" aria-pressed="true" onclick="setTopMode('cpu')">CPU</button><button type="button" class="top-process-mode" data-mode="ram" aria-pressed="false" onclick="setTopMode('ram')">RAM</button></span></h2>
<table class="dt" id="topproc"><tr><td style="color:#888">${esc(strings.get("dashboard.status.top_processes"))}</td></tr></table></div>
<div class="card" data-layout-key="remote-webview"><h2>${esc(strings.get("dashboard.card.remote_webview"))} <small id="insthdr"></small></h2>
<div style="display:flex;gap:8px;margin-bottom:4px">
 <button id="inspstart" type="button" class="pbtn" onclick="inspStart()"${if (config.hardenedSecurityEnabled) " disabled title=\"${esc(strings.get("dashboard.remote_webview.hardened_unavailable"))}\"" else ""}>${esc(strings.get("dashboard.action.enable"))}</button>
 <button type="button" class="pbtn" onclick="inspStop()">${esc(strings.get("dashboard.action.stop"))}</button></div>
<p class="note" id="insthint"></p></div>
${tcard("livetbl", strings.get("dashboard.card.live_state"), if (s == null) null else liveRowsHtml(strings), pre = """<p class="note">${esc(strings.get("dashboard.live_state.note"))}</p>""")}
${tcard("behavtbl", strings.get("dashboard.card.behaviour"), s?.let { behaviourRowsHtml(it, strings) })}
${tcard("disptbl", strings.get("dashboard.card.display_tuning"), s?.let { displayRowsHtml(it, strings) })}
${tcard("updtbl", strings.get("dashboard.card.updates"), s?.let { updatesRowsHtml(it, strings) })}
</div>
<p class="note" style="text-align:center;margin-top:18px"><a href="${localizedHref("api", strings)}" style="color:#9cf">${esc(strings.get("dashboard.footer.api_explorer"))}</a>
 · <a href="api/v1/diag" target="_blank" style="color:#9cf">${esc(strings.get("dashboard.footer.diagnostics"))}</a> · <a href="$REPO_URL" target="_blank" rel="noopener" style="color:#9cf">GitHub</a></p>""",
            strings = strings,
            translationPrefixes = setOf("shell.", "dashboard.", "runtime."),
        )
    }

    private fun esc(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** JSON-quote a string value (escapes backslash + double-quote). */
    private fun jsonStr(s: String): String = Json.str(s)

    /**
     * Standalone "Vendor packages" card. Taming intrusive firmware apps is a distinct, deploy-time concept
     * — not part of basic configuration — so it gets its own card with **per-package action buttons**, not
     * a checkbox list behind a shared Save (which made "did it apply?" and "how do I remove one?" unclear).
     * Each row acts immediately via `POST /tame`: an active app offers **Tame**, a tamed/disabled one offers
     * **Re-enable**. A free-text box tames any package by name. Hidden where no privileged path exists (taming
     * needs root or the helper daemon). Critical / HA / own packages are never listed.
     */
    /** One Vendor-packages row: label + package id, an optional state badge, and the single action button.
     *  Shared by the card and the picker. [showState] is false on the card — every row there is already
     *  tamed (disabled), so the column is redundant and just crowds the layout. */
    private fun localizedTameGroupTitle(title: String, strings: AppStrings): String = when (title) {
        "Recommended for this panel" -> strings.get("install.tame.group.recommended")
        "Other apps" -> strings.get("install.tame.group.other")
        "Using the most CPU" -> strings.get("install.tame.group.cpu")
        else -> title
    }

    private fun localizedTameGroupHint(hint: String, strings: AppStrings): String = when (hint) {
        "Known intrusive firmware apps for your hardware — safe first picks." ->
            strings.get("install.tame.group.recommended_hint")
        "Apps on this panel that aren't part of core Android." -> strings.get("install.tame.group.other_hint")
        "Top CPU users right now. Core/system ones are shown for context but can't be disabled; only tame a vendor app you recognise." ->
            strings.get("install.tame.group.cpu_hint")
        else -> hint
    }

    private fun tameRowHtml(
        c: TameController.Candidate,
        showState: Boolean = true,
        disabled: Boolean = false,
        strings: AppStrings = catalogueLoader.strings(AppLocale.ENGLISH),
    ): String {
        val tamed = c.blocked || c.disabled
        val state = if (!showState) "" else when {
            !c.installed -> """<span style="width:80px;text-align:right;font-size:.85em;color:var(--dim)">${esc(strings.get("install.shared.not_installed"))}</span>"""
            c.disabled -> """<span style="width:80px;text-align:right;font-size:.85em;color:#d9a528">${esc(strings.get("install.tame.state.disabled"))}</span>"""
            else -> """<span style="width:80px;text-align:right;font-size:.85em;color:#3fb950">${esc(strings.get("install.tame.state.active"))}</span>"""
        }
        val action = if (tamed) "untame" else "tame"
        val label = strings.get(if (tamed) "install.tame.action.reenable" else "install.tame.action.tame")
        val btn = if (tamed) "" else "background:#7a2e2e;border-color:#7a2e2e"
        // Tags (authored or heuristic: core/vendor/user/overlay) after the label; note below the package id.
        val tags = c.tags.joinToString("") { tag ->
            val localized = when (tag.lowercase(java.util.Locale.ROOT)) {
                "core" -> strings.get("install.tame.tag.core")
                "vendor" -> strings.get("install.tame.tag.vendor")
                "user" -> strings.get("install.tame.tag.user")
                "overlay" -> strings.get("install.tame.tag.overlay")
                else -> tag
            }
            """<span class="vtag">${esc(localized)}</span>"""
        }
        // A "recommended" badge marks the profile's defaultTame picks (safe first picks / the "Tame all
        // recommended" set) while they're still active.
        val recBadge = if (c.recommended && !tamed)
            """<span class="vtag rec">${esc(strings.get("install.tame.badge.recommended"))}</span>""" else ""
        val note = if (c.note.isNotBlank())
            """<br><small style="color:#9aa">${esc(c.note)}</small>""" else ""
        // A non-removable package (core Android / dashboard / ourselves) is shown for context with a muted
        // "protected" label where the action button would be — no way to disable it.
        val control = if (!c.removable)
            """<span style="font-size:.8em;color:#777;white-space:nowrap">${esc(strings.get("install.tame.state.protected"))}</span>"""
        else
            """<form method="post" action="${localizedHref("api/v1/tame", strings)}" style="margin:0"><input type="hidden" name="pkg" value="${esc(c.pkg)}"><input type="hidden" name="action" value="$action"><button type="submit"${hardenedApprovalA11yAttrs(strings = strings)} style="$btn;white-space:nowrap"${if (disabled) " disabled" else ""}>${esc(label)}</button></form>"""
        return """  <div style="display:flex;align-items:center;gap:10px;padding:9px 0;border-top:1px solid #222">
   <span style="flex:1;min-width:0;overflow:hidden">${esc(c.label)}$recBadge$tags<br><small style="color:#888">${esc(c.pkg)}</small>$note</span>
   $state
   $control
  </div>"""
    }

    private fun tameCardHtml(rootReady: Boolean, strings: AppStrings): String {
        // Root-gated, but shown (never hidden) so a no-root user sees the feature: the profile's candidate
        // vendor apps are listed greyed with a lock banner, actions disabled. Discovery (PackageManager)
        // needs no root; the tame/re-enable ACTIONS do.
        val locked = !rootReady
        // The card shows what's currently TAMED (the blocklist); discovery lives in the Find-a-package
        // picker. So a tamed package always has a visible Re-enable here. When locked, fall back to the
        // profile's candidate list so there's something to show.
        val cands = runCatching {
            tame.cardCandidates(config.tameVendorPackages, tameProfileCandidates)
        }.getOrDefault(emptyList())
        val rows = cands.joinToString("\n") { tameRowHtml(it, showState = false, disabled = locked, strings = strings) }
        val body = when {
            locked -> """<div class="locked">${rows.ifBlank { """<p class="note">${esc(strings.get("install.tame.locked_empty"))}</p>""" }}</div>"""
            else -> rows.ifBlank {
                """<p class="note">${esc(strings.get("install.tame.empty"))}</p>"""
            }
        }
        val dis = if (locked) " disabled" else ""
        val lock = if (locked) rootLockBanner(strings.get("install.tame.root_required"), strings) else ""
        val titleText = esc(strings.get("install.card.vendor_packages"))
        val title = if (!locked) hardenedApprovalCardTitle(titleText, conditional = true, strings = strings)
            else "<h2>$titleText</h2>"
        return """<div class="card" id="cfg-tame" data-layout-key="vendor-packages">$title
$lock<p class="note">${esc(strings.get("install.tame.description"))}</p>
$body
<div style="display:flex;flex-direction:column;gap:8px;margin-top:12px" class="${if (locked) "locked" else ""}">
 <button type="button" onclick="pkgPick()"$dis>${esc(strings.get("install.tame.find"))}</button>
 <form method="post" action="${localizedHref("api/v1/tame", strings)}" style="display:grid;grid-template-columns:1fr auto;gap:8px;margin:0">
  <label for="tame-pkg" style="grid-column:1/-1">${esc(strings.get("install.tame.package_name"))}</label>
  <input id="tame-pkg" name="pkg" autocapitalize="none" autocorrect="off" spellcheck="false" required pattern="[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)*" maxlength="255" aria-describedby="tame-pkg-hint" placeholder="io.example.app" style="min-width:0"$dis oninput="updateTamePackageSubmit()">
  <input type="hidden" name="action" value="tame">
  <button id="tame-package-submit" type="submit"${hardenedApprovalA11yAttrs(strings = strings)}$dis>${esc(strings.get("install.tame.action.tame"))}</button>
  <small id="tame-pkg-hint" class="note" style="grid-column:1/-1">${esc(strings.get("install.tame.package_hint"))}</small>
 </form>
</div>
<div id="hand-back-home" style="margin-top:16px;padding-top:12px;border-top:1px solid #222">
 <h3 style="margin:0 0 4px">${esc(strings.get("install.tame.hand_back.title"))}</h3>
 <p class="note" style="margin:0 0 4px">${esc(strings.get("install.tame.hand_back.description"))}</p>
 <p class="note" style="margin:0 0 4px"><strong>${esc(strings.get("install.tame.hand_back.warning"))}</strong></p>
 <p class="note" style="margin:0 0 8px">${esc(strings.get("install.tame.hand_back.scope"))}</p>
 <button id="hand-back-home-button" type="button" onclick="handBackHome()"${hardenedApprovalA11yAttrs(strings = strings)}$dis>${esc(strings.get("install.tame.hand_back.action"))}</button>
 <p id="hand-back-home-status" class="note" role="status" aria-live="polite" style="margin:8px 0 0"></p>
</div>
<dialog id="pkgdlg" style="background:#1a1a1a;color:#eee;border:1px solid #333;border-radius:12px;max-width:520px;width:92%;padding:16px">
 <h3 data-hardened-approval="conditional" aria-describedby="hardened-approval-section-conditional-description" title="${esc(strings.get("shell.hardened.section_conditional"))}" style="margin:0 0 4px">${esc(strings.get("install.tame.dialog.title"))}</h3>
 <p class="note" style="margin:0 0 8px">${esc(strings.get("install.tame.dialog.description"))}</p>
 <div id="pkgdlgbody" style="max-height:55vh;overflow:auto">${esc(strings.get("install.shared.loading"))}</div>
 <form method="dialog" style="margin-top:12px;text-align:right"><button>${esc(strings.get("install.shared.close"))}</button></form>
</dialog>
<script>function pkgPick(){var d=document.getElementById('pkgdlg');d.showModal();
document.getElementById('pkgdlgbody').textContent=${jsonStr(strings.get("install.shared.loading"))};
fetch(${jsonStr(localizedHref("api/v1/tame/suggest", strings))}).then(function(r){return r.text()}).then(function(t){document.getElementById('pkgdlgbody').innerHTML=t}).catch(function(){document.getElementById('pkgdlgbody').textContent=${jsonStr(strings.get("install.tame.dialog.list_failed"))};});}
function updateTamePackageSubmit(){var input=document.getElementById('tame-pkg'),button=document.getElementById('tame-package-submit');if(!input||!button)return;button.disabled=input.disabled||!input.checkValidity();}updateTamePackageSubmit();
function handBackHome(){var b=document.getElementById('hand-back-home-button'),s=document.getElementById('hand-back-home-status');if(!b||!s)return;b.disabled=true;s.textContent=${jsonStr(strings.get("install.shared.loading"))};
fetch(${jsonStr(localizedHref("api/v1/hand-back-home", strings))},{method:'POST'}).then(function(r){return r.json().then(function(j){return {status:r.status,body:j}})}).then(function(r){
if(r.status===200&&r.body&&r.body.home_handed_to){s.textContent=${jsonStr(strings.get("install.tame.hand_back.done"))};return;}
b.disabled=false;s.textContent=${jsonStr(strings.get("install.tame.hand_back.failed"))};
}).catch(function(){b.disabled=false;s.textContent=${jsonStr(strings.get("install.tame.hand_back.failed"))};});}</script></div>"""
    }

    /** Display-sizing card (density + text scale). Empty when su isn't reachable (no control). */
    private fun displayCardHtml(
        typedShellReady: Boolean,
        sizing: DisplaySizingObservation,
        strings: AppStrings,
    ): String {
        // The POST primes densityCache, so peek preserves immediate post-write values without turning
        // Install rendering into another privileged probe. Cold startup already populated the snapshot.
        val (curOverride, base, fs) = sizing
        // Shown even without root (density can't be READ without it either) so a no-root user sees the
        // feature — but greyed, with a lock banner, and every control disabled. `dis` toggles all of it.
        val locked = !typedShellReady
        // Prefill: the active override if one is set, else the profile's HA-optimised recommendation
        // (so a fresh panel offers the right value to Apply rather than the factory base), else the base.
        val cur = curOverride?.takeIf { it != base } ?: recommendedDensity ?: base ?: DensityController.MIN_DPI
        val densityHint = recommendedDensity?.let { formattedString(strings, "install.display.profile_recommendation", "value" to it.toString()) }
            ?: formattedString(strings, "install.display.firmware_default", "value" to (base?.toString() ?: "?"))
        val resetTitle = base?.let { formattedString(strings, "install.display.reset_default_with_dpi", "value" to it.toString()) }
            ?: strings.get("install.display.reset_default")
        val dis = if (locked) " disabled" else ""
        val rec = if (!locked && (recommendedDensity != null || recommendedFontScale != null))
            """ <button type="submit" name="action" value="rec"${hardenedApprovalA11yAttrs(strings = strings)} formnovalidate>${esc(strings.get("install.display.ha_optimised"))}</button>""" else ""
        val lock = if (locked) privilegedLockBanner(strings.get("install.display.root_required"), strings) else ""
        val badge = """<span class="cardbadge exp">${esc(strings.get("install.display.badge.experimental"))}</span>"""
        val title = if (!locked) hardenedApprovalCardTitle(esc(strings.get("install.display.title")), badge, strings = strings) else "<h2>${esc(strings.get("install.display.title"))}$badge</h2>"
        return """<div class="card" id="cfg-display" data-layout-key="display-sizing">$title
$lock<p class="note">${esc(strings.get("install.display.description"))}</p>
<form method="post" action="${localizedHref("api/v1/display/density", strings)}" class="${if (locked) "locked" else ""}" style="display:flex;flex-direction:column;gap:10px">
 <label style="display:flex;flex-direction:row;justify-content:space-between;align-items:center;gap:12px">
  <span>${esc(strings.get("install.display.logical_density"))} <small style="color:#888">· ${esc(densityHint)}</small></span>
  <input name="density" type="number" min="${DensityController.MIN_DPI}" max="${DensityController.MAX_DPI}" value="$cur" style="width:96px"$dis>
 </label>
 <label style="display:flex;flex-direction:row;justify-content:space-between;align-items:center;gap:12px">
  <span>${esc(strings.get("install.display.text_size"))} <small style="color:#888">· ${esc(strings.get("install.display.default_scale"))}</small></span>
  <input name="font" type="number" step="0.05" min="${DensityController.MIN_FONT}" max="${DensityController.MAX_FONT}" value="$fs" style="width:96px"$dis>
 </label>
 <div style="display:flex;gap:8px;flex-wrap:wrap;margin-top:2px">
  <button type="submit"${hardenedApprovalA11yAttrs(strings = strings)}$dis>${esc(strings.get("install.display.apply"))}</button>$rec
  <button type="submit" name="action" value="reset" aria-describedby="hardened-approval-description" formnovalidate title="${esc(resetTitle)} · ${esc(strings.get("configure.hardened.action_approval"))}"$dis>${esc(strings.get("install.display.reset"))}</button>
 </div>
</form></div>"""
    }

    /** Read a bundled static asset (info.js / info.css) as text. */
    private fun asset(name: String): String =
        appContext.assets.open(name).bufferedReader().use { it.readText() }

    private fun inspectJson(status: String): String =
        """{"running":${CdpRelay.running},"port":${CdpRelay.PORT},"status":"$status","start_allowed":${!config.hardenedSecurityEnabled}}"""

    private fun entityFilterStatusJson(): String {
        val ids = runCatching { EntityFilterProtocol.normalize(config.dashboardEntityFilterIds) }
            .getOrDefault(emptyList())
        val hash = EntityFilterProtocol.hash(ids)
        return "{" +
            "\"enabled\":${config.dashboardEntityFilterEnabled}," +
            "\"entity_count\":${ids.size},\"filter_hash\":\"$hash\"," +
            "\"runtime\":${EntityFilterTelemetry.json()},\"learning\":${entityLearning.statusJson()}}"
    }

    /** Entity administration requests are tiny control messages. Bound both declared and chunked bodies
     *  before materializing JSON so a LAN client cannot exhaust a panel's heap. */
    private suspend fun receiveEntityAdminJson(call: ApplicationCall, allowBlank: Boolean = false): JSONObject? {
        val bytes = when (val receipt = receiveBoundedBody(call, MAX_ENTITY_ADMIN_BODY_BYTES)) {
            is BoundedBodyReceipt.Received -> receipt.bytes
            BoundedBodyReceipt.TooLarge -> {
                call.respondText("request too large\n", status = HttpStatusCode.PayloadTooLarge)
                return null
            }
            BoundedBodyReceipt.TimedOut -> {
                call.respondText("request timeout\n", status = HttpStatusCode.RequestTimeout)
                return null
            }
        }
        val text = String(bytes, Charsets.UTF_8)
        return try {
            JSONObject(if (allowBlank && text.isBlank()) "{}" else text)
        } catch (_: Throwable) {
            call.respondText("invalid JSON\n", status = HttpStatusCode.BadRequest)
            null
        }
    }

    /** Replace/toggle the complete experimental allow-list. Existing state supplies omitted fields,
     *  making `{\"enabled\":false}` a cheap A/B switch while the list remains stored on the panel. */
    private suspend fun handleEntityFilterPost(call: ApplicationCall) {
        val body = when (val receipt = receiveBoundedBody(call, EntityFilterProtocol.MAX_API_BODY_BYTES.toLong())) {
            is BoundedBodyReceipt.Received -> String(receipt.bytes, Charsets.UTF_8)
            BoundedBodyReceipt.TooLarge -> {
                call.respondText("request too large\n", status = HttpStatusCode.PayloadTooLarge)
                return
            }
            BoundedBodyReceipt.TimedOut -> {
                call.respondText("request timeout\n", status = HttpStatusCode.RequestTimeout)
                return
            }
        }
        val update = runCatching { EntityFilterProtocol.parseUpdate(body) }
            .getOrElse {
                call.respondText("invalid entity filter: ${it.message}\n", status = HttpStatusCode.BadRequest)
                return
            }
        if (update.mode == "automatic") {
            val requested = update.enabled ?: true
            if (!entityLearning.setEnabled(requested)) {
                call.respondText("configuration commit failed\n", status = HttpStatusCode.InternalServerError)
                return
            }
            call.respondText(entityFilterStatusJson(), ContentType.Application.Json)
            return
        }
        val ids = update.entityIds ?: config.dashboardEntityFilterIds
        val enabled = update.enabled ?: config.dashboardEntityFilterEnabled
        if (update.entityIds != null && ids.isEmpty()) {
            call.respondText("entity_ids must contain at least one valid entity\n", status = HttpStatusCode.BadRequest)
            return
        }
        if (enabled && ids.isEmpty()) {
            call.respondText("entity_ids required when enabled\n", status = HttpStatusCode.BadRequest)
            return
        }
        val committed = withContext(Dispatchers.IO) {
            if (update.mode == "manual" || update.entityIds != null) {
                config.commitDashboardManualEntityFilter(enabled, ids)
            } else {
                config.setDashboardEntityFilter(enabled, ids)
            }
        }
        if (!committed) {
            call.respondText("configuration commit failed\n", status = HttpStatusCode.InternalServerError)
            return
        }
        call.respondText(entityFilterStatusJson(), ContentType.Application.Json)
        // The live renderer is singleTask. reloadDashboard marks a reload intent; onNewIntent sees the
        // changed filter signature and rebuilds the WebView so document-start wiring is atomic.
        if (effectiveDashboardIsBuiltin()) {
            scope.launch {
                runCatching {
                    system.reloadDashboard(
                        SystemController.BUILTIN_DASHBOARD,
                        reason = "updating the entity filter",
                    )
                }
            }
        }
    }

    private fun startHaOAuth(
        haUrl: String,
        panelOrigin: String,
        context: HaOAuthStartContext = HaOAuthStartContext.ENGLISH_CONFIGURE,
    ): HaOAuthStart = synchronized(haOAuthStartLock) {
        val authority = config.beginHaOAuthAttempt()
        haOAuthFlow.start(haUrl, panelOrigin, authority, context)
    }

    private fun haOAuthCallbackCopy(strings: AppStrings): HaOAuthCallbackCopy = HaOAuthCallbackCopy(
        successHeading = strings.get("oauth.callback.success_heading"),
        failureHeading = strings.get("oauth.callback.failure_heading"),
        continueAction = strings.get("oauth.callback.action.continue"),
        backToConfigureAction = strings.get("oauth.callback.action.back_to_configure"),
        backToSetupAction = strings.get("oauth.callback.action.back_to_setup"),
        cancelled = strings.get("oauth.callback.cancelled"),
        invalidCode = strings.get("oauth.callback.invalid_code"),
        rejected = strings.get("oauth.callback.rejected"),
        transient = strings.get("oauth.callback.transient"),
        stale = strings.get("oauth.callback.stale"),
        commitFailed = strings.get("oauth.callback.commit_failed"),
        configured = strings.get("oauth.callback.configured"),
        reloadMayBeNeeded = strings.get("oauth.callback.reload_may_be_needed"),
        ambientWarning = strings.get("oauth.callback.ambient_warning"),
    )

    private suspend fun completeHaOAuth(
        attempt: HaOAuthAttempt,
        tokens: HaLink.OAuthTokens,
    ): HaOAuthCompletion {
        val expiry = System.currentTimeMillis() / 1_000L + tokens.expiresInSec
        val accepted = linkedMapOf(
            "ha_url" to attempt.haUrl,
            "ha_token" to tokens.accessToken,
            "ha_refresh_token" to tokens.refreshToken,
            "ha_token_expiry" to expiry.toString(),
            "ha_client_id" to attempt.clientId,
        )
        val newOwner = HaAuthSnapshot(
            attempt.haUrl,
            tokens.accessToken,
            tokens.refreshToken,
            expiry,
            attempt.clientId,
        ).stableOwner()
        val result = runCatching {
            applyAccepted(
                accepted,
                expectedHaAuthOwner = attempt.expectedOwner,
                expectedHaOAuthEpoch = attempt.expectedEpoch,
            )
        }
        if (result.isFailure) {
            return if (config.haAuthSnapshot().stableOwner() == newOwner &&
                config.isHaOAuthAttemptCurrent(attempt.expectedEpoch)
            ) {
                HaOAuthCompletion.Success(reloadMayBeNeeded = true)
            } else {
                HaOAuthCompletion.CommitFailed
            }
        }
        when (result.getOrThrow()) {
            ApplyAcceptedResult.Stale -> return HaOAuthCompletion.Stale
            ApplyAcceptedResult.CommitFailed,
            is ApplyAcceptedResult.CompatibilityRefused -> return HaOAuthCompletion.CommitFailed
            ApplyAcceptedResult.Applied -> Unit
        }
        val ambientWarning = runCatching {
            config.autoBrightnessHaEntity.takeIf(String::isNotBlank)?.let { entityId ->
                val validation = autoBrightnessHttpApi.validateHaSource(entityId)
                validation.action.statusCode !in 200..299
            } ?: false
        }.getOrDefault(true)
        return HaOAuthCompletion.Success(ambientWarning = ambientWarning)
    }

    /**
     * Apply a POSTed config form/JSON (partial-merge), then live-reconfigure. Shared by the legacy
     * `/config` route and `/api/v1/config`. Fleet/JSON clients (Accept: application/json) get the new
     * config back; a browser form gets an HTML redirect to the info page. The bespoke handling of
     * identity/MQTT/logging/tame keys is preserved; the formerly MQTT-only behaviour keys are applied
     * through [applySetting] (same path as an HA command), and per-row HA-exposure toggles are stored.
     */
    /**
     * Install the canonical direct-config mutation route. The optional capability provider is a narrow
     * JVM-test seam: production always uses the request-scoped management snapshot, while route tests can
     * avoid constructing Android sensor/probe owners without replacing either Ktor routing or this handler.
     */
    internal fun Route.installDirectConfigPostRoute(
        capabilityProvider: (() -> Capabilities)? = null,
    ) {
        post("/config") { handleConfigPost(call, capabilityProvider) }
    }

    private suspend fun handleConfigPost(
        call: ApplicationCall,
        capabilityProvider: (() -> Capabilities)? = null,
    ) {
        val received = receiveBoundedConfigParameters(call) ?: return
        // Same capability snapshot the Configure form was rendered from, so a choice the form offered is
        // the same set this admission step accepts.
        val postCaps = capabilityProvider?.invoke() ?: liveCapabilities(snapStaleOk().caps)
        val normalizedPost = when (val result = normalizeConfigPostParameters(received, postCaps)) {
            is ConfigPostParameters.Ok -> result.values
            is ConfigPostParameters.Bad -> {
                call.respondText("${result.reason}\n", status = HttpStatusCode.BadRequest)
                return
            }
        }
        // Settle a handed-over Home Assistant URL before anything else looks at `ha_url`: every
        // admission decision below reads the posted parameters, and a handover that verified is an
        // ordinary `ha_url` write from here on.
        val handoverPost = augmentPostWithVerifiedHandover(normalizedPost)
        val onboardingPost = augmentPostWithDiscoveredHaUrlForMqttOnboarding(handoverPost.parameters)
        val p = onboardingPost.parameters
        val postedValues = p.names().associateWith { p[it].orEmpty() }
        if (rejectHardenedNetworkAdb(call, p["network_adb"])) return
        // Fence every baseline-dependent admission decision below. The direct mutation lane rechecks
        // this complete effective snapshot before persistence, so a concurrent save cannot bypass an
        // Area prerequisite or hardened approval by changing the value while this request waits.
        val admissionBaselineHash = io.github.maxlyth.hapaneld.config.ConfigHash.of(directMutationValues())
        val requestedAutoSleepSource = p["auto_sleep_source"] ?: config.autoSleepSource
        val requestedAutoSleep = p["auto_sleep"]?.let(SettingValue::parseBool) ?: config.autoSleep
        val enablingAutoSleep = autoSleepRequiresHaAdmission(
            config.autoSleep, config.autoSleepSource, requestedAutoSleep, requestedAutoSleepSource,
        )
        var autoSleepPrerequisiteOwner: HaAuthOwner? = null
        var prerequisiteDeviceUid: String? = null
        val prerequisitePanelId = config.panelId
        if (enablingAutoSleep) {
            prerequisiteDeviceUid = config.deviceUid
            val connectionChanged = p["ha_url"]?.trimEnd('/')?.let { it != config.haUrl.trimEnd('/') } == true ||
                listOf("ha_token", "ha_refresh_token", "ha_token_expiry", "ha_client_id").any { p[it] != null }
            val identityChanged = p["panel_id"]?.let { it != prerequisitePanelId } == true
            if (connectionChanged || identityChanged) {
                call.respondText(
                    autoSleepConfigErrorJson(
                        "auto-sleep-prerequisite-stale",
                        "Save the Home Assistant connection and panel identity first, then enable Auto sleep.",
                    ),
                    ContentType.Application.Json,
                    HttpStatusCode.Conflict,
                )
                return
            }
            val prerequisite = autoSleepHttpApi.prerequisite()
            when (prerequisite.phase) {
                HaPanelAreaPrerequisitePhase.ASSIGNED -> {
                    autoSleepPrerequisiteOwner = prerequisite.authOwner ?: run {
                        call.respondText(
                            autoSleepConfigErrorJson(
                                "auto-sleep-prerequisite-stale",
                                "Home Assistant settings changed during the Area check. Try again.",
                            ),
                            ContentType.Application.Json,
                            status = HttpStatusCode.Conflict,
                        )
                        return
                    }
                }
                HaPanelAreaPrerequisitePhase.UNASSIGNED -> {
                    call.respondText(
                        autoSleepConfigErrorJson(
                            "auto-sleep-area-required",
                            "Assign this panel to a Home Assistant Area before enabling Auto sleep.",
                        ),
                        ContentType.Application.Json,
                        status = HttpStatusCode.Conflict,
                    )
                    return
                }
                HaPanelAreaPrerequisitePhase.AUTH_FAILED, HaPanelAreaPrerequisitePhase.UNAVAILABLE -> {
                    call.respondText(
                        autoSleepConfigErrorJson(
                            "auto-sleep-prerequisite-unavailable",
                            "Auto sleep could not check this panel's Home Assistant Area. Check the Home Assistant connection.",
                        ),
                        ContentType.Application.Json,
                        status = HttpStatusCode.ServiceUnavailable,
                    )
                    return
                }
            }
        }
        val previousAmbientSource = config.autoBrightnessHaEntity
        var ambientSourceOwner: io.github.maxlyth.hapaneld.HaAuthOwner? = null
        p["auto_brightness_ha_entity"]?.takeIf { it.isNotBlank() && it != previousAmbientSource }?.let { entityId ->
            val connectionChangesBesideSource = p["ha_url"]?.trimEnd('/')?.let { it != config.haUrl.trimEnd('/') } == true ||
                listOf("ha_token", "ha_refresh_token", "ha_token_expiry", "ha_client_id").any { p[it] != null }
            if (connectionChangesBesideSource) {
                call.respondText(
                    """{"ok":false,"error":"ha-source-connection-change","message":"Save the Home Assistant connection first, then select the ambient light entity."}""",
                    ContentType.Application.Json,
                    HttpStatusCode.Conflict,
                )
                return
            }
            val validation = autoBrightnessHttpApi.validateHaSource(entityId)
            if (validation.action.statusCode !in 200..299) {
                respondAutoBrightnessAction(call, validation.action)
                return
            }
            ambientSourceOwner = validation.authOwner ?: run {
                call.respondText(
                    """{"ok":false,"error":"ha-source-validation-stale","message":"Home Assistant settings changed during the check. Try again."}""",
                    ContentType.Application.Json,
                    HttpStatusCode.Conflict,
                )
                return
            }
        }
        val dashboardPackage = p["dashboard_package"]
        if (builtinRendererNeedsConnection(
                currentPackage = config.dashboardPackage,
                currentHaUrl = config.haUrl,
                currentHasCredentials = (config.haToken.isNotBlank() || config.haRefreshToken.isNotBlank()) &&
                    !(config.hardenedSecurityEnabled && p["ha_url"]?.trimEnd('/')?.let {
                        it != config.haUrl.trimEnd('/')
                    } == true),
                requestedPackage = dashboardPackage,
                requestedHaUrl = p["ha_url"],
                requestHasCredentials = p["ha_token"].orEmpty().isNotBlank() ||
                    p["ha_refresh_token"].orEmpty().isNotBlank(),
            )
        ) {
            call.respondText(
                """{"ok":false,"error":"ha-sign-in-required","message":"Connect Home Assistant with Browser sign-in before selecting the Built-in renderer."}""",
                ContentType.Application.Json,
                HttpStatusCode.Conflict,
            )
            return
        }
        if (p["dashboard_idle_return_min"] != null &&
            (dashboardPackage ?: config.dashboardPackage).let { it.isNotBlank() && it != SystemController.BUILTIN_DASHBOARD }
        ) {
            call.respondText(
                """{"ok":false,"error":"built-in-renderer-required","message":"Idle return is available only for the built-in renderer."}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
            return
        }
        val tamePackagesChanged = p["tame_vendor_packages"]?.split(' ')?.filter(String::isNotBlank)?.toSet()
            ?.let { requested -> requested != config.tameVendorPackages.toSet() } == true
        val softwareAuthorityChanged = requestsSoftwareInstallAuthority { p[it] }
        val powerSafetyReduction = PowerSafetyMutationPolicy.requestsSafetyReduction(
            keepAwake = config.keepAwake,
            requestedKeepAwake = p["keep_awake"]?.let(SettingValue::parseBool),
            preventIdleDim = config.preventIdleDim,
            requestedPreventIdleDim = p["prevent_idle_dim"]?.let(SettingValue::parseBool),
        )
        val privilegedOperation = when {
            tamePackagesChanged -> SensitiveOperation.PACKAGE_TAME
            softwareAuthorityChanged -> SensitiveOperation.APK_INSTALL
            else -> null
        }
        val sensitiveOperations = buildList {
            if (powerSafetyReduction) add(SensitiveOperation.POWER_CONFIGURATION)
            privilegedOperation?.let(::add)
        }
        val approvalPayload = exactHttpApprovalPayload(call, p.canonicalDigest())
        when (ConfigSensitiveAdmission.authorize(
            hardenedSecurityEnabled = config.hardenedSecurityEnabled,
            loopbackPeer = isLoopbackPeer(call.request.origin.remoteAddress),
            requestedOperations = sensitiveOperations,
            authorize = { operation ->
                authorizeSensitive(
                    call,
                    operation,
                    approvalPayload,
                    when (operation) {
                        SensitiveOperation.POWER_CONFIGURATION -> "Disable a panel power-safety guard"
                        SensitiveOperation.PACKAGE_TAME -> when {
                            tamePackagesChanged && softwareAuthorityChanged ->
                                "Change vendor package state and software installation policy"
                            else -> "Change the persistent vendor package blocklist"
                        }
                        else -> "Allow automatic software installation or apply an active update channel"
                    },
                )
            },
        )) {
            ConfigSensitiveAdmissionResult.SEPARATE_SENSITIVE_CHANGES -> {
                call.respondText(
                    """{"ok":false,"error":"separate-sensitive-changes","message":"Save power-safety reductions separately from vendor-package or software-installation policy changes."}""",
                    ContentType.Application.Json,
                    HttpStatusCode.Conflict,
                )
                return
            }
            ConfigSensitiveAdmissionResult.DENIED -> return
            ConfigSensitiveAdmissionResult.AUTHORIZED -> Unit
        }
        val configMutationTicket = InstallProgress.startConfigMutation()
        if (configMutationTicket == null) {
            respondConfigMutation(
                call,
                "operation-busy",
                emptyList(),
                emptyList(),
                emptyList(),
                "Another panel operation is in progress. Wait for it to finish, then save again.",
                HttpStatusCode.Conflict,
            )
            return
        }
        var preparedChannel: SelfUpdateChannelPreflight.Ready? = null
        var committedChannel = false
        val previousChannel = config.updateChannel
        try {
        val requestedChannelChanged = p["update_channel"]?.let { it != config.updateChannel } == true
        selfUpdateChannelMutation(
            currentChannel = config.updateChannel,
            currentSelfUpdate = config.selfUpdate,
            requestedValues = postedValues,
        )?.let { request ->
            when (val preflight = prepareSelfUpdateChannel(request.requested, request.force)) {
                is SelfUpdateChannelPreflight.Ready -> {
                    if (preflight.requiresRecovery) {
                        preflight.close()
                        respondConfigMutation(
                            call,
                            "database-compatibility-refused",
                            emptyList(),
                            emptyList(),
                            listOf("update_channel"),
                            "An update-channel change cannot recover an older database snapshot.",
                            HttpStatusCode.Conflict,
                        )
                        return
                    }
                    preparedChannel = preflight
                }
                is SelfUpdateChannelPreflight.UpToDate -> Unit
                is SelfUpdateChannelPreflight.Refused,
                is SelfUpdateChannelPreflight.Unresolved -> {
                    respondConfigMutation(
                        call,
                        "database-compatibility-refused",
                        emptyList(),
                        emptyList(),
                        listOf("update_channel"),
                        preflight.message,
                        HttpStatusCode.Conflict,
                    )
                    return
                }
            }
        }
        lateinit var mutationPlan: DirectConfigMutationPlan
        lateinit var expectedReadBack: Map<String, String>
        // Partial-merge: apply ONLY keys present, so a fleet tool can set one field without clobbering
        // the rest. The UI form sends every key (blank = clear), preserving its full-replace behaviour.
        val panelId = p["panel_id"]
        // Persisted fields are staged into one editor and committed atomically, so a power loss mid-apply
        // can't leave a half-written config (e.g. broker set but credentials not). Live side-effects
        // (behaviour keys, reconfigure) run after the commit so they read freshly-committed state.
        // Live-apply side-effects are DETECTED inside the batch (from POSTED values — a read-back inside
        // applyBatch returns the pre-commit value, which silently defeated change-detection) but EXECUTED
        // after it commits, so the relaunched renderer can never read stale config.
        var applyDark: Boolean? = null
        // The built-in dashboard colour-scheme policy. Detected from the POSTED value for the same
        // reason as applyDark: a read-back inside applyBatch returns the pre-commit value.
        var themePolicyChanged = false
        var relaunchForHa = false
        var relaunchForDash = false
        var relaunchForFullscreen = false
        var relaunchForOverscroll = false
        var relaunchForNativeKiosk = false
        var reloadForZoom = false
        var entityLearningChanged: Boolean? = null
        var entityTargetChanged = false
        var homeDashboardAppliedEarly = false
        var homeDashboardChangedEarly = false
        var rendererFailure: Throwable? = null
        val liveApplied = ArrayList<String>()
        val livePending = ArrayList<String>()
        val liveRejected = ArrayList<String>()
        var ambientSourceValidationStale = false
        var autoSleepPrerequisiteStale = false
        var directAdmissionStale = false
        var channelCompatibilityRefusal: String? = null
        var previous: ConfigBundle? = null
        val committed = withContext(Dispatchers.IO) {
            synchronized(directConfigMutationLock) {
                val saved = rendererPreparation.transaction {
                    val persisted = config.synchronizedTransaction {
                    if (io.github.maxlyth.hapaneld.config.ConfigHash.of(directMutationValues()) != admissionBaselineHash) {
                        directAdmissionStale = true
                        return@synchronizedTransaction false
                    }
                    mutationPlan = planDirectConfigMutation(
                        posted = postedValues,
                        before = directMutationValues(),
                    )
                    expectedReadBack = directConfigExpectedReadBack(config, postedValues)
                    if (mutationPlan.isNoOp) return@synchronizedTransaction true
                    if (ambientSourceOwner != null &&
                        (config.haAuthSnapshot().stableOwner() != ambientSourceOwner || config.autoBrightnessHaEntity != previousAmbientSource)
                    ) {
                        ambientSourceValidationStale = true
                        return@synchronizedTransaction false
                    }
                    if (autoSleepPrerequisiteOwner != null &&
                        (config.haAuthSnapshot().stableOwner() != autoSleepPrerequisiteOwner ||
                            config.deviceUid != prerequisiteDeviceUid || config.panelId != prerequisitePanelId ||
                            (config.autoSleep && config.autoSleepSource == "home_assistant"))
                    ) {
                        autoSleepPrerequisiteStale = true
                        return@synchronizedTransaction false
                    }
                    previous = ConfigBundle.fromValues(
                        revisionValues(), kind = ConfigBundle.KIND_REVISION,
                        exportedAt = System.currentTimeMillis().toString(), exportedBy = config.panelId,
                    )
                    // DB_COMPAT_MUTATION_ANCHOR: HTTP_DIRECT_CONFIG_COMMIT
                    preparedChannel?.revalidateForConfigCommit()?.let { refusal ->
                        // The prepared APK and live database can change while HTTP admission is doing
                        // unrelated validation. No preference in this request may commit unless the
                        // exact candidate is still authenticated and the current database is DIRECT.
                        channelCompatibilityRefusal = refusal
                        return@synchronizedTransaction false
                    }
                    config.applyBatch(
                        afterCommit = {
                            if ("tame_vendor_packages" in p) requestTameReconcileAfterCommit()
                            p["ui_language"]?.let(NativeLocale::apply)
                        },
                    ) {
                    stageDirectConfigRegistryValues(config, postedValues, mutationPlan.changedKeys)
                    panelId?.let { config.setPanelId(it) }
                    p["friendly_name"]?.let { config.setFriendlyName(it.trim()) }
                    p["ui_language"]?.let { config.setUiLanguage(it) }
                    val prevDash = config.dashboardPackage
                    dashboardPackage?.let { config.setDashboardPackage(it) }
                    val dashChanged = dashboardPackage?.let { it != prevDash } == true
                    p["launcher_package"]?.let { config.setLauncherPackage(it.trim()) }
                    p["kiosk_companion_packages"]?.let { config.setKioskCompanionPackages(it) }
                    p["tame_vendor_packages"]?.let { raw ->
                        if (tamePackagesChanged) config.setTameVendorPackages(raw)
                    }
                    p["http_allowed_hosts"]?.let { config.setHttpAllowedHosts(it) }
                    // The handover trio persists here for the same reason `http_allowed_hosts` does:
                    // they are bespoke config keys with no SettingsRegistry spec, so the generic
                    // registry writer never sees them. The reason is only ever appended by this
                    // panel's own verification, never accepted from the network.
                    p["ha_setup_handover"]?.let {
                        config.setHaSetupHandover(SettingValue.parseBool(it) == true)
                    }
                    p["ha_url_handover"]?.let { config.setHaUrlHandover(it) }
                    p["ha_url_handover_reason"]?.let { config.setHaUrlHandoverReason(it) }
                    // Live keys are deliberately excluded from this batch. Their handlers must observe
                    // the previous value before the live-setting authority persists the applied value.
                    // update_channel is the exception: its exact APK was admitted above, and putting it
                    // in this same batch prevents any other setting in the request from becoming visible
                    // before compatibility proof. Its live handler is suppressed below to avoid resolving
                    // a second candidate.
                    p["update_channel"]?.let { config.setUpdateChannel(it) }
                    // Keep-awake (partial wakelock so SoC/network never suspend). Applied live by reconfigure().
                    p["keep_awake"]?.let { config.setKeepAwake(it.trim().equals("true", ignoreCase = true) || it.trim() == "1") }
                    // Room-temperature calibration trim (°C) — a plain local pref with no MQTT command, so it
                    // persists here rather than through HTTP_LIVE_KEYS/applySetting (the command path).
                    p["room_temp_offset"]?.let { config.setRoomTempOffset(it) }
                    // Live-apply a fullscreen toggle: a bare foreground relaunch of the running renderer re-runs
                    // onResume → applyFullscreen with the new value, without touching the page (no reload flag).
                    // Detected from the POSTED value — config read-back inside the batch is pre-commit.
                    val prevFullscreen = config.dashboardFullscreen
                    val postedFullscreen = p["dashboard_fullscreen"]?.let { it.trim().equals("true", ignoreCase = true) || it.trim() == "1" }
                    postedFullscreen?.let { config.setDashboardFullscreen(it) }
                    relaunchForFullscreen = postedFullscreen != null && postedFullscreen != prevFullscreen && !dashChanged
                    // Native HA kiosk mode is applied over the live external bus. Foregrounding the
                    // singleTask renderer lets onNewIntent update the current document without reload.
                    val prevNativeKiosk = config.dashboardNativeKiosk
                    val postedNativeKiosk = p["dashboard_native_kiosk"]?.let {
                        it.trim().equals("true", ignoreCase = true) || it.trim() == "1"
                    }
                    postedNativeKiosk?.let { config.setDashboardNativeKiosk(it) }
                    relaunchForNativeKiosk = postedNativeKiosk != null &&
                        postedNativeKiosk != prevNativeKiosk && !dashChanged
                    // Overscroll stretch/glow (hidden, API-only). Same live-apply as fullscreen: a foreground
                    // relaunch re-runs onResume → applyOverscroll. Detected from the POSTED value (read-back
                    // inside applyBatch is pre-commit).
                    val prevOverscroll = config.dashboardOverscroll
                    val postedOverscroll = p["dashboard_overscroll"]?.let { it.trim().equals("true", ignoreCase = true) || it.trim() == "1" }
                    postedOverscroll?.let { config.setDashboardOverscroll(it) }
                    relaunchForOverscroll = postedOverscroll != null && postedOverscroll != prevOverscroll && !dashChanged
                    val prevEntityLearning = config.dashboardEntityLearningEnabled
                    val postedEntityLearning = p["dashboard_entity_learning"]?.let {
                        it.trim().equals("true", ignoreCase = true) || it.trim() == "1"
                    }
                    if (postedEntityLearning != null && postedEntityLearning != prevEntityLearning) {
                        // The manager owns this transition after the surrounding config transaction.
                        // Pre-writing it here makes setEnabled() observe the new value as the old value,
                        // defeating its fresh-opt-in latch reset and bootstrap semantics.
                        entityLearningChanged = postedEntityLearning
                    }
                    // Page zoom (%). A fresh load is where setInitialScale reliably takes effect, so on a change
                    // we reload the renderer rather than just re-foregrounding it. Detected from the POSTED value.
                    val prevZoom = config.dashboardZoom
                    val postedZoom = p["dashboard_zoom"]?.trim()?.toIntOrNull()?.coerceIn(50, 300)
                    postedZoom?.let { config.setDashboardZoom(it) }
                    reloadForZoom = postedZoom != null && postedZoom != prevZoom && !dashChanged
                    // Dark mode (Display card; only meaningful on panels WITHOUT a system dark-mode setting,
                    // Android 9-). Detected from the POSTED value (read-back inside the batch is pre-commit —
                    // this exact bug made the toggle a silent no-op); executed after the batch commits.
                    val prevDark = config.darkMode
                    val postedDark = p["dark_mode"]?.let { it.trim().equals("true", ignoreCase = true) || it.trim() == "1" }
                    postedDark?.let { config.setDarkMode(it) }
                    if (postedDark != null && postedDark != prevDark && android.os.Build.VERSION.SDK_INT < 29) applyDark = postedDark
                    // Dashboard colour-scheme policy (Dashboard card). Separate authority from dark_mode
                    // and deliberately ungated by SDK: it is the only lever that re-themes Home Assistant,
                    // and it must reach a fresh page load because the policy is baked into a
                    // document-start script that cannot be replaced in a live WebView.
                    val prevThemePolicy = config.dashboardTheme
                    val postedThemePolicy = p["dashboard_theme"]?.let { DashboardTheme.policy(it) }
                    postedThemePolicy?.let { config.setDashboardTheme(it) }
                    themePolicyChanged = postedThemePolicy != null && postedThemePolicy != prevThemePolicy
                    stageDirectLogShipping(config, postedValues)
                    val mfr = p["manufacturer"]?.trim()
                    val mdl = p["model"]?.trim()
                    if (mfr != null || mdl != null) config.setHardware(
                        mfr ?: config.manufacturerRaw,
                        mdl ?: config.modelRaw,
                    )
                    // Credential groups carry dependent-clear semantics which cannot be represented as
                    // independent generic keys. Keep their actual owner in one production-used helper so
                    // the behavioural contract can prove username/password and HA session transitions.
                    val credentialEffects = stageDirectCredentialSettings(config, postedValues)
                    relaunchForHa = credentialEffects.haChanged
                    entityTargetChanged = credentialEffects.haChanged
                    // Live-apply a renderer switch: re-anchor HOME to the new renderer and bring it up now —
                    // previously changing "Dashboard app" did nothing until the next boot. Off-thread (su/daemon).
                    // The kiosk/watchdog loops read dashboard_package per tick, so they retarget on their own.
                    relaunchForDash = dashChanged

                    // Per-row "expose to HA" toggles (ha_expose_<key>=true|false) — take effect on the reconfigure.
                    for (name in p.names()) {
                        val exposed = SettingsRegistry.parseExposure(name) ?: continue
                        SettingValue.parseBool(p[name].orEmpty())?.let {
                            config.setHaExposed(exposed.key, it)
                        }
                    }
                    }
                }
                if (persisted) {
                    committedChannel = directUpdateChannelCommitted(
                        requestedChannelChanged,
                        mutationPlan.changedKeys,
                        postedValues["update_channel"],
                        config.updateChannel,
                    )
                }
                if (persisted && !mutationPlan.isNoOp) {
                    revisions.snapshot(requireNotNull(previous))
                    runCatching {
                        // Home dashboard normally travels through the live MQTT-equivalent path. Apply this
                        // one target-defining value before any renderer effect so owner-scoped filter state
                        // is rebound (or hidden) before a relaunched WebView can observe it.
                        mutationPlan.changedLive.firstOrNull { it.first == "home_dashboard" }?.let { (_, posted) ->
                            val previousHome = config.homeDashboard
                            recordLiveApplyOutcome(
                                "home_dashboard",
                                applySetting("home_dashboard", posted),
                                liveApplied,
                                livePending,
                                liveRejected,
                            )
                            homeDashboardAppliedEarly = true
                            homeDashboardChangedEarly = config.homeDashboard != previousHome
                        }
                        if (entityTargetChanged && !homeDashboardChangedEarly) {
                            entityLearning.onTargetConfigurationChanged()
                        }
                        entityLearningChanged?.let { enabled ->
                            val delegated = applyDirectConfigDelegatedSettings(
                                postedValues,
                                mutationPlan.changedKeys,
                            ) { key, value ->
                                key == "dashboard_entity_learning" &&
                                    entityLearning.setEnabled(value.toBoolean())
                            }
                            check("dashboard_entity_learning" in delegated) {
                                "entity-learning transition failed"
                            }
                            entityLearningChanged = null
                        }
                        applyRendererEffects(
                            RendererConfigEffects.coalesce(
                                dashboardChanged = relaunchForDash,
                                credentialChanged = relaunchForHa,
                                zoomChanged = reloadForZoom,
                                fullscreenChanged = relaunchForFullscreen,
                                overscrollChanged = relaunchForOverscroll,
                                nativeKioskChanged = relaunchForNativeKiosk,
                                homeChanged = homeDashboardChangedEarly,
                                darkMode = applyDark,
                                themePolicyChanged = themePolicyChanged,
                            ),
                        )
                    }.onFailure { rendererFailure = it }
                }
                    persisted
                }
                if (saved && !mutationPlan.isNoOp) {
                    // Keep equality planning, persistence and hardware admission in one request lane. A
                    // concurrent retry therefore observes this request's durable desired state as its baseline.
                    dispatchDirectConfigLiveSettings(mutationPlan.changedLive) { key, raw ->
                        if (key == "home_dashboard" && homeDashboardAppliedEarly) {
                            return@dispatchDirectConfigLiveSettings
                        }
                        if (key == "update_channel") return@dispatchDirectConfigLiveSettings
                        val spec = SettingsRegistry.spec(key)
                        val value = if (spec != null) {
                            when (val validated = SettingValue.validate(spec, raw)) {
                                is Validation.Ok -> validated.normalized
                                is Validation.Bad -> return@dispatchDirectConfigLiveSettings
                            }
                        } else raw.trim()
                        recordLiveApplyOutcome(
                            key,
                            applySetting(key, value),
                            liveApplied,
                            livePending,
                            liveRejected,
                        )
                    }
                }
                val requestedHaArea = mutationPlan.changedLive
                    .firstOrNull { it.first == "ha_area" }
                    ?.second
                    ?.trim()
                val durableHaArea = requestedHaArea?.takeIf { requested ->
                    saved && config.haArea == requested &&
                        config.haAreaUserOverride == requested.isNotBlank()
                }
                if (requestedHaArea != null && durableHaArea == null) {
                    // A pending live journal is not the Area authority: until the atomic Config commit
                    // publishes both fields, HTTP must not call this saved or start Area-dependent work.
                    liveApplied.remove("ha_area")
                    livePending.remove("ha_area")
                    if ("ha_area" !in liveRejected) liveRejected += "ha_area"
                }
                if (durableHaArea != null) {
                    // commitRaw owns Area + override in one durable transaction. Only the matching
                    // read-back may admit dependent work; a failed SQLite commit leaves both old values
                    // visible and the response's live outcome remains rejected for an explicit retry.
                    autoSleepHttpApi.noteAreaChanged()
                }
                if (!durableHaArea.isNullOrBlank()) {
                    // Requested-area write-back, post-commit and in the background: admin sessions move
                    // the device now; non-admin attempts fail closed inside and the request simply stands
                    // (it seeds discovery's suggested_area and retries when an admin next reads the
                    // ha-area endpoint). Afterwards adopt whatever HA actually reports — for a value HA
                    // agrees with the override bit retires; a deliberate divergence is KEPT.
                    haAreaWriteJob?.cancel()
                    invalidateHaAreaCatalogCache()
                    val snapshot = captureHaAreaSnapshot()
                    haAreaWriteJob = scope.launch {
                        runCatching {
                            val before = haAreaCatalogFor(snapshot, fresh = true)
                            applyHaAreaPrecedence(snapshot, before)
                        }.onFailure { Log.w(TAG, "ha-area: write-back after config save failed", it) }
                    }
                }
                saved
            }
        }
        if (ambientSourceValidationStale) {
            call.respondText(
                """{"ok":false,"error":"ha-source-validation-stale","message":"Home Assistant settings changed during the check. Try again."}""",
                ContentType.Application.Json,
                HttpStatusCode.Conflict,
            )
            return
        }
        if (autoSleepPrerequisiteStale) {
            call.respondText(
                autoSleepConfigErrorJson(
                    "auto-sleep-prerequisite-stale",
                    "Home Assistant settings changed during the Area check. Try again.",
                ),
                ContentType.Application.Json,
                status = HttpStatusCode.Conflict,
            )
            return
        }
        if (directAdmissionStale) {
            respondConfigMutation(
                call,
                "configuration-stale",
                emptyList(),
                emptyList(),
                emptyList(),
                "Settings changed while this request was being checked. Reload and try again.",
                HttpStatusCode.Conflict,
            )
            return
        }
        channelCompatibilityRefusal?.let { refusal ->
            respondConfigMutation(
                call,
                "database-compatibility-refused",
                emptyList(),
                emptyList(),
                listOf("update_channel"),
                refusal,
                HttpStatusCode.Conflict,
            )
            return
        }
        if (!committed) {
            call.respondText("configuration commit failed\n", status = HttpStatusCode.InternalServerError)
            return
        }
        if (mutationPlan.isNoOp) {
            respondConfigMutation(call, "no-op", emptyList(), emptyList(), emptyList(), null)
            return
        }
        // Ordinary preferences are named only after committed read-back matches the normalized request.
        // Planned keys are intent, not evidence: echoing them here hid both the idle-return and camera
        // writer defects by reporting values the handler had silently dropped.
        val ordinaryOutcomes = directConfigOrdinaryOutcomes(
            config,
            postedValues,
            mutationPlan.changedKeys,
            expectedReadBack,
        )
        liveApplied.addAll(0, ordinaryOutcomes.applied)
        ordinaryOutcomes.rejected.filterNot(liveRejected::contains).forEach(liveRejected::add)
        if (committedChannel) liveApplied.add(0, "update_channel")
        if ("update_channel" in mutationPlan.changedKeys && !committedChannel && "update_channel" !in liveRejected) {
            liveRejected += "update_channel"
        }
        snapInvalidate()
        val reconfigureKeys = ordinaryOutcomes.applied.toCollection(linkedSetOf()).apply {
            if ("home_dashboard" in liveApplied || "home_dashboard" in livePending) add("home_dashboard")
            addAll(livePending)
        }
        if (reconfigureKeys.isNotEmpty()) onReconfigure(reconfigureKeys)
        rendererFailure?.let {
            Log.e(TAG, "configuration committed but renderer preparation failed", it)
            val failedOwner = directConfigEffectFailureOwner(entityLearningChanged != null)
            liveApplied.remove(failedOwner)
            if (failedOwner !in liveRejected) liveRejected += failedOwner
        }
        if (liveRejected.isNotEmpty()) {
            val nothingSaved = liveApplied.isEmpty() && livePending.isEmpty()
            val effectFailures = liveRejected.filter { it == "renderer" || it.endsWith("_effect") }
            val failureMessage = when {
                effectFailures.size == liveRejected.size && !nothingSaved ->
                    "Settings were saved, but these post-commit effects failed: ${effectFailures.joinToString()}."
                nothingSaved ->
                    "Settings could not be durably accepted: ${liveRejected.joinToString()}."
                else ->
                    "Some settings were saved, but ${liveRejected.joinToString()} could not be durably accepted."
            }
            respondConfigMutation(
                call,
                if (nothingSaved) "commit-failed" else "saved-partial",
                liveApplied,
                livePending,
                liveRejected,
                failureMessage,
                HttpStatusCode.InternalServerError,
            )
            return
        }
        val pendingMessage = livePending.takeIf(List<String>::isNotEmpty)?.let {
            "Settings were saved; retrying hardware apply: ${it.joinToString()}. " +
                "If they remain pending, restart the service."
        }
        val onboardingSignInMessage = mqttOnboardingSignInMessage(
            onboardingPost.haUrlDiscovered,
            mutationPlan.changedKeys,
            onboardingPost.haDiscovery,
        )
        // A handover that failed still saved the marker and the address, so this is a successful write
        // reporting an unsuccessful address — not an error. The authoritative state the integration
        // reads is `GET /api/v1/setup`, which carries the same verdict; this message exists so an
        // operator watching the wire sees the reason immediately.
        val handoverMessage = handoverPost.outcome?.let { outcome ->
            if (outcome.verified) {
                "Home Assistant's address was verified from this panel and saved."
            } else {
                "Home Assistant handed this panel ${handoverPost.url}, which did not answer " +
                    "(${outcome.reason}). Setup will ask for the address instead."
            }
        }
        respondConfigMutation(
            call,
            if (livePending.isEmpty()) "saved" else "saved-apply-pending",
            liveApplied,
            livePending,
            emptyList(),
            pendingMessage ?: handoverMessage ?: onboardingSignInMessage,
            if (livePending.isEmpty()) HttpStatusCode.OK else HttpStatusCode.Accepted,
        )
        } finally {
            val promotedChannelTicket = if (committedChannel && preparedChannel != null) {
                checkNotNull(
                    InstallProgress.promoteConfigMutation(
                        configMutationTicket,
                        "ha-paneld",
                        InstallPresentation("operation-working", mapOf("owner" to "paneld")),
                    ),
                ) {
                    "committed self-update channel lost its configuration owner"
                }
            } else null
            InstallProgress.finishConfigMutation(configMutationTicket)
            if (committedChannel) {
                onSelfUpdateChannelCommitted(
                    preparedChannel,
                    promotedChannelTicket,
                    previousChannel,
                    config.updateChannel,
                )
                preparedChannel = null
            }
            preparedChannel?.close()
        }
    }

    private data class OnboardingConfigPost(
        val parameters: Parameters,
        val haUrlDiscovered: Boolean,
        /** Carried so the response can explain a failed discovery without browsing a second time. */
        val haDiscovery: DiscoveryResult = DiscoveryResult(),
    )

    /**
     * Verify a handed-over Home Assistant URL from this panel's own network before accepting it.
     *
     * Rewrites the posted parameters rather than persisting separately, so an accepted address takes
     * exactly the same commit and live-apply path as one a person typed — the renderer reconfigure, the
     * read-back check and the mutation plan all behave identically. This mirrors
     * [augmentPostWithDiscoveredHaUrlForMqttOnboarding], which fills the same field from discovery.
     *
     * Three outcomes:
     *  - **already configured** — the panel has a URL, so the handover is dropped untouched. Adoption is
     *    re-runnable, and a retry must converge rather than overwrite a working panel.
     *  - **verified** — promoted to `ha_url`, and the handover field and its reason are cleared.
     *  - **failed** — the address is kept, with the reason, so the wizard can offer a correction that
     *    shows what was tried instead of an empty field.
     *
     * The provenance marker `ha_setup_handover` is NOT touched here. It rides the POST as an ordinary
     * key and is stored whatever this decides, because a handed-over address that did not answer still
     * means Home Assistant deployed this panel.
     */
    private suspend fun augmentPostWithVerifiedHandover(posted: Parameters): HandoverConfigPost =
        rewritePostForHandover(posted, config.haUrl) { offered ->
            withContext(Dispatchers.IO) { verifyHandedOverHaUrl(offered) }
                .also { Log.i(TAG, "config: handed-over Home Assistant URL verification ${it.name.lowercase()}") }
        }

    /**
     * One bounded unauthenticated `GET {url}/api/` from the panel itself.
     *
     * Home Assistant answers that with 401; anything else answering means something that is not Home
     * Assistant holds the address. See [HaUrlHandover] for why a 200 there is a failure rather than a
     * weaker success.
     */
    private fun verifyHandedOverHaUrl(url: String): HaUrlHandover.Outcome {
        val probe = HaUrlHandover.probeUrl(url) ?: return HaUrlHandover.Outcome.INVALID
        return runCatching {
            val connection = java.net.URL(probe).openConnection() as java.net.HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = HaUrlHandover.CONNECT_TIMEOUT_MS
                connection.readTimeout = HaUrlHandover.READ_TIMEOUT_MS
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Accept", "application/json")
                HaUrlHandover.classifyStatus(connection.responseCode)
            } finally {
                connection.disconnect()
            }
        }.getOrElse(HaUrlHandover::classifyFailure)
    }

    private suspend fun augmentPostWithDiscoveredHaUrlForMqttOnboarding(posted: Parameters): OnboardingConfigPost {
        if (!shouldDiscoverHaUrlForMqttOnboarding(config.haUrl, posted)) {
            return OnboardingConfigPost(posted, false)
        }
        val suggestions = withContext(Dispatchers.IO) { configDiscoverySuggestions() }
        val found = suggestions.haDiscovery
        lastHaDiscovery = found
        val discovered = suggestions.haUrl.trim()
        if (discovered.isBlank() || config.haUrl.isNotBlank()) return OnboardingConfigPost(posted, false, found)
        val spec = SettingsRegistry.spec("ha_url") ?: return OnboardingConfigPost(posted, false, found)
        val normalized = when (val validation = SettingValue.validate(spec, discovered)) {
            is Validation.Ok -> validation.normalized
            is Validation.Bad -> {
                Log.w(TAG, "config: ignoring invalid discovered Home Assistant URL during MQTT onboarding")
                return OnboardingConfigPost(posted, false, found)
            }
        }
        val augmented = Parameters.build {
            for (name in posted.names()) {
                posted.getAll(name).orEmpty().forEach { append(name, it) }
            }
            append("ha_url", normalized)
        }
        Log.i(TAG, "config: discovered Home Assistant URL during MQTT onboarding")
        return OnboardingConfigPost(augmented, true, found)
    }

    /**
     * What to tell the user immediately after they save MQTT settings, so the next step is never a guess.
     *
     * The message must NOT be conditional on discovery having succeeded. It previously returned null
     * whenever `ha_url` was still blank, which is exactly the state a panel on a different network segment
     * from Home Assistant is always left in: mDNS is link-local, discovery cannot reach across the
     * boundary, so the URL stayed empty and the user got no message at all — silence at the precise moment
     * they most needed direction. Now a failed discovery produces guidance *and* the reason for it.
     */
    private fun mqttOnboardingSignInMessage(
        haUrlDiscovered: Boolean,
        changedKeys: Collection<String>,
        discovery: DiscoveryResult,
    ): String? {
        val onboardingKeys = setOf("mqtt_broker", "mqtt_user", "mqtt_password", "ha_url", "dashboard_package")
        if (!haUrlDiscovered && changedKeys.none { it in onboardingKeys }) return null
        if (!effectiveDashboardIsBuiltin()) return null
        if (config.haToken.isNotBlank() || config.haRefreshToken.isNotBlank()) return null
        if (config.haUrl.isBlank()) {
            val next = "MQTT settings saved. Next: enter the Home Assistant URL in the Home Assistant " +
                "connection card below."
            val why = HaDiscovery.unavailableExplanation(discovery)
                ?: return "$next It was not found automatically on this network."
            return "$next It could not be found automatically because $why."
        }
        return "MQTT settings saved — preparing Home Assistant sign-in. " +
            "The panel should show the sign-in workflow; you can also use Browser sign-in below."
    }

    private fun recordLiveApplyOutcome(
        key: String,
        outcome: LiveSettingRequestOutcome,
        applied: MutableList<String>,
        pending: MutableList<String>,
        rejected: MutableList<String>,
    ) {
        when {
            outcome == LiveSettingRequestOutcome.APPLIED -> applied += key
            outcome.pending -> pending += key
            else -> rejected += key
        }
    }

    private suspend fun respondConfigMutation(
        call: ApplicationCall,
        status: String,
        applied: List<String>,
        pending: List<String>,
        rejected: List<String>,
        message: String?,
        httpStatus: HttpStatusCode = HttpStatusCode.OK,
    ) {
        val resolvedMessage = message ?: when (status) {
            "no-op" -> "No settings changed."
            else -> "Settings saved."
        }
        val jsonResponse = configMutationWantsJson(
            call.request.headers["Accept"],
            call.request.headers["Content-Type"],
        )
        if (jsonResponse) {
            call.respondText(
                configJson(
                    mutationStatus = status,
                    applied = applied,
                    pending = pending,
                    rejected = rejected,
                    message = resolvedMessage,
                ),
                ContentType.Application.Json,
                httpStatus,
            )
        } else {
            call.respondText(
                configMutationHtml(resolvedMessage),
                ContentType.Text.Html,
                httpStatus,
            )
        }
    }

    /** Atomically update the desired selection and notify its owner before releasing config commit order. */
    private fun updateTameSelection(update: (MutableSet<String>) -> Unit): Boolean =
        config.synchronizedTransaction {
            val selected = config.tameVendorPackages.toCollection(LinkedHashSet())
            update(selected)
            val normalized = when (val validation = TamePackagePolicy.normalize(selected.joinToString(" "))) {
                is Validation.Ok -> validation.normalized
                is Validation.Bad -> return@synchronizedTransaction false
            }
            if (normalized == config.tameVendorPackages.joinToString(" ")) {
                requestTameReconcileAfterCommit()
                return@synchronizedTransaction true
            }
            val spec = requireNotNull(SettingsRegistry.spec("tame_vendor_packages"))
            val editor = config.editor()
            config.stage(editor, spec, normalized)
            config.commit(editor, afterCommit = ::requestTameReconcileAfterCommit)
        }

    /** Startup/reconfigure wake-up. Config commits use [requestTameReconcileAfterCommit] under the lock. */
    fun requestTameReconcile(): Boolean = config.synchronizedTransaction {
        requestTameReconcileAfterCommit()
    }

    /**
     * One commit-order submission seam. Admission loss is observable but not correctness-critical: the
     * desired config and write-ahead ownership markers are durable, and startup requests another pass.
     */
    private fun requestTameReconcileAfterCommit(): Boolean {
        val admission = tameReconciliation.request()
        when (admission) {
            LatestDispatcher.Admission.ACCEPTED -> Unit
            LatestDispatcher.Admission.COALESCED ->
                FeatureCosts.registry.recordCoalesced(FeatureCostOperation.TAME_MUTATION)
            LatestDispatcher.Admission.REJECTED,
            LatestDispatcher.Admission.CLOSED ->
                FeatureCosts.registry.recordDropped(FeatureCostOperation.TAME_MUTATION)
        }
        return admission == LatestDispatcher.Admission.ACCEPTED ||
            admission == LatestDispatcher.Admission.COALESCED
    }

    private suspend fun respondRemoteAdmission(call: ApplicationCall, command: RemoteControl) {
        // Coordinate injection cannot be made self-approving: an approved tap could target the next
        // approval dialog. Hardened mode therefore trusts taps only from loopback software already on
        // the panel. Non-coordinate navigation remains routine; reboot has its own explicit gate below.
        val loopback = isLoopbackPeer(call.request.origin.remoteAddress)
        if (command is RemoteControl.Tap && config.hardenedSecurityEnabled && !loopback) {
            call.respondText(
                """{"ok":false,"error":"remote-input-disabled"}""",
                ContentType.Application.Json,
                HttpStatusCode.Forbidden,
            )
            return
        }
        val admittedCommand = if (command is RemoteControl.Tap) {
            val requestId = if (command.capture) remoteInputSequence.incrementAndGet() else null
            RemoteControl.Tap(
                x = command.x,
                y = command.y,
                loopback = loopback,
                capture = command.capture,
                requestId = requestId,
                executeBeforeElapsedMs = requestId?.let {
                    SystemClock.elapsedRealtime() + REMOTE_TAP_QUEUE_DEADLINE_MS
                },
                completeBeforeElapsedMs = requestId?.let {
                    SystemClock.elapsedRealtime() + REMOTE_TAP_CAPTURE_TIMEOUT_MS
                },
                completion = requestId?.let { CompletableDeferred() },
            )
        } else command
        val admission = remoteControls.submit(admittedCommand.key, admittedCommand)
        when (admission) {
            LatestDispatcher.Admission.ACCEPTED -> Unit
            LatestDispatcher.Admission.COALESCED ->
                FeatureCosts.registry.recordCoalesced(FeatureCostOperation.REMOTE_INPUT)
            LatestDispatcher.Admission.REJECTED,
            LatestDispatcher.Admission.CLOSED ->
                FeatureCosts.registry.recordDropped(FeatureCostOperation.REMOTE_INPUT)
        }
        FeatureCosts.registry.setBacklog(FeatureCostOperation.REMOTE_INPUT, remoteControls.pendingCount())
        (admittedCommand as? RemoteControl.Tap)?.requestId?.let { requestId ->
            Log.i(TAG, "remote input id=$requestId admission=${admission.name.lowercase()} " +
                "backlog=${remoteControls.pendingCount()}")
        }
        if (admission == LatestDispatcher.Admission.ACCEPTED ||
            admission == LatestDispatcher.Admission.COALESCED
        ) {
            val tap = admittedCommand as? RemoteControl.Tap
            if (tap?.completion == null || tap.requestId == null) {
                call.respondText("accepted\n", status = HttpStatusCode.Accepted)
            } else {
                // The worker owns the semantic deadline. A larger response grace prevents an ambiguous
                // HTTP timeout from racing a still-running, exactly-once input/capture operation.
                val result = withTimeoutOrNull(REMOTE_TAP_CAPTURE_RESPONSE_TIMEOUT_MS) {
                    tap.completion.await()
                }
                    ?: TapCaptureResult.CompletionUnknown()
                respondTapCapture(call, tap.requestId, result)
            }
        } else {
            call.respondText(
                "control queue busy\n",
                status = if (stopping) HttpStatusCode.ServiceUnavailable else HttpStatusCode.Conflict,
            )
        }
    }

    private suspend fun respondTapCapture(
        call: ApplicationCall,
        requestId: Long,
        result: TapCaptureResult,
    ) {
        val outcome = when (result) {
            is TapCaptureResult.Success -> "success"
            is TapCaptureResult.TapFailed -> "tap-failed"
            is TapCaptureResult.ScreenshotFailed -> "screenshot-unavailable"
            is TapCaptureResult.CompletionUnknown -> "completion-unknown"
            TapCaptureResult.HardenedRefusal -> "remote-input-disabled"
            TapCaptureResult.Expired -> "tap-expired"
            TapCaptureResult.NotExecuted -> if (stopping) "control-plane-stopping" else "tap-superseded"
        }
        Log.i(TAG, "remote input id=$requestId response=$outcome")
        respondTapCaptureResult(call, requestId, result, stopping) { png ->
            withContext(Dispatchers.IO) { cacheScreenshot(png) }
        }
    }

    private suspend fun respondAutoBrightnessAction(
        call: ApplicationCall,
        action: AutoBrightnessHttpAction,
    ) {
        call.respondText(
            action.json,
            ContentType.Application.Json,
            HttpStatusCode.fromValue(action.statusCode),
        )
    }

    /**
     * Registry metadata for generating the Configure form (type/group/tier/scope/options/range +
     * whether the setting is an HA entity and currently exposed), capability-gated to this panel.
     * Values themselves come from GET /config; this endpoint is metadata only.
     */
    private fun configSchemaJson(): String = configSchemaJson(catalogueLoader.strings(AppLocale.ENGLISH))

    private fun configSchemaJson(strings: AppStrings): String {
        fun s(v: String) = Json.str(v)
        val caps = liveCapabilities(snapStaleOk().caps) // learned eligibility is fail-closed and live
        val hints = autoHints(strings)   // what blank ("auto") package fields resolve to → field placeholder
        val displaySizingAvailable = caps.canSetDisplay
        // Include the settable settings PLUS the read-only HA sensors (diagnostics): the latter carry
        // no editable value but still render an expose pip, so the user can opt them into HA.
        val schemaSpecs = SettingsRegistry.schemaVisibleSpecs(caps)
        val items = schemaSpecs.joinToString(",") { spec ->
            val opts = spec.optionsFor(caps).joinToString(",") { s(it) }
            val isHa = spec.ha != null
            val placeholder = hints[spec.key]?.let {
                formattedString(strings, "configure.option.auto_detail", "value" to it)
            } ?: when (spec.key) {
                "manufacturer" -> profile.manufacturer
                "model" -> profile.model
                else -> null
            }?.takeIf { it.isNotBlank() }
            // Resolve every value that needs a quote — a key comparison, or the bare JSON `null` token —
            // BEFORE the template below, so each interpolation is a plain identifier. Nesting a quoted
            // literal inside ${...} is valid Kotlin, but it reads as though it were string data that
            // someone forgot to escape, and it has twice been "corrected" to \" — which is a parse error,
            // because ${...} holds code, and which takes the whole module's compilation down with it
            // (Kotlin loses the enclosing class, so every companion member reports as unresolved).
            // Bare identifiers leave nothing to second-guess. Guarded by StringTemplateEscapeContractTest.
            val nullJson = "null"
            val autoSleepActivityHidden = spec.key == "auto_sleep_activity" && !config.autoSleep
            val available = spec.availableWhen(caps) && !autoSleepActivityHidden
            val displaySizing = spec.key == "dashboard_zoom" && displaySizingAvailable
            val pickerJson = spec.picker?.let { s(it) } ?: nullJson
            val minJson = spec.min?.toString() ?: nullJson
            val maxJson = spec.max?.toString() ?: nullJson
            val stepJson = spec.step?.toString() ?: nullJson
            val sized = spec.type == SettingType.STRING || spec.type == SettingType.PASSWORD
            val maxLengthJson = if (sized) spec.maxChars.toString() else nullJson
            val exposed = if (isHa) config.haExposed(spec.key, spec.haExposedByDefault) else false
            val placeholderJson = placeholder?.let { s(it) } ?: nullJson
            val label = strings.resolve(spec.labelKey)
            val helpKey = spec.helpKeyFor(caps)
            val help = helpKey?.let(strings::resolve)
            val helpKeyJson = helpKey?.let(::s) ?: nullJson
            val helpLanguageJson = help?.language?.let(::s) ?: nullJson
            "{" +
                "\"key\":${s(spec.key)}," +
                "\"type\":${s(spec.type.name)}," +
                "\"group\":${s(spec.group)}," +
                "\"labelKey\":${s(spec.labelKey)}," +
                "\"helpKey\":$helpKeyJson," +
                "\"label\":${s(label.text)}," +
                "\"labelLanguage\":${s(label.language)}," +
                "\"help\":${s(help?.text.orEmpty())}," +
                "\"helpLanguage\":$helpLanguageJson," +
                "\"default\":${s(spec.default)}," +
                "\"tier\":${s(spec.tierFor(caps).name)}," +
                "\"scope\":${s(spec.scope.name)}," +
                "\"secret\":${spec.secret}," +
                "\"readOnly\":${spec.readOnly}," +
                "\"available\":$available," +
                "\"displaySizingAvailable\":$displaySizing," +
                "\"options\":[$opts]," +
                "\"picker\":$pickerJson," +
                "\"min\":$minJson," +
                "\"max\":$maxJson," +
                "\"step\":$stepJson," +
                "\"maxLength\":$maxLengthJson," +
                "\"ha\":$isHa," +
                "\"exposed\":$exposed," +
                "\"placeholder\":$placeholderJson" +
                "}"
        }
        return "[$items]"
    }

    /** A setting's effective current value: controller-sourced live state where it exists, identity
     *  fields resolved (panel_id auto-derives when unset), else the persisted value. */
    private fun effectiveValue(spec: io.github.maxlyth.hapaneld.config.SettingSpec, live: Map<String, String>): String =
        live[spec.key] ?: when (spec.key) {
            "panel_id" -> config.panelId
            "friendly_name" -> config.friendlyName
            else -> config.getRaw(spec)
        }

    /** Registry-driven current values (typed JSON; secrets blanked) for the Configure form. */
    private fun settingsValuesJson(): String {
        fun s(v: String) = Json.str(v)
        val live = snapStaleOk().live   // controller-sourced keys via the snapshot, not fresh su probes
        val parts = SettingsRegistry.settable().joinToString(",") { spec ->
            val raw = effectiveValue(spec, live)
            val v = when {
                spec.secret -> "\"\""
                spec.type == SettingType.BOOL -> if (raw.toBoolean()) "true" else "false"
                spec.type == SettingType.INT || spec.type == SettingType.LONG ->
                    raw.toLongOrNull()?.toString() ?: s(raw)
                spec.type == SettingType.FLOAT -> raw.toDoubleOrNull()?.toString() ?: s(raw)
                else -> s(raw)
            }
            "${s(spec.key)}:$v"
        }
        return "{$parts}"
    }

    /** Per-key HA-exposure flags for every HA-capable setting (for the inline expose pips). */
    private fun haExposeJson(): String {
        val parts = SettingsRegistry.SPECS.filter { it.ha != null }.joinToString(",") { spec ->
            "\"${spec.key}\":${config.haExposed(spec.key, spec.haExposedByDefault)}"
        }
        return "{$parts}"
    }

    private fun exposureSpec(key: String) = key.takeIf { it.startsWith("ha_expose_") }
        ?.removePrefix("ha_expose_")
        ?.let(SettingsRegistry::spec)
        ?.takeIf { it.ha != null }

    // ---- config bundles (export / validated import) + on-panel revision history ----------------

    /** Current registry values as a flat map (skips transient inputs; controller-sourced settings
     *  read their live state). The basis for export, the pre-change snapshot, and the dry-run diff. */
    private fun currentValues(): Map<String, String> = currentValues(configLiveValues())

    private fun currentValues(live: Map<String, String>): Map<String, String> {
        val m = projectConfigSnapshot(
            specs = SettingsRegistry.settable(),
            zigbeeRouterConfigured = config.zigbeeRouterConfigured,
            effectiveValue = { effectiveValue(it, live) },
        )
        SettingsRegistry.SPECS.filter { it.ha != null }.forEach { spec ->
            m[SettingsRegistry.exposureKey(spec)] = config.haExposed(spec.key, spec.haExposedByDefault).toString()
        }
        return m
    }

    /** Complete effective values for direct POST equality. Unlike export/revision snapshots this includes
     * transient and untouched hardware-backed settings, and pending durable intent supersedes observed state. */
    private fun directMutationValues(): Map<String, String> {
        val live = configLiveValues()
        return LinkedHashMap<String, String>().apply {
            SettingsRegistry.settable().forEach { spec -> put(spec.key, effectiveValue(spec, live)) }
            SettingsRegistry.SPECS.filter { it.ha != null }.forEach { spec ->
                put(SettingsRegistry.exposureKey(spec), config.haExposed(spec.key, spec.haExposedByDefault).toString())
            }
            putAll(pendingLiveSettings())
        }
    }

    /** Page shells and liveness use only persisted/non-privileged controller state. */
    /**
     * Live inputs for [SetupJourney], all read from memory.
     *
     * The MQTT state arrives as its canonical token rather than the info page's prose. Mapping prose back
     * to a state would reintroduce exactly the two-vocabulary drift the adapter exists to remove, and its
     * failure mode is invisible: an unrecognised string reads as "still connecting", so guidance silently
     * stops and the user waits forever.
     *
     * The renderer snapshot uses the same resolver the launcher does, so what setup reports and what the
     * panel actually opens cannot disagree.
     */
    private fun setupJourneyInputs(): SetupJourney.Inputs {
        val resolved = system.resolveDashboard(config.dashboardPackage)
        val renderer = when {
            resolved == SystemController.BUILTIN_DASHBOARD -> SetupJourney.RendererChoice.Builtin
            resolved.isBlank() -> SetupJourney.RendererChoice.Unresolved
            else -> SetupJourney.RendererChoice.Foreign(resolved, installed = true)
        }
        val builtin = renderer is SetupJourney.RendererChoice.Builtin
        val fingerprint = setupProofFingerprint()
        val proof = when {
            builtin && BuiltinDashboard.frontendEverConnected -> SetupJourney.RenderProof(
                source = SetupJourney.ProofSource.BUILTIN_FRONTEND_CONNECTED,
                certain = true,
                observedAtMs = BuiltinDashboard.lastFrontendConnectedAtMs.takeIf { it >= 0 },
                fingerprint = fingerprint,
            )
            // A human said so, and against this exact configuration — the only proof a foreign renderer
            // can ever have. A stale attestation (fingerprint mismatch) is NONE, not "stale proof": the
            // journey should simply ask again rather than explain bookkeeping.
            config.setupRenderAttestation.isNotBlank() && config.setupRenderAttestation == fingerprint ->
                SetupJourney.RenderProof(
                    source = SetupJourney.ProofSource.USER_ATTESTED,
                    certain = true,
                    observedAtMs = null,
                    fingerprint = fingerprint,
                )
            else -> SetupJourney.RenderProof()
        }
        // The pre-existing-install inference is only valid BEFORE guided setup has begun. The wizard
        // itself writes a broker at step 2, so inferring "upgraded install" from durable config alone
        // force-satisfied the later question stages MID-JOURNEY: on the first hardware walk the journey
        // reported complete the instant the HA token committed, the sign-in callback sent the browser to
        // Configure, the dashboard and filter pages never showed, and the panel deadlocked on its hold
        // screen (whose predicate has no such escape) while this authority claimed nothing was needed.
        // Identity confirmation is the begin-marker: a fresh panel records it at step 1, which switches
        // the inference off for the rest of that journey; a genuinely pre-existing install never confirms
        // identity through the wizard, so it keeps the escape until the startup migration stamps its
        // durable flags. The identity input itself still uses the raw inference — that is the upgrade
        // case the inference exists for.
        val preTracking = !config.setupIdentityConfirmed && panelConfiguredBeforeSetupTracking()
        return SetupJourney.Inputs(
            identityConfirmed = config.setupIdentityConfirmed || panelConfiguredBeforeSetupTracking(),
            panelId = config.panelId,
            brokerConfigured = config.mqttBroker.isNotBlank(),
            mqttUserConfigured = config.mqttUser.isNotBlank(),
            mqttPasswordConfigured = config.mqttPassword.isNotEmpty(),
            mqtt = SetupJourney.MqttSetupState.of(mqttState()),
            renderer = renderer,
            haUrl = config.haUrl,
            haCredentialed = config.haToken.isNotBlank() || config.haRefreshToken.isNotBlank(),
            haOAuthInFlight = haOAuthFlow.pendingCount() > 0,
            discovery = lastHaDiscovery,
            // Uses the true engine major from the WebView user agent, not the package stamp, so a panel
            // already swapped to a LineageOS/Cromite build is not accused of being ancient because the
            // provider still reports the OEM version.
            webViewTooOld = builtin && webViewTooOldOnce,
            webViewFixable = profile.recommendedWebView != null,
            entityFilterAnswered = config.setupEntityFilterAnswered || preTracking,
            homeDashboardChosen = config.setupHomeDashboardChosen || preTracking,
            proof = proof,
            currentFingerprint = fingerprint,
        )
    }

    /**
     * The entity-filter question's supporting facts: how many entities Home Assistant would send, and how
     * much panel there is to receive them.
     *
     * The count is a live reading — a scan in flight reports its running total so the wizard can show the
     * number climbing while the user reads the question, and only a completed scan produces a settled
     * verdict. The tier comes from the profile's declared SoC where there is one and from the platform
     * otherwise; neither costs a probe.
     */
    /** Sticky across catalog re-keys; see the comment in entityFilterVerdict(). */
    @Volatile private var lastSettledEntityCount = 0

    /** The panel's chip as the filter question shows it: model and core layout, nothing else. */
    private fun entityFilterTierLabel(): String {
        val soc = profile.soc ?: return ""
        val cores = soc.cpuCores.takeIf { it.isNotEmpty() }
            ?.joinToString(" + ") { "${it.count}× ${it.architecture.removePrefix("Arm ")}" }
        return listOfNotNull(soc.model, cores).joinToString(" · ")
    }

    private fun entityFilterVerdict(): EntityFilterAdvice.Verdict {
        val progress = entityLearning.scanProgress()
        val settled = entityLearning.catalogCount()
        val (tier, source) = EntityFilterAdvice.tier(
            soc = profile.soc,
            sdkInt = android.os.Build.VERSION.SDK_INT,
            cores = Runtime.getRuntime().availableProcessors(),
            totalRamBytes = runCatching {
                val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem
            }.getOrDefault(0L),
        )
        // A scan in flight wins even when an older total exists: the user is watching this scan, and showing
        // last week's number while a new one runs would be stale the moment it finished.
        // No scan running AND no settled catalog = NO READING — a live Home Assistant always has
        // entities, so a literal zero is an impossible value, not a measurement. Presented as still
        // counting (the answer route kicks the scan the moment this step becomes next), because the
        // alternative shipped: a fresh panel showed "0 entities · Measured · all fine" on hardware.
        // The last settled count is STICKY across a catalog re-key (the sync's instance-UUID adoption
        // momentarily nulls the snapshot): a settled red verdict briefly flashing 0/green mid-page read
        // as flakiness on hardware. Serve the remembered number with the counting tag instead.
        if (settled != null) lastSettledEntityCount = settled
        val counting = progress != null || settled == null
        return EntityFilterAdvice.advise(
            tier = tier,
            tierSource = source,
            entityCount = progress ?: settled ?: lastSettledEntityCount,
            counting = counting,
        )
    }

    /**
     * Whether this panel was already set up before identity confirmation began being recorded.
     *
     * The confirmation flag defaults false, so without this every existing install would be told its next
     * step is to confirm a panel name the user chose long ago — wrong on its face, and on a working panel
     * it would drag a finished journey back to step one. Found immediately on the first canary deploy: an
     * established panel reported `next: identity` while its real gap was a missing Home Assistant login.
     *
     * Durable configuration is the evidence. A broker, a Home Assistant URL or an explicit renderer can
     * only be present because somebody configured this panel, and a genuinely fresh install has none of
     * them — so an upgrade infers the consent it could not have recorded, while a new panel still asks.
     * Read-only by design: a GET must not write, and the inference is stable enough not to need storing.
     */
    /** See GET /config/probe-broker. Bounded: one DNS resolve + one 2s TCP connect attempt. */
    private fun probeBrokerJson(url: String): String {
        val ep = io.github.maxlyth.hapaneld.util.BrokerEndpoint.endpoint(url.trim())
            ?: return """{"ok":false,"error":"invalid-url"}"""
        val resolved = runCatching { java.net.InetAddress.getByName(ep.host).hostAddress }.getOrNull()
            ?: return "{\"ok\":false,\"error\":\"unresolvable\",\"host\":${jsonStr(ep.host)}}"
        val reachable = runCatching {
            java.net.Socket().use { it.connect(java.net.InetSocketAddress(ep.host, ep.port), 2_000); true }
        }.getOrDefault(false)
        return "{\"ok\":$reachable,\"host\":${jsonStr(ep.host)},\"port\":${ep.port}," +
            "\"resolved\":${jsonStr(resolved)}" +
            if (reachable) "}" else ",\"error\":\"unreachable\"}"
    }

    /**
     * See POST /config/probe-log-sink. Bounded: one DNS resolve plus one 2s connect and exactly one
     * marked record, in the real wire format of the selected transport.
     *
     * Every transport transmits. An earlier revision reported a bare TCP `connect()` as success, but
     * a connect succeeds against *any* listening socket — an MQTT broker, an SSH daemon, a mistyped
     * port belonging to something else entirely — so "Connected" asserted a working log sink on
     * evidence that could not distinguish one. Writing a real RFC5424 frame or a real NDJSON POST is
     * the cheapest check that actually discriminates.
     *
     * `delivered` says whether the transport itself confirmed anything. UDP is always false: a send
     * that returns without error proves only that the panel handed the datagram to the network, and
     * calling that success would rebuild in the UI the very false confidence this change removes.
     * Every transport returns the marker regardless, because searching the collector for it is the
     * only end-to-end confirmation that exists.
     *
     * Takes [panelId] as a parameter rather than reading `config`, so it stays free of instance state
     * and can be exercised the same way [probeBrokerJson] is, without the Android-backed server graph.
     */
    private fun probeLogSinkJson(host: String, port: Int, protocol: String, panelId: String): String {
        val ep = LogShipEndpoint.resolve(host, port, protocol)
        if (ep.host.isBlank()) return """{"ok":false,"error":"no-host"}"""
        if (ep.port !in 1..65535) return """{"ok":false,"error":"invalid-port"}"""
        val head = "\"host\":${jsonStr(ep.host)},\"port\":${ep.port}," +
            "\"protocol\":${jsonStr(ep.protocol)}"
        val marker = "ha-paneld-sink-probe-${System.currentTimeMillis().toString(36)}"
        val name = panelId.ifBlank { "panel" }
        val timestamp = probeTimestamp()
        val build = LogShipRecord.Build.CURRENT
        val payload = when (ep.protocol) {
            LogShipEndpoint.HTTP -> LogShipRecord.jsonEvent(timestamp, name, build, marker)
            LogShipEndpoint.SYSLOG_UDP -> LogShipRecord.syslogFrame(14, timestamp, name, build, marker).trimEnd('\n')
            else -> LogShipRecord.syslogFrame(14, timestamp, name, build, marker)
        }.toByteArray(Charsets.UTF_8)
        val result = NetworkLogSinkFactory.probe(
            LogShipTarget(ep.host, ep.port, ep.protocol, panelId),
            payload,
            timeoutMs = LOG_SINK_DNS_TIMEOUT_MS,
        )
        val body = "$head" +
            (result.candidate?.let { ",\"resolved\":${jsonStr(it.hostAddress)}" } ?: "") +
            ",\"marker\":${jsonStr(marker)}" +
            (result.status?.let { ",\"status\":$it" } ?: "")
        return if (result.ok) {
            "{\"ok\":true,\"delivered\":${result.delivered},$body}"
        } else {
            val error = when {
                result.status != null -> "http-${result.status}"
                result.error == "unresolvable" -> "unresolvable"
                ep.protocol == LogShipEndpoint.SYSLOG_UDP -> "send-failed"
                else -> "unreachable"
            }
            "{\"ok\":false,\"error\":${jsonStr(error)},$body}"
        }
    }

    private fun probeTimestamp(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date())

    /**
     * The single owner of the HA-canonical area rule: adopt what Home Assistant reports, or push a pending
     * request when HA has none and this session may write. Called by the area endpoint and by the
     * unprompted convergence loop below, so the rule has exactly one implementation.
     */
    private data class HaAreaSnapshot(
        val ownerKey: String,
        val deviceUid: String,
        val panelId: String,
        val localArea: String,
        val userOverride: Boolean = false,
    )

    private fun captureHaAreaSnapshot(): HaAreaSnapshot = HaAreaSnapshot(
        ownerKey = entityLearning.haAreaOwnerKey(),
        deviceUid = config.deviceUid,
        panelId = config.panelId,
        localArea = config.haArea,
        userOverride = config.haAreaUserOverride,
    )

    /**
     * A briefly-held copy of the area registry, this device's row and the account's admin flag.
     *
     * All three change infrequently, yet the picker asked Home Assistant for them on EVERY
     * Configure paint: one authenticated WebSocket session per page load, and one per reload through an
     * upgrade round, which is what made the control look like it was constantly refreshing. Only successful
     * reads are held, so a failed query never becomes authoritative; the unprompted convergence pass
     * deliberately bypasses this, because noticing an admin's change in Home Assistant is its whole job.
     */
    private data class HaAreaCatalogCacheEntry(
        val key: String,
        val cachedAtMs: Long,
        val catalog: EntityLearningManager.HaAreaCatalog,
    )

    @Volatile private var haAreaCatalogCache: HaAreaCatalogCacheEntry? = null

    private fun haAreaCatalogKey(snapshot: HaAreaSnapshot): String =
        "${snapshot.ownerKey}|${snapshot.deviceUid}|${snapshot.panelId}"

    private fun cacheHaAreaCatalog(
        snapshot: HaAreaSnapshot,
        catalog: EntityLearningManager.HaAreaCatalog,
        nowMs: Long = System.nanoTime() / 1_000_000L,
    ) {
        synchronized(directConfigMutationLock) {
            if (catalog.queried && catalog.ownerKey == snapshot.ownerKey && ownsHaAreaSnapshot(snapshot)) {
                haAreaCatalogCache = HaAreaCatalogCacheEntry(haAreaCatalogKey(snapshot), nowMs, catalog)
            }
        }
    }

    private suspend fun haAreaCatalogFor(
        snapshot: HaAreaSnapshot,
        fresh: Boolean = false,
    ): EntityLearningManager.HaAreaCatalog {
        val key = haAreaCatalogKey(snapshot)
        val now = System.nanoTime() / 1_000_000L
        if (!fresh) {
            haAreaCatalogCache?.takeIf { entry ->
                haAreaCacheEntryUsable(entry.key, key, entry.cachedAtMs, now, HA_AREA_CATALOG_TTL_MS)
            }?.catalog?.let { return it }
        }
        val catalog = entityLearning.haAreaCatalog(snapshot.deviceUid, snapshot.panelId)
        cacheHaAreaCatalog(snapshot, catalog, now)
        return catalog
    }

    /** A local area change must never be answered from a catalog read before it. */
    private fun invalidateHaAreaCatalogCache() {
        haAreaCatalogCache = null
    }

    /** Populate the config-response seed without making config rendering wait on Home Assistant. */
    private fun warmHaAreaCatalogInBackground() {
        synchronized(haAreaWarmLock) {
            if (stopping || haAreaWarmJob?.isActive == true) return
            haAreaWarmJob = scope.launch {
                val credentialed = config.haToken.isNotBlank() || config.haRefreshToken.isNotBlank()
                if (!HaAreaProtocol.canQueryUnprompted(config.haUrl, credentialed)) return@launch
                runCatching { haAreaCatalogFor(captureHaAreaSnapshot(), fresh = true) }
                    .onFailure { Log.w(TAG, "ha-area: catalog warm failed", it) }
            }
        }
    }

    private fun ownsHaAreaSnapshot(snapshot: HaAreaSnapshot): Boolean =
        snapshot.ownerKey == entityLearning.haAreaOwnerKey() &&
            snapshot.deviceUid == config.deviceUid && snapshot.panelId == config.panelId &&
            snapshot.localArea == config.haArea && snapshot.userOverride == config.haAreaUserOverride

    private suspend fun applyHaAreaPrecedence(
        snapshot: HaAreaSnapshot,
        catalog: EntityLearningManager.HaAreaCatalog,
        allowWriteBack: Boolean = true,
    ): EntityLearningManager.HaAreaCatalog {
        if (!catalog.queried || !catalog.device.found || catalog.ownerKey != snapshot.ownerKey ||
            !ownsHaAreaSnapshot(snapshot)
        ) return catalog
        when (HaAreaProtocol.reconcile(snapshot.localArea, catalog.device.areaName, catalog.admin, snapshot.userOverride)) {
            HaAreaProtocol.ReconcileAction.ADOPT_HA -> withContext(Dispatchers.IO) {
                synchronized(directConfigMutationLock) {
                    if (!ownsHaAreaSnapshot(snapshot)) return@synchronized
                    config.synchronizedTransaction {
                        if (!ownsHaAreaSnapshot(snapshot)) return@synchronizedTransaction false
                        Log.i(TAG, "ha-area: adopting Home Assistant's area for this device")
                        // Adoption is only reachable for a non-override value, or for an override that
                        // matches HA in a different casing — either way nothing local-only remains.
                        config.commitHaArea(catalog.device.areaName, userOverride = false)
                    }
                }
            }
            HaAreaProtocol.ReconcileAction.WRITE_BACK -> if (allowWriteBack && ownsHaAreaSnapshot(snapshot)) {
                val moved = entityLearning.applyRequestedArea(
                    snapshot.deviceUid,
                    snapshot.panelId,
                    snapshot.localArea,
                    snapshot.ownerKey,
                )
                if (moved && ownsHaAreaSnapshot(snapshot)) {
                    invalidateHaAreaCatalogCache()
                    val after = entityLearning.haAreaCatalog(snapshot.deviceUid, snapshot.panelId)
                    cacheHaAreaCatalog(snapshot, after)
                    return applyHaAreaPrecedence(snapshot, after, allowWriteBack = false)
                }
            }
            HaAreaProtocol.ReconcileAction.KEEP -> {
                // An override HA has come to agree with (exactly) is no longer overriding anything.
                if (snapshot.userOverride && catalog.device.areaName == snapshot.localArea &&
                    ownsHaAreaSnapshot(snapshot)
                ) withContext(Dispatchers.IO) {
                    synchronized(directConfigMutationLock) {
                        if (!ownsHaAreaSnapshot(snapshot)) return@synchronized
                        config.synchronizedTransaction {
                            if (!ownsHaAreaSnapshot(snapshot)) return@synchronizedTransaction false
                            config.commitHaArea(snapshot.localArea, userOverride = false)
                        }
                    }
                }
            }
        }
        return catalog
    }

    /**
     * Converge the panel's area WITHOUT waiting for a person.
     *
     * "Home Assistant is canonical" was implemented only at read time, and every reader was a UI control —
     * the Configure area picker and the wizard's dashboard step. A panel nobody had opened that dropdown on
     * therefore never adopted anything: affected panels held a blank `ha_area` while their HA
     * devices sat in real areas, so every surface honestly reported "No area" and discovery published no
     * `suggested_area`. One unprompted
     * pass after start, then a slow repeat, is enough: the area of a wall panel changes about never, and the
     * read is one authenticated WebSocket round trip.
     */
    private fun startHaAreaConvergence() {
        if (haAreaJob?.isActive == true) return
        warmHaAreaCatalogInBackground()
        haAreaJob = scope.launch {
            delay(HA_AREA_FIRST_PASS_MS)
            while (true) {
                val credentialed = config.haToken.isNotBlank() || config.haRefreshToken.isNotBlank()
                if (HaAreaProtocol.canQueryUnprompted(config.haUrl, credentialed)) {
                    val snapshot = captureHaAreaSnapshot()
                    runCatching {
                        applyHaAreaPrecedence(snapshot, haAreaCatalogFor(snapshot, fresh = true))
                    }
                        .onFailure { Log.w(TAG, "ha-area: unprompted convergence failed", it) }
                }
                delay(HA_AREA_REPEAT_MS)
            }
        }
    }

    /**
     * Evidence this panel predates setup tracking, for the journey's purposes.
     *
     * The Home Assistant URL term is delegated to the shared [panelConfiguredBeforeSetupTracking] so the
     * migration's copy of this question and this one cannot disagree about a handed-over URL. The other
     * two terms are the journey's own.
     */
    private fun panelConfiguredBeforeSetupTracking(): Boolean =
        io.github.maxlyth.hapaneld.panelConfiguredBeforeSetupTracking(
            haUrl = config.haUrl,
            haSetupHandover = config.haSetupHandover,
            otherEvidence = config.mqttBroker.isNotBlank() || config.dashboardPackage.isNotBlank(),
        )

    /**
     * Identity a render proof is valid for. Changing the endpoint, the renderer or the credentialled
     * account means the panel has not been shown to work as it is now configured, so the proof must not
     * carry over.
     *
     * Built only from non-secret values. `HaAuthSnapshot.stableOwner()` would be the natural identity but
     * it carries the refresh and access tokens, and this fingerprint is computed on the request path of an
     * unauthenticated LAN endpoint — materialising token text there risks it reaching a log or a heap dump
     * for no benefit, since the hash is all that is ever emitted. The client id plus the credential's
     * expiry stamp move whenever the account or session actually changes, and a token merely rotated for
     * the same account leaves the panel rendering the same dashboard, so keeping the proof is correct.
     */
    private fun setupProofFingerprint(): String = io.github.maxlyth.hapaneld.config.ConfigHash.of(
        mapOf(
            "ha_url" to config.haUrl.trim().trimEnd('/'),
            "dashboard_package" to config.dashboardPackage,
            "ha_client_id" to config.haClientId,
            "ha_credentialed" to (config.haToken.isNotBlank() || config.haRefreshToken.isNotBlank()).toString(),
        ),
    )

    private fun setupJourneyJson(): String {
        val journey = SetupJourney.evaluate(setupJourneyInputs())
        val steps = journey.steps.joinToString(",") { step ->
            "{\"stage\":${jsonStr(step.stage.name.lowercase())}," +
                "\"status\":${jsonStr(step.status.name.lowercase())}," +
                "\"blocking\":${step.blocking}," +
                "\"detail\":${jsonStr(step.detail)}}"
        }
        val next = journey.next?.let { jsonStr(it.name.lowercase()) } ?: "null"
        val discovery = lastHaDiscovery
        val reason = jsonStr(discovery.reason.name.lowercase())
        val explanation = jsonStr(HaDiscovery.unavailableExplanation(discovery).orEmpty())
        // No config-hash token here. It would cost a full settings read on every poll, and it would tell a
        // client nothing the steps do not: the journey is derived from live config, so a change made from
        // another browser already shows up as a changed stage on the next poll.
        // The panel identity rides along (non-secret) so the wizard's name step can prefill without ever
        // touching GET /api/v1/config — the endpoint whose redacted reads have previously wiped credentials
        // when echoed back.
        // `repair` separates a re-armed journey on a panel that once worked from a first run — the wizard
        // hides its numbered-journey framing and says "nothing has been reset" instead of starting over.
        // `entity_filter` carries its own step's facts — the live count, the panel's tier and the resulting
        // recommendation — so the page can render the whole question without ever reading GET /api/v1/config.
        val resolvedRenderer = system.resolveDashboard(config.dashboardPackage)
        val builtinRenderer = resolvedRenderer == SystemController.BUILTIN_DASHBOARD
        val verdict = entityFilterVerdict()
        val entityFilter = "{\"relevant\":$builtinRenderer," +
            "\"enabled\":${config.dashboardEntityLearningEnabled}," +
            "\"answered\":${config.setupEntityFilterAnswered}," +
            "\"count\":${verdict.entityCount}," +
            "\"counting\":${verdict.counting}," +
            "\"tier\":${jsonStr(verdict.tier.name.lowercase())}," +
            "\"tier_source\":${jsonStr(verdict.tierSource.name.lowercase())}," +
            // Model + cores only. The profile's own displayText() appends "introduced YYYY", which is a fact
            // about the chip rather than about this decision and is long enough to wrap the row badly.
            "\"tier_label\":${jsonStr(entityFilterTierLabel())}," +
            "\"level\":${jsonStr(verdict.level.name.lowercase())}," +
            "\"confidence\":${jsonStr(verdict.confidence.name.lowercase())}," +
            "\"recommend_above\":${verdict.bands.recommendAbove}," +
            "\"struggle_above\":${verdict.bands.struggleAbove ?: "null"}," +
            // Deterministic bring-up milestones (all in-memory/store reads): the wizard narrates
            // reading→building→applying→optimising from these instead of going silent post-answer.
            "\"learning\":{\"applied\":${config.dashboardEntityLearningApplied}," +
            "\"scanned\":${entityLearning.scanProgress() ?: -1}," +
            "\"catalog\":${entityLearning.catalogCount() ?: -1}}}"
        // `home_dashboard` carries only the current value and the answered bit — the dashboard LIST and the
        // account default stay on GET /api/v1/config/home-dashboards, which does a live HA round-trip and
        // must never ride along on this poll (SetupStateEndpointContractTest pins the expense rule).
        val homeDashboard = "{\"value\":${jsonStr(config.homeDashboard)}," +
            "\"answered\":${config.setupHomeDashboardChosen}}"
        // `renderer` is the panel's own answer to which renderer its stored selection RESOLVES to, from the
        // same resolver the launcher uses. It exists because a client cannot derive it: a blank
        // `dashboard_package` selects the built-in renderer, so reading the stored value answers a
        // different question. The installer's dashboard seeds are the first caller — they apply only to the
        // built-in renderer, and used to gate on the literal stored string, which refused a blank panel that
        // was in fact running the built-in renderer. `package` is empty when the selection resolves to
        // nothing usable, which a caller must be able to refuse on separately from a foreign renderer.
        val renderer = "{\"builtin\":$builtinRenderer," +
            "\"package\":${jsonStr(RendererResolver.reportedRenderer(resolvedRenderer))}}"
        // `handover` is how the Panel Assistant integration learns, BEFORE it sends anything, that this
        // panel understands a handed-over Home Assistant URL. `supported` is a constant: its absence on an
        // older panel is the whole version-pairing mechanism. That matters because
        // `normalizeConfigPostParameters` refuses an unknown key AND the admission is atomic, so an
        // integration that posted the handover blindly would not merely fail to hand over — it would make
        // the entire POST 400 and drop every other setting in it. The integration reads this, and only
        // sends the handover when it is true.
        //
        // This endpoint is the authority for the verdict too: `url` and `reason` are non-blank only while
        // an address was handed over and did not answer, which is exactly the state the wizard renders as
        // a correction rather than as a blank question. Both are cheap stored reads, so the expense rule
        // SetupStateEndpointContractTest pins is untouched — the probe itself runs on the config POST.
        val handover = "{\"supported\":true," +
            "\"source\":${config.haSetupHandover}," +
            "\"url\":${jsonStr(config.haUrlHandover)}," +
            "\"reason\":${jsonStr(config.haUrlHandoverReason)}}"
        return "{\"complete\":${journey.complete}," +
            "\"handover\":$handover," +
            "\"repair\":${config.setupEverCompleted && !journey.complete}," +
            "\"entity_filter\":$entityFilter," +
            "\"home_dashboard\":$homeDashboard," +
            "\"renderer\":$renderer," +
            "\"next\":$next," +
            "\"panel\":{\"id\":${jsonStr(config.panelId)},\"name\":${jsonStr(config.friendlyName)}}," +
            "\"steps\":[$steps]," +
            "\"discovery\":{\"outcome\":${jsonStr(discovery.outcome.name.lowercase())}," +
            "\"reason\":$reason,\"explanation\":$explanation}}"
    }

    private fun renderConfigConcurrencyHash(): String =
        configConcurrencyHash(currentValues())

    private fun configConcurrencyHash(values: Map<String, String>): String =
        io.github.maxlyth.hapaneld.config.ConfigHash.of(configConcurrencyValues(values))

    private fun revisionValues(
        values: Map<String, String> = currentValues(),
        state: DashboardEntityBackupState = config.dashboardEntityBackupState(),
    ): Map<String, String> = LinkedHashMap(values).apply {
        put("$ENTITY_REVISION_PREFIX.instance_key", state.instanceKey)
        put("$ENTITY_REVISION_PREFIX.instance_origin", state.instanceOrigin)
        put("$ENTITY_REVISION_PREFIX.instance_uuid", state.instanceUuid)
        put("$ENTITY_REVISION_PREFIX.dashboard_path", state.dashboardPath)
        put("$ENTITY_REVISION_PREFIX.filter_ids", state.filterIds)
        put("$ENTITY_REVISION_PREFIX.filter_enabled", state.filterEnabled.toString())
        put("$ENTITY_REVISION_PREFIX.filter_owner", state.filterOwner)
        put("$ENTITY_REVISION_PREFIX.learning_applied", state.learningApplied.toString())
        put("$ENTITY_REVISION_PREFIX.applied_owner", state.appliedOwner)
        put("$ENTITY_REVISION_PREFIX.overrides", state.overrides)
        put("$ENTITY_REVISION_PREFIX.override_owner", state.overrideOwner)
    }

    private fun revisionEntityState(values: Map<String, String>): DashboardEntityBackupState? {
        val fields = values.filterKeys { it.startsWith("$ENTITY_REVISION_PREFIX.") }
        if (fields.isEmpty()) return null
        val obj = org.json.JSONObject()
        for ((key, value) in fields) {
            val name = key.removePrefix("$ENTITY_REVISION_PREFIX.")
            obj.put(name, if (name == "filter_enabled" || name == "learning_applied") {
                SettingValue.parseBool(value) ?: return null
            } else value)
        }
        return runCatching { planEntityBackup(obj) }.getOrNull()
    }

    /** Export a versioned config bundle. Secrets are excluded unless `?include_secrets=1`. */
    private suspend fun handleConfigExport(call: ApplicationCall) {
        val includeSecrets = call.request.queryParameters["include_secrets"] == "1"
        if (includeSecrets && !authorizeSensitive(
                call,
                SensitiveOperation.CONFIG_SECRET_EXPORT,
                exactHttpApprovalPayload(call, sha256Hex(ByteArray(0))),
                "Export settings including stored credentials",
            )
        ) return
        val live = configLiveValues()
        val values = projectConfigSnapshot(
            specs = SettingsRegistry.settable().filter { includeSecrets || !it.secret },
            zigbeeRouterConfigured = config.zigbeeRouterConfigured,
            effectiveValue = { effectiveValue(it, live) },
        )
        SettingsRegistry.SPECS.filter { it.ha != null }.forEach { spec ->
            values[SettingsRegistry.exposureKey(spec)] = config.haExposed(spec.key, spec.haExposedByDefault).toString()
        }
        val bundle = ConfigBundle.fromValues(
            values, exportedAt = System.currentTimeMillis().toString(), exportedBy = config.panelId,
        )
        call.response.headers.append("Content-Disposition", "attachment; filename=\"${config.panelId}-config.json\"")
        call.respondText(bundle.serialize(), ContentType.Application.Json)
    }

    /**
     * Bundle import — BEST-EFFORT by design (a bundle exported from different hardware or a different
     * ha-paneld version must still restore what it can). Parse → migrate to the current schema →
     * scope/secret filter (`?mode=fleet` applies only PORTABLE, non-secret keys; default `restore`
     * applies everything) → validate per-key against the registry: valid keys apply, invalid keys are
     * reported in `errors` and skipped, unknown keys warn and skip. `?strict=1` restores the old
     * all-or-nothing validation behaviour. Apply is ordered in two phases: atomically commit ordinary
     * preferences, then apply controller/hardware-backed live settings and reconfigure. The latter
     * cannot be rolled back across Android settings, sysfs, services, and hardware. `?dry_run=1`
     * returns the diff without writing.
     * Status: "applied" (all valid), "partial" (some skipped as invalid), "rejected" (nothing usable
     * or strict mode with any error).
     */
    private suspend fun handleConfigImport(call: ApplicationCall) {
        val bodyBytes = when (val receipt = receiveBoundedBody(call, MAX_CONFIG_IMPORT_BYTES)) {
            is BoundedBodyReceipt.Received -> receipt.bytes
            BoundedBodyReceipt.TooLarge -> {
                call.respondText("""{"status":"too-large"}""", ContentType.Application.Json, HttpStatusCode.PayloadTooLarge)
                return
            }
            BoundedBodyReceipt.TimedOut -> {
                call.respondText("""{"status":"timeout"}""", ContentType.Application.Json, HttpStatusCode.RequestTimeout)
                return
            }
        }
        val body = String(bodyBytes, Charsets.UTF_8)
        val bundle = ConfigBundle.parse(body)
        if (bundle == null) {
            call.respondText("""{"status":"bad-bundle"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
            return
        }
        if (bundle.kind != ConfigBundle.KIND_CONFIG || bundle.schema < 1) {
            call.respondText("""{"status":"wrong-kind-or-schema"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
            return
        }
        val (migrated, warnings) = Migrations.migrate(bundle.schema, bundle.values)
        val fleet = call.request.queryParameters["mode"] == "fleet"
        val dryRun = call.request.queryParameters["dry_run"] == "1"
        val expectedConfig = call.request.queryParameters["expected_cfg"]?.trim().orEmpty()
        if (expectedConfig.isNotEmpty() && !expectedConfig.matches(Regex("^[a-f0-9]{8}$"))) {
            call.respondText(
                """{"status":"bad-expected-cfg"}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
            return
        }
        val accepted = LinkedHashMap<String, String>()
        val skipped = ArrayList<String>()
        val errors = ArrayList<String>()
        val warn = warnings.toMutableList()
        for ((key, raw) in migrated) {
            val spec = SettingsRegistry.spec(key)
            val exposedSpec = SettingsRegistry.parseExposure(key)
            if (exposedSpec != null) {
                val normalized = SettingValue.parseBool(raw)?.toString()
                if (normalized == null) errors.add("$key: expected a boolean") else accepted[key] = normalized
                continue
            }
            if (spec == null) { warn.add("unknown key skipped: $key"); continue }
            if (spec.readOnly || spec.transient) { skipped.add(key); continue }
            if (fleet && (spec.scope != Scope.PORTABLE || spec.secret)) { skipped.add(key); continue }
            // Same rule the upgrade and the restore apply: a value an older release was allowed to store
            // is read in the form the current validator understands, so an import of an older export
            // carries it across instead of silently dropping it. A route naming a different server is
            // still refused here, exactly as it is there.
            when (val v = SettingValue.validate(spec, restorableSettingValue(key, raw, canonicalHaOrigin(config.haUrl)))) {
                is Validation.Ok -> {
                    // A blank portable HA URL means “renderer not configured” on the source panel. In
                    // fleet mode it must not clear a target's URL and, through import dependencies, its
                    // device-local OAuth credentials. A non-blank common endpoint remains portable.
                    if (fleetImportPreservesTargetLocalValue(fleet, key, v.normalized)) {
                        skipped.add(key)
                        warn.add("blank ha_url skipped in fleet mode to preserve target-local Home Assistant login")
                    } else {
                        accepted[key] = v.normalized
                    }
                }
                is Validation.Bad -> errors.add(v.reason)
            }
        }
        if (preserveUnconfiguredZigbeeOwnership(accepted, config.zigbeeRouterConfigured)) {
            skipped.add("zigbee_router")
            warn.add("legacy zigbee_router=false skipped to preserve untouched vendor gateway ownership")
        }
        // Canonicalise the sink triple before it is previewed OR applied, so a dry run cannot advertise
        // a destination the apply would not write. Applying it here is not what makes the stored fields
        // consistent — Config.stageImportDependencies does that for every applyAccepted path — but doing
        // it before the branch keeps preview and apply the same operation on the same values.
        LogShipEndpoint.canonicalUpdate(accepted, config.logShipHost, config.logShipPort, config.logShipProtocol)
            ?.let { accepted.putAll(it) }
        val strict = call.request.queryParameters["strict"] == "1"
        if ((strict && errors.isNotEmpty()) || (accepted.isEmpty() && errors.isNotEmpty())) {
            call.respondText(importJson("rejected", emptyList(), skipped, warn, errors), ContentType.Application.Json, HttpStatusCode.UnprocessableEntity)
            return
        }
        if (dryRun) {
            val current = currentValues()
            call.respondText(
                configDryRunJson(
                    configPreviewDiff(current, accepted),
                    skipped,
                    warn + errors.map { "would skip (invalid): $it" },
                    configConcurrencyHash(current),
                ),
                ContentType.Application.Json,
            )
            return
        }
        if (rejectHardenedNetworkAdb(call, accepted["network_adb"])) return
        if (accepted.isEmpty()) {
            call.respondText(importJson("no-op", emptyList(), skipped, warn, errors), ContentType.Application.Json)
            return
        }
        val importDigest = sha256Hex(bodyBytes)
        if (!authorizeSensitive(
                call,
                SensitiveOperation.CONFIG_IMPORT,
                exactHttpApprovalPayload(call, importDigest),
                "Import ${accepted.size} panel setting${if (accepted.size == 1) "" else "s"}",
            )
        ) return
        when (val applyResult = applyAccepted(accepted, expectedConfig.ifEmpty { null })) {
            ApplyAcceptedResult.Stale -> {
                val actual = configConcurrencyHash(currentValues())
                call.respondText(
                    """{"status":"stale-preview","expected_cfg":${jsonStr(expectedConfig)},"actual_cfg":${jsonStr(actual)}}""",
                    ContentType.Application.Json,
                    HttpStatusCode.Conflict,
                )
                return
            }
            ApplyAcceptedResult.CommitFailed -> {
                call.respondText(
                    importJson("error", emptyList(), skipped, warn, listOf("configuration commit failed")),
                    ContentType.Application.Json,
                    HttpStatusCode.InternalServerError,
                )
                return
            }
            is ApplyAcceptedResult.CompatibilityRefused -> {
                call.respondText(
                    importJson("database-compatibility-refused", emptyList(), skipped, warn, listOf(applyResult.message)),
                    ContentType.Application.Json,
                    HttpStatusCode.Conflict,
                )
                return
            }
            ApplyAcceptedResult.Applied -> Unit
        }
        val status = if (errors.isEmpty()) "applied" else "partial"
        call.respondText(importJson(status, accepted.keys.toList(), skipped, warn, errors), ContentType.Application.Json)
    }

    /** Apply a validated value set in two ordered phases: snapshot current → atomically commit ordinary
     *  preference fields → run live controller/hardware persistence and side-effects → reconfigure.
     *  External state cannot be rolled back and only starts after a successful preference commit.
     *  Returns false without starting side-effects when the preference commit fails. */
    private sealed interface ApplyAcceptedResult {
        data object Applied : ApplyAcceptedResult
        data object Stale : ApplyAcceptedResult
        data object CommitFailed : ApplyAcceptedResult
        data class CompatibilityRefused(val message: String) : ApplyAcceptedResult
    }

    private suspend fun applyAccepted(
        accepted: Map<String, String>,
        expectedConfig: String? = null,
        expectedRevision: String? = null,
        expectedHaAuthOwner: HaAuthOwner? = null,
        expectedHaOAuthEpoch: Long? = null,
        entityState: DashboardEntityBackupState? = null,
        existingOperationTicket: InstallProgress.Ticket? = null,
        onDurableRevision: (String) -> Unit = {},
        afterCommitBeforeRenderer: (RendererConfigEffects, String) -> Unit = { _, _ -> },
        afterApply: () -> Unit = {},
    ): ApplyAcceptedResult = withContext(Dispatchers.IO) {
        if (existingOperationTicket != null && !InstallProgress.owns(existingOperationTicket)) {
            return@withContext ApplyAcceptedResult.CompatibilityRefused(
                "the owning panel operation is no longer active",
            )
        }
        val configMutationTicket = if (existingOperationTicket == null) {
            InstallProgress.startConfigMutation()
                ?: return@withContext ApplyAcceptedResult.CompatibilityRefused(
                    "another panel operation owns configuration admission",
                )
        } else null
        var channelMutation: SelfUpdateChannelMutation? = null
        var preparedChannel: SelfUpdateChannelPreflight.Ready? = null
        var channelCommitted = false
        val previousChannel = config.updateChannel
        try {
        if (existingOperationTicket != null && restoreChangesUpdateChannel(config.updateChannel, accepted)) {
            return@withContext ApplyAcceptedResult.CompatibilityRefused(
                "backup restore cannot change an active self-update channel",
            )
        }
        channelMutation = selfUpdateChannelMutation(config.updateChannel, config.selfUpdate, accepted)
        channelMutation?.let { request ->
            when (val preflight = prepareSelfUpdateChannel(request.requested, request.force)) {
                is SelfUpdateChannelPreflight.Ready -> {
                    if (preflight.requiresRecovery) {
                        preflight.close()
                        return@withContext ApplyAcceptedResult.CompatibilityRefused(
                            "An update-channel change cannot recover an older database snapshot.",
                        )
                    }
                    preparedChannel = preflight
                }
                is SelfUpdateChannelPreflight.UpToDate -> Unit
                is SelfUpdateChannelPreflight.Refused,
                is SelfUpdateChannelPreflight.Unresolved ->
                    return@withContext ApplyAcceptedResult.CompatibilityRefused(preflight.message)
            }
        }
        val result = rendererPreparation.transaction {
            var earlyResult: ApplyAcceptedResult? = null
            var committed: AcceptedCommit? = null
            config.synchronizedTransaction {
                if (expectedConfig != null &&
                    configConcurrencyHash(currentValues()) != expectedConfig
                ) {
                    earlyResult = ApplyAcceptedResult.Stale
                    return@synchronizedTransaction
                }
                if (expectedRevision != null &&
                    io.github.maxlyth.hapaneld.config.ConfigHash.of(revisionValues()) != expectedRevision
                ) {
                    earlyResult = ApplyAcceptedResult.Stale
                    return@synchronizedTransaction
                }
                if (expectedHaAuthOwner != null && config.haAuthSnapshot().stableOwner() != expectedHaAuthOwner) {
                    earlyResult = ApplyAcceptedResult.Stale
                    return@synchronizedTransaction
                }
                if (expectedHaOAuthEpoch != null && !config.isHaOAuthAttemptCurrent(expectedHaOAuthEpoch)) {
                    earlyResult = ApplyAcceptedResult.Stale
                    return@synchronizedTransaction
                }
                val previous = ConfigBundle.fromValues(
                    revisionValues(), kind = ConfigBundle.KIND_REVISION,
                    exportedAt = System.currentTimeMillis().toString(), exportedBy = config.panelId,
                )
                val editor = config.editor()
                val live = ArrayList<Pair<String, String>>()
                for ((key, value) in accepted) {
                    when {
                        key == "panel_id" -> config.stagePanelId(editor, value)
                        SettingsRegistry.parseExposure(key) != null -> editor.putBoolean(key, SettingValue.parseBool(value) == true)
                        // EntityLearningManager owns enable/disable transition semantics and commits this
                        // preference after the ordinary bundle transaction succeeds.
                        key == "dashboard_entity_learning" -> Unit
                        key == "update_channel" -> SettingsRegistry.spec(key)?.let { config.stage(editor, it, value) }
                        key in HTTP_LIVE_KEYS -> live.add(key to value)
                        else -> SettingsRegistry.spec(key)?.let { spec ->
                            config.stage(editor, spec, value)
                        }
                    }
                }
                config.stageImportDependencies(editor, accepted)
                entityState?.let { config.stageDashboardEntityBackupState(editor, it) }
                // DB_COMPAT_MUTATION_ANCHOR: HTTP_SHARED_CONFIG_COMMIT
                preparedChannel?.revalidateForConfigCommit()?.let { refusal ->
                    // Staging is non-durable. Revalidate at the last boundary before commit so a
                    // refusal leaves this complete imported/restored configuration untouched.
                    earlyResult = ApplyAcceptedResult.CompatibilityRefused(refusal)
                    return@synchronizedTransaction
                }
                if (!config.commit(
                        editor,
                        afterCommit = {
                            if ("tame_vendor_packages" in accepted) requestTameReconcileAfterCommit()
                        },
                    )
                ) {
                    earlyResult = ApplyAcceptedResult.CommitFailed
                    return@synchronizedTransaction
                }
                channelCommitted = "update_channel" in accepted &&
                    accepted["update_channel"] == config.updateChannel
                committed = AcceptedCommit(
                    previous = previous,
                    live = live,
                    effects = RendererConfigEffects.between(previous.values, accepted),
                )
            }
            earlyResult?.let { return@transaction it }
            val phase = requireNotNull(committed)
            revisions.snapshot(phase.previous)
            var rendererFailure: Throwable? = null
            runCatching {
                // The base transaction deliberately excludes live keys. Publish every actually durable
                // generation to rollback ownership, then converge all live values before any external
                // Companion/profile work can fail.
                onDurableRevision(io.github.maxlyth.hapaneld.config.ConfigHash.of(revisionValues()))
                val previousHome = phase.previous.values["home_dashboard"].orEmpty()
                phase.live.firstOrNull { it.first == "home_dashboard" }?.let { (_, value) ->
                    val applied = applySetting("home_dashboard", value).legacyAcknowledged
                    onDurableRevision(io.github.maxlyth.hapaneld.config.ConfigHash.of(revisionValues()))
                    check(applied) { "home_dashboard live apply refused" }
                }
                for ((k, v) in phase.live) if (k != "home_dashboard") {
                    val applied = applySetting(k, v).legacyAcknowledged
                    onDurableRevision(io.github.maxlyth.hapaneld.config.ConfigHash.of(revisionValues()))
                    check(applied) { "$k live apply refused" }
                }
                val homeChanged = normalizeDashboardEntityPath(config.homeDashboard) !=
                    normalizeDashboardEntityPath(previousHome)
                val credentialsChanged = RendererConfigEffects.credentialsChanged(phase.previous.values, accepted)
                if (homeChanged || credentialsChanged) entityLearning.onTargetConfigurationChanged()
                accepted["dashboard_entity_learning"]?.let { raw ->
                    val enabled = SettingValue.parseBool(raw)
                        ?: error("validated automatic entity-filter value became invalid")
                    if (enabled != config.dashboardEntityLearningEnabled && !entityLearning.setEnabled(enabled)) {
                        error("entity-learning transition failed")
                    }
                    onDurableRevision(io.github.maxlyth.hapaneld.config.ConfigHash.of(revisionValues()))
                }
                // All accepted values are now durable. This same fence remains exact if either the
                // Companion/renderer callback or the later profile callback fails.
                afterCommitBeforeRenderer(
                    phase.effects,
                    io.github.maxlyth.hapaneld.config.ConfigHash.of(revisionValues()),
                )
                applyRendererEffects(phase.effects)
            }.onFailure { rendererFailure = it }
            snapInvalidate()
            onReconfigure(accepted.keys)
            rendererFailure?.let { throw it }
            afterApply()
            ApplyAcceptedResult.Applied
        }
        result
        } finally {
            val promotedChannelTicket = if (channelCommitted && preparedChannel != null) {
                checkNotNull(
                    InstallProgress.promoteConfigMutation(
                        requireNotNull(configMutationTicket),
                        "ha-paneld",
                        InstallPresentation("operation-working", mapOf("owner" to "paneld")),
                    ),
                ) { "committed self-update channel lost its configuration owner" }
            } else null
            configMutationTicket?.let(InstallProgress::finishConfigMutation)
            if (channelCommitted) {
                onSelfUpdateChannelCommitted(
                    preparedChannel,
                    promotedChannelTicket,
                    previousChannel,
                    config.updateChannel,
                )
                preparedChannel = null
            }
            preparedChannel?.close()
        }
    }

    private data class AcceptedCommit(
        val previous: ConfigBundle,
        val live: List<Pair<String, String>>,
        val effects: RendererConfigEffects,
    )

    /** Apply renderer changes only after their preferences commit. A dashboard switch dominates all
     *  reloads; otherwise a reload dominates a foreground relaunch, so one request schedules at most
     *  one renderer operation. */
    private fun applyRendererEffects(effects: RendererConfigEffects) {
        effects.darkMode?.let { dark ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                    if (dark) androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                    else androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO,
                )
            }
        }
        when {
            effects.dashboardChanged -> {
                val result = rendererPreparation.launchConfigured(
                    ensureHome = { pkg, ready ->
                        system.applyLauncherHomePolicy(config.launcherPackage, pkg, ready)
                    },
                    launchHome = { pkg -> system.launchHome(pkg) },
                )
                requireRendererResult(result)
                Log.i(TAG, "renderer switch completed (preparation=$result)")
            }
            effects.reloadBuiltin && effectiveDashboardIsBuiltin() -> {
                val result = rendererPreparation.prepareIfNeeded()
                requireRendererResult(result)
                system.reloadDashboard(
                    SystemController.BUILTIN_DASHBOARD,
                    reason = "applying your settings",
                )
            }
            effects.relaunchBuiltin && effectiveDashboardIsBuiltin() -> {
                val result = rendererPreparation.prepareIfNeeded()
                requireRendererResult(result)
                system.launchHome(SystemController.BUILTIN_DASHBOARD)
            }
        }
    }

    private fun requireRendererResult(result: RendererPreparationCoordinator.Result) {
        check(result != RendererPreparationCoordinator.Result.PERSIST_FAILED) {
            "built-in renderer preparation did not commit"
        }
        check(result != RendererPreparationCoordinator.Result.CLOSED) {
            "renderer lifecycle is stopping"
        }
    }

    // ---- Full panel backup / restore (device-state bundle) ------------------------------------------
    //
    // A backup bundle = ha-paneld config (all settable keys incl. secrets) + optionally the HA Companion's
    // login files (its HomeAssistantDB carries HA access/refresh tokens). Sealed when a passphrase is
    // supplied; an explicit, prominently warned plaintext export remains supported for local recovery.
    // Companion capture/restore needs su; the SELinux context matters (per-app MLS categories), so restore
    // reapplies the LIVE dir's owner uid + context rather than trusting restorecon.

    // Deliberately NOT the -wal/-shm sidecars: capture checkpoints the WAL into the main DB first, so the
    // single HomeAssistantDB file is complete. Writing back a STALE -wal/-shm makes SQLite discard it and
    // lose the login (the `servers` row lives in the WAL until a checkpoint) — validated the hard way.
    private data class CapturedCompanionFile(val relativePath: String, val file: File)
    private data class CapturedCompanion(
        val packageName: String,
        val files: List<CapturedCompanionFile>,
        val owner: java.io.Closeable,
    ) : java.io.Closeable {
        override fun close() = owner.close()
    }
    private data class BackupArchiveParts(
        val manifest: String,
        val sources: List<PanelBackup.ArchiveSource>,
        val ownedFiles: List<File>,
        val stateUnavailable: Boolean,
    )

    /** Build a file-backed v2 container. Companion bytes are raw ZIP entries, not base64 JSON. */
    private fun buildBackupArtifact(request: CompanionBackupRequest, passphrase: String): PanelBackup.Artifact {
        // Resolve before reserving: the staging bound has to describe the backup this panel is going to
        // build, not the largest one the request could have meant. An omitted request on a Companion-free
        // panel would otherwise reserve room for a capture that never happens, and could be refused for
        // storage the archive never needed.
        val includeCompanion = resolveCompanionInclusion(request) {
            CompanionInstaller.installedPkg(appContext) != null
        }
        if (cacheDir.usableSpace < backupStagingRequirement(includeCompanion, passphrase.isNotEmpty())) {
            throw CompanionBackupUnavailable("Insufficient storage to stage a backup safely")
        }
        val capture = if (includeCompanion) captureCompanion() else null
        return withBackupCaptureAndPlaintext(
            capture,
            createPlaintext = { File.createTempFile("panel-backup-", ".zip", cacheDir) },
        ) { ownedCapture, plain ->
            var sealed: File? = null
            var parts: BackupArchiveParts? = null
            withBackupArtifactCleanup(
                plain = plain,
                sealed = { sealed },
                ownedFiles = { parts?.ownedFiles.orEmpty() },
            ) {
                parts = backupArchiveParts(ownedCapture)
                plain.outputStream().use { output ->
                    PanelBackup.writeArchive(
                        output,
                        parts.manifest,
                        parts.sources,
                        MAX_BACKUP_MANIFEST_BYTES,
                    )
                }
                val plaintextLimit = if (passphrase.isEmpty()) MAX_RESTORE_BYTES
                    else PanelBackup.maxSealablePlaintextBytes(MAX_RESTORE_BYTES)
                if (plain.length() !in 1..plaintextLimit) throw ByteLimitExceeded(plaintextLimit)
                val stateUnavailable = parts.stateUnavailable
                if (passphrase.isEmpty()) {
                    return@withBackupCaptureAndPlaintext PanelBackup.Artifact(plain, "zip", stateUnavailable)
                }
                sealed = File.createTempFile("panel-backup-", ".hpb", cacheDir)
                plain.inputStream().use { input ->
                    sealed.outputStream().use { output -> PanelBackup.seal(input, output, passphrase) }
                }
                if (sealed.length() !in 1..MAX_RESTORE_BYTES) throw ByteLimitExceeded(MAX_RESTORE_BYTES)
                encryptedBackupArtifact(plain, sealed, stateUnavailable)
            }
        }
    }

    /** Keep the v2 manifest small: large profile and owner-scoped entity strings are bounded ZIP entries. */
    private fun backupArchiveParts(companion: CapturedCompanion?): BackupArchiveParts {
        return withStagedFiles { staged ->
            val owned = ArrayList<File>(3)
            fun textEntry(name: String, prefix: String, text: String, maxBytes: Long): PanelBackup.ArchiveSource {
                val file = staged.stage(File.createTempFile(prefix, ".payload", cacheDir)).also(owned::add)
                file.writer(Charsets.UTF_8).use { it.write(text) }
                if (file.length() > maxBytes) throw ByteLimitExceeded(maxBytes)
                return PanelBackup.ArchiveSource(name, file)
            }
            val entity = config.dashboardEntityBackupState()
            val filter = textEntry(ENTITY_FILTER_BACKUP_ENTRY, "entity-filter-backup-", entity.filterIds, MAX_ENTITY_BACKUP_TEXT_BYTES)
            val overrides = textEntry(ENTITY_OVERRIDES_BACKUP_ENTRY, "entity-overrides-backup-", entity.overrides, MAX_ENTITY_BACKUP_TEXT_BYTES)
            val profile = profileAdmin?.exportBackup()?.let {
                textEntry(PROFILE_BACKUP_ENTRY, "profile-backup-", it.toJson().toString(), MAX_PROFILE_BACKUP_ENTRY_BYTES)
            }
            // A database that will not read must not cost the owner the rest of the backup, which still
            // carries the validated config projection — but it must not be silent either. The failure is
            // logged and marked in the manifest below, so this archive can never be mistaken for one taken
            // from a panel that simply had nothing stored.
            // Not `use { }`: SQLiteOpenHelper only implements AutoCloseable from API 29, so `use`
            // compiles against the current compileSdk yet throws ClassCastException at runtime on
            // Android 8.1. readThenClose also isolates the close, so a store that exported successfully
            // but failed to close still contributes its rows.
            val stateCapture = runCatching {
                readThenClose(EntityCatalogStore(appContext), { it.close() }) { it.exportAppState() }
            }
            val stateFailure = stateCapture.exceptionOrNull()
            if (stateFailure != null) Log.w(TAG, "backup could not read app_state", stateFailure)
            val stateRows = stateCapture.getOrDefault(emptyList())
            val state = stateRows.takeIf { it.isNotEmpty() }?.let { rows ->
                textEntry(
                    STATE_BACKUP_ENTRY,
                    "app-state-backup-",
                    ConfigVault.encode(ConfigVault.Export(rows, emptyMap())),
                    MAX_STATE_BACKUP_BYTES,
                )
            }
            val sources = ArrayList<PanelBackup.ArchiveSource>(7)
            sources.add(filter)
            sources.add(overrides)
            profile?.let(sources::add)
            state?.let(sources::add)
            sources += companion?.files.orEmpty().mapIndexed { index, file ->
                PanelBackup.ArchiveSource("companion/$index", file.file)
            }
            val parts = BackupArchiveParts(
                manifest = backupManifest(
                    companion,
                    entity,
                    filter.file.length(),
                    overrides.file.length(),
                    profile?.file?.length(),
                    state?.file?.length(),
                    stateRows.size,
                    stateFailure != null,
                ),
                sources = sources,
                ownedFiles = owned,
                stateUnavailable = stateFailure != null,
            )
            staged.commit()
            parts
        }
    }

    /** Build bounded metadata only. A requested Companion capture is all-or-error. */
    private fun backupManifest(
        companion: CapturedCompanion?,
        entity: DashboardEntityBackupState,
        filterBytes: Long,
        overrideBytes: Long,
        profileBytes: Long?,
        stateBytes: Long?,
        stateRows: Int,
        stateCaptureFailed: Boolean,
    ): String {
        val live = configLiveValues()
        val cfg = projectConfigSnapshot(
            specs = SettingsRegistry.settable(),
            zigbeeRouterConfigured = config.zigbeeRouterConfigured,
            excludedKeys = ENTITY_STATE_CONFIG_KEYS,
            effectiveValue = { effectiveValue(it, live) },
        ).entries.joinToString(",") { (key, value) -> "${jsonStr(key)}:${jsonStr(value)}" }
        val exposures = SettingsRegistry.SPECS.filter { it.ha != null }
            .joinToString(",") { spec ->
                "${jsonStr(SettingsRegistry.exposureKey(spec))}:${jsonStr(config.haExposed(spec.key, spec.haExposedByDefault).toString())}"
            }
        val sb = StringBuilder("{\"kind\":\"ha-paneld-backup\",\"schema\":${SettingsRegistry.SCHEMA}")
        sb.append(",\"panel_id\":${jsonStr(config.panelId)},\"created\":${jsonStr(System.currentTimeMillis().toString())}")
        // Which device and which installed identity wrote this archive: the pseudonym Panel Assistant
        // already sees, never the Android id. It lets the other identity of this app, installed beside
        // this one, prove the archive is from the same device before it has adopted the panel id.
        sb.append(
            BackupIdentity.manifestFragment(
                panelAssistantDiscoveryId(config.androidId),
                appContext.packageName,
                mqttConnected = mqttState() == "connected",
            ),
        )
        sb.append(
            RawPreferenceBackup.manifestFragment { store ->
                appContext.getSharedPreferences(store, android.content.Context.MODE_PRIVATE).all
            },
        )
        sb.append(",\"config\":{").append(listOf(cfg, exposures).filter { it.isNotEmpty() }.joinToString(",")).append("}")
        sb.append(",\"entity_state\":").append(entityBackupArchiveJson(entity, filterBytes, overrideBytes))
        if (profileBytes != null) {
            sb.append(",\"profiles\":{\"entry\":").append(jsonStr(PROFILE_BACKUP_ENTRY))
                .append(",\"size\":").append(profileBytes).append('}')
        }
        StateArchiveSection.manifestFragment(
            STATE_BACKUP_ENTRY,
            stateBytes,
            stateRows,
            stateCaptureFailed,
        )?.let { sb.append(",\"state\":").append(it) }
        if (companion != null) {
            val files = companion.files.mapIndexed { index, file ->
                "{\"rel\":${jsonStr(file.relativePath)},\"entry\":${jsonStr("companion/$index")},\"size\":${file.file.length()}}"
            }.joinToString(",")
            sb.append(",\"companion\":{\"pkg\":${jsonStr(companion.packageName)},\"files\":[")
                .append(files).append("]}")
        }
        return sb.append("}").toString()
    }

    /** Capture descriptor-opened raw files, then checkpoint only the private-cache SQLite copy. */
    private fun captureCompanion(): CapturedCompanion {
        val pkg = CompanionInstaller.installedPkg(appContext)
            ?: throw CompanionBackupUnavailable("HA Companion is not installed")
        if (pkg !in CompanionInstaller.SUPPORTED_PACKAGES || !AndroidInput.isPackage(pkg)) {
            throw CompanionBackupUnavailable("HA Companion package is not supported")
        }
        if (!ensureCompanionHelper()) {
            throw CompanionBackupUnavailable("HA Companion backup needs the current ha-paneld helper")
        }
        val lease = when (
            val acquisition = CompanionDataLease.acquireArmed(
                pkg,
                companionDataOperationState,
                ::retainCompanionLeaseUntilHelperIdle,
            )
        ) {
            is CompanionDataLease.Acquisition.Acquired -> acquisition.lease
            CompanionDataLease.Acquisition.GateBusy ->
                throw CompanionBackupUnavailable("Another Companion data operation is running")
            CompanionDataLease.Acquisition.MarkerFailed ->
                throw CompanionBackupUnavailable("Companion operation safety marker could not be persisted")
        }
        var helperCapture: CompanionHelperProtocol.Capture? = null
        var needsCompanionRecovery = false
        try {
            val result = HelperClient.backupCompanion(pkg, cacheDir)
            val capture = when (result) {
                is CompanionHelperProtocol.BackupResult.Success -> result.capture.also {
                    needsCompanionRecovery = !it.relaunched
                }
                CompanionHelperProtocol.BackupResult.Busy -> {
                    lease.settle(possiblyInFlight = true) {}
                    throw CompanionBackupUnavailable("Companion helper is busy")
                }
                CompanionHelperProtocol.BackupResult.NotSubmitted ->
                    throw CompanionBackupUnavailable("Companion helper is unavailable")
                is CompanionHelperProtocol.BackupResult.Failed -> {
                    needsCompanionRecovery = result.relaunchFailed
                    throw CompanionBackupUnavailable(
                        if (result.relaunchFailed) "Companion capture failed and relaunch was not confirmed"
                        else "Companion capture failed",
                    )
                }
                CompanionHelperProtocol.BackupResult.Indeterminate -> {
                    lease.settle(possiblyInFlight = true) {
                        system.launchHome(pkg)
                        if (system.resolveDashboard(config.dashboardPackage) != pkg) {
                            system.launchHome(config.dashboardPackage)
                        }
                    }
                    throw CompanionBackupUnavailable("Companion capture result was indeterminate")
                }
            }
            helperCapture = capture
            val database = capture.files[CompanionRestore.DATABASE_FILE]
                ?: throw CompanionBackupUnavailable("Companion login database was not captured")
            if (!io.github.maxlyth.hapaneld.backup.CompanionDatabasePreparation.checkpointCapturedDatabase(
                    database,
                    capture.files[CompanionHelperProtocol.DATABASE_WAL_FILE],
                    capture.files[CompanionHelperProtocol.DATABASE_SHM_FILE],
                )
            ) throw CompanionBackupUnavailable("Companion login database could not be checkpointed safely")

            val captured = CompanionRestore.ALLOWED_FILES.mapNotNull { relative ->
                capture.files[relative]?.let { CapturedCompanionFile(relative, it) }
            }
            val total = captured.sumOf { it.file.length() }
            if (captured.none { it.relativePath == CompanionRestore.DATABASE_FILE } ||
                captured.any { it.file.length() !in 1..CompanionRestore.maxBytes(it.relativePath) } ||
                total > MAX_COMPANION_BACKUP_BYTES
            ) throw CompanionBackupUnavailable("Companion capture exceeded portable backup bounds")
            if (!capture.relaunched) {
                throw CompanionBackupUnavailable("Companion was captured but helper relaunch failed")
            }
            helperCapture = null
            return CapturedCompanion(pkg, captured, capture)
        } finally {
            lease.settle(possiblyInFlight = false) {
                // The helper always launches Companion to clear Android's stopped state. Restore the
                // configured dashboard after releasing suppression when Companion is not it.
                if (needsCompanionRecovery) system.launchHome(pkg)
                if (system.resolveDashboard(config.dashboardPackage) != pkg) {
                    system.launchHome(config.dashboardPackage)
                }
            }
            helperCapture?.close()
        }
    }

    /** Restore endpoint: decrypt + validate; ?dry_run=1 reports contents without writing. A real restore is
     *  DESTRUCTIVE (config rewrite + Companion force-stop/rewrite), run off-thread with InstallProgress. */
    private suspend fun handleRestore(call: ApplicationCall) {
        val pw = call.request.headers["X-Backup-Passphrase"].orEmpty()
        val dryRun = call.request.queryParameters["dry_run"] == "1"
        val migrationRestore = when (
            migrationRestoreAdmission(
                requested = call.request.queryParameters["mode"] == "migration",
                loopbackPeer = isLoopbackPeer(call.request.origin.remoteAddress),
                restoreOpen = identityMigration.restoreOpen(),
            )
        ) {
            MigrationRestoreAdmission.NOT_REQUESTED -> false
            MigrationRestoreAdmission.ADMITTED -> true
            MigrationRestoreAdmission.REFUSED -> return call.respondText(
                """{"ok":false,"error":"migration-restore-refused"}""",
                ContentType.Application.Json,
                HttpStatusCode.Forbidden,
            )
        }
        // Claim the successor's wait now, not when the restore ends. A restore that outlives the five
        // minute wait must still answer the attempt that started it: by the time it finishes, the
        // successor may already have opened another, and answering that one would report this restore's
        // outcome for a restore that has not run.
        val restoreAttempt =
            if (claimsRestoreAttempt(migrationRestore, dryRun)) identityMigration.claimRestoreAttempt()
            else RestoreAttempt.NONE
        // Claim the shared destructive-operation lane before buffering, decrypting, or parsing a bundle.
        // Otherwise several losing requests can each consume 64 MiB and expensive KDF/JSON work before
        // discovering that another restore/install already owns admission.
        val progress = InstallProgress.start(
            if (dryRun) "Restore preview" else "Restore",
            InstallPresentation(
                "operation-working",
                mapOf("owner" to if (dryRun) "restore-preview" else "restore"),
            ),
        )
            ?: return call.respondText(
                """{"status":"busy"}""",
                ContentType.Application.Json,
                HttpStatusCode.Conflict,
            )
        var transferredToJob = false
        var requestAccepted = false
        val restoreFiles = ArrayList<File>(4)
        var retainedCompanionPlan: CompanionRestore.Plan? = null
        try {
            val receivedFile = File.createTempFile("panel-restore-", ".upload", cacheDir).also(restoreFiles::add)
            val stagingLimit = restoreBodyStagingLimit(cacheDir.usableSpace)
            val declaredBytes = call.request.headers["Content-Length"]?.toLongOrNull()
            if (stagingLimit <= 0L || (declaredBytes != null && declaredBytes > stagingLimit)) {
                return call.respondText(
                    """{"ok":false,"error":"insufficient-storage"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.InsufficientStorage,
                )
            }
            try {
                withContext(Dispatchers.IO) {
                    call.receiveStream().use { input ->
                        receivedFile.outputStream().use { output ->
                            DeadlineBoundedBody.copy(
                                input,
                                output,
                                stagingLimit,
                                RESTORE_BODY_RECEIPT_DEADLINE_MS,
                            )
                        }
                    }
                }
            } catch (_: ByteLimitExceeded) {
                val storageBound = stagingLimit < MAX_RESTORE_BYTES
                return call.respondText(
                    if (storageBound) """{"ok":false,"error":"insufficient-storage"}"""
                    else """{"ok":false,"error":"bundle-too-large"}""",
                    ContentType.Application.Json,
                    if (storageBound) HttpStatusCode.InsufficientStorage else HttpStatusCode.PayloadTooLarge,
                )
            } catch (_: BodyReceiptTimeout) {
                return call.respondText(
                    """{"ok":false,"error":"bundle-timeout"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.RequestTimeout,
                )
            }
            if (receivedFile.length() <= 0L) return call.respondText(
                withInstallPresentation(
                    """{"ok":false,"error":"not a ha-paneld backup"}""",
                    InstallPresentation("restore-not-panel-backup"),
                ),
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
            // GCM decryption writes to a private temporary file and the tag must authenticate fully before
            // any JSON is parsed or a restore plan can be applied.
            val plainFile = if (PanelBackup.isSealed(receivedFile)) {
                if (pw.isEmpty()) return call.respondText(
                    withInstallPresentation(
                        """{"ok":false,"error":"this bundle is encrypted — enter its passphrase"}""",
                        InstallPresentation("restore-passphrase-required"),
                    ),
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
                val decrypted = File.createTempFile("panel-restore-", ".plain", cacheDir).also(restoreFiles::add)
                val opened = try {
                    withContext(Dispatchers.IO) {
                        receivedFile.inputStream().use { input ->
                            decrypted.outputStream().use { output ->
                                PanelBackup.open(input, output, pw, MAX_RESTORE_BYTES)
                            }
                        }
                    }
                } catch (_: ByteLimitExceeded) {
                    return call.respondText(
                        """{"ok":false,"error":"bundle-too-large"}""",
                        ContentType.Application.Json,
                        HttpStatusCode.PayloadTooLarge,
                    )
                }
                if (!opened) return call.respondText(
                    withInstallPresentation(
                        """{"ok":false,"error":"wrong passphrase or corrupt bundle"}""",
                        InstallPresentation("restore-passphrase-or-bundle-invalid"),
                    ),
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
                decrypted
            } else receivedFile
            val archiveManifest = PanelBackup.readManifest(plainFile, MAX_BACKUP_MANIFEST_BYTES)
            // Legacy v1 embeds Companion files as base64 inside one JSON object. JSONObject necessarily
            // holds both the source text and parsed strings, so keep that compatibility path under a
            // much smaller semantic ceiling. v2 archives carry large payloads as streamed ZIP entries.
            if (archiveManifest == null && plainFile.length() > MAX_LEGACY_BACKUP_JSON_BYTES) {
                return call.respondText(
                    withInstallPresentation(
                        """{"ok":false,"error":"legacy backup is too large; create a new backup before restoring"}""",
                        InstallPresentation("restore-legacy-too-large"),
                    ),
                    ContentType.Application.Json,
                    HttpStatusCode.PayloadTooLarge,
                )
            }
            val obj = runCatching {
                val json = archiveManifest ?: String(
                    plainFile.inputStream().use { BoundedStreams.readBytes(it, MAX_LEGACY_BACKUP_JSON_BYTES) },
                    Charsets.UTF_8,
                )
                org.json.JSONObject(json)
            }.getOrNull()
            if (obj == null || obj.optString("kind") != "ha-paneld-backup") {
                return call.respondText(
                    withInstallPresentation(
                        """{"ok":false,"error":"not a ha-paneld backup"}""",
                        InstallPresentation("restore-not-panel-backup"),
                    ),
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
            }
            val cfgObj = obj.optJSONObject("config")
                ?: return call.respondText(
                    withInstallPresentation(
                        """{"ok":false,"error":"backup contains no config object"}""",
                        InstallPresentation("restore-config-missing"),
                    ),
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
            val backupSchema = obj.optInt("schema", -1)
            if (backupSchema < 1) return call.respondText(
                withInstallPresentation(
                    """{"ok":false,"error":"backup contains no valid schema"}""",
                    InstallPresentation("restore-schema-missing"),
                ),
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
            val configPlan = planRestoreConfig(cfgObj, backupSchema).let { plan ->
                if (migrationRestore) plan.copy(values = migrationRestoreConfig(plan.values)) else plan
            }
            if (configPlan.errors.isNotEmpty()) {
                return call.respondText(
                    withInstallPresentation(
                        """{"ok":false,"error":"invalid backup config","errors":${jarr(configPlan.errors)}}""",
                        InstallPresentation("restore-config-invalid"),
                    ),
                    ContentType.Application.Json,
                    HttpStatusCode.UnprocessableEntity,
                )
            }
            val entityObj = obj.optJSONObject("entity_state")
            if (obj.has("entity_state") && entityObj == null) return call.respondText(
                withInstallPresentation(
                    """{"ok":false,"error":"invalid entity_state object"}""",
                    InstallPresentation("restore-entity-object-invalid"),
                ),
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
            val profilesObj = obj.optJSONObject("profiles")
            if (obj.has("profiles") && profilesObj == null) return call.respondText(
                withInstallPresentation(
                    """{"ok":false,"error":"invalid profiles object"}""",
                    InstallPresentation("restore-profiles-object-invalid"),
                ),
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
            val comp = obj.optJSONObject("companion")
            if (obj.has("companion") && comp == null) {
                return call.respondText(
                    withInstallPresentation(
                        """{"ok":false,"error":"Invalid Companion restore section"}""",
                        InstallPresentation("restore-companion-section-invalid"),
                    ),
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
            }
            val stateObj = obj.optJSONObject("state")
            if (obj.has("state") && stateObj == null) return call.respondText(
                withInstallPresentation(
                    """{"ok":false,"error":"invalid state object"}""",
                    InstallPresentation("restore-state-object-invalid"),
                ),
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
            // Resolve the section before anything reads it. A `state` object that declares neither a
            // payload nor a capture failure cannot be resolved to "nothing to restore" — that silence is
            // the defect this marker exists to remove.
            val stateDisposition = StateArchiveSection.restoreStateDisposition(stateObj)
                ?: return call.respondText(
                    withInstallPresentation(
                        """{"ok":false,"error":"invalid state object"}""",
                        InstallPresentation("restore-state-object-invalid"),
                    ),
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
            val stateUnavailable = stateDisposition == StateArchiveSection.Disposition.INCOMPLETE
            val archiveEntries = if (archiveManifest != null) {
                runCatching { declaredArchiveEntries(entityObj, profilesObj, comp, stateObj) }.getOrNull()
                    ?: return call.respondText(
                        withInstallPresentation(
                            """{"ok":false,"error":"invalid backup archive metadata"}""",
                            InstallPresentation("restore-archive-metadata-invalid"),
                        ),
                        ContentType.Application.Json,
                        HttpStatusCode.BadRequest,
                    )
            } else emptySet()
            if (archiveManifest != null && !PanelBackup.extractArchive(plainFile, emptyList(), archiveEntries)) {
                return call.respondText(
                    withInstallPresentation(
                        """{"ok":false,"error":"backup archive contains missing or unexpected files"}""",
                        InstallPresentation("restore-archive-entries-invalid"),
                    ),
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
            }
            val entityState = entityObj?.let {
                runCatching {
                    if (archiveManifest != null && it.has("filter_ids_entry")) {
                        planEntityArchive(it, plainFile, archiveEntries)
                    } else {
                        planEntityBackup(it)
                    }
                }.getOrNull()
            }
            if (entityObj != null && entityState == null) return call.respondText(
                withInstallPresentation(
                    """{"ok":false,"error":"invalid owner-scoped entity state"}""",
                    InstallPresentation("restore-entity-state-invalid"),
                ),
                ContentType.Application.Json,
                HttpStatusCode.UnprocessableEntity,
            )
            if (entityState == null && (
                    configPlan.values["dashboard_entity_overrides"].orEmpty().isNotBlank() ||
                        configPlan.values["dashboard_entity_learning_applied"] == "true"
                    )
            ) return call.respondText(
                withInstallPresentation(
                    """{"ok":false,"error":"entity state is missing its owner namespace"}""",
                    InstallPresentation("restore-entity-owner-missing"),
                ),
                ContentType.Application.Json,
                HttpStatusCode.UnprocessableEntity,
            )
            // A migration-mode restore is this app's other identity handing its state over on one device.
            // The successor has not adopted the panel id yet, so the device is proven by the discovery
            // pseudonym instead, and an archive from anywhere else is refused outright rather than
            // restored with its device-local rows withheld.
            val sameDeviceByDiscoveryId =
                BackupIdentity.sameDevice(obj, panelAssistantDiscoveryId(config.androidId))
            if (migrationRestore && !sameDeviceByDiscoveryId) return call.respondText(
                """{"ok":false,"error":"migration-backup-not-from-this-device"}""",
                ContentType.Application.Json,
                HttpStatusCode.UnprocessableEntity,
            )
            val rawPreferences = if (migrationRestore) {
                RawPreferenceBackup.restorable(obj) ?: return call.respondText(
                    """{"ok":false,"error":"invalid raw_preferences object"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
            } else emptyMap()
            // Durable state outside the settings registry. `panel_id` is read before any config write, so
            // it still identifies the physical target: device-local rows return only to their own panel.
            // A cleared panel that no longer carries its old id is treated as a different one, which
            // withholds hardware-specific rows rather than guessing.
            val restorableState = if (
                archiveManifest != null && stateDisposition == StateArchiveSection.Disposition.RESTORABLE
            ) {
                val samePanel = StateBackupPolicy.sameDevice(
                    migrationRestore = migrationRestore,
                    panelIdMatches = obj.optString("panel_id").let { it.isNotEmpty() && it == config.panelId },
                    discoveryIdMatches = sameDeviceByDiscoveryId,
                )
                runCatching {
                    val ref = archiveTextRef(
                        stateObj,
                        "entry",
                        "size",
                        STATE_BACKUP_ENTRY,
                        MAX_STATE_BACKUP_BYTES,
                        allowEmpty = false,
                    )
                    val decoded = ConfigVault.decode(
                        readArchiveText(plainFile, ref, archiveEntries, "app-state-restore-"),
                    ) ?: throw IllegalArgumentException("corrupt app_state payload")
                    StateBackupPolicy.restorableRows(decoded.rows, samePanel)
                }.getOrNull() ?: return call.respondText(
                    withInstallPresentation(
                        """{"ok":false,"error":"invalid app_state payload"}""",
                        InstallPresentation("restore-app-state-invalid"),
                    ),
                    ContentType.Application.Json,
                    HttpStatusCode.UnprocessableEntity,
                )
            } else emptyList()
            val profilePayload = profilesObj?.let {
                if (archiveManifest != null && it.has("entry")) {
                    readProfileArchive(it, plainFile, archiveEntries)
                } else {
                    ProfileBackup.fromJson(it)
                }
            }
            if (profilesObj != null && profilePayload == null) return call.respondText(
                withInstallPresentation(
                    """{"ok":false,"error":"invalid profile archive entry"}""",
                    InstallPresentation("restore-profile-archive-invalid"),
                ),
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
            if (profilePayload != null && profilePayload.payload == null) return call.respondText(
                withInstallPresentation(
                    """{"ok":false,"error":"invalid profile catalog","errors":${jarr(profilePayload.issues.map(::profileIssueText))}}""",
                    InstallPresentation("restore-profile-catalog-invalid"),
                ),
                ContentType.Application.Json,
                HttpStatusCode.UnprocessableEntity,
            )
            if (profilePayload?.payload != null && profileAdmin == null) return call.respondText(
                withInstallPresentation(
                    """{"ok":false,"error":"profile catalog restore is unavailable"}""",
                    InstallPresentation("restore-profile-restore-unavailable"),
                ),
                ContentType.Application.Json,
                HttpStatusCode.ServiceUnavailable,
            )
            val profilePlan = profilePayload?.payload?.let { requireNotNull(profileAdmin).planBackupRestore(it) }
            if (profilePlan != null && !profilePlan.valid) return call.respondText(
                withInstallPresentation(
                    """{"ok":false,"error":"profile catalog is not restorable","errors":${jarr(profilePlan.issues.map(::profileIssueText))}}""",
                    InstallPresentation("restore-profile-catalog-not-restorable"),
                ),
                ContentType.Application.Json,
                HttpStatusCode.UnprocessableEntity,
            )
            val plannedCompanion = when {
                comp != null && archiveManifest != null -> planCompanionArchive(comp, plainFile, archiveEntries)
                comp != null -> planCompanionRestore(comp)
                else -> null
            }
            if (plannedCompanion is CompanionRestore.PlanResult.Invalid) {
                return call.respondText(
                    withInstallPresentation(
                        """{"ok":false,"error":${jsonStr(plannedCompanion.reason)}}""",
                        plannedCompanion.presentation,
                    ),
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
            }
            val companionPlan = (plannedCompanion as? CompanionRestore.PlanResult.Valid)?.plan
            retainedCompanionPlan = companionPlan
            val compFiles = companionPlan?.files?.size ?: 0
            if (dryRun) {
                requestAccepted = true
                return call.respondText(
                    """{"ok":true,"dry_run":true,"panel_id":${jsonStr(obj.optString("panel_id"))},""" +
                        """"config_keys":${configPlan.values.size},"config_warnings":${jarr(configPlan.warnings)},"profile_revisions":${profilePlan?.toImport?.size ?: 0},"profile_restart_required":${profilePlan?.restartRequired ?: false},"companion_pkg":${jsonStr(companionPlan?.packageName ?: "")},"companion_files":$compFiles,"state_unavailable":$stateUnavailable}""",
                    ContentType.Application.Json,
                )
            }
            if (rejectHardenedNetworkAdb(call, configPlan.values["network_adb"])) return
            val restoreDigest = withContext(Dispatchers.IO) {
                val digest = MessageDigest.getInstance("SHA-256")
                receivedFile.inputStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            }
            if (!authorizeSensitive(
                    call,
                    SensitiveOperation.BACKUP_RESTORE,
                    exactHttpApprovalPayload(call, restoreDigest),
                    "Restore this panel backup${if (companionPlan != null) " including the Companion login" else ""}",
                )
            ) return
            if (companionPlan != null && !withContext(Dispatchers.IO) { ensureCompanionHelper() }) {
                return call.respondText(
                    withInstallPresentation(
                        """{"ok":false,"error":"Companion restore needs the current ha-paneld helper"}""",
                        InstallPresentation("restore-companion-helper-required"),
                    ),
                    ContentType.Application.Json,
                    HttpStatusCode.ServiceUnavailable,
                )
            }
            val job = scope.launch {
                val before = currentValues()
                val beforeEntityState = config.dashboardEntityBackupState()
                val beforeRevisionHash = io.github.maxlyth.hapaneld.config.ConfigHash.of(
                    revisionValues(before, beforeEntityState),
                )
                var configCommitted = false
                var configItems = 0
                var restoredStateRows = 0
                var companionResult: CompanionApplyResult? = null
                var profileResult: ProfileBackupRestoreResult? = null
                var appliedRevisionHash: String? = null
                val operation = runCatching {
                    configItems = applyRestoreConfig(
                        configPlan.values,
                        entityState,
                        beforeRevisionHash,
                        existingOperationTicket = progress,
                        onDurableRevision = { appliedHash ->
                            configCommitted = true
                            appliedRevisionHash = appliedHash
                        },
                        afterCommitBeforeRenderer = { effects, acceptedCount, appliedHash ->
                            configItems = acceptedCount
                            appliedRevisionHash = appliedHash
                            companionResult = companionPlan?.let(::restoreCompanion)
                            if (companionResult?.ok == false) throw CompanionApplyFailed()
                            reconcileAfterCompanionRestore(effects)
                        },
                        afterApply = afterApply@{
                            // Post-commit, and before the profile early-return below so a backup without
                            // profiles still restores its state. Never fatal: the configuration the owner
                            // came for is already durable, so a failure here must not roll it back.
                            val rawPreferencesApplied = rawPreferences.all { (store, values) ->
                                runCatching {
                                    val editor = appContext
                                        .getSharedPreferences(store, android.content.Context.MODE_PRIVATE).edit()
                                    values.forEach { (key, value) -> editor.putString(key, value) }
                                    editor.commit()
                                }.getOrDefault(false)
                            }
                            if (restorableState.isNotEmpty()) {
                                restoredStateRows = runCatching {
                                    AppState.applyRestoredRows(appContext, restorableState)
                                }.getOrDefault(0)
                                if (restoredStateRows > 0) {
                                    // Never fatal, exactly like the write above: the restore is
                                    // already durable, and a live re-read failing must not undo it.
                                    runCatching { onDurableStateRestored() }
                                }
                            }
                            // An ordinary restore forgives a state write that failed, because its archive
                            // survives it. A migration's receipt is about to become the only copy of a
                            // package that is then removed, so there every carried value must have landed.
                            if (migrationRestore &&
                                !migrationRestoreComplete(rawPreferencesApplied, restorableState.size, restoredStateRows)
                            ) {
                                throw IllegalStateException("migration restore did not apply every carried value")
                            }
                            val payload = profilePayload?.payload ?: return@afterApply
                            profileResult = requireNotNull(profileAdmin).restoreBackup(
                                payload,
                                requireNotNull(profilePlan).expectedCatalogRevision,
                            )
                            if (profileResult?.outcome != ProfileBackupRestoreOutcome.SUCCEEDED) {
                                throw ProfileApplyFailed()
                            }
                            if (profileResult?.restartRequired == true) {
                                rejectFailedProfileRestart(
                                    restartAllowed = true,
                                    requestRestart = onProfileRestart,
                                    abortPendingRestart = onProfileRestartAbort,
                                )?.let { throw ProfileApplyFailed(it) }
                            }
                        },
                    )
                    RestoreOperationResult(
                        // An archive that marked itself incomplete restored everything it held, and still
                        // must not report the plain success of one that held the panel's state.
                        message = when {
                            restoredStateRows > 0 ->
                                "Restore completed, including $restoredStateRows panel state values"
                            stateUnavailable -> "Restore completed; this backup carried no panel state"
                            else -> "Restore completed"
                        },
                        structured = InstallProgress.OperationResult(
                            status = InstallProgress.Outcome.SUCCEEDED,
                            config = succeededComponent(configItems),
                            profiles = profileComponent(profileResult),
                            companion = companionResult?.component
                                ?: skippedComponent("not present"),
                        ),
                        presentation = when {
                            restoredStateRows > 0 -> InstallPresentation(
                                "restore-completed-with-state",
                                mapOf("count" to restoredStateRows.toString()),
                            )
                            stateUnavailable -> InstallPresentation("restore-completed-state-unavailable")
                            else -> InstallPresentation("restore-completed")
                        },
                    )
                }.getOrElse { error ->
                    Log.w(TAG, "restore failed", error)
                    val profileRestartRejection = (error as? ProfileApplyFailed)?.restartRejection
                    val rollback = if (configCommitted) {
                        val expected = appliedRevisionHash
                        val restored = expected != null && runCatching {
                            applyAccepted(before, expectedRevision = expected,
                                entityState = beforeEntityState,
                                existingOperationTicket = progress,
                            ) ==
                                ApplyAcceptedResult.Applied
                        }
                            .getOrDefault(false)
                        if (restored) InstallProgress.ComponentResult(InstallProgress.Outcome.ROLLED_BACK)
                        else InstallProgress.ComponentResult(InstallProgress.Outcome.ROLLBACK_FAILED)
                    } else null
                    val partial = companionResult?.ok == true ||
                        companionResult?.component?.status == InstallProgress.Outcome.PARTIAL ||
                        profileResult?.outcome == ProfileBackupRestoreOutcome.SUCCEEDED ||
                        profileResult?.outcome == ProfileBackupRestoreOutcome.PARTIAL ||
                        rollback?.status == InstallProgress.Outcome.ROLLBACK_FAILED
                    RestoreOperationResult(
                        message = if (partial) "Restore partially completed" else "Restore failed",
                        structured = InstallProgress.OperationResult(
                            status = if (partial) InstallProgress.Outcome.PARTIAL else InstallProgress.Outcome.FAILED,
                            config = when {
                                rollback?.status == InstallProgress.Outcome.ROLLED_BACK ->
                                    InstallProgress.ComponentResult(InstallProgress.Outcome.ROLLED_BACK, configItems)
                                rollback?.status == InstallProgress.Outcome.ROLLBACK_FAILED ->
                                    InstallProgress.ComponentResult(InstallProgress.Outcome.ROLLBACK_FAILED, configItems)
                                configCommitted -> InstallProgress.ComponentResult(InstallProgress.Outcome.PARTIAL, configItems)
                                else -> InstallProgress.ComponentResult(InstallProgress.Outcome.FAILED, 0)
                            },
                            profiles = profileRestartRejection?.let {
                                profileRestartFailureComponent(it, profileResult?.imported?.size ?: 0)
                            } ?: profileComponent(profileResult, profilePlan != null),
                            companion = companionResult?.component
                                ?: if (companionPlan == null) skippedComponent("not present")
                                else InstallProgress.ComponentResult(InstallProgress.Outcome.FAILED, 0),
                            rollback = rollback,
                        ),
                        presentation = InstallPresentation(if (partial) "restore-partial" else "restore-failed"),
                    )
                }
                Log.i(TAG, "restore: ${operation.message}")
                // Only a whole success advances the migration; a partial or failed restore leaves the
                // step open, and the successor restores the same receipt again. It is told either way,
                // so a failure is retried at once rather than after waiting out a timeout.
                restoreAttempt.finished(operation.structured.status == InstallProgress.Outcome.SUCCEEDED)
                InstallProgress.finish(
                    progress,
                    operation.message,
                    operation.structured,
                    operation.presentation,
                )
            }
            job.invokeOnCompletion {
                // A job that was cancelled, or that failed somewhere its own result never reaches,
                // still ends the attempt. Reporting is first-wins, so a job that already reported its
                // real outcome keeps it and only an unanswered attempt is failed here — which the
                // successor retries at once rather than waiting out the five minute timeout.
                restoreAttempt.finished(false)
                retainedCompanionPlan?.close()
                restoreFiles.forEach(File::delete)
            }
            transferredToJob = true
            requestAccepted = true
            InstallProgress.finishOnFailure(progress, job)
            call.respondText("""{"status":"started"}""", ContentType.Application.Json)
        } finally {
            if (!transferredToJob) {
                // Rejected before any restore ran — a bad bundle, a refused approval, a missing helper.
                // The attempt is answered here for the same reason the job answers its own: the
                // successor should retry now, not in five minutes.
                restoreAttempt.finished(false)
                retainedCompanionPlan?.close()
                restoreFiles.forEach(File::delete)
                val result = if (requestAccepted) {
                    InstallProgress.OperationResult(InstallProgress.Outcome.SUCCEEDED)
                } else {
                    InstallProgress.OperationResult(InstallProgress.Outcome.FAILED)
                }
                InstallProgress.finish(
                    progress,
                    if (requestAccepted) "Restore preview complete" else "Restore request rejected",
                    result,
                    InstallPresentation(
                        if (requestAccepted) "restore-preview-complete" else "restore-request-rejected",
                    ),
                )
            }
        }
    }

    private data class RestoreOperationResult(
        val message: String,
        val structured: InstallProgress.OperationResult,
        val presentation: InstallPresentation,
    )

    private data class CompanionApplyResult(
        val ok: Boolean,
        val component: InstallProgress.ComponentResult,
    )

    private class CompanionApplyFailed : IllegalStateException("Companion restore failed")
    private class ProfileApplyFailed(
        val restartRejection: ProfileRestartRejection? = null,
    ) : IllegalStateException(restartRejection?.message ?: "Profile catalog restore failed")

    private fun succeededComponent(items: Int) = InstallProgress.ComponentResult(
        InstallProgress.Outcome.SUCCEEDED,
        items,
    )

    private fun skippedComponent(detail: String) = InstallProgress.ComponentResult(
        InstallProgress.Outcome.SKIPPED,
        detail = detail,
        presentation = InstallPresentation("component-not-present"),
    )

    private fun profileComponent(
        result: ProfileBackupRestoreResult?,
        present: Boolean = result != null,
    ): InstallProgress.ComponentResult = when {
        result == null && !present -> skippedComponent("not present")
        result == null -> InstallProgress.ComponentResult(InstallProgress.Outcome.FAILED, 0)
        else -> InstallProgress.ComponentResult(
            status = when (result.outcome) {
                ProfileBackupRestoreOutcome.SUCCEEDED -> InstallProgress.Outcome.SUCCEEDED
                ProfileBackupRestoreOutcome.PARTIAL -> InstallProgress.Outcome.PARTIAL
                ProfileBackupRestoreOutcome.REJECTED -> InstallProgress.Outcome.FAILED
            },
            items = result.imported.size,
            detail = result.message,
            presentation = result.presentation?.let {
                InstallPresentation.create(it.code, it.params)
            },
        )
    }

    private fun profileRestartFailureComponent(
        rejection: ProfileRestartRejection,
        imported: Int,
    ) = InstallProgress.ComponentResult(
        status = if (rejection.abortPersisted) {
            InstallProgress.Outcome.ROLLED_BACK
        } else {
            InstallProgress.Outcome.ROLLBACK_FAILED
        },
        items = imported,
        detail = rejection.message,
        presentation = InstallPresentation.create(
            rejection.presentation.code,
            rejection.presentation.params,
        ),
    )

    private fun profileIssueText(issue: io.github.maxlyth.hapaneld.device.profile.ProfileIssue): String =
        "${issue.path}: ${issue.message}"

    private data class ArchiveTextRef(
        val entry: String,
        val size: Long,
        val maxBytes: Long,
        val allowEmpty: Boolean,
    )

    private fun archiveTextRef(
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

    private fun declaredArchiveEntries(
        entity: org.json.JSONObject?,
        profiles: org.json.JSONObject?,
        companion: org.json.JSONObject?,
        state: org.json.JSONObject?,
    ): Set<String> {
        val entries = ArrayList<String>(7)
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

    private fun readArchiveText(
        archive: File,
        ref: ArchiveTextRef,
        allowedEntries: Set<String>,
        prefix: String,
    ): String {
        return withStagedFiles { staged ->
            val target = staged.stage(File.createTempFile(prefix, ".payload", cacheDir))
            require(
                PanelBackup.extractArchive(
                    archive,
                    listOf(PanelBackup.ArchiveTarget(ref.entry, target, ref.maxBytes, ref.allowEmpty)),
                    allowedEntries,
                ),
            )
            require(target.length() == ref.size)
            val bytes = target.inputStream().use { BoundedStreams.readBytes(it, ref.maxBytes) }
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()
        }
    }

    private fun readProfileArchive(
        metadata: org.json.JSONObject,
        archive: File,
        allowedEntries: Set<String>,
    ): io.github.maxlyth.hapaneld.device.profile.ProfileBackupDecodeResult? = runCatching {
        val ref = archiveTextRef(
            metadata,
            "entry",
            "size",
            PROFILE_BACKUP_ENTRY,
            MAX_PROFILE_BACKUP_ENTRY_BYTES,
            allowEmpty = false,
        )
        ProfileBackup.fromJson(org.json.JSONObject(readArchiveText(archive, ref, allowedEntries, "profile-restore-")))
    }.getOrNull()

    private fun planEntityArchive(
        metadata: org.json.JSONObject,
        archive: File,
        allowedEntries: Set<String>,
    ): DashboardEntityBackupState {
        val filterRef = archiveTextRef(
            metadata,
            "filter_ids_entry",
            "filter_ids_size",
            ENTITY_FILTER_BACKUP_ENTRY,
            MAX_ENTITY_BACKUP_TEXT_BYTES,
            allowEmpty = true,
        )
        val overridesRef = archiveTextRef(
            metadata,
            "overrides_entry",
            "overrides_size",
            ENTITY_OVERRIDES_BACKUP_ENTRY,
            MAX_ENTITY_BACKUP_TEXT_BYTES,
            allowEmpty = true,
        )
        val filterIds = readArchiveText(archive, filterRef, allowedEntries, "entity-filter-restore-")
        val overrides = readArchiveText(archive, overridesRef, allowedEntries, "entity-overrides-restore-")
        return planEntityBackup(
            org.json.JSONObject(metadata.toString())
                .put("filter_ids", filterIds)
                .put("overrides", overrides),
        )
    }

    /** Convert untrusted JSON to a completely validated and decoded plan before any config commit or app stop. */
    private fun planCompanionRestore(comp: org.json.JSONObject): CompanionRestore.PlanResult {
        val files = comp.optJSONArray("files")
            ?: return invalidCompanionPayload("Companion restore contains no files")
        val encoded = ArrayList<CompanionRestore.EncodedFile>(files.length())
        for (i in 0 until files.length()) {
            val file = files.optJSONObject(i)
                ?: return invalidCompanionPayload("Invalid Companion file entry at index $i")
            encoded += CompanionRestore.EncodedFile(file.optString("rel"), file.optString("b64"))
        }
        return CompanionRestore.plan(
            packageName = comp.optString("pkg"),
            files = encoded,
            installedPackages = CompanionInstaller.installedPackages(appContext),
            stagingDir = cacheDir,
        )
    }

    /** Extract a v2 archive's raw Companion entries under per-file and aggregate decoded limits. */
    private fun planCompanionArchive(
        comp: org.json.JSONObject,
        archive: File,
        allowedEntries: Set<String>,
    ): CompanionRestore.PlanResult {
        val files = comp.optJSONArray("files")
            ?: return invalidCompanionPayload("Companion restore contains no files")
        if (files.length() !in 1..CompanionRestore.ALLOWED_FILES.size) {
            return invalidCompanionPayload("Companion restore contains an invalid file count")
        }
        data class Pending(val relativePath: String, val entry: String, val size: Long, val target: File)
        return withStagedFiles { staged ->
            val pending = ArrayList<Pending>(files.length())
            for (index in 0 until files.length()) {
                val file = files.optJSONObject(index)
                    ?: return@withStagedFiles invalidCompanionPayload("Invalid Companion file entry at index $index")
                val relativePath = file.optString("rel")
                val entry = file.optString("entry")
                val declaredSize = file.optLong("size", -1L)
                if (relativePath !in CompanionRestore.ALLOWED_FILES ||
                    declaredSize !in 1..CompanionRestore.maxBytes(relativePath)
                ) return@withStagedFiles invalidCompanionPayload("Invalid Companion file metadata at index $index")
                pending += Pending(
                    relativePath,
                    entry,
                    declaredSize,
                    staged.stage(File.createTempFile("companion-restore-", ".payload", cacheDir)),
                )
            }
            if (pending.map { it.relativePath }.toSet().size != pending.size ||
                pending.map { it.entry }.toSet().size != pending.size ||
                pending.sumOf { it.size } > CompanionRestore.MAX_AGGREGATE_BYTES
            ) return@withStagedFiles invalidCompanionPayload("Duplicate or oversized Companion archive metadata")
            val extracted = PanelBackup.extractArchive(
                archive,
                pending.map { PanelBackup.ArchiveTarget(it.entry, it.target, CompanionRestore.maxBytes(it.relativePath)) },
                allowedEntries,
            )
            if (!extracted || pending.any { it.target.length() != it.size }) {
                return@withStagedFiles invalidCompanionPayload("Companion archive files are missing, corrupt, or too large")
            }
            val result = CompanionRestore.planFiles(
                packageName = comp.optString("pkg"),
                files = pending.map { CompanionRestore.FilePayload(it.relativePath, it.target) },
                installedPackages = CompanionInstaller.installedPackages(appContext),
            )
            if (result is CompanionRestore.PlanResult.Valid) staged.commit()
            result
        }
    }

    private fun invalidCompanionPayload(reason: String): CompanionRestore.PlanResult.Invalid =
        CompanionRestore.PlanResult.Invalid(reason, InstallPresentation("companion-payload-invalid"))

    /** Validate + apply the config half of a backup (reuses the import apply path). Returns keys applied. */
    private data class RestoreConfigPlan(
        val values: Map<String, String>,
        val warnings: List<String>,
        val errors: List<String>,
    )

    private fun entityBackupJson(state: DashboardEntityBackupState): String = buildString {
        append("{\"instance_key\":").append(jsonStr(state.instanceKey))
        append(",\"instance_origin\":").append(jsonStr(state.instanceOrigin))
        append(",\"instance_uuid\":").append(jsonStr(state.instanceUuid))
        append(",\"dashboard_path\":").append(jsonStr(state.dashboardPath))
        append(",\"filter_ids\":").append(jsonStr(state.filterIds))
        append(",\"filter_enabled\":").append(state.filterEnabled)
        append(",\"filter_owner\":").append(jsonStr(state.filterOwner))
        append(",\"learning_applied\":").append(state.learningApplied)
        append(",\"applied_owner\":").append(jsonStr(state.appliedOwner))
        append(",\"overrides\":").append(jsonStr(state.overrides))
        append(",\"override_owner\":").append(jsonStr(state.overrideOwner))
        append('}')
    }

    private fun entityBackupArchiveJson(
        state: DashboardEntityBackupState,
        filterBytes: Long,
        overrideBytes: Long,
    ): String = buildString {
        append("{\"instance_key\":").append(jsonStr(state.instanceKey))
        append(",\"instance_origin\":").append(jsonStr(state.instanceOrigin))
        append(",\"instance_uuid\":").append(jsonStr(state.instanceUuid))
        append(",\"dashboard_path\":").append(jsonStr(state.dashboardPath))
        append(",\"filter_ids_entry\":").append(jsonStr(ENTITY_FILTER_BACKUP_ENTRY))
        append(",\"filter_ids_size\":").append(filterBytes)
        append(",\"filter_enabled\":").append(state.filterEnabled)
        append(",\"filter_owner\":").append(jsonStr(state.filterOwner))
        append(",\"learning_applied\":").append(state.learningApplied)
        append(",\"applied_owner\":").append(jsonStr(state.appliedOwner))
        append(",\"overrides_entry\":").append(jsonStr(ENTITY_OVERRIDES_BACKUP_ENTRY))
        append(",\"overrides_size\":").append(overrideBytes)
        append(",\"override_owner\":").append(jsonStr(state.overrideOwner))
        append('}')
    }

    private fun planEntityBackup(obj: org.json.JSONObject): DashboardEntityBackupState {
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

    private fun planRestoreConfig(cfgObj: org.json.JSONObject, schema: Int): RestoreConfigPlan {
        val raw = LinkedHashMap<String, String>()
        for (key in cfgObj.keys()) {
            val value = cfgObj.opt(key)
            if (value == null || value == org.json.JSONObject.NULL || value is org.json.JSONObject || value is org.json.JSONArray) {
                return RestoreConfigPlan(emptyMap(), emptyList(), listOf("$key: expected a scalar setting value"))
            }
            raw[key] = value.toString()
        }
        val (migrated, warnings) = Migrations.migrate(schema, raw)
        val decided = planRestoreSettings(migrated, canonicalHaOrigin(config.haUrl))
        val accepted = LinkedHashMap(decided.accepted)
        val errors = ArrayList(decided.errors)
        val ownershipPreserved = preserveUnconfiguredZigbeeOwnership(
            accepted,
            config.zigbeeRouterConfigured,
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

    private suspend fun applyRestoreConfig(
        accepted: Map<String, String>,
        entityState: DashboardEntityBackupState?,
        expectedRevision: String,
        existingOperationTicket: InstallProgress.Ticket,
        onDurableRevision: (String) -> Unit,
        afterCommitBeforeRenderer: (RendererConfigEffects, Int, String) -> Unit,
        afterApply: () -> Unit = {},
    ): Int {
        val result = applyAccepted(
            accepted,
            expectedRevision = expectedRevision,
            entityState = entityState,
            existingOperationTicket = existingOperationTicket,
            onDurableRevision = onDurableRevision,
            afterCommitBeforeRenderer = { effects, appliedHash ->
                afterCommitBeforeRenderer(effects, accepted.size, appliedHash)
            },
            afterApply = afterApply,
        )
        check(result == ApplyAcceptedResult.Applied) {
            if (result is ApplyAcceptedResult.CompatibilityRefused) {
                "configuration refused: ${result.message}"
            } else "configuration commit failed"
        }
        return accepted.size
    }

    /** A Companion-only restore can make an interrupted built-in switch repairable without changing a
     * renderer setting. When an ordinary renderer effect exists, that effect performs preparation. */
    private fun reconcileAfterCompanionRestore(effects: RendererConfigEffects?) {
        if (effects != null && (effects.dashboardChanged || effects.reloadBuiltin || effects.relaunchBuiltin)) return
        val result = rendererPreparation.reconcileStartup(
            ensureHome = { pkg, ready ->
                system.applyLauncherHomePolicy(config.launcherPackage, pkg, ready)
            },
            launchHome = { pkg -> system.launchHome(pkg) },
        )
        requireRendererResult(result)
    }

    /** Execute a prevalidated Companion restore through the descriptor-confined helper transaction. */
    private fun restoreCompanion(plan: CompanionRestore.Plan): CompanionApplyResult {
        if (plan.packageName !in CompanionInstaller.SUPPORTED_PACKAGES || !AndroidInput.isPackage(plan.packageName)) {
            return CompanionApplyResult(
                false,
                InstallProgress.ComponentResult(
                    InstallProgress.Outcome.FAILED,
                    0,
                    "unsupported Companion package",
                    InstallPresentation("companion-unsupported-package"),
                ),
            )
        }
        val preparation = io.github.maxlyth.hapaneld.backup.CompanionDatabasePreparation.prepare(plan, cacheDir)
            ?: return CompanionApplyResult(
                false,
                InstallProgress.ComponentResult(
                    InstallProgress.Outcome.FAILED,
                    0,
                    "Companion payload validation failed",
                    InstallPresentation("companion-payload-invalid"),
                ),
            )
        preparation.use { prepared ->
            val lease = when (
                val acquisition = CompanionDataLease.acquireArmed(
                    plan.packageName,
                    companionDataOperationState,
                    ::retainCompanionLeaseUntilHelperIdle,
                )
            ) {
                is CompanionDataLease.Acquisition.Acquired -> acquisition.lease
                CompanionDataLease.Acquisition.GateBusy -> return CompanionApplyResult(
                    false,
                    InstallProgress.ComponentResult(
                        InstallProgress.Outcome.FAILED,
                        0,
                        "Companion helper is busy",
                        InstallPresentation("companion-helper-busy"),
                    ),
                )
                CompanionDataLease.Acquisition.MarkerFailed -> return CompanionApplyResult(
                    false,
                    InstallProgress.ComponentResult(
                        InstallProgress.Outcome.FAILED,
                        0,
                        "Companion operation safety marker could not be persisted",
                        InstallPresentation("companion-marker-failed"),
                    ),
                )
            }
            var result = CompanionHelperProtocol.RestoreResult.INDETERMINATE
            try {
                result = HelperClient.restoreCompanion(
                    plan.packageName,
                    prepared.files.associate { it.relativePath to it.file },
                )
                if (result == CompanionHelperProtocol.RestoreResult.INDETERMINATE ||
                    result == CompanionHelperProtocol.RestoreResult.BUSY
                ) {
                    lease.settle(possiblyInFlight = true) {
                        if (system.resolveDashboard(config.dashboardPackage) != plan.packageName) {
                            system.launchHome(config.dashboardPackage)
                        }
                    }
                }
            } finally {
                lease.settle(possiblyInFlight = false) {
                    if (result in setOf(
                        CompanionHelperProtocol.RestoreResult.COMMITTED_RELAUNCH_FAILED,
                        CompanionHelperProtocol.RestoreResult.ROLLED_BACK_RELAUNCH_FAILED,
                    )
                    ) system.launchHome(plan.packageName)
                    if (system.resolveDashboard(config.dashboardPackage) != plan.packageName) {
                        system.launchHome(config.dashboardPackage)
                    }
                }
            }
            val repaired = prepared.repairedInternalUrls
            return when (result) {
                CompanionHelperProtocol.RestoreResult.COMMITTED -> CompanionApplyResult(
                    true,
                    InstallProgress.ComponentResult(
                        InstallProgress.Outcome.SUCCEEDED,
                        plan.files.size,
                        if (repaired > 0) "$repaired blank internal URL(s) repaired" else "owner/context restored",
                        if (repaired > 0) {
                            InstallPresentation(
                                "companion-urls-repaired",
                                mapOf("count" to repaired.toString()),
                            )
                        } else {
                            InstallPresentation("companion-owner-restored")
                        },
                    ),
                )
                CompanionHelperProtocol.RestoreResult.COMMITTED_RELAUNCH_FAILED -> CompanionApplyResult(
                    false,
                    InstallProgress.ComponentResult(
                        InstallProgress.Outcome.PARTIAL,
                        plan.files.size,
                        "files restored but Companion relaunch was not confirmed",
                        InstallPresentation("companion-relaunch-unconfirmed"),
                    ),
                )
                CompanionHelperProtocol.RestoreResult.ROLLED_BACK,
                CompanionHelperProtocol.RestoreResult.ROLLED_BACK_RELAUNCH_FAILED -> CompanionApplyResult(
                    false,
                    InstallProgress.ComponentResult(
                        InstallProgress.Outcome.ROLLED_BACK,
                        0,
                        "restore failed; prior Companion files retained",
                        InstallPresentation("companion-prior-files-retained"),
                    ),
                )
                CompanionHelperProtocol.RestoreResult.ROLLBACK_FAILED,
                CompanionHelperProtocol.RestoreResult.ROLLBACK_FAILED_RELAUNCH_FAILED,
                CompanionHelperProtocol.RestoreResult.ROLLBACK_FAILED_RELAUNCH_SUPPRESSED -> CompanionApplyResult(
                    false,
                    InstallProgress.ComponentResult(
                        InstallProgress.Outcome.ROLLBACK_FAILED,
                        null,
                        "restore and rollback failed; Companion state may be partial",
                        InstallPresentation("companion-rollback-failed"),
                    ),
                )
                CompanionHelperProtocol.RestoreResult.BUSY -> CompanionApplyResult(
                    false,
                    InstallProgress.ComponentResult(
                        InstallProgress.Outcome.FAILED,
                        0,
                        "Companion helper is busy",
                        InstallPresentation("companion-helper-busy"),
                    ),
                )
                CompanionHelperProtocol.RestoreResult.NOT_SUBMITTED -> CompanionApplyResult(
                    false,
                    InstallProgress.ComponentResult(
                        InstallProgress.Outcome.FAILED,
                        0,
                        "Companion helper is unavailable",
                        InstallPresentation("companion-helper-unavailable"),
                    ),
                )
                CompanionHelperProtocol.RestoreResult.FAILED -> CompanionApplyResult(
                    false,
                    InstallProgress.ComponentResult(
                        InstallProgress.Outcome.FAILED,
                        0,
                        "restore rejected before commit",
                        InstallPresentation("companion-rejected-before-commit"),
                    ),
                )
                CompanionHelperProtocol.RestoreResult.INDETERMINATE -> CompanionApplyResult(
                    false,
                    InstallProgress.ComponentResult(
                        InstallProgress.Outcome.PARTIAL,
                        null,
                        "restore terminal status was indeterminate",
                        InstallPresentation("companion-indeterminate"),
                    ),
                )
            }
        }
    }

    /** A timed-out socket does not cancel the helper worker. Keep every automatic launch path blocked
     * until a reachable helper affirmatively reports that the transaction can no longer be active. */
    private fun retainCompanionLeaseUntilHelperIdle(
        lease: CompanionDataOperationGate.Lease,
        afterRelease: () -> Unit,
    ) {
        scope.launch(Dispatchers.IO) {
            retainCompanionLeaseUntilHelperIdle(
                lease = lease,
                operationState = companionDataOperationState,
                afterRelease = afterRelease,
                operationStatus = HelperClient::companionOperationStatus,
                pollMs = COMPANION_STATUS_POLL_MS,
            )
        }
    }

    /** List on-panel revisions (newest first) as `[{id, exported_at, keys}]`. */
    private fun revisionsJson(): String =
        "[" + revisions.list().joinToString(",") { (id, b) ->
            "{\"id\":$id,\"exported_at\":\"${b.exportedAt}\",\"keys\":${b.values.size}}"
        } + "]"

    /** Roll back to a stored revision (itself recorded as a new revision, so restores are undoable). */
    private suspend fun handleRevisionRestore(call: ApplicationCall, id: Long) {
        val bundle = revisions.get(id)
        if (bundle == null) {
            call.respondText("""{"status":"not-found"}""", ContentType.Application.Json, HttpStatusCode.NotFound)
            return
        }
        val entityState = revisionEntityState(bundle.values)
        val ordinaryValues = bundle.values.filterKeys { !it.startsWith("$ENTITY_REVISION_PREFIX.") }
        val (migrated, _) = Migrations.migrate(bundle.schema, ordinaryValues)
        val accepted = LinkedHashMap<String, String>()
        for ((key, raw) in migrated) {
            if (SettingsRegistry.parseExposure(key) != null) {
                SettingValue.parseBool(raw)?.let { accepted[key] = it.toString() }
                continue
            }
            val spec = SettingsRegistry.spec(key) ?: continue
            if (spec.readOnly || spec.transient) continue
            (SettingValue.validate(spec, raw) as? Validation.Ok)?.let { accepted[key] = it.normalized }
        }
        preserveUnconfiguredZigbeeOwnership(accepted, config.zigbeeRouterConfigured)
        if (rejectHardenedNetworkAdb(call, accepted["network_adb"])) return
        val revisionDigest = sha256Hex(bundle.serialize().toByteArray(Charsets.UTF_8))
        if (!authorizeSensitive(
                call,
                SensitiveOperation.CONFIG_IMPORT,
                exactHttpApprovalPayload(call, revisionDigest),
                "Restore stored configuration revision $id",
            )
        ) return
        if (entityState == null && (
                accepted["dashboard_entity_overrides"].orEmpty().isNotBlank() ||
                    accepted["dashboard_entity_learning_applied"] == "true"
                )
        ) {
            call.respondText(
                importJson("rejected", emptyList(), emptyList(), emptyList(), listOf("revision lacks entity owner metadata")),
                ContentType.Application.Json,
                HttpStatusCode.UnprocessableEntity,
            )
            return
        }
        val applied = applyAccepted(accepted, entityState = entityState)
        if (applied != ApplyAcceptedResult.Applied) {
            call.respondText(
                importJson(
                    if (applied is ApplyAcceptedResult.CompatibilityRefused) "database-compatibility-refused" else "error",
                    emptyList(), emptyList(), emptyList(),
                    listOf(
                        if (applied is ApplyAcceptedResult.CompatibilityRefused) {
                            applied.message
                        } else "configuration commit failed",
                    ),
                ),
                ContentType.Application.Json,
                if (applied is ApplyAcceptedResult.CompatibilityRefused) HttpStatusCode.Conflict
                else HttpStatusCode.InternalServerError,
            )
            return
        }
        call.respondText(importJson("restored", accepted.keys.toList(), emptyList(), emptyList(), emptyList()), ContentType.Application.Json)
    }

    private fun jarr(items: List<String>): String =
        "[" + items.joinToString(",") { Json.str(it) } + "]"

    /** Add the optional v3 overlay after the legacy object without rewriting its existing fields. */
    private fun withInstallPresentation(
        legacyJson: String,
        presentation: InstallPresentation?,
    ): String {
        if (presentation == null) return legacyJson
        require(legacyJson.endsWith('}'))
        return legacyJson.dropLast(1) + ",\"presentation\":" + presentation.json() + "}"
    }

    private fun importJson(status: String, applied: List<String>, skipped: List<String>, warnings: List<String>, errors: List<String>): String =
        "{\"status\":\"$status\",\"applied\":${jarr(applied)},\"skipped\":${jarr(skipped)},\"warnings\":${jarr(warnings)},\"errors\":${jarr(errors)}}"

    /** Last successfully queried catalog for the current owner; never blocks a config response. */
    private fun haAreaCatalogJson(): String? {
        val credentialed = config.haToken.isNotBlank() || config.haRefreshToken.isNotBlank()
        if (!HaAreaProtocol.canQueryUnprompted(config.haUrl, credentialed)) return null
        val snapshot = captureHaAreaSnapshot()
        val entry = haAreaCatalogCache
        val now = System.nanoTime() / 1_000_000L
        if (entry == null || !haAreaCacheEntryUsable(
                entry.key,
                haAreaCatalogKey(snapshot),
                entry.cachedAtMs,
                now,
                HA_AREA_CATALOG_TTL_MS,
            ) ||
            entry.catalog.ownerKey != snapshot.ownerKey || !entry.catalog.queried
        ) {
            warmHaAreaCatalogInBackground()
            return null
        }
        val catalog = entry.catalog
        val areas = catalog.areas.joinToString(",") { area ->
            "{\"area_id\":${Json.str(area.areaId)},\"name\":${Json.str(area.name)}," +
                "\"icon\":${Json.str(area.icon)}}"
        }
        return "{\"areas\":[$areas],\"device\":{\"found\":${catalog.device.found}," +
            "\"area_id\":${Json.str(catalog.device.areaId)}," +
            "\"area_name\":${Json.str(catalog.device.areaName)}}," +
            "\"admin\":${catalog.admin},\"queried\":true}"
    }

    /** Full config as JSON for fleet management. The MQTT password is never emitted — only a boolean
     *  saying whether one is set. `http_port` is read-only (changing it needs a restart). */
    private fun configJson(
        mutationStatus: String? = null,
        applied: List<String> = emptyList(),
        pending: List<String> = emptyList(),
        rejected: List<String> = emptyList(),
        message: String? = null,
    ): String {
        fun s(v: String) = Json.str(v)
        val powerAdvisory = powerSafetyAdvisory(snapStaleOk().privilege)
        val mutation = mutationStatus?.let {
            "\"ok\":${rejected.isEmpty()}," +
                "\"status\":${s(it)}," +
                "\"applied\":${jarr(applied)}," +
                "\"pending\":${jarr(pending)}," +
                "\"rejected\":${jarr(rejected)}," +
                "\"message\":${s(message.orEmpty())},"
        }.orEmpty()
        val pending = pendingLiveSettings()
        val pendingDesired = pending.entries.joinToString(",") { (key, value) ->
            "${s(key)}:${s(value)}"
        }
        val stalledDesired = jarr(stalledApplyKeys(pending.keys, stalledLiveSettings()))
        return "{" +
            mutation +
            "\"panel_id\":${s(config.panelId)}," +
            "\"ha_area_user_override\":${config.haAreaUserOverride}," +
            "\"friendly_name\":${s(config.friendlyName)}," +
            "\"manufacturer\":${s(config.manufacturer)}," +
            "\"model\":${s(config.model)}," +
            "\"http_port\":${config.httpPort}," +
            "\"mqtt_broker\":${s(config.mqttBroker)}," +
            "\"mqtt_user\":${s(config.mqttUser)}," +
            "\"mqtt_password_set\":${config.mqttPassword.isNotEmpty()}," +
            "\"mqtt_address_family\":${s(config.mqttAddressFamily)}," +
            "\"dashboard_package\":${s(config.dashboardPackage)}," +
            "\"launcher_package\":${s(config.launcherPackage)}," +
            "\"tame_vendor_packages\":${s(config.tameVendorPackagesRaw)}," +
            "\"silence_boot_chime\":${config.silenceBootChime}," +
            "\"keep_awake\":${config.keepAwake}," +
            "\"log_ship_enabled\":${config.logShipEnabled}," +
            "\"log_ship_host\":${s(config.logShipHost)}," +
            "\"log_ship_port\":${config.logShipPort}," +
            "\"log_ship_protocol\":${s(config.logShipProtocol)}," +
            "\"ha_auth\":{\"configured\":${config.haToken.isNotEmpty() || config.haRefreshToken.isNotEmpty()},\"oauth\":${config.haRefreshToken.isNotEmpty()}}," +
            "\"version\":${s(Config.VERSION)}," +
            "\"proximity\":${sensors.proximityJson()}," +
            "\"power_safety\":${PowerSafetyPresentation.json(powerAdvisory)}," +
            // Registry-driven current values + per-key HA-exposure flags for the Configure form.
            "\"settings\":${settingsValuesJson()}," +
            "\"ha_expose\":${haExposeJson()}," +
            haAreaCatalogJson()?.let { "\"ha_area_catalog\":$it," }.orEmpty() +
            "\"apply_pending\":{$pendingDesired}," +
            "\"apply_stalled\":$stalledDesired" +
            "}"
    }

    private fun performanceWorkloadValues(): Map<String, String> {
        val live = snapStaleOk().live
        return PERFORMANCE_WORKLOAD_KEYS.associateWith { key ->
            effectiveValue(requireNotNull(SettingsRegistry.spec(key)), live)
        }
    }

    companion object {
        private const val TAG = "ha-paneld/http"
        private const val GUARD_DB_ARM_RESPONSE_GRACE_MS = 500L
        private const val LOG_SINK_DNS_TIMEOUT_MS = 2_000L
        private const val HARDENED_APPROVAL_TEXT =
            "Requires physical on-panel approval for this action when Hardened mode is enabled."
        private const val HARDENED_CONDITIONAL_APPROVAL_TEXT =
            "Changing this setting may require physical on-panel approval when Hardened mode is enabled."
        private const val RETIRED_PROXIMITY_OPERATION =
            "{\"error\":\"automatic proximity learning replaced this operation\"}"
        private const val PROXIMITY_SOURCE_REQUIRED =
            "{\"error\":\"proximity_source_required\"}"
        private const val ENTITY_REVISION_PREFIX = "_local.entity_state"
        // Late enough that the first pass does not compete with boot (renderer, MQTT, profile activation),
        // early enough that a panel is correct long before anybody opens a settings page.
        // Long enough that a round of page reloads costs one Home Assistant read, short enough that an
        // admin who moves the device in HA sees it here without waiting for the six-hourly pass.
        private const val HA_AREA_CATALOG_TTL_MS = 10 * 60 * 1000L
        private const val HA_AREA_FIRST_PASS_MS = 45_000L
        private const val HA_AREA_REPEAT_MS = 6 * 60 * 60 * 1000L
        private const val TAME_SHUTDOWN_MS = 5_000L
        private const val REMOTE_CONTROL_SHUTDOWN_MS = 5_000L
        private const val REMOTE_TAP_QUEUE_DEADLINE_MS = 5_000L
        // The panel renderer can blank its surface briefly after input. Give it a bounded redraw
        // window before the one-shot capture; the response still has a hard overall deadline.
        private const val REMOTE_TAP_CAPTURE_SETTLE_MS = 1000L
        private const val REMOTE_SCREENSHOT_WAIT_MS = 25_000L
        private const val REMOTE_TAP_CAPTURE_TIMEOUT_MS = 45_000L
        private const val REMOTE_TAP_CAPTURE_RESPONSE_TIMEOUT_MS = 60_000L
        private val OPAQUE_AUTO_SLEEP_KEY = Regex("^[a-f0-9]{64}$")

        /** Keys routed through [applySetting] after an HTTP persistence commit, declared by the registry. */
        internal val HTTP_LIVE_KEYS = SettingsRegistry.liveApplyKeys()

        /** HTML pages that follow the panel into guided setup while it is waiting on a person. `/setup`
         *  itself, the API, assets and the OAuth callback are deliberately absent. */
        internal val WIZARD_REDIRECT_PAGES = setOf(
            "/", "/configure", "/profiles", "/install", "/logs", "/entities", "/api",
        )

        private val PROFILE_FACT_KEYS =
            listOf("Platform", "SoC", "LED", "Light sensor", "Proximity", "Zigbee", "Relays", "CPU profile")

        /**
         * Exact-profile declarations suppress rows for hardware that is both declared absent and absent
         * at runtime. Generic keeps the capability discovery set but omits an unknown SoC identity,
         * while an unexpected positive runtime observation remains visible so a stale exact profile
         * can still be corrected.
         */
        internal fun profileFactKeys(profile: DeviceProfile, facts: Map<String, String>): List<String> {
            val declaredSoc = profile.socClass.trim().takeUnless { it.isBlank() || it == "?" || it.equals("unknown", ignoreCase = true) }
            val availableKeys = PROFILE_FACT_KEYS.filterNot { it == "SoC" && declaredSoc == null }
            if (profile.id == "generic") return availableKeys
            fun observed(key: String, vararg absent: String): Boolean =
                facts[key]?.trim()?.lowercase()?.let { it !in absent.toSet() } ?: false
            return availableKeys.filter { key ->
                when (key) {
                    "LED" -> profile.ledMechanism != LedMechanism.NONE || observed(key, "none")
                    "Light sensor" -> profile.lightTech != null || observed(key, "no")
                    "Proximity" -> profile.proximityTech != null || observed(key, "no")
                    "Zigbee" -> profile.zigbeeGatewayDir != null || observed(key, "none")
                    "Relays" ->
                        profile.relayBase != null || profile.relayBaseFallbacks.isNotEmpty() ||
                            observed(key, "none")
                    "CPU profile" -> profile.cpuGovernors != null || observed(key, "n/a")
                    else -> true
                }
            }
        }


        // Probe-cache TTLs: the dashboard renders from the snapshot, so these bound both staleness
        // and how often the su round-trips can run. Density/su flap even less than the rest.
        private const val SNAP_TTL_MS = 15_000L
        private const val DIAG_TTL_MS = 15_000L
        private const val DENSITY_TTL_MS = 30_000L
        private const val SU_TTL_MS = 60_000L
        private const val COMPANION_URL_TTL_MS = 60_000L
        private const val COMPANION_STATUS_POLL_MS = 1_000L
        internal const val MAX_PLAY_BODY_BYTES = 16L * 1024L
        internal const val MAX_CONFIG_POST_BODY_BYTES = 256L * 1024L
        internal const val MAX_SMALL_FORM_POST_BODY_BYTES = 16L * 1024L
        internal const val MAX_ENTITY_ADMIN_BODY_BYTES = 256L * 1024L
        internal const val MAX_CONFIG_IMPORT_BYTES = 1L * 1024L * 1024L
        internal const val MAX_RESTORE_BYTES = 64L * 1024L * 1024L
        internal const val MAX_APK_UPLOAD_BYTES = 256L * 1024L * 1024L
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
        private const val ENTITY_FILTER_BACKUP_ENTRY = "entity/filter-ids.txt"
        private const val ENTITY_OVERRIDES_BACKUP_ENTRY = "entity/overrides.txt"

        /**
         * The complete `app_state` dump. The manifest's `config` block is a projection of declared
         * settings, so it cannot represent a namespace that is not a setting; this entry is the whole
         * table, in the same flat-text codec the config vault uses.
         */
        private const val STATE_BACKUP_ENTRY = "state/app-state.txt"

        /** Configuration is tens of kilobytes on real panels; this is headroom, not a target. */
        internal const val MAX_STATE_BACKUP_BYTES = 4L * 1024L * 1024L
        private val ENTITY_STATE_CONFIG_KEYS = setOf(
            "dashboard_entity_overrides",
            "dashboard_entity_learning_applied",
        )

        // Dashboard fact rows that are BACKED BY A SETTING → the Configure anchor the ✎ marker
        // deep-links to. Facts absent here are static (hardware/runtime) and get no marker.
        private val FACT_CFG = mapOf(
            "panel_id" to "cfg-panel_id",
            "Friendly name" to "cfg-friendly_name",
            "MQTT" to "cfg-mqtt_broker",
            "Navbar" to "cfg-navbar_mode",
            "Zigbee" to "cfg-zigbee_router",
            "CPU profile" to "cfg-cpu_governor",
            "Network ADB" to "cfg-network_adb",
            "Log shipping" to "cfg-log_ship_enabled",
        )
        private const val RELEASES_URL = "https://github.com/panel-assistant/android/releases"
        private const val REPO_URL = "https://github.com/panel-assistant/android"
        private const val WEBVIEW_DOC = "https://panel-assistant.io/go/docs?page=hardware/readme"
        private const val SHIZUKU_GUIDE_DOC = "https://panel-assistant.io/go/docs?page=provisioning"
        private const val DEVICE_PROFILES_DOC = "https://panel-assistant.io/go/docs?page=architecture/device-profiles"
        // GitHub mark (official, CC0 simple-icons) + Material "open in new" glyph — icon links in the UI.
        private const val GH_ICON = "M12 .297c-6.63 0-12 5.373-12 12 0 5.303 3.438 9.8 8.205 11.385.6.113.82-.258.82-.577 0-.285-.01-1.04-.015-2.04-3.338.724-4.042-1.61-4.042-1.61C4.422 18.07 3.633 17.7 3.633 17.7c-1.087-.744.084-.729.084-.729 1.205.084 1.838 1.236 1.838 1.236 1.07 1.835 2.809 1.305 3.495.998.108-.776.417-1.305.76-1.605-2.665-.3-5.466-1.332-5.466-5.93 0-1.31.465-2.38 1.235-3.22-.135-.303-.54-1.523.105-3.176 0 0 1.005-.322 3.3 1.23.96-.267 1.98-.399 3-.405 1.02.006 2.04.138 3 .405 2.28-1.552 3.285-1.23 3.285-1.23.645 1.653.24 2.873.12 3.176.765.84 1.23 1.91 1.23 3.22 0 4.61-2.805 5.625-5.475 5.92.42.36.81 1.096.81 2.22 0 1.606-.015 2.896-.015 3.286 0 .315.21.69.825.57C20.565 22.092 24 17.592 24 12.297c0-6.627-5.373-12-12-12"
    }
}

/**
 * Project a config-import preview without turning the dry-run endpoint into a secret read oracle.
 *
 * Every submitted secret is represented as the same redacted change regardless of whether it equals the
 * current value. The key remains visible so the preview can confirm its scope, but neither credential
 * content nor equality is exposed.
 */
internal fun configDryRunJson(
    diff: List<ConfigDiff.Change>,
    skipped: List<String>,
    warnings: List<String>,
    expectedConfig: String,
): String {
    fun q(value: String) = Json.str(value)
    fun array(items: List<String>) = "[" + items.joinToString(",") { q(it) } + "]"
    val changes = diff.joinToString(",") { change ->
        val secret = SettingsRegistry.spec(change.key)?.secret == true
        val from = if (secret) q(REDACTED_CONFIG_VALUE) else change.from?.let(::q) ?: "null"
        val to = if (secret) q(REDACTED_CONFIG_VALUE) else q(change.to)
        "{\"key\":${q(change.key)},\"from\":$from,\"to\":$to}"
    }
    return "{\"status\":\"dry_run\",\"expected_cfg\":${q(expectedConfig)}," +
        "\"changes\":[$changes],\"skipped\":${array(skipped)},\"warnings\":${array(warnings)}}"
}

internal fun zigbeeWarningText(snapshot: ZigbeeHealthSnapshot, configuredOn: Boolean): String? = when {
    snapshot.state == ZigbeeHealthState.CONTAINED ->
        "⛔ <b>Zigbee gateway runaway was contained</b> — the router switch was turned OFF after sustained unjoined high CPU or repeated restarts."
    snapshot.state == ZigbeeHealthState.CONTAINMENT_FAILED ->
        "⛔ <b>Zigbee gateway containment was incomplete</b> — the respawner was stopped where possible and surviving work was demoted. Review diagnostics before retrying."
    snapshot.state == ZigbeeHealthState.RUNAWAY ->
        "⛔ <b>Zigbee gateway is runaway</b> — automatic containment is in progress."
    snapshot.state == ZigbeeHealthState.DEGRADED_HIGH_CPU ->
        "⚠ <b>Joined Zigbee gateway has sustained high CPU</b> — it remains running because joined routers are warn-only."
    snapshot.state == ZigbeeHealthState.DEGRADED_UNJOINED && configuredOn ->
        "⚠ <b>Zigbee router is enabled but not joined</b> — repeated join retries can consume substantial CPU. " +
            "Join this panel to your Zigbee coordinator or turn the Zigbee router switch OFF. " +
            "<a href=\"configure#cfg-zigbee_join\">Resolve Zigbee setup →</a>"
    snapshot.recursiveWatchdogAssignment ->
        "⚠ <b>Legacy Zigbee watchdog defect detected</b> — the exact recursive LD_LIBRARY_PATH assignment is present. ha-paneld will not edit the vendor script automatically."
    else -> null
}

private const val REDACTED_CONFIG_VALUE = "[redacted]"

/** Public/UI concurrency hashes deliberately exclude credential-bearing settings. */
internal fun configConcurrencyValues(values: Map<String, String>): Map<String, String> =
    values.filterKeys { key -> SettingsRegistry.spec(key)?.secret != true }

/**
 * Secret submissions always produce the same projected entry, including when the guess equals the
 * stored value. This preserves acknowledgement that a secret was submitted without an equality oracle.
 */
internal fun configPreviewDiff(
    current: Map<String, String>,
    candidate: Map<String, String>,
): List<ConfigDiff.Change> {
    val ordinary = ConfigDiff.diff(
        current,
        candidate.filterKeys { key -> SettingsRegistry.spec(key)?.secret != true },
    )
    val secrets = candidate.keys
        .filter { key -> SettingsRegistry.spec(key)?.secret == true }
        .map { key -> ConfigDiff.Change(key, null, REDACTED_CONFIG_VALUE) }
    return (ordinary + secrets).sortedBy { it.key }
}

/** Keep room for the received envelope, authenticated plaintext, and extracted Companion payloads. */
internal fun restoreBodyStagingLimit(
    usableBytes: Long,
    maxPayloadBytes: Long = PaneldServer.MAX_RESTORE_BYTES,
    safetyMarginBytes: Long = 64L * 1024L * 1024L,
): Long {
    if (usableBytes <= safetyMarginBytes || maxPayloadBytes <= 0L) return 0L
    return minOf(maxPayloadBytes, (usableBytes - safetyMarginBytes) / 3L)
}

/** Start one HTTP-engine generation. A failed start must release any partially acquired engine resources
 * and close request admission without replacing the original failure that the service lifecycle observes. */
internal fun startOwnedHttpServer(
    start: () -> Unit,
    stop: () -> Unit,
    closeIngress: () -> Unit,
) {
    try {
        start()
    } catch (error: Exception) {
        runCatching(stop)
        runCatching(closeIngress)
        throw error
    }
}

/**
 * Do not admit a new best-effort startup probe once HTTP teardown has begun. The volatile admission
 * read is the phase boundary: an already-admitted read-only phase may finish while teardown closes
 * HTTP, then the service scope's cancellation/join owns its bounded drain.
 */
internal fun runPrewarmPhases(
    isStopping: () -> Boolean,
    management: () -> Unit,
    companion: () -> Unit,
) {
    if (isStopping()) return
    management()
    if (isStopping()) return
    companion()
}

/** Close every HTTP-owned admission/resource and retain any ambiguous result while continuing the sweep. */
internal fun stopHttpOwners(
    closeOperationAdmission: () -> Unit,
    closeUploadIngress: () -> Unit,
    stopEngine: () -> Unit,
    stopRelay: () -> Boolean,
    drainTameMutations: () -> Boolean,
    drainRemoteControls: () -> Boolean,
    onIncomplete: (step: String, error: Throwable?) -> Unit,
): Boolean {
    var complete = true
    fun prove(step: String, action: () -> Boolean) {
        val result = runCatching(action)
        if (result.getOrDefault(false)) return
        complete = false
        runCatching { onIncomplete(step, result.exceptionOrNull()) }
    }

    prove("clear-storage admission") { closeOperationAdmission(); true }
    prove("pending uploads") { closeUploadIngress(); true }
    prove("HTTP engine stop request") { stopEngine(); true }
    prove("CDP relay", stopRelay)
    prove("vendor mutation", drainTameMutations)
    prove("remote control", drainRemoteControls)
    return complete
}

internal fun fleetImportPreservesTargetLocalValue(fleet: Boolean, key: String, normalized: String): Boolean =
    fleet && key == "ha_url" && normalized.isEmpty()

/** Coalesced renderer work caused by a committed configuration change. */
internal data class RendererConfigEffects(
    val dashboardChanged: Boolean,
    val reloadBuiltin: Boolean,
    val relaunchBuiltin: Boolean,
    val darkMode: Boolean?,
) {
    companion object {
        private val CREDENTIAL_KEYS = setOf(
            "ha_url", "ha_token", "ha_refresh_token", "ha_token_expiry", "ha_client_id",
        )

        fun credentialsChanged(previous: Map<String, String>, accepted: Map<String, String>): Boolean {
            fun changed(key: String): Boolean {
                val next = accepted[key] ?: return false
                val before = previous[key]
                return if (key == "ha_url") next.trimEnd('/') != before?.trimEnd('/') else next != before
            }
            val accessReplacesRefresh = accepted["ha_token"]?.isNotEmpty() == true &&
                "ha_refresh_token" !in accepted && previous["ha_refresh_token"].orEmpty().isNotEmpty()
            val urlClearDropsCredentials = accepted["ha_url"]?.isEmpty() == true &&
                listOf("ha_token", "ha_refresh_token", "ha_client_id").any { previous[it].orEmpty().isNotEmpty() }
            return CREDENTIAL_KEYS.any(::changed) || accessReplacesRefresh || urlClearDropsCredentials
        }

        fun between(previous: Map<String, String>, accepted: Map<String, String>): RendererConfigEffects {
            fun changed(key: String): Boolean {
                val next = accepted[key] ?: return false
                val before = previous[key]
                return if (key == "ha_url") next.trimEnd('/') != before?.trimEnd('/') else next != before
            }
            return coalesce(
                dashboardChanged = changed("dashboard_package"),
                credentialChanged = credentialsChanged(previous, accepted),
                zoomChanged = changed("dashboard_zoom"),
                fullscreenChanged = changed("dashboard_fullscreen"),
                overscrollChanged = changed("dashboard_overscroll"),
                nativeKioskChanged = changed("dashboard_native_kiosk"),
                homeChanged = changed("home_dashboard"),
                darkMode = accepted["dark_mode"]?.toBooleanStrictOrNull()
                    ?.takeIf { changed("dark_mode") && android.os.Build.VERSION.SDK_INT < 29 },
                // Unlike dark_mode this has no SDK gate: the policy is the only lever that re-themes
                // Home Assistant, and it is meaningful on every panel (Android 10+ has no HA theme
                // lever at all today, which is half of what this setting exists to fix).
                themePolicyChanged = changed("dashboard_theme"),
            )
        }

        fun coalesce(
            dashboardChanged: Boolean,
            credentialChanged: Boolean,
            zoomChanged: Boolean,
            fullscreenChanged: Boolean,
            overscrollChanged: Boolean,
            nativeKioskChanged: Boolean = false,
            // A new home path only took effect on the next incidental reload, so editing it in the UI
            // appeared to do nothing. A change reloads the built-in renderer onto the new home now.
            homeChanged: Boolean = false,
            darkMode: Boolean?,
            // The colour-scheme policy is baked into a document-start script, which cannot be replaced
            // in a live WebView, so a change must reach a fresh page load rather than a foregrounding.
            themePolicyChanged: Boolean = false,
        ): RendererConfigEffects {
            val reload = !dashboardChanged &&
                (credentialChanged || zoomChanged || homeChanged || darkMode != null || themePolicyChanged)
            val relaunch = !dashboardChanged && !reload &&
                (fullscreenChanged || overscrollChanged || nativeKioskChanged)
            return RendererConfigEffects(dashboardChanged, reload, relaunch, darkMode)
        }
    }
}

/** What an archive's settings would restore to, and why any of them cannot. */
internal data class RestoreSettingsDecision(
    val accepted: Map<String, String>,
    val errors: List<String>,
)

/**
 * A stored value from an older archive, in the form the current validator can read.
 *
 * `home_dashboard` had no validator before this release, so an archive can hold a whole address. The
 * path canonicalizer refuses a URL scheme, so handing it one returns null and falls back to the
 * original, which then fails validation — and because a restore is all or nothing, that single
 * historical value takes the entire archive with it, precisely when its owner needs it. This panel's
 * own origin is stripped first, exactly as the live store does on upgrade. A route naming a different
 * server is left alone and still refused, rather than silently retargeted at someone else's dashboard.
 */
internal fun restorableSettingValue(key: String, value: String, configuredOrigin: String?): String =
    when (key) {
        "home_dashboard" -> {
            val candidate = sameOriginDashboardRoute(value, configuredOrigin) ?: value
            if (DashboardPath.followsAccountDefault(candidate)) ""
            else DashboardPath.canonical(candidate, preserveRoute = true) ?: value
        }
        else -> value
    }

/** The restore plan's per-setting decision, separated from the transport so it can be asserted. */
/**
 * Configuration adjustments for a migration-mode restore. The bridge made the successor a kiosk
 * companion of itself so its return loop would leave the successor in the foreground; carried into
 * the successor that entry would exempt the legacy package, and the successor itself, from its own
 * kiosk lock. Every other value is restored exactly as written.
 */
internal fun migrationRestoreConfig(values: Map<String, String>): Map<String, String> {
    val restored = LinkedHashMap(values)
    // A setting that names this app's own package is a sentinel, not a foreign app: the launcher
    // selection "Panel admin" and the built-in renderer are both stored as the writer's package name.
    // Left as written they would name the legacy package, which is about to be removed.
    MIGRATION_OWN_PACKAGE_SETTINGS.forEach { key ->
        if (restored[key] == io.github.maxlyth.hapaneld.AppIdentity.LEGACY) {
            restored[key] = io.github.maxlyth.hapaneld.AppIdentity.SUCCESSOR
        }
    }
    restored["kiosk_companion_packages"]?.let { companions ->
        restored["kiosk_companion_packages"] = io.github.maxlyth.hapaneld.parseKioskCompanionPackages(companions)
            .filterNot(io.github.maxlyth.hapaneld.AppIdentity::isPanelApp)
            .joinToString(",")
    }
    return if (restored == values) values else restored
}

internal val MIGRATION_OWN_PACKAGE_SETTINGS: Set<String> = setOf("launcher_package", "dashboard_package")

/** Whether a migration-mode restore wrote back everything its receipt carried. */
internal fun migrationRestoreComplete(rawPreferencesApplied: Boolean, carriedRows: Int, restoredRows: Int): Boolean =
    rawPreferencesApplied && restoredRows == carriedRows

internal fun planRestoreSettings(
    migrated: Map<String, String>,
    configuredOrigin: String?,
): RestoreSettingsDecision {
    val accepted = LinkedHashMap<String, String>()
    val errors = ArrayList<String>()
    for ((key, value) in migrated) {
        val spec = SettingsRegistry.spec(key)
        val exposedSpec = SettingsRegistry.parseExposure(key)
        when {
            exposedSpec != null -> {
                val normalized = SettingValue.parseBool(value)?.toString()
                if (normalized == null) errors += "$key: expected a boolean" else accepted[key] = normalized
            }
            spec == null -> errors += "$key: unknown setting"
            spec.readOnly || spec.transient -> errors += "$key: setting cannot be restored"
            else -> when (
                val validated = SettingValue.validate(spec, restorableSettingValue(key, value, configuredOrigin))
            ) {
                is Validation.Ok -> accepted[key] = validated.normalized
                is Validation.Bad -> errors += "$key: ${validated.reason}"
            }
        }
    }
    return RestoreSettingsDecision(accepted, errors)
}
