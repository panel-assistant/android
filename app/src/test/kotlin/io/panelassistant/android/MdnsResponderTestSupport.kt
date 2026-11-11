package io.panelassistant.android

import android.content.SharedPreferences
import io.panelassistant.android.util.MonotonicDeadline
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo
import javax.jmdns.impl.DNSOutgoing

/** Shared fixtures for the mDNS responder tests, split one test per class so each can take its own test fork. */
internal object MdnsResponderTestSupport {
    /**
     * Watches for [timeoutMs] (4.5 s by default) whatever the network carries. On a LAN with other panels, list returns their
     * cached services at once, so a count of attempts ended before this test's responder answered.
     */
    fun hasAddress(binding: String, name: String, address: String, timeoutMs: Long = 4_500): Boolean =
        JmDNS.create(InetAddress.getByName(binding), "dual-responder-browser").use { browser ->
            val deadline = MonotonicDeadline(timeoutMs)
            while (deadline.remainingMs() > 0) {
                if (browser.list(Config.MDNS_SERVICE_TYPE, minOf(1_500, deadline.remainingMs()))
                        .filter { it.name == name }
                        .flatMap { it.inetAddresses.toList() }
                        .any { it.hostAddress == address }) return@use true
                Thread.sleep(minOf(20, deadline.remainingMs()))
            }
            false
        }

    /**
     * Waits up to 9 s for this test's service with a probe token that also satisfies [accept]. The browser's cache can still
     * hold an earlier registration of the same name, so a caller that re-advertised says which one it is waiting for.
     */
    fun browse(
        browser: JmDNS,
        name: String = "responder-test-panel-$RUN",
        accept: (ServiceInfo) -> Boolean = { true },
    ): ServiceInfo? {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(9)
        while (System.nanoTime() < deadline) {
            val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceAtLeast(1)
            browser.list(Config.MDNS_SERVICE_TYPE, minOf(3_000, remainingMs))
                .firstOrNull { it.name == name && it.getPropertyString("probe") != null && accept(it) }
                ?.let { return it }
            // A cached unrelated service can make list return immediately.
            val remainingNs = deadline - System.nanoTime()
            if (remainingNs > 0) TimeUnit.NANOSECONDS.sleep(minOf(TimeUnit.MILLISECONDS.toNanos(20), remainingNs))
        }
        return null
    }

    fun isResponderReply(outgoing: DNSOutgoing): Boolean = outgoing.isResponse &&
        Thread.currentThread().stackTrace.any { it.className == "javax.jmdns.impl.tasks.Responder" }

    /** Every read answers its default; the advertiser only reads identity from its configuration. */
    fun readOnlyPreferences(): SharedPreferences {
        lateinit var editor: SharedPreferences.Editor
        editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "commit" -> true
                "apply" -> null
                else -> editor
            }
        } as SharedPreferences.Editor
        return Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getAll" -> emptyMap<String, Any?>()
                "contains" -> false
                "edit" -> editor
                "registerOnSharedPreferenceChangeListener", "unregisterOnSharedPreferenceChangeListener" -> null
                "toString" -> "ReadOnlyPreferences"
                else -> args?.getOrNull(1)
            }
        } as SharedPreferences
    }

    const val LOOPBACK = "127.0.0.1"
    const val SECONDARY_LOOPBACK = "127.0.0.2"
    /** Per-JVM suffix: concurrent debug and release test JVMs share the loopback multicast group. */
    val RUN = ProcessHandle.current().pid()
}
