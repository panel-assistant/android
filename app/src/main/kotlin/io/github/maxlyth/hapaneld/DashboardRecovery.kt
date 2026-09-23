package io.github.maxlyth.hapaneld

import android.content.Intent
import io.github.maxlyth.hapaneld.dashboard.EntityBootstrapProblem
import io.github.maxlyth.hapaneld.dashboard.EntityFilterProtocol
import io.github.maxlyth.hapaneld.http.HA_OAUTH_CALLBACK_PATH
import io.github.maxlyth.hapaneld.util.ProfileRestartCoordinator
import io.github.maxlyth.hapaneld.util.InstallPresentation
import java.net.URI
import java.net.URLEncoder

/** Locale-neutral counterpart of the renderer-specific warning rendered by the HTTP server. */
internal fun dashboardRecoveryPresentation(
    state: PanelStatus.DashboardRecoveryState,
): InstallPresentation? = when (state) {
    PanelStatus.DashboardRecoveryState.NONE -> null
    PanelStatus.DashboardRecoveryState.BUILTIN_RENDERER ->
        InstallPresentation("status-builtin-renderer-retries-stopped")
    PanelStatus.DashboardRecoveryState.EXTERNAL_RENDERER ->
        InstallPresentation("status-external-renderer-crash-loop")
}

/**
 * Owns callbacks from one replaceable WebView generation. Opening or invalidating a generation makes
 * every callback captured by an older renderer stale; closing the gate makes all callbacks terminally
 * stale. Methods are synchronized because the external-auth bridge runs off the Android main thread.
 */
internal class RendererGenerationGate {
    private var sequence = 0L
    private var current = 0L
    private var closed = false

    @Synchronized fun open(): Long {
        check(!closed) { "renderer generation gate is closed" }
        current = ++sequence
        return current
    }

    @Synchronized fun invalidate() {
        current = ++sequence
    }

    @Synchronized fun owns(generation: Long): Boolean =
        !closed && generation != 0L && generation == current

    @Synchronized fun close() {
        closed = true
        current = ++sequence
    }
}

internal data class WakeMediaRecoveryTicket(
    val cycle: Long,
    val rendererGeneration: Long,
)

internal enum class WakeMediaRecoveryAction { NONE, INSPECT, RELOAD }

/** Owns one bounded media-resume check for each real screen-off to screen-on cycle. */
internal class WakeMediaRecoveryGate {
    private var sequence = 0L
    private var current: WakeMediaRecoveryTicket? = null
    private var deferred = false
    private var recoveryClaimed = false
    private var closed = false

    @Synchronized fun begin(rendererGeneration: Long): WakeMediaRecoveryTicket {
        check(!closed) { "wake media recovery gate is closed" }
        deferred = false
        return current?.takeIf { it.rendererGeneration == rendererGeneration } ?: WakeMediaRecoveryTicket(
            cycle = ++sequence,
            rendererGeneration = rendererGeneration,
        ).also {
            current = it
            recoveryClaimed = false
        }
    }

    /** Preserve a real wake edge until this exact renderer generation reconnects its frontend. */
    @Synchronized fun defer(rendererGeneration: Long): WakeMediaRecoveryTicket {
        check(!closed) { "wake media recovery gate is closed" }
        val ticket = current?.takeIf { it.rendererGeneration == rendererGeneration } ?: WakeMediaRecoveryTicket(
            cycle = ++sequence,
            rendererGeneration = rendererGeneration,
        ).also {
            current = it
            recoveryClaimed = false
        }
        deferred = true
        return ticket
    }

    /** Claim a deferred wake only for the renderer that owned the original dark-to-awake edge. */
    @Synchronized fun activateDeferred(rendererGeneration: Long): WakeMediaRecoveryTicket? {
        val ticket = current?.takeIf { deferred && it.rendererGeneration == rendererGeneration } ?: return null
        deferred = false
        return ticket
    }

    @Synchronized fun owns(ticket: WakeMediaRecoveryTicket): Boolean =
        !closed && current == ticket

    @Synchronized fun onArmResult(ticket: WakeMediaRecoveryTicket, candidates: Int): WakeMediaRecoveryAction {
        if (!owns(ticket)) return WakeMediaRecoveryAction.NONE
        if (candidates > 0) return WakeMediaRecoveryAction.INSPECT
        complete(ticket)
        return WakeMediaRecoveryAction.NONE
    }

    @Synchronized fun onInspectResult(ticket: WakeMediaRecoveryTicket, stalled: Boolean): WakeMediaRecoveryAction {
        if (!owns(ticket) || recoveryClaimed) return WakeMediaRecoveryAction.NONE
        if (!stalled) {
            complete(ticket)
            return WakeMediaRecoveryAction.NONE
        }
        recoveryClaimed = true
        return WakeMediaRecoveryAction.RELOAD
    }

    @Synchronized fun complete(ticket: WakeMediaRecoveryTicket) {
        if (current == ticket) {
            current = null
            deferred = false
        }
    }

    @Synchronized fun invalidate() {
        current = null
        deferred = false
        recoveryClaimed = false
    }

    @Synchronized fun close() {
        closed = true
        invalidate()
    }
}

/** Exact scripts evaluated in the live dashboard to distinguish healthy resumed media from a dead camera card. */
internal object WakeMediaRecoveryScript {
    private const val STATE = "__haPanelWakeMedia"

