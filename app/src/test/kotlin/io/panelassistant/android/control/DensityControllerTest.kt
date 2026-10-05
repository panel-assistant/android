package io.panelassistant.android.control

import io.panelassistant.android.platform.ShellPrivilege
import io.panelassistant.android.shizuku.ShizukuBridge
import io.panelassistant.android.shizuku.ShizukuState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Density/font-scale parsing and live helper↔su routing, with no device or privileged process. */
class DensityControllerTest {
    private class Shell : ShellPrivilege {
        val calls = mutableListOf<String>()
        override fun available() = true
        override fun uid() = 2000
        override fun screenshot(): ByteArray? = null
        override fun inputKey(keyCode: Int) = false
        override fun tap(x: Int, y: Int) = false
        override fun density(): String { calls += "density"; return "Physical density: 320\nOverride density: 240" }
        override fun setDensity(dpi: Int): Boolean { calls += "set-density:$dpi"; return true }
        override fun resetDensity() = false
        override fun fontScale(): String? = null
        override fun setFontScale(scale: Float) = false
        override fun resetFontScale() = false
        override fun installApk(apk: File, allowDowngrade: Boolean, timeoutMs: Long): String? = null
    }
    private data class Harness(
        val density: DensityController,
        val root: FakeRootShell,
        val daemon: FakeDaemon,
    )

    private fun controller(
        canSu: Boolean = true,
        rootOutputs: Map<String, String> = emptyMap(),
        rootResult: Boolean = true,
        daemonReplies: Map<String, String> = emptyMap(),
    ): Harness {
        val root = FakeRootShell(outputs = rootOutputs, runResult = rootResult)
        val daemon = FakeDaemon(replies = daemonReplies)
        return Harness(DensityController(canSu = canSu, root = root, daemon = daemon), root, daemon)
    }

    private fun privilege(
        directSuReady: Boolean = false,
        helperRootReady: Boolean = false,
        shizukuReady: Boolean = false,
    ) = PrivilegedRouteObservation(
        directSuReady = directSuReady,
        helperRootReady = helperRootReady,
        shizuku = ShizukuBridge.Snapshot(
            state = if (shizukuReady) ShizukuState.READY else ShizukuState.STOPPED,
            ready = shizukuReady,
        ),
    )

    @Test fun sizingObservationReadsDensityAndFontScaleExactlyOnce() {
        val h = controller(
            rootOutputs = mapOf(
                "wm density" to "Physical density: 320\nOverride density: 240",
                "font_scale" to "1.25",
            ),
        )

        assertEquals(
            DisplaySizingObservation(current = 240, base = 320, fontScale = 1.25f),
            h.density.observeSizing(),
        )
        assertEquals(1, h.root.outputRan.count { it.startsWith("wm density") })
        assertEquals(1, h.root.outputRan.count { it.contains("font_scale") })
        assertEquals(2, h.root.outputRan.size)
        assertTrue(h.daemon.sent.isEmpty())
    }

    @Test fun sizingObservationUsesTwoHelperCommandsWhenHelperIsPreferred() {
        val h = controller(
            canSu = false,
            daemonReplies = mapOf(
                "DENSITY" to "PHYS=320 OVER=-",
                "FONTSCALE" to "SCALE=null",
            ),
        )

        assertEquals(
            DisplaySizingObservation(current = 320, base = 320, fontScale = 1.0f),
            h.density.observeSizing(),
        )
        assertEquals(listOf("DENSITY", "FONTSCALE"), h.daemon.sent)
        assertTrue(h.root.outputRan.isEmpty())
    }

    @Test fun sizingObservationFallsBackIndependentlyForBothReads() {
        val h = controller(
            rootOutputs = mapOf(
                "wm density" to "unexpected",
                "font_scale" to "not-a-scale",
            ),
            daemonReplies = mapOf(
                "DENSITY" to "PHYS=480 OVER=360",
                "FONTSCALE" to "SCALE=0.85",
            ),
        )

        assertEquals(
            DisplaySizingObservation(current = 360, base = 480, fontScale = 0.85f),
            h.density.observeSizing(),
        )
        assertEquals(1, h.root.outputRan.count { it.startsWith("wm density") })
        assertEquals(1, h.root.outputRan.count { it.contains("font_scale") })
        assertEquals(listOf("DENSITY", "FONTSCALE"), h.daemon.sent)
    }

