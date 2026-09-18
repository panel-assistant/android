package io.github.maxlyth.hapaneld.mqtt

import io.github.maxlyth.hapaneld.mqttKnownConfigTopics
import io.github.maxlyth.hapaneld.mqttStalePanelCleanup
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The MQTT update entities: exact discovery, the Companion state matrix, strict `install` admission,
 * progress, and the discovery-shape transitions that keep Home Assistant truthful. Every JSON key is
 * checked with `has` before it is read so a broken payload fails as an assertion.
 */
class SoftwareUpdateEntitiesTest {
    private val panel = "test"
    private val availability = """"availability_topic":"ha-paneld/test/availability","payload_available":"online","payload_not_available":"offline""""
    private val device = """"device":{"identifiers":["ha-paneld-test"]}"""

    private val paneldTarget = SoftwareTarget(
        version = "0.9.8",
        tag = "v0.9.8",
        releaseUrl = "https://github.com/panel-assistant/android/releases/tag/v0.9.8",
    )
    private val companionTarget = SoftwareTarget(
        version = "2026.6.5",
        tag = "2026.6.5",
        releaseUrl = "https://github.com/home-assistant/android/releases/tag/2026.6.5",
    )

    private fun paneld(
        installed: String = "0.9.7",
        target: SoftwareTarget? = paneldTarget,
        canInstall: Boolean = true,
        installing: Boolean = false,
        suppressed: Boolean = false,
        channel: String = "stable",
    ) = SoftwareUpdateInputs(
        component = SoftwareComponent.PANELD,
        installedVersion = installed,
        target = target,
        channel = channel,
        canInstall = canInstall,
        installing = installing,
        suppressed = suppressed,
    )

    private fun companion(
        installed: String? = "2026.6.5-minimal",
        full: Boolean = false,
        target: SoftwareTarget? = companionTarget,
        cap: String? = null,
        canInstall: Boolean = true,
        installing: Boolean = false,
    ) = SoftwareUpdateInputs(
        component = SoftwareComponent.COMPANION,
        installedVersion = installed,
        externallyManaged = full,
        target = target,
        channel = "stable",
        cap = cap,
        canInstall = canInstall,
        installing = installing,
    )

    private fun state(inputs: SoftwareUpdateInputs) = JSONObject(SoftwareUpdateEntities.stateJson(inputs))

    private fun field(json: JSONObject, key: String): Any {
        assertTrue("state is missing $key: $json", json.has(key))
        return json.get(key)
    }

    // --- discovery -------------------------------------------------------------------------------

    @Test fun paneldDiscoveryIsByteExactAndReusesTheButtonCommandTopic() {
        assertEquals(
            """{"default_entity_id":"update.test_ha_paneld","name":"ha-paneld","object_id":"test_ha_paneld",""" +
                """"unique_id":"test_ha_paneld_update","state_topic":"ha-paneld/test/update/ha_paneld/state",""" +
                """"command_topic":"ha-paneld/test/update_paneld/set","payload_install":"install",""" +
                """"entity_category":"config",$availability,$device}""",
            SoftwareUpdateEntities.discoveryJson(panel, SoftwareComponent.PANELD, installable = true, availability, device),
        )
        assertEquals(
            "homeassistant/update/test_ha_paneld_update/config",
            SoftwareUpdateEntities.configTopic(panel, SoftwareComponent.PANELD),
        )
    }

    @Test fun companionDiscoveryWithoutAnInstallRouteOmitsTheCommand() {
        assertEquals(
            """{"default_entity_id":"update.test_ha_companion","name":"HA Companion","object_id":"test_ha_companion",""" +
                """"unique_id":"test_ha_companion_update","state_topic":"ha-paneld/test/update/ha_companion/state",""" +
                """"entity_category":"config",$availability,$device}""",
            SoftwareUpdateEntities.discoveryJson(panel, SoftwareComponent.COMPANION, installable = false, availability, device),
        )
        assertEquals("ha-paneld/test/update_companion/set", SoftwareUpdateEntities.commandTopic(panel, SoftwareComponent.COMPANION))
    }