    fun arm(cycle: Long): String =
        """
        (()=>{try{
          const videos=[];
          const visit=root=>{
            root.querySelectorAll('video').forEach(video=>videos.push(video));
            root.querySelectorAll('*').forEach(element=>{if(element.shadowRoot)visit(element.shadowRoot);});
          };
          const live=video=>{const stream=video.srcObject;return !!(stream&&typeof stream.getVideoTracks==='function'&&stream.getVideoTracks().some(track=>track.readyState==='live'));};
          const visible=video=>{const rect=video.getBoundingClientRect();const style=getComputedStyle(video);return video.isConnected&&rect.width>0&&rect.height>0&&rect.bottom>0&&rect.right>0&&rect.top<innerHeight&&rect.left<innerWidth&&style.display!=='none'&&style.visibility!=='hidden'&&Number(style.opacity)!==0;};
          const frames=video=>{try{if(typeof video.getVideoPlaybackQuality==='function'){const n=Number(video.getVideoPlaybackQuality().totalVideoFrames);if(Number.isFinite(n))return {supported:true,value:n};}if(typeof video.webkitDecodedFrameCount==='number'&&Number.isFinite(video.webkitDecodedFrameCount))return {supported:true,value:video.webkitDecodedFrameCount};}catch(_){}return {supported:false,value:0};};
          visit(document);
          const candidates=videos.filter(video=>visible(video)&&!video.ended&&(!video.paused||video.autoplay||live(video)));
          candidates.forEach(video=>{try{const playing=video.play();if(playing&&typeof playing.catch==='function')playing.catch(()=>{});}catch(_){}});
          window.$STATE={cycle:$cycle,samples:candidates.map(video=>({video,time:Number(video.currentTime)||0,frames:frames(video)}))};
          return candidates.length;
        }catch(_){return -1;}})()
        """.trimIndent()

    fun inspect(cycle: Long): String =
        """
        (()=>{try{
          const state=window.$STATE;
          delete window.$STATE;
          if(!state||state.cycle!==$cycle||!state.samples.length)return -1;
          const live=video=>{const stream=video.srcObject;return !!(stream&&typeof stream.getVideoTracks==='function'&&stream.getVideoTracks().some(track=>track.readyState==='live'));};
          const visible=video=>{const rect=video.getBoundingClientRect();const style=getComputedStyle(video);return video.isConnected&&rect.width>0&&rect.height>0&&rect.bottom>0&&rect.right>0&&rect.top<innerHeight&&rect.left<innerWidth&&style.display!=='none'&&style.visibility!=='hidden'&&Number(style.opacity)!==0;};
          const frames=video=>{try{if(typeof video.getVideoPlaybackQuality==='function'){const n=Number(video.getVideoPlaybackQuality().totalVideoFrames);if(Number.isFinite(n))return {supported:true,value:n};}if(typeof video.webkitDecodedFrameCount==='number'&&Number.isFinite(video.webkitDecodedFrameCount))return {supported:true,value:video.webkitDecodedFrameCount};}catch(_){}return {supported:false,value:0};};
          if(state.samples.some(sample=>!visible(sample.video)||sample.video.ended||(sample.video.paused&&!sample.video.autoplay&&!live(sample.video))))return -1;
          const progress=state.samples.map(sample=>{const now=frames(sample.video);if(sample.frames.supported){if(!now.supported)return null;return now.value>sample.frames.value;}return (Number(sample.video.currentTime)||0)>sample.time+0.05;});
          if(progress.some(value=>value===null))return -1;
          return progress.every(Boolean)?0:1;
        }catch(_){return -1;}})()
        """.trimIndent()
}

internal fun javascriptIntResult(result: String?): Int? =
    result?.trim()?.removeSurrounding("\"")?.toIntOrNull()

/**
 * Keep top-level navigation on the configured HA authority. An HTTP configuration may upgrade to HTTPS
 * when both URLs use scheme defaults or preserve the same explicit port. An HTTPS configuration never
 * downgrades to HTTP: cleartext content must not inherit the renderer's external-auth bridge.
 */
internal fun dashboardNavigationAllowed(configuredUrl: String, candidateUrl: String): Boolean = runCatching {
    val configured = URI(configuredUrl.trim())
    val candidate = URI(candidateUrl.trim())
    val configuredScheme = configured.scheme?.lowercase() ?: return@runCatching false
    val candidateScheme = candidate.scheme?.lowercase() ?: return@runCatching false
    if (configuredScheme !in setOf("http", "https") || candidateScheme !in setOf("http", "https")) return@runCatching false
    if (!configured.host.equals(candidate.host, ignoreCase = true)) return@runCatching false
    if (configuredScheme == "https" && candidateScheme != "https") return@runCatching false
    if (configuredScheme == candidateScheme) {
        fun effectivePort(uri: URI, scheme: String): Int =
            if (uri.port >= 0) uri.port else if (scheme == "https") 443 else 80
        effectivePort(configured, configuredScheme) == effectivePort(candidate, candidateScheme)
    } else {
        (configured.port < 0 && candidate.port < 0) ||
            (configured.port >= 0 && configured.port == candidate.port)
    }
}.getOrDefault(false)

/**
 * A Home Assistant URL is configured but no credential is: the built-in renderer cannot render yet, but
 * it CAN run the on-panel sign-in that produces the missing credential.
 *
 * Deliberately a sibling of `Config.builtInRendererReady()` rather than a relaxation of it. That
 * predicate means "can render right now" and is load-bearing for `MainActivity`, `LaunchScreenPolicy` and
 * `PostUpdateReturnPolicy`; widening it would let the launcher hand off to a renderer with no credential
 * and strand the panel. This is strictly narrower — every state that stranded before still strands — and
 * it exists so the one state that can make progress on its own is allowed to.
 *
 * Without it the panel deadlocks on first run: saving a URL relaunches DashboardActivity, the readiness
 * gate rejects it for want of a token, and it bounces to the QR screen — while the only screen that can
 * obtain that token lives behind the same gate. The user sees repeated reloads and never a login.
 */
