package io.panelassistant.android

import java.net.InetAddress

/**
 * Pure decision for "could automatic Home Assistant discovery have worked on this panel at all, and if
 * not, why?" — so setup can tell a user to type an address instead of leaving them at an empty field.
 *
 * mDNS is link-local by definition, so a panel on a different network segment from Home Assistant can
 * never discover it. [MdnsAdvertiser] already knows this — an explicitly configured broker that matches
 * no local HA service is deliberately treated as "its HA is across a tunnel" — but it throws the
 * knowledge away by returning a bare null, which a caller cannot tell apart from "found nothing yet".
 * That ambiguity is what makes setup on a routed/tunnelled panel look broken rather than inapplicable:
 * the field simply stays blank and nothing explains why.
 *
 * Outcomes are ranked by how much is actually known, and the wording must never overclaim.
 * [DiscoveryReason.MDNS_NOT_RUNNING] and [DiscoveryReason.NO_MULTICAST_LOCK] are facts about this panel
 * (we did not, or could not, listen). [DiscoveryReason.BROKER_NOT_ON_LINK] is a sound inference from
 * addresses and interface prefix lengths, and holds whether or not mDNS itself worked.
 * [DiscoveryReason.NO_RESPONSES_AT_ALL] is only a guess and is phrased as one.
 *
 * Pure — no Android imports, unit-tested in HaDiscoveryTest.
 */
enum class DiscoveryOutcome {
    /** No browse has been run yet in this process. */
    NOT_ATTEMPTED,

    /** A browse is in flight; its result is not known yet. */
    SEARCHING,

    /** Home Assistant was discovered. */
    FOUND,

    /** Discovery ran and could plausibly have worked, but nothing matched. Retrying may help. */
    NONE_FOUND,

    /** Discovery cannot work on this panel/network. Retrying will not help; the user must type the address. */
    UNAVAILABLE,
}

enum class DiscoveryReason {
    NONE,

    /** The mDNS stack is not running, so nothing was listened for. Certain. */
    MDNS_NOT_RUNNING,

    /** No multicast lock, so multicast reception is not guaranteed. Certain. */
    NO_MULTICAST_LOCK,

    /** The configured broker's address is outside every local interface prefix. Sound inference. */
    BROKER_NOT_ON_LINK,

    /** A full browse saw no mDNS services of any kind. A guess — phrase it as one. */
    NO_RESPONSES_AT_ALL,

    /** The browse itself failed. */
    ERROR,
}

/**
 * One local interface prefix, e.g. `192.168.1.42/24`. [prefixLength] of zero or less means the platform
 * did not report one; such an entry is unusable and is skipped rather than guessed at (assuming /24
 * would silently misjudge a /16 or /22 LAN).
 */
data class LocalPrefix(val address: String, val prefixLength: Int)

data class DiscoveryResult(
    val outcome: DiscoveryOutcome = DiscoveryOutcome.NOT_ATTEMPTED,
    /** The discovered base URL, only when [outcome] is [DiscoveryOutcome.FOUND]. */
    val value: String = "",
    val reason: DiscoveryReason = DiscoveryReason.NONE,
    /** Wall-clock of the attempt this result describes; zero when never attempted. */
    val attemptedAtMs: Long = 0L,
) {
    /** True when retrying cannot help, so the UI should ask the user to type the address instead. */
    val hopeless: Boolean get() = outcome == DiscoveryOutcome.UNAVAILABLE
}

/** Sentinel for [HaDiscovery.classify]'s `servicesSeen` when the caller cannot count responses. */
const val SERVICES_SEEN_UNKNOWN = -1

object HaDiscovery {
    /**
     * Prefer the mDNS record's own hostname over an IP-literal advertised URL.
     *
     * Home Assistant instances commonly advertise `internal_url`/`base_url` in IP form; suggestions
     * built from that pin panels to one IPv4 address (hardware review: "still a raw IPv4"). The record's
     * `server` field (`homeassistant.local.`-style) names the same machine — same trust level, same
     * record — and resolves over IPv6 too, so an IP-literal host is rewritten to it. A hostname-form
     * advertised URL is left untouched, and any parse trouble returns the URL unchanged.
     */
    fun preferServerHostname(url: String?, serverHost: String?): String? {
        url ?: return null
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return url
        if (!hostIsIpLiteral(host)) return url
        val name = serverHost?.trim()?.trimEnd('.')
            ?.takeIf { it.isNotBlank() && !hostIsIpLiteral(it) } ?: return url
        return runCatching {
            val u = java.net.URI(url)
            java.net.URI(u.scheme, null, name, u.port, u.path, u.query, u.fragment).toString()
        }.getOrDefault(url)
    }

