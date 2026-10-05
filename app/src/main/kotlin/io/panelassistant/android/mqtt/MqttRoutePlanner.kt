package io.panelassistant.android.mqtt

import io.panelassistant.android.MqttAddressFamily
import io.panelassistant.android.mqttAddressFamily
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.function.Supplier

/** User-owned bound on MQTT address-family selection. */
internal enum class MqttAddressFamilyPolicy(
    val configValue: String,
    val initialPreferIpv4: Boolean,
    val ipv4Only: Boolean,
) {
    AUTOMATIC("Automatic", initialPreferIpv4 = false, ipv4Only = false),
    PREFER_IPV4("Prefer IPv4", initialPreferIpv4 = true, ipv4Only = false),
    FORCE_IPV4("Force IPv4", initialPreferIpv4 = true, ipv4Only = true),
    ;

    companion object {
        fun fromConfig(value: String): MqttAddressFamilyPolicy = entries.firstOrNull {
            it.configValue.lowercase(Locale.ROOT) == value.trim().lowercase(Locale.ROOT)
        } ?: AUTOMATIC
    }
}

internal fun interface MqttBrokerResolver {
    fun resolve(host: String): List<InetAddress>
}

/** One concrete address while retaining the logical broker name for TLS SNI/hostname verification. */
internal data class MqttDialRoute(
    val logicalHost: String,
    val port: Int,
    val address: InetAddress?,
) {
    val family: MqttAddressFamily? = mqttAddressFamily(address)

    fun socketAddress(): InetSocketAddress? = address?.let { resolved ->
        val named = if (resolved is Inet6Address) {
            Inet6Address.getByAddress(logicalHost, resolved.address, resolved.scopeId)
        } else {
            InetAddress.getByAddress(logicalHost, resolved.address)
        }
        InetSocketAddress(named, port)
    }
}

/**
 * Per-Hive-client route owner. Every reconnect asks DNS again. Under `Automatic`, each pre-CONNACK
 * network failure suppresses the failed family and tries its sibling until one connects, so a
 * black-holed family cannot outlive one socket-connect bound. An explicit policy keeps its single
 * excursion and then returns to the family the user asked for. After a connection succeeds, reconnects
 * first refresh the established family; one failed pre-CONNACK retry may then suppress that family.
 * HiveMQ's existing backoff remains authoritative, and a force-IPv4 plan never emits IPv6.
 */
