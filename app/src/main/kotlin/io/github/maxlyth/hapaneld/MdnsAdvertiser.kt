package io.github.maxlyth.hapaneld

import android.content.Context
import android.net.wifi.WifiManager
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import io.github.maxlyth.hapaneld.util.Json
import io.github.maxlyth.hapaneld.util.InstallPresentation
import io.github.maxlyth.hapaneld.util.MonotonicDeadline
import io.github.maxlyth.hapaneld.util.RetirableMutationGate
import io.github.maxlyth.hapaneld.util.interruptAndJoin
import io.github.maxlyth.hapaneld.util.localIpv4
import io.github.maxlyth.hapaneld.util.localIpv6
import io.github.maxlyth.hapaneld.util.shutdownNowAndAwait
import io.github.maxlyth.hapaneld.util.submit
import io.github.maxlyth.hapaneld.metrics.FeatureCostOperation
import io.github.maxlyth.hapaneld.metrics.FeatureCostOutcome
import io.github.maxlyth.hapaneld.metrics.FeatureCosts
import java.io.IOException
import java.net.InetAddress
import java.net.SocketException
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener
import javax.jmdns.impl.DNSOutgoing
import javax.jmdns.impl.JmDNSImpl

internal open class MdnsSendRetryDns(address: InetAddress, name: String) : JmDNSImpl(address, name) {
    protected open fun sendPacket(outgoing: DNSOutgoing) = super.send(outgoing)

    // JmDNS closes its whole responder if one response send throws. Android has returned a single
    // sendto EPERM on live panels; retry that packet once before allowing JmDNS's normal failure path.
    override fun send(outgoing: DNSOutgoing) {
        try {
            sendPacket(outgoing)
        } catch (failure: IOException) {
            var cause: Throwable? = failure
            var permissionDenied = false
            while (cause != null) {
                if ((cause is ErrnoException && cause.errno == OsConstants.EPERM) ||
                    (cause is SocketException && cause.message?.startsWith("sendto failed: EPERM") == true)
                ) {
                    permissionDenied = true
                    break
                }
                cause = cause.cause
            }
            if (!permissionDenied) throw failure
            sendPacket(outgoing)
        }
    }
}

/**
 * Advertises `_ha-paneld._tcp.local.` via JmDNS so HA's zeroconf discovery can auto-pair the
 * panel. JmDNS is used in preference to Android's NsdManager because NsdManager's TXT-record
 * handling is unreliable across the API 26→30 range the fleet spans. A WifiManager.MulticastLock
 * is held for the lifetime of the advertisement (panels otherwise drop multicast in doze).
 */