    @Test fun bothUpdateEntitiesAndTheRetiringButtonsAreTombstonable() {
        val known = mqttKnownConfigTopics(panel)
        for (component in SoftwareComponent.entries) {
            assertTrue(SoftwareUpdateEntities.configTopic(panel, component) in known)
        }
        assertTrue("homeassistant/button/test_update_paneld/config" in known)
        assertTrue("homeassistant/button/test_update_companion/config" in known)
        val cleanup = mqttStalePanelCleanup("old", panel)
        assertTrue(cleanup.any { it.topic == "homeassistant/update/old_ha_paneld_update/config" && it.payload.isEmpty() && it.retain })
        assertTrue(cleanup.any { it.topic == "homeassistant/update/old_ha_companion_update/config" && it.payload.isEmpty() && it.retain })
    }

    // --- ha-paneld state -------------------------------------------------------------------------

    @Test fun paneldReportsInstalledLatestNotesAndIdleProgress() {
        val json = state(paneld())
        assertEquals("0.9.7", field(json, "installed_version"))
        assertEquals("0.9.8", field(json, "latest_version"))
        assertEquals("ha-paneld", field(json, "title"))
        assertEquals("https://github.com/panel-assistant/android/releases/tag/v0.9.8", field(json, "release_url"))
        assertEquals(false, field(json, "in_progress"))
        assertEquals("Stable channel.", field(json, "release_summary"))
    }

    @Test fun anUnresolvedCatalogOmitsLatestAndOffersNoInstall() {
        val inputs = paneld(target = null)
        val json = state(inputs)
        assertFalse(json.has("latest_version"))
        assertFalse(json.has("release_url"))
        assertFalse(SoftwareUpdateEntities.installable(inputs))
        assertEquals(SoftwareInstallAdmission.Refused("unresolved"), SoftwareUpdateEntities.admit(inputs))
    }

    @Test fun aBuildNewerThanTheChannelHeadSaysSoAndIsNeverDowngraded() {
        val rc3 = SoftwareTarget("0.9.7-rc3", "v0.9.7-rc3", "https://github.com/panel-assistant/android/releases/tag/v0.9.7-rc3")
        val inputs = paneld(installed = "0.9.7-rc4", target = rc3, channel = "prerelease")
        val json = state(inputs)
        assertEquals("0.9.7-rc3", field(json, "latest_version"))
        assertEquals("Pre-release channel. This build is newer than the latest release.", field(json, "release_summary"))
        assertEquals(SoftwareInstallAdmission.Refused("downgrade"), SoftwareUpdateEntities.admit(inputs))
    }

    @Test fun anInstalledCurrentBuildIsNotReinstalled() {
        assertEquals(
            SoftwareInstallAdmission.Refused("up-to-date"),
            SoftwareUpdateEntities.admit(paneld(installed = "0.9.8")),
        )
    }

    // --- Companion matrix --------------------------------------------------------------------------

    @Test fun companionUpToDateStripsTheVariantSoHomeAssistantSeesNoUpdate() {
        val inputs = companion(installed = "2026.6.5-minimal")
        val json = state(inputs)
        assertEquals("2026.6.5", field(json, "installed_version"))
        assertEquals("2026.6.5", field(json, "latest_version"))
        assertEquals("HA Companion (minimal)", field(json, "title"))
        assertTrue(SoftwareUpdateEntities.installable(inputs))
        assertEquals(SoftwareInstallAdmission.Refused("up-to-date"), SoftwareUpdateEntities.admit(inputs))
    }

    @Test fun companionOutdatedOffersExactlyTheResolvedTag() {
        val inputs = companion(installed = "2026.5.1-minimal")
        val json = state(inputs)
        assertEquals("2026.5.1", field(json, "installed_version"))
        assertEquals("2026.6.5", field(json, "latest_version"))
        assertEquals(
            "https://github.com/home-assistant/android/releases/tag/2026.6.5",
            field(json, "release_url"),
        )
        assertEquals(SoftwareInstallAdmission.Admitted("2026.6.5"), SoftwareUpdateEntities.admit(inputs))
    }

