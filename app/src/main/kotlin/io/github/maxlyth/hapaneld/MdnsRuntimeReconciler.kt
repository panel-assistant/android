package io.github.maxlyth.hapaneld

import io.github.maxlyth.hapaneld.util.ServiceRuntimeOwner
import java.net.Inet6Address
import java.net.InetAddress

/**
 * The default network's addresses the advertiser binds one mDNS responder each to. JmDNS answers only
 * the address family of the address it is bound to, so a dual-stack panel needs two responders.
 * [primary] is the IPv4 address when there is one, so an IPv4 or dual-stack panel keeps the responder
 * it has always had; an IPv6-only panel's primary is its IPv6 address. [secondary] is the IPv6 address
 * of a dual-stack panel.
 */
internal data class MdnsLanAddresses(val primary: String, val secondary: String? = null)

/**
 * The IPv6 address to advertise: global or unique-local, never loopback, link-local or multicast. A
 * stable address is preferred to a temporary (privacy) one, which rotates within a day and would leave
 * Home Assistant holding a dead address; unique-local is preferred to global, which moves with the ISP
 * prefix. Any zone id is dropped.
 */
internal fun defaultNetworkIpv6(
    addresses: Iterable<InetAddress>,
    temporary: Set<InetAddress> = emptySet(),
): String? = addresses.filterIsInstance<Inet6Address>()
    .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isMulticastAddress && !it.isAnyLocalAddress }
    .sortedWith(compareBy<Inet6Address>({ it in temporary }, { (it.address[0].toInt() and 0xfe) != 0xfc }))
    .firstOrNull()?.hostAddress?.substringBefore('%')

internal fun defaultNetworkMdnsAddresses(
    addresses: Iterable<InetAddress>,
    temporary: Set<InetAddress> = emptySet(),
): MdnsLanAddresses? {
    val ipv4 = defaultNetworkIpv4(addresses)
    val ipv6 = defaultNetworkIpv6(addresses, temporary)
    return when {
        ipv4 != null -> MdnsLanAddresses(ipv4, ipv6)
        ipv6 != null -> MdnsLanAddresses(ipv6)
        else -> null
    }
}

/**
 * Retains default-network truth until the service runtime can accept an mDNS revalidation.
 *
 * Android may deliver the initial DHCP link properties while [ServiceRuntimeOwner] is still
 * STARTING, when [ServiceRuntimeOwner.observe] deliberately returns null. The successful-start hook
 * replays the latest retained topology; later callbacks continue through the same serialized path.
 */
internal class MdnsRuntimeReconciler<T : Any>(
    private val owner: ServiceRuntimeOwner<T>,
    private val revalidate: (ServiceRuntimeOwner.Observation<T>, MdnsLanAddresses?) -> Unit,
) {
    private var networkObserved = false
    private var latestAddresses: MdnsLanAddresses? = null

    @Synchronized fun networkChanged(
        addresses: Collection<InetAddress>,
        temporary: Set<InetAddress> = emptySet(),
    ): Boolean {
        networkObserved = true
        latestAddresses = defaultNetworkMdnsAddresses(addresses, temporary)
        return replayLocked()
    }

    @Synchronized fun networkLost(): Boolean {
        networkObserved = true
        latestAddresses = null
        return replayLocked()
    }

    /** Called after a lifecycle transition has published RUNNING. */
    @Synchronized fun runtimeRunning(): Boolean = replayLocked()

    private fun replayLocked(): Boolean {
        if (!networkObserved) return false
        val current = owner.observe() ?: return false
        revalidate(current, latestAddresses)
        return true
    }
}
