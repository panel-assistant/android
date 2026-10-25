package io.panelassistant.android

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import io.panelassistant.android.control.BuiltinDashboard
import io.panelassistant.android.http.PaneldServer
import io.panelassistant.android.panelassistant.KtorPanelAssistantTransportConnector
import io.panelassistant.android.panelassistant.PanelAssistantCommandSink
import io.panelassistant.android.panelassistant.PanelAssistantHelloIdentity
import io.panelassistant.android.panelassistant.PanelAssistantStateReporter
import io.panelassistant.android.panelassistant.PanelAssistantTransportDemand
import io.panelassistant.android.panelassistant.PanelAssistantTransportOwner
import io.panelassistant.android.panelassistant.PanelAssistantVoice
import io.panelassistant.android.panelassistant.PanelAssistantVoiceStream
import io.panelassistant.android.panelassistant.panelAssistantTransportDemand
import io.panelassistant.android.sensors.DashboardHaApiSessionProvider
import io.panelassistant.android.sensors.HaExactEntityStreamOwner
import io.panelassistant.android.sensors.HaLifecycleCoordinator
import io.panelassistant.android.sensors.HaLifecycleRuntime
import io.panelassistant.android.sensors.HaLifecycleState
import io.panelassistant.android.sensors.HaNetworkPath
import io.panelassistant.android.sensors.HaNetworkPathMonitor
import io.panelassistant.android.sensors.HaNetworkPathRuntime
import io.panelassistant.android.sensors.IcmpEchoSource
import io.panelassistant.android.sensors.KtorHaAmbientTransport
import io.panelassistant.android.sensors.KtorHaExactEntityStreamTransport
import io.panelassistant.android.sensors.PathProbeMonitor
import io.panelassistant.android.sensors.PathProbeRuntime
import io.panelassistant.android.util.AddressFamilyPolicy
import io.panelassistant.android.util.AppInstaller
import io.panelassistant.android.util.ServiceRuntimeOwner
import kotlinx.coroutines.CoroutineScope

/**
 * The service's long-lived links to Home Assistant and their health: the exact-entity stream, the
 * native Panel Assistant transport and its demand, the layer-3 path probe, the network-path monitor
 * and the Home Assistant lifecycle coordinator. Built once, after configuration and before the HTTP
 * server; [installRuntimeHolders] publishes them when the runtime starts and [close] retires them.
 *
 * [runtime] and [server] are read when used, never captured: both are built after this owner.
 */
