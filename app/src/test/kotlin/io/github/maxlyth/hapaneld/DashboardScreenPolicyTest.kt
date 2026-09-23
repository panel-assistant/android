package io.github.maxlyth.hapaneld

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardScreenPolicyTest {
    private fun englishString(name: String): String {
        val xml = listOf(
            File("src/main/res/values/strings.xml"),
            File("app/src/main/res/values/strings.xml"),
        ).first { it.isFile }.readText()
        return Regex("""<string name="${Regex.escape(name)}"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1)
            ?: error("missing English string resource: $name")
    }

    @Test fun preventIdleDimOwnsTheBuiltInRendererWindowFlag() {
        assertTrue(shouldKeepBuiltInRendererScreenOn(preventIdleDim = true))
        assertFalse(shouldKeepBuiltInRendererScreenOn(preventIdleDim = false))
    }

    @Test fun activityAppliesAndClearsTheFlagAcrossLiveAndResumePaths() {
        val source = File("src/main/kotlin/io/github/maxlyth/hapaneld/DashboardActivity.kt").readText()
        val policy = source.substring(
            source.indexOf("private fun applyRendererScreenPolicy()"),
            source.indexOf("private fun applyOverscroll()"),
        )

        assertTrue(policy.contains("window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)"))
        assertTrue(policy.contains("window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)"))
        assertTrue(source.contains("activityConfig.registerChangeListener(rendererPowerListener)"))
        assertTrue(source.contains("activityConfig.unregisterChangeListener(rendererPowerListener)"))
        assertTrue(source.substring(source.indexOf("override fun onResume()"), source.indexOf("private fun applyOverscroll()"))
            .contains("applyRendererScreenPolicy()"))
    }

    @Test fun dashboardEntryBootstrapsTheServiceBeforeConfigGates() {
        val source = File("src/main/kotlin/io/github/maxlyth/hapaneld/DashboardActivity.kt").readText()
        val onCreate = source.substring(
            source.indexOf("override fun onCreate(savedInstanceState: Bundle?)"),
            source.indexOf("override fun dispatchTouchEvent"),
        )

        assertTrue(onCreate.contains("PaneldService.start(this)"))
        assertTrue(onCreate.indexOf("PaneldService.start(this)") < onCreate.indexOf("val config = Config(this)"))
        assertTrue(onCreate.contains("if (!config.builtInRendererReady())"))
    }

    @Test fun unauthenticatedBuiltinCompatibilityFailureUsesSignInRepairCopy() {
        val source = File("src/main/kotlin/io/github/maxlyth/hapaneld/DashboardActivity.kt").readText()
        val block = source.substring(
            source.indexOf("DashboardV2ProbeResult.AuthenticationFailed"),
            source.indexOf("is DashboardV2ProbeResult.Unavailable"),
        )

        assertTrue(block.contains("config.haToken.isBlank() && config.haRefreshToken.isBlank()"))
        assertTrue(block.contains("getString(R.string.ha_sign_in_needed)"))
        assertEquals("Home Assistant sign-in needed", englishString("ha_sign_in_needed"))
        // The refusal title must name the SIGN-IN, not the version check. A version-check title sends
        // diagnosis after a version problem that does not exist; the body has always said authentication.
        assertTrue(block.contains("getString(R.string.ha_sign_in_rejected)"))
        assertEquals("Home Assistant sign-in rejected", englishString("ha_sign_in_rejected"))
        assertFalse(block.contains("version check rejected"))
        // The two situations must reach DIFFERENT outcomes so a diagnostic surface can tell them
        // apart, even though both are person-repaired.
        assertTrue(block.contains("AdmissionOutcome.SIGN_IN_REQUIRED"))
        assertTrue(block.contains("AdmissionOutcome.CREDENTIAL_REFUSED"))
    }

    /**
     * A blocked screen must not promise a retry its outcome will never perform. This pairs the COPY
     * with the CLASS: both credential outcomes are `MANUAL_ONLY`, because an absent credential has
     * nothing to re-ask with and replaying a rejected one triggers Home Assistant login-attempt
     * banning. Between the ceiling-cadence proposal and the review correction that kept the outcome
     * manual, the shipped screen said it would "keep checking" and then never did.
     */
    @Test fun aManualOnlyCredentialScreenNeverPromisesToKeepChecking() {
        val source = File("src/main/kotlin/io/github/maxlyth/hapaneld/DashboardActivity.kt").readText()
        val block = source.substring(
            source.indexOf("DashboardV2ProbeResult.AuthenticationFailed"),
            source.indexOf("is DashboardV2ProbeResult.Unavailable"),
        )

        val credentialDetails = listOf(
            englishString("ha_sign_in_needed_detail"),
            englishString("ha_sign_in_rejected_detail"),
        )
        assertTrue(block.contains("getString(R.string.ha_sign_in_needed_detail)"))
        assertTrue(block.contains("getString(R.string.ha_sign_in_rejected_detail)"))
        listOf("keep checking", "keep trying", "will retry automatically", "retrying automatically").forEach { phrase ->
            credentialDetails.forEach { detail ->
                assertFalse("credential copy must not promise an automatic retry: $phrase", detail.contains(phrase))
            }
        }
        // Saying the dashboard RETURNS once the sign-in works is a different claim, and a true one:
        // PaneldServer relaunches the renderer on a changed Home Assistant credential or URL
        // (`relaunchForHa`, change-gated). What these outcomes never do is run a timer against a
        // credential nothing has changed, which is what the strings above would promise.
        assertTrue(
            "a credential screen must say the dashboard comes back once the sign-in works",
            credentialDetails.all { it.contains("on its own") },
        )
        // It must still name a route back, or the screen is a dead end, and it must name the
        // DESTINATION rather than a control. These screens now sometimes replace the Configure button
        // with a code that opens the same setting on a phone, so copy that explained the button was
        // describing something the person could no longer see.
        assertTrue(
            "a credential screen must name where the sign-in lives, not which button to press",
            credentialDetails.all { detail ->
                detail.contains("this panel’s settings, under Home Assistant") ||
                    detail.contains("this panel's settings, under Home\n") ||
                    detail.contains("sign-in lives in this panel's settings")
            },
        )
        assertFalse(
            "the copy must not name a button that a code may have replaced",
            credentialDetails.any { it.contains("Configure opens") },
        )
        assertTrue(block.contains("showBlockedAdmissionScreen("))
        assertTrue(source.contains("showV2CompatibilityScreen(title, detail, getString(R.string.retry), admissionRetryClass(outcome), outcome)"))
        // The pairing this test exists to protect: neither credential outcome runs a timer.
        assertEquals(AdmissionRetryClass.MANUAL_ONLY, admissionRetryClass(AdmissionOutcome.CREDENTIAL_REFUSED))
        assertEquals(AdmissionRetryClass.MANUAL_ONLY, admissionRetryClass(AdmissionOutcome.SIGN_IN_REQUIRED))
    }

    // --- admission auto-retry contracts (an admission screen must never be terminal) ---

    private fun dashboardSource() = File("src/main/kotlin/io/github/maxlyth/hapaneld/DashboardActivity.kt").readText()

    /** A window from each blocked-screen call large enough to include its outcome. */
    private fun callWindows(source: String, titleResource: String): List<String> =
        Regex(
            "showBlockedAdmissionScreen\\(\\s*getString\\(R\\.string\\." +
                Regex.escape(titleResource) + "\\)",
        ).findAll(source).map {
            source.substring(it.range.first, minOf(source.length, it.range.first + 700))
        }.toList()

    @Test fun everyBlockedAdmissionScreenNamesWhatThePanelLearned() {
        val source = dashboardSource()

        // Each blocked screen names its evidence; admissionRetryClass (not the call site) decides the
        // policy, so a screen cannot quietly pick its own recovery behavior.
        fun outcomeOf(titleResource: String) = callWindows(source, titleResource).map { w ->
            Regex("""AdmissionOutcome\.([A-Z_]+)""").find(w)?.groupValues?.get(1)
        }
        assertEquals(listOf("TRANSPORT_FAILED"), outcomeOf("cannot_reach_ha"))
        assertEquals(listOf("DASHBOARD_LIST_UNREADABLE"), outcomeOf("dashboard_list_unavailable"))
        assertEquals(listOf("SIGN_IN_PAGE_UNREACHABLE"), outcomeOf("ha_sign_in_page_failed"))
        assertEquals(listOf("BRIDGE_HANDSHAKE_MISSED"), outcomeOf("home_assistant_unresponsive"))
        assertEquals(listOf("VERSION_UNVERIFIABLE"), outcomeOf("ha_version_unverifiable"))
        assertEquals(listOf("UNSUPPORTED_HA"), outcomeOf("ha_upgrade_required"))
        assertEquals(
            listOf("NO_LEGAL_DASHBOARD", "DASHBOARD_LIST_UNREADABLE", "NO_LEGAL_DASHBOARD"),
            outcomeOf("no_dashboard_title"),
        )
        // One permanent-incapability screen. The three attachment failures are separate, retryable
        // evidence, and they split by what the person actually lost: one never opened Home Assistant,
        // the other two dropped it part-way through a page change.
        assertEquals(listOf("BRIDGE_UNAVAILABLE"), outcomeOf("web_viewer_too_old_title"))
        assertEquals(listOf("BRIDGE_ATTACH_FAILED"), outcomeOf("home_assistant_could_not_open"))
        assertEquals(List(2) { "BRIDGE_ATTACH_FAILED" }, outcomeOf("home_assistant_stopped_loading"))
        // The credential branch chooses its title inside one call, so read the branch itself.
        val authBranch = source.substring(
            source.indexOf("DashboardV2ProbeResult.AuthenticationFailed ->"),
            source.indexOf("is DashboardV2ProbeResult.Unavailable ->"),
        )
        assertTrue(authBranch.contains("AdmissionOutcome.CREDENTIAL_REFUSED"))
    }

    @Test fun onlyProgressScreensMayOmitAnOutcome() {
        val source = dashboardSource()
        // The raw painter is private and reached through exactly two helpers: blocked screens must
        // pass an outcome (no default), progress screens use the helper that never arms.
        assertEquals(2, Regex(Regex.escape("showV2CompatibilityScreen(title, detail,")).findAll(source).count())
        assertFalse("an outcome must never be defaulted", source.contains("outcome: AdmissionOutcome ="))
        assertFalse("call sites must not choose the retry class themselves", source.contains("autoRetry = AdmissionRetryClass."))
        assertEquals(2, Regex(Regex.escape("showAdmissionProgressScreen(")).findAll(source).count() - 1)
        assertTrue(source.contains("showV2CompatibilityScreen(title, detail, getString(R.string.retry), admissionRetryClass(outcome), outcome)"))
        // The verdict itself reaches the painter, not just its retry class. Two screens now offer
        // something the class cannot express — a repair the panel can perform, and a code that
        // opens the exact setting on a phone — and both are decided from the outcome. Passing only
        // the class would leave the painter guessing from the copy it was handed.
        assertTrue(
            "a progress screen has no verdict and must say so rather than borrow one",
            source.contains("showV2CompatibilityScreen(title, detail, null, AdmissionRetryClass.MANUAL_ONLY, null)"),
        )
    }

    @Test fun theActivityDelegatesCountdownVisibilityToTheTestedOwner() {
        // Behavior lives in AdmissionCountdownOwner and is proven there by executable lifecycle tests;
        // these assertions only pin that the activity delegates rather than re-deciding.
        val source = dashboardSource()
        assertTrue(source.contains("private val admissionCountdown = AdmissionCountdownOwner { SystemClock.uptimeMillis() }"))

        // Top-visibility, not resumed-ness, owns the repaint: Android 10+ can drop top-resumed status
        // without ever calling onPause.
        val topResumed = source.substring(
            source.indexOf("override fun onTopResumedActivityChanged"),
            source.indexOf("override fun onTopResumedActivityChanged") + 400,
        )
        assertTrue(topResumed.contains("onAdmissionVisibilityChanged(isTopResumedActivity)"))
        // Exactly one tier owns visibility: resume below API 29, top-resumed from 29 up. The rule itself
        // is proven executably by resumeOwnsAdmissionVisibility's tests.
        assertTrue(topResumed.contains("if (!resumeOwnsAdmissionVisibility("))
        val resume = source.substring(
            source.indexOf("override fun onResume()"),
            source.indexOf("override fun onPause()"),
        )
        assertTrue(resume.contains("if (resumeOwnsAdmissionVisibility(android.os.Build.VERSION.SDK_INT)) onAdmissionVisibilityChanged(true)"))
        val pause = source.substring(
            source.indexOf("override fun onPause()"),
            source.indexOf("override fun onTopResumedActivityChanged"),
        )
        assertTrue(pause.contains("onAdmissionVisibilityChanged(false)"))
        assertFalse("pausing must not disarm the retry", pause.contains("main.removeCallbacks(admissionRetry)"))
        assertFalse("pausing must not disarm the countdown owner", pause.contains("admissionCountdown.disarm()"))

        // A detached view counts as not visible, so a replaced screen cannot keep a stale row alive.
        val apply = source.substring(
            source.indexOf("private fun applyAdmissionPaint"),
            source.indexOf("private fun onAdmissionCountdownTick"),
        )
        assertTrue(apply.contains("it.isAttachedToWindow"))
        assertTrue(apply.contains("main.removeCallbacks(admissionCountdownTick)"))
    }

    @Test fun everyContentReplacingFunctionDisarmsTheAdmissionAutoRetry() {
        val source = dashboardSource()
        val bodies = source.split(Regex("\\n    (?:private |internal |public )?(?:override )?fun "))
        val painting = bodies.filter { it.contains("setContentView(") }
        assertEquals("a new screen must decide its disarm behavior", 3, painting.size)
        painting.forEach { body ->
            assertTrue(
                "a content-replacing function must disarm the admission auto-retry: ${body.lineSequence().first()}",
                body.contains("cancelAdmissionAutoRetry()"),
            )
        }
    }

    @Test fun automaticAndManualAdmissionRetryRunTheSameSequence() {
        val source = dashboardSource()
        val runnable = source.substring(
            source.indexOf("private val admissionRetry = Runnable"),
            source.indexOf("private val admissionCountdownTick"),
        )
        assertTrue(runnable.contains("destroyed || !BuiltinDashboard.ownsActivity(activityOwner) || authLatched"))
        assertTrue("the timer must not reset the back-off it is pacing", runnable.contains("retryAdmission(resetBackoff = false)"))
        // Scoped to the manual button's own construction rather than matched anywhere in the file: the
        // shared status frame builds it, so the click handler no longer sits beside a setOnClickListener
        // call here. What must hold is unchanged — this one button, and nothing else, resets the back-off.
        val manualButton = source.substring(
            source.indexOf("retryLabel?.let { label ->"),
            source.indexOf("add(surface.action(getString(R.string.configure))"),
        )
        assertTrue(
            "a present human does not wait out a timer",
            manualButton.contains("retryAdmission(resetBackoff = true)"),
        )
        val sequence = source.substring(
            source.indexOf("private fun retryAdmission(resetBackoff: Boolean)"),
            source.indexOf("private fun cancelAdmissionAutoRetry()"),
        )
        assertTrue(sequence.contains("compatibilityAttempts.invalidate()"))
        assertTrue(sequence.contains("v2Handshake.reset()"))
        assertTrue(sequence.contains("buildAndLoad(Config(this))"))
    }

    @Test fun admissionSuccessResetsTheBackoffAndDisarmsTheTimer() {
        // Without the reset, a recovered panel carries an inflated ladder into the NEXT incident, so
        // its first retry waits up to the ceiling instead of the base. Success means actually building
        // the renderer, which is exactly the buildCompatibleAndLoad prologue.
        val source = dashboardSource()
        val success = source.substring(
            source.indexOf("private fun buildCompatibleAndLoad"),
            source.indexOf("WebView.setWebContentsDebuggingEnabled"),
        )
        assertTrue(success.contains("cancelAdmissionAutoRetry()"))
        assertTrue(
            "recovery must not leave the back-off inflated for the next incident",
            success.contains("admissionRetryPolicy.reset()"),
        )
    }

    @Test fun theJitteredDelayIsComputedOncePerPaintAndProgressScreensNeverArm() {
        val source = dashboardSource()
        val painter = source.substring(
            source.indexOf("private fun showV2CompatibilityScreen("),
            source.indexOf("private fun retryAdmission(resetBackoff: Boolean)"),
        )
        assertEquals(1, Regex(Regex.escape("admissionRetryPolicy.nextDelayMs(")).findAll(painter).count())
        assertTrue(painter.contains("if (retryLabel == null) null else admissionRetryPolicy.nextDelayMs(autoRetry)"))

        // A provisional dashboard remains on screen, so this recovery route deliberately does not
        // repaint. It still owns one independently computed delay and arms the shared retry owner.
        val resolver = source.substring(
            source.indexOf("private fun resolveHomeDashboardAndLoad"),
            source.indexOf("private fun navigateAfterHomeDashboardCorrection"),
        )
        assertTrue(resolver.contains("admissionRetryPolicy.nextDelayMs("))
        assertTrue(resolver.contains("armAdmissionAutoRetry(it, getString(R.string.dashboard_list_unavailable))"))
    }

    @Test fun theCountdownAndTheRetryCallbackShareTheHandlerClock() {
        // Handler.postDelayed schedules on uptimeMillis, so the countdown owner is given that same
        // clock; a sleep-advancing clock would let the text reach 0s while the callback is pending.
        // The counting behavior itself is proven executably in AdmissionCountdownOwner's tests.
        val source = dashboardSource()
        assertTrue(source.contains("AdmissionCountdownOwner { SystemClock.uptimeMillis() }"))
        assertFalse(
            "the admission countdown must not read the sleep-advancing clock",
            source.substring(
                source.indexOf("private fun retryAdmission(resetBackoff: Boolean)"),
                source.indexOf("private fun resolveHomeDashboardAndLoad"),
            ).contains("elapsedRealtime"),
        )
        val arm = source.substring(
            source.indexOf("private fun armAdmissionAutoRetry"),
            source.indexOf("private fun applyAdmissionPaint"),
        )
        assertTrue("the armed delay and the painted delay are one value", arm.contains("main.postDelayed(admissionRetry, delayMs)"))
        assertTrue(arm.contains("admissionCountdown.arm(delayMs)"))
    }

    @Test fun theEntityFilterHoldNamesTheReasonTheAddressAndAWayOut() {
        // The hold protects the first impression; it must never become a trap. Someone standing at the panel
        // with no browser to hand needs both the address of the page and a way to proceed without it, and the
        // skip has to say what it costs — it is the unfiltered load this screen exists to avoid.
        val source = File("src/main/kotlin/io/github/maxlyth/hapaneld/DashboardActivity.kt").readText()
        val screen = source.substring(
            source.indexOf("private fun showWaitingForEntityFilterAnswer()"),
            source.indexOf("private fun showWaitingForEntityBootstrap()"),
        )
        assertTrue(screen.contains("getString(R.string.finish_questions_browser)"))
        assertTrue(englishString("finish_questions_browser").contains("to optimise the "))
        assertTrue("the page's own address must be shown", screen.contains("\"/setup\""))
        assertTrue(screen.contains("getString(R.string.skip_load_dashboard)"))
        assertEquals("Skip and load the dashboard now", englishString("skip_load_dashboard"))
        assertTrue(screen.contains("getString(R.string.skip_entity_filter_detail)"))
        assertTrue(
            "skipping must state its cost",
            englishString("skip_entity_filter_detail").contains("which is slower on this panel"),
        )
        assertTrue(screen.contains("skipEntityFilterQuestion()"))

        // Skipping records the SAME answer the browser's decline records, so the two surfaces cannot disagree
        // about whether the question was asked — and it must not quietly enable what the user passed over.
        val skip = source.substring(
            source.indexOf("private fun skipEntityFilterQuestion()"),
            source.indexOf("private fun showWaitingForEntityFilterAnswer()"),
        )
        assertTrue(skip.contains("config.setupEntityFilterAnswered = true"))
        assertFalse(skip.contains("dashboardEntityLearning"))
        assertTrue(skip.contains("buildAndLoad(config)"))
    }

    // --- the prose contract for every full-screen message this activity draws ---

    /**
     * The text between one call's parentheses, with nested calls and string literals handled, so a
     * window ends at the call's own closing paren rather than the first one inside it.
     */
    private fun argumentWindows(source: String, call: String): List<String> {
        val windows = mutableListOf<String>()
        var from = source.indexOf(call)
        while (from >= 0) {
            var i = from + call.length
            var depth = 1
            var inString = false
            while (i < source.length && depth > 0) {
                val c = source[i]
                when {
                    inString && c == '\\' -> i++
                    inString && c == '"' -> inString = false
                    !inString && c == '"' -> inString = true
                    !inString && c == '(' -> depth++
                    !inString && c == ')' -> depth--
                }
                i++
            }
            windows += source.substring(from + call.length, (i - 1).coerceAtMost(source.length))
            from = source.indexOf(call, i)
        }
        return windows
    }

    /**
     * Every literal this activity hands to the shared status frame, i.e. everything a person standing
     * at the panel can be shown. Titles and details reach the frame through the two admission helpers;
     * the entity-bootstrap phases call the frame directly; a native hold's reason is interpolated into
     * a detail; and two labels are written straight onto a view.
     */
    private fun renderedStrings(source: String): List<String> {
        val lit = "\"(?:\\\\.|[^\"\\\\\\n])*\""
        val literal = Regex(lit)
        // A message is what the panel DISPLAYS, so `+`-joined fragments are rejoined first. Checking
        // fragments would have passed a screen whose repair instruction was split across the line
        // break that made it fit under 120 columns.
        val message = Regex("$lit(?:\\s*\\+\\s*$lit)*")
        val windows = listOf(
            "showBlockedAdmissionScreen(",
            "showAdmissionProgressScreen(",
            "surface.heading(",
            "surface.detail(",
            "surface.action(",
            "surface.caption(",
            "EntityFilterNativeHold(",
        ).flatMap { argumentWindows(source, it) }
        val assigned = Regex("\\.text = ($lit(?:\\s*\\+\\s*$lit)*)")
            .findAll(source).map { it.groupValues[1] }
        val resource = Regex("""getString\(R\.string\.([A-Za-z0-9_]+)""")
        val assignedResources = Regex("""\.text\s*=\s*getString\(R\.string\.([A-Za-z0-9_]+)""")
            .findAll(source).map { englishString(it.groupValues[1]) }
        val literals = (windows.asSequence() + assigned)
            .flatMap { region ->
                message.findAll(region).map { m ->
                    literal.findAll(m.value).joinToString("") { it.value.drop(1).dropLast(1) }
                }
            }
        val resources = windows.asSequence().flatMap { region ->
            resource.findAll(region).map { englishString(it.groupValues[1]) }
        }
        return (literals + resources + assignedResources)
            .filter { it.isNotBlank() }
            .toList()
    }

    /**
     * Fixing one screen was never the fix.
     *
     * Two closed renderer-admission lanes each wrote their screens in the vocabulary of the mechanism
     * they had just built, and nothing in the project ever read the result from outside: the panel
     * told whoever walked up to it about a "secure V2 native bridge", a "V2 native-host handshake",
     * "WebMessageListener support", a "V2-only renderer" and "legal dashboards". This test is the
     * reader those lanes did not have. It reads what the frame is actually given, not what any one
     * screen function says, so a new screen inherits the contract instead of rediscovering it.
     */
    @Test fun noFullScreenMessageNamesTheMachineryBehindIt() {
        val rendered = renderedStrings(dashboardSource())
        assertTrue("fixture sanity: the screens must have been found at all", rendered.size > 25)

        // Internal component and protocol names. Each of these was on a panel; none of them tells
        // somebody standing in front of it what they lost or what to do about it.
        // "subscription" is deliberately NOT here. It names the wait on the entity-bootstrap screen,
        // and that is informative: the defect this test guards is a word the reader can do nothing
        // with, not every technical noun. A list that rejects an informative name would be a worse
        // contract than no list.
        listOf(
            "bridge", "handshake", "native-host", "WebMessageListener", "interceptor",
            "renderer", "legal dashboard", "entity-discovery", "unfiltered",
        ).forEach { word ->
            rendered.forEach { text ->
                assertFalse(
                    "a full-screen message names \"$word\", which is machinery: $text",
                    text.contains(word, ignoreCase = true),
                )
            }
        }
    }

    /**
     * A protocol version is never actionable: nobody can install one. The Home Assistant version is
     * the deliberate exception, because upgrading Home Assistant is exactly the repair the screen that
     * carries it is asking for.
     */
    /**
     * A message must carry its own subject.
     *
     * The heading and the detail are two separate views with a gap between them, and a detail that
     * opens with a pronoun is borrowing a noun the reader has to go back for: "The panel cannot reach
     * Home Assistant" over "It could not read the Home Assistant version" makes "it" mean the panel
     * while the nearer noun in its own sentence is Home Assistant. Naming the subject costs one word.
     */
    @Test fun noMessageOpensWithAPronounItHasNotIntroduced() {
        // Personal pronouns only. A demonstrative carrying its own noun ("This account has no
        // dashboard to open") is self-contained; a bare "It" or "They" is not.
        val borrowed = Regex("""^(It|Its|They|Their|He|She)\b""")
        renderedStrings(dashboardSource()).forEach { text ->
            assertFalse(
                "a message opens with a pronoun whose subject is only in the heading: $text",
                borrowed.containsMatchIn(text.trimStart()),
            )
        }
    }

    @Test fun noFullScreenMessageShowsAProtocolVersion() {
        val protocolVersion = Regex("""\bV\d""", RegexOption.IGNORE_CASE)
        renderedStrings(dashboardSource()).forEach { text ->
            assertFalse(
                "a full-screen message shows a protocol version: $text",
                protocolVersion.containsMatchIn(text),
            )
        }
    }

    /**
     * Android System WebView is the one component somebody has to go and update, so it is named in
     * full where that is the repair, and never as a bare component name they would have to already
     * know. What the screen must NOT do is send them to a particular shop: a large part of this
     * project's fleet runs vendor Android with no Google Play at all, and a panel that tells its owner
     * to open the Play Store has given them an instruction their device cannot follow. Name the thing,
     * say it needs updating, and leave how to the person who knows their own panel.
     */
    @Test fun theWebViewIsNamedInFullAndNeverSendsAnybodyToAParticularShop() {
        val rendered = renderedStrings(dashboardSource())
        val naming = rendered.filter { it.contains("WebView") }
        assertTrue("fixture sanity: some screen must still name it", naming.isNotEmpty())
        naming.forEach { text ->
            assertTrue(
                "naming Android System WebView without its full name: $text",
                text.contains("Android System WebView"),
            )
        }
        listOf("Play Store", "Google Play", "app store", "App Store", "Play store").forEach { shop ->
            rendered.forEach { text ->
                assertFalse(
                    "a full-screen message sends somebody to \"$shop\", which many panels do not have: $text",
                    text.contains(shop),
                )
            }
        }
        // And it is explained in ordinary language on at least one screen that sends someone after it.
        assertTrue(
            "no screen explains the thing it is sending somebody to update",
            naming.any { it.contains("web viewer") },
        )
    }
}