    @Test fun companionAbsentWithholdsTheEntityEntirely() {
        val inputs = companion(installed = null)
        assertTrue(SoftwareUpdateEntities.companionAbsent(inputs))
        assertTrue(SoftwareUpdateEntities.withheld(inputs))
        assertEquals(
            SoftwareDiscoveryShape(announced = false, installable = false, hasLatest = false),
            SoftwareUpdateEntities.shape(inputs),
        )
        assertFalse(SoftwareUpdateEntities.installable(inputs))
        // A forwarded install cannot add a first Companion from Home Assistant; the Install page does that.
        assertEquals(SoftwareInstallAdmission.Refused("not-installed"), SoftwareUpdateEntities.admit(inputs))
        // Absence withdraws an announced entity like a Panel Assistant lease does: one retained tombstone.
        val previous = SoftwareUpdateEntities.shape(companion())
        val step = SoftwareUpdateEntities.transition(previous, SoftwareUpdateEntities.shape(inputs), announcing = false)
        assertEquals(SoftwareDiscoveryStep.WITHDRAW, step)
        assertEquals(
            listOf(SoftwarePublication(SoftwareUpdateEntities.configTopic("test", SoftwareComponent.COMPANION), "", retain = true)),
            SoftwareUpdateEntities.discoveryPlan("test", inputs, step, availability, device),
        )
    }

    @Test fun companionAbsentWithoutAnInstallRouteIsWithheldToo() {
        val inputs = companion(installed = null, canInstall = false)
        assertTrue(SoftwareUpdateEntities.withheld(inputs))
        assertFalse(SoftwareUpdateEntities.shape(inputs).announced)
        assertEquals(SoftwareInstallAdmission.Refused("no-install-route"), SoftwareUpdateEntities.admit(inputs))
    }

    @Test fun anInstalledCompanionIsAnnouncedAgainAfterAbsence() {
        val absent = SoftwareUpdateEntities.shape(companion(installed = null))
        val installed = companion(installed = "2026.5.1-minimal")
        assertFalse(SoftwareUpdateEntities.withheld(installed))
        assertEquals(
            SoftwareDiscoveryStep.ANNOUNCE,
            SoftwareUpdateEntities.transition(absent, SoftwareUpdateEntities.shape(installed), announcing = false),
        )
        assertEquals("2026.5.1", field(state(installed), "installed_version"))
    }

    @Test fun paneldIsNeverTreatedAsAnAbsentCompanion() {
        assertFalse(SoftwareUpdateEntities.companionAbsent(paneld()))
        assertFalse(SoftwareUpdateEntities.withheld(paneld()))
    }

    @Test fun aPlayManagedCompanionIsReadOnlyWithNoLatestVersion() {
        val inputs = companion(installed = "2026.6.5-full", full = true)
        val json = state(inputs)
        assertEquals("2026.6.5", field(json, "installed_version"))
        assertFalse(json.has("latest_version"))
        assertFalse(json.has("release_url"))
        assertEquals("HA Companion (full)", field(json, "title"))
        assertEquals("Managed by Google Play. ha-paneld does not update the full Companion app.", field(json, "release_summary"))
        assertFalse(SoftwareUpdateEntities.installable(inputs))
        assertEquals(SoftwareInstallAdmission.Refused("externally-managed"), SoftwareUpdateEntities.admit(inputs))
    }

    @Test fun aCompanionAboveItsSafetyCapIsMarkedUnsupportedAndInstallRemediates() {
        val capped = SoftwareTarget(
            "2026.5.4", "2026.5.4", "https://github.com/home-assistant/android/releases/tag/2026.5.4",
            capped = true, newestVersion = "2026.9.1",
        )
        val inputs = companion(installed = "2026.9.1-minimal", target = capped, cap = "2026.5.4")
        val json = state(inputs)
        assertEquals("unsupported 2026.9.1", field(json, "installed_version"))
        assertEquals("2026.5.4", field(json, "latest_version"))
        assertEquals(
            "Stable channel. Installed 2026.9.1 is above this panel's 2026.5.4 safety cap; installing moves it to 2026.5.4.",
            field(json, "release_summary"),
        )
        assertEquals(SoftwareInstallAdmission.Admitted("2026.5.4", downgrade = true), SoftwareUpdateEntities.admit(inputs))
    }

    @Test fun aCappedTargetExplainsTheHold() {
        val capped = SoftwareTarget(
            "2026.5.4", "2026.5.4", "https://github.com/home-assistant/android/releases/tag/2026.5.4",
            capped = true, newestVersion = "2026.9.1",
        )
        val json = state(companion(installed = "2026.5.4-minimal", target = capped, cap = "2026.5.4"))
        assertEquals(
            "Stable channel. This panel's safety cap is 2026.5.4; the newest release is 2026.9.1.",
            field(json, "release_summary"),
        )
    }

    @Test fun aTargetAboveTheCapIsRefusedEvenIfNewer() {
        val inputs = companion(installed = "2026.5.1-minimal", target = companionTarget, cap = "2026.5.4")
        assertEquals(SoftwareInstallAdmission.Refused("exceeds-cap"), SoftwareUpdateEntities.admit(inputs))
    }

