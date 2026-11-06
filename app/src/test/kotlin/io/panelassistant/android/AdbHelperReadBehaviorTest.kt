package io.panelassistant.android

import android.content.SharedPreferences
import io.panelassistant.android.control.*
import io.panelassistant.android.metrics.PanelMetrics
import io.panelassistant.android.testsupport.scratchDirectory
import io.panelassistant.android.util.ZigbeeReadFixture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

/** Existing service/controller/transport APIs keep this caller proof compilable on the old base. */
class AdbHelperReadBehaviorTest {
    @Test fun externalAdbObservationUsesHelperEvenWhenDirectSuIsUnavailable() = withObservation(false, "PROPS=1 LISTEN=0\n") { active, wire ->
        assertEquals(true, active)
        assertEquals(1, wire.bytes.size)
    }

    @Test fun persistedAdbReportsListenerRatherThanItsOwnEnabledProperties() = withObservation(true, "PROPS=1 LISTEN=0\n") { active, wire ->
        assertEquals(false, active)
        assertEquals(1, wire.bytes.size)
    }

    @Test fun missingAndUnknownHelperRemainUnknownInsteadOfOff() {
        for (reply in listOf(null, "ERR\n", "PROPS=? LISTEN=?\n")) withObservation(false, reply) { active, _ -> assertNull(active) }
    }

    @Test fun managedObservationRemainsOffWithoutReadingHelper() = withObservation(false, "PROPS=1 LISTEN=1\n", managed = true) { active, wire ->
        assertEquals(false, active)
        assertTrue(wire.bytes.isEmpty())
    }

    private fun withObservation(persisted: Boolean, reply: String?, managed: Boolean = false, check: (Any?, ZigbeeReadFixture) -> Unit) {
        val service = PaneldService()
        val directory = scratchDirectory("service-adb-observation").toFile()
        val previous = ManagedNetworkAdb.managed
        try {
            ManagedNetworkAdb.managed = managed
            val preferences = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
                when (method.name) {
                    "contains" -> args!![0] == "network_adb"
                    "getBoolean" -> if (args!![0] == "network_adb") persisted else args[1]
                    "getString" -> args!![1]
                    else -> error("unexpected preference operation ${method.name}")
                }
            } as SharedPreferences
            val config = Config(preferences)
            val root = FakeRootShell(available = false, suLaunchable = false)
            val wire = ZigbeeReadFixture().apply {
                version = "HELPER version=1.10.0 proto=1.10"
                replies["ADBSTATE"] = reply?.toByteArray()
            }
            val adb = AdbController(config, NetworkAdbDisableTransitionMarker(directory.resolve("adb-off")), root)
            // Candidate injection uses its normal Daemon field; old code still executes its real reader.
            adb.javaClass.declaredFields.firstOrNull { it.name == "daemon" }?.apply { isAccessible = true }?.set(adb, wire.client)
            val profile = fakeProfile(appCanSu = false)
            val controls = PaneldService::class.java.getDeclaredField("controls").apply { isAccessible = true }.get(service) as ServiceSystemControls
            listOf("adb" to adb, "cpu" to CpuController(profile, root, FakeDaemon(), PanelMetrics(FakeMetricSource())), "zigbee" to ZigbeeController(profile, root, FakeDaemon()), "relay" to RelayController(profile, root)).forEach { (name, value) ->
                ServiceSystemControls::class.java.getDeclaredField(name).apply { isAccessible = true }.set(controls, value)
            }
            PaneldService::class.java.getDeclaredField("config").apply { isAccessible = true }.set(service, config)
            val observation = PaneldService::class.java.getDeclaredMethod("observeManagementControllers", PrivilegedRouteObservation::class.java).apply { isAccessible = true }
                .invoke(service, PrivilegedRouteObservation(false, true))
            val active = observation.javaClass.getDeclaredMethod("getNetworkAdbActive").apply { isAccessible = true }.invoke(observation)
            check(active, wire)
            assertTrue(root.ran.isEmpty()); assertTrue(root.outputRan.isEmpty()); assertTrue(root.isolatedOutputRan.isEmpty()); assertEquals(0, root.availabilityChecks)
        } finally {
            ManagedNetworkAdb.managed = previous
            (PaneldService::class.java.getDeclaredField("scope").apply { isAccessible = true }.get(service) as CoroutineScope).cancel()
            directory.deleteRecursively()
        }
    }
}
