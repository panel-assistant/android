package io.panelassistant.android.mqtt

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Exports the exact publication sequences the bridge sends for each update-entity scenario, so a real
 * Home Assistant can replay them in a throwaway discovery namespace. Every byte comes from
 * [SoftwareUpdateEntities]: the same discovery plan, transition and state functions the bridge calls.
 * The fixture lands in `build/software-update-replay/fixtures.json`; the replay harness lives in the
 * maintainer's private tooling.
 */
class SoftwareUpdateReplayFixtureTest {
    private val panel = "replay"
    private val availability = """"availability_topic":"ha-paneld/replay/availability","payload_available":"online","payload_not_available":"offline""""
    private val device = """"device":{"identifiers":["ha-paneld-replay"],"name":"Replay panel","manufacturer":"Sonoff",""" +
        """"model":"NSPanel Pro (ha-paneld)","sw_version":"0.9.7-rc4 (build 767)","hw_version":"Android 8.1.0"}"""

    private val paneldTarget = SoftwareTarget("0.9.8", "v0.9.8", "https://github.com/panel-assistant/android/releases/tag/v0.9.8")
    private val rc3 = SoftwareTarget("0.9.7-rc3", "v0.9.7-rc3", "https://github.com/panel-assistant/android/releases/tag/v0.9.7-rc3")
    private val companionTarget = SoftwareTarget(
        "2026.6.5", "2026.6.5", "https://github.com/home-assistant/android/releases/tag/2026.6.5",
    )
    private val cappedTarget = SoftwareTarget(
        "2026.5.4", "2026.5.4", "https://github.com/home-assistant/android/releases/tag/2026.5.4",
        capped = true, newestVersion = "2026.9.1",
    )

    private fun paneld(
        installed: String = "0.9.7",
        target: SoftwareTarget? = paneldTarget,
        installing: Boolean = false,
        suppressed: Boolean = false,
        channel: String = "stable",
    ) = SoftwareUpdateInputs(
        SoftwareComponent.PANELD, installed, target = target, channel = channel,
        canInstall = true, installing = installing, suppressed = suppressed,
    )

    private fun companion(
        installed: String? = "2026.5.1-minimal",
        full: Boolean = false,
        target: SoftwareTarget? = companionTarget,
        cap: String? = null,
        canInstall: Boolean = true,
    ) = SoftwareUpdateInputs(
        SoftwareComponent.COMPANION, installed, externallyManaged = full, target = target,
        channel = "stable", cap = cap, canInstall = canInstall, installing = false,
    )

    /** One reconcile pass exactly as the bridge runs it: discovery plan, then the state channel. */
    private fun pass(
        previous: SoftwareDiscoveryShape?,
        inputs: SoftwareUpdateInputs,
        announcing: Boolean,
        stepOverride: SoftwareDiscoveryStep? = null,
    ): Pair<SoftwareDiscoveryShape, JSONArray> {
        val current = SoftwareUpdateEntities.shape(inputs)
        val step = stepOverride ?: SoftwareUpdateEntities.transition(previous, current, announcing)
        val publications = SoftwareUpdateEntities.discoveryPlan(panel, inputs, step, availability, device) +
            SoftwarePublication(
                SoftwareUpdateEntities.stateTopic(panel, inputs.component),
                if (SoftwareUpdateEntities.withheld(inputs)) "" else SoftwareUpdateEntities.stateJson(inputs),
                retain = true,
            )
        return current to JSONArray(publications.map {
            JSONObject().put("topic", it.topic).put("payload", it.payload).put("retain", it.retain)
        })
    }

    private fun scenario(name: String, vararg passes: Pair<SoftwareUpdateInputs, Boolean>): JSONObject {
        var previous: SoftwareDiscoveryShape? = null
        val steps = JSONArray()
        for ((inputs, announcing) in passes) {
            val (shape, publications) = pass(previous, inputs, announcing)
            steps.put(publications)
            previous = shape
        }
        return describe(name, passes.first().first.component, steps)
    }