internal fun haSignInPending(haUrl: String, haToken: String, haRefreshToken: String): Boolean =
    haUrl.isNotBlank() && haToken.isBlank() && haRefreshToken.isBlank()

/**
 * Whether the built-in renderer must hold its first load until the entity-filter question is answered.
 *
 * A first load against an unfiltered Home Assistant is slow and laggy on a weak panel — measured at 94% p95
 * renderer CPU and touch responses ~60% slower on a Cortex-A35 panel against 3,769 entities — and it is the
 * first thing a new owner ever sees. Loading it and then offering to fix it inverts the order that matters:
 * the bad impression has already been made. So the panel waits on its existing pre-render surface, which is
 * a screen it is already showing safely, rather than loading something it will immediately have to reload.
 *
 * Narrow on purpose, and only ever a delay:
 *  - it applies to the built-in renderer alone, since a foreign renderer's subscription is not ours to narrow;
 *  - it requires a credential, so it can never mask the sign-in states [haSignInPending] handles;
 *  - it ends on an explicit answer, either way, so declining still gets a dashboard.
 *
 * A panel that was already configured before this question existed reports answered, so an upgrade never
 * stops rendering — the hold is for panels being set up, not for panels already working.
 */
internal fun entityFilterQuestionPending(
    builtinRenderer: Boolean,
    haUrl: String,
    haToken: String,
    haRefreshToken: String,
    entityFilterAnswered: Boolean,
    setupEverCompleted: Boolean = false,
    entityFilterEnabled: Boolean = false,
): Boolean = builtinRenderer &&
    // The filter being ON is self-evident proof the question is moot: it cannot be true on a fresh panel
    // (the setting defaults off) and it is true on every panel that has ever been through this. Derived at
    // the moment of the check rather than trusted from a flag written once at startup — which is what failed.
    // Panels could be stranded on the hold screen with the filter already enabled, because the
    // one-shot migration below had run at a moment when the configuration read back blank and so recorded
    // "not a pre-existing install". A durable fact about the panel beats a flag captured at one instant.
    !entityFilterEnabled &&
    // A panel that has ALREADY finished setup is never held, whatever the answer flag says. The flag is new,
    // so it defaults false on every panel that upgrades — and without this an upgrade stops a working panel
    // from showing its dashboard to ask a question it was never asked before. Found exactly that way, on two
    // configured panels, by the synthetic canary. Holding a first render is protecting a first impression;
    // holding a panel that already had one is a regression, and an optimisation question never justifies it.
    !setupEverCompleted &&
    !entityFilterAnswered &&
    haUrl.isNotBlank() &&
    (haToken.isNotBlank() || haRefreshToken.isNotBlank())

internal fun panelHaOAuthStartUrl(haUrl: String): String =
    "http://127.0.0.1:8888/api/v1/ha/oauth/panel-start?ha_url=${URLEncoder.encode(haUrl.trim().trimEnd('/'), "UTF-8")}"

internal fun panelHaOAuthNavigationAllowed(configuredUrl: String, candidateUrl: String): Boolean =
    dashboardNavigationAllowed(configuredUrl, candidateUrl) || isPanelHaOAuthCallback(candidateUrl)

internal fun isPanelHaOAuthCallback(candidateUrl: String): Boolean = runCatching {
    val uri = URI(candidateUrl.trim())
    val scheme = uri.scheme?.lowercase() ?: return@runCatching false
    val host = uri.host?.lowercase() ?: return@runCatching false
    scheme == "http" &&
        host in setOf("127.0.0.1", "localhost") &&
        (uri.port == -1 || uri.port == 8888) &&
        uri.path == HA_OAUTH_CALLBACK_PATH
}.getOrDefault(false)

/**
 * Exact document origins that may inherit panel startup scripts. This mirrors [dashboardNavigationAllowed]:
 * HTTPS is restricted to its configured origin, while an HTTP configuration also admits only the same-host
 * HTTPS upgrade using the same explicit port (or the HTTPS default when neither URL has one).
 */
internal fun dashboardDocumentStartOrigins(configuredUrl: String): Set<String> {
    val configured = URI(configuredUrl.trim())
    val configuredOrigin = EntityFilterProtocol.origin(configuredUrl)
    if (!configured.scheme.equals("http", ignoreCase = true)) return setOf(configuredOrigin)
    val https = URI(
        "https",
        null,
        configured.host?.lowercase() ?: throw IllegalArgumentException("ha_url must contain a host"),
        configured.port,
        null,
        null,
        null,
    )
    return linkedSetOf(configuredOrigin, EntityFilterProtocol.origin(https.toString()))
}

/** Distinguishes the registration-time [android.net.ConnectivityManager.NetworkCallback.onAvailable]
 * from a network that genuinely became available after the renderer started offline. */
internal class NetworkRecoveryGate(initiallyAvailable: Boolean) {
    private var reloadOnAvailable = !initiallyAvailable

    fun onLost() {
        reloadOnAvailable = true
    }

    fun onAvailable(): Boolean {
        val reload = reloadOnAvailable
        reloadOnAvailable = false
        return reload
    }
}

/** Retry cadence for a frontend that has not connected yet. A live dashboard gets a longer grace
 * period so HA can heal a brief websocket flap without a disruptive full-page reload. */