    @Test fun summariesNeverExceedHomeAssistantsLimit() {
        val longCap = "2026." + "9".repeat(300)
        val json = state(companion(installed = "2026.9.1-minimal", cap = longCap, target = companionTarget))
        assertTrue((field(json, "release_summary") as String).length <= 255)
    }

    // --- command admission -----------------------------------------------------------------------

    @Test fun onlyExactInstallAndPressPayloadsAreRecognised() {
        assertEquals(SoftwareCommand.INSTALL, SoftwareUpdateEntities.classifyCommand("install"))
        assertEquals(SoftwareCommand.LEGACY_PRESS, SoftwareUpdateEntities.classifyCommand("PRESS"))
        for (payload in listOf(
            "", "INSTALL", "install ", " install", "press", "v0.9.8", "0.9.8",
            "https://example.invalid/ha-paneld.apk", """{"version":"0.9.8"}""", "install\n",
        )) {
            assertEquals("payload <$payload>", SoftwareCommand.REJECTED, SoftwareUpdateEntities.classifyCommand(payload))
        }
    }

    private class Recorder {
        val installs = mutableListOf<String>()
        val approvals = mutableListOf<String>()
        var legacy = 0
        var busy = false
        var inputReads = 0
    }

    private fun route(payload: String, inputs: SoftwareUpdateInputs?, recorder: Recorder) =
        SoftwareUpdateEntities.route(
            payload = payload,
            inputs = { recorder.inputReads++; inputs },
            legacy = { recorder.legacy++ },
            authorize = { recorder.approvals += it },
            install = { recorder.installs += it; !recorder.busy },
        )

    @Test fun unsignedOrUnlistedInstallPayloadsNeverReachTheInstaller() {
        for (payload in listOf(
            "https://example.invalid/unsigned.apk", "v0.9.1", "0.9.9", """{"version":"0.9.9"}""", "INSTALL", "",
        )) {
            val recorder = Recorder()
            val outcome = route(payload, paneld(), recorder)
            assertEquals("payload <$payload>", SoftwareCommandOutcome.Refused("payload"), outcome)
            assertEquals("payload <$payload> installs", 0, recorder.installs.size)
            assertEquals("payload <$payload> approvals", 0, recorder.approvals.size)
            assertEquals("payload <$payload> legacy", 0, recorder.legacy)
            assertEquals("payload <$payload> must be refused before inputs are read", 0, recorder.inputReads)
        }
    }

    @Test fun aTargetThatIsNotAValidCatalogTagIsRefused() {
        for (tag in listOf("../../evil", "https://example.invalid/x.apk", "v0.9.8 ", "", "a".repeat(65))) {
            val recorder = Recorder()
            val inputs = paneld(target = paneldTarget.copy(tag = tag))
            assertEquals("tag <$tag>", SoftwareCommandOutcome.Refused("invalid-tag"), route("install", inputs, recorder))
            assertEquals("tag <$tag> installs", 0, recorder.installs.size)
        }
    }

    @Test fun anAdmittedInstallStartsExactlyTheCatalogTagOnce() {
        val recorder = Recorder()
        assertEquals(SoftwareCommandOutcome.Started("v0.9.8"), route("install", paneld(), recorder))
        assertEquals(listOf("v0.9.8"), recorder.installs)
        assertEquals(listOf("v0.9.8"), recorder.approvals)
        assertEquals(0, recorder.legacy)
    }

    @Test fun refusedInstallsMakeNoInstallerCall() {
        val cases = mapOf(
            "unresolved" to paneld(target = null),
            "downgrade" to paneld(installed = "0.9.9"),
            "up-to-date" to paneld(installed = "0.9.8"),
            "no-install-route" to paneld(canInstall = false),
            "in-progress" to paneld(installing = true),
            "externally-managed" to companion(full = true, installed = "2026.5.1-full"),
            "uncomparable" to paneld(installed = "not-a-version"),
        )
        for ((reason, inputs) in cases) {
            val recorder = Recorder()
            assertEquals(reason, SoftwareCommandOutcome.Refused(reason), route("install", inputs, recorder))
            assertEquals("$reason installs", 0, recorder.installs.size)
            assertEquals("$reason approvals", 0, recorder.approvals.size)
        }
        val recorder = Recorder()
        assertEquals(SoftwareCommandOutcome.Refused("unwired"), route("install", null, recorder))
        assertEquals(0, recorder.installs.size)
    }

