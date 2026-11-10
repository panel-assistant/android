package io.panelassistant.android

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A fresh panel signed in to Home Assistant on its own screen, with setup's questions still open, is held
 * on "One setup question is waiting" before its first render. That screen must lead back to the panel's
 * own setup wizard. With only "Skip" on it (seen on a fresh network install, 2026-11-09), the wizard the
 * sign-in had covered was unreachable from the panel, setup never reported complete, and Home Assistant's
 * add-panel flow kept answering that setup was not finished.
 *
 * Driven from the activity's build ([EntityHoldRig] mirrors [DashboardActivity.buildAndLoad]) with the
 * screen's actions recorded rather than painted.
 */
class EntityFilterQuestionHoldTest {
    private var rig: EntityHoldRig? = null

    @After fun close() {
        rig?.close()
        SetupWizardResume.clear()
    }

    private fun signedInFreshPanel(): Config = Config(fakePreferences(initial = mapOf(
        "ha_url" to "http://ha.local:8123",
        "ha_token" to "token",
    )).instance)

    @Test fun `the setup question screen opens the panel's setup wizard`() {
        val config = signedInFreshPanel()
        val rig = EntityHoldRig(config).also { rig = it }
        rig.start()

        val actions = requireNotNull(rig.question) { "a signed-in fresh panel is held on the setup question" }
        assertEquals(
            "the wizard comes first, then the escape hatch",
            listOf(R.string.set_up, R.string.skip_load_dashboard),
            actions.map { it.first },
        )
        assertTrue("nothing renders behind the open question", rig.admitted.isEmpty())

        actions.first { it.first == R.string.set_up }.second()
        assertEquals("Set up opens the setup wizard on the panel", listOf("/setup"), rig.opened)
        assertFalse("and answers nothing on the owner's behalf", config.setupEntityFilterAnswered)
        assertFalse(config.setupHomeDashboardChosen)
    }

    /**
     * The sign-in, and each wizard answer that relaunches the renderer, bring the dashboard over the wizard.
     * The panel then stopped on this screen and needed a Set up tap, sometimes two (fresh installs,
     * 2026-11-09). It now goes straight back to the wizard on its own, with nothing answered for the owner.
     */
    @Test fun `after the on-panel sign-in the panel goes straight back to its setup wizard`() {
        val config = signedInFreshPanel()
        SetupWizardResume.arm() // what the sign-in's success does
        val rig = EntityHoldRig(config).also { rig = it }
        rig.start()

        assertEquals("the wizard opened with no tap", listOf("/setup"), rig.opened)
        assertTrue("nothing renders behind the open question", rig.admitted.isEmpty())
        assertFalse(config.setupEntityFilterAnswered)
        assertFalse(config.setupHomeDashboardChosen)
    }

    @Test fun `a relaunch over the wizard takes the panel back to it every time`() {
        val config = signedInFreshPanel()
        SetupWizardResume.arm()
        val first = EntityHoldRig(config)
        first.start()
        first.close()
        SetupWizardResume.clear() // the wizard came back on screen
        SetupWizardResume.arm()   // and "Use this dashboard" relaunched the renderer over it
        val rig = EntityHoldRig(config).also { rig = it }
        rig.start()

        assertEquals(listOf("/setup"), first.opened)
        assertEquals("covered again, back again", listOf("/setup"), rig.opened)
    }

    @Test fun `leaving the wizard lands on the question screen, not the wizard again`() {
        val config = signedInFreshPanel()
        SetupWizardResume.arm()
        SetupWizardResume.clear() // the person pressed Back in the wizard
        val rig = EntityHoldRig(config).also { rig = it }
        rig.start()

        assertEquals("no wizard on its own", emptyList<String>(), rig.opened)
        assertEquals(listOf(R.string.set_up, R.string.skip_load_dashboard), requireNotNull(rig.question).map { it.first })
    }

    @Test fun `skipping the question stops the wizard coming back`() {
        val config = signedInFreshPanel()
        val rig = EntityHoldRig(config).also { rig = it }
        rig.start()
        SetupWizardResume.arm()

        requireNotNull(rig.question).first { it.first == R.string.skip_load_dashboard }.second()

        assertFalse(SetupWizardResume.armed())
    }
}
