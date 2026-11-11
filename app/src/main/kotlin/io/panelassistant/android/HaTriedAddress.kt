package io.panelassistant.android

import io.panelassistant.android.util.HaTransportFault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.net.URI

/**
 * The Home Assistant address a blocked screen names, so the person in front of the panel can see what
 * it is trying: the URL, and the IP address its host name resolves to when that is quick to learn.
 */
internal object HaTriedAddress {
    private const val LOOKUP_TIMEOUT_MS = 1_000L
    private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

    /**
     * Null when no address is configured. The lookup never delays the screen by more than a second: a
     * resolver that is still waiting is left to finish on its own and the URL is shown without an IP.
     */
    suspend fun describe(
        url: String,
        fault: HaTransportFault,
        lookup: (String) -> String? = { host -> InetAddress.getAllByName(host).firstOrNull()?.hostAddress },
        timeoutMs: Long = LOOKUP_TIMEOUT_MS,
    ): String? {
        val shown = url.trim().trimEnd('/').takeIf(String::isNotEmpty) ?: return null
        val host = runCatching { URI(shown).host }.getOrNull()
        // A name that did not resolve has no IP to show, and a literal already is one.
        if (host.isNullOrEmpty() || fault == HaTransportFault.DNS || ':' in host || IPV4.matches(host)) return shown
        val resolving = CoroutineScope(Dispatchers.IO).async { runCatching { lookup(host) }.getOrNull() }
        // Timed on a real clock, whatever dispatcher the caller is on.
        val ip = withContext(Dispatchers.IO) { withTimeoutOrNull(timeoutMs) { resolving.await() } }
        return if (ip.isNullOrBlank() || ip == host) shown else "$shown ($ip)"
    }
}