internal class DashboardRetryPolicy(
    private val initialRetryMs: Long = 5_000L,
    private val maxRetryMs: Long = 60_000L,
    private val connectedGraceMs: Long = 90_000L,
) {
    private var retryMs = initialRetryMs

    fun connectionFailureDelay(wasConnected: Boolean): Long =
        if (wasConnected) connectedGraceMs else retryMs

    /** Called when a retry reload actually fires; returns the deadline for that new attempt. */
    fun afterRetry(): Long {
        retryMs = (retryMs * 2).coerceAtMost(maxRetryMs)
        return retryMs
    }

    fun reset() {
        retryMs = initialRetryMs
    }
}

/** How a blocked renderer-admission verdict may recover on its own. Classified by the caller so the
 * policy stays pure: [FROM_BASE] backs off exponentially toward the ceiling (transport faults, where
 * the server's return should be noticed quickly), [AT_CEILING] probes only at the ceiling cadence
 * (verdicts only a server-side repair can change — re-enabled users, restored dashboards),
 * [MANUAL_ONLY] never arms (a wrong HA version or a missing WebView capability is not time-driven). */
internal enum class AdmissionRetryClass { FROM_BASE, AT_CEILING, MANUAL_ONLY }

/**
 * What the panel actually learned when renderer admission was blocked. Every blocked screen names one
 * of these, so recovery policy is decided in one place instead of screen by screen.
 */
internal enum class AdmissionOutcome {
    /** The check never completed: certificate, DNS, timeout or 5xx while reaching Home Assistant. */
    TRANSPORT_FAILED,

    /** The signed-in account's dashboard list could not be read. */
    DASHBOARD_LIST_UNREADABLE,

    /** The panel's own sign-in page did not load; its server restarts during reconfigures. */
    SIGN_IN_PAGE_UNREACHABLE,

    /** Home Assistant loaded but never posted the V2 handshake. The WebView is capable and the page is
     *  reachable, so a later load can still complete it — a missed exchange, not a missing capability. */
    BRIDGE_HANDSHAKE_MISSED,

    /** Home Assistant answered with a version string that cannot be parsed — a degraded proxy answer or
     *  a prerelease build. An answer arrived, but it settles nothing. */
    VERSION_UNVERIFIABLE,

    /** The server refused a credential the panel actually holds. Repeating the unchanged credential
     *  can trigger Home Assistant login-attempt banning, so recovery requires an explicit retry or a
     *  new credential rather than an unattended timer. */
    CREDENTIAL_REFUSED,

    /** No credential is configured at all. There is nothing to re-ask WITH, so a timer would repeat an
     *  empty request forever; connecting the panel relaunches admission immediately. */
    SIGN_IN_REQUIRED,

    /** The signed-in account can reach no legal dashboard. */
    NO_LEGAL_DASHBOARD,

    /** Home Assistant is older than the supported floor. */
    UNSUPPORTED_HA,

    /** This Android System WebView cannot provide the secure bridge at all — the capability itself is
     *  absent, which no amount of asking again will change. */
    BRIDGE_UNAVAILABLE,

    /** The WebView advertises the capability but attaching, installing or retaining the bridge threw.
     *  That is a failed setup on a capable provider — a provider update or process death mid-session
     *  looks exactly like this — and creating a fresh WebView can succeed, so it is not terminal. */
    BRIDGE_ATTACH_FAILED,
}

/**
 * The recovery rule, decided by one question asked in two parts: **can this change without anyone
 * touching the panel, and if so will the panel ever be told?**
 *
 * - **An incomplete check** — the panel could not reach the server, could not read what it asked for,
 *   or got nothing it can act on. It knows nothing yet, the situation usually clears quickly, and
 *   noticing promptly is the whole point: fast ladder.
 * - **A definitive answer that only the server can change, with no event to carry the news** — an
 *   unusable version string, and an account that can currently reach no dashboard. Creating a
 *   dashboard or fixing a degraded response happens entirely server-side and nothing tells the panel,
 *   so asking again slowly is the only way it will ever find out. Ceiling cadence, never the fast
 *   ladder: these are not urgent, and a parked panel that never asks again is the defect this whole
 *   change exists to remove.
 * - **A definitive answer the panel will learn about by event, or that a person must act on** — an
 *   ABSENT credential is repaired by connecting the panel, which relaunches admission immediately, and
 *   there is nothing to re-ask with meanwhile; an unsupported server and an incapable WebView are
 *   maintainer-designated terminal outcomes. No timer.
 *
 * A credential the server REFUSED sits in the second group, not the third, and the distinction is the
 * whole reason this rule is asked in two parts. Re-enabling an HA user, reissuing a token or repairing
 * a reverse proxy that was answering 401 all happen server-side with no event reaching the panel, so a
 * refusal that never re-asks stays parked indefinitely. That failure mode can also blame a credential
 * that was never actually refused.
 *
 * Note the pairs that look alike and are not: a WebView that cannot bridge at all is terminal, while
 * one that failed to attach the bridge it does support is retried; a handshake Home Assistant never
 * completed is retried, while a version it reported as too old is not.
 *
 * A timer is therefore absent only where it would add nothing, never merely because the answer sounded
 * final. Every retry runs the full admission sequence, which invalidates any cached resolution first,
 * so a slow probe genuinely re-asks rather than replaying the answer that blocked the panel.
 */