class MdnsAdvertiser(
    private val context: Context,
    private val config: Config,
    private val runtimePanelId: String = config.panelId,
    private val runtimeFriendlyName: String = config.friendlyName,
    private val runtimeHttpPort: Int = config.httpPort,
    // Acquires the Wi-Fi multicast lock and returns its release. Injectable so the responder can run
    // on a JVM, where there is no WifiManager.
    private val acquireMulticastLock: () -> () -> Unit = { acquireWifiMulticastLock(context) },
    private val discoveryId: () -> String? = { panelAssistantDiscoveryId(config.deviceUid) },
    // The production cadence is fixed; a shorter interval lets the real responder recovery run in a JVM test.
    private val refreshIntervalMs: Long = REFRESH_MS,
    private val createDns: (InetAddress, String) -> JmDNS = ::MdnsSendRetryDns,
) {
    private val ownerGate = RetirableMutationGate()
    private var jmdns: JmDNS? = null
    private var lock: (() -> Unit)? = null
    @Volatile private var browsing = false
    @Volatile private var refreshThread: Thread? = null
    @Volatile private var resolver: ThreadPoolExecutor? = null
    private val browseGeneration = AtomicLong()
    private val retirement = AtomicReference<CompletableFuture<Boolean>?>()
    // The LAN address JmDNS is actually bound to. A panel's IPv4 arrives asynchronously (DHCP) and can
    // change later, so [start] compares this against the live address before keeping an advertisement.
    @Volatile private var boundIp: String? = null
    // The latest address explicitly supplied by the default-network owner. Recovery may recreate only
    // this topology; process-wide interface enumeration is never authoritative on multi-homed panels.
    private val topology = MdnsTopology()
    @Volatile private var advertisedInstanceName: String? = null
    // A dual-stack panel's IPv6 responder. JmDNS answers only its bound address's family, so this second
    // instance carries the AAAA record. It only answers: the primary alone browses peers and carries the
    // liveness probe, so a dual-stack panel's IPv4 advertisement behaves exactly as an IPv4-only one.
    @Volatile private var secondaryDns: JmDNS? = null
    @Volatile private var secondaryBoundIp: String? = null
    private var advertisedProps: Map<String, String>? = null
    private val liveness = MdnsLivenessPolicy()
    private data class RecoveryRequest(
        val dns: JmDNS?,
        val generation: Long,
        val epoch: Long,
        val boundIp: String,
        val reason: String,
        val reservation: MdnsRecoveryReservation,
    )
    private val recoveryScheduler = LatestScheduledTask("ha-paneld-mdns-recovery")

    private fun newResolver() = ThreadPoolExecutor(
        RESOLVE_WORKERS, RESOLVE_WORKERS, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(RESOLVE_QUEUE),
        { task -> Thread(task, "mdns-resolve").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    private fun JmDNS?.isStopped(): Boolean = (this as? JmDNSImpl)?.let {
        it.isCanceling || it.isCanceled || it.isClosing || it.isClosed
    } == true

    // Live roster of discovered ha-paneld panels (keyed by mDNS instance name = panel_id), maintained by a
    // persistent [peerListener]. Reads are cheap + non-blocking and — unlike a one-shot dns.list, which
    // returns only what's in this JmDNS's cache within a short window (observed to miss panels a peer sees) —
    // this converges to the full fleet over the listener's periodic queries and self-prunes on service-removed.
    private val peerMap = ConcurrentHashMap<String, Peer>()

    private val peerListener = object : ServiceListener {
        override fun serviceAdded(event: ServiceEvent) {
            // Resolve OFF the JmDNS packet thread (getServiceInfo blocks) so we never stall mDNS processing.
            val dns = event.dns.takeIf { it === jmdns } ?: return
            val type = event.type
            val name = event.name
            val executor = resolver ?: return
            try {
                executor.execute {
                    runCatching { dns.getServiceInfo(type, name, RESOLVE_MS) }.getOrNull()?.let {
                        record(it, name, dns)
                    }
                }
            } catch (_: RejectedExecutionException) {
                Log.w(TAG, "mDNS resolve queue saturated; dropping $name")
            }
        }

        override fun serviceResolved(event: ServiceEvent) {
            event.info?.let { record(it, event.name, event.dns) } // some paths resolve directly
        }

        override fun serviceRemoved(event: ServiceEvent) {
            if (browsing && jmdns === event.dns) peerMap.remove(event.name)
        }
    }

    /** Fold a resolved mDNS record into the roster (needs a resolved IPv4 to be navigable). */
    private fun record(info: ServiceInfo, fallbackName: String, expectedDns: JmDNS) {
        if (!browsing || jmdns !== expectedDns) return
        val p = toPeer(
            instanceName = info.name ?: fallbackName,
            txtName = info.getPropertyString("name"),
            txtVer = info.getPropertyString("ver"),
            ipv4 = info.inet4Addresses?.firstOrNull()?.hostAddress,
            port = info.port,
            selfId = runtimePanelId,
            selfIp = localIpv4(),
        )
        if (p.panelId.isBlank() || p.ip == null) return
        // Don't DOWNGRADE a resolved friendly name back to the panel_id fallback: a later TXT-less resolve
        // (common right after a whole-fleet restart floods mDNS) must not clobber a good name. Otherwise store.
        val existing = peerMap[p.panelId]
        if (existing == null && peerMap.size >= MAX_PEERS) return
        val newHasName = p.name != p.panelId
        val oldHasName = existing != null && existing.name != existing.panelId
        if (browsing && jmdns === expectedDns && (existing == null || newHasName || !oldHasName)) {
            peerMap[p.panelId] = p
        }
    }

    /**
     * Blocking network setup — call off the main thread.
     *
     * Safe to call after network changes: an already healthy advertisement is left alone, while a
     * changed LAN address is rebound. Silent responder stalls are handled separately by the bounded
     * liveness supervisor; network changes still reset that circuit because they start a fresh topology.
     */
    fun start(lanIp: String?, secondaryIp: String? = null) {
        val request = topology.request(lanIp, secondaryIp)
        if (request.changed) {
            cancelRecovery()
            liveness.onStarted(true)
        }
        start(lanIp, resetLivenessBudget = request.changed, expectedEpoch = request.epoch)
    }

    /** Bootstrap before ConnectivityManager has delivered the first authoritative default-network address. */
    fun start() {
        val current = topology.snapshot()
        if (current.lanIp == null) start(localIpv4() ?: localIpv6()) else ensureStarted()
    }

    /** Retry creation for the already-authoritative topology without enumerating another interface. */
    fun ensureStarted() {
        val current = topology.snapshot()
        start(current.lanIp, resetLivenessBudget = false, expectedEpoch = current.epoch)
    }

    private fun start(lanIp: String?, resetLivenessBudget: Boolean, expectedEpoch: Long): Boolean =
        ownerGate.runIfOpen(false) start@{
            // A successor that has not restored the panel's identity yet must not advertise the one it
            // generated for itself; it restarts from the restored configuration and advertises then.
            if (io.github.maxlyth.hapaneld.migration.IdentityMigrationGate.holdsNetworkIdentity()) return@start false
            if (!topology.matches(expectedEpoch, lanIp)) return@start false
            val secondaryIp = topology.snapshot().secondaryIp
            if (lanIp == null) {
                // ha-paneld can start before DHCP completes. Advertising loopback is worse than waiting:
                // peers cannot reach it and JmDNS joins multicast on `lo`, not the LAN interface.
                if (jmdns != null || lock != null) stopResources(MonotonicDeadline(OWNER_STOP_MS))
                Log.i(TAG, "mDNS advertise deferred — no LAN address yet")
                return@start false
            }
            if (jmdns != null || lock != null) {
                if (!mdnsRebindRequired(boundIp, lanIp, browsing)) {
                    if (resetLivenessBudget) liveness.onStarted(true)
                    reconcileSecondary(secondaryIp)
                    return@start true
                }
                Log.i(TAG, "mDNS address changed or advertiser stopped (bound=$boundIp lan=$lanIp); rebinding")
                if (!stopResources(MonotonicDeadline(OWNER_STOP_MS))) return@start false
                // A teardown that could not drain still owns its socket. A later network event can retry;
                // never stack a second responder on top of it.
                if (jmdns != null) return@start false
            }
            if (resolver?.isShutdown != false) resolver = newResolver()
            try {
                lock = acquireMulticastLock()
                val addr = InetAddress.getByName(lanIp)
                val dns = createDns(addr, runtimePanelId)
                dns.setDelegate { failedDns, _ ->
                    requestRecovery(
                        failedDns,
                        "JmDNS could not recover its multicast socket",
                        MdnsReasonCode.MULTICAST_SOCKET_FAILED,
                        terminal = true,
                    )
                }
                val generationProbeToken = UUID.randomUUID().toString()
                val props = buildMap {
                    put("ver", Config.VERSION)
                    put("caps", "tts")
                    put("path", "/play")
                    // Friendly name so a peer's fleet switcher can label this panel nicely (falls back to the
                    // instance name = panel_id on older panels that don't advertise it). Additive TXT key.
                    put("name", runtimeFriendlyName.ifBlank { runtimePanelId })
                    put("probe", generationProbeToken)
                    // A stable token lets Panel Assistant distinguish the mutable mDNS instance/name from
                    // a panel identity without advertising Settings.Secure.ANDROID_ID on the LAN.
                    discoveryId()?.let { put("did", it) }
                }
                val info = ServiceInfo.create(
                    Config.MDNS_SERVICE_TYPE,
                    runtimePanelId,
                    runtimeHttpPort,
                    0,
                    0,
                    props,
                )
                jmdns = dns
                boundIp = lanIp
                dns.registerService(info)
                // JmDNS may rename a colliding instance during registration; monitor the actual name.
                advertisedInstanceName = info.name ?: runtimePanelId
                advertisedProps = props
                reconcileSecondary(secondaryIp)
                // Start the persistent peer browse (powers the header switcher) — begins querying immediately
                // and feeds a cached roster, so UI reads are instant. Completeness is eventual and can take
                // longer after a whole-fleet restart.
                browsing = true
                runCatching { dns.addServiceListener(Config.MDNS_SERVICE_TYPE, peerListener) }
                // Periodic re-browse: dns.list resolves each service fully (incl. TXT), so this refreshes names
                // and fills any TXT missed during a congested (whole-fleet-restart) window. Merges with the
                // don't-downgrade rule in record(), so it only closes gaps and never flaps a good name.
                val generation = browseGeneration.incrementAndGet()
                lateinit var worker: Thread
                worker = Thread {
                    var secondaryRetryAfterMs = 0L
                    while (mdnsRunCurrent(browseGeneration, generation, browsing, jmdns === dns)) {
                        try { Thread.sleep(refreshIntervalMs) } catch (e: InterruptedException) { break }
                        if (!mdnsRunCurrent(browseGeneration, generation, browsing, jmdns === dns)) break
                        val cost = FeatureCosts.registry.span(FeatureCostOperation.MDNS_PEER_REFRESH)
                        try {
                            val services = dns.list(Config.MDNS_SERVICE_TYPE, LIST_MS)?.toList().orEmpty()
                            cost.work(units = services.size.toLong())
                            if (!mdnsRunCurrent(browseGeneration, generation, browsing, jmdns === dns)) {
                                cost.outcome(FeatureCostOutcome.CANCELLED)
                                break
                            }
                            services.forEach { record(it, it.name ?: "", dns) }
                        } catch (failure: Exception) {
                            cost.outcome(FeatureCostOutcome.FAILURE)
                        } finally {
                            cost.close()
                        }
                        if (!mdnsRunCurrent(browseGeneration, generation, browsing, jmdns === dns)) break
                        when (probeMdnsService(
                                lanIp, advertisedInstanceName ?: runtimePanelId,
                                Config.MDNS_SERVICE_TYPE, generationProbeToken,
                            )
                        ) {
                            MdnsProbeResult.VISIBLE -> liveness.observeSelf(true, monotonicMs())
                            MdnsProbeResult.MISSING -> {
                                liveness.observeSelf(false, monotonicMs())?.let { reservation ->
                                    submitRecovery(
                                        dns,
                                        "own advertisement absent from $DEAD_SWEEPS consecutive on-wire probes",
                                        reservation,
                                    )
                                }
                            }
                            MdnsProbeResult.UNAVAILABLE -> Unit
                            MdnsProbeResult.INCONCLUSIVE -> Unit
                        }
                        val current = topology.snapshot()
                        val observedSecondary = secondaryDns
                        if (current.secondaryIp != null &&
                            (observedSecondary == null || observedSecondary.isStopped()) &&
                            monotonicMs() >= secondaryRetryAfterMs
                        ) {
                            val primaryHealthy = {
                                liveness.snapshot().let {
                                    it.state == MdnsLivenessState.HEALTHY && it.consecutiveMisses == 0
                                }
                            }
                            if (recoveryScheduler.scheduleIf(
                                    admitted = {
                                        jmdns === dns && secondaryDns === observedSecondary &&
                                            topology.matches(current.epoch, current.lanIp) &&
                                            topology.snapshot().secondaryIp == current.secondaryIp && primaryHealthy()
                                    },
                                    task = {
                                        ownerGate.runIfOpen(Unit) {
                                            if (jmdns === dns && secondaryDns === observedSecondary &&
                                                topology.matches(current.epoch, current.lanIp) &&
                                                topology.snapshot().secondaryIp == current.secondaryIp && primaryHealthy()) {
                                                Log.w(TAG, "mDNS secondary responder stopped; rebinding")
                                                reconcileSecondary(current.secondaryIp)
                                            }
                                        }
                                    },
                                    delayMs = 0L,
                                )) secondaryRetryAfterMs = saturatingAdd(monotonicMs(), 10_000L)
                        }
                    }
                    if (refreshThread === worker) refreshThread = null
                }.apply { isDaemon = true; name = "mdns-peer-refresh" }
                refreshThread = worker
                worker.start()
                liveness.onStarted(resetLivenessBudget)
                Log.i(TAG, "advertising ${Config.MDNS_SERVICE_TYPE} as $runtimePanelId @ $addr:$runtimeHttpPort")
                true
            } catch (e: Exception) {
                Log.w(TAG, "mDNS advertise failed", e)
                stopResources(MonotonicDeadline(OWNER_STOP_MS))
                false
            }
        }

    /**
     * Bind the dual-stack IPv6 responder to [wanted], or remove it. Runs under [ownerGate] after the
     * primary is registered, and advertises the primary's instance name and TXT record, so both families
     * describe one panel. A failure leaves the primary alone; the next network callback retries.
     */
    private fun reconcileSecondary(wanted: String?) {
        if (secondaryBoundIp == wanted &&
            (wanted == null || (secondaryDns != null && !secondaryDns.isStopped()))
        ) return
        if (!stopSecondary() || wanted == null) return
        val props = advertisedProps ?: return
        try {
            val dns = createDns(InetAddress.getByName(wanted), runtimePanelId)
            secondaryDns = dns
            dns.setDelegate { _, _ -> Log.w(TAG, "mDNS responder at $wanted could not recover its multicast socket") }
            dns.registerService(
                ServiceInfo.create(
                    Config.MDNS_SERVICE_TYPE,
                    advertisedInstanceName ?: runtimePanelId,
                    runtimeHttpPort,
                    0,
                    0,
                    props,
                ),
            )
            secondaryBoundIp = wanted
            Log.i(TAG, "also advertising ${Config.MDNS_SERVICE_TYPE} @ $wanted:$runtimeHttpPort")
        } catch (e: Exception) {
            Log.w(TAG, "mDNS advertise at $wanted failed", e)
            stopSecondary()
        }
    }

    private fun stopSecondary(): Boolean {
        val dns = secondaryDns
        if (dns != null) {
            runCatching { dns.setDelegate(null) }
            runCatching { dns.unregisterAllServices() }
            if (runCatching { dns.close() }.isFailure) return false
        }
        secondaryDns = null
        secondaryBoundIp = null
        return true
    }

    /** Queue recovery off JmDNS and refresh threads; neither may tear down a responder it owns. */
    private fun requestRecovery(
        dns: JmDNS,
        reason: String,
        reasonCode: MdnsReasonCode,
        terminal: Boolean,
    ) {
        if (dns !== jmdns || !browsing) return
        if (!terminal) return
        liveness.observeTerminalFailure(monotonicMs(), reason, reasonCode)?.let { submitRecovery(dns, reason, it) }
    }

    private fun submitRecovery(
        dns: JmDNS?,
        reason: String,
        reservation: MdnsRecoveryReservation,
        expectedEpoch: Long? = null,
        expectedIp: String? = null,
    ) {
        val currentTopology = topology.snapshot()
        if (expectedEpoch != null &&
            (currentTopology.epoch != expectedEpoch || currentTopology.lanIp != expectedIp)
        ) {
            liveness.cancelPending(reservation.token)
            return
        }
        val ip = currentTopology.lanIp ?: run {
            liveness.cancelPending()
            return
        }
        val request = RecoveryRequest(
            dns, browseGeneration.get(), currentTopology.epoch, ip, reason, reservation,
        )
        if (!recoveryScheduler.scheduleIf(
            admitted = {
                topology.matches(request.epoch, request.boundIp) &&
                    liveness.isCurrent(request.reservation.token)
            },
            task = {
                runCatching { recover(request) }
                    .onFailure { Log.e(TAG, "mDNS recovery worker failed", it) }
            },
            delayMs = reservation.delayMs,
        )) liveness.cancelPending(request.reservation.token)
    }

    /** Full teardown and recreation, serialized with network rebind, reconfigure, stop and retirement. */
    private fun recover(request: RecoveryRequest) {
        if (!liveness.isCurrent(request.reservation.token)) return
        val outcome = runMdnsRecoveryTransaction(
            gate = ownerGate,
            admitted = {
                val currentTopology = topology.snapshot()
                liveness.isCurrent(request.reservation.token) && mdnsRecoveryStillCurrent(
                    request.epoch, currentTopology.epoch, request.boundIp, currentTopology.lanIp,
                    request.generation, browseGeneration.get(), request.dns === jmdns,
                )
            },
            onAdmitted = { Log.w(TAG, "mDNS liveness recovery: ${request.reason}") },
            teardown = {
                request.dns == null || stopResources(MonotonicDeadline(OWNER_STOP_MS))
            },
            restart = {
                start(request.boundIp, resetLivenessBudget = false, expectedEpoch = request.epoch)
            },
            stillCurrent = {
                topology.matches(request.epoch, request.boundIp) &&
                    liveness.isCurrent(request.reservation.token)
            },
        )
        val failure = when (outcome) {
            MdnsRecoveryOutcome.TEARDOWN_FAILED ->
                "responder teardown did not drain" to MdnsReasonCode.TEARDOWN_FAILED
            MdnsRecoveryOutcome.RESTART_FAILED ->
                "responder recreation failed" to MdnsReasonCode.RECREATION_FAILED
            else -> null
        }
        if (failure != null && topology.matches(request.epoch, request.boundIp)) {
            liveness.recoveryFailed(
                request.reservation.token,
                monotonicMs(),
                failure.first,
                failure.second,
            )?.let { delay ->
                submitRecovery(jmdns, failure.first, delay, request.epoch, request.boundIp)
            }
        } else liveness.recoveryFinished(request.reservation.token)
    }

    /** Stop the current advertisement while still allowing a later network-recovery restart. */
    fun stop() {
        topology.stop()
        cancelRecovery()
        liveness.cancelPending()
        ownerGate.runExclusive { stopResources(MonotonicDeadline(OWNER_STOP_MS)) }
    }

    private fun cancelRecovery() {
        recoveryScheduler.cancel()
    }

    /** Advertiser state for the operator-visible status warning. Never throws. */
    fun health(): MdnsHealth = MdnsHealth(
        advertising = jmdns != null && browsing,
        boundIp = boundIp,
        lanIp = mdnsHealthLanIp(boundIp, topology.snapshot().lanIp, ::localIpv4),
        liveness = liveness.snapshot(),
    )

    /** Host-free, fixed-cardinality projection for `/diag` and the System card. */
    fun statusPublic(): String {
        val health = health()
        val live = health.liveness
        val responder = if (health.advertising) "advertising" else "stopped"
        return "$responder · ${live.state.name.lowercase()} · misses=${live.consecutiveMisses} · " +
            "recoveries=${live.recoveryAttempts} · retry=${live.retryAfterMs}ms"
    }

    /** Permanently close this runtime generation so a late recovery callback cannot resurrect it. */
    internal fun retire(deadline: MonotonicDeadline): CompletableFuture<Boolean> {
        ownerGate.closeAdmission()
        topology.stop()
        cancelRecovery()
        liveness.cancelPending()
        retirement.get()?.let { return it }
        val result = CompletableFuture<Boolean>()
        if (!retirement.compareAndSet(null, result)) return checkNotNull(retirement.get())
        Thread {
            try {
                val recoveryDrained = recoveryScheduler.closeAndJoin(deadline.remainingMs())
                val responderStopped = ownerGate.runExclusive { stopResources(deadline) }
                result.complete(recoveryDrained && responderStopped)
            } catch (failure: Throwable) {
                result.completeExceptionally(failure)
            }
        }.apply {
            isDaemon = true
            name = "mdns-retire"
            start()
        }
        return result
    }

    private fun stopResources(deadline: MonotonicDeadline): Boolean {
        var complete = true
        browsing = false
        browseGeneration.incrementAndGet()
        val refresh = refreshThread
        val refreshDrained = refresh.interruptAndJoin(deadline)
        if (refreshDrained) refreshThread = null else complete = false
        val activeResolver = resolver
        val resolverDrained = activeResolver?.shutdownNowAndAwait(deadline) ?: true
        if (resolverDrained) resolver = null else complete = false
        // Do not dismantle JmDNS underneath an admitted list/resolve call. The terminal retirement fence
        // is already installed; a failed drain selects the process boundary instead of a successor.
        if (!complete || deadline.remainingMs() <= 0L) return false
        // Best effort: the IPv6 responder is outside the liveness supervisor and must never make the
        // primary's teardown, and so its recovery, report failure.
        if (!stopSecondary()) {
            Log.w(TAG, "mDNS responder at $secondaryBoundIp did not close; abandoning it")
            secondaryDns = null
            secondaryBoundIp = null
        }
        val activeDns = jmdns
        runCatching { activeDns?.setDelegate(null) }
        runCatching { activeDns?.removeServiceListener(Config.MDNS_SERVICE_TYPE, peerListener) }
        runCatching { activeDns?.unregisterAllServices() }
        if (activeDns != null && runCatching { activeDns.close() }.isFailure) {
            return false
        }
        jmdns = null
        boundIp = null
        advertisedInstanceName = null
        advertisedProps = null
        peerMap.clear()
        val activeLock = lock
        if (activeLock != null && runCatching { activeLock() }.isFailure) {
            return false
        }
        lock = null
        return true
    }

    /**
     * Browse for Home Assistant's own zeroconf advertisement (`_home-assistant._tcp.local.`) and
     * return the IPv4 of one that actually has the MQTT port (1883) open — so on a LAN with more than
     * one HA instance we pick the one running a broker, and skip an HA whose broker lives elsewhere.
     * Falls back to the first HA host if none probe open. Used to default the MQTT broker to
     * `tcp://<ha-ip>:1883` when none is set. Blocking up to [timeoutMs]; call off the main thread.
     */
    fun discoverHaIp(timeoutMs: Long = 4000): String? = ownerGate.runIfOpen<String?>(null) {
        val dns = jmdns ?: return@runIfOpen null
        val ips = runCatching {
            dns.list("_home-assistant._tcp.local.", timeoutMs)
                ?.mapNotNull { it.inet4Addresses?.firstOrNull()?.hostAddress }?.distinct() ?: emptyList()
        }.getOrDefault(emptyList())
        val chosen = ips.firstOrNull { mqttPortOpen(it) } ?: ips.firstOrNull()
        Log.i(TAG, "HA mDNS discovery: hosts=$ips -> ${chosen ?: "none found"}")
        chosen
    }

    /**
     * HA's own advertised base URL (scheme+host+port) from its zeroconf TXT — `internal_url` preferred,
     * then `base_url`, then `external_url`. Authoritative for setups that don't use the default http :8123
     * (e.g. HA Core terminating TLS on :443, or behind a reverse proxy), so callers never have to guess a
     * port/scheme. Null if no HA service is found or it advertises no URL. Blocking; call off the main thread.
     */
    fun discoverHaBaseUrl(configuredBroker: String, timeoutMs: Long = 4000): String? =
        ownerGate.runIfOpen<String?>(null) {
        val dns = jmdns ?: return@runIfOpen null
        val brokerIps = brokerHostIps(configuredBroker) // all of the broker host's addresses (v4 AND v6) — see below
        val url = runCatching {
            val svcs = dns.list("_home-assistant._tcp.local.", timeoutMs)?.toList() ?: emptyList()
            // The panel's HA is the one hosting its MQTT broker. Match the configured broker by IP — against
            // ALL of the service's addresses (v4 + v6), since the broker host commonly resolves to a global
            // IPv6 first — so on a LAN with several HA instances (a primary + a secondary) we never pick the
            // wrong one. Else fall back to whichever HA has :1883 open; else give up rather than guess.
            val pick = svcs.firstOrNull { s ->
                s.inetAddresses?.any { (it.hostAddress?.substringBefore('%')) in brokerIps } == true
            } ?: if (configuredBroker.isBlank()) {
                // No explicit broker → auto-discover: the local HA that runs the broker (:1883 open).
                svcs.firstOrNull { s -> s.inet4Addresses?.firstOrNull()?.hostAddress?.let { mqttPortOpen(it) } == true }
            } else {
                // Explicit broker that matched no local HA (e.g. its HA is across a tunnel) → return null so
                // the caller's broker-HOST fallback handles it, rather than picking an unrelated local HA.
                null
            }
            pick?.let {
                val advertised = it.getPropertyString("internal_url") ?: it.getPropertyString("base_url")
                    ?: it.getPropertyString("external_url")
                val serviceIps = it.inetAddresses.orEmpty()
                    .mapNotNull { address -> address.hostAddress?.substringBefore('%') }
                    .toSet()
                // An IP-form advertised URL is rewritten to the record's own hostname (same record, same
                // trust) so downstream suggestions — HA URL and the derived MQTT broker — stay hostname-
                // shaped and IPv6-capable instead of pinning the panel to one IPv4 address.
                HaDiscovery.preferServerHostname(safeAdvertisedHaUrl(advertised, serviceIps), it.server)
            }
        }.getOrNull()
        Log.i(TAG, "HA mDNS base url (broker=${brokerIps.joinToString(",").ifEmpty { "?" }}): ${url ?: "none"}")
        url
    }

    /**
     * Resolve the stable Home Assistant instance id advertised in zeroconf TXT. Identity is accepted only
     * when exactly one valid record matches the configured HA origin or one of [candidateUrls] returned by
     * that authenticated HA API. IP address, MQTT location and record order are deliberately irrelevant:
     * several HA instances can share a host or reverse proxy. A `<uuid>.local` candidate is also an exact
     * identity match because Home Assistant defines that hostname from the same advertised uuid.
     *
     * Blocking for at most the bounded JmDNS browse budget; call off the main thread. Malformed, ambiguous
     * and unmatched records all fail closed with null.
     */
    fun discoverHaInstanceUuid(candidateUrls: Collection<String> = emptyList(), timeoutMs: Long = 4000): String? =
        ownerGate.runIfOpen<String?>(null) {
        val dns = jmdns ?: return@runIfOpen null
        val candidates = buildList {
            config.haUrl.takeIf { it.isNotBlank() }?.let(::add)
            addAll(candidateUrls)
        }
        if (candidates.isEmpty()) return@runIfOpen null
        runCatching {
            val services = dns.list(HA_SERVICE_TYPE, timeoutMs.coerceIn(1L, MAX_HA_BROWSE_MS))
                ?.toList() ?: emptyList()
            // Truncating a crowded result could hide a second matching UUID. Likewise, silently dropping
            // a malformed record could turn an ambiguous browse into an apparently unique identity.
            if (services.size > MAX_HA_RECORDS) return@runCatching null
            val records = services.map { info ->
                parseHaTxtRecord(
                        uuid = info.getPropertyString("uuid"),
                        internalUrl = info.getPropertyString("internal_url"),
                        externalUrl = info.getPropertyString("external_url"),
                        baseUrl = info.getPropertyString("base_url"),
                        server = info.server,
                    ) ?: return@runCatching null
            }
            matchHaInstanceUuid(candidates, records)
        }.getOrNull()
    }

    /**
     * The LAN ha-paneld roster (`_ha-paneld._tcp.local.`) for the header panel switcher — a cheap,
     * non-blocking snapshot of [peerMap], which [peerListener] keeps converged + fresh in the background.
     * This panel's own advertised service is included and [Peer.self]-marked against this advertiser's
     * immutable runtime identity, so a newer config cannot relabel the retiring generation. Never throws.
     */
    fun browsePeers(): List<Peer> {
        if (!browsing) return emptyList()
        val selfId = runtimePanelId
        val selfIp = localIpv4()
        val selfName = runtimeFriendlyName.ifBlank { selfId }
        return dedupePeers(
            peerMap.values.map {
                val self = it.panelId == selfId || (it.ip != null && it.ip == selfIp)
                // mDNS self-resolution can miss our OWN TXT (name), so a panel would show ITSELF as its
                // panel_id while peers show its friendly name — making the alphabetical menu order differ
                // between panels. Force this panel's authoritative friendly name for the self entry.
                if (self) it.copy(self = true, name = selfName) else it.copy(self = false)
            },
        )
    }

    /**
     * [discoverHaBaseUrl] plus *why* it came back empty, so setup can distinguish "nothing found yet"
     * from "this can never work here" and tell the user to type the address instead of leaving them at a
     * blank field. A panel on a different network segment from Home Assistant is the case that matters:
     * mDNS is link-local, so no amount of retrying will help, and only an explanation makes that obvious.
     *
     * The off-link judgement is made from addresses and interface prefixes, independently of the browse,
     * so it holds even when the browse itself succeeds at reaching nothing.
     *
     * Blocking for the same budget as [discoverHaBaseUrl]; call off the main thread.
     */
    fun discoverHaBaseUrlDetailed(configuredBroker: String, timeoutMs: Long = 4000): DiscoveryResult {
        val attemptedAtMs = System.currentTimeMillis()
        val offLink = HaDiscovery.brokerOffLink(brokerHostIps(configuredBroker).toList(), localPrefixes())
        val url = discoverHaBaseUrl(configuredBroker, timeoutMs)
        return HaDiscovery.classify(
            mdnsRunning = jmdns != null,
            multicastLockHeld = lock != null,
            brokerConfigured = configuredBroker.isNotBlank(),
            brokerOffLink = offLink,
            // The "nothing answered at all" inference stays unwired: counting responses would mean
            // restructuring the browse hot path for the one signal already known to be unreliable (a
            // healthy single-panel LAN whose HA simply does not advertise looks identical). The signals
            // above are facts or sound inferences; this one would be a guess, so it is not made.
            servicesSeen = SERVICES_SEEN_UNKNOWN,
            discoveredUrl = url.orEmpty(),
            attemptedAtMs = attemptedAtMs,
        )
    }

    /**
     * Local interface prefixes for the off-link judgement. Loopback and down interfaces are excluded, and
     * an interface that reports no prefix length is left for [HaDiscovery] to treat as unjudgeable rather
     * than being assumed to be a /24.
     */
    private fun localPrefixes(): List<LocalPrefix> = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { nif ->
                nif.interfaceAddresses.orEmpty().mapNotNull { ia ->
                    val host = ia?.address?.hostAddress?.substringBefore('%') ?: return@mapNotNull null
                    LocalPrefix(host, ia.networkPrefixLength.toInt())
                }
            }
    }.getOrDefault(emptyList())

    /** All of the configured MQTT broker host's IP addresses (v4 + v6), to match against HA mDNS records. */
    private fun brokerHostIps(configuredBroker: String): Set<String> = runCatching {
        val host = configuredBroker.substringAfter("://").substringBefore(":").substringBefore("/").trim()
        if (host.isBlank()) emptySet()
        else java.net.InetAddress.getAllByName(host).mapNotNull { it.hostAddress?.substringBefore('%') }.toSet()
    }.getOrDefault(emptySet())

    /** True if TCP 1883 accepts a connection on [ip] within [timeoutMs] (the MQTT broker is there). */
    private fun mqttPortOpen(ip: String, port: Int = 1883, timeoutMs: Int = 600): Boolean =
        runCatching {
            java.net.Socket().use { it.connect(java.net.InetSocketAddress(ip, port), timeoutMs) }; true
        }.getOrDefault(false)

    companion object {
        private const val TAG = "ha-paneld/mdns"
        private const val RESOLVE_MS = 2500L // per-peer mDNS resolve budget (off the JmDNS thread)
        private const val RESOLVE_WORKERS = 2
        private const val RESOLVE_QUEUE = 32
        private const val MAX_PEERS = 64
        private const val REFRESH_MS = 60_000L // periodic re-browse interval (name refresh / gap fill)
        private const val LIST_MS = 3000L // dns.list resolve budget per refresh sweep
        private const val DEAD_SWEEPS = 3
        private const val OWNER_STOP_MS = 2_000L
        private const val HA_SERVICE_TYPE = "_home-assistant._tcp.local."
        private const val MAX_HA_BROWSE_MS = 5_000L
        private const val MAX_HA_RECORDS = 16
    }
}

private const val PANEL_ASSISTANT_DISCOVERY_NAMESPACE = "panel-assistant-mdns-v1\u0000"

private fun acquireWifiMulticastLock(context: Context): () -> Unit {
    val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    val lock = wifi.createMulticastLock("ha-paneld-mdns").apply {
        setReferenceCounted(true)
        acquire()
    }
    return lock::release
}

/** Domain-separated pseudonym of the persisted installation identity; never a raw hardware ID. */
internal fun panelAssistantDiscoveryId(installationId: String): String? {
    val source = installationId.trim()
    if (source.isEmpty()) return null
    return MessageDigest.getInstance("SHA-256")
        .digest((PANEL_ASSISTANT_DISCOVERY_NAMESPACE + source).toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
}

/** Observable mDNS state. A null [lanIp] means DHCP has not supplied a usable IPv4 address yet. */
data class MdnsHealth(
    val advertising: Boolean,
    val boundIp: String?,
    val lanIp: String?,
    val liveness: MdnsLivenessSnapshot = MdnsLivenessSnapshot(),
)

/** Closed reason vocabulary for localized recovery warnings; [lastReason] remains exact diagnostic evidence. */
enum class MdnsReasonCode(val wireValue: String) {
    OWN_ADVERTISEMENT_ABSENT("own-advertisement-absent"),
    MULTICAST_SOCKET_FAILED("multicast-socket-failed"),
    TEARDOWN_FAILED("teardown-failed"),
    RECREATION_FAILED("recreation-failed"),
    NO_RESPONSE("no-response"),
}

/** Typed counterpart of [mdnsHealthWarning], preserving its exact precedence and null behavior. */
internal fun mdnsHealthPresentation(health: MdnsHealth): InstallPresentation? = when {
    health.lanIp == null -> null
    !health.advertising -> InstallPresentation("status-mdns-not-running")
    health.boundIp != health.lanIp -> health.boundIp?.let { boundIp ->
        InstallPresentation.create(
            "status-mdns-stale-address",
            mapOf("bound_ip" to boundIp, "lan_ip" to health.lanIp),
        )
    }
    health.liveness.state == MdnsLivenessState.EXHAUSTED -> InstallPresentation.create(
        "status-mdns-unresponsive",
        mapOf(
            "attempts" to health.liveness.recoveryAttempts.toString(),
            "reason_code" to (health.liveness.reasonCode ?: MdnsReasonCode.NO_RESPONSE).wireValue,
        ),
    )
    health.liveness.state == MdnsLivenessState.RECOVERING -> InstallPresentation.create(
        "status-mdns-recovering",
        mapOf("reason_code" to (health.liveness.reasonCode ?: MdnsReasonCode.NO_RESPONSE).wireValue),
    )
    else -> null
}

/** A concise status warning for an advertiser that is absent or bound to an obsolete LAN address. */
internal fun mdnsHealthWarning(health: MdnsHealth): String? = when {
    // Before DHCP there is nothing useful to advertise; the network cards already explain that state.
    health.lanIp == null -> null
    !health.advertising ->
        "⚠ <b>Panel discovery (mDNS) is not running</b> — this panel will not appear in other panels' " +
            "switcher menus or Home Assistant discovery. Reconnect the panel to the network or restart ha-paneld."
    health.boundIp != health.lanIp ->
        "⚠ <b>Panel discovery (mDNS) has a stale address</b> (${health.boundIp}, now ${health.lanIp}) — " +
            "other panels cannot reach this one from their switcher until it rebinds."
    health.liveness.state == MdnsLivenessState.EXHAUSTED ->
        "⚠ <b>Panel discovery (mDNS) is unresponsive</b> — automatic recovery stopped after " +
            "${health.liveness.recoveryAttempts} attempts (${health.liveness.lastReason ?: "no response"}). " +
            "Reconnect the panel to the network or restart ha-paneld."
    health.liveness.state == MdnsLivenessState.RECOVERING ->
        "⚠ <b>Panel discovery (mDNS) is recovering</b> — ${health.liveness.lastReason ?: "no response"}."
    else -> null
}

enum class MdnsLivenessState { HEALTHY, RECOVERING, EXHAUSTED }

data class MdnsLivenessSnapshot(
    val state: MdnsLivenessState = MdnsLivenessState.HEALTHY,
    val consecutiveMisses: Int = 0,
    val recoveryAttempts: Int = 0,
    val lastReason: String? = null,
    val retryAfterMs: Long = 0L,
    val reasonCode: MdnsReasonCode? = null,
)

internal data class MdnsRecoveryReservation(val delayMs: Long, val token: Long)

/**
 * Pure synchronized circuit for the responder watchdog. A successful active query must contain this
 * process's own registered service; peers may legitimately be absent. Three consecutive self misses are
 * required before recovery. A healthy self observation or a fresh network topology resets the budget.
 */
internal class MdnsLivenessPolicy(
    private val deadSweeps: Int = 3,
    private val maxAttempts: Int = 3,
    private val backoffMs: LongArray = longArrayOf(0L, 60_000L, 300_000L),
) {
    private var misses = 0
    private var attempts = 0
    private var retryAtMs = 0L
    private var state = MdnsLivenessState.HEALTHY
    private var reason: String? = null
    private var reasonCode: MdnsReasonCode? = null
    private var recoveryPending = false
    private var reservationToken = 0L

    init {
        require(deadSweeps > 0 && maxAttempts > 0 && backoffMs.isNotEmpty())
        require(backoffMs.all { it >= 0L })
    }

    @Synchronized fun onStarted(resetBudget: Boolean) {
        misses = 0
        if (resetBudget) resetHealthy()
    }

    @Synchronized fun observeSelf(visible: Boolean, nowMs: Long): MdnsRecoveryReservation? {
        if (visible) {
            resetHealthy()
            return null
        }
        misses++
        if (misses < deadSweeps) return null
        return reserveRecovery(
            nowMs,
            "own advertisement missing from $misses active queries",
            MdnsReasonCode.OWN_ADVERTISEMENT_ABSENT,
        )
    }

    @Synchronized fun observeTerminalFailure(
        nowMs: Long,
        failure: String,
        code: MdnsReasonCode = MdnsReasonCode.NO_RESPONSE,
    ): MdnsRecoveryReservation? = reserveRecovery(nowMs, failure, code)

    @Synchronized fun isCurrent(token: Long): Boolean = recoveryPending && reservationToken == token

    @Synchronized fun recoveryFinished(token: Long = reservationToken) {
        if (recoveryPending && reservationToken == token) recoveryPending = false
    }

    /** Advance the retry circuit only when this exact reservation still owns it. */
    @Synchronized fun recoveryFailed(
        token: Long,
        nowMs: Long,
        failure: String,
        code: MdnsReasonCode = MdnsReasonCode.NO_RESPONSE,
    ): MdnsRecoveryReservation? {
        if (!recoveryPending || reservationToken != token) return null
        recoveryPending = false
        return reserveRecovery(nowMs, failure, code)
    }

    @Synchronized fun cancelPending(token: Long? = null) {
        if (token != null && (!recoveryPending || reservationToken != token)) return
        recoveryPending = false
        reservationToken++
    }

    @Synchronized fun snapshot(nowMs: Long = monotonicMs()): MdnsLivenessSnapshot = MdnsLivenessSnapshot(
        state = state,
        consecutiveMisses = misses,
        recoveryAttempts = attempts,
        lastReason = reason,
        retryAfterMs = (retryAtMs - nowMs).coerceAtLeast(0L),
        reasonCode = reasonCode,
    )

    private fun reserveRecovery(
        nowMs: Long,
        failure: String,
        code: MdnsReasonCode,
    ): MdnsRecoveryReservation? {
        reason = failure
        reasonCode = code
        if (recoveryPending) return null
        if (attempts >= maxAttempts) {
            state = MdnsLivenessState.EXHAUSTED
            return null
        }
        val delay = backoffMs[attempts.coerceAtMost(backoffMs.lastIndex)]
        attempts++
        misses = 0
        retryAtMs = saturatingAdd(nowMs, delay)
        state = MdnsLivenessState.RECOVERING
        recoveryPending = true
        reservationToken++
        return MdnsRecoveryReservation(delay, reservationToken)
    }

    private fun resetHealthy() {
        misses = 0
        attempts = 0
        retryAtMs = 0L
        state = MdnsLivenessState.HEALTHY
        reason = null
        reasonCode = null
        recoveryPending = false
        reservationToken++
    }
}

internal fun saturatingAdd(left: Long, right: Long): Long =
    if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

internal fun monotonicMs(): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime())

internal data class MdnsTopologySnapshot(
    val epoch: Long,
    val lanIp: String?,
    val changed: Boolean = false,
    val secondaryIp: String? = null,
)

/**
 * Default-network authority. Duplicate callbacks preserve the current recovery generation and budget.
 * Only the primary address defines the epoch: the dual-stack IPv6 responder is outside the liveness
 * supervisor, so a change to it alone must not cancel recovery or reset the budget.
 */
internal class MdnsTopology {
    private var epoch = 0L
    private var lanIp: String? = null
    private var secondaryIp: String? = null

    @Synchronized fun request(requestedIp: String?, requestedSecondaryIp: String? = null): MdnsTopologySnapshot {
        val changed = requestedIp != lanIp
        if (changed) {
            epoch++
            lanIp = requestedIp
        }
        secondaryIp = requestedSecondaryIp.takeIf { requestedIp != null }
        return MdnsTopologySnapshot(epoch, lanIp, changed, secondaryIp)
    }

    @Synchronized fun stop(): MdnsTopologySnapshot {
        epoch++
        lanIp = null
        secondaryIp = null
        return MdnsTopologySnapshot(epoch, null, true)
    }

    @Synchronized fun snapshot(): MdnsTopologySnapshot = MdnsTopologySnapshot(epoch, lanIp, secondaryIp = secondaryIp)

    @Synchronized fun matches(expectedEpoch: Long, expectedIp: String?): Boolean =
        epoch == expectedEpoch && lanIp == expectedIp
}

internal fun mdnsRecoveryStillCurrent(
    expectedEpoch: Long,
    currentEpoch: Long,
    expectedIp: String,
    currentIp: String?,
    expectedGeneration: Long,
    currentGeneration: Long,
    ownsResponder: Boolean,
): Boolean = expectedEpoch == currentEpoch && expectedIp == currentIp &&
    expectedGeneration == currentGeneration && ownsResponder

internal enum class MdnsRecoveryOutcome {
    NOT_ADMITTED, TEARDOWN_FAILED, READY_TO_RESTART, RESTARTED, RESTART_FAILED, SUPERSEDED,
}

/** The production teardown/restart transaction. Stop/rebind invalidates topology before waiting on [gate]. */
internal fun runMdnsRecoveryTransaction(
    gate: RetirableMutationGate,
    admitted: () -> Boolean,
    onAdmitted: () -> Unit = {},
    teardown: () -> Boolean,
    restart: () -> Boolean,
    stillCurrent: () -> Boolean,
): MdnsRecoveryOutcome {
    val teardownOutcome = gate.runIfOpen(MdnsRecoveryOutcome.NOT_ADMITTED) {
        if (!admitted()) return@runIfOpen MdnsRecoveryOutcome.NOT_ADMITTED
        onAdmitted()
        if (teardown()) MdnsRecoveryOutcome.READY_TO_RESTART else MdnsRecoveryOutcome.TEARDOWN_FAILED
    }
    if (teardownOutcome != MdnsRecoveryOutcome.READY_TO_RESTART) return teardownOutcome
    if (restart()) return MdnsRecoveryOutcome.RESTARTED
    return if (stillCurrent()) MdnsRecoveryOutcome.RESTART_FAILED else MdnsRecoveryOutcome.SUPERSEDED
}

/** Replaceable delayed task: cancelling an old backoff never occupies the sole worker or delays fresh work. */
internal class LatestScheduledTask(threadName: String) {
    private val executor = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, threadName).apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    private var pending: ScheduledFuture<*>? = null
    private var generation = 0L
    private var runningThread: Thread? = null

    fun schedule(task: () -> Unit, delayMs: Long): Boolean = scheduleIf({ true }, task, delayMs)

    /** Validate and replace atomically so an obsolete submit cannot displace newer scheduled work. */
    @Synchronized fun scheduleIf(admitted: () -> Boolean, task: () -> Unit, delayMs: Long): Boolean {
        if (executor.isShutdown || !admitted()) return false
        pending?.cancel(true)
        runningThread?.takeIf { it !== Thread.currentThread() }?.interrupt()
        val taskGeneration = ++generation
        pending = try {
            executor.schedule(
                {
                    synchronized(this) {
                        if (generation == taskGeneration) pending = null
                        runningThread = Thread.currentThread()
                    }
                    try {
                        task()
                    } finally {
                        synchronized(this) {
                            if (runningThread === Thread.currentThread()) runningThread = null
                        }
                    }
                },
                delayMs.coerceAtLeast(0L),
                TimeUnit.MILLISECONDS,
            )
        } catch (_: RejectedExecutionException) {
            return false
        }
        return true
    }

    @Synchronized fun cancel() {
        pending?.cancel(true)
        pending = null
        runningThread?.takeIf { it !== Thread.currentThread() }?.interrupt()
        generation++
    }

    fun closeAndJoin(timeoutMs: Long): Boolean {
        cancel()
        executor.shutdownNow()
        return executor.awaitTermination(timeoutMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
    }
}

/**
 * The address the advertiser should be bound to, for the stale-address warning. An IPv4 panel keeps
 * comparing against the interface's live IPv4, which also catches a DHCP change no callback reported.
 * A responder bound to IPv6 compares against the default network's requested address instead: on a
 * host with IPv4 on some other interface, the live IPv4 would call a correct IPv6 binding stale.
 */
internal fun mdnsHealthLanIp(boundIp: String?, requestedIp: String?, liveIpv4: () -> String?): String? =
    if (boundIp?.contains(':') == true) requestedIp else liveIpv4() ?: requestedIp

/** True when an existing JmDNS instance must be replaced for the current LAN address. */
internal fun mdnsRebindRequired(boundIp: String?, lanIp: String, browsing: Boolean): Boolean =
    !browsing || boundIp != lanIp

/** A zeroconf TXT URL is untrusted input. Keep credentials on the service host that advertised it. */
internal fun safeAdvertisedHaUrl(raw: String?, serviceIps: Set<String>): String? {
    val uri = runCatching { java.net.URI(raw?.trim().orEmpty()) }.getOrNull() ?: return null
    if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.userInfo != null) return null
    val destinationIps = runCatching {
        java.net.InetAddress.getAllByName(uri.host).mapNotNull { it.hostAddress?.substringBefore('%') }.toSet()
    }.getOrDefault(emptySet())
    if (serviceIps.isEmpty() || destinationIps.intersect(serviceIps).isEmpty()) return null
    return uri.toString().trimEnd('/')
}