    @Test fun theLegacyButtonKeepsItsOwnPathAndNeverAdmits() {
        val recorder = Recorder()
        assertEquals(SoftwareCommandOutcome.Legacy, route("PRESS", paneld(), recorder))
        assertEquals(1, recorder.legacy)
        assertEquals(0, recorder.installs.size)
        assertEquals(0, recorder.inputReads)
    }

    @Test fun aBusyLaneIsReportedAsBusy() {
        val recorder = Recorder().apply { busy = true }
        assertEquals(SoftwareCommandOutcome.Busy("v0.9.8"), route("install", paneld(), recorder))
        assertEquals(1, recorder.installs.size)
    }

    @Test fun aRefusedApprovalStopsBeforeTheInstaller() {
        var installs = 0
        val refused = runCatching {
            SoftwareUpdateEntities.route(
                payload = "install",
                inputs = { paneld() },
                legacy = {},
                authorize = { error("approval required") },
                install = { installs++; true },
            )
        }
        assertTrue(refused.isFailure)
        assertEquals(0, installs)
    }

    // --- progress ----------------------------------------------------------------------------------

    private fun sources(running: String?) = SoftwareUpdateSources(
        paneldVersion = "0.9.7",
        paneldChannel = "stable",
        paneldTarget = paneldTarget,
        companionMinimalVersion = "2026.5.1-minimal",
        companionFullVersion = null,
        companionChannel = "stable",
        companionCap = null,
        companionTarget = companionTarget,
        runningOperation = running,
        panelAssistantOwnsPaneldUpdate = false,
    )

    @Test fun onlyTheComponentThatOwnsTheLaneReportsProgress() {
        val paneldRun = sources("ha-paneld")
        assertTrue(paneldRun.inputs(SoftwareComponent.PANELD, true).installing)
        assertFalse(paneldRun.inputs(SoftwareComponent.COMPANION, true).installing)
        val companionRun = sources("HA Companion")
        assertFalse(companionRun.inputs(SoftwareComponent.PANELD, true).installing)
        assertTrue(companionRun.inputs(SoftwareComponent.COMPANION, true).installing)
        assertEquals(true, field(state(paneldRun.inputs(SoftwareComponent.PANELD, true)), "in_progress"))
    }

    @Test fun anotherOwnerOfTheSharedLaneNeverPublishesInProgress() {
        for (owner in listOf("Backup", "APK", "Restore", "System WebView", "Uninstall", null)) {
            val snapshot = sources(owner)
            for (component in SoftwareComponent.entries) {
                assertEquals(
                    "$owner / $component",
                    false,
                    field(state(snapshot.inputs(component, true)), "in_progress"),
                )
            }
        }
    }

    @Test fun theFullCompanionWinsOverAMinimalOne() {
        val both = sources(null).copy(companionFullVersion = "2026.6.5-full")
        val inputs = both.inputs(SoftwareComponent.COMPANION, true)
        assertTrue(inputs.externallyManaged)
        assertEquals("2026.6.5-full", inputs.installedVersion)
        val absent = sources(null).copy(companionMinimalVersion = null)
        assertNull(absent.inputs(SoftwareComponent.COMPANION, true).installedVersion)
    }

    // --- discovery shape ---------------------------------------------------------------------------

    @Test fun shapesRoundTripAndRejectGarbage() {
        for (announced in listOf(true, false)) for (installable in listOf(true, false)) for (latest in listOf(true, false)) {
            val shape = SoftwareDiscoveryShape(announced, installable, latest)
            assertEquals(shape, SoftwareDiscoveryShape.decode(shape.encode()))
        }
        for (raw in listOf(null, "", "v1:11", "v1:1111", "v2:111", "v1:1x1")) assertNull(SoftwareDiscoveryShape.decode(raw))
    }

    @Test fun losingTheLatestVersionRecreatesTheEntity() {
        val before = SoftwareUpdateEntities.shape(companion(installed = "2026.5.1-minimal"))
        val after = SoftwareUpdateEntities.shape(companion(installed = "2026.5.1-full", full = true))
        assertTrue(before.hasLatest)
        assertFalse(after.hasLatest)
        assertEquals(SoftwareDiscoveryStep.CLEAR_THEN_ANNOUNCE, SoftwareUpdateEntities.transition(before, after, announcing = false))
        assertEquals(SoftwareDiscoveryStep.CLEAR_THEN_ANNOUNCE, SoftwareUpdateEntities.transition(before, after, announcing = true))
    }

