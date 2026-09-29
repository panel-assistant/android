package io.github.maxlyth.hapaneld.http

import android.content.Context
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
import io.github.maxlyth.hapaneld.panelAssistantDiscoveryId
import io.github.maxlyth.hapaneld.RendererResolver
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
import io.github.maxlyth.hapaneld.camera.CameraSurface
import io.github.maxlyth.hapaneld.control.AmbientThemeReport
import io.github.maxlyth.hapaneld.control.BuiltinDashboard
import io.github.maxlyth.hapaneld.control.CdpRelay
import io.github.maxlyth.hapaneld.control.AdbController
import io.github.maxlyth.hapaneld.control.AdaptiveLuxCurve
import io.github.maxlyth.hapaneld.control.CompanionDataLease
import io.github.maxlyth.hapaneld.control.CompanionDataOperationGate
import io.github.maxlyth.hapaneld.control.CompanionDataOperationState
import io.github.maxlyth.hapaneld.control.DensityController
import io.github.maxlyth.hapaneld.control.InteractiveController
import io.github.maxlyth.hapaneld.control.PrivilegeRoute
import io.github.maxlyth.hapaneld.control.RemoteDebugSecurityTransitionGate
import io.github.maxlyth.hapaneld.control.RemoteDebugAuthorityResult
import io.github.maxlyth.hapaneld.control.PrivilegedRouteObservation
import io.github.maxlyth.hapaneld.control.PowerRepairCapability
import io.github.maxlyth.hapaneld.control.PowerSafetyAdvisoryAction
import io.github.maxlyth.hapaneld.control.PowerSafetyAssessment
import io.github.maxlyth.hapaneld.control.PowerSafetyMutationPolicy
import io.github.maxlyth.hapaneld.control.PowerSafetyRepairResult
import io.github.maxlyth.hapaneld.control.Su
import io.github.maxlyth.hapaneld.control.SystemController
import io.github.maxlyth.hapaneld.KioskAdminUi
import io.github.maxlyth.hapaneld.control.HandBackHomeController
import io.github.maxlyth.hapaneld.control.HandBackHomePolicy
import io.github.maxlyth.hapaneld.control.TameController
import io.github.maxlyth.hapaneld.control.TameReconcileResult
import io.github.maxlyth.hapaneld.control.VolumeController
import io.github.maxlyth.hapaneld.control.ZigbeeHealthSnapshot
import io.github.maxlyth.hapaneld.control.ZigbeeHealthState
import io.github.maxlyth.hapaneld.control.zigbeeHealthPresentation
import io.github.maxlyth.hapaneld.dashboard.readThenClose
import io.github.maxlyth.hapaneld.dashboard.EntityFilterProtocol
import io.github.maxlyth.hapaneld.dashboard.EntityLearningManager
import io.github.maxlyth.hapaneld.device.DeviceProfile
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
    // The wake words this panel holds, bundled and imported, for the Configure wake-word picker; null
    // answers 503. [onWakeWordsChanged] rearms the listener and tells Home Assistant after an import.
    private val wakeWords: io.github.maxlyth.hapaneld.assist.wakeword.WakeWordCatalog? = null,
    private val onWakeWordsChanged: () -> Unit = {},
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
            acceptedConfigTransaction().applyAccepted(accepted, expectedHaAuthOwner = owner, expectedHaOAuthEpoch = epoch)
        },
        { setupState.setupNeedsUser() },
    )
    private val setupState = SetupState(
        config, system, entityLearning, profile, appContext, mqttState, haOAuth::pendingCount,
        { lastHaDiscovery }, { pageHealth.webViewTooOldOnce }, ::panelAssistantNative,
        ::effectiveDashboardIsBuiltin, scope, rendererPreparation,
    )
    private val catalogueLoader by lazy { CatalogueLoader(asset) }
    private val pageHealth = PageHealth(appContext, config)
    private val pages get() = PageShell(config, catalogueLoader, { setupState.setupNeedsUser() }, ::buildToken, ::renderConfigConcurrencyHash)

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
                dashboardPageRoute(::requestStrings) { strings, embed -> dashboardPageHandler().html(strings, embed) }
                assetRoutes(asset)
                // Tabbed multi-page shell. `/` stays the existing dashboard (now with a tab bar); the
                // other tabs are dedicated pages that consume /api/v1.
                configurePageRoute(::requestStrings, { pages }) { strings -> configurePageHandler().body(strings) }
                setupPageRoute(::requestStrings, { pages }, ::buildToken)
                profilesPageRoute(::requestStrings, { pages })
                // The experimental remote-control page is withheld from 0.9.2. Keep old bookmarks
                // useful while its tap-injection UX is reviewed for a later release.
                get("/test") { call.respondRedirect("/") }
                installPageRoute(::requestStrings, { pages }) { strings -> installPageHandler().body(strings) }
                fleetPageRoute(::requestStrings, { pages }) { config.httpPort }
                logsPageRoute(::requestStrings, { pages }) { config.httpPort }
                entitiesPageRoute(::requestStrings, { pages }) { config.dashboardEntityLearningEnabled && effectiveDashboardIsBuiltin() }
                // Self-contained REST API explorer (no Swagger-UI CDN bundle) + the OpenAPI spec it
                // renders — the spec also imports into Swagger/Postman for fleet tooling.
                apiPageRoute(::requestStrings, asset) { config.friendlyName }
                healthRoute(config, appContext.packageName, ::buildToken, ::renderConfigConcurrencyHash) { panelAssistantRestartHealth() }
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
                    healthRoute(config, appContext.packageName, ::buildToken, ::renderConfigConcurrencyHash) { panelAssistantRestartHealth() }
                    migrationNoticeRoute(config)
                    configReadRoutes(
                        config = config,
                        values = ::configValues,
                        requestStrings = ::requestStrings,
                        schemaJson = { strings ->
                            configValues().schemaJson(
                                strings,
                                liveCapabilities(managementObservations.snapStaleOk().caps), // learned eligibility is fail-closed and live
                                dashboardAutoHints(system, strings), // what blank ("auto") package fields resolve to → field placeholder
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
                    voiceRoutes(
                        hasMicrophone = { liveCapabilities(managementObservations.snapStaleOk().caps).hasMicrophone },
                        voiceEnabled = { config.voiceEnabled },
                        assistPipelines = assistPipelines,
                        voiceTest = voiceTest,
                        wakeWords = wakeWords,
                        onWakeWordsChanged = onWakeWordsChanged,
                    )
                    // LAN ha-paneld panels for the header panel switcher — a cheap, non-blocking snapshot of
                    // the live mDNS roster (a background listener keeps it converged + fresh; see browsePeers).
                    discoveryRoutes({ peersJson(peers()) }, { launchableAppsJson(appContext) })
                    // Hydration payload for the dashboard (see DashboardPageHandler.json) — the one place the probe
                    // suite actually runs; cached + single-flight, so concurrent viewers share it.
                    get("/info") {
                        val strings = requestStrings(call)
                        call.response.headers.append(HttpHeaders.Vary, HttpHeaders.AcceptLanguage)
                        call.response.headers.append(
                            HttpHeaders.ContentLanguage,
                            strings.languages(setOf("dashboard.")).joinToString(", "),
                        )
                        call.respondText(withContext(Dispatchers.IO) { dashboardPageHandler().json(strings) }, ContentType.Application.Json)
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
                        powerSafetyAdvisory = { pageHealth.powerSafetyAdvisory(managementObservations.snapStaleOk().privilege, { profile.appCanSu }, powerSafety) },
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
                    rendererMaintenanceRoutes(
                        appContext, config, system, scope, clearStorageGate, { stopping },
                        onRepairCompanionUrl,
                        invalidateCompanionObservation = { managementObservations.companionServerCache.invalidate() },
                        authorizeSensitive = ::authorizeSensitive,
                    )
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


    /** Panel Assistant granted native authority, so this panel reaches Home Assistant without MQTT. */
    private fun panelAssistantNative(): Boolean =
        config.panelAssistantAuthority == PanelAssistantTransportProtocol.AUTHORITY_NATIVE


    private fun statusJson(
        storageSnapshot: StorageHealthSnapshot,
        databaseObservationNonce: String? = null,
        homeProofRequested: Boolean = false,
    ): String {
        val management = managementObservations.snapStaleOk()
        val powerAdvisory = pageHealth.powerSafetyAdvisory(management.privilege, { profile.appCanSu }, powerSafety)
        val companion = managementObservations.companionServersStaleOk()
        val radio = radioStatus()
        val storage = HealthAudit.storage(storageSnapshot)
        val h = pageHealth.healthInputs()
        val updates = UpdateChecker.current(appContext)
        val findings = pageHealth.healthFindings(h, h.webView.display, updates)
        val health = StatusHealth(
            updates, findings, pageHealth::dashboardRecoveryState,
            mdnsWarningProjection, pageHealth::schemaRollbackVersions,
        )
        return managementStatusJson(
            config, management, companion, powerAdvisory, radio, storage, health,
            { rendererAdmission(appContext, config, autoBrightnessHttpApi) }, camera::presentation, databaseObservationNonce,
            homeProof = if (homeProofRequested) ({
                val proof = system.homeUiProof(config.dashboardPackage, KioskAdminUi.isVisible())
                homeUiProofJson(proof.state, proof.reason, proof.evidence)
            }) else null,
        )
    }


    private val screenshots = ScreenshotCache(appContext.filesDir)

    // ---- dashboard snapshot (probe results) + hydration ---------------------------------------------
    //
    // Rendering `/` used to gather every root/probe value inline — ~8 serialized su round-trips
    // (zigbee status, CPU tier, network-ADB ×2, touch sound, `wm density` ×3, su presence), a 12+s
    // blank page on PX30 panels. The probes now funnel through ONE cached snapshot: `/` renders
    // whatever is last known instantly (placeholders on a cold start). Post-critical prewarm and stale
    // management endpoints all enter the same at-most-once-per-TTL, single-flight cache supplier.


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

    private fun dashboardRows() = DashboardRows(
        config, appContext, profile, system, sensors, camera, autoBrightnessHttpApi,
        effectiveBrightness = { effectiveBrightness() },
        volumePercent = { volume.getPercent() },
        liveCapabilities = ::liveCapabilities,
    )

    private fun dashboardAdvisories() = DashboardAdvisories(
        config, appContext, sensors, managementObservations, setupState, pageHealth,
        mqttState,
        panelAssistantNative = ::panelAssistantNative,
        haSignInNeededForEffectiveDashboard = setupState::haSignInNeededForEffectiveDashboard,
        powerSafetyAdvisory = { privilege ->
            pageHealth.powerSafetyAdvisory(privilege, { profile.appCanSu }, powerSafety)
        },
        storageHealth = { storageHealth() },
        catalogueLoader = { catalogueLoader },
        radioStatus = radioStatus,
        dashboardRecoveryState = pageHealth::dashboardRecoveryState,
    )

    private fun dashboardPageHandler() = DashboardPageHandler(
        config, appContext, profile, camera, managementObservations, pageHealth, screenshots,
        dashboardRows(), dashboardAdvisories(), { pages }, ::buildToken, ::renderConfigConcurrencyHash,
    )

    private fun configurePageHandler() = ConfigurePageHandler(
        config, sensors, managementObservations, setupState,
        strategySelectorAllowed = { entityLearning.strategySelectorAllowed() },
        pageHealth = pageHealth,
        mqttState = mqttState,
        powerSafetyAdvisory = { privilege ->
            pageHealth.powerSafetyAdvisory(privilege, { profile.appCanSu }, powerSafety)
        },
        haSignInNeededForEffectiveDashboard = setupState::haSignInNeededForEffectiveDashboard,
    )

    private fun installPageHandler() = InstallPageHandler(
        config, appContext, profile, managementObservations, pageHealth,
        tame, tameProfileCandidates, recommendedDensity, recommendedFontScale,
        catalogueLoader = { catalogueLoader },
        radioStatus = radioStatus,
        dashboardRecoveryState = pageHealth::dashboardRecoveryState,
        powerSafetyAdvisory = { privilege ->
            pageHealth.powerSafetyAdvisory(privilege, { profile.appCanSu }, powerSafety)
        },
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


    private fun effectiveDashboardIsBuiltin(): Boolean =
        system.resolveDashboard(config.dashboardPackage) == SystemController.BUILTIN_DASHBOARD

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

    private fun directConfigPost() = acceptedConfigTransaction().directPost(
        directConfigMutationLock = directConfigMutationLock,
        autoSleepHttpApi = autoSleepHttpApi,
        autoBrightnessHttpApi = autoBrightnessHttpApi,
        onboarding = DirectConfigOnboarding(
            config,
            { configDiscoverySuggestions() },
            { lastHaDiscovery = it },
            ::effectiveDashboardIsBuiltin,
        ),
        capabilities = { liveCapabilities(managementObservations.snapStaleOk().caps) },
        authorizeSensitive = ::authorizeSensitive,
        rejectHardenedNetworkAdb = ::rejectHardenedNetworkAdb,
        onHaAreaCommitted = { haArea.onHaAreaCommitted() },
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
        powerSafetyJson = { PowerSafetyPresentation.json(pageHealth.powerSafetyAdvisory(managementObservations.snapStaleOk().privilege, { profile.appCanSu }, powerSafety)) },
        haAreaCatalogJson = { haArea.haAreaCatalogJson() },
    )



    private fun exposureSpec(key: String) = key.takeIf { it.startsWith("ha_expose_") }
        ?.removePrefix("ha_expose_")
        ?.let(SettingsRegistry::spec)
        ?.takeIf { it.ha != null }

    // ---- config bundles (export / validated import) + on-panel revision history ----------------




    private fun renderConfigConcurrencyHash(): String =
        configValues().concurrencyHash()



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


    private fun acceptedConfigTransaction() = AcceptedConfigTransaction(
        config = config,
        revisions = revisions,
        rendererPreparation = rendererPreparation,
        system = system,
        values = configValues(),
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
            effectiveValue = { spec, live -> configValues().effectiveValue(spec, live) },
            profileAdmin = profileAdmin,
            companion = companionBackupOperations(),
            mqttState = mqttState,
            wakeWords = wakeWords,
        ).build(request, passphrase)

    private suspend fun handleRestore(call: ApplicationCall) {
        val executor = RestoreExecutor(
            appContext = appContext,
            config = config,
            currentValues = { configValues().currentValues() },
            revisionValues = { values, state -> configValues().revisionValues(values, state) },
            commitConfig = RestoreConfigCommit { accepted, entityState, expectedRevision,
                existingOperationTicket, onDurableRevision, afterCommitBeforeRenderer, afterApply ->
                val result = acceptedConfigTransaction().applyAccepted(
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
                acceptedConfigTransaction().applyAccepted(
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
            onWakeWordsChanged = { onWakeWordsChanged() },
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
            wakeWords = wakeWords,
            verifiedRetiredReceipt = {
                io.github.maxlyth.hapaneld.migration.MigrationState.of(appContext).verifiedRetiredReceipt(it)
            },
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