internal fun mdnsRunCurrent(
    generation: AtomicLong,
    expected: Long,
    browsing: Boolean,
    ownsResolver: Boolean,
): Boolean = browsing && ownsResolver && generation.get() == expected

/** Validated identity-bearing subset of one resolved Home Assistant zeroconf record. */
internal data class HaTxtRecord(
    val uuid: String,
    val origins: Set<String>,
    val serverHost: String?,
)

/** Pure TXT parser. A malformed UUID or non-empty advertised URL invalidates the record. */
internal fun parseHaTxtRecord(
    uuid: String?,
    internalUrl: String?,
    externalUrl: String?,
    baseUrl: String?,
    server: String?,
): HaTxtRecord? {
    val id = uuid?.trim()?.lowercase()?.takeIf { it.matches(Regex("[0-9a-f]{32}")) } ?: return null
    val rawUrls = listOf(internalUrl, externalUrl, baseUrl).mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
    val origins = rawUrls.map { canonicalHaOrigin(it) ?: return null }.toSet()
    val host = server?.trim()?.trimEnd('.')?.lowercase()?.takeIf(String::isNotEmpty)
    return HaTxtRecord(id, origins, host)
}

/**
 * Pure fail-closed identity matcher. Duplicate advertisements for one UUID are harmless; two distinct
 * matching UUIDs are ambiguous. URL paths are intentionally discarded because authenticated `/api/config`
 * candidates and configured dashboard paths still identify the same HTTP origin.
 */
