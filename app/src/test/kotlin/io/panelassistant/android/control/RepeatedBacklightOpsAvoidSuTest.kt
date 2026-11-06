package io.panelassistant.android.control

import android.content.ContextWrapper
import io.panelassistant.android.device.ScreenOff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Maintainer rule (2026-11-05): repeated backlight reads and writes go through the root helper and never
 * start a root shell. Each case gives the panel a working su route, so a regression that reaches su again
 * is visible as a recorded root call. Wake and sleep are event-driven and may use su, but only when no
 * helper answers at all.
 */
class RepeatedBacklightOpsAvoidSuTest {

    /** A panel where su works and finds a real backlight node at bl_power [blPower]. */
    private fun workingRoot(blPower: String = "0") = FakeRootShell(
        outputs = mapOf(
            "ls -d /sys/class/backlight" to "/sys/class/backlight/bl/",
            "max_brightness" to "255",
            "actual_brightness" to "200",
            "bl_power" to blPower,
        ),
        runResult = true,
    )

    private fun FakeRootShell.calls() = ran.size + outputRan.size + isolatedOutputRan.size

    // --- screen power reads (R10) ---------------------------------------------------------------

    @Test fun suBlPowerProfileReadsBacklightPowerFromTheHelperOnly() {
        val root = workingRoot(blPower = "0")
        val sc = ScreenController(
            FakeBacklight(120), FakeScreenPower(), root, FakeDaemon(mapOf("BLPOWER" to "4")), FakeWakeTap(),
            ScreenOff.SU_BLPOWER,
        )
        repeat(3) {
            assertEquals(true, sc.observedDark())
            assertEquals(false, sc.observedLit())
        }
        assertEquals("repeated screen reads must not start a root shell", 0, root.calls())
    }

    @Test fun anUnansweredHelperReadIsUnknownOnBothBlPowerRoutesAndNeverReachesSu() {
        for (route in listOf(ScreenOff.DAEMON_BLPOWER, ScreenOff.SU_BLPOWER)) {
            for (helper in listOf(FakeDaemon(), FakeDaemon(mapOf("BLPOWER" to "ERR")))) {
                val root = workingRoot(blPower = "4")
                val power = FakeScreenPower(interactive = true)
                val sc = ScreenController(FakeBacklight(120), power, root, helper, FakeWakeTap(), route)

                assertNull("$route: no helper answer cannot prove the screen lit", sc.observedLit())
                assertFalse("$route: unknown is not grounds for the never-blank guard to act", sc.looksDark())
                assertFalse(sc.recoverUnexpectedDark())
                assertEquals("$route: unknown must not fall back to su", 0, root.calls())
                assertEquals("$route: the guard must not relight on a guess", 0, power.pulses)
            }
        }
    }

    // --- screen power writes (R10) --------------------------------------------------------------

    @Test fun sleepOnAnSuBlPowerProfileUsesAnAnsweringHelperWithoutSu() {
        val root = workingRoot()
        val sc = ScreenController(
            FakeBacklight(), FakeScreenPower(), root,
            FakeDaemon(mapOf("SCREEN OFF" to "OK", "SCREEN ON" to "OK")), FakeWakeTap(), ScreenOff.SU_BLPOWER,
        )
        sc.sleep()
        assertEquals(ScreenOff.DAEMON_BLPOWER, sc.routeSelection().selected)
        sc.wake()
        assertEquals(0, root.calls())
    }

    @Test fun aHelperThatRefusesTheOffFallsBackToABrightnessZeroOffWithTheTouchWakeArmed() {
        for (route in listOf(ScreenOff.DAEMON_BLPOWER, ScreenOff.SU_BLPOWER)) {
            val root = workingRoot()
            val backlight = FakeBacklight()
            val wakeTap = FakeWakeTap()
            val sc = ScreenController(
                backlight, FakeScreenPower(), root, FakeDaemon(mapOf("SCREEN OFF" to "ERR")), wakeTap, route,
            )
            sc.sleep()
            assertEquals("$route", ScreenOff.BRIGHTNESS_ZERO, sc.routeSelection().selected)
            assertEquals("$route: the level goes to a raw zero", 0, backlight.level)
            assertTrue("$route: the touch wake is armed for that off", wakeTap.armed)
            assertEquals("$route: a helper refusal is not a reason to use su", 0, root.calls())
        }
    }