    @Test fun eachDiscoveryStepPublishesExactlyItsPlanInOrder() {
        val playManaged = companion(installed = "2026.6.5-full", full = true)
        val config = SoftwareUpdateEntities.configTopic(panel, SoftwareComponent.COMPANION)
        val state = SoftwareUpdateEntities.stateTopic(panel, SoftwareComponent.COMPANION)
        fun plan(step: SoftwareDiscoveryStep, inputs: SoftwareUpdateInputs = playManaged) =
            SoftwareUpdateEntities.discoveryPlan(panel, inputs, step, availability, device)
        val readOnlyConfig = SoftwareUpdateEntities.discoveryJson(
            panel, SoftwareComponent.COMPANION, installable = false, availability, device,
        )
        // Recreation: the new retained state lands first, then the retained tombstone, then live config.
        assertEquals(
            listOf(
                SoftwarePublication(state, SoftwareUpdateEntities.stateJson(playManaged), retain = true),
                SoftwarePublication(config, "", retain = true),
                SoftwarePublication(config, readOnlyConfig, retain = false),
            ),
            plan(SoftwareDiscoveryStep.CLEAR_THEN_ANNOUNCE),
        )
        assertEquals(listOf(SoftwarePublication(config, readOnlyConfig, retain = false)), plan(SoftwareDiscoveryStep.ANNOUNCE))
        assertEquals(listOf(SoftwarePublication(config, "", retain = true)), plan(SoftwareDiscoveryStep.WITHDRAW))
        assertTrue(plan(SoftwareDiscoveryStep.NONE).isEmpty())
    }

    @Test fun gainingOrLosingTheInstallCommandIsAnOrdinaryReannouncement() {
        val installable = SoftwareUpdateEntities.shape(companion())
        val readOnly = SoftwareUpdateEntities.shape(companion(canInstall = false))
        assertEquals(SoftwareDiscoveryStep.ANNOUNCE, SoftwareUpdateEntities.transition(installable, readOnly, announcing = false))
        assertEquals(SoftwareDiscoveryStep.ANNOUNCE, SoftwareUpdateEntities.transition(readOnly, installable, announcing = false))
        assertEquals(SoftwareDiscoveryStep.NONE, SoftwareUpdateEntities.transition(installable, installable, announcing = false))
        assertEquals(SoftwareDiscoveryStep.ANNOUNCE, SoftwareUpdateEntities.transition(installable, installable, announcing = true))
        assertEquals(SoftwareDiscoveryStep.ANNOUNCE, SoftwareUpdateEntities.transition(null, installable, announcing = false))
    }

    @Test fun panelAssistantOwnershipWithdrawsOnlyTheHaPaneldEntity() {
        val owned = paneld(suppressed = true)
        val shape = SoftwareUpdateEntities.shape(owned)
        assertFalse(shape.announced)
        assertFalse(SoftwareUpdateEntities.installable(owned))
        val announced = SoftwareUpdateEntities.shape(paneld())
        assertEquals(SoftwareDiscoveryStep.WITHDRAW, SoftwareUpdateEntities.transition(announced, shape, announcing = false))
        assertEquals(SoftwareDiscoveryStep.WITHDRAW, SoftwareUpdateEntities.transition(null, shape, announcing = false))
        assertEquals(SoftwareDiscoveryStep.NONE, SoftwareUpdateEntities.transition(shape, shape, announcing = false))
        // Every announcement repeats the retained tombstone rather than trusting an earlier one.
        assertEquals(SoftwareDiscoveryStep.WITHDRAW, SoftwareUpdateEntities.transition(shape, shape, announcing = true))
        assertEquals(SoftwareDiscoveryStep.ANNOUNCE, SoftwareUpdateEntities.transition(shape, announced, announcing = false))
        // Suppression is presentation only: an install command still passes the same admission.
        assertEquals(SoftwareInstallAdmission.Admitted("v0.9.8"), SoftwareUpdateEntities.admit(owned))
        val companionSources = sources(null).copy(panelAssistantOwnsPaneldUpdate = true)
        assertTrue(companionSources.inputs(SoftwareComponent.PANELD, true).suppressed)
        assertFalse(companionSources.inputs(SoftwareComponent.COMPANION, true).suppressed)
    }
}
