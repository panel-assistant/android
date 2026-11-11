package io.panelassistant.android.http

import android.util.Log
import io.panelassistant.android.Config
import io.panelassistant.android.DashboardIdleReturnPolicy
import io.panelassistant.android.NativeLocale
import io.panelassistant.android.autoSleep
import io.panelassistant.android.PresenceMode
import io.panelassistant.android.presenceMode
import io.panelassistant.android.dashboardEntityLearningEnabled
import io.panelassistant.android.haAuthSnapshot
import io.panelassistant.android.haOriginChanges
import io.panelassistant.android.haRefreshToken
import io.panelassistant.android.haToken
import io.panelassistant.android.haUrl
import io.panelassistant.android.sensors.HaPanelAreaPrerequisitePhase
import io.panelassistant.android.HaAuthOwner
import io.panelassistant.android.LiveSettingRequestOutcome
import io.panelassistant.android.setHaSetupHandover
import io.panelassistant.android.setHaUrlHandover
import io.panelassistant.android.setHaUrlHandoverReason
import io.panelassistant.android.stableOwner
import io.panelassistant.android.config.Capabilities
import io.panelassistant.android.config.ConfigBundle
import io.panelassistant.android.config.SettingValue
import io.panelassistant.android.config.SettingsRegistry
import io.panelassistant.android.config.Validation
import io.panelassistant.android.control.PowerSafetyMutationPolicy
import io.panelassistant.android.security.SensitiveOperation
import io.panelassistant.android.util.InstallProgress
import io.panelassistant.android.util.Json
import io.panelassistant.android.util.RendererPreparationCoordinator
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
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
    private val authorizeSensitive: suspend (OpCall, SensitiveOperation, String, String) -> Boolean,
    private val rejectHardenedNetworkAdb: suspend (OpCall, String?) -> Boolean,
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
     * identity/logging/tame keys is preserved; the formerly command-only behaviour keys are applied
     * through [applySetting] (same path as an HA command), and per-row HA-exposure toggles are stored.
     */
    suspend fun handle(
        call: OpCall,
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
        val p = handoverPost.parameters
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
        val requestedAutoSleepSource = p[PresenceMode.KEY] ?: config.presenceMode.wire
        val requestedAutoSleep = p["auto_sleep"]?.let(SettingValue::parseBool) ?: config.autoSleep
        val enablingAutoSleep = autoSleepRequiresHaAdmission(
            config.autoSleep, config.presenceMode.wire, requestedAutoSleep, requestedAutoSleepSource,
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
                // The commit clears the stored credentials on an origin change in every security mode.
                currentHasCredentials = (config.haToken.isNotBlank() || config.haRefreshToken.isNotBlank()) &&
                    !haOriginChanges(config.haUrl, p["ha_url"]),
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
            !DashboardIdleReturnPolicy.appliesTo(dashboardPackage ?: config.dashboardPackage)
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
            loopbackPeer = call.origin.isLoopback,
            requestedOperations = sensitiveOperations,
            exempt = { operation -> relayedAdministratorExempt(call.origin, operation) },
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
        // Persisted fields are staged into one editor and committed atomically, so a power loss mid-apply
        // can't leave a half-written config (e.g. address set but credentials not). Live side-effects
        // (behaviour keys, reconfigure) run after the commit so they read freshly-committed state, and
        // renderer effects come from what changed against the pre-commit revision, never a read-back
        // inside applyBatch, which returns the pre-commit value.
        var credentialsChanged = false
        var entityLearningChanged: Boolean? = null
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
                            (config.autoSleep && config.presenceMode == PresenceMode.HOME_ASSISTANT))
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
                    // Every registered setting that changed is written once, here; the bespoke writes
                    // below are only for keys with no spec, coupled groups and live keys.
                    stageDirectConfigRegistryValues(config, postedValues, mutationPlan.changedKeys)
                    // Commit the coupled range before either live handler schedules a new evaluation.
                    p["auto_brightness_minimum_percent"]?.toInt()?.let(config::setAutoBrightnessMinimumPercent)
                    p["auto_brightness_maximum_percent"]?.toInt()?.let(config::setAutoBrightnessMaximumPercent)
                    p["http_allowed_hosts"]?.let { config.setHttpAllowedHosts(it) }
                    // Saved for the next start of the HTTP server; the running listener keeps its port.
                    p["http_port"]?.toInt()?.let { port -> config.setHttpPortRequest(port.takeIf { it != config.httpPort }) }
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
                    stageDirectLogShipping(config, postedValues)
                    // Credential groups carry dependent-clear semantics which cannot be represented as
                    // independent generic keys. Keep their actual owner in one production-used helper so
                    // the behavioural contract can prove the HA session transitions.
                    credentialsChanged = stageDirectCredentialSettings(config, postedValues).haChanged

                    // Per-row "expose to HA" toggles (ha_expose_<key>=true|false); a sensor's pip withholds its
                    // reading, and the reconfigure re-reports that channel.
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
                        // Home dashboard normally travels through the live command path. Apply this
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
                        if (credentialsChanged && !homeDashboardChangedEarly) {
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
                        // A dashboard app switch re-anchors HOME to the new renderer and brings it up now;
                        // the kiosk and watchdog loops read dashboard_package per tick and retarget alone.
                        applyRendererEffects(
                            RendererConfigEffects.between(
                                requireNotNull(previous).values,
                                postedValues.filterKeys(mutationPlan.changedKeys::contains),
                                // The credential group's own owner decides a credential change, and the home
                                // path counts only once applied, because its live handler may refuse it.
                                credentialChanged = credentialsChanged,
                                homeChanged = homeDashboardChangedEarly,
                            ),
                        )
                    }.onFailure { rendererFailure = it }
                }
                    persisted
                }
                // Admission can refuse before a plan exists; only committed saves have live effects.
                if (!saved) return@synchronized false
                if (!mutationPlan.isNoOp) {
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
                        // The owner's press of Network ADB on a panel that has lost its session: every
                        // admission above has passed, so this is where Panel Assistant's lease is given
                        // back. An ON that cannot have it is rejected before anything is queued.
                        if (key == "network_adb" && value == "true" &&
                            !io.panelassistant.android.control.takeNetworkAdbBackForOwner()
                        ) {
                            liveRejected += key
                            return@dispatchDirectConfigLiveSettings
                        }
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
                    // (it retries when an admin next reads the ha-area endpoint). Afterwards adopt whatever HA actually reports — for a value HA
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
            // Nothing changes until the HTTP server next starts, so nothing republishes the port now.
            remove("http_port")
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
        val portMessage = config.httpPortRequest?.takeIf { "http_port" in liveApplied }?.let {
            "Settings saved. The panel moves to port $it the next time its HTTP server starts."
        }
        respondConfigMutation(
            call,
            if (livePending.isEmpty()) "saved" else "saved-apply-pending",
            liveApplied,
            livePending,
            emptyList(),
            pendingMessage ?: handoverMessage ?: portMessage,
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
        call: OpCall,
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