    @Test fun sleepAndWakeUseSuOnlyWhenNoHelperIsReachable() {
        for (route in listOf(ScreenOff.DAEMON_BLPOWER, ScreenOff.SU_BLPOWER)) {
            val root = workingRoot()
            val power = FakeScreenPower()
            val backlight = FakeBacklight()
            val sc = ScreenController(backlight, power, root, FakeDaemon(), FakeWakeTap(), route)
            sc.sleep()
            assertEquals("$route", ScreenOff.SU_BLPOWER, sc.routeSelection().selected)
            assertEquals("$route: one su write for the off", 1, root.ran.size)
            sc.wake()
            assertEquals("$route: one su write for the wake", 2, root.ran.size)
            assertTrue("$route: no brightness fallback after an su wake", backlight.calls.isEmpty())
            assertEquals(1, power.pulses)
        }
    }

    @Test fun anSuPerformedOffIsRelitBySuEvenWhenTheHelperReturnsAndRefuses() {
        // Never-blank: su wrote bl_power=4 while no helper answered. A helper that now answers but refuses
        // the wake must not leave the panel dark by our own hand.
        val replies = mutableMapOf<String, String>()
        val root = workingRoot()
        val sc = ScreenController(
            FakeBacklight(), FakeScreenPower(), root, FakeDaemon(replies), FakeWakeTap(), ScreenOff.SU_BLPOWER,
        )
        sc.sleep()
        assertEquals(1, root.ran.size)
        replies["SCREEN ON"] = "ERR"
        sc.wake()
        assertEquals("the su-performed off is undone by su", 2, root.ran.size)
    }

    @Test fun aPoweredDownBacklightWithoutAHelperReadingStaysOffAndKeepsItsTouchWake() {
        // su powered the backlight down because no helper answered; the node level stays positive. The
        // state tick must not read that level as a physical wake and disarm the only way back.
        for (offBy in listOf("su", "helper")) {
            val replies = if (offBy == "helper") mutableMapOf("SCREEN OFF" to "OK") else mutableMapOf()
            val root = workingRoot(blPower = "4")
            val wakeTap = FakeWakeTap()
            val sc = ScreenController(
                FakeBacklight(120), FakeScreenPower(), root, FakeDaemon(replies), wakeTap, ScreenOff.SU_BLPOWER,
            )
            sc.sleep()
            replies.clear()
            val generation = sc.currentOffGeneration()

            val observed = sc.observedDark()
            if (observed == true) sc.noteObservedDark(generation)
            if (observed == false) sc.reconcileObservedLit(generation)
            sc.reconcilePhysicalWake()

            assertTrue("$offBy: the off intent survives an unreadable bl_power", sc.isIntendedOff())
            assertTrue("$offBy: touch-to-wake stays armed on the dark panel", wakeTap.armed)
            assertEquals("$offBy: only the off itself may use su", if (offBy == "su") 1 else 0, root.calls())
        }
    }

    @Test fun aHelperPerformedOffIsRelitBySuWhenTheHelperRefusesTheWake() {
        // Wake is event-driven: a helper that powered the backlight down and then refuses SCREEN ON must
        // not leave the panel dark by our own hand while su could relight it.
        for (route in listOf(ScreenOff.DAEMON_BLPOWER, ScreenOff.SU_BLPOWER)) {
            val replies = mutableMapOf("SCREEN OFF" to "OK")
            val root = workingRoot()
            val sc = ScreenController(FakeBacklight(), FakeScreenPower(), root, FakeDaemon(replies), FakeWakeTap(), route)
            sc.sleep()
            assertEquals("$route", ScreenOff.DAEMON_BLPOWER, sc.routeSelection().selected)
            assertEquals("$route: the helper did the off", 0, root.calls())
            replies["SCREEN ON"] = "ERR"
            sc.wake()
            assertEquals("$route: su relights after the helper refused", 1, root.ran.size)
        }
    }

