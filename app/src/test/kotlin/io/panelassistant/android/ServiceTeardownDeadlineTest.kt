package io.panelassistant.android

import android.content.ContextWrapper
import android.content.SharedPreferences
import io.panelassistant.android.util.ServiceRuntimeOwner
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A real mDNS responder takes seconds to retire, because JmDNS waits for its goodbye announcements.
 * The service stop must not spend its main-thread deadline on that wait, and the goodbye must still
 * reach the network.
 */
class ServiceTeardownDeadlineTest {
    @Test fun aRetiringMdnsResponderDoesNotHoldTheServiceStopPastItsDeadline() {
        val browser = JmDNS.create(InetAddress.getByName(LOOPBACK), "teardown-test-browser")
        val seen = CountDownLatch(1)
        val removed = CountDownLatch(1)
        browser.addServiceListener(Config.MDNS_SERVICE_TYPE, object : ServiceListener {
            override fun serviceAdded(event: ServiceEvent) {
                if (event.name == PANEL) seen.countDown()
            }
            override fun serviceRemoved(event: ServiceEvent) {
                if (event.name == PANEL) removed.countDown()
            }
            override fun serviceResolved(event: ServiceEvent) = Unit
        })
        val locksHeld = AtomicInteger()
        val advertiser = MdnsAdvertiser(
            context = ContextWrapper(null),
            config = Config(readOnlyPreferences()),
            runtimePanelId = PANEL,
            runtimeFriendlyName = "Teardown Test Panel",
            runtimeHttpPort = 8888,
            acquireMulticastLock = {
                locksHeld.incrementAndGet()
                val release: () -> Unit = { locksHeld.decrementAndGet() }
                release
            },
            discoveryId = { "teardown-test-did" },
        )
        try {
            advertiser.start(LOOPBACK)
            assertTrue("the browser never saw the advertisement", seen.await(10, TimeUnit.SECONDS))
            val service = PaneldService()
            fenceProcessExit(service)
            installRuntime(service, retiredBridge(), advertiser)

            try {
                service.onDestroy()
            } catch (_: NullPointerException) {
                // The JVM stub has no Application, so the application-state flush that follows a timely
                // runtime stop cannot run here. On an overrun it is skipped, and nothing throws.
            }

            val runtime = field(service, "runtime") as ServiceRuntimeOwner<*>
            assertTrue("runtime teardown must finish inside the service deadline", runtime.isStopped())
            assertTrue("the goodbye must still reach the network", removed.await(15, TimeUnit.SECONDS))
            val released = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (locksHeld.get() != 0 && System.nanoTime() < released) Thread.sleep(20)
            assertEquals("the retired responder must release its multicast lock", 0, locksHeld.get())
        } finally {
            advertiser.stop()
            browser.close()
        }
    }

    /** A bridge whose terminal retirement already completed: `stop` returns it without new work. */
    private fun retiredBridge(): MqttBridge {
        val bridge = allocate(MqttBridge::class.java)
        val retirement = MqttRetirement().apply {
            ownersDrained.complete(true)
            finalization.complete(Unit)
        }
        MqttBridge::class.java.getDeclaredField("retirement").apply { isAccessible = true }
            .set(bridge, AtomicReference(retirement))
        return bridge
    }

    private fun installRuntime(service: PaneldService, mqtt: MqttBridge, mdns: MdnsAdvertiser) {
        val type = Class.forName("${PaneldService::class.java.name}\$NetworkRuntime")
        val pair = type.declaredConstructors.single().apply { isAccessible = true }.newInstance(mqtt, mdns)
        val owner = ServiceRuntimeOwner<Any>(pair, "test-runtime-lane")
        owner.start { }
        setField(service, "runtime", owner)
    }

    /**
     * The asynchronous finalizer ends in `exitProcess` on an incomplete teardown. Claiming the one
     * recovery slot first makes every later boundary run return before it, so the test JVM survives.
     */
    private fun fenceProcessExit(service: PaneldService) {
        val boundary = field(service, "teardownBoundary") as ServiceTeardownBoundary
        assertTrue(boundary.recordCompletionAndClaimRecovery(false))
    }

    private fun field(service: PaneldService, name: String): Any? =
        PaneldService::class.java.getDeclaredField(name).apply { isAccessible = true }.get(service)

    private fun setField(service: PaneldService, name: String, value: Any?) {
        PaneldService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> allocate(type: Class<T>): T {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type) as T
    }

    /** Every read answers its default; the advertiser only reads identity from its configuration. */
    private fun readOnlyPreferences(): SharedPreferences {
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

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        /** Per-JVM suffix: concurrent debug and release test JVMs share the loopback multicast group. */
        val PANEL = "teardown-test-panel-${ProcessHandle.current().pid()}"
    }
}