    @Test fun sizingObservationUsesOnlyRoutesCapturedReadyForThisRequest() {
        val h = controller(
            canSu = true,
            rootOutputs = mapOf(
                "wm density" to "Physical density: 480\nOverride density: 360",
                "font_scale" to "0.85",
            ),
            daemonReplies = mapOf(
                "DENSITY" to "PHYS=320 OVER=240",
                "FONTSCALE" to "SCALE=1.15",
            ),
        )

        assertEquals(
            DisplaySizingObservation(current = 240, base = 320, fontScale = 1.15f),
            h.density.observeSizing(privilege(helperRootReady = true)),
        )
        assertTrue(h.root.outputRan.isEmpty())
        assertEquals(listOf("DENSITY", "FONTSCALE"), h.daemon.sent)
    }

    @Test fun sizingObservationWithNoCapturedRouteDoesNoPrivilegedWork() {
        val h = controller(
            rootOutputs = mapOf("wm density" to "Physical density: 320"),
            daemonReplies = mapOf("DENSITY" to "PHYS=320 OVER=-"),
        )

        assertEquals(
            DisplaySizingObservation(current = null, base = null, fontScale = 1.0f),
            h.density.observeSizing(privilege()),
        )
        assertTrue(h.root.outputRan.isEmpty())
        assertTrue(h.daemon.sent.isEmpty())
    }

    @Test fun helperPreferredSizingObservationFallsBackToSu() {
        val h = controller(
            canSu = false,
            rootOutputs = mapOf(
                "wm density" to "Physical density: 480\nOverride density: 360",
                "font_scale" to "0.85",
            ),
            daemonReplies = mapOf("DENSITY" to "ERR", "FONTSCALE" to "ERR"),
        )

        assertEquals(
            DisplaySizingObservation(current = 360, base = 480, fontScale = 0.85f),
            h.density.observeSizing(),
        )
        assertEquals(listOf("DENSITY", "FONTSCALE"), h.daemon.sent)
        assertEquals(2, h.root.outputRan.size)
    }

    @Test fun sizingObservationKeepsDensityWhenFontScaleIsUnreadable() {
        val h = controller(
            canSu = false,
            rootOutputs = mapOf("font_scale" to "Infinity"),
            daemonReplies = mapOf(
                "DENSITY" to "PHYS=320 OVER=240",
                "FONTSCALE" to "SCALE=oops",
            ),
        )

        assertEquals(
            DisplaySizingObservation(current = 240, base = 320, fontScale = 1.0f),
            h.density.observeSizing(),
        )
    }

    @Test fun sizingObservationKeepsFontScaleWhenDensityIsUnreadable() {
        val h = controller(
            canSu = false,
            rootOutputs = mapOf("wm density" to "Physical density: 2147483648"),
            daemonReplies = mapOf(
                "DENSITY" to "PHYS=bad OVER=-",
                "FONTSCALE" to "SCALE=1.15",
            ),
        )

        assertEquals(
            DisplaySizingObservation(current = null, base = null, fontScale = 1.15f),
            h.density.observeSizing(),
        )
    }

    @Test fun densitySetUsesPreferredSuAndEnforcesBounds() {
        val h = controller()
        assertTrue(h.density.set(240))
        assertTrue(h.root.ran.contains("wm density 240"))
        assertTrue(h.daemon.sent.isEmpty())
        assertFalse(h.density.set(DensityController.MIN_DPI - 1))
        assertFalse(h.density.set(DensityController.MAX_DPI + 1))
        assertEquals(1, h.root.ran.size)
    }

    @Test fun densitySetFallsThroughInBothDirections() {
        val suFirst = controller(
            rootResult = false,
            daemonReplies = mapOf("DENSITY 240" to "OK"),
        )
        assertTrue(suFirst.density.set(240))
        assertTrue(suFirst.root.ran.isNotEmpty())
        assertEquals(listOf("DENSITY 240"), suFirst.daemon.sent)

        val helperFirst = controller(
            canSu = false,
            daemonReplies = mapOf("DENSITY 240" to "ERR"),
        )
        assertTrue(helperFirst.density.set(240))
        assertEquals(listOf("DENSITY 240"), helperFirst.daemon.sent)
        assertTrue(helperFirst.root.ran.contains("wm density 240"))
    }

