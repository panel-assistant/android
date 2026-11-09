package io.panelassistant.android.http

import io.panelassistant.android.control.fakeProfile
import io.panelassistant.android.control.TameController
import io.panelassistant.android.device.EvdevButton
import io.panelassistant.android.device.LocalWake
import io.panelassistant.android.device.ScreenOff
import io.panelassistant.android.device.profile.BundledProfileFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagCapabilityPolicyTest {
    private val fallback = BundledProfileFixtures.fallback()
    private val nspanel = BundledProfileFixtures.profile("nspanel-pro")

    @Test fun diagnosticButtonRequestUsesTheInjectedProfile() {
        val profile = fakeProfile(
            evdevButtons = listOf(
                EvdevButton("/dev/input/event7", 116, grab = true, eventType = "power"),
                EvdevButton("/dev/input/event3", 14, grab = false, eventType = "mute", sw = true),
            ),
        )
        assertEquals(
            "/dev/input/event7:KEY/116:grab,/dev/input/event3:SW/14:watch",
            DiagReader.evdevRequestDescription(profile),
        )
    }

    /**
     * The capability row must describe the route the panel will actually take, and on
     * [ScreenOff.BRIGHTNESS_ZERO] that route never reaches a privileged actuator at all:
     * `ScreenController.sleepInternal` hard-codes `ScreenOff.BRIGHTNESS_ZERO -> null` for its
     * powered-off route. Reporting a helper- or su-backed backlight-off there tells the owner of a
     * rooted panel that the screen goes properly dark when it only dims.
     */
    @Test fun brightnessZeroRouteReportsDimOnlyEvenWhenPrivilegeIsAvailable() {
        listOf(
            Triple(true, true, "helper and su"),
            Triple(false, true, "helper only"),
            Triple(true, false, "su only"),
            Triple(false, false, "neither"),
        ).forEach { (su, daemon, label) ->
            val cap = DiagReader.screenOnOffCapability(ScreenOff.BRIGHTNESS_ZERO, su = su, daemon = daemon)

            assertEquals("brightness-zero must never claim ok with $label", "degraded", cap.status)
            assertFalse(
                "brightness-zero must not claim a backlight-off it never attempts with $label: ${cap.note(englishCatalogue)}",
                cap.note(englishCatalogue).contains("backlight-off"),
            )
            assertEquals(
                "brightness-zero must say it only dims, and why, with $label",
                "DIM ONLY — this panel's profile selects the brightness-zero route, which never powers the backlight down",
                cap.note(englishCatalogue),
            )
        }
    }

    /** A bl_power route names the transport it will actually try first, not whichever exists. */
    @Test fun blPowerRoutesNameTheirOwnFirstAttempt() {
        val su = DiagReader.screenOnOffCapability(ScreenOff.SU_BLPOWER, su = true, daemon = true)
        assertEquals("ok", su.status)
        assertTrue("su-blpower tries su first: ${su.note(englishCatalogue)}", su.note(englishCatalogue).contains("su bl_power"))

        val daemon = DiagReader.screenOnOffCapability(ScreenOff.DAEMON_BLPOWER, su = true, daemon = true)
        assertEquals("ok", daemon.status)
        assertTrue("daemon-blpower tries the daemon first: ${daemon.note(englishCatalogue)}", daemon.note(englishCatalogue).contains("helper daemon"))
    }

    /** A bl_power route with no privileged transport at all cannot power the backlight down. */
    @Test fun blPowerRoutesWithoutPrivilegeReportDimOnly() {
        listOf(ScreenOff.SU_BLPOWER, ScreenOff.DAEMON_BLPOWER).forEach { route ->
            val cap = DiagReader.screenOnOffCapability(route, su = false, daemon = false)
            assertEquals("$route without privilege must be degraded", "degraded", cap.status)
            assertEquals(
                "$route must say it only dims, and why",
                "DIM ONLY — the backlight stays powered; needs su or the helper daemon for a real off",
                cap.note(englishCatalogue),
            )
        }
    }

    /** The unprivileged Android-sleep routes name the route a screen-off will take from the grants in
     *  hand, and add the PIN-on-wake consequence as a note rather than a refusal. */
    @Test fun androidSleepRoutesReportTheirGrantsAndAPinOnWake() {
        val timeout = DiagReader.screenOnOffCapability(ScreenOff.SCREEN_TIMEOUT, false, false, canWriteSettings = true)
        assertEquals("ok", timeout.status)
        assertTrue(timeout.note(englishCatalogue), timeout.note(englishCatalogue).startsWith("Android sleep via a short screen timeout"))

        val lock = DiagReader.screenOnOffCapability(ScreenOff.SCREEN_TIMEOUT, false, false, deviceAdminActive = true)
        assertEquals("ok", lock.status)
        assertTrue(lock.note(englishCatalogue), lock.note(englishCatalogue).contains("device-admin screen lock"))

        // Stay-on-while-plugged-in the app cannot clear defeats the timeout: report the fallback instead.
        val stayOn = DiagReader.screenOnOffCapability(
            ScreenOff.SCREEN_TIMEOUT, false, false,
            canWriteSettings = true, deviceAdminActive = true, stayOnDefeatsTimeout = true,
        )
        assertTrue(stayOn.note(englishCatalogue), stayOn.note(englishCatalogue).contains("device-admin screen lock"))

        listOf(ScreenOff.SCREEN_TIMEOUT, ScreenOff.DEVICE_ADMIN).forEach { route ->
            val none = DiagReader.screenOnOffCapability(route, su = true, daemon = true)
            assertEquals("$route: root is not this route's remedy", "degraded", none.status)
            assertEquals(
                "DIM ONLY — needs WRITE_SETTINGS or an active device admin to let Android sleep",
                none.note(englishCatalogue),
            )
        }

        val pin = DiagReader.screenOnOffCapability(ScreenOff.DEVICE_ADMIN, false, false, deviceAdminActive = true, deviceSecure = true)
        assertEquals("ok", pin.status)
        assertTrue(pin.note(englishCatalogue), pin.note(englishCatalogue).endsWith("a screen lock PIN is set, so waking needs that PIN"))
    }

    /** A profile's `hardware.local_wake` replaces the generic "only where the touchscreen is a wake source" wording. */
    @Test fun aDeclaredLocalWakeTellsTheOwnerHowTheDarkPanelWakes() {
        val x2i = setOf(LocalWake.TOUCH, LocalWake.PROXIMITY)
        assertEquals(
            "Android sleep via a short screen timeout; any touch wakes it; a wave wakes it once proximity is calibrated; Home Assistant always wakes it",
            DiagReader.screenOnOffCapability(ScreenOff.SCREEN_TIMEOUT, false, false, canWriteSettings = true, localWake = x2i)
                .note(englishCatalogue),
        )
        assertEquals(
            "Android sleep via KEYCODE_SLEEP; only Home Assistant wakes it",
            DiagReader.screenOnOffCapability(ScreenOff.KEYEVENT, su = true, daemon = false, localWake = emptySet()).note(englishCatalogue),
        )
        assertEquals(
            "Android sleep via the device-admin screen lock; its button wakes it; Home Assistant always wakes it; a screen lock PIN is set, so waking needs that PIN",
            DiagReader.screenOnOffCapability(
                ScreenOff.DEVICE_ADMIN, false, false, deviceAdminActive = true, deviceSecure = true, localWake = setOf(LocalWake.BUTTON),
            ).note(englishCatalogue),
        )
        // Undeclared keeps the generic wording, and a dim-only route says nothing about waking.
        assertTrue(
            DiagReader.screenOnOffCapability(ScreenOff.SCREEN_TIMEOUT, false, false, canWriteSettings = true, localWake = null)
                .note(englishCatalogue).endsWith("a local touch only where this panel's touchscreen is a platform wake source"),
        )
        assertEquals(
            "DIM ONLY — needs WRITE_SETTINGS or an active device admin to let Android sleep",
            DiagReader.screenOnOffCapability(ScreenOff.SCREEN_TIMEOUT, false, false, localWake = x2i).note(englishCatalogue),
        )
    }

    @Test fun profileWithoutEvdevButtonsHasNoRequestedStream() {
        assertNull(DiagReader.evdevRequestDescription(fakeProfile()))
    }

    @Test fun exactProfileDeclarationsHideAbsentLedAndHardwareButtons() {
        assertFalse(DiagReader.showRgbLedCapability(nspanel))
        assertFalse(DiagReader.showHardwareButtonsCapability(nspanel))
    }

    @Test fun genericProfileRetainsRuntimeLedAndButtonDiscoveryRows() {
        assertTrue(DiagReader.showRgbLedCapability(fallback))
        assertTrue(DiagReader.showHardwareButtonsCapability(fallback))
    }

    @Test fun profiledEvdevButtonsKeepTheHardwareButtonRow() {
        val profile = fakeProfile(
            evdevButtons = listOf(EvdevButton("/dev/input/event7", 116, grab = true, eventType = "power")),
        )

        assertTrue(DiagReader.showHardwareButtonsCapability(profile))
    }

    @Test fun exactProfileCardOmitsExplicitlyAbsentHardware() {
        val keys = profileFactKeys(
            nspanel,
            mapOf(
                "Platform" to "Sonoff NSPanel Pro",
                "SoC" to "Rockchip RK3326 · 4× Arm Cortex-A35 · introduced 2018",
                "LED" to "none",
                "Light sensor" to "yes · Ambient light",
                "Proximity" to "yes · Infrared",
                "Zigbee" to "sonoff · running",
                "Relays" to "none",
                "CPU profile" to "Auto",
            ),
        )

        assertFalse("LED" in keys)
        assertFalse("Relays" in keys)
        assertTrue("SoC" in keys)
        assertTrue("Light sensor" in keys)
        assertTrue("Proximity" in keys)
        assertTrue("Zigbee" in keys)
    }

    @Test fun genericProfileCardKeepsCapabilityDiscoveryButOmitsUnknownSoc() {
        val keys = profileFactKeys(
            fallback,
            mapOf("LED" to "none", "Relays" to "none", "Zigbee" to "none"),
        )

        assertTrue("LED" in keys)
        assertTrue("Relays" in keys)
        assertTrue("Zigbee" in keys)
        assertFalse("SoC" in keys)
    }

    @Test fun unexpectedObservedHardwareRemainsVisibleForProfileCorrection() {
        val keys = profileFactKeys(
            nspanel,
            mapOf("LED" to "RGB", "Relays" to "2"),
        )

        assertTrue("LED" in keys)
        assertTrue("Relays" in keys)
    }

    @Test fun missingAppSuDoesNotClaimHelperBackedActionsAreUnavailable() {
        val cap = DiagReader.rootSuCapability(su = false, daemon = true)

        assertEquals("Root (su)", cap.name(englishCatalogue))
        assertEquals("degraded", cap.status)
        assertTrue(cap.note(englishCatalogue).contains("routed through the helper daemon"))
        assertFalse(cap.note(englishCatalogue).contains("unavailable"))
        assertFalse(cap.note(englishCatalogue).contains("no su on this firmware"))
    }

    @Test fun missingBothPrivilegeRoutesDefersToSpecificCapabilityRows() {
        val cap = DiagReader.rootSuCapability(su = false, daemon = false)

        assertEquals("none", cap.status)
        assertTrue(cap.note(englishCatalogue).contains("individual capability rows"))
        assertFalse(cap.note(englishCatalogue).contains("reboot/reload"))
    }

    @Test fun privilegedActionsNeedRootOrTheHelperAndOtherwiseGiveTheStandardExplanation() {
        val rooted = DiagReader.verifiedOperationsCapability(rootish = true)
        assertEquals("ok", rooted.status)
        assertEquals("available through root or the helper daemon", rooted.note(englishCatalogue))

        val rootless = DiagReader.verifiedOperationsCapability(rootish = false)
        assertEquals("Verified app update / screenshot / display", rootless.name(englishCatalogue))
        assertEquals("none", rootless.status)
        assertEquals("needs supported privileged panel access", rootless.note(englishCatalogue))
    }

    @Test fun appVisibleSuIsReportedPrecisely() {
        val cap = DiagReader.rootSuCapability(su = true, daemon = false)

        assertEquals("ok", cap.status)
        assertEquals("available directly to Panel Assistant", cap.note(englishCatalogue))
    }

    @Test fun blPowerRoutesReportABacklightOff() {
        val daemonRoute = DiagReader.screenOnOffCapability(ScreenOff.DAEMON_BLPOWER, su = false, daemon = true)
        assertEquals("Screen on/off", daemonRoute.name(englishCatalogue))
        assertEquals("ok", daemonRoute.status)
        assertEquals("true backlight-off via the helper daemon", daemonRoute.note(englishCatalogue))

        val suRoute = DiagReader.screenOnOffCapability(ScreenOff.SU_BLPOWER, su = true, daemon = false)
        assertEquals("true backlight-off via su bl_power", suRoute.note(englishCatalogue))

        val none = DiagReader.screenOnOffCapability(ScreenOff.BRIGHTNESS_ZERO, su = false, daemon = false)
        assertEquals("degraded", none.status)
        assertEquals(
            "DIM ONLY — this panel's profile selects the brightness-zero route, which never powers the backlight down",
            none.note(englishCatalogue),
        )
    }

    /** A panel whose screen-off is Android's own sleep must not be told it has a backlight off, and it
     *  must be told plainly that a local touch is not guaranteed to wake it. */
    @Test fun keyeventRouteDescribesAndroidSleepRatherThanABacklightOff() {
        listOf(
            DiagReader.screenOnOffCapability(ScreenOff.KEYEVENT, su = true, daemon = false),
            DiagReader.screenOnOffCapability(ScreenOff.KEYEVENT, su = false, daemon = true),
        ).forEach { cap ->
            assertEquals("ok", cap.status)
            assertTrue(cap.note(englishCatalogue).contains("KEYCODE_SLEEP"))
            assertTrue(cap.note(englishCatalogue).contains("Home Assistant always wakes it"))
            assertTrue(cap.note(englishCatalogue).contains("platform wake source"))
            assertFalse("the keyevent route blanks no backlight", cap.note(englishCatalogue).contains("backlight-off"))
        }

        val unprivileged = DiagReader.screenOnOffCapability(ScreenOff.KEYEVENT, su = false, daemon = false)
        assertEquals("with no privileged injector there is no real off at all", "degraded", unprivileged.status)
        assertEquals(
            "DIM ONLY — needs su or the helper daemon to inject KEYCODE_SLEEP",
            unprivileged.note(englishCatalogue),
        )
    }

    @Test fun screenBrightnessCallsOutReducedHardwareOnlyControl() {
        val direct = DiagReader.screenBrightnessCapability(canWrite = true, su = false, daemon = false, pkg = "test.pkg")
        assertEquals("Screen brightness", direct.name(englishCatalogue))
        assertEquals("ok", direct.status)
        assertEquals("WRITE_SETTINGS granted", direct.note(englishCatalogue))

        val helper = DiagReader.screenBrightnessCapability(canWrite = false, su = false, daemon = true, pkg = "test.pkg")
        assertEquals("degraded", helper.status)
        assertTrue(helper.note(englishCatalogue).contains("helper daemon"))
        assertFalse(helper.note(englishCatalogue).contains("adb shell"))

        val root = DiagReader.screenBrightnessCapability(canWrite = false, su = true, daemon = false, pkg = "test.pkg")
        assertEquals("degraded", root.status)
        assertTrue(root.note(englishCatalogue).contains("via su"))

        val unavailable = DiagReader.screenBrightnessCapability(canWrite = false, su = false, daemon = false, pkg = "test.pkg")
        assertEquals("none", unavailable.status)
        assertTrue(unavailable.note(englishCatalogue).contains("adb shell appops set test.pkg WRITE_SETTINGS allow"))
    }

    @Test fun bootSecurityDiagnosticsNormalizeOnlyAllowlistedCategoricalFacts() {
        val properties = mapOf(
            "ro.boot.verifiedbootstate" to "GREEN",
            "ro.boot.flash.locked" to "1",
            "ro.boot.vbmeta.device_state" to "locked",
            "ro.debuggable" to "0",
        )

        assertEquals(
            "[boot-security] verified=green flash=locked vbmeta=locked build=user debuggable=no",
            DiagReader.bootSecurityLine({ properties[it].orEmpty() }, "user"),
        )
    }

    @Test fun bootSecurityDiagnosticsDoNotEchoUnknownRawPropertyValues() {
        val identifyingRawValue = "device-specific-value-12345"
        val line = DiagReader.bootSecurityLine({ identifyingRawValue }, identifyingRawValue)

        assertEquals(
            "[boot-security] verified=unknown flash=unknown vbmeta=unknown build=unknown debuggable=unknown",
            line,
        )
        assertFalse(line.contains(identifyingRawValue))
    }

    @Test fun publicPanelFactsExcludeProfileAndDeploymentAuthoredText() {
        val privateValue = "private-room-or-network.example"
        val facts = linkedMapOf(
            "ha-paneld" to "0.9.5-rc1 (build 294)",
            "Security mode" to "Hardened · high-impact remote actions need physical on-panel approval",
            "Prevent idle dim" to "on · timeout 60s (not applied)",
            "Platform" to privateValue,
            "Model" to privateValue,
            "Light sensor" to privateValue,
            "Proximity" to privateValue,
            "CPU profile" to privateValue,
            "Log shipping" to "tcp://$privateValue:9000",
            "Friendly name" to privateValue,
        )

        val public = DiagReader.publicPanelFacts(facts)

        assertEquals(listOf("ha-paneld", "Security mode", "Prevent idle dim"), public.keys.toList())
        assertFalse(public.values.joinToString().contains(privateValue))
    }

    /**
     * The Wi-Fi line is the one allowlisted fact that is also conditional. The panel's own card shows
     * every episode; this report is terse by design and is read by somebody triaging a bug, so the
     * line enters it only once the instability is chronic.
     */
    @Test fun theWifiStabilityLineEntersThePastedReportOnlyWhenTheInstabilityIsChronic() {
        val facts = linkedMapOf(
            "ha-paneld" to "0.9.7-rc1 (build 563)",
            "Wi-Fi stability" to "2 outages in the last 24 h",
        )

        assertEquals(
            listOf("ha-paneld"),
            DiagReader.publicPanelFacts(facts, wifiStabilityChronic = false).keys.toList(),
        )
        assertEquals(
            listOf("ha-paneld", "Wi-Fi stability"),
            DiagReader.publicPanelFacts(facts, wifiStabilityChronic = true).keys.toList(),
        )
        // Text that leaves the panel fails closed: a caller that says nothing gets no line.
        assertFalse(DiagReader.publicPanelFacts(facts).containsKey("Wi-Fi stability"))
    }

    @Test fun vendorTameDiagnosticsExposeCountsWithoutProfilePackageIdentifiers() {
        val packageName = "private.example.vendor.panel"
        val line = DiagReader.vendorTameSummary(
            listOf(
                TameController.Candidate(packageName, "one", installed = true, disabled = false, blocked = false),
                TameController.Candidate("private.example.disabled", "two", installed = true, disabled = true, blocked = false),
                TameController.Candidate("private.example.absent", "three", installed = false, disabled = false, blocked = false),
            ),
        )

        assertEquals("[vendor-tame] known=3 installed=2 active=1 disabled=1", line)
        assertFalse(line.contains(packageName))
        assertFalse(line.contains("private.example"))
    }

    /** A report from an unknown panel must show it fell back to the generic profile. */
    @Test fun theReportNamesTheMatchedProfile() {
        val line = DiagReader.profileLine(nspanel)
        assertEquals("[profile] id=nspanel-pro revision=${nspanel.revision.take(12)}", line)
        assertTrue(DiagReader.profileLine(fallback).startsWith("[profile] id=${fallback.id} "))
    }

    /** Output captured on a TPA10: the missing nodes go, the denied read stays. */
    @Test fun labelsDropNodesTheModelLacksButKeepADeniedRead() {
        val tpa10 = "ls: /sys/class/leds/*/: Permission denied\n" +
            "ls: /sys/class/backlight/*/: No such file or directory\n" +
            "ls: /dev/ledjni: No such file or directory\n"
        assertEquals("[labels] ls: /sys/class/leds/*/: Permission denied", DiagReader.labelsLine(tpa10))
        val nspanel = "ls: /sys/class/leds/*/: No such file or directory\n" +
            "ls: /dev/ledjni: No such file or directory\n" +
            "u:object_r:sysfs:s0 /sys/class/backlight/backlight/\n"
        assertEquals("[labels] u:object_r:sysfs:s0 /sys/class/backlight/backlight/", DiagReader.labelsLine(nspanel))
        assertEquals("[labels] none", DiagReader.labelsLine("ls: /dev/ledjni: No such file or directory\n"))
    }

    @Test fun theVendorAppLineCarriesTheVersionButNotThePackage() {
        assertEquals("[vendor-app] version=2.8.0", DiagReader.vendorAppLine("2.8.0"))
    }

    @Test fun theVoiceLineSaysWhyAPanelDoesNotHear() {
        val line = voiceDiagnosticLine(
            enabled = true,
            microphone = io.panelassistant.android.audio.MicrophoneStatus(
                io.panelassistant.android.audio.MicrophonePresence.UNPROVEN,
                io.panelassistant.android.audio.MicrophoneCheck.NO_AUDIO,
                detail = "read returned 0",
            ),
            wakeWords = "[\"okay_nabu\"]",
            pipelines = "",
            audioSource = "voice_recognition",
            sensitivity = "default",
            micGainDb = 6,
            stopWord = false,
        )
        assertTrue(line, line.startsWith("[voice] enabled=true microphone=unproven check=no_audio muted=false "))
        assertTrue(line, line.contains("detail=\"read returned 0\" wake_words=[\"okay_nabu\"] pipelines=- "))
        assertTrue(line, line.endsWith("source=voice_recognition sensitivity=default gain_db=6 stop_word=false route=- aec=- rebuilds=-"))
    }

    @Test fun theVoiceLineSaysWhichRouteThePanelCapturesThrough() {
        val requested = io.panelassistant.android.audio.CaptureRoute(sampleRateHz = 48_000, channels = 2)
        fun line(active: io.panelassistant.android.audio.ActiveCapture?) = voiceDiagnosticLine(
            enabled = true,
            microphone = io.panelassistant.android.audio.MicrophoneStatus(
                io.panelassistant.android.audio.MicrophonePresence.PROVEN,
                route = io.panelassistant.android.audio.CaptureRouteReport(requested, active, rebuilds = 2),
            ),
            wakeWords = "", pipelines = "", audioSource = "", sensitivity = "", micGainDb = 0, stopWord = false,
        )
        val open = line(
            io.panelassistant.android.audio.ActiveCapture(
                source = android.media.MediaRecorder.AudioSource.MIC, sampleRateHz = 16_000, channels = 2,
                downmix = io.panelassistant.android.audio.CaptureDownmix.DOMINANCE, routedDevice = "15:Built-In Mic",
                aec = io.panelassistant.android.audio.AecState.UNAVAILABLE, warmupReads = 16,
            ),
        )
        assertTrue(open, open.endsWith(" route=mic/16000/2/dominance aec=unavailable rebuilds=2"))
        val closed = line(null)
        assertTrue(closed, closed.endsWith(" route=voice_recognition/48000/2/dominance(closed) aec=- rebuilds=2"))
    }
}