    private fun hostIsIpLiteral(host: String): Boolean {
        val bare = host.removePrefix("[").removeSuffix("]")
        return bare.contains(':') || bare.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))
    }

    /**
     * Whether the broker — and therefore Home Assistant — is off-link, and so structurally undiscoverable
     * by mDNS. Returns null when this cannot be judged, which the caller must treat as "don't know" and
     * degrade to [DiscoveryOutcome.NONE_FOUND] rather than assert anything.
     *
     * Unjudgeable cases, all deliberately null rather than a guess: no broker addresses to test; no local
     * prefix reported a length; or the broker is only reachable in an address family for which we have no
     * local prefix (e.g. an IPv6-only broker on a panel that reported only IPv4 prefixes).
     *
     * A broker that is on-link in *any* family wins immediately — the broker host is reachable without a
     * router, so mDNS to that segment is possible.
     */
    fun brokerOffLink(brokerIps: List<String>, localPrefixes: List<LocalPrefix>): Boolean? {
        val targets = brokerIps.mapNotNull { parseIpLiteral(it) }
        if (targets.isEmpty()) return null
        val usable = localPrefixes.mapNotNull { prefix ->
            val base = parseIpLiteral(prefix.address) ?: return@mapNotNull null
            if (prefix.prefixLength <= 0) null else base to prefix.prefixLength
        }
        if (usable.isEmpty()) return null
        var judged = false
        for (target in targets) {
            val sameFamily = usable.filter { (base, _) -> base.address.size == target.address.size }
            if (sameFamily.isEmpty()) continue // no comparable local prefix — cannot judge THIS address
            judged = true
            if (sameFamily.any { (base, bits) -> sharesPrefix(base, target, bits) }) return false
        }
        return if (judged) true else null
    }

    /**
     * Fold the probe signals into one honest outcome. Order is the honesty ranking: a successful discovery
     * settles it; then the two facts about this panel; then the address-based inference; then, last and
     * weakest, the "nothing answered at all" guess.
     *
     * @param brokerOffLink the verdict from [brokerOffLink] — null means "could not judge"
     * @param servicesSeen  mDNS services of any type observed, or [SERVICES_SEEN_UNKNOWN] to skip the guess
     */
    fun classify(
        mdnsRunning: Boolean,
        multicastLockHeld: Boolean,
        brokerConfigured: Boolean,
        brokerOffLink: Boolean?,
        servicesSeen: Int,
        discoveredUrl: String,
        attemptedAtMs: Long,
        failed: Boolean = false,
    ): DiscoveryResult {
        fun result(outcome: DiscoveryOutcome, reason: DiscoveryReason = DiscoveryReason.NONE) =
            DiscoveryResult(outcome = outcome, reason = reason, attemptedAtMs = attemptedAtMs)

        if (discoveredUrl.isNotBlank()) {
            return DiscoveryResult(DiscoveryOutcome.FOUND, discoveredUrl, DiscoveryReason.NONE, attemptedAtMs)
        }
        // Off-link is checked before the panel-local facts only when those facts don't apply: if we never
        // listened, saying "HA is on another segment" would claim a search we didn't run.
        if (!mdnsRunning) return result(DiscoveryOutcome.UNAVAILABLE, DiscoveryReason.MDNS_NOT_RUNNING)
        if (!multicastLockHeld) return result(DiscoveryOutcome.UNAVAILABLE, DiscoveryReason.NO_MULTICAST_LOCK)
        if (brokerConfigured && brokerOffLink == true) {
            return result(DiscoveryOutcome.UNAVAILABLE, DiscoveryReason.BROKER_NOT_ON_LINK)
        }
        if (failed) return result(DiscoveryOutcome.UNAVAILABLE, DiscoveryReason.ERROR)
        if (servicesSeen == 0) return result(DiscoveryOutcome.UNAVAILABLE, DiscoveryReason.NO_RESPONSES_AT_ALL)
        return result(DiscoveryOutcome.NONE_FOUND)
    }

    /**
     * Plain-language reason automatic discovery could not work, for use inside a sentence — null when the
     * outcome is not [DiscoveryOutcome.UNAVAILABLE]. Confidence is carried in the grammar: the certain and
     * inferred reasons state what is so, the guess says "may".
     */
    fun unavailableExplanation(result: DiscoveryResult): String? = when {
        result.outcome != DiscoveryOutcome.UNAVAILABLE -> null
        else -> when (result.reason) {
            DiscoveryReason.BROKER_NOT_ON_LINK ->
                "Home Assistant is on a different network segment from this panel, and automatic discovery only works within one segment"
            DiscoveryReason.MDNS_NOT_RUNNING ->
                "this panel's network discovery service is not running"
            DiscoveryReason.NO_MULTICAST_LOCK ->
                "this panel cannot receive network discovery traffic"
            DiscoveryReason.NO_RESPONSES_AT_ALL ->
                "nothing answered this panel's discovery request, so Home Assistant may be on another network segment or discovery traffic may be blocked"
            DiscoveryReason.ERROR ->
                "this panel's network discovery attempt failed"
            DiscoveryReason.NONE -> null
        }
    }

    /** Whether [base] and [target] agree on their leading [bits] bits. Same-length inputs only. */
    private fun sharesPrefix(base: InetAddress, target: InetAddress, bits: Int): Boolean {
        val a = base.address
        val b = target.address
        if (a.size != b.size || bits <= 0 || bits > a.size * 8) return false
        val wholeBytes = bits / 8
        for (i in 0 until wholeBytes) if (a[i] != b[i]) return false
        val remainder = bits % 8
        if (remainder == 0) return true
        val mask = (0xFF shl (8 - remainder)) and 0xFF
        return (a[wholeBytes].toInt() and mask) == (b[wholeBytes].toInt() and mask)
    }

    /**
     * Parse an IP literal only — never a hostname, so this can never trigger a DNS lookup from a pure
     * decision path. Brackets and an IPv6 zone index are tolerated because that is how addresses arrive
     * from broker URLs and from `NetworkInterface`.
     */
    internal fun parseIpLiteral(value: String): InetAddress? {
        val bare = value.trim().removeSurrounding("[", "]").substringBefore('%')
        if (bare.isEmpty() || !bare.contains('.') && !bare.contains(':')) return null
        if (!bare.all { it.isDigit() || it in "abcdefABCDEF.:" }) return null
        return runCatching { InetAddress.getByName(bare) }.getOrNull()
    }
}
