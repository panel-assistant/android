package io.github.maxlyth.hapaneld.http

import android.content.Context
import android.os.SystemClock
import android.util.Log
import io.github.maxlyth.hapaneld.canonicalHaOrigin
import io.github.maxlyth.hapaneld.Config
import io.github.maxlyth.hapaneld.MigrationNotice
import io.github.maxlyth.hapaneld.NativeLocale
import io.github.maxlyth.hapaneld.sensors.HaLifecycleMessage
import io.github.maxlyth.hapaneld.sensors.HaLifecycleRuntime
import io.github.maxlyth.hapaneld.sensors.HaNetworkPathRuntime
import io.github.maxlyth.hapaneld.sensors.PathProbeRuntime
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
import io.github.maxlyth.hapaneld.camera.CameraState
import io.github.maxlyth.hapaneld.camera.CameraSurface
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
import io.github.maxlyth.hapaneld.security.LocalApprovalBroker
import io.github.maxlyth.hapaneld.security.SensitiveOperation
import io.github.maxlyth.hapaneld.sensors.SensorReporter
import io.github.maxlyth.hapaneld.sensors.HaCurrentUserClient
import io.github.maxlyth.hapaneld.shizuku.ShizukuBridge
import io.github.maxlyth.hapaneld.storage.StorageHealthRuntime
import io.github.maxlyth.hapaneld.storage.StorageHealthSnapshot
import io.github.maxlyth.hapaneld.util.AccessDenialMemo
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
import io.github.maxlyth.hapaneld.util.HelperClient
import io.github.maxlyth.hapaneld.util.GuardDbArmCoordinator
import io.github.maxlyth.hapaneld.util.GuardDbMaintenance
import io.github.maxlyth.hapaneld.util.guardDbSettingsAuthorityStore
import io.github.maxlyth.hapaneld.util.guardDbAppStaging
import io.github.maxlyth.hapaneld.util.guardDbBootNonce
import io.github.maxlyth.hapaneld.util.guardDbSentinelStore
import io.github.maxlyth.hapaneld.util.guardDbTerminalRetirementStore
import io.github.maxlyth.hapaneld.util.inspectGuardDbCandidate
import io.github.maxlyth.hapaneld.util.HaLink
import io.github.maxlyth.hapaneld.util.LogShipEndpoint
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
import io.github.maxlyth.hapaneld.panelassistant.PanelAssistantTransportProtocol
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveStream
import io.ktor.server.request.receiveText
import io.ktor.server.request.uri
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.Route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
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
    private val panelAssistantRestartHealth: () -> String = { "" },
    private val releasePanelAssistantTransport: () -> Unit,
    /** Read a bundled static asset (info.js / info.css) as text. */
    // Platform asset access; JVM HTTP hosts read the same bundled files from disk.
    private val asset: (String) -> String = { name ->
        appContext.assets.open(name).bufferedReader().use { it.readText() }
    },
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
    private val catalogueLoader by lazy { CatalogueLoader(asset) }
    private val pages get() = PageShell(config, catalogueLoader, ::setupNeedsUser, ::buildToken, ::renderConfigConcurrencyHash)

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
    @Volatile private var stopping = true
    private val haArea = HaAreaRuntime(config, entityLearning, scope, directConfigMutationLock) { stopping }
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
    private val remoteControlRoutes = RemoteControlRoutes(
        config = config,
        interactive = interactive,
        system = system,
        stepVolume = { volume.step(up = it) },
        stopping = { stopping },
        cacheScreenshot = { screenshots.store(it) },
    )
    private val clearStorageGate = GenerationSingleFlight()
    private val pendingApks = PendingUploadStore()
    private val guardDbStaging = guardDbAppStaging(appContext)

    fun start() {
        stopping = false
        pendingApks.open()
        // Bind the IPv6 wildcard "::" — on Android this is dual-stack (net.ipv6.bindv6only=0), so the
        // server answers on both IPv6 and IPv4, instead of the IPv4-only default 0.0.0.0.
        val server = scope.embeddedServer(CIO, port = config.httpPort, host = "::") {
            mount(this)
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
            haArea.startHaAreaConvergence()
            Log.i(TAG, "HTTP listening on :${config.httpPort}")
        } catch (e: Exception) {
            // HTTP is part of the service's required control plane. Propagate a bind/start failure to the
            // runtime owner so this generation becomes FAILED and a staged profile cannot be marked healthy.
            stopping = true
            Log.e(TAG, "HTTP bind on :${config.httpPort} failed", e)
            throw e
        }
    }
    internal fun mount(application: io.ktor.server.application.Application) {
        with(application) {
            paneldRoot(
                allowedHosts = { config.httpAllowedHosts },
                setupNeedsUser = ::setupNeedsUser,
                setupRedirectLocation = ::setupRedirectLocation,
            ) {
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
                        identityMigration = identityMigration,
                        apkUpload = ApkUploadRouteDependencies(
                            enabled = { config.apkUploadAllowed },
                            rootAvailable = { rootOk() },
                            pending = pendingApks,
                            createStagingFile = { File.createTempFile("apk-upload-", ".apk", appContext.cacheDir) },
                            inspect = { staged ->
                                withContext(Dispatchers.IO) { AppInstaller.inspect(appContext, staged.absolutePath) }?.let {
                                    UploadedApkIdentity(it.pkg, it.version, it.signerSha256, it.signerSha256s, it.versionCode)
                                }
                            },
                            startInstall = { claimed, progress ->
                                val apk = claimed.file
                                val job = scope.launch {
                                    val result = runCatching {
                                        installUploadedApk(
                                            claimed,
                                            identityMigration,
                                            install = { AppInstaller.installLocalApk(appContext, it) },
                                        )
                                    }.getOrElse {
                                        apk.delete()
                                        "error: ${it.message}"
                                    }
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
                assetRoutes(asset)
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
                        pages.page(
                            active = "configure",
                            title = strings.get("shell.nav.configure"),
                            body = configureBody(strings, sensors.hasProximity(), configureSetupBanners(strings)),
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
                        pages.pageShell(
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
                        pages.page("profiles", strings.get("shell.nav.profile"), profilesBody(strings), strings, call.embedMode()),
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
                            pages.page("install", strings.get("shell.nav.install"), installBody(strings), strings, call.embedMode())
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
                        pages.page("fleet", strings.get("shell.nav.fleet"), fleetBody(strings), strings, call.embedMode()),
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
                        pages.page("logs", strings.get("shell.nav.logs"), logsBody(strings), strings, call.embedMode()),
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
                        pages.page("entities", strings.get("shell.nav.entities"), entitiesBody(strings, config.dashboardEntityLearningEnabled && effectiveDashboardIsBuiltin()), strings, call.embedMode()),
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
                    call.respondText("ha-paneld ${Config.VERSION} panel=${config.panelId} build=${buildToken()} cfg=${renderConfigConcurrencyHash()}${panelAssistantDiscoveryHealthToken(config.androidId)}${packageHealthToken(appContext.packageName)}${versionCodeHealthToken(BuildConfig.VERSION_CODE)}${haLifecycleHealthToken()}${haNetworkHealthToken()}${panelAssistantRestartHealth()} pa_notice=${if (config.migrationNoticeVisible()) 1 else 0}\n")
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
                        call.respondText("ha-paneld ${Config.VERSION} panel=${config.panelId} build=${buildToken()} cfg=${renderConfigConcurrencyHash()}${panelAssistantDiscoveryHealthToken(config.androidId)}${packageHealthToken(appContext.packageName)}${versionCodeHealthToken(BuildConfig.VERSION_CODE)}${haLifecycleHealthToken()}${haNetworkHealthToken()}${panelAssistantRestartHealth()} pa_notice=${if (config.migrationNoticeVisible()) 1 else 0}\n")
                    }
                    post("/migration-notice/dismiss") {
                        val persisted = config.dismissMigrationNotice()
                        call.respondText(
                            """{"ok":$persisted}""",
                            ContentType.Application.Json,
                            if (persisted) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
                        )
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
                    haAreaRoutes { haArea.areaJson() }
                    configProbeRoutes(config)
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
                    installConfigBundleRoutes()
                    // Restore a .hpb bundle (raw body; passphrase in the X-Backup-Passphrase header so it
                    // never lands in a query log). ?dry_run=1 decrypts + reports the contents WITHOUT writing.
                    // A real restore is DESTRUCTIVE (rewrites config; force-stops + rewrites the Companion DB).
                    post("/restore") { handleRestore(call) }
                    performanceRoutes(
                        admit = { admitActiveRead(it) },
                        perf = { PerfReader.touch(); PerfReader.json() },
                        binding = { id -> performanceBindingJson(id, performanceBindingSecret, config.panelId, performanceWorkloadValues()) },
                        costs = FeatureCosts::json,
                        history = { entityLearning.performanceHistoryJson(it) },
                    )
                    autoBrightnessRoutes(autoBrightnessHttpApi, { admitActiveRead(it) }, ::receiveEntityAdminJson)
                    autoSleepRoutes(autoSleepHttpApi, { admitActiveRead(it) }, ::receiveEntityAdminJson)
                    EntityRoutes(config, entityLearning) {
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
                    }.mount(this)
                    proximityRoutes(sensors::hasProximity, sensors::proximityJson, onProximityCalibration)
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
                    discoveryRoutes({ peersJson(peers()) }, { launchableAppsJson(appContext) })
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
                    loggingRoutes(
                        logApp, logSystem, logWebView, webViewConsoleEnabled, logShipStatus,
                        admitActiveRead = { admitActiveRead(it) },
                    )
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
                    powerSafetyRoutes(
                        config, powerSafety,
                        powerSafetyAdvisory = { powerSafetyAdvisory(snapStaleOk().privilege) },
                        onRepairPowerSafety, freshPowerSafetyRepairCapability,
                        snapInvalidate = ::snapInvalidate,
                        authorizeSensitive = ::authorizeSensitive,
                    )
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
                    radioRoutes(
                        status = radioStatus,
                        configured = { config.zigbeeRouterConfigured },
                        enabled = { config.zigbeeRouterEnabled },
                        join = onZigbeeJoinRetry,
                    )
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
                    remoteControlRoutes({ remoteControlRoutes }, ::authorizeSensitive)
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
                    screenshotRoutes(screenshots, { interactive.screenshot() }, { admitActiveRead(it) })
                    cameraRoutes(camera, ::admitActiveRead)
                    get("/openapi.json") {
                        call.respondText(asset("openapi.json"), ContentType.Application.Json)
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
                    displayRoutes(
                        appContext, profile, density, densityCache, recommendedDensity, recommendedFontScale,
                        requestStrings = ::requestStrings,
                        snapInvalidate = ::snapInvalidate,
                        authorizeSensitive = ::authorizeSensitive,
                        escapeHtml = ::esc,
                        localizedHref = ::localizedHref,
                    )
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
        haArea.stop()
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
            drainRemoteControls = { remoteControlRoutes.closeAndJoin(REMOTE_CONTROL_SHUTDOWN_MS) },
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

    /** Panel Assistant granted native authority, so this panel reaches Home Assistant without MQTT. */
    private fun panelAssistantNative(): Boolean =
        config.panelAssistantAuthority == PanelAssistantTransportProtocol.AUTHORITY_NATIVE

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
        val compPkg = CompanionInstaller.installedPkg(appContext)
        val compCur = compPkg?.let { AppInstaller.installedVersion(appContext, it) }?.takeIf { it.isNotBlank() }
        return installPageBody(
            strings = strings,
            warnings = warnings,
            allGood = allGood,
            components = componentsCardHtml(wv, root, installer, strings, config, compPkg, compCur, profile.recommendedWebView != null),
            apk = apkCardHtml(root, strings, config),
            uninstall = uninstallCardHtml(su, strings),
            vendor = tameCardHtml(root, strings) { tame.cardCandidates(config.tameVendorPackages, tameProfileCandidates) },
            display = displayCardHtml(management.privilege.typedShellControlReady, displaySizing, strings, recommendedDensity, recommendedFontScale),
            backup = backupCardHtml(companionHelper, CompanionInstaller.installedPkg(appContext) != null, strings),
        )
    }

    /** Removable apps (third-party or updated-system) for the Uninstall picker, sorted by label. Stock
     *  system apps + ha-paneld are excluded — pm can't uninstall stock system apps (only disable), and
     *  self-uninstall would kill the tool. */
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
            "\"capabilities\":[$caps],${installCapabilityStatusJson(management.privilege)}," +
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

    private val screenshots = ScreenshotCache(appContext.filesDir)

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

    private val termuxBridgeCache = Cached(SNAP_TTL_MS) {
        TermuxBridgeProbe.collect(
            termuxUid = runCatching<Int?> { appContext.packageManager.getApplicationInfo("com.termux", 0).uid }
                .recoverCatching { if (it is android.content.pm.PackageManager.NameNotFoundException) null else throw it },
            routes = { privilegeObservation().let { it.directSuReady to it.helperRootReady } },
            rootRun = Su::runOutputIsolatedBounded,
            helperRun = { HelperClient.sendBytes(it)?.toString(Charsets.UTF_8) },
        )
    }

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
            termuxBridge = termuxBridgeCache.get(),
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
        val needs = SetupBanner.needs(mqtt, config.mqttBroker.isNotBlank(), config.mqttUser.isNotBlank(), panelAssistantNative())
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
        val termuxBridge = if (termuxBridgeCache.get() == TermuxBridgeProbe.State.RUNNING) {
            """<div class="setup">⚠ ${esc(strings.get("dashboard.banner.panel_bridge_running"))}</div>"""
        } else ""
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
            findings.joinToString("") { bannerFor(it, strings) } + termuxBridge + proximityLearning + haSetup + mqttProgress + setup
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
                    """<div style="margin-top:10px"><button class="pbtn"${hardenedApprovalAttrs(strings = catalogueLoader.strings(AppLocale.ENGLISH))} onclick="repairCompUrl(this)">⚙ ${esc(strings.get("dashboard.banner.companion_url.repair"))}</button> <span id="cu-fix" class="muted"></span></div>"""
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
        "silence_boot_chime", "keep_awake", "navbar_mode", "log_ship_enabled", "log_ship_system_enabled",
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


    /** Visible "this needs root" banner for a root-gated card/control group — shown (never hidden) so a
     *  no-root user sees the feature and what root would unlock, next to controls rendered disabled. */
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
        return """{"banners":${jsonStr(bannersHtml(s, h, strings))},"shot":${s.privilege.typedShellControlReady},"shotCached":${jsonStr(screenshots.placeholderUrl() ?: "")},"versionCode":${BuildConfig.VERSION_CODE},"package":${jsonStr(BuildConfig.APPLICATION_ID)},"controls":${jsonStr(controlsHtml(s, strings))},"cards":{$cards}}"""
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
        val cachedShot = screenshots.placeholderUrl()
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
        return pages.pageShell(
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
${tcard("nettbl", strings.get("dashboard.card.networking"), s?.let { factRowsHtml(it, NET_KEYS, h, strings) }, post = """<p class="note">${esc(strings.get("dashboard.networking.warning_guidance"))}</p>""")}
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

    /** JSON-quote a string value (escapes backslash + double-quote). */
    private fun jsonStr(s: String): String = Json.str(s)

    private fun inspectJson(status: String): String =
        """{"running":${CdpRelay.running},"port":${CdpRelay.PORT},"status":"$status","start_allowed":${!config.hardenedSecurityEnabled}}"""


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
     * Install the canonical direct-config mutation route. The optional capability provider is a narrow
     * JVM-test seam: production always uses the request-scoped management snapshot, while route tests can
     * avoid constructing Android sensor/probe owners without replacing either Ktor routing or this handler.
     */
    internal fun Route.installDirectConfigPostRoute(
        capabilityProvider: (() -> Capabilities)? = null,
    ) {
        post("/config") { directConfigPost().handle(call, capabilityProvider) }
    }

    private fun directConfigPost() = DirectConfigPost(
        config = config,
        revisions = revisions,
        directConfigMutationLock = directConfigMutationLock,
        rendererPreparation = rendererPreparation,
        autoSleepHttpApi = autoSleepHttpApi,
        autoBrightnessHttpApi = autoBrightnessHttpApi,
        onboarding = DirectConfigOnboarding(
            config,
            { configDiscoverySuggestions() },
            { lastHaDiscovery = it },
            ::effectiveDashboardIsBuiltin,
        ),
        capabilities = { liveCapabilities(snapStaleOk().caps) },
        directMutationValues = ::directMutationValues,
        revisionValues = { revisionValues() },
        authorizeSensitive = ::authorizeSensitive,
        rejectHardenedNetworkAdb = ::rejectHardenedNetworkAdb,
        applySetting = { key, value -> applySetting(key, value) },
        applyRendererEffects = ::applyRendererEffects,
        onEntityTargetChanged = { entityLearning.onTargetConfigurationChanged() },
        setEntityLearningEnabled = { entityLearning.setEnabled(it) },
        requestTameReconcileAfterCommit = ::requestTameReconcileAfterCommit,
        onHaAreaCommitted = { haArea.onHaAreaCommitted() },
        snapInvalidate = ::snapInvalidate,
        onReconfigure = { onReconfigure(it) },
        prepareSelfUpdateChannel = { channel, force -> prepareSelfUpdateChannel(channel, force) },
        onSelfUpdateChannelCommitted = { prepared, ticket, before, after ->
            onSelfUpdateChannelCommitted(prepared, ticket, before, after)
        },
        configJson = { status, applied, pending, rejected, message ->
            configJson(status, applied, pending, rejected, message)
        },
    )

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


    private fun configValues() = ConfigValueProjection(
        config = config,
        configLiveValues = { configLiveValues() },
        renderedLiveValues = { snapStaleOk().live },
        pendingLiveSettings = { pendingLiveSettings() },
        stalledLiveSettings = { stalledLiveSettings() },
        proximityJson = { sensors.proximityJson() },
        powerSafetyJson = { PowerSafetyPresentation.json(powerSafetyAdvisory(snapStaleOk().privilege)) },
        haAreaCatalogJson = { haArea.haAreaCatalogJson() },
    )

    private fun configSchemaJson(): String = configSchemaJson(catalogueLoader.strings(AppLocale.ENGLISH))

    private fun configSchemaJson(strings: AppStrings): String = configValues().schemaJson(
        strings,
        liveCapabilities(snapStaleOk().caps), // learned eligibility is fail-closed and live
        autoHints(strings), // what blank ("auto") package fields resolve to → field placeholder
        profile.manufacturer,
        profile.model,
    )

    private fun effectiveValue(spec: io.github.maxlyth.hapaneld.config.SettingSpec, live: Map<String, String>): String =
        configValues().effectiveValue(spec, live)

    private fun exposureSpec(key: String) = key.takeIf { it.startsWith("ha_expose_") }
        ?.removePrefix("ha_expose_")
        ?.let(SettingsRegistry::spec)
        ?.takeIf { it.ha != null }

    // ---- config bundles (export / validated import) + on-panel revision history ----------------

    private fun currentValues(): Map<String, String> = configValues().currentValues()

    private fun currentValues(live: Map<String, String>): Map<String, String> =
        configValues().currentValues(live)

    private fun directMutationValues(): Map<String, String> = configValues().directMutationValues()

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
            panelAssistantNative = panelAssistantNative(),
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
    ): Map<String, String> = configValues().revisionValues(values, state)


    internal fun Route.installConfigBundleRoutes() {
        configBundleRoutes {
            ConfigBundleRoutes(
                config = config,
                revisions = revisions,
                values = configValues(),
                transaction = acceptedConfigTransaction(),
                authorizeSensitive = ::authorizeSensitive,
                rejectHardenedNetworkAdb = ::rejectHardenedNetworkAdb,
                planEntityBackup = ::planEntityBackup,
            )
        }
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
    ): ApplyAcceptedResult = acceptedConfigTransaction().applyAccepted(
        accepted, expectedConfig, expectedRevision, expectedHaAuthOwner, expectedHaOAuthEpoch,
        entityState, existingOperationTicket, onDurableRevision, afterCommitBeforeRenderer, afterApply,
    )

    private fun applyRendererEffects(effects: RendererConfigEffects) =
        acceptedConfigTransaction().applyRendererEffects(effects)

    private fun acceptedConfigTransaction() = AcceptedConfigTransaction(
        config = config,
        revisions = revisions,
        rendererPreparation = rendererPreparation,
        system = system,
        currentValues = { currentValues() },
        revisionValues = { revisionValues() },
        applySetting = { key, value -> applySetting(key, value) },
        onEntityTargetChanged = { entityLearning.onTargetConfigurationChanged() },
        setEntityLearningEnabled = { entityLearning.setEnabled(it) },
        effectiveDashboardIsBuiltin = ::effectiveDashboardIsBuiltin,
        requestTameReconcileAfterCommit = ::requestTameReconcileAfterCommit,
        snapInvalidate = ::snapInvalidate,
        onReconfigure = { onReconfigure(it) },
        prepareSelfUpdateChannel = { channel, force -> prepareSelfUpdateChannel(channel, force) },
        onSelfUpdateChannelCommitted = { prepared, ticket, before, after ->
            onSelfUpdateChannelCommitted(prepared, ticket, before, after)
        },
    )

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
    private fun companionBackupOperations() = CompanionBackupOperations(
        installedCompanionPackage = { CompanionInstaller.installedPkg(appContext) },
        cacheDir = cacheDir,
        ensureCompanionHelper = ::ensureCompanionHelper,
        companionDataOperationState = companionDataOperationState,
        scope = scope,
        config = config,
        system = system,
    )

    private fun buildBackupArtifact(request: CompanionBackupRequest, passphrase: String): PanelBackup.Artifact =
        PanelBackupBuilder(
            appContext = appContext,
            config = config,
            cacheDir = cacheDir,
            configLiveValues = configLiveValues,
            effectiveValue = ::effectiveValue,
            profileAdmin = profileAdmin,
            companion = companionBackupOperations(),
            mqttState = mqttState,
        ).build(request, passphrase)

    private suspend fun handleRestore(call: ApplicationCall) {
        val executor = RestoreExecutor(
            appContext = appContext,
            config = config,
            currentValues = { currentValues() },
            revisionValues = { values, state -> revisionValues(values, state) },
            commitConfig = RestoreConfigCommit { accepted, entityState, expectedRevision,
                existingOperationTicket, onDurableRevision, afterCommitBeforeRenderer, afterApply ->
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
                accepted.size
            },
            rollbackConfig = { before, entityState, expectedRevision, ticket ->
                applyAccepted(
                    before,
                    expectedRevision = expectedRevision,
                    entityState = entityState,
                    existingOperationTicket = ticket,
                ) == ApplyAcceptedResult.Applied
            },
            restoreCompanion = { companionBackupOperations().restore(it) },
            reconcileAfterCompanionRestore = ::reconcileAfterCompanionRestore,
            profileAdmin = profileAdmin,
            onProfileRestart = { onProfileRestart() },
            onProfileRestartAbort = { onProfileRestartAbort(it) },
            onDurableStateRestored = { onDurableStateRestored() },
        )
        RestoreRoutes(
            cacheDir = cacheDir,
            config = config,
            identityMigration = identityMigration,
            reader = BackupArchiveReader(cacheDir) { CompanionInstaller.installedPackages(appContext) },
            profileAdmin = profileAdmin,
            ensureCompanionHelper = ::ensureCompanionHelper,
            authorizeSensitive = ::authorizeSensitive,
            rejectHardenedNetworkAdb = ::rejectHardenedNetworkAdb,
            scope = scope,
            executor = executor,
        ).handle(call)
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



    private fun jarr(items: List<String>): String =
        "[" + items.joinToString(",") { Json.str(it) } + "]"



    /** Full config as JSON for fleet management. The MQTT password is never emitted — only a boolean
     *  saying whether one is set. `http_port` is read-only (changing it needs a restart). */
    private fun configJson(
        mutationStatus: String? = null,
        applied: List<String> = emptyList(),
        pending: List<String> = emptyList(),
        rejected: List<String> = emptyList(),
        message: String? = null,
    ): String = configValues().configJson(mutationStatus, applied, pending, rejected, message)

    private fun performanceWorkloadValues(): Map<String, String> {
        val live = snapStaleOk().live
        return PERFORMANCE_WORKLOAD_KEYS.associateWith { key ->
            effectiveValue(requireNotNull(SettingsRegistry.spec(key)), live)
        }
    }

    companion object {
        private const val TAG = "ha-paneld/http"
        private const val GUARD_DB_ARM_RESPONSE_GRACE_MS = 500L
        private const val HARDENED_APPROVAL_TEXT =
            "Requires physical on-panel approval for this action when Hardened mode is enabled."
        private const val HARDENED_CONDITIONAL_APPROVAL_TEXT =
            "Changing this setting may require physical on-panel approval when Hardened mode is enabled."
        private const val TAME_SHUTDOWN_MS = 5_000L
        private const val REMOTE_CONTROL_SHUTDOWN_MS = 5_000L

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
        internal const val MAX_PLAY_BODY_BYTES = 16L * 1024L
        internal const val MAX_CONFIG_POST_BODY_BYTES = 256L * 1024L
        internal const val MAX_SMALL_FORM_POST_BODY_BYTES = 16L * 1024L
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

        /** Configuration is tens of kilobytes on real panels; this is headroom, not a target. */
        internal const val MAX_STATE_BACKUP_BYTES = 4L * 1024L * 1024L

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
        private const val DEVICE_PROFILES_DOC = "https://panel-assistant.io/go/docs?page=architecture/device-profiles"
    }
}