internal fun matchHaInstanceUuid(candidateUrls: Collection<String>, records: Collection<HaTxtRecord>): String? {
    val candidates = candidateUrls.mapNotNull(::canonicalHaCandidate).toSet()
    if (candidates.isEmpty()) return null
    val matches = records.asSequence().filter { record ->
        candidates.any { candidate ->
            candidate.origin in record.origins ||
                candidate.host == "${record.uuid}.local" ||
                record.serverHost == "${record.uuid}.local" && candidate.host == record.serverHost
        }
    }.map { it.uuid }.toSet()
    return matches.singleOrNull()
}

private data class CanonicalHaCandidate(val origin: String, val host: String)

private fun canonicalHaCandidate(raw: String): CanonicalHaCandidate? {
    val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
    val origin = canonicalHaOrigin(uri) ?: return null
    return CanonicalHaCandidate(origin, uri.host.trimEnd('.').lowercase())
}

/** Canonical HTTP origin with scheme/host case and default ports normalized. */
internal fun canonicalHaOrigin(raw: String): String? =
    runCatching { URI(raw.trim()) }.getOrNull()?.let(::canonicalHaOrigin)

private fun canonicalHaOrigin(uri: URI): String? {
    val scheme = uri.scheme?.lowercase()?.takeIf { it == "http" || it == "https" } ?: return null
    val host = uri.host?.trimEnd('.')?.lowercase()?.takeIf(String::isNotEmpty) ?: return null
    if (uri.userInfo != null) return null
    val port = when {
        uri.port < 0 -> -1
        scheme == "http" && uri.port == 80 -> -1
        scheme == "https" && uri.port == 443 -> -1
        else -> uri.port
    }
    return URI(scheme, null, host, port, null, null, null).toString()
}

