package io.github.maxlyth.hapaneld

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchScreenWiringContractTest {
    private fun source(name: String): String = listOf(
        File("src/main/kotlin/io/github/maxlyth/hapaneld/$name"),
        File("app/src/main/kotlin/io/github/maxlyth/hapaneld/$name"),
    ).first { it.isFile }.readText()

    private fun englishString(name: String): String {
        val xml = listOf(
            File("src/main/res/values/strings.xml"),
            File("app/src/main/res/values/strings.xml"),
        ).first { it.isFile }.readText()
        return Regex("""<string name="${Regex.escape(name)}"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1)
            ?: error("missing English string resource: $name")
    }

    @Test fun adminLauncherMarksItsIntroEntryExplicitly() {
        val admin = source("AdminLauncherActivity.kt")
        val config = source("ConfigActivity.kt")
        val main = source("MainActivity.kt")
        assertTrue(admin.contains("putExtra(MainActivity.EXTRA_EXPLICIT_ADMIN_ENTRY, true)"))
        assertTrue(main.contains("explicitAdminEntry = explicitAdminEntry"))
        assertTrue(main.contains("introExplicitAdminEntry = plan.explicitAdminEntry"))
        assertTrue(main.contains("if (!plan.explicitAdminEntry)"))
        assertTrue(main.contains("KioskAdminUi.setVisible(this, presentedIntro != null)"))
        assertTrue(main.contains("KioskAdminUi.setVisible(this, false)"))
        assertTrue(admin.indexOf("KioskAdminUi.setVisible(this, true)") < admin.indexOf("setContentView(buildUi())"))
        assertTrue(config.indexOf("KioskAdminUi.setVisible(this, true)") < config.indexOf("web = WebView(this)"))
        assertTrue(admin.contains("KioskAdminUi.setVisible(this, true)"))
        assertTrue(config.contains("KioskAdminUi.setVisible(this, true)"))
    }

    @Test fun serviceStartsBeforePolicyCanRedirectAndIntroPrecedesAcknowledgement() {
        val main = source("MainActivity.kt")
        val startup = main.substring(
            main.indexOf("override fun onCreate"),
            main.indexOf("private fun chooseDestination"),
        )
        assertTrue(startup.indexOf("PaneldService.start(this)") < startup.indexOf("chooseDestination()"))

        val choose = main.substring(
            main.indexOf("private fun chooseDestination"),
            main.indexOf("// After an app update"),
        )
        assertTrue(choose.indexOf("setContentView(intro)") < choose.indexOf("acknowledgeIntroAfterFirstDraw"))
        assertTrue(choose.contains("finish()"))

        val acknowledge = main.substring(
            main.indexOf("private fun acknowledgeIntroAfterFirstDraw"),
            main.indexOf("// After an app update"),
        )
        assertTrue(acknowledge.contains("IntroAcknowledgement(view, versionCode, generation).also { it.arm() }"))
        assertTrue(acknowledge.indexOf("view.post(this)") < acknowledge.indexOf("armAutoReturn(it)"))
        assertTrue(acknowledge.indexOf("override fun onDraw()") < acknowledge.indexOf("commitLaunchScreenVersionShown"))
        assertTrue(acknowledge.contains("mayAcknowledgePresentedIntro("))
        assertTrue(acknowledge.contains("generationMatches = generation == introGeneration"))
        assertTrue(acknowledge.contains("presentedViewMatches = presentedIntro === view"))
        assertTrue(acknowledge.contains("viewAttached = view.isAttachedToWindow"))
        assertTrue(main.contains("prepareAutoReturn(ignoreUpdateAge = freshDecision.rememberVersionShown)"))
        assertTrue(main.contains("preparedAutoReturn?.let"))
        assertTrue(main.contains("restoredIntroState?.let { saved ->"))
        assertTrue(main.contains("presentIntro(restored)"))

        val save = main.substring(
            main.indexOf("override fun onSaveInstanceState"),
            main.indexOf("override fun onDestroy()"),
        )
        assertTrue(save.contains("introVersionPending?.let"))
        assertTrue(save.contains("STATE_AUTO_RETURN_REMAINING"))
        assertTrue(save.contains("STATE_AUTO_RETURN_NEXT_REMAINING"))
        assertTrue(save.contains("coerceIn(0L, AUTO_RETURN_WINDOW_MS)"))

        val destroy = main.substring(
            main.indexOf("override fun onDestroy()"),
            main.indexOf("private fun buildUi()"),
        )
        assertTrue(destroy.contains("introAcknowledgement?.cancel()"))
        assertTrue(destroy.contains("presentedIntro = null"))
        assertTrue(destroy.contains("introGeneration++"))
    }

    @Test fun serviceStartsAndDestinationIsChosenBeforeAnyNotificationConsent() {
        val main = source("MainActivity.kt")
        assertTrue("Consent is asked in its own step, after startup", main.contains("private fun requestNotificationConsentIfDue"))
        val startup = main.substring(
            main.indexOf("override fun onCreate"),
            main.indexOf("private fun requestNotificationConsentIfDue"),
        )
        val serviceStart = startup.indexOf("PaneldService.start(this)")
        val destination = startup.indexOf("chooseDestination()")
        val consent = startup.indexOf("requestNotificationConsentIfDue()")
        assertTrue("The foreground service must start without a notification result", serviceStart >= 0)
        assertTrue("The destination is chosen after the service starts", destination > serviceStart)
        assertTrue("Consent is asked only after the destination is chosen", consent > destination)
        assertFalse("onCreate never launches the dialog itself", startup.contains("requestNotif.launch"))
        assertFalse("No provisional surface stands in for a destination", startup.contains("setContentView("))
        val result = main.substring(main.indexOf("private val requestNotif"), main.indexOf("private fun dp"))
        assertFalse("The answer never gates navigation", result.contains("chooseDestination"))
        assertFalse("Permission results must not restart the service", result.contains("PaneldService.start"))
    }

    @Test fun notificationConsentIsAskedOnlyThroughThePerVersionPolicyAndRecordedFirst() {
        val main = source("MainActivity.kt")
        assertEquals("One launch site for the dialog", 1, Regex("""requestNotif\.launch\(""").findAll(main).count())
        assertTrue("The dialog has one gated launch step", main.contains("private fun requestNotificationConsentIfDue"))
        val ask = main.substring(
            main.indexOf("private fun requestNotificationConsentIfDue"),
            main.indexOf("private fun chooseDestination"),
        )
        val policy = ask.indexOf("NotificationConsentPrompt.shouldAsk(")
        val commit = ask.indexOf("if (!config.commitNotificationConsentAsked(versionCode)) return")
        val launch = ask.indexOf("requestNotif.launch(Manifest.permission.POST_NOTIFICATIONS)")
        assertTrue("The dialog is gated by the policy", policy >= 0)
        assertTrue("The version is recorded before the dialog, and a failed record never asks", commit in (policy + 1) until launch)
        assertTrue(ask.contains("lastAskedVersionCode = config.lastNotificationConsentVersionCode"))
        assertTrue(ask.contains("currentVersionCode = versionCode"))
        assertTrue(ask.contains("val versionCode = BuildConfig.VERSION_CODE.toLong()"))
        assertTrue(ask.contains("standingScreenPresented = presentedIntro != null && !isFinishing"))
        assertTrue(ask.contains("autoReturnPending = preparedAutoReturn != null || autoReturn != null"))
    }

    @Test fun manualBuiltinRecoveryIsAnExplicitRetryRatherThanALatchBypass() {
        val main = source("MainActivity.kt")
        val open = main.substring(
            main.indexOf("private fun openDashboard"),
            main.indexOf("private fun Bundle.getLongOrNull"),
        )
        assertTrue(main.contains("getString(R.string.retry_dashboard)"))
        assertEquals("Retry dashboard", englishString("retry_dashboard"))
        assertTrue(open.contains("DashboardRecoveryState.BUILTIN_RENDERER"))
        assertTrue(open.indexOf("BuiltinDashboard.requestExplicitReload()") < open.indexOf("startActivity(it)"))
    }

    @Test fun unconfiguredFreshInstallHomeFallsBackToQrConfigureSurfaceNotAdminLauncher() {
        val dashboard = source("DashboardActivity.kt")
        val main = source("MainActivity.kt")
        val manifest = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        ).first { it.isFile }.readText()

        assertTrue("MainActivity owns the normal app launch entry", manifest.contains("android:name=\".MainActivity\""))
        assertTrue("MainActivity must keep the readable configure URL", main.contains("LocalAdminEndpoint.externalUrl"))
        // The QR's destination is now setup-aware: guided setup until this panel has completed setup
        // once, the Configure tab afterwards — never a generic admin drawer. The intended flow
        // is that the QR exists to start commissioning, and the wizard is its primary destination.
        assertTrue(
            "MainActivity must route the QR to guided setup first, Configure after completion",
            main.contains("if (config.setupEverCompleted) \"/configure\" else \"/setup\""),
        )
        assertTrue(main.contains("config.httpPort, adminPath()"))
        assertTrue("MainActivity must render the QR bitmap for the configure URL", main.contains("qrBitmap(url"))
        assertTrue("DashboardActivity must have a first-run fallback", dashboard.contains("fallbackToFirstRunSurface()"))
        assertTrue(
            "An unconfigured built-in renderer must route to MainActivity's QR/configure surface",
            dashboard.contains("Intent(this, MainActivity::class.java)"),
        )
        val unreadyBlock = dashboard.substring(
            dashboard.indexOf("if (!config.builtInRendererReady())"),
            dashboard.indexOf("activityConfig = config"),
        )
        assertTrue(unreadyBlock.contains("fallbackToFirstRunSurface()"))
        assertTrue(
            "Fresh install must not strand the panel on the Panel admin launcher grid",
            !unreadyBlock.contains("fallbackToLauncher()") &&
                !unreadyBlock.contains("AdminLauncherActivity::class.java"),
        )
    }

    @Test fun qrIntroNoBackgroundLanguage() {
        val main = source("MainActivity.kt")
        assertTrue(main.contains("getString(R.string.panel_generic_description)"))
        val description = englishString("panel_generic_description")
        assertTrue(description.contains("dashboard, app launcher and panel controls"))
        assertTrue(description.contains("speaker and sensors to Home Assistant over your local network"))
        assertTrue(description.contains("Configure the panel from "))
        assertTrue(description.contains("a browser using the address below"))
        // The version line is still on this screen, but it is now drawn by the shared brand header
        // rather than by this screen's own column, so the string is composed in two places. Both
        // halves are asserted, so dropping either still fails: the screen supplies the build number,
        // the shared header supplies the version and the separator.
        assertTrue(main.contains("surface.setBrandCaption(getString(R.string.build_number, BuildConfig.VERSION_CODE))"))
        assertEquals("build %1${'$'}d", englishString("build_number"))
        assertTrue(
            source("StatusSurface.kt").contains("v${'$'}{BuildConfig.VERSION_NAME} · ${'$'}suffix"),
        )
        assertTrue(!description.contains("running in the background"))
        assertTrue(!description.contains("runs in the background so Home Assistant can control"))
    }

    @Test fun qrIntroDescribesTheOnPanelDashboardAndLauncher() {
        val main = source("MainActivity.kt")
        assertTrue(main.contains("getString(R.string.panel_generic_description)"))
        val description = englishString("panel_generic_description")
        assertTrue(description.contains("dashboard, app launcher and panel controls"))
        assertTrue(description.contains("speaker and sensors to Home Assistant over your local network"))
        assertTrue(description.contains("Configure the panel from "))
        assertTrue(description.contains("a browser using the address below"))
        assertTrue(!description.contains("runs in the background so Home Assistant can control"))
    }
}