class MqttRoutePlanner internal constructor(
    private val logicalHost: String,
    private val port: Int,
    private val policy: MqttAddressFamilyPolicy,
    initialPreferIpv4: Boolean,
    private val rapidInitialFallbackAllowed: Boolean,
    private val resolver: MqttBrokerResolver = MqttBrokerResolver {
        InetAddress.getAllByName(it).toList()
    },
    private val resolverExecutor: Executor = RESOLVER_EXECUTOR,
) {
    private var preferredIpv4 = if (policy == MqttAddressFamilyPolicy.AUTOMATIC) {
        initialPreferIpv4
    } else {
        policy.initialPreferIpv4
    }
    private var connectedEver = false
    private var explicitPolicyAlternateSpent = false
    private var alternateSpentSinceConnect = false
    private var resolutionGeneration = 0L

    @Volatile
    internal var currentRoute: MqttDialRoute? = null
        private set

    @Synchronized
    internal fun resolveInitial(): MqttDialRoute? {
        val route = resolveRoute(
            preferIpv4 = preferredIpv4,
            requirePreferred = policy.ipv4Only,
            fallbackOnResolutionFailure = null,
        )
        currentRoute = route
        return route
    }

    internal fun resolveReconnect(
        preConnackFailure: Boolean,
        networkFailure: Boolean,
    ): CompletableFuture<MqttDialRoute?> {
        val request = synchronized(this) {
            val current = currentRoute
            // A client that has NEVER reached CONNACK alternates on every pre-CONNACK network
            // failure. One black-holed family costs exactly one socket-connect bound per attempt, and
            // HiveMQ's own growing backoff still paces the retries, so alternating is strictly cheaper
            // than pinning: a single-shot budget spent on a transient failure used to leave the panel
            // dialling a dead address family with nothing able to select its live sibling.
            // An EXPLICIT policy keeps its existing one-excursion shape: Prefer IPv4 looks at the
            // sibling once and then returns to the family the user asked for. Only Automatic, which
            // has no user-stated preference to return to, alternates until something connects.
            val explicitPolicyAllowsAlternate = policy == MqttAddressFamilyPolicy.AUTOMATIC ||
                !explicitPolicyAlternateSpent
            val initialAlternate = rapidInitialFallbackAllowed && !connectedEver &&
                explicitPolicyAllowsAlternate &&
                preConnackFailure && networkFailure && !policy.ipv4Only && current?.family != null
            val steadyAlternate = connectedEver && !alternateSpentSinceConnect && preConnackFailure &&
                networkFailure && !policy.ipv4Only && current?.family != null
            val alternateBudget = when {
                initialAlternate -> AlternateBudget.INITIAL
                steadyAlternate -> AlternateBudget.STEADY
                else -> null
            }
            val suppressFailedFamily = alternateBudget != null
            val preferIpv4 = if (suppressFailedFamily) {
                current?.family == MqttAddressFamily.IPV6
            } else if (policy != MqttAddressFamilyPolicy.AUTOMATIC) {
                policy.initialPreferIpv4
            } else {
                current?.family?.let { it == MqttAddressFamily.IPV4 } ?: preferredIpv4
            }
            ResolveRequest(
                generation = ++resolutionGeneration,
                preferIpv4 = preferIpv4,
                requirePreferred = suppressFailedFamily || policy.ipv4Only,
                fallback = current,
                alternateBudget = alternateBudget,
            )
        }
        val resolution = try {
            CompletableFuture.supplyAsync(
                Supplier {
                    resolveRoute(
                        preferIpv4 = request.preferIpv4,
                        requirePreferred = request.requirePreferred,
                        fallbackOnResolutionFailure = request.fallback,
                    ) ?: request.fallback
                },
                resolverExecutor,
            )
        } catch (_: RejectedExecutionException) {
            // A retired client's DNS lookup may still occupy the single bounded resolver slot. Do not
            // queue this reconnect behind it: keep the concrete route and let HiveMQ's normal backoff
            // retry. The alternate-family allowance remains unspent because no sibling was selected.
            CompletableFuture.completedFuture(request.fallback)
        }
        return resolution.thenApply { resolved ->
            synchronized(this) {
                if (request.generation != resolutionGeneration) currentRoute else {
                    currentRoute = resolved
                    if (resolved?.family != null && resolved.family != request.fallback?.family) {
                        when (request.alternateBudget) {
                            // Unbounded under Automatic; one excursion under an explicit policy,
                            // which then pins back to its stated family.
                            AlternateBudget.INITIAL ->
                                if (policy != MqttAddressFamilyPolicy.AUTOMATIC) {
                                    explicitPolicyAlternateSpent = true
                                }
                            AlternateBudget.STEADY -> alternateSpentSinceConnect = true
                            null -> Unit
                        }
                    }
                    resolved?.family?.let { preferredIpv4 = it == MqttAddressFamily.IPV4 }
                    resolved
                }
            }
        }
    }

    @Synchronized
    internal fun markConnected(route: MqttDialRoute?) {
        if (route != null && route == currentRoute) {
            connectedEver = true
            alternateSpentSinceConnect = false
            route.family?.let { preferredIpv4 = it == MqttAddressFamily.IPV4 }
        }
    }

    private fun resolveRoute(
        preferIpv4: Boolean,
        requirePreferred: Boolean,
        fallbackOnResolutionFailure: MqttDialRoute?,
    ): MqttDialRoute? {
        val candidates = runCatching { resolver.resolve(logicalHost) }.getOrElse {
            return if (policy.ipv4Only) null else fallbackOnResolutionFailure
                ?: MqttDialRoute(logicalHost, port, address = null)
        }
        val allowed = if (policy.ipv4Only) candidates.filterIsInstance<Inet4Address>() else candidates
        val preferred = if (preferIpv4) {
            allowed.filterIsInstance<Inet4Address>()
        } else {
            allowed.filterIsInstance<Inet6Address>()
        }
        val alternate = if (preferIpv4) {
            allowed.filterIsInstance<Inet6Address>()
        } else {
            allowed.filterIsInstance<Inet4Address>()
        }
        val selected = preferred.firstOrNull() ?: alternate.firstOrNull().takeUnless { requirePreferred }
        return selected?.let { MqttDialRoute(logicalHost, port, it) }
    }

    private data class ResolveRequest(
        val generation: Long,
        val preferIpv4: Boolean,
        val requirePreferred: Boolean,
        val fallback: MqttDialRoute?,
        val alternateBudget: AlternateBudget?,
    )

    private enum class AlternateBudget { INITIAL, STEADY }

    private companion object {
        val RESOLVER_EXECUTOR: Executor = ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            SynchronousQueue(),
            { runnable -> Thread(runnable, "mqtt-dns-resolver").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy(),
        )
    }
}
