package io.panelassistant.android

import android.os.Handler
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import io.panelassistant.android.DashboardRendererRig.Companion.HA
import io.panelassistant.android.DashboardRendererRig.Companion.allocate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A bridge-phase block (the secure listener cannot attach, or the frontend handshake was missed)
 * replaces the admission verdict with BLOCKED, so the compatibility pass that admitted the URL must stop
 * standing: a relaunch reuses a standing admission instead of re-admitting, and status then keeps naming
 * the block over a dashboard that rendered. `showDashboardBlocked` is the one route that withdraws it, so
 * every bridge-phase block must go through it.
 *
 * Source-text reason for the routing check: the sites are unreachable on the JVM: each passes
 * `getString(...)`, which the mockable android.jar answers with null, and Kotlin's call-site non-null
 * check throws before the block route is entered, on the base and on the fix alike. The route itself
 * runs, so what the withdrawal changes is checked by behaviour.
 */
class DashboardBridgeBlockAdmissionTest {
    @Test fun everyBridgePhaseBlockWithdrawsTheStandingAdmission() {
        val file = File("src/main/kotlin/io/panelassistant/android/DashboardActivity.kt")
        assertTrue("missing ${file.absolutePath}", file.isFile)
        val source = file.readText()
        val outcomes = Regex("""AdmissionOutcome\.(BRIDGE_ATTACH_FAILED|BRIDGE_HANDSHAKE_MISSED)\b""").findAll(source).toList()
        assertTrue("no bridge-phase block found", outcomes.isNotEmpty())
        val direct = outcomes.mapNotNull { match ->
            val before = source.substring(0, match.range.first)
            val routed = before.lastIndexOf("showDashboardBlocked(")
            val bypass = before.lastIndexOf("statusScreens.showBlockedAdmissionScreen(")
            if (bypass > routed) "line ${before.count { it == '\n' } + 1}: ${match.value}" else null
        }
        assertEquals("bridge-phase blocks drawn without withdrawing the admission", emptyList<String>(), direct)
    }

    /**
     * A dashboard build that arrives after the block (a home dashboard answer still in flight, or the launch
     * accelerator) must wait for a fresh admission. Building on the old one put a dashboard on screen while
     * status still named the block.
     */
    @Test fun aDashboardBuildAfterABridgeBlockWaitsForAFreshAdmission() = DashboardRendererRig().use { rig ->
        RendererAdmissionRuntime.reset()
        try {
            rig.show(RecordingWebView("$HA/lovelace"))
            rig.admitted("lovelace")
            rig.phase = RendererPhase.CONNECTED
            rig.set("statusScreens", statusScreensFor(rig))
            val generation = rig.get("rendererGeneration")

            // The screen records the block for status, then cannot draw it: the mockable jar has no strings.
            runCatching { rig.call("showDashboardBlocked", "title", "detail", AdmissionOutcome.BRIDGE_ATTACH_FAILED) }
            rig.call("buildCompatibleAndLoad", rig.config, "lovelace")

            assertEquals("status names the block", RendererAdmissionState.BLOCKED, RendererAdmissionRuntime.current()?.record?.state)
            assertEquals("no dashboard was built over the block", generation, rig.get("rendererGeneration"))
        } finally {
            RendererAdmissionRuntime.reset()
        }
    }

    /** The rig's status screens have no host; the block screen needs one to record its verdict. */
    private fun statusScreensFor(rig: DashboardRendererRig) = DashboardStatusScreens(
        allocate(AppCompatActivity::class.java),
        allocate(Handler::class.java),
        CoroutineScope(Dispatchers.Unconfined),
        rig.get("admissionRetryPolicy") as AdmissionRetryPolicy,
        object : DashboardStatusScreens.Host {
            override val activityOwner get() = rig.owner
            override val destroyed get() = false
            override val hasWeb get() = true
            override fun teardownWeb() = Unit
            override var root: FrameLayout? = null
            override fun detachLifecycleBar() = Unit
            override fun activeNetworkPresent() = true
            override fun retryAdmission(resetBackoff: Boolean) = Unit
            override fun onAdmissionRetryDue() = Unit
            override fun redrawCoverPage() = Unit
        },
    )
}