internal fun admissionRetryClass(outcome: AdmissionOutcome): AdmissionRetryClass = when (outcome) {
    AdmissionOutcome.TRANSPORT_FAILED,
    AdmissionOutcome.DASHBOARD_LIST_UNREADABLE,
    AdmissionOutcome.SIGN_IN_PAGE_UNREACHABLE,
    AdmissionOutcome.BRIDGE_HANDSHAKE_MISSED,
    AdmissionOutcome.BRIDGE_ATTACH_FAILED,
    -> AdmissionRetryClass.FROM_BASE

    AdmissionOutcome.VERSION_UNVERIFIABLE,
    AdmissionOutcome.NO_LEGAL_DASHBOARD,
    -> AdmissionRetryClass.AT_CEILING

    AdmissionOutcome.SIGN_IN_REQUIRED,
    AdmissionOutcome.CREDENTIAL_REFUSED,
    AdmissionOutcome.UNSUPPORTED_HA,
    AdmissionOutcome.BRIDGE_UNAVAILABLE,
    -> AdmissionRetryClass.MANUAL_ONLY
}

/**
 * Which package currently provides this panel's WebView, as resolved through
 * `WebViewCompat.getCurrentWebViewPackage`.
 *
 * **What counts as a provider identity change, and what deliberately does not.** The identity is the
 * whole triple, but the only part this panel can act on is [packageName], and the version fields exist
 * to say WHICH build a decision was taken against rather than to take it. Two facts force that:
 *
 *  - **The resolvable version is stale by construction once a provider is loaded.** The platform call
 *    answers with the provider loaded INTO THIS PROCESS when there is one, falling back to the system's
 *    current choice only when nothing has been loaded. A process that has already bound an engine
 *    therefore keeps reporting the build it bound, however many times the APK on disk is replaced — so
 *    "the version changed" is a signal this panel structurally cannot observe from inside, and treating
 *    its absence as "nothing happened" would ignore exactly the event worth acting on.
 *  - **A repair is a same-version install.** Reinstalling the identical build is the documented remedy
 *    for a damaged WebView, so a rule keyed on a version difference would sit out the case the screen
 *    itself tells the user to try.
 *
 *  A replace preserves the package name, which is what makes the surviving half sufficient: the install
 *  event names the package, and the question "is that the package that provides our WebView?" is
 *  answerable from a pinned identity and a fresh one alike.
 */
internal data class WebViewProviderIdentity(
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
) {
    /** For the log line that records which build a rebind was decided against. Never a path or a host. */
    fun describe(): String = "$packageName ${versionName ?: "?"} ($versionCode)"
}

/**
 * Whether a blocked admission verdict is one that installing, updating or repairing the WebView
 * provider can actually repair.
 *
 * Exhaustive on purpose, exactly like [admissionRetryClass]: a new outcome must be given an answer here
 * rather than inheriting `false` from an `else`, because inheriting silently is how the terminal-screen
 * decision this rule exists to soften gets re-made by accident.
 *
 * Only [AdmissionOutcome.BRIDGE_UNAVAILABLE] qualifies. It is the one verdict that is BOTH about the
 * provider's capability and never armed a timer, so nothing else in the panel will ever ask again.
 * [AdmissionOutcome.BRIDGE_ATTACH_FAILED] was considered and deliberately excluded: it describes a
 * capable provider whose bridge failed to attach, it already recovers on the fast ladder, and pointing
 * a process boundary at a screen that is recovering on its own is a larger behaviour change than this
 * rule's premise supports. Everything else — transport, credentials, dashboards, server version — is
 * untouched, so network and Home Assistant-version recovery keep the classification [admissionRetryClass]
 * gives them.
 */
internal fun providerRepairableAdmission(outcome: AdmissionOutcome): Boolean = when (outcome) {
    AdmissionOutcome.BRIDGE_UNAVAILABLE -> true

    AdmissionOutcome.TRANSPORT_FAILED,
    AdmissionOutcome.DASHBOARD_LIST_UNREADABLE,
    AdmissionOutcome.SIGN_IN_PAGE_UNREACHABLE,
    AdmissionOutcome.BRIDGE_HANDSHAKE_MISSED,
    AdmissionOutcome.BRIDGE_ATTACH_FAILED,
    AdmissionOutcome.VERSION_UNVERIFIABLE,
    AdmissionOutcome.NO_LEGAL_DASHBOARD,
    AdmissionOutcome.CREDENTIAL_REFUSED,
    AdmissionOutcome.SIGN_IN_REQUIRED,
    AdmissionOutcome.UNSUPPORTED_HA,
    -> false
}

/** What a package install event means for a panel parked on a provider-repairable admission screen.
 *  Every declining answer names its own reason so the log says why nothing happened. */
internal enum class WebViewRebindDecision {
    /** Bind the newly installed provider — which only a fresh process can do. */
    REBIND,

    /** Not an install event this rule observes. */
    UNRELATED_ACTION,

    /** The added-package half of a replace; the replaced-package broadcast owns the same event. */
    DUPLICATE_INSTALL,

    /** No live renderer generation is parked on a verdict a provider change could repair. */
    NOT_BLOCKED,

    /** Something was installed, but it is not the package that provides this panel's WebView. */
    OTHER_PACKAGE,

    /** Nothing resolves as the WebView provider, so there is no engine to rebind to. */
    NO_PROVIDER,
}

