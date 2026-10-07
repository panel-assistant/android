package io.panelassistant.android.http

import io.panelassistant.android.control.fakeProfile
import io.panelassistant.android.control.TameController
import io.panelassistant.android.device.EvdevButton
import io.panelassistant.android.device.ScreenOff
import io.panelassistant.android.device.profile.BundledProfileFixtures
import io.panelassistant.android.shizuku.ShizukuBridge
import io.panelassistant.android.shizuku.ShizukuManagerIdentity
import io.panelassistant.android.shizuku.ShizukuState
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

    @Test fun appVisibleSuIsReportedPrecisely() {
        val cap = DiagReader.rootSuCapability(su = true, daemon = false)

        assertEquals("ok", cap.status)
        assertEquals("available directly to ha-paneld", cap.note(englishCatalogue))
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

    @Test fun rootedOrHelperBackedPanelsExplainConfiguredShizukuIsRedundant() {
        for (manager in ShizukuManagerIdentity.Status.entries) {
            assertTrue(DiagReader.showShizukuCapability(consentEnabled = true, manager))
        }
        assertFalse(
            DiagReader.showShizukuCapability(
                consentEnabled = false,
                ShizukuManagerIdentity.Status.MISSING,
            ),
        )
        assertTrue(
            DiagReader.shizukuCapabilityNote(
                ShizukuState.READY,
                ShizukuManagerIdentity.Status.TRUSTED,
                preferredPrivilegeReady = true,
            ).render(englishCatalogue).contains("adds no capability while root or the helper daemon provides the preferred route"),
        )
        val unhealthy = DiagReader.shizukuCapabilityNote(
            ShizukuState.READY,
            ShizukuManagerIdentity.Status.UNTRUSTED,
            preferredPrivilegeReady = true,
        ).render(englishCatalogue)
        assertTrue(unhealthy.contains("adds no capability"))
        assertTrue(unhealthy.contains("signer is not trusted"))
        assertFalse(unhealthy.contains("ready as shell UID"))
    }

    @Test fun shizukuCapabilityStatusRequiresAReadyBridgeAndTrustedManager() {
        val ready = ShizukuBridge.Snapshot(ShizukuState.READY, ready = true)
        val trusted = DiagReader.shizukuCapability(
            ready,
            ShizukuManagerIdentity.Status.TRUSTED,
            preferredPrivilegeReady = true,
        )
        val untrusted = DiagReader.shizukuCapability(
            ready,
            ShizukuManagerIdentity.Status.UNTRUSTED,
            preferredPrivilegeReady = true,
        )
        val stopped = DiagReader.shizukuCapability(
            ShizukuBridge.Snapshot(ShizukuState.STOPPED, ready = false),
            ShizukuManagerIdentity.Status.TRUSTED,
        )

        assertEquals("ok", trusted.status)
        assertTrue(trusted.note(englishCatalogue).contains("adds no capability"))
        assertEquals("none", untrusted.status)
        assertTrue(untrusted.note(englishCatalogue).contains("signer is not trusted"))
        assertEquals("none", stopped.status)
        assertTrue(stopped.note(englishCatalogue).contains("service is stopped"))
    }

    @Test fun genuinelyUnrootedPanelsShowShizukuOnlyWhenConfiguredOrInstalled() {
        assertFalse(
            DiagReader.showShizukuCapability(
                consentEnabled = false,
                ShizukuManagerIdentity.Status.MISSING,
            ),
        )
        assertTrue(
            DiagReader.showShizukuCapability(
                consentEnabled = true,
                ShizukuManagerIdentity.Status.MISSING,
            ),
        )
        assertTrue(
            DiagReader.showShizukuCapability(
                consentEnabled = false,
                ShizukuManagerIdentity.Status.TRUSTED,
            ),
        )
    }

    @Test fun enhancedAccessDiagnosticsGiveDifferentDisabledAndStoppedRecoveryPaths() {
        val disabled = DiagReader.shizukuCapabilityNote(
            ShizukuState.DISABLED,
            ShizukuManagerIdentity.Status.TRUSTED,
        ).render(englishCatalogue)
        val stopped = DiagReader.shizukuCapabilityNote(
            ShizukuState.STOPPED,
            ShizukuManagerIdentity.Status.TRUSTED,
        ).render(englishCatalogue)

        assertTrue(disabled.contains("Configure → toolbar overflow → Enhanced access → Enable"))
        assertFalse(disabled.contains("service is stopped"))
        assertTrue(stopped.contains("service is stopped"))
        assertTrue(stopped.contains("open Shizuku"))
        assertFalse(stopped.contains("→ Enable"))
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
            "MQTT state" to "connected · ack 2s ago · ipv4",
            "Security mode" to "Hardened · high-impact remote actions need physical on-panel approval",
            "Prevent idle dim" to "on · timeout 60s (not applied)",
            "Platform" to privateValue,
            "Model" to privateValue,
            "Light sensor" to privateValue,
            "Proximity" to privateValue,
            "CPU profile" to privateValue,
            "Log shipping" to "tcp://$privateValue:9000",
            "Friendly name" to privateValue,
            "MQTT" to "$privateValue · connected",
        )

        val public = DiagReader.publicPanelFacts(facts)

        assertEquals(listOf("ha-paneld", "MQTT state", "Security mode", "Prevent idle dim"), public.keys.toList())
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
}
