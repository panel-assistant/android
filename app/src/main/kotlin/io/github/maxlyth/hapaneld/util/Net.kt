package io.github.maxlyth.hapaneld.util

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface

/** Reachability candidates for hello, ordered by default network then other active interfaces. */
fun localPanelAddresses(
    primary: Iterable<InetAddress> = emptyList(),
    secondary: () -> List<InetAddress> = {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
    },
): List<String> = (primary.asSequence() + runCatching(secondary).getOrDefault(emptyList()).asSequence())
    .filter { address ->
        (address is Inet4Address || address is Inet6Address) &&
            !address.isAnyLocalAddress && !address.isLoopbackAddress &&
            !address.isLinkLocalAddress && !address.isMulticastAddress
    }
    .mapNotNull { it.hostAddress?.substringBefore('%') }
    .distinct()
    .take(16)
    .toList()

/**
 * First non-loopback IPv4 address of an up interface (works for both Wi-Fi and Ethernet panels),
 * or null if none. Used for the `configuration_url` ("Visit" link) and the info page.
 */
fun localIpv4(): String? {
    runCatching {
        for (nif in NetworkInterface.getNetworkInterfaces()) {
            if (!nif.isUp || nif.isLoopback) continue
            for (addr in nif.inetAddresses) {
                if (!addr.isLoopbackAddress && addr is Inet4Address) return addr.hostAddress
            }
        }
    }
    return null
}

/**
 * First global-scope IPv6 address (skips loopback and link-local `fe80::`), with any zone id stripped,
 * or null if none. Covers both ULA (`fd00::/8`) and global-unicast addresses. Informational for the
 * info page — IPv4 stays the primary address for `configuration_url` and MQTT discovery.
 */
fun localIpv6(): String? {
    runCatching {
        for (nif in NetworkInterface.getNetworkInterfaces()) {
            if (!nif.isUp || nif.isLoopback) continue
            for (addr in nif.inetAddresses) {
                if (addr is Inet6Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                    return addr.hostAddress?.substringBefore('%') // drop the %iface zone id
                }
            }
        }
    }
    return null
}

/**
 * Parse a request source string (a raw peer IP, or an info-page address value) into an [InetAddress],
 * or null if it does not parse. Strips any `%zone` id and a leading `/` (as `remoteAddress` /
 * `InetAddress.toString()` can carry) before resolving. Because these are already numeric IP literals
 * in practice, `getByName` does not perform a blocking DNS lookup. Parsing with [InetAddress] also unmaps
 * an IPv4-mapped IPv6 form (`::ffff:a.b.c.d`) to its IPv4 address, so a dual-stack bind cannot smuggle a
 * public IPv4 past the [isLanLocal] check.
 */
private fun parseAddress(host: String): InetAddress? =
    runCatching { InetAddress.getByName(host.substringBefore('%').removePrefix("/")) }.getOrNull()

/**
 * True if [addr] is a LAN-local address: loopback, link-local, RFC1918 site-local, wildcard/any-local, or
 * IPv6 ULA (`fc00::/7`). These are exactly the five predicates a globally-routable source must fail.
 */
private fun isLanLocal(addr: InetAddress): Boolean =
    addr.isLoopbackAddress || addr.isLinkLocalAddress || addr.isSiteLocalAddress || addr.isAnyLocalAddress ||
        (addr is Inet6Address && (addr.address[0].toInt() and 0xfe) == 0xfc) // fc00::/7 ULA

/**
 * True if [host] parses to a loopback address. Unparseable values return false. Used by the hardened-mode
 * gate that trusts only software already on the panel (loopback), where a broader LAN-local match would be
 * too permissive.
 */
fun isLoopbackPeer(host: String): Boolean = parseAddress(host)?.isLoopbackAddress ?: false

/**
 * True if [host] (the request's source IP) is a LAN-local address: loopback, RFC1918, link-local, IPv6 ULA,
 * or the wildcard address. Global/public sources — and unparseable values — return false → 403. See
 * [parseAddress] for the IPv4-mapped-IPv6 unmapping and zone/`/` stripping this relies on.
 */
fun isLocalSource(host: String): Boolean = parseAddress(host)?.let(::isLanLocal) ?: false

/**
 * True only for a parseable, globally-routable address (not loopback / RFC1918 / ULA / link-local /
 * any-local). Unparseable values (e.g. "—") return false → not routable. The exact negation of
 * [isLocalSource] for parseable input; both return false for unparseable input.
 */
fun isRoutable(host: String): Boolean = parseAddress(host)?.let { !isLanLocal(it) } ?: false

/**
 * True if [host] is an address a phone on the same network could actually open.
 *
 * Deliberately NOT [isRoutable], which is its near-opposite here: that predicate exists to reject a
 * request source that is not globally routable, so it turns away exactly the RFC1918 and ULA addresses
 * every panel on a home network actually has. The question this one asks is different — "would printing
 * this address on the screen give somebody something they can reach" — and only three families fail it:
 * loopback, which resolves on the scanning phone instead of on the panel; link-local, which is what a
 * panel shows after its DHCP lease failed and is unreachable from anywhere useful; and the wildcard.
 *
 * This matters because [io.github.maxlyth.hapaneld.util.LocalAdminEndpoint.externalUrl] falls back to
 * `127.0.0.1` when it is handed nothing, so a panel with no network produces a plausible, scannable and
 * completely useless URL rather than an obviously absent one.
 */
fun isScannableHost(host: String): Boolean = parseAddress(host)?.let {
    !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isAnyLocalAddress
} ?: false

/** The first of [ipv4] then [ipv6] that somebody could actually reach, or null when neither is. */
fun scannableHost(ipv4: String?, ipv6: String?): String? =
    listOfNotNull(ipv4, ipv6).firstOrNull { it.isNotBlank() && isScannableHost(it) }
