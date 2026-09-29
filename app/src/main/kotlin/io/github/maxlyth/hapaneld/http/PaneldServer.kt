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
import io.github.maxlyth.hapaneld.sensors.HaPanelAreaPrerequisitePhase
import io.github.maxlyth.hapaneld.BuildConfig
import io.github.maxlyth.hapaneld.DashboardEntityBackupState
import io.github.maxlyth.hapaneld.DiscoveryResult
import io.github.maxlyth.hapaneld.HaAuthOwner
import io.github.maxlyth.hapaneld.HaDiscovery
import io.github.maxlyth.hapaneld.LiveSettingRequestOutcome
import io.github.maxlyth.hapaneld.PanelStatus
import io.github.maxlyth.hapaneld.panelAssistantDiscoveryId
import io.github.maxlyth.hapaneld.RendererResolver
import io.github.maxlyth.hapaneld.haSignInPending
import io.github.maxlyth.hapaneld.normalizeDashboardEntityPath
import io.github.maxlyth.hapaneld.peersJson
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
import io.github.maxlyth.hapaneld.storage.StorageHealthRuntime
import io.github.maxlyth.hapaneld.storage.StorageHealthSnapshot
import io.github.maxlyth.hapaneld.util.AppInstaller
import io.github.maxlyth.hapaneld.util.AndroidInput
import io.github.maxlyth.hapaneld.util.BoundedStreams
import io.github.maxlyth.hapaneld.util.BoundedDns
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



    // Per-INSTALL build token (changes on every (re)install, not just a version bump) so an open info
    // page can auto-reload after the app is updated — even a same-version dev re-spin. /health carries it.
    private fun buildToken(): String =
        runCatching { appContext.packageManager.getPackageInfo(appContext.packageName, 0).lastUpdateTime.toString() }
            .getOrDefault(Config.VERSION)

    private fun displayCell(v: String): String {
        val observation = DisplayGeometryReport.observe(appContext) ?: return esc(v)
        val size = profile.displayGeometry(observation.physicalWidthPx, observation.physicalHeightPx)?.physical
            ?: return esc(v)
        return displayCell(v, size)
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

    private val haOAuth: HaOAuthRuntime = HaOAuthRuntime(
        config, { catalogueLoader }, haOAuthExchange, autoBrightnessHttpApi,
        { accepted, owner, epoch ->
            applyAccepted(accepted, expectedHaAuthOwner = owner, expectedHaOAuthEpoch = epoch)
        },
        { setupState.setupNeedsUser() },
    )
    private val setupState = SetupState(
        config, system, entityLearning, profile, appContext, mqttState, haOAuth::pendingCount,
        { lastHaDiscovery }, { webViewTooOldOnce }, ::panelAssistantNative,
        ::effectiveDashboardIsBuiltin, scope, rendererPreparation,
    )
    private val catalogueLoader by lazy { CatalogueLoader(asset) }
    private val pages get() = PageShell(config, catalogueLoader, { setupState.setupNeedsUser() }, ::buildToken, ::renderConfigConcurrencyHash)
    private val settingRows get() = DashboardSettingRows(config)

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
    private val tameReconciliation = TameReconcileAuthority(config, tame) { stopping }
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
                setupNeedsUser = { setupState.setupNeedsUser() },
                setupRedirectLocation = ::setupRedirectLocation,
            ) {
                handBackHomeRoutes(handBackHomeDependencies(
                    config = config,
                    tameController = { tame },
                    profileKnownPackages = { tameProfileCandidates.mapTo(hashSetOf()) { it.pkg } },
                    setHome = { system.setHomeActivity(it) },
                    ownPackage = { appContext.packageName },
                    authorize = ::authorizeSensitive,
                ))
                controlPlaneRoutes(
                    controlPlaneDependencies(
                        appContext, config, scope, pendingApks, identityMigration,
                        playAudio, onInstallComponent,
                        buildBackupArtifact = ::buildBackupArtifact,
                        authorizeSensitive = ::authorizeSensitive,
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
                    guardDbBootstrapDependencies(appContext, config, scope, pendingApks, guardDbStaging),
                )
                dashboardPageRoute(::requestStrings, ::infoHtml)
                assetRoutes(asset)
                // Tabbed multi-page shell. `/` stays the existing dashboard (now with a tab bar); the
                // other tabs are dedicated pages that consume /api/v1.
                configurePageRoute(::requestStrings, { pages }) { strings -> configureBody(strings, sensors.hasProximity(), configureSetupBanners(strings)) }
                setupPageRoute(::requestStrings, { pages }, ::buildToken)
                profilesPageRoute(::requestStrings, { pages })
                // The experimental remote-control page is withheld from 0.9.2. Keep old bookmarks
                // useful while its tap-injection UX is reviewed for a later release.
                get("/test") { call.respondRedirect("/") }
                installPageRoute(::requestStrings, { pages }, ::installBody)
                fleetPageRoute(::requestStrings, { pages }) { config.httpPort }
                logsPageRoute(::requestStrings, { pages }) { config.httpPort }
                entitiesPageRoute(::requestStrings, { pages }) { config.dashboardEntityLearningEnabled && effectiveDashboardIsBuiltin() }
                // Self-contained REST API explorer (no Swagger-UI CDN bundle) + the OpenAPI spec it
                // renders — the spec also imports into Swagger/Postman for fleet tooling.
                apiPageRoute(::requestStrings, asset) { config.friendlyName }
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
                        config = config,
                        values = ::configValues,
                        requestStrings = ::requestStrings,
                        schemaJson = { strings ->
                            configValues().schemaJson(
                                strings,
                                liveCapabilities(managementObservations.snapStaleOk().caps), // learned eligibility is fail-closed and live
                                autoHints(strings), // what blank ("auto") package fields resolve to → field placeholder
                                profile.manufacturer,
                                profile.model,
                            )
                        },
                        homeDashboards = { entityLearning.homeDashboardCatalog() },
                        discover = { configDiscoverySuggestions() },
                        rememberDiscovery = { lastHaDiscovery = it },
                    )
                    installDirectConfigPostRoute()
                    haAreaRoutes { haArea.areaJson() }
                    configProbeRoutes(config)
                    setupRoutes { setupState }
                    haOAuthRoutes(haOAuth.routes())
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
                        val caps = liveCapabilities(managementObservations.snapStaleOk().caps)
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
                        val caps = liveCapabilities(managementObservations.snapStaleOk().caps)
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
                    managementRoutes(
                        diagnostics = managementObservations::diagStaleOk,
                        status = ::statusJson,
                        onUpdateOwner = onPanelAssistantUpdateOwner,
                        admitActiveRead = ::admitActiveRead,
                        refreshUpdates = {
                            UpdateChecker.check(
                                appContext, config.updateChannel,
                                config.companionUpdateChannel, profile.companionMaxVersion,
                            )
                        },
                        refreshStorage = refreshStorageHealth,
                        cachedStorage = storageHealth,
                    )
                    loggingRoutes(
                        logApp, logSystem, logWebView, webViewConsoleEnabled, logShipStatus,
                        admitActiveRead = { admitActiveRead(it) },
                    )
                    powerSafetyRoutes(
                        config, powerSafety,
                        powerSafetyAdvisory = { powerSafetyAdvisory(managementObservations.snapStaleOk().privilege) },
                        onRepairPowerSafety, freshPowerSafetyRepairCapability,
                        snapInvalidate = ::snapInvalidate,
                        authorizeSensitive = ::authorizeSensitive,
                    )
                    installationRoutes(appContext, config, profile, pendingApks, ::authorizeSensitive)
                    radioRoutes(
                        status = radioStatus,
                        configured = { config.zigbeeRouterConfigured },
                        enabled = { config.zigbeeRouterEnabled },
                        join = onZigbeeJoinRetry,
                    )
                    webViewHealRoute(onInstallComponent, ::authorizeSensitive)
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
                        if (started) managementObservations.companionServerCache.invalidate()
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
                    sensorTraceRoute()
                    screenshotRoutes(screenshots, { interactive.screenshot() }, { admitActiveRead(it) })
                    cameraRoutes(camera, ::admitActiveRead)
                    get("/openapi.json") {
                        call.respondText(asset("openapi.json"), ContentType.Application.Json)
                    }
                    tameRoutes {
                        TameRoutes(
                            config = config,
                            tame = tame,
                            tameProfileCandidates = tameProfileCandidates,
                            requestTameReconcileAfterCommit = tameReconciliation::requestAfterCommit,
                            snapInvalidate = ::snapInvalidate,
                            requestStrings = ::requestStrings,
                            localizedHref = ::localizedHref,
                            authorizeSensitive = ::authorizeSensitive,
                            respondInstallFormError = ::respondInstallFormError,
                        )
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
                        appContext, profile, density, managementObservations.densityCache, recommendedDensity, recommendedFontScale,
                        requestStrings = ::requestStrings,
                        snapInvalidate = ::snapInvalidate,
                        authorizeSensitive = ::authorizeSensitive,
                        escapeHtml = ::esc,
                        localizedHref = ::localizedHref,
                    )
                    debugInspectionRoutes(appContext, config, inspectLock, { stopping }, ::authorizeSensitive)
                }
            }
        }
    }

    /**
     * Populate the dashboard's last-known observation after critical startup work has completed.
     * Binding the HTTP control plane itself remains free of privileged or hardware probes.
     * The caller supplies the existing IO scope so post-critical work can remain intentionally ordered.
     */
    internal fun prewarm() = managementObservations.prewarm { config.setHaBaseUrl(it) }

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



    // ---- tabbed multi-page shell ----

    private fun configureSetupBanners(strings: AppStrings): String {
        val management = managementObservations.snapStaleOk()
        val power = localizedPowerSafetyBanner(
            powerSafetyAdvisory(management.privilege),
            inlineRepair = true,
            strings = strings,
        )
        // Someone landing on the full settings wall mid-commissioning (an old bookmark, the QR from a
        // build that pointed here) should learn the guided path exists — once setup completes this line
        // vanishes with the rest of the wizard surface.
        val resume = if (setupState.setupNeedsUser()) {
            configureResumeBanner(strings)
        } else ""
        // MQTT verification runs asynchronously after the save returns, and the Configure tab is where the
        // user actually is while it happens — but it showed nothing, so a save that was still being checked
        // looked like a save that had done nothing. SetupBanner already derives this state and is already
        // rendered on the dashboard; surfacing it here too costs nothing and keeps one authority.
        val mqtt = management.facts["MQTT"] ?: "disabled"
        SetupBanner.progress(mqtt, config.mqttBroker.isNotBlank(), setupState.dashboardSetupStepPending(), mqttState())?.let { progress ->
            return power + resume + setupProgressBanner(progress, strings)
        }
        if (haSignInNeededForEffectiveDashboard()) {
            return power + resume + configureSignInBanner(strings)
        }
        val noRenderer = healthFindings(healthInputs(), "", emptyList()).any { it.kind == HealthAudit.Kind.NO_RENDERER }
        // Only a panel past setup runs a filtered dashboard, so only this path can carry the strategy note.
        if (!noRenderer) return power + resume + strategySelectorAllowedBanner(strings)
        return power + resume + configureRendererBanner(strings)
    }

    // Issue #133 follow-up. With a strategy dashboard's check allowed, cards for entities outside the
    // subscription never appear and nothing on the panel can list them, so say so where settings are
    // changed and send the reader to the Entities page, which explains what to pin. A failed read of the
    // entity store must not take the Configure page down with it.
    private fun strategySelectorAllowedBanner(strings: AppStrings): String =
        if (!runCatching { entityLearning.strategySelectorAllowed() }.getOrDefault(false)) "" else
            configureStrategyBanner(strings)

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
        val management = managementObservations.snapStaleOk()
        val companion = managementObservations.companionServersStaleOk()
        // Engine-aware WebView age check (a Cromite swap reports the stale OEM package version).
        val h = healthInputs()
        val wv = h.webView
        val root = management.privilege.rootControlReady
        val installer = management.privilege.typedShellControlReady
        val su = management.privilege.directSuReady
        val displaySizing = managementObservations.densityCache.peek() ?: DisplaySizingObservation(
            current = management.densityCur,
            base = management.densityBase,
            fontScale = management.fontScale,
        )
        val companionHelper = managementObservations.companionHelperCache.get()
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
            adHocWarnings(
                config, catalogueLoader, management.privilege.directSuReady, management.densityBase,
                radioStatus, ::dashboardRecoveryState,
                companion, inlineRepair = true, strings = strings,
            )
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

    /** One renderer-aware warning shared by JSON status and the Dashboard/Install banners. */
    private fun dashboardRecoveryState(): PanelStatus.DashboardRecoveryState =
        PanelStatus.dashboardRecoveryState(
            config.dashboardPackage,
            appContext.packageName,
            SystemClock.elapsedRealtime(),
        )

    private fun dashboardRecoveryWarning(): String? = dashboardRecoveryWarning(dashboardRecoveryState())

    private fun statusJson(
        storageSnapshot: StorageHealthSnapshot,
        databaseObservationNonce: String? = null,
    ): String {
        val management = managementObservations.snapStaleOk()
        val powerAdvisory = powerSafetyAdvisory(management.privilege)
        val companion = managementObservations.companionServersStaleOk()
        val radio = radioStatus()
        val storage = HealthAudit.storage(storageSnapshot)
        val h = healthInputs()
        val updates = UpdateChecker.current(appContext)
        val findings = healthFindings(h, h.webView.display, updates)
        val health = StatusHealth(
            updates, findings, ::dashboardRecoveryState,
            mdnsWarningProjection, ::schemaRollbackVersions,
        )
        return managementStatusJson(
            config, management, companion, powerAdvisory, radio, storage, health,
            { rendererAdmission(appContext, config, autoBrightnessHttpApi) }, camera::presentation, databaseObservationNonce,
        )
    }


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

    // ---- dashboard snapshot (probe results) + hydration ---------------------------------------------
    //
    // Rendering `/` used to gather every root/probe value inline — ~8 serialized su round-trips
    // (zigbee status, CPU tier, network-ADB ×2, touch sound, `wm density` ×3, su presence), a 12+s
    // blank page on PX30 panels. The probes now funnel through ONE cached snapshot: `/` renders
    // whatever is last known instantly (placeholders on a cold start). Post-critical prewarm and stale
    // management endpoints all enter the same at-most-once-per-TTL, single-flight cache supplier.


    private val screenshots = ScreenshotCache(appContext.filesDir)

    private val managementObservations = ManagementObservations(
        appContext, density, managementProjection,
        diagnosticReport = { management, termux ->
            managementDiagnosticReport(
                appContext, profile, management, radioStatus(), storageHealth(), powerSafety(),
                rendererAdmission(appContext, config, autoBrightnessHttpApi), camera.presentation(), termux,
            )
        },
        scope = scope,
        isStopping = { stopping },
    )

    private fun snapInvalidate() = managementObservations.snapInvalidate()

    internal fun invalidateCapabilitySnapshot() = managementObservations.invalidateCapabilitySnapshot()

    internal fun invalidateStorageHealthDiagnostics() = managementObservations.invalidateStorageHealthDiagnostics()

    internal fun lastPrivilegeObservation(): PrivilegedRouteObservation? = managementObservations.lastPrivilegeObservation()

    /** Ranged proximity is learned from live samples and can change between cached hardware probes.
     *  Overlay that cheap live fact so stale-while-revalidate can never expose wake UI for one request
     *  after eligibility is lost. Other capabilities retain their bounded cached probe semantics. */
    private fun liveCapabilities(cached: Capabilities): Capabilities =
        cached.copy(
            hasProximity = sensors.hasProximity(),
            hasLearnedProximity = sensors.hasLearnedProximity(),
        )

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
    private fun infoKeys(s: ManagementSnapshot): List<String> =
        s.facts.keys.filter {
            it !in NET_KEYS && it !in PROFILE_FACT_KEYS && it !in CONTEXT_KEYS && it !in BEHAVIOUR_FACT_KEYS
        }

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
    private fun contextValue(key: String, facts: Map<String, String>): String? = when (key) {
        HA_LIFECYCLE_FACT -> HaLifecycleRuntime.statusText() ?: ""
        // Live and always present for the same reasons as the lifecycle row: the verdict
        // changes while the page is open, and the poll fills the cell from the same `/health`
        // observation that drives the banner. One read of the one state owner.
        HA_NETWORK_FACT -> HaNetworkPathRuntime.statusText() ?: ""
        HA_RENDERER_FACT -> rendererAdmission(appContext, config, autoBrightnessHttpApi).statusText()
        // The camera row is live for the same reason, and it is also where a person reads the
        // stream URL off the panel — with the warning that travels beside it, because the place
        // the URL is copied from is the place somebody is about to paste it into a card on this
        // very panel. A panel whose profile declares no camera has nothing to say and no row.
        CAMERA_FACT -> camera.presentation().takeIf { it.state != CameraState.ABSENT }?.summary
        else -> facts[key]
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

    private fun bannersHtml(s: ManagementSnapshot, h: HealthInputs, strings: AppStrings): String {
        val storage = HealthAudit.storage(storageHealth())
        val mqtt = s.facts["MQTT"] ?: "disabled"
        // Pure decision (unit-tested in SetupBannerTest) — note a CONFIGURED broker that's merely
        // mid-(re)connect must not be reported as missing.
        val needs = SetupBanner.needs(mqtt, config.mqttBroker.isNotBlank(), config.mqttUser.isNotBlank(), panelAssistantNative())
        val setup = if (needs.isNotEmpty())
            dashboardSetupNeedsBanner(needs, strings)
        else ""
        // Commissioning progress only while somebody is actually commissioning. `announcing` is transient but
        // recurs on every bridge reconnect — an HA restart, a broker blip, a panel waking — so on a finished
        // panel this banner kept reappearing to narrate a step that was done months ago. Reported twice from
        // deployed panels. The Configure tab keeps it unconditionally: there it is feedback for a save the user just
        // made, which is the reason it was added.
        val mqttProgress = if (!setupState.setupNeedsUser()) "" else {
            SetupBanner.progress(mqtt, config.mqttBroker.isNotBlank(), setupState.dashboardSetupStepPending(), mqttState())?.let {
                setupProgressBanner(it, strings)
            }.orEmpty()
        }
        val haSetup = if (haSignInNeededForEffectiveDashboard()) haSignInBanner(strings) else ""
        val termuxBridge = if (managementObservations.termuxBridgeCache.get() == TermuxBridgeProbe.State.RUNNING) {
            dashboardPanelBridgeBanner(strings)
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
            proximityLearningBanner(title, strings)
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
            adHocWarnings(
                config, catalogueLoader, s.privilege.directSuReady, s.densityBase,
                radioStatus, ::dashboardRecoveryState,
                managementObservations.companionServersForRender(), inlineRepair = false, strings = strings,
            ) +
            findings.joinToString("") { bannerFor(it, strings) } + termuxBridge + proximityLearning + haSetup + mqttProgress + setup
    }

    private fun effectiveDashboardIsBuiltin(): Boolean =
        system.resolveDashboard(config.dashboardPackage) == SystemController.BUILTIN_DASHBOARD

    // Shares haSignInPending with the renderer, so what the browser advertises as the next step and what
    // the panel actually does when it starts cannot drift apart.
    private fun haSignInNeededForEffectiveDashboard(): Boolean =
        effectiveDashboardIsBuiltin() &&
            haSignInPending(config.haUrl, config.haToken, config.haRefreshToken)



    private fun zigbeeWarning(snapshot: ZigbeeHealthSnapshot): String? = zigbeeWarningText(
        snapshot,
        configuredOn = config.zigbeeRouterConfigured && config.zigbeeRouterEnabled,
    )

    private fun liveRowsHtml(strings: AppStrings): String {
        val led = config.lastLed
        val brightness = effectiveBrightness().takeIf { it >= 0 } ?: runCatching {
            android.provider.Settings.System.getInt(appContext.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS)
        }.getOrNull()
        return liveRowsHtml(led, brightness, volume.getPercent(), config.lastNavigate, strings)
    }


    /** Visible "this needs root" banner for a root-gated card/control group — shown (never hidden) so a
     *  no-root user sees the feature and what root would unlock, next to controls rendered disabled. */
    /** Hydration payload for the dashboard: ready-to-inject HTML fragments, rendered by the same
     *  functions as the warm server render so the two paths can't drift. Builds the snapshot (this
     *  is where the probe cost actually lands — once per TTL). */
    private fun infoJson(strings: AppStrings): String {
        val s = managementObservations.snapCache.get()
        // One health snapshot for this render — the banner, facts card and diagnostics rows below all read
        // the same WebView/renderer verdict rather than each re-probing (which could otherwise disagree).
        val h = healthInputs()
        val cards = listOf(
            "livetbl" to liveRowsHtml(strings),
            "behavtbl" to settingRows.behaviourRowsHtml(s.live, strings, autoHints(strings), liveCapabilities(s.caps)),
            "disptbl" to settingRows.displayRowsHtml(
                s.live, DisplaySizingObservation(s.densityCur, s.densityBase, s.fontScale), strings,
                capabilities = { liveCapabilities(s.caps) },
                proximity = { sensors.proximitySummary().takeIf { sensors.hasProximity() } },
            ),
            "updtbl" to settingRows.updatesRowsHtml(s.live, strings) { liveCapabilities(s.caps) },
            "infotbl" to factRowsHtml(s.facts, infoKeys(s), h.webView.tooOld, strings, ::displayCell),
            "nettbl" to factRowsHtml(s.facts, NET_KEYS, h.webView.tooOld, strings, ::displayCell),
            "proftbl" to factRowsHtml(s.facts, profileFactKeys(profile, s.facts), h.webView.tooOld, strings, ::displayCell),
            "contexttbl" to contextRowsHtml(CONTEXT_KEYS, h.webView.reportingQuirk, strings) { key -> contextValue(key, s.facts) },
            "captbl" to capRowsHtml(s.capabilityRows, strings),
        ).joinToString(",") { (k, v) -> "\"$k\":${jsonStr(v)}" }
        return """{"banners":${jsonStr(bannersHtml(s, h, strings))},"shot":${s.privilege.typedShellControlReady},"shotCached":${jsonStr(screenshots.placeholderUrl() ?: "")},"versionCode":${BuildConfig.VERSION_CODE},"package":${jsonStr(BuildConfig.APPLICATION_ID)},"controls":${jsonStr(controlsHtml(s.facts, s.privilege, profile.hasRecents, { system.resolvedLauncher(config.launcherPackage)?.let { it != appContext.packageName } == true }, strings))},"cards":{$cards}}"""
    }

    private fun infoHtml(strings: AppStrings, embed: EmbedMode? = null): String {
        // Stale-while-revalidate: render the last-known snapshot instantly (placeholders if none yet)
        // and let the page hydrate/refresh from /api/v1/info when the snapshot is missing or old.
        val s = managementObservations.snapCache.peek()
        // One health snapshot shared by every warm branch below (banner + facts + diagnostics), captured
        // lazily so a cold shell (s == null, nothing rendered warm) still probes nothing.
        val h: HealthInputs by lazy(LazyThreadSafetyMode.NONE) { healthInputs() }
        val hydrate = s == null || managementObservations.snapCache.ageMs() > ManagementObservations.SNAP_TTL_MS
        val profNote = dashboardProfileNote(profile.profileLinks, strings)
        // A cold shell can safely show the app-private last-successful capture before the capability
        // probes finish. It must not request a new capture until hydration confirms a privileged route.
        val cachedShot = screenshots.placeholderUrl()
        val shotCard = dashboardScreenshotCard(
            s == null, s?.privilege?.typedShellControlReady == true,
            cachedShot, ::screenAspectRatio, strings,
        )
        val cameraCard = dashboardCameraCard(camera.presentation().state != CameraState.ABSENT, strings)
        val rightControls = dashboardHeaderControls(config, strings)
        return pages.pageShell(
            active = "dashboard",
            sectionTitle = null,
            bodyAttrs = """data-ver="${Config.VERSION}" data-build="${buildToken()}" data-cfg="${renderConfigConcurrencyHash()}" data-hydrate="${if (hydrate) "1" else "0"}" data-hardened="${if (config.hardenedSecurityEnabled) "1" else "0"}"""",
            rightControls = rightControls,
            embed = embed,
            extraScripts = """<script src="assets/card-size-memory.js"></script>
<script src="assets/card-column-alignment.js"></script>
<script src="info.js"></script>
""",
            body = dashboardBody(
                config, strings, profNote, shotCard, cameraCard,
                banners = s?.let { bannersHtml(it, h, strings) } ?: "",
                controls = controlsHtml(
                    s?.facts, s?.privilege, profile.hasRecents,
                    distinctLauncher = {
                        system.resolvedLauncher(config.launcherPackage)?.let { it != appContext.packageName } == true
                    },
                    strings,
                ),
                rowHtml = { id ->
                    when (id) {
                        "infotbl" -> s?.let { factRowsHtml(it.facts, infoKeys(it), h.webView.tooOld, strings, ::displayCell) }
                        "nettbl" -> s?.let { factRowsHtml(it.facts, NET_KEYS, h.webView.tooOld, strings, ::displayCell) }
                        "proftbl" -> s?.let { factRowsHtml(it.facts, profileFactKeys(profile, it.facts), h.webView.tooOld, strings, ::displayCell) }
                        "contexttbl" -> s?.let { contextRowsHtml(CONTEXT_KEYS, h.webView.reportingQuirk, strings) { key -> contextValue(key, it.facts) } }
                        "captbl" -> s?.let { capRowsHtml(it.capabilityRows, strings) }
                        "livetbl" -> if (s == null) null else liveRowsHtml(strings)
                        "behavtbl" -> s?.let { settingRows.behaviourRowsHtml(it.live, strings, autoHints(strings), liveCapabilities(it.caps)) }
                        "disptbl" -> s?.let {
                            settingRows.displayRowsHtml(
                                it.live, DisplaySizingObservation(it.densityCur, it.densityBase, it.fontScale), strings,
                                capabilities = { liveCapabilities(it.caps) },
                                proximity = { sensors.proximitySummary().takeIf { sensors.hasProximity() } },
                            )
                        }
                        "updtbl" -> s?.let { settingRows.updatesRowsHtml(it.live, strings) { liveCapabilities(it.caps) } }
                        else -> error("Unknown dashboard table: $id")
                    }
                },
            ),
            strings = strings,
            translationPrefixes = setOf("shell.", "dashboard.", "runtime."),
        )
    }

    /** JSON-quote a string value (escapes backslash + double-quote). */
    private fun jsonStr(s: String): String = Json.str(s)




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
        capabilities = { liveCapabilities(managementObservations.snapStaleOk().caps) },
        directMutationValues = { configValues().directMutationValues() },
        revisionValues = { revisionValues() },
        authorizeSensitive = ::authorizeSensitive,
        rejectHardenedNetworkAdb = ::rejectHardenedNetworkAdb,
        applySetting = { key, value -> applySetting(key, value) },
        applyRendererEffects = ::applyRendererEffects,
        onEntityTargetChanged = { entityLearning.onTargetConfigurationChanged() },
        setEntityLearningEnabled = { entityLearning.setEnabled(it) },
        requestTameReconcileAfterCommit = tameReconciliation::requestAfterCommit,
        onHaAreaCommitted = { haArea.onHaAreaCommitted() },
        snapInvalidate = ::snapInvalidate,
        onReconfigure = { onReconfigure(it) },
        prepareSelfUpdateChannel = { channel, force -> prepareSelfUpdateChannel(channel, force) },
        onSelfUpdateChannelCommitted = { prepared, ticket, before, after ->
            onSelfUpdateChannelCommitted(prepared, ticket, before, after)
        },
        configJson = { status, applied, pending, rejected, message ->
            configValues().configJson(status, applied, pending, rejected, message)
        },
    )


    /** Startup/reconfigure wake-up. Config commits use [TameReconcileAuthority.requestAfterCommit] under the lock. */
    fun requestTameReconcile(): Boolean = config.synchronizedTransaction {
        tameReconciliation.requestAfterCommit()
    }



    private fun configValues() = ConfigValueProjection(
        config = config,
        configLiveValues = { configLiveValues() },
        renderedLiveValues = { managementObservations.snapStaleOk().live },
        pendingLiveSettings = { pendingLiveSettings() },
        stalledLiveSettings = { stalledLiveSettings() },
        proximityJson = { sensors.proximityJson() },
        powerSafetyJson = { PowerSafetyPresentation.json(powerSafetyAdvisory(managementObservations.snapStaleOk().privilege)) },
        haAreaCatalogJson = { haArea.haAreaCatalogJson() },
    )


    private fun effectiveValue(spec: io.github.maxlyth.hapaneld.config.SettingSpec, live: Map<String, String>): String =
        configValues().effectiveValue(spec, live)

    private fun exposureSpec(key: String) = key.takeIf { it.startsWith("ha_expose_") }
        ?.removePrefix("ha_expose_")
        ?.let(SettingsRegistry::spec)
        ?.takeIf { it.ha != null }

    // ---- config bundles (export / validated import) + on-panel revision history ----------------

    private fun currentValues(): Map<String, String> = configValues().currentValues()



    private fun renderConfigConcurrencyHash(): String =
        configValues().concurrencyHash()

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
        requestTameReconcileAfterCommit = tameReconciliation::requestAfterCommit,
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
        ensureCompanionHelper = managementObservations::ensureCompanionHelper,
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
            ensureCompanionHelper = managementObservations::ensureCompanionHelper,
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




    private fun performanceWorkloadValues(): Map<String, String> {
        val live = managementObservations.snapStaleOk().live
        return PERFORMANCE_WORKLOAD_KEYS.associateWith { key ->
            effectiveSettingValue(config, requireNotNull(SettingsRegistry.spec(key)), live)
        }
    }

    companion object {
        private const val TAG = "ha-paneld/http"
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

    }
}