/** One ha-paneld panel discovered over mDNS (or this panel itself, [self]=true). [ip] is null when the
 *  record didn't resolve to an IPv4 (IPv6-only / unresolved) — such peers aren't navigable in the switcher. */
data class Peer(
    val panelId: String,
    val name: String,
    val ip: String?,
    val port: Int,
    val version: String,
    val self: Boolean,
)

/** Pure: dedupe by panel id (a panel can resolve to several records/addresses), preferring the entry with
 *  a resolved IPv4; sort purely by friendly name so the roster is IDENTICAL on every panel (self is flagged,
 *  not reordered — the switcher must look the same everywhere). Blank panel ids are dropped. Unit-tested. */
internal fun dedupePeers(peers: List<Peer>): List<Peer> {
    val byId = LinkedHashMap<String, Peer>()
    for (p in peers) {
        if (p.panelId.isBlank()) continue
        val existing = byId[p.panelId]
        if (existing == null || (existing.ip == null && p.ip != null)) byId[p.panelId] = p
    }
    return byId.values.sortedBy { it.name.lowercase() }
}

/** Pure: serialize the switcher roster to the `/api/v1/peers` JSON array. Kept here beside the peer
 *  mapping (not inline in the HTTP server, which needs a Context) so it's unit-testable without booting
 *  Ktor — `ip`/`url` emit a literal `null` for a peer whose IPv4 didn't resolve (un-navigable). */
internal fun peersJson(list: List<Peer>): String =
    list.joinToString(",", "[", "]") { p ->
        val ip = p.ip?.let { Json.str(it) } ?: "null"
        val url = p.ip?.let { Json.str("http://$it:${p.port}/") } ?: "null"
        """{"panel_id":${Json.str(p.panelId)},"name":${Json.str(p.name)},"ip":$ip,"port":${p.port},"url":$url,"version":${Json.str(p.version)},"self":${p.self}}"""
    }

/** Pure mDNS-record → [Peer] mapper (plain strings, no ServiceInfo) so it's JVM-unit-testable. */
internal fun toPeer(
    instanceName: String,
    txtName: String?,
    txtVer: String?,
    ipv4: String?,
    port: Int,
    selfId: String,
    selfIp: String?,
): Peer = Peer(
    panelId = instanceName,
    name = txtName?.takeIf { it.isNotBlank() } ?: instanceName,
    ip = ipv4,
    port = port,
    version = txtVer ?: "",
    self = instanceName == selfId || (ipv4 != null && ipv4 == selfIp),
)