    private fun describe(name: String, component: SoftwareComponent, steps: JSONArray) = JSONObject()
        .put("name", name)
        .put("entity_id", "update.${SoftwareUpdateEntities.objectId(panel, component)}")
        .put("unique_id", SoftwareUpdateEntities.uniqueId(panel, component))
        .put("command_topic", SoftwareUpdateEntities.commandTopic(panel, component))
        .put("steps", steps)

    @Test fun exportReplayFixtures() {
        val scenarios = listOf(
            scenario("paneld_update_available", paneld() to true),
            scenario("paneld_newer_than_channel", paneld(installed = "0.9.7-rc4", target = rc3, channel = "prerelease") to true),
            scenario("paneld_unresolved", paneld(target = null) to true),
            scenario("paneld_installing", paneld(installing = true) to true),
            scenario("companion_up_to_date", companion(installed = "2026.6.5-minimal") to true),
            scenario("companion_outdated", companion() to true),
            // No Companion app: nothing is announced, whether or not the panel could install one.
            scenario("companion_absent", companion(installed = null) to true),
            scenario("companion_absent_no_route", companion(installed = null, canInstall = false) to true),
            scenario("companion_play_managed", companion(installed = "2026.6.5-full", full = true) to true),
            scenario(
                "companion_above_cap",
                companion(installed = "2026.9.1-minimal", target = cappedTarget, cap = "2026.5.4") to true,
            ),
            scenario(
                "companion_minimal_to_play_managed",
                companion() to true,
                companion(installed = "2026.6.5-full", full = true) to false,
            ),
            // The entity appears when a Companion is installed and goes again when it is removed.
            scenario(
                "companion_installed_after_absent",
                companion(installed = null) to true,
                companion(installed = "2026.5.1-minimal") to false,
            ),
            scenario(
                "companion_removed",
                companion(installed = "2026.5.1-minimal") to true,
                companion(installed = null) to false,
            ),
            scenario("paneld_withdrawn_for_panel_assistant", paneld() to true, paneld(suppressed = true) to false),
            scenario("paneld_returns_after_lease", paneld(suppressed = true) to true, paneld() to false),
            scenario(
                "paneld_install_progress",
                paneld() to true,
                paneld(installing = true) to false,
                paneld(installing = false) to false,
            ),
            scenario(
                "paneld_restart_after_update",
                paneld(installing = true) to true,
                paneld(installed = "0.9.8") to true,
            ),
        )
        // Negative control: the same Play-managed transition sent as an ordinary reannouncement. Home
        // Assistant keeps the stale latest version, which is why the bridge recreates the entity.
        val (firstShape, first) = pass(null, companion(), announcing = true)
        val (_, second) = pass(
            firstShape, companion(installed = "2026.6.5-full", full = true), announcing = false,
            stepOverride = SoftwareDiscoveryStep.ANNOUNCE,
        )
        val control = describe(
            "control_play_managed_without_recreation",
            SoftwareComponent.COMPANION,
            JSONArray().put(first).put(second),
        )

        val fixture = JSONObject()
            .put("panel", panel)
            .put("discovery_prefix", "homeassistant")
            .put("scenarios", JSONArray(scenarios + control))
        val out = File(System.getProperty("user.dir"), "build/software-update-replay/fixtures.json")
        out.parentFile.mkdirs()
        out.writeText(fixture.toString(2))

        assertEquals(scenarios.size + 1, JSONObject(out.readText()).getJSONArray("scenarios").length())
        val transition = scenarios.first { it.getString("name") == "companion_minimal_to_play_managed" }
            .getJSONArray("steps").getJSONArray(1)
        // Recreate: new retained state, retained tombstone, live config, then the converged state.
        assertEquals(4, transition.length())
        assertTrue(transition.getJSONObject(1).getString("payload").isEmpty())
        assertTrue(transition.getJSONObject(1).getBoolean("retain"))
    }
}