    @Test fun densityResetAndAllRouteFailureAreTruthful() {
        val ok = controller(canSu = false, daemonReplies = mapOf("DENSITY reset" to "OK"))
        assertTrue(ok.density.reset())
        assertTrue(ok.root.ran.isEmpty())

        val failed = controller(
            canSu = false,
            rootResult = false,
            daemonReplies = mapOf("DENSITY reset" to "ERR"),
        )
        assertFalse(failed.density.reset())
        assertEquals(listOf("DENSITY reset"), failed.daemon.sent)
        assertTrue(failed.root.ran.contains("wm density reset"))
    }

    @Test fun fontScaleWriteFallsThroughAndRejectsNonFiniteValues() {
        val h = controller(
            canSu = false,
            daemonReplies = mapOf("FONTSCALE 1.2" to "ERR"),
        )
        assertTrue(h.density.setFontScale(1.2f))
        assertEquals(listOf("FONTSCALE 1.2"), h.daemon.sent)
        assertTrue(h.root.ran.any { it.contains("font_scale 1.2") })

        val calls = h.root.ran.size to h.daemon.sent.size
        assertFalse(h.density.setFontScale(Float.NaN))
        assertFalse(h.density.setFontScale(Float.POSITIVE_INFINITY))
        assertFalse(h.density.setFontScale(DensityController.MIN_FONT - 0.1f))
        assertFalse(h.density.setFontScale(DensityController.MAX_FONT + 0.1f))
        assertEquals(calls, h.root.ran.size to h.daemon.sent.size)
    }

    @Test fun fontScaleResetReportsAllRouteFailure() {
        val h = controller(
            rootResult = false,
            daemonReplies = mapOf("FONTSCALE reset" to "ERR"),
        )
        assertFalse(h.density.resetFontScale())
        assertTrue(h.root.ran.any { it.contains("delete system font_scale") })
        assertEquals(listOf("FONTSCALE reset"), h.daemon.sent)
    }

    @Test fun compositeDisplayResultRequiresEveryRequestedEffect() {
        assertTrue(DensityController.allApplied(true))
        assertTrue(DensityController.allApplied(true, null))
        assertTrue(DensityController.allApplied(true, true))
        assertFalse(DensityController.allApplied())
        assertFalse(DensityController.allApplied(null, null))
        assertFalse(DensityController.allApplied(true, false))
        assertFalse(DensityController.allApplied(false, true))
    }

    @Test fun displayFallsThroughRootAndHelperToShizukuLast() {
        val shell = Shell()
        val root = FakeRootShell(outputs = mapOf("wm density" to "bad"), runResult = false)
        val daemon = FakeDaemon(replies = mapOf("DENSITY" to "ERR", "DENSITY 240" to "ERR"))
        val density = DensityController(canSu = true, root = root, daemon = daemon, shell = shell)

        assertEquals(240, density.observeSizing().current)
        assertTrue(density.set(240))
        assertEquals(listOf("density", "set-density:240"), shell.calls)
    }

    @Test fun readyShizukuPrecedesSpeculativeSuOnSandboxedProfile() {
        val shell = Shell()
        val root = FakeRootShell(outputs = mapOf("wm density" to "Physical density: 480"), runResult = true)
        val daemon = FakeDaemon(replies = mapOf("DENSITY" to "ERR", "DENSITY 240" to "ERR"))
        val density = DensityController(canSu = false, root = root, daemon = daemon, shell = shell)

        assertEquals(240, density.observeSizing().current)
        assertTrue(density.set(240))
        assertTrue(root.outputRan.none { it.startsWith("wm density") })
        assertTrue(root.ran.isEmpty())
        assertEquals(listOf("density", "set-density:240"), shell.calls)
    }

    @Test fun capturedShizukuRouteSuppressesUnreadySuAndHelperReads() {
        val shell = Shell()
        val root = FakeRootShell(outputs = mapOf("wm density" to "Physical density: 480"))
        val daemon = FakeDaemon(replies = mapOf("DENSITY" to "PHYS=320 OVER=-"))
        val density = DensityController(canSu = true, root = root, daemon = daemon, shell = shell)

        assertEquals(240, density.observeSizing(privilege(shizukuReady = true)).current)
        assertTrue(root.outputRan.isEmpty())
        assertTrue(daemon.sent.isEmpty())
        assertEquals(listOf("density"), shell.calls)
    }
}
