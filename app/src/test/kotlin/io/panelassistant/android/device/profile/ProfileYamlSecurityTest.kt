package io.panelassistant.android.device.profile

import io.panelassistant.android.device.ScreenOff
import io.panelassistant.android.device.SuForm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileYamlSecurityTest {
    @Test fun `automatic brightness defaults reject equal and inverted ranges after YAML parsing`() {
        for ((minimum, maximum) in listOf(90 to 10, 30 to 30)) {
            val parsed = ProfileYaml.parse(ProfileYaml.serialize(testProfileDocument().copy(
                settingDefaults = mapOf(
                    "auto_brightness_minimum_percent" to minimum,
                    "auto_brightness_maximum_percent" to maximum,
                ),
            )))
            assertTrue(parsed.issues.toString(), parsed.issues.isEmpty())
            val issues = ProfileValidator.validate(requireNotNull(parsed.document), "1.0.0", bundled = false)
            assertTrue("accepted $minimum..$maximum", issues.any {
                it.severity == ProfileIssueSeverity.ERROR && it.path.startsWith("setting_defaults")
            })
        }
    }

    @Test fun `automatic brightness defaults admit ordered pairs and either partial bound`() {
        for (defaults in listOf(
            mapOf("auto_brightness_minimum_percent" to 30, "auto_brightness_maximum_percent" to 60),
            mapOf("auto_brightness_minimum_percent" to 90),
            mapOf("auto_brightness_maximum_percent" to 10),
        )) {
            val parsed = ProfileYaml.parse(ProfileYaml.serialize(testProfileDocument().copy(settingDefaults = defaults)))
            assertTrue(parsed.issues.toString(), parsed.issues.isEmpty())
            val issues = ProfileValidator.validate(requireNotNull(parsed.document), "1.0.0", bundled = false)
            assertTrue("rejected $defaults: $issues", issues.isEmpty())
        }
    }

    @Test fun `generic typed setting defaults survive YAML round trip and registry admission`() {
        val document = testProfileDocument().copy(settingDefaults = mapOf(
            "dark_mode" to false,
            "dashboard_zoom" to 85,
            "room_temp_offset" to 1.25,
            "camera_resolution" to "1080P",
        ))
        val parsed = ProfileYaml.parse(ProfileYaml.serialize(document))
        assertTrue(parsed.issues.toString(), parsed.issues.isEmpty())
        val restored = requireNotNull(parsed.document)
        assertEquals(document, restored)
        assertTrue(ProfileValidator.validate(restored, "1.0.0", bundled = false).isEmpty())
        assertEquals(mapOf(
            "dark_mode" to "false", "dashboard_zoom" to "85", "room_temp_offset" to "1.25", "camera_resolution" to "1080p",
        ), DataDeviceProfile(restored, "1.0.0", ProfileYaml.sha256(ProfileYaml.serialize(restored)), false).settingDefaults)
    }

    @Test fun `profile setting defaults reject invalid types ranges nested values and unusable keys`() {
        val invalid = listOf(
            "dark_mode" to "false",
            "dashboard_zoom" to "85",
            "dashboard_zoom" to 85.5,
            "dashboard_zoom" to true,
            "dashboard_zoom" to 49,
            "dashboard_zoom" to 301,
            "room_temp_offset" to "1.25",
            "room_temp_offset" to -20.1,
            "room_temp_offset" to 20.1,
            "room_temp_offset" to Double.NaN,
            "room_temp_offset" to Double.POSITIVE_INFINITY,
            "camera_resolution" to "unsupported",
            "camera_resolution" to 720,
            "dark_mode" to listOf(false),
            "dashboard_zoom" to mapOf("value" to 85),
            "dashboard_zoom" to null,
            "unknown_future_setting" to 85,
            "dashboard_overscroll" to false,
            "illuminance" to 1,
            "cpu_governor" to "Auto",
            "dashboard_entity_learning_applied" to true,
        )
        for ((key, value) in invalid) {
            val parsed = ProfileYaml.parse(ProfileYaml.serialize(testProfileDocument().copy(settingDefaults = mapOf(key to value))))
            val issues = parsed.issues + parsed.document?.let { ProfileValidator.validate(it, "1.0.0", bundled = false) }.orEmpty()
            assertTrue("accepted $key=$value", issues.any {
                it.severity == ProfileIssueSeverity.ERROR && (it.path == "setting_defaults" || it.path == "setting_defaults.$key")
            })
        }
    }

    @Test fun `profile setting defaults require a key value mapping`() {
        for (section in listOf("[]", "85", "null", "true")) {
            val raw = ProfileYaml.serialize(testProfileDocument()) + "\nsetting_defaults: $section\n"
            val parsed = ProfileYaml.parse(raw)
            assertTrue("accepted $section", parsed.issues.any {
                it.severity == ProfileIssueSeverity.ERROR && it.path == "setting_defaults"
            })
        }
    }

    @Test fun `controlled parser and validator outcomes carry additive stable presentation metadata`() {
        val wrongType = ProfileYaml.parse(ProfileYaml.serialize(testProfileDocument()).replace("schema: 2", "schema: wrong"))
        val parserIssue = requireNotNull(wrongType.issues.firstOrNull { it.message == "Expected a 32-bit integer." })
        assertEquals("Expected a 32-bit integer.", parserIssue.message)
        assertEquals("expected-integer", parserIssue.presentation?.code)
        assertEquals(emptyMap<String, String>(), parserIssue.presentation?.params)

        val validatorIssue = requireNotNull(
            ProfileValidator.validate(testProfileDocument().copy(schema = 99), "1.0.0", bundled = false)
                .singleOrNull { it.path == "schema" },
        )
        assertEquals("Unsupported schema 99; expected 2.", validatorIssue.message)
        assertEquals("unsupported-schema", validatorIssue.presentation?.code)
        assertEquals(mapOf("actual" to "99", "expected" to "2"), validatorIssue.presentation?.params)

        val arbitraryParserFailure = requireNotNull(ProfileYaml.parse(": invalid").issues.firstOrNull())
        assertNull(arbitraryParserFailure.presentation)
    }

    @Test fun `oversized presentation parameter preserves the compatibility issue without throwing`() {
        val oversizedValue = "x".repeat(513)
        val raw = ProfileYaml.serialize(testProfileDocument()).replace(
            "maturity: draft",
            "maturity: $oversizedValue",
        )

        val parsed = ProfileYaml.parse(raw)
        val issue = requireNotNull(parsed.issues.singleOrNull { it.message == "Unknown value '$oversizedValue'." })

        assertNull(issue.presentation)
        assertTrue(parsed.sourceBytes < ProfileMetadata.MAX_BYTES)
    }

    @Test fun `valid document round trips through strict yaml`() {
        val document = testProfileDocument()
        val parsed = ProfileYaml.parse(ProfileYaml.serialize(document))

        assertEquals(emptyList<ProfileIssue>(), parsed.issues)
        assertEquals(document, parsed.document)
        assertTrue(ProfileValidator.validate(parsed.document!!, "1.0.0", bundled = false).isEmpty())
        assertFalse(Regex("(?m)^soc:").containsMatchIn(ProfileYaml.serialize(document)))
        assertFalse(Regex("(?m)^  links:").containsMatchIn(ProfileYaml.serialize(document)))
    }

    @Test fun `structured soc facts and display links round trip without runtime authority`() {
        val document = testProfileDocument().copy(
            soc = ProfileSoc(
                model = "Rockchip RK3566",
                introducedYear = 2020,
                cpuCores = listOf(ProfileCpuCoreCluster("Arm Cortex-A55", 4)),
            ),
            metadata = testProfileDocument().metadata.copy(
                links = listOf(
                    ProfileLink("Product page", "https://vendor.example/panel"),
                    ProfileLink("Community notes", "https://en.wikipedia.org/wiki/Example"),
                ),
            ),
        )

        val serialized = ProfileYaml.serialize(document)
        val parsed = ProfileYaml.parse(serialized)

        assertEquals(document, parsed.document)
        assertTrue(ProfileValidator.validate(requireNotNull(parsed.document), "1.0.0", bundled = false).isEmpty())
        assertTrue("introduced_year: 2020" in serialized)
        assertTrue("architecture: Arm Cortex-A55" in serialized)
        assertEquals("Rockchip RK3566 · 4× Arm Cortex-A55 · introduced 2020", document.soc?.displayText())
    }

    @Test fun `profile links are bounded absolute https navigation only`() {
        val baseline = testProfileDocument()
        val invalid = baseline.copy(
            metadata = baseline.metadata.copy(
                links = listOf(
                    ProfileLink("Script", "javascript:alert(1)"),
                    ProfileLink("Credentials", "https://user:secret@example.com/panel"),
                    ProfileLink("Duplicate source", requireNotNull(baseline.metadata.source)),
                    ProfileLink("\u0001", "https://example.net/panel"),
                    ProfileLink("Trusted\u202eelpmaxe", "https://example.org/panel"),
                    ProfileLink("Hidden\u200djoiner", "https://example.edu/panel"),
                ),
            ),
        )

        val issues = ProfileValidator.validate(invalid, "1.0.0", bundled = false)

        assertTrue(issues.count { it.path.endsWith(".url") } >= 3)
        assertTrue(issues.count { it.path.endsWith(".label") } >= 3)
        val tooMany = baseline.copy(
            metadata = baseline.metadata.copy(
                links = (1..9).map { ProfileLink("Link $it", "https://example.net/$it") },
            ),
        )
        assertTrue(ProfileValidator.validate(tooMany, "1.0.0", bundled = false).any { it.path == "metadata.links" })
    }

    @Test fun `schema descriptors preserve container and item optionality`() {
        val fields = ProfileMetadata.schema.fields.associateBy { it.path }

        assertFalse(requireNotNull(fields["soc.model"]).required)
        assertFalse(requireNotNull(fields["soc.introduced_year"]).required)
        assertTrue(requireNotNull(fields["soc.cpu_cores[].architecture"]).required)
        assertTrue(requireNotNull(fields["soc.cpu_cores[].count"]).required)
        assertTrue(requireNotNull(fields["metadata.links[].label"]).required)
        assertTrue(requireNotNull(fields["metadata.links[].url"]).required)
        assertFalse(requireNotNull(fields["hardware.led.transfer"]).required)
    }

    @Test fun `soc topology is bounded and closed`() {
        val baseline = testProfileDocument()
        val invalid = baseline.copy(
            soc = ProfileSoc(
                model = "Invalid\u0000SoC",
                introducedYear = 1969,
                cpuCores = listOf(
                    ProfileCpuCoreCluster("Arm Cortex-A55", 0),
                    ProfileCpuCoreCluster("arm cortex-a55", 257),
                ),
            ),
        )
        val issues = ProfileValidator.validate(invalid, "1.0.0", bundled = false)
        assertTrue(issues.any { it.path == "soc.model" })
        assertTrue(issues.any { it.path == "soc.introduced_year" })
        assertTrue(issues.any { it.path.endsWith(".count") })
        assertTrue(issues.any { "unique" in it.message })

        val raw = ProfileYaml.serialize(baseline).replace(
            "soc_class: Test SoC",
            "soc_class: Test SoC\nsoc:\n  model: Test SoC\n  remote_data: https://example.com/data.json",
        )
        assertRejected(raw)
    }

    @Test fun `led transfer descriptor matches the parser identity default`() {
        val descriptor = requireNotNull(ProfileMetadata.schema.fields.singleOrNull {
            it.path == "hardware.led.transfer"
        })
        assertFalse(descriptor.required)
        assertTrue(descriptor.description.contains("defaults to identity"))

        val withoutTransfer = ProfileYaml.serialize(testProfileDocument()).replace("    transfer: identity\n", "")
        val parsed = ProfileYaml.parse(withoutTransfer)
        assertEquals(emptyList<ProfileIssue>(), parsed.issues)
        assertEquals("identity", parsed.document?.hardware?.led?.transfer)
    }

    @Test fun `an imported profile that still recommends the retired Shizuku route is accepted`() {
        val serialized = ProfileYaml.serialize(testProfileDocument())
        assertFalse(serialized.contains("shizuku", ignoreCase = true))
        for (value in listOf("none", "optional", "recommended")) {
            val older = serialized.replaceFirst("provisioning:\n", "provisioning:\n  access:\n    shizuku: $value\n")
            assertTrue("shizuku: $value" in older)
            val parsed = ProfileYaml.parse(older)
            assertEquals(emptyList<ProfileIssue>(), parsed.issues)
            assertEquals(emptyList<ProfileIssue>(), ProfileValidator.validate(requireNotNull(parsed.document), "1.0.0", bundled = false))
        }
    }

    @Test fun `retired proximity classifier keys load as ignored tombstones`() {
        val raw = ProfileYaml.serialize(testProfileDocument()).replace(
            "sensors:\n  cht8305:",
            """sensors:
  proximity_near_below: false
  proximity_near_raw: 123.0
  proximity_far_raw: 456.0
  proximity_graded_strategy: nspanel-firmware-cutover
  cht8305:""",
        )

        val parsed = ProfileYaml.parse(raw)
        val document = requireNotNull(parsed.document)

        assertEquals(emptyList<ProfileIssue>(), parsed.issues)
        assertEquals(testProfileDocument().sensors, document.sensors)
        assertTrue(ProfileValidator.validate(document, "1.0.0", bundled = false).isEmpty())
        val serialized = ProfileYaml.serialize(document)
        assertFalse("proximity_near_below" in serialized)
        assertFalse("proximity_near_raw" in serialized)
        assertFalse("proximity_far_raw" in serialized)
        assertFalse("proximity_graded_strategy" in serialized)
    }

    /**
     * The keyevent route is legal under either privilege, unlike the two bl_power routes: it injects
     * through root where the app has it and through the helper daemon otherwise, so neither
     * `app_can_su` setting can be rejected on its own. It still selects a privileged driver, which the
     * requirements set must declare.
     */
    @Test fun `keyevent screen off validates under either su capability and requires its own driver`() {
        listOf(false, true).forEach { appCanSu ->
            val base = testProfileDocument()
            val document = base.copy(
                platform = ProfilePlatform(if (appCanSu) "android" else "none", appCanSu = appCanSu),
                hardware = base.hardware.copy(screenOff = "keyevent"),
                requires = ProfileRequirements(
                    drivers = setOf("screen.keyevent") + if (appCanSu) setOf("access.android-su") else emptySet(),
                ),
            )
            assertEquals(
                "keyevent must validate with app_can_su=$appCanSu",
                emptyList<ProfileIssue>(),
                ProfileValidator.validate(document, "1.0.0", bundled = false),
            )
            assertEquals(document, ProfileYaml.parse(ProfileYaml.serialize(document)).document)

            val missingDriver = document.copy(
                requires = ProfileRequirements(
                    drivers = setOf("screen.brightness-zero") +
                        if (appCanSu) setOf("access.android-su") else emptySet(),
                ),
            )
            assertTrue(
                "an undeclared screen.keyevent driver must be rejected",
                ProfileValidator.validate(missingDriver, "1.0.0", bundled = false)
                    .any { it.path == "requires.drivers" },
            )
        }
    }

    /** The unprivileged Android-sleep routes validate on a sandboxed profile, require their own driver,
     *  and reach their own runtime route. */
    @Test fun `screen-timeout and device-admin validate without su and reach their routes`() {
        mapOf("screen-timeout" to ScreenOff.SCREEN_TIMEOUT, "device-admin" to ScreenOff.DEVICE_ADMIN).forEach { (token, route) ->
            val base = testProfileDocument()
            val document = base.copy(
                platform = ProfilePlatform("none", appCanSu = false),
                hardware = base.hardware.copy(screenOff = token),
                requires = ProfileRequirements(drivers = setOf("screen.$token")),
            )
            assertEquals(token, emptyList<ProfileIssue>(), ProfileValidator.validate(document, "1.0.0", bundled = false))
            assertTrue(
                "an undeclared screen.$token driver must be rejected",
                ProfileValidator.validate(document.copy(requires = ProfileRequirements(drivers = setOf("screen.brightness-zero"))), "1.0.0", bundled = false)
                    .any { it.path == "requires.drivers" },
            )
            val profile = DataDeviceProfile(document = document, productVersion = "1.0.0", revision = "test", trustedBundledContent = false)
            assertEquals(route, profile.screenOff)
        }
    }

    /** Validation alone proves nothing about what the runtime does with the value, so the adapter's
     *  own mapping is asserted here: a validated `keyevent` document must reach the keyevent route,
     *  and the permissive `else` that catches unknown text must not be what carries it there. */
    @Test fun `a keyevent document reaches the keyevent screen route`() {
        val base = testProfileDocument()
        fun profileFor(token: String) = DataDeviceProfile(
            document = base.copy(hardware = base.hardware.copy(screenOff = token)),
            productVersion = "1.0.0",
            revision = "test",
            trustedBundledContent = false,
        )
        assertEquals(ScreenOff.KEYEVENT, profileFor("keyevent").screenOff)
        assertEquals(ScreenOff.SU_BLPOWER, profileFor("su-blpower").screenOff)
        assertEquals(ScreenOff.DAEMON_BLPOWER, profileFor("daemon-blpower").screenOff)
        assertEquals(ScreenOff.BRIGHTNESS_ZERO, profileFor("brightness-zero").screenOff)
        assertEquals("unknown text must stay the safest route", ScreenOff.BRIGHTNESS_ZERO, profileFor("keyevents").screenOff)
    }

    /** The keyevent route is privileged, so it must select a privileged driver rather than the
     *  unprivileged brightness one — the classification that decides what a profile is allowed to do. */
    @Test fun `the keyevent driver is declared and privileged`() {
        val driver = ProfileMetadata.drivers.single { it.id == "screen.keyevent" }
        assertTrue("screen.keyevent must be privileged", driver.privileged)
        assertEquals(ProfileHelperAuthorityDemand.SANDBOX_FALLBACK, driver.helperDemand)
    }

    /** An unknown route must still be refused: the new value widens the vocabulary, not the grammar. */
    @Test fun `an unknown screen off route is still rejected`() {
        val base = testProfileDocument()
        val issues = ProfileValidator.validate(
            base.copy(hardware = base.hardware.copy(screenOff = "keyevents")),
            "1.0.0",
            bundled = false,
        )
        assertTrue(issues.any { it.path == "hardware.screen_off" })
    }

    /** The su form, screen-off route and LED mechanism names are one vocabulary across the schema
     *  descriptor, the validator and the runtime adapter. */
    @Test fun `profile enum names are accepted, mapped and refused the same way everywhere`() {
        val base = testProfileDocument()
        fun codes(document: ProfileDocument) = ProfileValidator.validate(document, "1.0.0", bundled = false)
            .mapNotNull { it.presentation?.code }.toSet()
        fun withSu(form: String, appCanSu: Boolean) = base.copy(platform = ProfilePlatform(form, appCanSu = appCanSu))
        fun runtime(document: ProfileDocument) = DataDeviceProfile(document, "1.0.0", "test", trustedBundledContent = false)

        assertEquals(listOf("none", "android", "toolbox"), ProfileMetadata.schema.fields.single { it.path == "platform.su_form" }.enumValues)
        assertEquals(
            listOf("brightness-zero", "su-blpower", "daemon-blpower", "keyevent", "screen-timeout", "device-admin", "vendor-rpc"),
            ProfileMetadata.schema.fields.single { it.path == "hardware.screen_off" }.enumValues,
        )
        assertEquals(SuForm.TOOLBOX, runtime(withSu("toolbox", true)).suForm)
        assertEquals(SuForm.ANDROID, runtime(withSu("android", true)).suForm)
        assertEquals(SuForm.NONE, runtime(withSu("none", false)).suForm)
        assertTrue("unknown-su-form" in codes(withSu("busybox", true)))
        assertTrue("app-su-needs-su-form" in codes(withSu("none", true)))
        assertTrue("su-blpower-needs-app-su" in codes(base.copy(platform = ProfilePlatform("none", appCanSu = false), hardware = base.hardware.copy(screenOff = "su-blpower"))))
        assertTrue("daemon-blpower-sandbox-only" in codes(withSu("android", true).let { it.copy(hardware = it.hardware.copy(screenOff = "daemon-blpower")) }))
        assertTrue("daemon-led-sandbox-only" in codes(withSu("android", true).let { it.copy(hardware = it.hardware.copy(led = it.hardware.led.copy(mechanism = "sysfs-daemon"))) }))
    }

    @Test fun `duplicate keys are rejected`() {
        val raw = ProfileYaml.serialize(testProfileDocument()).replaceFirst("schema: 2", "schema: 2\nschema: 2")
        assertRejected(raw)
    }

    @Test fun `non string map key reports issue and never escapes`() {
        val raw = "1: value\n" + ProfileYaml.serialize(testProfileDocument())
        assertRejected(raw)
    }

    @Test fun `collection aliases are rejected`() {
        val raw = ProfileYaml.serialize(testProfileDocument()).replace(
            "limitations:\n    - Test-only profile.",
            "limitations: &shared\n    - Test-only profile.\n  tested_firmware: *shared",
        )
        assertRejected(raw)
    }

    @Test fun `unknown fields are rejected`() {
        assertRejected(ProfileYaml.serialize(testProfileDocument()) + "unknown_field: true\n")
    }

    @Test fun `two thousand nested collections are rejected before construction`() {
        val raw = "[".repeat(2_000) + "0" + "]".repeat(2_000)
        assertRejected(raw)
    }

    @Test fun `oversize yaml is rejected before loading`() {
        val raw = "#" + "x".repeat(ProfileMetadata.MAX_BYTES + 1)
        assertRejected(raw)
    }

    @Test fun `privileged paths are driver specific allowlists`() {
        val unsupported = testProfileDocument().copy(
            requires = ProfileRequirements(drivers = setOf("screen.brightness-zero", "relay.sysfs")),
            hardware = testProfileDocument().hardware.copy(relayBase = "/sys/class/strelay;id"),
        )
        val issues = ProfileValidator.validate(unsupported, "1.0.0", bundled = false)
        assertTrue(issues.any { it.path == "hardware.relay_base" && it.severity == ProfileIssueSeverity.ERROR })
    }

    @Test fun `evdev path code event and duplicate mappings fail closed`() {
        val invalid = ProfileEvdevButton("/dev/input/event1;id", 0, false, "POWER")
        val document = testProfileDocument().copy(
            requires = ProfileRequirements(drivers = setOf("screen.brightness-zero", "input.evdev")),
            input = ProfileInput(listOf(invalid, invalid)),
        )
        val issues = ProfileValidator.validate(document, "1.0.0", bundled = false)
        assertTrue(issues.any { it.path.endsWith(".node") })
        assertTrue(issues.any { it.path.endsWith(".code") })
        assertTrue(issues.any { it.path.endsWith(".event_type") })
        assertTrue(issues.any { "Duplicate" in it.message })
    }

    @Test fun `relay fallback duplicates and overflowing gpio block are rejected`() {
        val document = testProfileDocument().copy(
            requires = ProfileRequirements(drivers = setOf("screen.brightness-zero", "relay.sysfs", "relay.gpio-button-led")),
            hardware = testProfileDocument().hardware.copy(
                relayBase = "/sys/class/strelay",
                relayBaseFallbacks = listOf("/sys/class/strelay", "/sys/class/strelay"),
                buttonLedGpioBase = 4093,
            ),
        )
        val issues = ProfileValidator.validate(document, "1.0.0", bundled = false)
        assertTrue(issues.any { it.path == "hardware.relay_base_fallbacks" })
        assertTrue(issues.any { it.path == "hardware.button_led_gpio_base" })
    }

    @Test fun `profile cannot supply an apk trust root`() {
        val raw = ProfileYaml.serialize(testProfileDocument()).replace(
            "software: {}",
            "software:\n    webview:\n      artifact: lineageos-150-arm64\n      url: https://evil.example/app.apk\n      cert_sha256: ${"0".repeat(64)}",
        )
        assertRejected(raw)
        val unknown = testProfileDocument().copy(
            requires = ProfileRequirements(drivers = setOf("screen.brightness-zero", "update.webview")),
            provisioning = testProfileDocument().provisioning.copy(
                software = ProfileProvisioningSoftware(
                    webView = ProfileWebViewProvisioning("third-party-apk"),
                ),
            ),
        )
        assertTrue(ProfileValidator.validate(unknown, "1.0.0", bundled = false).any {
            it.path == "provisioning.software.webview.artifact"
        })
    }

    @Test fun `known core webview artifact resolves compiled trust data`() {
        val document = testProfileDocument().copy(
            requires = ProfileRequirements(drivers = setOf("screen.brightness-zero", "update.webview")),
            provisioning = testProfileDocument().provisioning.copy(
                software = ProfileProvisioningSoftware(
                    webView = ProfileWebViewProvisioning("lineageos-150-arm64"),
                ),
            ),
        )
        assertTrue(ProfileValidator.validate(document, "1.0.0", bundled = false).isEmpty())
        assertEquals(ProfileArtifacts.webViews["lineageos-150-arm64"], dataProfile(document).recommendedWebView)
    }

    @Test fun `display recommendations stay within controller bounds`() {
        val density = testProfileDocument().copy(
            provisioning = testProfileDocument().provisioning.copy(
                display = ProfileProvisioningDisplay(
                    density = ProfileDensity.Fixed(641),
                    fontScale = 1.51f,
                ),
            ),
        )

        val issues = ProfileValidator.validate(density, "1.0.0", bundled = false)

        assertTrue(issues.any { it.path == "provisioning.display.density" })
        assertTrue(issues.any { it.path == "provisioning.display.font_scale" })
    }

    @Test fun `schema 1 locations are not interpreted by schema 2`() {
        val serialized = ProfileYaml.serialize(testProfileDocument())
        assertRejected(serialized.replace("schema: 2", "schema: 1"))
        assertRejected(serialized.replace(
            "has_recents: true",
            "has_recents: true\n  shizuku: recommended",
        ))
        assertRejected(serialized + "updates: {}\n")
        assertRejected(serialized + "taming: []\n")
    }

    @Test fun `recipes are core owned argument free and unique`() {
        val known = testProfileDocument().copy(
            provisioning = testProfileDocument().provisioning.copy(
                recipes = listOf(ProfileRecipeSelection("tpa10.vendor-stack-minimize")),
            ),
        )
        assertTrue(ProfileValidator.validate(known, "1.0.0", bundled = false).isEmpty())

        val unknown = known.copy(
            provisioning = known.provisioning.copy(
                recipes = listOf(ProfileRecipeSelection("community.shell-command")),
            ),
        )
        assertTrue(ProfileValidator.validate(unknown, "1.0.0", bundled = false).any {
            it.path == "provisioning.recipes[0].id"
        })

        val raw = ProfileYaml.serialize(known).replace(
            "id: tpa10.vendor-stack-minimize",
            "id: tpa10.vendor-stack-minimize\n      command: reboot",
        )
        assertRejected(raw)
    }

    @Test fun `sandbox walled profile can declare helper streamed gpio proximity`() {
        val document = testProfileDocument().copy(
            requires = ProfileRequirements(drivers = setOf("screen.brightness-zero", "sensor.gpio-proximity")),
            sensors = ProfileSensors(proximityGpio = 18),
        )

        val issues = ProfileValidator.validate(document, "1.0.0", bundled = false)

        assertFalse(issues.any { it.path == "sensors.proximity_gpio" })
        assertEquals(ProfileHelperAuthorityDemand.REQUIRED,
            ProfileMetadata.helperAuthorityDemand[document.requires.drivers.single { it.startsWith("sensor.") }])
    }

    @Test fun `room climate requires the helper`() {
        val document = testProfileDocument().copy(
            requires = ProfileRequirements(drivers = setOf("screen.brightness-zero", "sensor.cht8305-daemon")),
            sensors = ProfileSensors(cht8305 = true),
        )

        assertEquals(ProfileHelperAuthorityDemand.REQUIRED,
            ProfileMetadata.helperAuthorityDemand[document.requires.drivers.single { it.startsWith("sensor.") }])
    }

    @Test fun `the core version gate admits a core at or above the profile minimum`() {
        fun admits(core: String, minimum: String) = ProfileValidator.validate(
            testProfileDocument().let { it.copy(requires = it.requires.copy(minCoreVersion = minimum)) },
            core,
            bundled = false,
        ).none { it.path == "requires.min_core_version" }

        assertTrue(admits("0.9.3-rc10", "0.9.3-rc2"))
        assertFalse(admits("0.9.3-rc9", "0.9.3-rc10"))
        assertTrue(admits("0.9.3-rc2", "0.9.3-rc2"))
        assertFalse(admits("0.9.3-rc2", "0.9.3"))
        assertTrue(admits("0.9.3", "0.9.3-rc10"))
        assertTrue(admits("0.9.10", "0.9.9"))
        assertFalse(admits("0.9.9", "0.9.10"))
        assertTrue(admits("0.9.3", "0.9"))
        assertFalse(admits("0.9", "0.9.3"))
        assertTrue(admits("1.0.0-beta2", "1.0.0-alpha3"))
        assertFalse(admits("1.0.0-beta2", "1.0.0-rc1"))
        assertTrue(admits("1.0.0-preview1", "0.9.0"))
        assertTrue(admits("1.0.0-preview10", "1.0.0-preview2"))
        assertFalse(admits("1.0.0-preview2", "1.0.0-rc1"))
        assertTrue(admits("1.0.0-rc1", "1.0.0-preview2"))
        assertTrue(admits("1.0.0", "1.0.0-rc1"))
        assertFalse(admits("0.9.3-rc2", "0.9.3-rc2.1"))
        assertTrue(admits("0.9.3-rc2.1", "0.9.3-rc2"))
        assertFalse(admits("1.0.0-beta.2", "1.0.0-beta.10"))
    }

    private fun assertRejected(raw: String) {
        val result = runCatching { ProfileYaml.parse(raw) }
        assertTrue("parser threw ${result.exceptionOrNull()}", result.isSuccess)
        assertNull(result.getOrThrow().document)
        assertFalse(result.getOrThrow().issues.isEmpty())
    }

    private fun dataProfile(document: ProfileDocument): DataDeviceProfile {
        val raw = ProfileYaml.serialize(document)
        return DataDeviceProfile(
            document = document,
            productVersion = "",
            revision = ProfileYaml.sha256(raw),
            trustedBundledContent = false,
        )
    }
    @Test fun everyCoreDriverHasThePinnedHelperAuthorityDemand() {
        val expected = mapOf(
            "access.android-su" to ProfileHelperAuthorityDemand.NONE,
            "access.toolbox-su" to ProfileHelperAuthorityDemand.NONE,
            "input.button-backlight" to ProfileHelperAuthorityDemand.REQUIRED,
            "input.evdev" to ProfileHelperAuthorityDemand.REQUIRED,
            "led.autodetect" to ProfileHelperAuthorityDemand.SANDBOX_FALLBACK,
            "led.rk3576-ioctl" to ProfileHelperAuthorityDemand.SANDBOX_FALLBACK,
            "led.rk3576-ioctl-daemon" to ProfileHelperAuthorityDemand.REQUIRED,
            "led.sysfs-daemon" to ProfileHelperAuthorityDemand.REQUIRED,
            "radio.siliconlabs-host" to ProfileHelperAuthorityDemand.NONE,
            "relay.sysfs" to ProfileHelperAuthorityDemand.NONE,
            "relay.gpio-button-led" to ProfileHelperAuthorityDemand.NONE,
            "screen.brightness-zero" to ProfileHelperAuthorityDemand.NONE,
            "screen.daemon-blpower" to ProfileHelperAuthorityDemand.REQUIRED,
            "screen.su-blpower" to ProfileHelperAuthorityDemand.SANDBOX_FALLBACK,
            // Added 2026-08-17: the keyevent route injects through root where the app has it and the
            // helper daemon otherwise, so the helper is a fallback rather than a requirement.
            "screen.keyevent" to ProfileHelperAuthorityDemand.SANDBOX_FALLBACK,
            // Added 2026-10-08: unprivileged Android sleep (WRITE_SETTINGS, device admin); no helper.
            "screen.screen-timeout" to ProfileHelperAuthorityDemand.NONE,
            "screen.device-admin" to ProfileHelperAuthorityDemand.NONE,
            "screen.vendor-rpc" to ProfileHelperAuthorityDemand.NONE,
            "sensor.android" to ProfileHelperAuthorityDemand.NONE,
            "sensor.cht8305-daemon" to ProfileHelperAuthorityDemand.REQUIRED,
            // Added 2026-08-14: the VI530x range route is helper-only — there is no sandbox path to a
            // misc-device ioctl, so the demand is REQUIRED.
            "sensor.vi530x-daemon" to ProfileHelperAuthorityDemand.REQUIRED,
            "sensor.gpio-proximity" to ProfileHelperAuthorityDemand.REQUIRED,
            "update.webview" to ProfileHelperAuthorityDemand.NONE,
        )

        assertEquals(ProfileMetadata.drivers.map { it.id }.toSet(), expected.keys)
        assertEquals(expected, ProfileMetadata.helperAuthorityDemand)
    }


}
