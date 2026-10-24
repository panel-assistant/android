package io.panelassistant.android.http

import io.panelassistant.android.panelassistant.PanelAssistantBlobs
import io.panelassistant.android.panelassistant.PanelAssistantManageReply
import io.panelassistant.android.sensors.HaApiSessionProvider
import org.json.JSONObject
import android.content.Context
import android.util.Log
import io.panelassistant.android.Config
import io.panelassistant.android.BuildConfig
import io.panelassistant.android.DiscoveryResult
import io.panelassistant.android.LiveSettingRequestOutcome
import io.panelassistant.android.dashboardEntityLearningEnabled
import io.panelassistant.android.peersJson
import io.panelassistant.android.config.Capabilities
import io.panelassistant.android.backup.PanelBackup
import io.panelassistant.android.config.SettingValue
import io.panelassistant.android.config.SettingsRegistry
import io.panelassistant.android.i18n.AppLocale
import io.panelassistant.android.i18n.CatalogueLoader
import io.panelassistant.android.i18n.Strings as AppStrings
import io.panelassistant.android.camera.AbsentCameraSurface
import io.panelassistant.android.camera.CameraSurface
import io.panelassistant.android.platform.PanelPermissionRepair
import io.panelassistant.android.control.CdpRelay
import io.panelassistant.android.control.AdbController
import io.panelassistant.android.control.CompanionDataOperationState
import io.panelassistant.android.control.DensityController
import io.panelassistant.android.control.FrameworkSizing
import io.panelassistant.android.control.InteractiveController
import io.panelassistant.android.control.PrivilegedRouteObservation
import io.panelassistant.android.control.PowerRepairCapability
import io.panelassistant.android.control.PowerSafetyAssessment
import io.panelassistant.android.control.PowerSafetyRepairResult
import io.panelassistant.android.control.Su
import io.panelassistant.android.control.SystemController
import io.panelassistant.android.KioskAdminUi
import io.panelassistant.android.control.TameController
import io.panelassistant.android.control.VolumeController
import io.panelassistant.android.control.ZigbeeHealthSnapshot
import io.panelassistant.android.dashboard.EntityLearningManager
import io.panelassistant.android.device.DeviceProfile
import io.panelassistant.android.device.TameCandidate
import io.panelassistant.android.device.profile.PassiveProfileDraft
import io.panelassistant.android.device.profile.PassiveProfileReport
import io.panelassistant.android.device.profile.ProfileAdmin
import io.panelassistant.android.logship.LogCapture
import io.panelassistant.android.persistence.AppState
import io.panelassistant.android.security.LocalApprovalBroker
import io.panelassistant.android.security.SensitiveOperation
import io.panelassistant.android.sensors.SensorReporter
import io.panelassistant.android.setHaBaseUrl
import io.panelassistant.android.storage.StorageHealthRuntime
import io.panelassistant.android.storage.StorageHealthSnapshot
import io.panelassistant.android.util.CompanionInstaller
import io.panelassistant.android.util.guardDbAppStaging
import io.panelassistant.android.util.HaLink
import io.panelassistant.android.util.InstallPresentation
import io.panelassistant.android.util.Json
import io.panelassistant.android.util.GenerationSingleFlight
import io.panelassistant.android.util.RendererPreparationCoordinator
import io.panelassistant.android.util.UpdateChecker
import io.panelassistant.android.panelassistant.PanelAssistantTransportProtocol
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.Route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.SecureRandom



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
    // Called after this server has written new settings to [config]; the service rebuilds the network runtime.
    private val onReconfigure: (Set<String>) -> Unit,
    // Applies a single behaviour setting through the command dispatcher (persist → drive hardware →
    // report HA state). Lets the config API set those keys identically to an HA command while preserving whether durable desired state is still waiting for actuation.
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
    private val systemLogAvailable: () -> Boolean = ::systemLogReadable,
    // EFFECTIVE backlight (sysfs actual_brightness via BrightnessController, cached) — the sensors
    // endpoint + Live-state row report what the hardware is doing, not just the Android setting.
    private val effectiveBrightness: () -> Int = { -1 },
    // Repair a Companion server row with an empty internal_url (the HA 2026.7 "Missing Host header"
    // incident). False means the shared destructive-operation lane is busy.
    private val onRepairCompanionUrl: (() -> io.panelassistant.android.util.InstallOperationResult) -> Boolean = { false },
    // Install/update a managed component from the Install tab. name ∈ {paneld, companion};
    // action ∈ {update, reinstall}; version = a specific release tag to install (blank = channel newest).
    // Runs off-thread; progress is reported via InstallProgress. Injected by the service.
    private val onInstallComponent: (String, String, String) -> Boolean = { _, _, _ -> false },
    // A Panel Assistant entry declared on its status poll that it owns the ha-paneld update entity.
    // One read-only Android power assessment shared by every user and diagnostic surface.
    private val powerSafety: () -> PowerSafetyAssessment,
    // Uncached harmless direct-root capability probe. Explicit acknowledgement and repair paths only;
    // passive rendering derives capability from the bounded management snapshot instead.
    private val freshPowerSafetyRepairCapability: () -> PowerRepairCapability = { PowerRepairCapability.DEGRADED },
    // Explicit repair only. Per-step readback decides whether the result is complete.
    private val onRepairPowerSafety: () -> PowerSafetyRepairResult,
    // Bounded EFR32 health snapshot, or null when this panel has no radio gateway.
    private val radioStatus: () -> ZigbeeHealthSnapshot? = { null },
    // Reassert Repeater mode through the service-owned serialized Zigbee actuator. Admitted only when
    // the persisted router switch is explicitly ON; false means the runtime lane is unavailable.
    private val onZigbeeJoinRetry: () -> Boolean = { false },
    // LAN ha-paneld peers discovered over mDNS — powers the header panel switcher. Injected by the service
    // (captures the live MdnsAdvertiser field). Blocking browse; called only through [peersCache] off-thread.
    private val peers: () -> List<io.panelassistant.android.Peer> = { emptyList() },
    // mDNS can fail independently of HTTP, leaving the panel absent from peer switchers.
    // Legacy text and typed metadata come from one observation so a liveness transition cannot pair
    // one warning with another warning's presentation envelope.
    private val mdnsWarningProjection: () -> Pair<String?, InstallPresentation?> = { null to null },
    // Proposed value for a blank HA URL field. Discovery is blocking, so the route invokes this on IO;
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
    /** The saved proximity calibrations a backup carries and a restore writes back. */
    private val proximityCalibrations: io.panelassistant.android.persistence.ProximityCalibrationCustody? = null,
    private val dashboardIssueCustody: io.panelassistant.android.persistence.DashboardIssueCustody? = null,
    private val profileRestartAllowed: () -> Boolean = { true },
    private val onProfileRestartAbort: (String) -> Boolean = { false },
    // Cheap process-local storage/database-health snapshot. The runtime starts at UNCHECKED, so
    // staged callers and tests that omit this provider retain an explicit, truthful initial state.
    private val storageHealth: () -> StorageHealthSnapshot = { StorageHealthRuntime.snapshot() },
    // Fresh observation uses the service's single serialized SQLite observation owner. Null means the
    // same-request probe stopped or was incomplete; refresh=1 then renders UNCHECKED, never stale health.
    private val refreshStorageHealth: suspend () -> StorageHealthSnapshot? = { null },
    // Camera trial (slice 3): one session owner shared by the snapshot route, /api/v1/status and
    // /api/v1/diag. A stable per-service instance rather than a lambda provider — unlike radioStatus
    // it is never reassigned on reconfigure, so no reconfigure-following indirection is
    // needed here. AbsentCameraSurface is the default so a board with no camera owner still compiles
    // and answers `absent` truthfully; the service wires the real session owner once profile.hasCamera.
    private val camera: CameraSurface = AbsentCameraSurface,
    private val permissionStatus: () -> Map<PanelPermissionRepair.Grant, PanelPermissionRepair.State>,
    // Home Assistant Assist pipeline catalogue for the Configure voice_pipelines picker.
    private val assistPipelines: io.panelassistant.android.assist.AssistPipelineDirectory,
    // One-shot voice-assistant test trigger for POST /api/v1/voice/test.
    private val voiceTest: io.panelassistant.android.assist.VoiceTestTrigger,
    // The microphone and its capture check as the voice coordinator holds them; null reports the
    // capability alone, with no check run.
    private val voiceMicrophone: () -> io.panelassistant.android.audio.MicrophoneStatus? = { null },
    // The wake words this panel holds, bundled and imported, for the Configure wake-word picker; null
    // answers 503. [onWakeWordsChanged] rearms the listener and tells Home Assistant after an import.
    private val wakeWords: io.panelassistant.android.assist.wakeword.WakeWordCatalog? = null,
    private val onWakeWordsChanged: () -> Unit = {},
    // The native transport, for its restart notice on the health line and its phase and refusal on status.
    private val panelAssistantTransport: io.panelassistant.android.panelassistant.PanelAssistantTransportOwner? = null,
    /** Read a bundled static asset (info.js / info.css) as text. */
    // Platform asset access; JVM HTTP hosts read the same bundled files from disk.
    private val asset: (String) -> String = { name ->
        appContext.assets.open(name).bufferedReader().use { it.readText() }
    },
    /** The named operations the routes below register as they mount (protocol section 20.3). */
    internal val manageTable: io.panelassistant.android.ManageTable = io.panelassistant.android.ManageTable(),
    /** The current runtime generation's dispatcher, which the stub's pages and `/ui` door enter (section 20.7). */
    private val door: () -> io.panelassistant.android.ManageDoor? = { null },
    /** The bundle Panel Assistant keeps for the sidebar (protocol section 20.5): the APK's assets. */
    private val bundleAssets: UiBundleAssets = UiBundleAssets.ofApk { appContext.applicationInfo.sourceDir },
) {
    /** The dispatcher the sidebar relay enters, as the stub's door does (protocol section 20.2). */
    internal fun manageDoor(): io.panelassistant.android.ManageDoor? = door()

    /** The stub's 410 answers, printed in `/diag` (protocol section 20.7). */
    private val goneCounts = GoneCounts()
    private val stubBlobs = StubBlobs(java.io.File(cacheDir, "ui-blobs"))

    /** The diag text, with the stub's 410 counts; `/diag`, `/api/v1/diag` and the `diag` read all answer it. */
    private fun diagText(): String = managementObservations.diagStaleOk().trimEnd('\n') + "\n" + goneCounts.text()

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

    /** [authorizeSensitive] for a route that serves both doors; see [authorizeSensitiveRequest]. */
    private suspend fun authorizeSensitive(
        call: OpCall,
        operation: SensitiveOperation,
        payload: String,
        summary: String,
    ): Boolean = authorizeSensitiveRequest(
        call = call,
        hardened = config.hardenedSecurityEnabled,
        operation = operation,
        payload = payload,
        summary = summary,
        broker = LocalApprovalBroker.instance,
    )

    private fun requestStrings(call: OpCall): AppStrings = resolvedRequestStrings(
        call = call,
        persistedLanguage = config.uiLanguage,
        deviceLanguageTag = java.util.Locale.getDefault().toLanguageTag(),
        allowPseudo = BuildConfig.DEBUG,
        catalogueLoader = catalogueLoader,
    )

    private suspend fun rejectHardenedNetworkAdb(call: ApplicationCall, requested: String?): Boolean =
        rejectHardenedNetworkAdb(call.op(), requested)

    private suspend fun rejectHardenedNetworkAdb(call: OpCall, requested: String?): Boolean {
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

    // Display sizing (density + text scale): su or the helper, else the app's own settings grants.
    private val density = DensityController(canSu = profile.appCanSu, app = FrameworkSizing(appContext))
    private val interactive = InteractiveController(canSu = profile.appCanSu)
    // On-panel config revision history (ring buffer) — written on every successful apply.
    private val revisions = RevisionStore(appContext.filesDir)
    private val performanceBindingSecret: String by lazy {
        val prefs = AppState.preferences(
            appContext,
            "performance-binding",
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
     * paths that already do it (the suggestion route) and leaves its result
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
        config, system, entityLearning, profile, appContext, haOAuth::pendingCount,
        { lastHaDiscovery }, { pageHealth.webViewTooOldOnce },
        ::effectiveDashboardIsBuiltin, scope, rendererPreparation,
    )
    private val catalogueLoader by lazy { CatalogueLoader(asset) }
    private val pageHealth = PageHealth(appContext, config)
    private val pages get() = PageShell(config, catalogueLoader, { setupState.setupNeedsUser() }, ::buildToken, asset)

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
    private var listener: PaneldListener? = null
    // Hardened mode with the routes closed takes the listener off the LAN: a change of either rebinds it.
    private val securityModeChanged = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if ((key == Config.SECURITY_MODE_PREF || key == Config.UI_ROUTES_CLOSED_PREF) && !stopping) scope.launch(Dispatchers.IO) {
            runCatching { listener?.rebind() }.onFailure { Log.e(TAG, "HTTP rebind for the security mode failed", it) }
        }
    }
    private val inspectLock = Any()
    private val directConfigMutationLock = Any()
    @Volatile private var stopping = true
    private val haArea = HaAreaRuntime(config, scope, directConfigMutationLock) { stopping }
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

    /** The control-route dependencies, the same for the HTTP routes and the Panel Assistant session. */
    private fun controlPlane() = controlPlaneDependencies(
        appContext, config, scope, pendingApks,
        playAudio, onInstallComponent,
        buildBackupArtifact = ::buildBackupArtifact,
        authorizeSensitive = ::authorizeSensitive,
    ).copy(
        wakeWords = { wakeWords.takeIf { liveCapabilities(managementObservations.snapStaleOk().caps).microphone.offered } },
        wakeWordsChanged = onWakeWordsChanged,
    )
    private val guardDbStaging = guardDbAppStaging(appContext)

    fun start() {
        stopping = false
        pendingApks.open()
        val listener = PaneldListener(
            scope, { config.httpPort }, { listenerOffLan(config) },
            onBindFailure = { Log.e(TAG, "HTTP bind on :${config.httpPort} failed", it) },
        ) { mount(this) }
        // Treat the bind as required startup, not a best-effort sidecar. A failure must close this
        // generation's admission and reach the service runtime owner, which records FAILED and prevents
        // a staged profile from being marked healthy without its management/control surface.
        try {
            listener.start(closeIngress = pendingApks::close)
            this.listener = listener
            config.registerChangeListener(securityModeChanged)
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
                uiRoutesClosed = { config.uiRoutesClosed },
                goneCounts = goneCounts,
            ) {
                serveOperations(manageTable)
                stubRoutes(
                    door = door,
                    blobs = stubBlobs,
                    health = { healthLine(config, appContext.packageName, ::buildToken, ::renderConfigConcurrencyHash) { panelAssistantTransport?.restartHealthToken().orEmpty() } },
                    diag = { withContext(Dispatchers.IO) { diagText() } },
                    hardened = { config.hardenedSecurityEnabled },
                )
                handBackHomeRoutes(handBackHomeDependencies(
                    config = config,
                    tameController = { tame },
                    profileKnownPackages = { tameProfileCandidates.mapTo(hashSetOf()) { it.pkg } },
                    setHome = { system.setHomeActivity(it) },
                    ownPackage = { appContext.packageName },
                    authorize = ::authorizeSensitive,
                ))
                controlPlaneRoutes(controlPlane())
                guardDbBootstrapRoutes(
                    guardDbBootstrapDependencies(appContext, config, scope, pendingApks, guardDbStaging),
                )
                dashboardPageRoute(::requestStrings) { strings, embed, peer -> dashboardPageHandler().html(strings, embed, peer) }
                assetRoutes(asset)
                // Tabbed multi-page shell. `/` stays the existing dashboard (now with a tab bar); the
                // other tabs are dedicated pages that consume /api/v1.
                configurePageRoute(::requestStrings, { pages }) { strings -> configurePageHandler().body(strings) }
                setupPageRoute(::requestStrings, { pages }, ::buildToken)
                profilesPageRoute(::requestStrings, { pages })
                installPageRoute(::requestStrings, { pages }) { strings -> installPageHandler().body(strings) }
                logsPageRoute(::requestStrings, { pages }) { config.httpPort }
                entitiesPageRoute(::requestStrings, { pages }) { config.dashboardEntityLearningEnabled && effectiveDashboardIsBuiltin() }
                // Self-contained REST API explorer (no Swagger-UI CDN bundle) + the OpenAPI spec it
                // renders — the spec also imports into Swagger/Postman for fleet tooling.
                apiPageRoute(::requestStrings, asset) { config.friendlyName }

                // ---- /api/v1 — the REST API, served while the routes are open (protocol section 20.8).
                // /play stays real at the root too for curl from Home Assistant automations. The stub above
                // serves the pages, both spellings of /health and /diag, /ui and /blob. ----
                route("/api/v1") {
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
                    healthRoute(config, appContext.packageName, ::buildToken, ::renderConfigConcurrencyHash) { panelAssistantTransport?.restartHealthToken().orEmpty() }
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
                    setupRoutes { setupState }
                    haOAuthRoutes(haOAuth.routes())
                    installConfigBundleRoutes()
                    // Restore a .hpb bundle (raw body; passphrase in the X-Backup-Passphrase header so it
                    // never lands in a query log). ?dry_run=1 decrypts + reports the contents WITHOUT writing.
                    // A real restore is DESTRUCTIVE (rewrites config; force-stops + rewrites the Companion DB).
                    ops { post("/restore") { handleRestore(call) } }
                    performanceRoutes(
                        admit = { admitActiveRead(it) },
                        perf = { PerfReader.touch(); PerfReader.json() },
                        binding = { id -> performanceBindingJson(id, performanceBindingSecret, config.panelId, performanceWorkloadValues()) },
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
                    sensorValuesRoute(appContext, sensors, { volume.getPercent() }, { effectiveBrightness() })
                    voiceRoutes(
                        hasMicrophone = { liveCapabilities(managementObservations.snapStaleOk().caps).microphone.offered },
                        microphone = {
                            voiceMicrophone()
                                ?: io.panelassistant.android.audio.MicrophoneStatus(
                                    liveCapabilities(managementObservations.snapStaleOk().caps).microphone,
                                )
                        },
                        voiceEnabled = { config.voiceEnabled },
                        assistPipelines = assistPipelines,
                        voiceTest = voiceTest,
                        wakeWords = wakeWords,
                        onWakeWordsChanged = onWakeWordsChanged,
                        authorize = ::authorizeSensitive,
                    )
                    // LAN ha-paneld panels for the header panel switcher — a cheap, non-blocking snapshot of
                    // the live mDNS roster (a background listener keeps it converged + fresh; see browsePeers).
                    discoveryRoutes({ peersJson(peers()) }, { launchableAppsJson(appContext) })
                    dashboardHydrationRoute(::requestStrings) { strings -> dashboardPageHandler().json(strings) }
                    managementRoutes(
                        diagnostics = ::diagText,
                        status = ::statusJson,
                        admitActiveRead = ::admitActiveRead,
                        refreshUpdates = {
                            UpdateChecker.check(appContext, "stable", profile.companionMaxVersion)
                        },
                        refreshStorage = refreshStorageHealth,
                        cachedStorage = storageHealth,
                    )
                    loggingRoutes(
                        logApp, logSystem, logWebView, webViewConsoleEnabled, systemLogAvailable,
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
                    rendererMaintenanceRoutes(
                        appContext, config, system, scope, clearStorageGate, { stopping },
                        { onRepairCompanionUrl { companionBackupOperations().repairInternalUrls() } },
                        invalidateCompanionObservation = { managementObservations.companionServerCache.invalidate() },
                        authorizeSensitive = ::authorizeSensitive,
                    )
                    remoteControlRoutes({ remoteControlRoutes }, ::authorizeSensitive)
                    screenshotRoutes(screenshots, { interactive.screenshot() }, { admitActiveRead(it) })
                    cameraRoutes(camera)
                    apiSpecificationRoute(asset)
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
                    tameSuggestionRoutes(::requestStrings, ::admitActiveRead) {
                        tame.suggestionGroups(tameProfileCandidates, config.tameVendorPackages.toSet(), PerfReader.topNames())
                    }
                    displayRoutes(
                        appContext, profile, density, managementObservations.densityCache, recommendedDensity, recommendedFontScale,
                        privilegedSizing = { managementObservations.snapStaleOk().privilege.rootControlReady },
                        requestStrings = ::requestStrings,
                        snapInvalidate = ::snapInvalidate,
                        authorizeSensitive = ::authorizeSensitive,
                        escapeHtml = ::esc,
                        localizedHref = ::localizedHref,
                    )
                    debugInspectionRoutes(appContext, config, inspectLock, { stopping }, ::authorizeSensitive)
                }
            }
            browserNotFoundPage(::requestStrings)
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
                config.unregisterChangeListener(securityModeChanged)
                listener?.stop()
                listener = null
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
            permissions = permissionStatus,
            proximity = sensors::proximityStatusJson,
            panelAssistantTransport = panelAssistantTransport?.facts(),
            homeProof = if (homeProofRequested) ({
                val proof = system.homeUiProof(config.dashboardPackage, KioskAdminUi.isVisible())
                homeUiProofJson(proof.state, proof.reason, proof.evidence)
            }) else null,
            english = catalogueLoader.strings(AppLocale.ENGLISH),
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
                catalogueLoader.strings(AppLocale.ENGLISH),
                voice = voiceDiagnosticLine(
                    config.voiceEnabled,
                    voiceMicrophone()
                        ?: io.panelassistant.android.audio.MicrophoneStatus(liveCapabilities(management.caps).microphone),
                    config.voiceWakeWords, config.voicePipelines, config.voiceAudioSource,
                    config.voiceSensitivity, config.voiceMicGainDb, config.voiceStopWord,
                ),
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
        haSignInNeededForEffectiveDashboard = setupState::haSignInNeededForEffectiveDashboard,
        powerSafetyAdvisory = { privilege ->
            pageHealth.powerSafetyAdvisory(privilege, { profile.appCanSu }, powerSafety)
        },
        storageHealth = { storageHealth() },
        catalogueLoader = { catalogueLoader },
        radioStatus = radioStatus,
        dashboardRecoveryState = pageHealth::dashboardRecoveryState,
        appSizingReady = density::appSizingReady,
    )

    private fun dashboardPageHandler() = DashboardPageHandler(
        config, appContext, profile, camera, managementObservations, pageHealth, screenshots,
        dashboardRows(), dashboardAdvisories(), { pages }, ::buildToken,
        { rootReady -> interactive.screenshotReady(rootReady) },
    )

    private fun configurePageHandler() = ConfigurePageHandler(
        config, sensors, managementObservations, setupState,
        strategySelectorAllowed = { entityLearning.strategySelectorAllowed() },
        pageHealth = pageHealth,
        powerSafetyAdvisory = { privilege ->
            pageHealth.powerSafetyAdvisory(privilege, { profile.appCanSu }, powerSafety)
        },
        haSignInNeededForEffectiveDashboard = setupState::haSignInNeededForEffectiveDashboard,
    )

    private fun installPageHandler() = InstallPageHandler(
        config, appContext, profile, managementObservations, pageHealth,
        tame, tameProfileCandidates, recommendedDensity, recommendedFontScale,
        appSizingReady = density::appSizingReady,
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





    /**
     * Install the canonical direct-config mutation route. The optional capability provider is a narrow
     * JVM-test seam: production always uses the request-scoped management snapshot, while route tests can
     * avoid constructing Android sensor/probe owners without replacing either Ktor routing or this handler.
     */
    internal fun Route.installDirectConfigPostRoute(
        capabilityProvider: (() -> Capabilities)? = null,
    ) = ops {
        // Protocol section 20.3: POST /api/v1/config is the `settings` operation. Panel Assistant's own origin
        // keeps its session allowlist; relayed and stub origins pass the Configure admission HTTP passes.
        post("/config", name = "settings") {
            if (call.origin.isPanelAssistant &&
                call.args?.keys()?.asSequence()?.all { it in io.panelassistant.android.panelassistant.PanelAssistantManagedSettings.KEYS } != true
            ) {
                return@post call.respondText("not-commandable\n", status = HttpStatusCode.Forbidden)
            }
            directConfigPost().handle(call, capabilityProvider)
        }
    }

    /** What Panel Assistant reads over its session: the same health line and status body HTTP serves. */
    internal suspend fun managementSnapshot() = withContext(Dispatchers.IO) {
        io.panelassistant.android.panelassistant.PanelAssistantManagementSnapshot(
            healthLine(config, appContext.packageName, ::buildToken, ::renderConfigConcurrencyHash) { panelAssistantTransport?.restartHealthToken().orEmpty() },
            statusJson(storageHealth()),
        )
    }

    /**
     * The named requests Panel Assistant makes over its session (protocol section 20.3): `status`, and the
     * update and backup routes, whose bytes move through Panel Assistant's blob view with [auth]'s credential.
     * Null for a name this build does not serve.
     */
    internal suspend fun managementRequest(op: String, args: JSONObject, onPending: () -> Unit, auth: HaApiSessionProvider): PanelAssistantManageReply? {
        if (op == MANAGE_STATUS) {
            val body = withContext(Dispatchers.IO) { statusJson(storageHealth(), homeProofRequested = args.optBoolean("home_proof")) }
            return PanelAssistantManageReply(PanelAssistantTransportProtocol.OUTCOME_APPLIED, null, JSONObject(body))
        }
        if (op == PanelAssistantTransportProtocol.MANAGE_HA_AREA_REFRESH) {
            val read = runCatching { haArea.refreshFromHomeAssistant() }.getOrDefault(false)
            return if (read) PanelAssistantManageReply(PanelAssistantTransportProtocol.OUTCOME_APPLIED, null, null)
            else PanelAssistantManageReply(PanelAssistantTransportProtocol.OUTCOME_FAILED, io.panelassistant.android.panelassistant.PanelAssistantCommandProcessor.CODE_FAILED, null)
        }
        if (op == PanelAssistantTransportProtocol.MANAGE_BUNDLE) return uploadUiBundle(args, PanelAssistantBlobs(auth), bundleAssets, buildToken(), cacheDir)
        return ControlPlaneSessionOps.request(op, args, controlPlane(), PanelAssistantBlobs(auth)) { operation, payload, summary ->
            authorizeSessionRequest(config.hardenedSecurityEnabled, operation, payload, summary, LocalApprovalBroker.instance, onPending)
        } ?: manageThroughDispatcher(op, args, onPending)
    }

    /**
     * Every other name goes to the dispatcher's one table of named operations with Panel Assistant's origin
     * (protocol section 20.2), so the session and the stub's socket serve each read from one definition.
     * Bodies that must move as blobs are left to the session's own operations above. Null when no
     * dispatcher holds the name.
     */
    private suspend fun manageThroughDispatcher(op: String, args: JSONObject, onPending: () -> Unit): PanelAssistantManageReply? {
        val kind = manageTable[op]?.kind ?: return null
        // Panel Assistant changes this panel only through its own operations above and the settings allowlist
        // (protocol section 20.2); through this fall-through it may read, never command.
        if (kind != io.panelassistant.android.ManageKind.READ) {
            return PanelAssistantManageReply(
                PanelAssistantTransportProtocol.OUTCOME_REFUSED,
                io.panelassistant.android.panelassistant.PanelAssistantCommandProcessor.CODE_NOT_COMMANDABLE,
                null,
            )
        }
        val dispatcher = door() ?: return null
        val reply = kotlinx.coroutines.suspendCancellableCoroutine<io.panelassistant.android.ManageReply> { waiting ->
            dispatcher.manage(
                java.util.UUID.randomUUID().toString(), op, args, io.panelassistant.android.CommandOrigin.panelAssistant(),
                blobs = null, onPending = onPending,
            ) { answer -> if (waiting.isActive) waiting.resume(answer) {} }
        }
        if (reply.outcome == PanelAssistantTransportProtocol.OUTCOME_REFUSED &&
            reply.code == io.panelassistant.android.panelassistant.PanelAssistantCommandProcessor.CODE_UNKNOWN_COMMAND
        ) return null
        return PanelAssistantManageReply(reply.outcome, reply.code, reply.result)
    }

    /** Settings Panel Assistant writes over its session, through the Configure page's own validation. */
    internal suspend fun applyManagedSettings(settings: Map<String, String>, admit: () -> Boolean): String? =
        io.panelassistant.android.panelassistant.PanelAssistantManagedSettings.apply(
            settings,
            admit,
            validate = { values ->
                val raw = io.ktor.http.Parameters.build { values.forEach { (key, value) -> append(key, value) } }
                when (val result = normalizeConfigPostParameters(raw, liveCapabilities(managementObservations.snapStaleOk().caps))) {
                    is ConfigPostParameters.Ok -> result.values.names().associateWith { result.values[it].orEmpty() }
                    is ConfigPostParameters.Bad -> null
                }
            },
            commit = { accepted, admitted -> acceptedConfigTransaction().applyAccepted(accepted, admit = admitted) == ApplyAcceptedResult.Applied },
        )

    private fun directConfigPost() = acceptedConfigTransaction().directPost(
        directConfigMutationLock = directConfigMutationLock,
        autoSleepHttpApi = autoSleepHttpApi,
        autoBrightnessHttpApi = autoBrightnessHttpApi,
        onboarding = DirectConfigOnboarding(config),
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
                capabilities = { liveCapabilities(managementObservations.snapCache.get().caps) },
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
            captureCompanion = companionBackupOperations()::capture,
            wakeWords = wakeWords,
            calibrations = calibrationCustody(),
            dashboardIssues = issueCustody(),
        ).build(request, passphrase)

    private fun calibrationCustody() = proximityCalibrations
        ?: io.panelassistant.android.persistence.CatalogProximityCalibrationCustody(appContext)

    private fun issueCustody() = dashboardIssueCustody
        ?: io.panelassistant.android.persistence.CatalogDashboardIssueCustody(appContext)

    private suspend fun handleRestore(call: OpCall) {
        val executor = AcceptedConfigTransaction.restoreExecutor(
            appContext = appContext,
            config = config,
            transaction = ::acceptedConfigTransaction,
            capabilities = { liveCapabilities(managementObservations.snapCache.get().caps) },
            restoreCompanion = { companionBackupOperations().restore(it) },
            profileAdmin = profileAdmin,
            onProfileRestart = { onProfileRestart() },
            onProfileRestartAbort = { onProfileRestartAbort(it) },
            onDurableStateRestored = { onDurableStateRestored() },
            onWakeWordsChanged = { onWakeWordsChanged() },
            calibrations = calibrationCustody(),
            dashboardIssues = issueCustody(),
        )
        RestoreRoutes(
            cacheDir = cacheDir,
            config = config,
            reader = BackupArchiveReader(cacheDir) { CompanionInstaller.installedPackages(appContext) },
            profileAdmin = profileAdmin,
            ensureCompanionHelper = managementObservations::ensureCompanionHelper,
            companionHelperReady = { managementObservations.companionHelperCache.get() },
            authorizeSensitive = ::authorizeSensitive,
            rejectHardenedNetworkAdb = ::rejectHardenedNetworkAdb,
            capabilities = { liveCapabilities(managementObservations.snapCache.get().caps) },
            scope = scope,
            executor = executor,
            wakeWords = wakeWords,
        ).handle(call)
    }







    private fun performanceWorkloadValues(): Map<String, String> {
        val live = managementObservations.snapStaleOk().live
        return PERFORMANCE_WORKLOAD_KEYS.associateWith { key ->
            effectiveSettingValue(config, requireNotNull(SettingsRegistry.spec(key)), live)
        }
    }

    companion object {
        /** The session name for `GET /api/v1/status` (protocol section 20.3); `args.home_proof` adds the HOME proof. */
        internal const val MANAGE_STATUS = "status"
        private const val TAG = "ha-paneld/http"
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
        internal const val MAX_APK_UPLOAD_BYTES = 256L * 1024L * 1024L

    }
}
