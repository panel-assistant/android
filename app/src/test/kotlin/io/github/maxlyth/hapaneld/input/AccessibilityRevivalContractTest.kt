package io.github.maxlyth.hapaneld.input

import io.github.maxlyth.hapaneld.testsupport.TestSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The route back when the process is revived only for the accessibility bind.
 *
 * A foreground-start timeout kills the process *and* drops the started-service record, so nothing is
 * waiting to be re-created by START_STICKY. The accessibility service is bound by the system
 * independently of that record, so the process can return for it alone: no `:8888`, no MQTT, no log
 * shipping. Observed lasting over eight hours until `MainActivity` was started by hand.
 *
 * Reviving the service is easy; reviving it *exactly once*, without fighting a deliberate stop, is
 * the part worth pinning. None of it runs on the JVM, so the wiring is pinned by source text.
 */
class AccessibilityRevivalContractTest {

    private val accessibility by lazy { TestSources.kotlin("input/PanelAccessibilityService.kt").readText() }
    private val service by lazy { TestSources.kotlin("PaneldService.kt").readText() }

    private fun body(source: String, name: String): String {
        val start = source.indexOf(name)
        assertTrue("$name is present", start >= 0)
        val open = source.indexOf('{', start)
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(open, i + 1)
            }
        }
        error("unbalanced $name")
    }

    @Test fun connectingTheAccessibilityBindBringsTheServiceUp() {
        val connected = body(accessibility, "override fun onServiceConnected()")
        assertTrue("the revival is issued from the bind", connected.contains("revivePaneldService()"))
        val revive = body(accessibility, "private fun revivePaneldService")
        assertTrue("through the single service entry point", revive.contains("PaneldService.start(this)"))
    }

    /**
     * Exactly once. `startForegroundService` is delivered to the one existing instance when the
     * service is already running, and `onStartCommand` makes that redelivery inert — which is what
     * stops a second Ktor bind on `:8888`. Both halves have to stay true.
     */
    @Test fun aRevivalCannotProduceASecondServiceInstance() {
        val revive = body(accessibility, "private fun revivePaneldService")
        assertFalse(
            "the revival must not construct or bind a service itself; it goes through start()",
            revive.contains("Intent(") || revive.contains("bindService") || revive.contains("startService("),
        )
        val start = body(service, "override fun onStartCommand")
        assertTrue(
            "a redelivered start is inert: this is what keeps :8888 bound exactly once",
            start.contains("if (started) return START_STICKY"),
        )
        // Exactly one reference to the service in the executable revival: one start, one route. Counted
        // over the function body rather than the file, so KDoc mentions do not inflate it.
        assertEquals(
            "PaneldService.start is the only route the accessibility service may use",
            1,
            Regex("PaneldService\\.").findAll(revive).count(),
        )
    }

    /**
     * A deliberate stop must survive. The refusal lives in `PaneldService.start` rather than here,
     * because that is the single entry point every caller shares — a second copy in the accessibility
     * service would be a second definition of the same contract, free to drift out of step.
     */
    @Test fun aDeliberateHoldIsRefusedAtTheSingleEntryPoint() {
        val start = body(service, "fun start(context: Context)")
        assertTrue("an armed upgrade holds the service down", start.contains("UpgradeShutdownCoordinator.isArmed()"))
        assertTrue("a retired bridge stays idle", start.contains("StartDisposition.RETIRED_BRIDGE"))
        assertTrue("guard-db maintenance redirects", start.contains("GuardDbProcessAdmission.maintenanceRequired()"))
        val revive = body(accessibility, "private fun revivePaneldService")
        assertFalse(
            "the accessibility service must not re-state the hold conditions: one definition only",
            revive.contains("isArmed") || revive.contains("RETIRED_BRIDGE"),
        )
    }

    /**
     * `onServiceConnected` throwing takes the accessibility bind down with it, and on Android 12+ a
     * foreground start from a background process can be refused outright. The revival is best-effort
     * by construction.
     */
    @Test fun aRefusedRevivalNeverTakesDownTheAccessibilityBind() {
        val revive = body(accessibility, "private fun revivePaneldService")
        assertTrue("the start is wrapped", revive.contains("runCatching"))
        assertTrue("and a refusal is observable rather than silent", revive.contains("Log.w"))
    }

    /**
     * The guard-db admission check still gates the bind, and the revival sits behind it: a process in
     * maintenance must not be handed an ordinary service start.
     */
    @Test fun maintenanceStillRefusesTheBindBeforeAnyRevival() {
        val connected = body(accessibility, "override fun onServiceConnected()")
        val admission = connected.indexOf("GuardDbProcessAdmission.ordinaryMutationsAllowed()")
        val revive = connected.indexOf("revivePaneldService()")
        assertTrue("admission is checked", admission >= 0)
        assertTrue("and it is checked before the revival", admission < revive)
        assertTrue("a refused admission returns rather than falling through", connected.contains("return"))
    }
}