    @Test fun anSuOffLeftByAReplacedProcessIsAdoptedOnceFromASingleStartupRead() {
        // The old process powered the backlight down through su and was killed without closing. The node
        // level stays positive and no helper answers, so only a bl_power read can see the panel is dark.
        val outputs = mutableMapOf(
            "ls -d /sys/class/backlight" to "/sys/class/backlight/bl/",
            "bl_power" to "0",
        )
        val root = FakeRootShell(outputs = outputs, runResult = true, onRun = { cmd ->
            if ("bl_power" in cmd) outputs["bl_power"] = if ("echo 4" in cmd) "4" else "0"
        })
        val old = ScreenController(FakeBacklight(120), FakeScreenPower(), root, FakeDaemon(), FakeWakeTap(), ScreenOff.SU_BLPOWER)
        old.sleep()
        assertEquals("the old process powered it down through su", "4", outputs["bl_power"])

        val wakeTap = FakeWakeTap()
        val power = FakeScreenPower(interactive = true)
        val next = ScreenController(FakeBacklight(120), power, root, FakeDaemon(), wakeTap, ScreenOff.SU_BLPOWER)
        val readsBefore = root.outputRan.size
        next.adoptInheritedDark(automatic = false)

        assertTrue("the inherited off is adopted", next.isIntendedOff())
        assertTrue("its touch wake is armed", wakeTap.armed)
        assertEquals("the panel stays dark", "4", outputs["bl_power"])
        assertEquals("one startup su read", 1, root.outputRan.size - readsBefore)
        repeat(3) { next.observedDark(); next.looksDark(); next.adoptInheritedDark(automatic = false) }
        assertEquals("never repeated on ticks", 1, root.outputRan.size - readsBefore)
        assertEquals("nothing relit it", 0, power.pulses)
    }

    // --- brightness (R8, R9) --------------------------------------------------------------------
    // BrightnessController holds no root shell at all, so these pin the observable result: the node moves
    // through the helper, and without one the reads and writes settle on the Android setting.

    private class MemorySetting : BrightnessSetting {
        var value: Int? = null
        override fun write(value: Int): Boolean { this.value = value; return true }
        override fun read(): Int? = value
    }

    /** A helper that owns a 0..255 backlight node: BLSET moves it and BLREAD reports it. */
    private fun helperWithNode(): Pair<FakeDaemon, () -> Int> {
        var node = 30
        val replies = mutableMapOf<String, String>()
        fun publish() { replies["BLREAD"] = "$node 255" }
        publish()
        val daemon = FakeDaemon(replies, onSend = { cmd ->
            cmd.removePrefix("BLSET ").takeIf { it != cmd }?.toIntOrNull()?.let {
                node = it
                replies[cmd] = "OK"
                publish()
            }
        })
        return daemon to { node }
    }

    private fun brightness(daemon: FakeDaemon, setting: BrightnessSetting) =
        BrightnessController(object : ContextWrapper(null) {}, daemon, BacklightScale.IDENTITY, setting)

    @Test fun anAutoBrightnessRampDrivesTheNodeThroughTheHelperWithoutSu() {
        val (daemon, node) = helperWithNode()
        val controller = brightness(daemon, MemorySetting())
        for (level in 40..60) {
            assertTrue(controller.trySetBrightness(level))
            assertEquals(level, node())
            assertEquals(level, controller.getBrightness())
        }
    }

    @Test fun withoutAHelperTheRampMovesOnlyTheAndroidSettingAndReadsFallBackToIt() {
        val setting = MemorySetting()
        val controller = brightness(FakeDaemon(), setting)
        for (level in 40..45) {
            assertTrue("the Android setting is the remaining actuator", controller.trySetBrightness(level))
            assertEquals(level, setting.value)
            assertEquals("the effective read falls back to the setting", level, controller.getBrightness())
        }
    }

    @Test fun aRefusedHelperReadFallsBackToTheSettingWithoutSu() {
        val setting = MemorySetting().apply { value = 77 }
        val controller = brightness(FakeDaemon(mapOf("BLREAD" to "ERR")), setting)
        assertEquals(77, controller.getBrightness())
    }
}
