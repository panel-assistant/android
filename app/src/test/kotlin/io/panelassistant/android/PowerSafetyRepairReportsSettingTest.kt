package io.panelassistant.android

import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.os.PowerManager
import io.panelassistant.android.control.BrightnessController
import io.panelassistant.android.control.FakeDaemon
import io.panelassistant.android.control.FakeRootShell
import io.panelassistant.android.control.PowerController
import io.panelassistant.android.control.PowerSafetyController
import io.panelassistant.android.control.RouteSelection
import io.panelassistant.android.device.ScreenOff
import io.panelassistant.android.state.StateConverger
import io.panelassistant.android.testsupport.PanelRuntimeRig
import io.panelassistant.android.util.ServiceRuntimeOwner
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Configure page's power-safety repair turns prevent_idle_dim on outside the command dispatcher.
 * Panel Assistant's prevent_idle_dim switch must learn of it from the repair itself, not from the next
 * 60-second audit. Drives the production caller ([ServiceDisplay.repairPowerSafety], which the
 * `/power-safety/repair` route invokes) against the real state core, with fakes only at the Android
 * power/settings boundary.
 */
internal class PowerSafetyRepairReportsSettingTest : PanelRuntimeRig() {

    private class PowerContext : ContextWrapper(null) {
        private val pm = allocate(PowerManager::class.java)
        private val resolver = object : ContentResolver(null) {}
        override fun getApplicationContext(): Context = this
        override fun getSystemService(name: String): Any? = if (name == Context.POWER_SERVICE) pm else null
        override fun getContentResolver(): ContentResolver = resolver
        override fun getPackageName(): String = "io.panelassistant.android"
    }

    private fun display(rig: Rig): ServiceDisplay {
        val context = PowerContext()
        val runtime = ServiceRuntimeOwner(
            PaneldService.NetworkRuntime(rig.core, rig.commands, allocate(MdnsAdvertiser::class.java)),
            "power-safety-repair-test",
        )
        val display = ServiceDisplay(
            service = context,
            scope = CoroutineScope(Dispatchers.Unconfined),
            teardownBoundary = ServiceTeardownBoundary(),
            config = { rig.config },
            profile = { error("repair reads no profile") },
            runtime = { runtime },
            runtimeInstalled = { true },
            liveSettingAuthority = { error("repair queues no live setting") },
            haLinks = { error("repair touches no HA link") },
            sensors = { error("repair reads no sensor") },
            sensorsReady = { false },
            controls = { error("repair uses no system control") },
        )
        val power = PowerController(context)
        fun set(name: String, value: Any) =
            ServiceDisplay::class.java.getDeclaredField(name).apply { isAccessible = true }.set(display, value)
        set("power", power)
        set("brightness", BrightnessController(context, FakeDaemon()))
        set(
            "powerSafety",
            PowerSafetyController(
                context = context,
                power = power,
                root = FakeRootShell(available = false),
                screenOffMechanism = "brightness_zero",
                screenRouteSelection = { RouteSelection(ScreenOff.BRIGHTNESS_ZERO, null, "test") },
            ),
        )
        return display
    }

    @Test fun aRepairThatTurnsOnPreventIdleDimReportsItToPanelAssistantAtOnce() {
        val rig = rig(configure = { it.setPreventIdleDim(false) })
        try {
            val reported = CopyOnWriteArrayList<String>()
            rig.core.addStateSink { channel, observation ->
                if (channel == "prevent_idle_dim" && observation is StateConverger.Observation.Known) {
                    reported += observation.payload
                }
            }

            display(rig).repairPowerSafety()

            assertTrue("the repair persisted the guard", rig.config.preventIdleDim)
            drainStatePump()
            assertEquals(
                "Panel Assistant's switch was left on OFF until the next audit",
                "ON",
                reported.lastOrNull(),
            )
        } finally { rig.close() }
    }
}
