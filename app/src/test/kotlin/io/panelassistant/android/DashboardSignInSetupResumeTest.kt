package io.panelassistant.android

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * A fresh panel that Panel Assistant handed over opens straight on the Home Assistant sign-in, inside the
 * built-in renderer. Signing in commits the credential, which relaunches the renderer: it finds the sign-in
 * page no longer needed and rebuilds, before the sign-in page's own success callback runs. On the bench Shelly
 * X2i (2026-11-11) the panel then sat on "Almost there" with Set up and Skip for over 40 s, because only that
 * callback asked for the setup wizard to come back.
 *
 * Driven through the renderer's own reconcile ([DashboardActivity.handleNewIntent], as a relaunch or a
 * settings change delivers it), with the sign-in page showing and the credential just committed.
 */
class DashboardSignInSetupResumeTest {
    private val rig = DashboardRendererRig(setupQuestion = true)

    @After fun close() {
        rig.close()
        SetupWizardResume.clear()
    }

    private fun signInPageShowing() {
        rig.set("signInShownForUrl", DashboardRendererRig.HA)
        rig.show(RecordingWebView("http://192.0.2.10:8888/api/v1/ha/oauth/panel-start"), committed = null)
        rig.phase = RendererPhase.SIGN_IN
    }

    @Test fun `signing in on the panel goes straight on to its setup wizard`() {
        signInPageShowing()

        rig.relaunch(null) // the credential's commit reaches the renderer

        assertEquals("the setup question is what the panel holds on", true, rig.question != null)
        assertEquals("and the wizard opens with no Set up tap", listOf(SETUP_WIZARD_PATH), rig.opened)
        assertFalse("nothing is answered on the owner's behalf", rig.config.setupEntityFilterAnswered)
        assertFalse(rig.config.setupHomeDashboardChosen)
    }

    @Test fun `a later reconcile while the question waits does not open the wizard again`() {
        signInPageShowing()
        rig.relaunch(null)
        SetupWizardResume.clear() // the wizard came on screen

        rig.relaunch(null) // a later settings change or return to the front, the sign-in long gone

        assertEquals("one wizard, opened once", listOf(SETUP_WIZARD_PATH), rig.opened)
    }
}
