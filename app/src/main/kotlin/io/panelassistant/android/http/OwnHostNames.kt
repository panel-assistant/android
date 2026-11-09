package io.panelassistant.android.http

import android.content.Context
import android.net.ConnectivityManager
import android.os.SystemClock
import android.provider.Settings
import io.panelassistant.android.util.SystemProps

/**
 * The panel's own DNS names, accepted by the `:8888` Host guard ([OriginGuard.hostAllowed]) without any
 * operator setting. A router that registers DHCP clients in DNS answers the panel's host name (for
 * example `sonoff-nspanelpro` and `sonoff-nspanelpro.home.example`), so opening the page or adding the
 * panel to Home Assistant by that name must work out of the box.
 *
 * Every entry is an exact name: the host label the panel itself reports, bare and qualified with each
 * search domain of the active network. Nothing is a wildcard, so a rebound attacker name is still refused.
 */
internal object OwnHostNames {
    private val LABEL = Regex("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?")

    /** Exact lowercase names for [labels] (raw host names or device names) and search [domains]. */
    fun names(labels: Collection<String?>, domains: Collection<String?>): Set<String> {
        val hosts = labels.mapNotNull(::hostName).toSet()
        val suffixes = domains.mapNotNull(::hostName).toSet()
        return buildSet {
            for (host in hosts) {
                add(host)
                if ('.' !in host) suffixes.forEach { add("$host.$it") }
            }
        }
    }

    /**
     * A DNS name for [raw], or null. A device name such as `NSPanel Pro` maps to `nspanel-pro`, the
     * same shape Android's DHCP client sends; a dotted value must already be a valid DNS name.
     */
    fun hostName(raw: String?): String? {
        val value = raw?.trim()?.trimEnd('.')?.lowercase()?.ifEmpty { null } ?: return null
        if ('.' in value) return value.takeIf { name -> name.length <= 253 && name.split('.').all(LABEL::matches) }
        val label = value.replace(Regex("[^a-z0-9-]+"), "-").trim('-').take(63).trimEnd('-')
        return label.takeIf(LABEL::matches)
    }

    /** Reads the live names on the device; cached briefly because the guard runs on every request. */
    fun android(context: Context): () -> Set<String> {
        var cached: Set<String> = emptySet()
        var readAt = Long.MIN_VALUE
        return {
            synchronized(this) {
                val now = SystemClock.elapsedRealtime()
                if (readAt == Long.MIN_VALUE || now - readAt > CACHE_MS) {
                    cached = runCatching { read(context) }.getOrDefault(emptySet())
                    readAt = now
                }
                cached
            }
        }
    }

    private fun read(context: Context): Set<String> {
        val deviceName = runCatching {
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        }.getOrNull()
        val domains = runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.getLinkProperties(cm.activeNetwork)?.domains
        }.getOrNull()
        return names(
            labels = listOf(SystemProps.get("net.hostname"), deviceName),
            domains = domains.orEmpty().split(Regex("[\\s,]+")),
        )
    }

    private const val CACHE_MS = 30_000L
}