/**
 * Decide whether a package install should hand the panel a fresh WebView engine.
 *
 * **Why a re-check cannot be the answer, and a new process must be.** A WebView provider binds once per
 * process — the same fact `PaneldService.activateWebView` already restarts on after ha-paneld installs an
 * engine itself. The capability verdict behind "Secure dashboard bridge unavailable" is read from the
 * bound engine's feature set, which is resolved once and held for the life of the process, so asking
 * again in the same process returns the same answer no matter what has since been installed on disk.
 * That is also why the manual Retry button cannot clear this particular screen. The only re-run that can
 * produce a different verdict is one that happens after a fresh bind, so the recovery this decides is a
 * gated process boundary and the admission sequence then runs normally on the other side of it.
 *
 * Android usually gets there first: replacing a provider kills the processes that have bound it, and a
 * START_STICKY service comes straight back. This rule covers what that leaves — a provider ARRIVING
 * rather than being replaced, and OEM builds whose update service does not kill dependents — so it is a
 * second route to the same place, never the only one.
 *
 * [resolveProvider] is a function rather than a value so that the cheap, panel-local questions are asked
 * first: an unrelated app updating in the background must not cost a provider lookup, and a test can
 * prove that it did not.
 */
internal fun webViewRebindDecision(
    action: String?,
    changedPackage: String?,
    replacingExistingInstall: Boolean,
    blockedOutcome: AdmissionOutcome?,
    resolveProvider: () -> WebViewProviderIdentity?,
): WebViewRebindDecision {
    if (action != Intent.ACTION_PACKAGE_ADDED && action != Intent.ACTION_PACKAGE_REPLACED) {
        return WebViewRebindDecision.UNRELATED_ACTION
    }
    // Android announces a replace twice: once as a removal/addition pair carrying EXTRA_REPLACING, and
    // once as ACTION_PACKAGE_REPLACED. Answering the addition as well would decide the same install
    // twice; the replaced broadcast is the one that is only ever sent once per event.
    if (action == Intent.ACTION_PACKAGE_ADDED && replacingExistingInstall) return WebViewRebindDecision.DUPLICATE_INSTALL
    if (blockedOutcome == null || !providerRepairableAdmission(blockedOutcome)) return WebViewRebindDecision.NOT_BLOCKED
    val provider = resolveProvider() ?: return WebViewRebindDecision.NO_PROVIDER
    if (changedPackage.isNullOrBlank() || changedPackage != provider.packageName) return WebViewRebindDecision.OTHER_PACKAGE
    return WebViewRebindDecision.REBIND
}

/**
 * The restart owner for a WebView provider rebind: single-flight, deferred while the panel is busy, and
 * **abandoned only when the service itself is going away.**
 *
 * The abandon condition is the whole point of this being written down. An earlier revision also
 * abandoned when the renderer's blocking verdict was no longer visible, reasoning that a restart
 * scheduled behind a running install should not land on a panel that had since recovered. That was a
 * category error, and a costly one: the verdict is read again on a DELAY, and it is legitimately
 * invisible for a while whenever Android is replacing the activity —
 * [io.github.maxlyth.hapaneld.control.BuiltinDashboard.acquireActivityOwner] retires the previous
 * generation's record immediately, and the replacement writes nothing until `buildAndLoad` runs. The
 * absence of a verdict is the absence of evidence, not evidence of recovery, and treating it as the
 * latter discarded the single package event that the whole rule exists to act on — permanently, because
 * no second broadcast is coming.
 *
 * There is also nothing this process could ever observe that WOULD prove the rebind unnecessary, which
 * is the same fact the rule is built on: the capability verdict is frozen for the life of the process,
 * so a renderer that answered "no secure bridge" cannot answer anything else here, whatever screen it is
 * showing meanwhile. A branch for that state would be a branch that never runs.
 *
 * So the cost is accepted explicitly instead: if the panel has moved to an external renderer, or the
 * dashboard activity is gone, by the time the boundary is safe to take, ha-paneld restarts its own
 * process once for nothing — START_STICKY brings it straight back, a foreign renderer is a different
 * process and never notices, and single-flight bounds it to one. That is a blip on a panel whose
 * dashboard was already down, weighed against silently losing its only route back.
 *
 * The screen condition still gates the decision — it is asked synchronously in the broadcast receiver,
 * where the verdict is on the display in front of somebody and cannot be transiently absent.
 */
internal fun webViewRebindRestartCoordinator(
    schedule: (Long, () -> Unit) -> Boolean,
    restartProcess: () -> Unit,
    destructiveOperationRunning: () -> Boolean,
    guidedSetupBeingWalked: () -> Boolean,
    serviceStopping: () -> Boolean,
): ProfileRestartCoordinator = ProfileRestartCoordinator(
    schedule = schedule,
    restartProcess = restartProcess,
    // Reasons to WAIT, never reasons to give up: both lapse on their own, so the retry terminates.
    safeToRestart = { !destructiveOperationRunning() && !guidedSetupBeingWalked() },
    shouldAbandon = serviceStopping,
)

/**
 * Retry cadence for blocked renderer-admission screens. A wall panel has nobody standing at it to
 * press Retry, so a blocked screen with no timer turns a server-side outage that has already been
 * repaired into a panel outage that lasts until someone walks to the device. Exponential back-off
 * with a ceiling measured in minutes, plus symmetric jitter so many panels recovering from one shared
 * server event do not retry in lockstep against a service that is just getting back up. The jittered
 * delay is computed exactly once per arm; the caller counts down to the same number it armed.
 */