internal class ServiceHaLinks(
    private val context: Context,
    private val scope: CoroutineScope,
    private val config: Config,
    private val teardownBoundary: ServiceTeardownBoundary,
    panelAssistantReporter: PanelAssistantStateReporter,
    panelAssistantCommands: PanelAssistantCommandSink,
    panelAssistantVoice: PanelAssistantVoice,
    voiceStream: PanelAssistantVoiceStream,
    private val runtime: () -> ServiceRuntimeOwner<PaneldService.NetworkRuntime>,
    private val server: () -> PaneldServer,
) {
    val haSocketClock: () -> Long = { android.os.SystemClock.elapsedRealtime() }
    val haSessionAuthority: DashboardHaApiSessionProvider
    val haApi: KtorHaAmbientTransport
    lateinit var haPathProbe: PathProbeMonitor
        private set
    lateinit var haExactEntityStream: HaExactEntityStreamOwner
        private set
    lateinit var panelAssistantTransport: PanelAssistantTransportOwner
        private set
    lateinit var haLifecycle: HaLifecycleCoordinator
        private set
    lateinit var haNetworkPath: HaNetworkPathMonitor
        private set

    init {
        haSessionAuthority = DashboardHaApiSessionProvider(config)
        haApi = KtorHaAmbientTransport()
        // The layer-3 probe is constructed before the transport so the transport can report the
        // address each route connects on straight to it. It measures the path the dashboard is
        // actually using; a fresh resolution could name a family the connect race already rejected.
        haPathProbe = PathProbeMonitor(
            source = IcmpEchoSource(),
            // The layer-3 verdict goes straight to the one owner of the prominent warning, so a
            // measured path fault raises the banner and the panel chip rather than sitting in a
            // diagnostics line nobody reads until they are already debugging.
            // Guarded: the probe is constructed first, and a diagnostic must never be the thing
            // that crashes the service it is diagnosing.
            onVerdict = { severity, cause ->
                if (::haNetworkPath.isInitialized) haNetworkPath.onPathProbeVerdict(severity, cause)
            },
        )
        haExactEntityStream = HaExactEntityStreamOwner(
            scope = scope,
            auth = haSessionAuthority,
            transport = KtorHaExactEntityStreamTransport(
                haApi,
                socketFamilyPolicy = { AddressFamilyPolicy.fromConfig(config.addressFamily) },
                monotonicMillis = haSocketClock,
                onRouteConnected = { address, otherResolved -> haPathProbe.onRouteConnected(address, otherResolved) },
            ),
            monotonicMillis = haSocketClock,
        )
        // The native transport's own long-lived socket, on the same credential authority as the stream
        // above and the same address-family policy as every other Home Assistant socket.
        panelAssistantTransport = PanelAssistantTransportOwner(
            scope = scope,
            auth = haSessionAuthority,
            connector = KtorPanelAssistantTransportConnector(
                socketFamilyPolicy = { AddressFamilyPolicy.fromConfig(config.addressFamily) },
            ),
            monotonicMillis = haSocketClock,
            addresses = {
                val primary = runCatching {
                    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                    cm?.getLinkProperties(cm.activeNetwork)?.linkAddresses.orEmpty().map { it.address }
                }.getOrDefault(emptyList())
                io.panelassistant.android.util.localPanelAddresses(primary)
            },
            reporter = panelAssistantReporter,
            observeForHello = {
                val observed = runtime().observe() ?: return@PanelAssistantTransportOwner false
                observed.value.core.observeForNativeHello { runtime().isCurrent(observed) }
            },
            commands = panelAssistantCommands,
            management = io.panelassistant.android.panelassistant.PanelAssistantManagement.of(
                { server().managementSnapshot() },
                { settings, admit -> server().applyManagedSettings(settings, admit) },
                door = { server().manageDoor() },
                blobs = io.panelassistant.android.panelassistant.PanelAssistantBlobs(haSessionAuthority),
            ) { op, args, onPending -> server().managementRequest(op, args, onPending, haSessionAuthority) },
            onConnected = config::markPanelAssistantConnected,
            onLifecycleNotice = { notice -> haLifecycle.onNativeNotice(notice) },
            onLifecycleAuthenticated = { haLifecycle.onNativeAuthenticated() },
            onLifecycleDisconnected = { haLifecycle.onNativeDisconnected() },
            onLifecycleRetired = {
                haLifecycle.onNativeRetired()
                if (HaLifecycleRuntime.setNativeWatching(haLifecycle, false)) BuiltinDashboard.onHaLifecycleChanged()
            },
            voice = panelAssistantVoice,
            voiceStream = voiceStream,
            onTransportFailure = { route -> HaConnectionRoutes.failed(config, route) },
            onConnection = { session, accepted ->
                val route = session.route
                val bound = route != null && HaConnectionRoutes.isCurrent(config, route) &&
                    (accepted.connection?.let { HaConnectionRoutes.learn(config, route, it, accepted.entryId) } ?: true)
                if (bound) {
                    // Protocol section 20.8: the bound Panel Assistant's grant opens or closes the REST routes.
                    config.recordPanelAssistantGrant(accepted.capabilities)
                    // The device link is keyed on the learned entry, so a first or changed entry resolves it now.
                    runtime().observe()?.value?.core?.maybeResolveHaLink()
                }
                bound
            },
            checkPreferred = {
                val route = HaConnectionRoutes.preferredCandidate(config)
                if (route == null) false else {
                    val session = DashboardHaApiSessionProvider(config).resolve(false)
                    val token = session.accessToken
                    val authenticated = if (token != null && session.route == route) {
                        try {
                            val probe = KtorPanelAssistantTransportConnector { AddressFamilyPolicy.fromConfig(config.addressFamily) }.connect(route.owner.url, token)
                            probe.close()
                            true
                        } catch (_: Exception) { false }
                    } else false
                    HaConnectionRoutes.preferredResult(config, route, authenticated)
                }
            },
        )
        AppInstaller.livePanelUpdatePolicy = {
            if (teardownBoundary.isStopping) null else panelAssistantTransport.status.liveUpdatePolicy()
        }
        haLifecycle = HaLifecycleCoordinator(
            // elapsedRealtime, not wall clock: a Home Assistant restart is exactly when NTP is likely to
            // step the panel's clock, and the back-online window must not be shortened or extended by it.
            nowMs = { android.os.SystemClock.elapsedRealtime() },
            onChanged = {
                // Read the canonical snapshot rather than trusting a payload, so a diagnostic line
                // cannot report something the machine no longer holds.
                Log.i(TAG, "Home Assistant lifecycle: ${HaLifecycleRuntime.snapshot()?.state?.wireValue}")
                BuiltinDashboard.onHaLifecycleChanged()
            },
        )
        // The network-path monitor rides the same socket, the same clock and the same renderer poke.
        // Installed BEFORE it is bound, because binding announces the current demand at once and that
        // first verdict must already be readable by whoever the poke wakes.
        haNetworkPath = HaNetworkPathMonitor(
            nowMs = haSocketClock,
            // Second attribution signal: while Home Assistant has announced a shutdown or a start
            // over the Panel Assistant session, an unanswered probe is
            // the server's doing and must not be counted as loss. Read outside the monitor's lock.
            haRestarting = {
                when (HaLifecycleRuntime.snapshot()?.state) {
                    HaLifecycleState.SHUTTING_DOWN, HaLifecycleState.STARTING -> true
                    else -> false
                }
            },
            onChanged = { BuiltinDashboard.onHaLifecycleChanged() },
            // Startup is a known high-latency period whose figures describe this panel's own load
            // rather than the path, so observations inside it are discarded rather than judged. The
            // process start is read on the same monotonic clock every probe is stamped with.
            path = HaNetworkPath(processStartElapsedMs = android.os.Process.getStartElapsedRealtime()),
        )
    }

    /** Publish each holder before binding can announce its first state to a live renderer. */
    fun installRuntimeHolders() {
        HaNetworkPathRuntime.install(haNetworkPath)
        haExactEntityStream.bindNetworkPath(haNetworkPath)
        PathProbeRuntime.install(haPathProbe, haSocketClock)
        haExactEntityStream.bindPathProbe(haPathProbe)
        HaLifecycleRuntime.install(haLifecycle)
        BuiltinDashboard.onHaLifecycleChanged()
    }

    fun currentPanelAssistantTransportDemand(): PanelAssistantTransportDemand? {
        val auth = config.haAuthSnapshot()
        return panelAssistantTransportDemand(
            credential = auth.stableOwner(),
            accessTokenPresent = auth.accessToken.isNotBlank(),
            identity = PanelAssistantHelloIdentity(
                did = panelAssistantDiscoveryId(config.deviceUid),
                appVersion = BuildConfig.VERSION_NAME,
                appVersionCode = BuildConfig.VERSION_CODE,
                httpPort = config.httpPort,
            ),
        )?.copy(routeEpoch = config.haRouteEpoch())
    }

    /** Start, keep or stop native transport only on the successfully started, admitted service owner. */
    fun refreshPanelAssistantTransport() {
        if (!::panelAssistantTransport.isInitialized) return
        val demand = currentPanelAssistantTransportDemand()
        runtime().runIfRunning { publishDemand(demand) }
    }

    /** Hand the transport its demand and tell the renderer whether a native session now watches Home Assistant. */
    fun publishDemand(demand: PanelAssistantTransportDemand?) {
        if (!teardownBoundary.isStopping) {
            panelAssistantTransport.replaceDemand(demand)
            if (HaLifecycleRuntime.setNativeWatching(haLifecycle, demand != null)) BuiltinDashboard.onHaLifecycleChanged()
        }
    }

    /** Close the transport and the stream, then clear this generation's runtime holders by identity. */
    fun close(closeOwner: (String, () -> Unit) -> Unit) {
        if (::panelAssistantTransport.isInitialized) {
            closeOwner("Panel Assistant transport") { panelAssistantTransport.close() }
        }
        if (::haExactEntityStream.isInitialized) {
            closeOwner("HA exact entity stream") { haExactEntityStream.close() }
            // Identity-gated: a successor service may already have installed its own coordinator by
            // the time this (possibly deadline-late) teardown runs, and erasing it would blank the
            // successor's live tracking. Only when THIS service's installation was actually cleared
            // is the renderer poked, so a card rendering the dead state is re-read and hidden.
            if (HaLifecycleRuntime.uninstall(haLifecycle)) BuiltinDashboard.onHaLifecycleChanged()
            // Same identity gate for the network-path owner: a successor's installation survives a
            // late predecessor teardown, and only a real clearing re-pokes the chip to hide.
            if (::haNetworkPath.isInitialized && HaNetworkPathRuntime.uninstall(haNetworkPath)) {
                BuiltinDashboard.onHaLifecycleChanged()
            }
            // The echo probe is cleared under the same identity gate, and silently: it drives no
            // prominent surface, so nothing needs re-poking when it goes.
            if (::haPathProbe.isInitialized) PathProbeRuntime.uninstall(haPathProbe)
        }
    }

    fun announcePanelAssistantRestart(scope: String, reason: String, expectedBackMs: Long) {
        if (!::panelAssistantTransport.isInitialized) return
        // One bounded acknowledgement chance before an already accepted restart or reboot tears down
        // the socket. The notice never decides whether the restart itself may proceed.
        try {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(300L) {
                    panelAssistantTransport.announceRestart(scope, reason, expectedBackMs).await()
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "restart notice failed; continuing the accepted restart", error)
        }
    }

    private companion object {
        const val TAG = PaneldService.TAG
    }
}
