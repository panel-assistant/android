package io.panelassistant.android.http

import io.panelassistant.android.control.fakeProfile
import io.panelassistant.android.device.EvdevButton
import io.panelassistant.android.device.ScreenOff
import io.panelassistant.android.i18n.AppLocale
import io.panelassistant.android.i18n.CatalogueLoader
import io.panelassistant.android.i18n.Strings
import io.panelassistant.android.input.ButtonCaptureHealth
import io.panelassistant.android.input.EvdevButtonClient
import io.panelassistant.android.input.EvdevStreamSession
import io.panelassistant.android.shizuku.ShizukuBridge
import io.panelassistant.android.shizuku.ShizukuManagerIdentity
import io.panelassistant.android.shizuku.ShizukuState
import java.io.File

/** The shipped catalogue, as the server loads it. */
internal val testCatalogue = CatalogueLoader { File("src/main/assets", it).readText() }

/** The English the capability rows put on the wire. */
internal val englishCatalogue: Strings by lazy { testCatalogue.strings(AppLocale.ENGLISH) }

/** Every branch of every diagnostics capability row, named, for tests that render them all. */
internal fun capabilityRowCases(): List<Pair<String, DiagReader.Cap>> = buildList {
    val flags = listOf(false to false, true to false, false to true, true to true)
    for ((su, daemon) in flags) {
        add("root su=$su daemon=$daemon" to DiagReader.rootSuCapability(su, daemon))
        add("brightness write su=$su daemon=$daemon" to DiagReader.screenBrightnessCapability(true, su, daemon, "pkg"))
        add("brightness su=$su daemon=$daemon" to DiagReader.screenBrightnessCapability(false, su, daemon, "pkg"))
        for (route in ScreenOff.entries) {
            add("screen $route su=$su daemon=$daemon" to DiagReader.screenOnOffCapability(route, su, daemon))
        }
    }
    val button = EvdevButton("/dev/input/event7", 116, grab = true, eventType = "power")
    val profiles = listOf(
        "sandboxed" to fakeProfile(appCanSu = false),
        "buttons" to fakeProfile(appCanSu = true, evdevButtons = listOf(button, button)),
        "backlight" to fakeProfile(appCanSu = true, hasButtonBacklight = true),
        "hardware" to fakeProfile(appCanSu = true),
    )
    for ((label, profile) in profiles) for (daemon in listOf(true, false)) {
        add("daemon $label running=$daemon" to DiagReader.helperDaemonCapability(profile, daemon))
    }
    for ((rootish, shizuku) in flags) {
        add("verified rootish=$rootish shizuku=$shizuku" to DiagReader.verifiedOperationsCapability(rootish, shizuku))
    }
    for ((rk, probe, daemonLed) in listOf(
        Triple(true, null, false), Triple(false, "ledjni", true), Triple(false, "sysfs", true),
        Triple(false, "ERR", true), Triple(false, "none", false),
    )) add("led rk=$rk probe=$probe daemon=$daemonLed" to DiagReader.rgbLedCapability(rk, probe, daemonLed))
    for (rootish in listOf(true, false)) add("system rootish=$rootish" to DiagReader.systemActionsCapability(rootish))
    for (manager in ShizukuManagerIdentity.Status.entries) for (state in ShizukuState.entries) {
        for (preferred in listOf(false, true)) {
            add(
                "shizuku $manager $state preferred=$preferred" to
                    DiagReader.shizukuCapability(ShizukuBridge.Snapshot(state, state == ShizukuState.READY), manager, preferred),
            )
        }
    }
    val streams = listOf(
        EvdevButtonClient.Snapshot(EvdevButtonClient.State.ACTIVE, EvdevStreamSession.Mode.VERIFIED, null),
        EvdevButtonClient.Snapshot(EvdevButtonClient.State.ACTIVE, EvdevStreamSession.Mode.LEGACY, null),
        EvdevButtonClient.Snapshot(EvdevButtonClient.State.RETRYING, null, "helper rejected WATCH"),
        EvdevButtonClient.Snapshot(EvdevButtonClient.State.STOPPED, null, null),
    )
    for ((index, stream) in streams.withIndex()) for (a11y in listOf(true, false)) for (count in listOf(0, 2)) {
        add(
            "buttons stream=$index a11y=$a11y count=$count" to
                DiagReader.hardwareButtonsCapability(ButtonCaptureHealth.evaluate(a11y, count, stream, "pkg")),
        )
    }
}