internal class AdmissionRetryPolicy(
    private val baseMs: Long = 5_000L,
    private val ceilingMs: Long = 300_000L,
    private val jitterFraction: Double = 0.2,
    private val jitterSource: () -> Double = { Math.random() },
) {
    init {
        require(baseMs in 1..ceilingMs)
        require(jitterFraction in 0.0..0.5)
    }

    private var nextUnjitteredMs = baseMs

    /** The delay to arm now, jittered, or null when the class never retries automatically. Only
     *  [AdmissionRetryClass.FROM_BASE] advances the ladder; a ceiling-cadence arm leaves it alone. */
    fun nextDelayMs(retryClass: AdmissionRetryClass): Long? {
        val unjittered = when (retryClass) {
            AdmissionRetryClass.MANUAL_ONLY -> return null
            AdmissionRetryClass.AT_CEILING -> ceilingMs
            AdmissionRetryClass.FROM_BASE -> nextUnjitteredMs.also {
                nextUnjitteredMs = (nextUnjitteredMs * 2).coerceAtMost(ceilingMs)
            }
        }
        val offset = (jitterSource().coerceIn(0.0, 1.0) * 2.0 - 1.0) * unjittered * jitterFraction
        return (unjittered + offset.toLong()).coerceAtLeast(1_000L)
    }

    fun reset() {
        nextUnjitteredMs = baseMs
    }
}

/**
 * Owns *when the visible countdown may work*, separately from the retry it describes. Pure and
 * clock-injected so the whole lifecycle — arm, tick, lose visibility, regain it, disarm — is
 * executable in tests rather than asserted by reading the activity's source.
 *
 * The rule it enforces: the retry deadline survives everything, because an unattended panel is exactly
 * the one that must still recover; only the per-second repaint is suspended, and it is suspended
 * whenever the screen is not actually in front of somebody. "In front of somebody" is top-visibility,
 * not merely resumed: a translucent Overview on Android 10+ can take top-resumed status without ever
 * calling `onPause`, and repainting behind it is invisible work.
 */
internal class AdmissionCountdownOwner(private val nowMs: () -> Long) {
    private var deadlineMs = 0L
    private var visible = false

    /** What the caller should do after any state change. */
    data class Paint(val text: String?, val scheduleNextTickMs: Long?, val remainingMs: Long? = null)

    val armed: Boolean get() = deadlineMs != 0L

    fun arm(delayMs: Long): Paint {
        deadlineMs = nowMs() + delayMs
        return paint()
    }

    fun disarm() {
        deadlineMs = 0L
    }

    /**
     * The INSTANT the pending retry is due, or null when nothing is armed.
     *
     * Deliberately an instant rather than a remaining duration. A caller that must disarm in order to
     * replace the screen re-arms this value through [rearmAt], and the time its redraw takes falls
     * between the read and the re-arm: carrying a duration would silently add that time to the
     * deadline on every repaint, so a panel being rotated would walk its own recovery away from itself.
     */
    val deadlineAtMs: Long? get() = if (armed) deadlineMs else null

    /** Restore an existing deadline unchanged. Unlike [arm] this takes the absolute instant, so the
     *  retry lands exactly where it already did no matter how long the caller took to get here. */
    fun rearmAt(instantMs: Long): Paint {
        deadlineMs = instantMs
        return paint()
    }

    /** Top-visibility changed. Returning to visible reconciles immediately, so the first thing seen is
     *  the true remaining time rather than a stale figure or a blank. */
    fun onVisibilityChanged(nowVisible: Boolean): Paint {
        visible = nowVisible
        return paint()
    }

    fun onTick(): Paint = paint()

    /** Null text means "do not repaint"; null schedule means "do not run again until something changes". */
    private fun paint(): Paint {
        if (!armed || !visible) return Paint(null, null)
        val remaining = deadlineMs - nowMs()
        return Paint(admissionRetryCountdown(remaining), if (remaining > 0L) COUNTDOWN_TICK_MS else null, remaining)
    }

    private companion object { const val COUNTDOWN_TICK_MS = 1_000L }
}

/**
 * Which lifecycle callback owns countdown visibility on this Android tier.
 *
 * `onTopResumedActivityChanged` exists from API 29 and is the precise signal — a translucent Overview
 * can take top-resumed status without pausing the activity. Below 29 it is never delivered at all, so
 * resume/pause must own visibility there or the countdown would stay blank forever while the retry it
 * describes keeps firing invisibly.
 */
internal fun resumeOwnsAdmissionVisibility(sdkInt: Int): Boolean = sdkInt < 29

/** Countdown copy for an armed admission retry — a real number, not a spinner. Ceils so the text
 *  never reads 0 while the retry is still pending. */
internal fun admissionRetryCountdown(remainingMs: Long): String {
    val totalSec = (remainingMs.coerceAtLeast(0L) + 999L) / 1_000L
    val min = totalSec / 60
    val sec = totalSec % 60
    return if (min > 0) "Retrying automatically in ${min}m ${sec}s" else "Retrying automatically in ${sec}s"
}

/** 0..950 launch-progress scale. Never claims completion: the real completion signal is Android's
 * network callback, after which the launch view is immediately replaced by the dashboard. */
internal fun networkWaitProgress(elapsedMs: Long, estimateMs: Long): Int {
    if (estimateMs <= 0L) return 0
    return ((elapsedMs.coerceAtLeast(0L) * 1_000L) / estimateMs).coerceAtMost(950L).toInt()
}

internal data class StartupNetworkSnapshot(
    val interfacePresent: Boolean,
    val linkUp: Boolean,
    val addressAssigned: Boolean,
    val defaultNetwork: Boolean,
)

/** User-facing network phase. Deliberate line breaks keep every state balanced on a 480px square. */
internal fun startupNetworkStage(s: StartupNetworkSnapshot): String = when {
    !s.interfacePresent -> "Starting Android network services"
    !s.linkUp -> "Waiting for a network link"
    !s.addressAssigned -> "Network link connected\nWaiting for a network address"
    !s.defaultNetwork -> "Network address received\nPreparing the connection"
    else -> "Network ready\nOpening Home Assistant"
}

