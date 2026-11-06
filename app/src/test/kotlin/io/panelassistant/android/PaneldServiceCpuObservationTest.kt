package io.panelassistant.android

import android.content.SharedPreferences
import io.panelassistant.android.control.AdbController
import io.panelassistant.android.control.CpuController
import io.panelassistant.android.control.FakeDaemon
import io.panelassistant.android.control.FakeRootShell
import io.panelassistant.android.control.ManagedNetworkAdb
import io.panelassistant.android.control.NetworkAdbDisableTransitionMarker
import io.panelassistant.android.control.PrivilegedRouteObservation
import io.panelassistant.android.control.RelayController
import io.panelassistant.android.control.ZigbeeController
import io.panelassistant.android.control.fakeProfile
import io.panelassistant.android.metrics.OsMetricSource
import io.panelassistant.android.metrics.PanelMetrics
import io.panelassistant.android.platform.Daemon
import io.panelassistant.android.testsupport.scratchDirectory
import java.io.IOException
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaneldServiceCpuObservationTest {
    @Test fun helperRootKeepsTheLiveCpuTierAcrossManagementAndConfigureWithoutSu() {
        val service = PaneldService()
        val directory = scratchDirectory("service-cpu-observation").toFile()
        val previouslyManaged = ManagedNetworkAdb.managed
        try {
            // Keep unrelated ADB observations on the managed-panel path, without host commands.
            ManagedNetworkAdb.managed = true
            val preferences = Proxy.newProxyInstance(
                SharedPreferences::class.java.classLoader,
                arrayOf(SharedPreferences::class.java),
            ) { _, method, arguments ->
                when (method.name) {
                    "contains" -> false
                    "getString", "getBoolean" -> arguments!![1]
                    else -> error("unexpected preference operation: ${method.name}")
                }
            } as SharedPreferences
            val config = Config(preferences)
            val profile = fakeProfile(appCanSu = false)
            val root = FakeRootShell(available = false, suLaunchable = false)
            val helper = object : Daemon by FakeDaemon() {
                override fun cpuGovernor(): String = "performance"
                override fun cpuAvailableGovernors(): List<String> = listOf("powersave", "performance")
            }
            val cpu = CpuController(
                profile, root, helper,
                PanelMetrics(OsMetricSource(helper, root, directRead = { throw IOException("denied") })),
            )
            val controls = PaneldService::class.java.getDeclaredField("controls")
                .apply { isAccessible = true }.get(service) as ServiceSystemControls
            listOf(
                "cpu" to cpu,
                "adb" to AdbController(config, NetworkAdbDisableTransitionMarker(directory.resolve("adb-off")), root),
                "zigbee" to ZigbeeController(profile, root, helper),
                "relay" to RelayController(profile, root),
            ).forEach { (name, value) ->
                ServiceSystemControls::class.java.getDeclaredField(name)
                    .apply { isAccessible = true }.set(controls, value)
            }
            PaneldService::class.java.getDeclaredField("config")
                .apply { isAccessible = true }.set(service, config)

            val observation = PaneldService::class.java.getDeclaredMethod(
                "observeManagementControllers", PrivilegedRouteObservation::class.java,
            ).apply { isAccessible = true }.invoke(service, PrivilegedRouteObservation(false, true))
            fun observed(name: String): Any? = observation.javaClass.getDeclaredMethod(name)
                .apply { isAccessible = true }.invoke(observation)
            assertEquals("Performance", observed("getCpuTier"))
            assertEquals(true, observed("getCpuGovernorsAvailable"))

            val liveValues = PaneldService::class.java.getDeclaredMethod("currentConfigLiveValues")
                .apply { isAccessible = true }.invoke(service) as Map<*, *>
            assertEquals("Performance", liveValues["cpu_governor"])
            assertTrue(root.ran.isEmpty())
            assertTrue(root.outputRan.isEmpty())
            assertTrue(root.isolatedOutputRan.isEmpty())
            assertEquals(0, root.availabilityChecks)
        } finally {
            ManagedNetworkAdb.managed = previouslyManaged
            (PaneldService::class.java.getDeclaredField("scope").apply { isAccessible = true }
                .get(service) as CoroutineScope).cancel()
            directory.deleteRecursively()
        }
    }
}
