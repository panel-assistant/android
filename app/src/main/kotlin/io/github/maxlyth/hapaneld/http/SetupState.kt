package io.github.maxlyth.hapaneld.http

import android.content.Context
import android.util.Log
import io.github.maxlyth.hapaneld.Config
import io.github.maxlyth.hapaneld.DiscoveryResult
import io.github.maxlyth.hapaneld.HaDiscovery
import io.github.maxlyth.hapaneld.RendererResolver
import io.github.maxlyth.hapaneld.control.BuiltinDashboard
import io.github.maxlyth.hapaneld.control.SystemController
import io.github.maxlyth.hapaneld.dashboard.EntityLearningManager
import io.github.maxlyth.hapaneld.device.DeviceProfile
import io.github.maxlyth.hapaneld.util.Json
import io.github.maxlyth.hapaneld.util.RendererPreparationCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class SetupState(
    private val config: Config,
    private val system: SystemController,
    private val entityLearning: EntityLearningManager,
    private val profile: DeviceProfile,
    private val appContext: Context,
    private val mqttState: () -> String,
    private val haOAuthPending: () -> Int,
    private val lastHaDiscovery: () -> DiscoveryResult,
    private val webViewTooOldOnce: () -> Boolean,
    private val panelAssistantNative: () -> Boolean,
    private val effectiveDashboardIsBuiltin: () -> Boolean,
    private val scope: CoroutineScope,
    private val rendererPreparation: RendererPreparationCoordinator,
) {
    fun attest() {
        // A human at the panel (or looking at it) confirms the dashboard is actually
        // showing. Bound to the current configuration fingerprint, so changing the URL,
        // renderer or account silently voids it and the journey re-arms — nothing to
        // expire, nothing to clean up.
        config.setupRenderAttestation = setupProofFingerprint()
        config.setupEverCompleted = true
    }

    fun confirmIdentity() {
        // Separate from POST /config on purpose. The panel name is an ordinary setting and
        // goes through the usual validated path; what this records is that a human saw the
        // consequence and accepted it, which is not a setting and must never appear on the
        // Configure form, in a config bundle or as a Home Assistant entity.
        config.setupIdentityConfirmed = true
    }

    fun chooseHomeDashboard() {
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
    }

    fun answerEntityFilter() {
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
    fun setupNeedsUser(): Boolean = SetupJourney.evaluate(setupJourneyInputs()).needsUser

    fun dashboardSetupStepPending(): Boolean =
        SetupJourney.evaluate(setupJourneyInputs()).step(SetupJourney.Stage.RENDERER).status !=
            SetupJourney.Status.SATISFIED

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
            haOAuthInFlight = haOAuthPending() > 0,
            discovery = lastHaDiscovery(),
            // Uses the true engine major from the WebView user agent, not the package stamp, so a panel
            // already swapped to a LineageOS/Cromite build is not accused of being ancient because the
            // provider still reports the OEM version.
            webViewTooOld = builtin && webViewTooOldOnce(),
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

    fun setupJourneyJson(): String {
        val journey = SetupJourney.evaluate(setupJourneyInputs())
        val steps = journey.steps.joinToString(",") { step ->
            "{\"stage\":${jsonStr(step.stage.name.lowercase())}," +
                "\"status\":${jsonStr(step.status.name.lowercase())}," +
                "\"blocking\":${step.blocking}," +
                "\"detail\":${jsonStr(step.detail)}}"
        }
        val next = journey.next?.let { jsonStr(it.name.lowercase()) } ?: "null"
        val discovery = lastHaDiscovery()
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

    private fun jsonStr(s: String): String = Json.str(s)

    companion object {
        private const val TAG = "ha-paneld/http"
    }
}