/**
 * How long to wait before the next entity-bootstrap watchdog resync.
 *
 * The hold's commonest cause is that Home Assistant is not up yet, so the recovery has to keep being
 * offered rather than fired once and latched. Widening from [baseMs] and capping at [ceilingMs] keeps a
 * long outage cheap while still converging within [ceilingMs] of HA answering.
 *
 * The step count is bounded and the result is range-checked because `shl` on a Long takes only the low
 * six bits of its operand: an unbounded attempt count would wrap the shift and collapse the backoff
 * straight back to [baseMs] after 64 attempts, which a multi-hour outage can reach.
 */
internal fun entityBootstrapRetryDelayMs(attempt: Int, baseMs: Long, ceilingMs: Long): Long {
    if (attempt <= 1) return baseMs.coerceAtMost(ceilingMs)
    val widened = baseMs shl (attempt - 1).coerceAtMost(32)
    return if (widened <= 0L || widened > ceilingMs) ceilingMs else widened
}

/** What the entity-bootstrap watchdog should do on one poll of the held renderer. */
internal enum class EntityBootstrapWatchdogAction {
    /** Nothing: the hold is progressing, a scan is running, or no deadline is due. */
    IDLE,
    /** Fire the same full catalogue resync the manual sync endpoint provides. */
    RESYNC,
    /** Ask Home Assistant whether the held dashboard changed, without resyncing the catalogue. */
    PROBE,
    /** Stop impersonating progress: present the problem screen with its retry and disable actions. */
    PRESENT_PROBLEM,
}

/**
 * The entity-bootstrap watchdog as a clock-injected state machine.
 *
 * The hold is structurally forbidden from being terminal, but the recovery it offers must match the
 * reason for the hold. A scan that has not answered, or failed, is repaired by resyncing; the commonest
 * cause is Home Assistant not being up yet, so the resync keeps being offered on a widening interval.
 * A scan that HAS answered and is blocked only on a person's decision about a flagged dashboard rule is
 * a different state: no resync can supply that decision, and one panel performed 794 full catalogue
 * resyncs over 2.75 days against exactly this hold. In that state the watchdog never resyncs and never
 * gives up; it asks Home Assistant, on a much wider cadence, whether the dashboard itself changed, which
 * is the only external input that can change the answer without anybody at the panel.
 *
 * Every transition between the two states rebases the clocks, so the sync a late approval starts is
 * measured from the approval rather than from days ago, and the rerun latch inside the manager is never
 * primed by a watchdog resync landing on top of it. A running scan is never doubled.
 */
internal class EntityBootstrapWatchdog(
    private val retryMs: Long,
    private val retryCeilingMs: Long,
    private val problemMs: Long,
    private val probeMs: Long,
    private val probeCeilingMs: Long,
) {
    init {
        require(retryMs in 1..retryCeilingMs)
        require(probeMs in 1..probeCeilingMs)
        require(problemMs > 0L)
    }

    private var holdSinceMs = 0L
    private var nextRetryMs = 0L
    private var nextProbeMs = 0L
    private var decisionHeld = false

    /** Full resyncs fired since the clocks were last rebased. */
    var resyncs = 0
        private set

    /** Dashboard probes fired since the clocks were last rebased. */
    var probes = 0
        private set

    /** Past the give-up deadline the hold presents as a problem so the retry and disable actions appear. */
    var gaveUp = false
        private set

    /** True while the only thing holding the renderer is an unanswered decision about flagged rules. */
    val heldOnDecision: Boolean get() = decisionHeld

    /** The hold ended: forget everything, so the next hold starts its clocks afresh. */
    fun reset() {
        holdSinceMs = 0L
        rebase(0L)
        decisionHeld = false
    }

    /** A person pressed the retry action: measure from now and offer the full ladder again. */
    fun restart(nowMs: Long) {
        reset()
        holdSinceMs = nowMs
    }

    fun tick(
        nowMs: Long,
        blockingIssues: Int,
        problem: EntityBootstrapProblem?,
        syncRunning: Boolean,
    ): EntityBootstrapWatchdogAction {
        if (holdSinceMs == 0L) holdSinceMs = nowMs
        val decision = blockingIssues > 0 && problem == null
        if (decision != decisionHeld) {
            decisionHeld = decision
            holdSinceMs = nowMs
            rebase(nowMs)
        }
        if (decision) {
            if (syncRunning || nowMs < nextProbeMs) return EntityBootstrapWatchdogAction.IDLE
            probes += 1
            nextProbeMs = nowMs + entityBootstrapRetryDelayMs(probes + 1, probeMs, probeCeilingMs)
            return EntityBootstrapWatchdogAction.PROBE
        }
        val heldMs = nowMs - holdSinceMs
        if (!syncRunning && heldMs > retryMs && nowMs >= nextRetryMs) {
            resyncs += 1
            nextRetryMs = nowMs + entityBootstrapRetryDelayMs(resyncs, retryMs, retryCeilingMs)
            return EntityBootstrapWatchdogAction.RESYNC
        }
        if (heldMs > problemMs && !gaveUp) {
            gaveUp = true
            return EntityBootstrapWatchdogAction.PRESENT_PROBLEM
        }
        return EntityBootstrapWatchdogAction.IDLE
    }

    private fun rebase(nowMs: Long) {
        nextRetryMs = 0L
        // The first probe waits one full interval: a person answering within minutes of the hold, or a
        // scan the approval starts, must not race a question whose answer cannot yet have changed.
        nextProbeMs = if (nowMs == 0L) 0L else nowMs + probeMs
        resyncs = 0
        probes = 0
        gaveUp = false
    }
}
