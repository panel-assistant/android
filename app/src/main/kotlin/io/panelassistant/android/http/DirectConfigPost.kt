package io.panelassistant.android.http

import android.util.Log
import io.panelassistant.android.Config
import io.panelassistant.android.NativeLocale
import io.panelassistant.android.sensors.HaPanelAreaPrerequisitePhase
import io.panelassistant.android.HaAuthOwner
import io.panelassistant.android.LiveSettingRequestOutcome
import io.panelassistant.android.stableOwner
import io.panelassistant.android.config.Capabilities
import io.panelassistant.android.config.ConfigBundle
import io.panelassistant.android.config.SettingValue
import io.panelassistant.android.config.SettingsRegistry
import io.panelassistant.android.config.Validation
import io.panelassistant.android.control.PowerSafetyMutationPolicy
import io.panelassistant.android.control.SystemController
import io.panelassistant.android.security.SensitiveOperation
import io.panelassistant.android.util.DashboardTheme
import io.panelassistant.android.util.isLoopbackPeer
import io.panelassistant.android.util.InstallProgress
import io.panelassistant.android.util.Json
import io.panelassistant.android.util.RendererPreparationCoordinator
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.response.respondText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Direct Configure mutations retain one admission, commit and live-apply sequence. */
internal class DirectConfigPost(
    private val config: Config,
    private val revisions: RevisionStore,
    private val directConfigMutationLock: Any,
    private val rendererPreparation: RendererPreparationCoordinator,
    private val autoSleepHttpApi: AutoSleepHttpApi,
    private val autoBrightnessHttpApi: AutoBrightnessHttpApi,
    private val onboarding: DirectConfigOnboarding,
    private val capabilities: () -> Capabilities,
    private val directMutationValues: () -> Map<String, String>,
    private val revisionValues: () -> Map<String, String>,
    private val authorizeSensitive: suspend (ApplicationCall, SensitiveOperation, String, String) -> Boolean,
    private val rejectHardenedNetworkAdb: suspend (ApplicationCall, String?) -> Boolean,
    private val applySetting: (String, String) -> LiveSettingRequestOutcome,
    private val applyRendererEffects: (RendererConfigEffects) -> Unit,
    private val onEntityTargetChanged: () -> Unit,
    private val setEntityLearningEnabled: (Boolean) -> Boolean,
    private val requestTameReconcileAfterCommit: () -> Boolean,
    private val onHaAreaCommitted: () -> Unit,
    private val snapInvalidate: () -> Unit,
    private val onReconfigure: (Set<String>) -> Unit,
    private val configJson: (String, List<String>, List<String>, List<String>, String) -> String,
) {
    /**
     * Apply a POSTed config form/JSON (partial-merge), then live-reconfigure. Shared by the legacy
     * `/config` route and `/api/v1/config`. Fleet/JSON clients (Accept: application/json) get the new
     * config back; a browser form gets an HTML redirect to the info page. The bespoke handling of
     * identity/MQTT/logging/tame keys is preserved; the formerly MQTT-only behaviour keys are applied
     * through [applySetting] (same path as an HA command), and per-row HA-exposure toggles are stored.
     */
    suspend fun handle(
        call: ApplicationCall,
        capabilityProvider: (() -> Capabilities)? = null,
    ) {
        val received = receiveBoundedConfigParameters(call) ?: return
        // Same capability snapshot the Configure form was rendered from, so a choice the form offered is
        // the same set this admission step accepts.
        val postCaps = capabilityProvider?.invoke() ?: capabilities()
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
        val handoverPost = onboarding.augmentPostWithVerifiedHandover(normalizedPost)
        val onboardingPost = onboarding.augmentPostWithDiscoveredHaUrlForMqttOnboarding(handoverPost.parameters)
        val p = onboardingPost.parameters
        val postedValues = p.names().associateWith { p[it].orEmpty() }
        SettingsRegistry.automaticBrightnessBoundsError(
            postedValues, config.autoBrightnessMinimumPercent, config.autoBrightnessMaximumPercent,
        )?.let { reason ->
            call.respondText("$reason\n", status = HttpStatusCode.BadRequest)
            return
        }
        if (rejectHardenedNetworkAdb(call, p["network_adb"])) return
        // Fence every baseline-dependent admission decision below. The direct mutation lane rechecks
        // this complete effective snapshot before persistence, so a concurrent save cannot bypass an
        // Area prerequisite or hardened approval by changing the value while this request waits.
        val admissionBaselineHash = io.panelassistant.android.config.ConfigHash.of(directMutationValues())
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
        var ambientSourceOwner: io.panelassistant.android.HaAuthOwner? = null
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
        val powerSafetyReduction = PowerSafetyMutationPolicy.requestsSafetyReduction(
            keepAwake = config.keepAwake,
            requestedKeepAwake = p["keep_awake"]?.let(SettingValue::parseBool),
            preventIdleDim = config.preventIdleDim,
            requestedPreventIdleDim = p["prevent_idle_dim"]?.let(SettingValue::parseBool),
        )
        val sensitiveOperations = buildList {
            if (powerSafetyReduction) add(SensitiveOperation.POWER_CONFIGURATION)
            if (tamePackagesChanged) add(SensitiveOperation.PACKAGE_TAME)
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
                        SensitiveOperation.PACKAGE_TAME -> "Change the persistent vendor package blocklist"
                        else -> error("Unexpected Configure approval operation")
                    },
                )
            },
        )) {
            ConfigSensitiveAdmissionResult.SEPARATE_SENSITIVE_CHANGES -> {
                call.respondText(
                    """{"ok":false,"error":"separate-sensitive-changes","message":"Save power-safety reductions separately from vendor-package changes."}""",
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
        try {
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
        var previous: ConfigBundle? = null
        val committed = withContext(Dispatchers.IO) {
            synchronized(directConfigMutationLock) {
                val saved = rendererPreparation.transaction {
                    val persisted = config.synchronizedTransaction {
                    if (io.panelassistant.android.config.ConfigHash.of(directMutationValues()) != admissionBaselineHash ||
                        SettingsRegistry.automaticBrightnessBoundsError(
                            postedValues, config.autoBrightnessMinimumPercent, config.autoBrightnessMaximumPercent,
                        ) != null
                    ) {
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
                    config.applyBatch(
                        afterCommit = {
                            if ("tame_vendor_packages" in p) requestTameReconcileAfterCommit()
                            p["ui_language"]?.let(NativeLocale::apply)
                        },
                    ) {
                    stageDirectConfigRegistryValues(config, postedValues, mutationPlan.changedKeys)
                    // Commit the coupled range before either live handler schedules a new evaluation.
                    p["auto_brightness_minimum_percent"]?.toInt()?.let(config::setAutoBrightnessMinimumPercent)
                    p["auto_brightness_maximum_percent"]?.toInt()?.let(config::setAutoBrightnessMaximumPercent)
                    panelId?.let { config.setPanelId(it) }
                    p["friendly_name"]?.let { config.setFriendlyName(it.trim()) }
                    p["ui_language"]?.let { config.setUiLanguage(it) }
                    val prevDash = config.dashboardPackage
                    dashboardPackage?.let { config.setDashboardPackage(it) }
                    val dashChanged = dashboardPackage?.let { it != prevDash } == true
                    p["launcher_package"]?.let { config.setLauncherPackage(it.trim()) }
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
                            onEntityTargetChanged()
                        }
                        entityLearningChanged?.let { enabled ->
                            val delegated = applyDirectConfigDelegatedSettings(
                                postedValues,
                                mutationPlan.changedKeys,
                            ) { key, value ->
                                key == "dashboard_entity_learning" &&
                                    setEntityLearningEnabled(value.toBoolean())
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
                    onHaAreaCommitted()
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
        val onboardingSignInMessage = onboarding.mqttOnboardingSignInMessage(
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
            InstallProgress.finishConfigMutation(configMutationTicket)
        }
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
                configJson(status, applied, pending, rejected, resolvedMessage),
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

    private companion object {
        const val TAG = "ha-paneld/http"
    }
}
