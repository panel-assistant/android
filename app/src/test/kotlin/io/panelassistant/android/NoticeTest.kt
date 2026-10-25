package io.panelassistant.android

import io.panelassistant.android.control.BuiltinDashboard
import io.panelassistant.android.dashboard.EntityBootstrapProblem
import io.panelassistant.android.http.SetupJourney
import io.panelassistant.android.sensors.HaNetworkPath
import io.panelassistant.android.sensors.HaNetworkPathSeverity
import io.panelassistant.android.sensors.PathProbeCause
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What each native screen puts in the Notice's slots, decided by the pure functions the screens call, and
 * the card's colours. There is no view instrumentation in this gate, so each phase is asserted at the seam
 * the activity uses to draw it.
 */
class NoticeTest {

    private fun luminance(colour: Int): Double {
        fun channel(value: Int): Double {
            val c = value / 255.0
            return if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel((colour shr 16) and 0xFF) + 0.7152 * channel((colour shr 8) and 0xFF) +
            0.0722 * channel(colour and 0xFF)
    }

    private fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** [top] at [alpha] (0-255) over the opaque [under], as drawn. */
    private fun blend(top: Int, under: Int, alpha: Int): Int {
        fun ch(shift: Int) = (((top shr shift) and 0xFF) * alpha + ((under shr shift) and 0xFF) * (255 - alpha)) / 255
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun hex(value: String): Int = (0xFF000000L or value.removePrefix("#").toLong(16)).toInt()

    // --- the card ---------------------------------------------------------------------------------

    /** The card is inverted against its backdrop, so a dark panel gets the light card and a light one the dark. */
    @Test
    fun theCardIsInvertedAgainstWhatIsBehindIt() {
        listOf(true, false).forEach { backdropDark ->
            val backdrop = hex(statusPalette(backdropDark).background)
            val card = noticeColours(backdropDark).surface
            assertTrue("backdropDark=$backdropDark: card ${contrast(card, backdrop)}", contrast(card, backdrop) >= 10.0)
        }
    }

    /** Every text role on the card, and a filled action's label, clears WCAG AA against the card. */
    @Test
    fun everyTextRoleClearsAaOnTheCard() {
        listOf(true, false).forEach { backdropDark ->
            val c = noticeColours(backdropDark)
            listOf("text" to c.text, "secondary" to c.secondary, "muted" to c.muted).forEach { (role, colour) ->
                assertTrue("$backdropDark $role: ${contrast(colour, c.surface)}", contrast(colour, c.surface) >= 4.5)
            }
            assertTrue("$backdropDark primary label: ${contrast(c.surface, c.accent)}", contrast(c.surface, c.accent) >= 4.5)
            val caption = blend(c.muted, c.surface, Notice.CAPTION_ALPHA)
            assertTrue("$backdropDark build caption: ${contrast(caption, c.surface)}", contrast(caption, c.surface) >= 4.5)
        }
    }

    /** The edge is drawn in the backdrop's accent, so it stands out from the panel behind the card. */
    @Test
    fun theEdgeContrastsWithTheBackdrop() {
        listOf(true, false).forEach { backdropDark ->
            val backdrop = hex(statusPalette(backdropDark).background)
            val edge = noticeEdge(backdropDark)
            assertTrue("backdropDark=$backdropDark edge: ${contrast(edge, backdrop)}", contrast(edge, backdrop) >= 3.0)
        }
    }

    /** The light card carries the dark-ink wordmark and the dark card the light one. */
    @Test
    fun theWordmarkIsDrawnInTheCardsOwnInk() {
        assertTrue("the light card takes the day wordmark (dark name)", !noticeWordmarkNight(backdropDark = true))
        assertTrue("the dark card takes the night wordmark (light name)", noticeWordmarkNight(backdropDark = false))
    }

    /** The build shows only on a pre-release, the build a panel that chose pre-releases runs. */
    @Test
    fun theBuildShowsOnlyOnAPreRelease() {
        assertTrue(noticeShowsBuild("1.0.0-rc1"))
        assertTrue(noticeShowsBuild("1.1.0-beta2"))
        assertTrue(!noticeShowsBuild("1.0.0"))
    }

    /** Android's font size setting grows every Notice's text, as it did when these screens used sp. */
    @Test
    fun theFontSizeSettingGrowsNoticeText() {
        assertEquals(1.3f, noticeTextScale(scale = 1f, fontScale = 1.3f), 0.0001f)
        assertEquals(2.6f, noticeTextScale(scale = 2f, fontScale = 1.3f), 0.0001f)
        assertEquals("a non-positive setting counts as 1", 2f, noticeTextScale(scale = 2f, fontScale = 0f), 0.0001f)
    }

    /** A two-line chip's mark spans both lines; a one-line notice keeps the small mark. */
    @Test
    fun aTwoLineChipsMarkSpansBothLines() {
        assertTrue(compactMarkHeight(twoLines = true) >= 1.6f * compactMarkHeight(twoLines = false))
    }

    // --- dashboard reload ------------------------------------------------------------------------

    @Test
    fun theReloadNoticeWordsThePanelsOwnReasonsAsSentences() {
        assertEquals(R.string.reload_optimising_entities, reloadNoticeReason(BuiltinDashboard.LEARNING_RELOAD_REASON))
        assertEquals(R.string.reload_optimising_entities, reloadNoticeReason("updating the entity filter"))
        assertEquals(R.string.reload_applying_changes, reloadNoticeReason("applying your settings"))
        assertEquals(R.string.reload_applying_changes, reloadNoticeReason("clearing the dashboard’s stored data"))
        assertEquals(R.string.reload_applying_changes, reloadNoticeReason(""))
        assertNull("another caller's reason is shown as given", reloadNoticeReason("restoring a backup"))
    }

    // --- network chip ----------------------------------------------------------------------------

    private fun path(
        severity: HaNetworkPathSeverity,
        cause: PathProbeCause,
        probes: Int = 30,
        lost: Int = 4,
        p95Ms: Long = 180L,
    ) = HaNetworkPath.Snapshot(
        measuring = true, settling = false, socketLive = true, severity = severity, cause = cause,
        responsiveness = HaNetworkPathSeverity.HEALTHY, windowMs = 300_000L, probes = probes,
        roundTrips = probes - lost, networkFailures = lost, serverFailures = 0, authFailures = 0,
        p50Ms = 40L, p95Ms = p95Ms, maxMs = p95Ms, jitterMs = 5L, lossPercent = 0.0,
        consecutiveFailures = 0, lastRoundTripAgeMs = 1_000L,
    )

    @Test
    fun aLossyPathCountsTheLostPings() {
        val text = haNetworkChipText(path(HaNetworkPathSeverity.WARNING, PathProbeCause.LOSS))
        assertEquals(R.string.network_chip_dropping, text.title)
        assertEquals(R.string.network_chip_pings_lost, text.detail)
        assertEquals(listOf<Any>(4, 30, 5), text.args)
        assertEquals(R.string.network_chip_failing, haNetworkChipText(path(HaNetworkPathSeverity.SEVERE, PathProbeCause.LOSS)).title)
    }

    @Test
    fun aSlowPathIsHighLatencyWithItsPingTime() {
        val warning = haNetworkChipText(path(HaNetworkPathSeverity.WARNING, PathProbeCause.LATENCY, p95Ms = 1_240L), Locale.ROOT)
        assertEquals(R.string.network_chip_high_latency, warning.title)
        assertEquals(R.string.network_chip_ping_time, warning.detail)
        assertEquals(listOf<Any>("1.2 s"), warning.args)
        val severe = haNetworkChipText(path(HaNetworkPathSeverity.SEVERE, PathProbeCause.LATENCY, p95Ms = 850L), Locale.ROOT)
        assertEquals(R.string.network_chip_very_high_latency, severe.title)
        assertEquals(listOf<Any>("850 ms"), severe.args)
    }

    @Test
    fun anEmptyWindowSaysNoPingWasAnswered() {
        val text = haNetworkChipText(path(HaNetworkPathSeverity.SEVERE, PathProbeCause.LOSS, probes = 0, lost = 0))
        assertEquals(R.string.network_chip_no_ping_answered, text.detail)
        assertEquals(listOf<Any>(5), text.args)
    }

    // --- waiting for network ---------------------------------------------------------------------

    private fun network(link: Boolean = true, address: Boolean = true, default: Boolean = true) =
        StartupNetworkSnapshot(interfacePresent = true, linkUp = link, addressAssigned = address, defaultNetwork = default)

    @Test
    fun theNetworkStepFollowsWhatAndroidHasBroughtUp() {
        assertEquals(0, networkWaitNotice(network(link = false), 1_000, 20_000, true).step)
        assertEquals(0, networkWaitNotice(network(address = false), 1_000, 20_000, true).step)
        assertEquals(1, networkWaitNotice(network(default = false), 1_000, 20_000, true).step)
        assertEquals(2, networkWaitNotice(network(), 1_000, 20_000, true).step)
    }

    @Test
    fun theHeroCountsDownToTheUsualWaitThenCountsUp() {
        val early = networkWaitNotice(network(address = false), 7_000, 20_000, learned = true)
        assertTrue(early.countingDown)
        assertEquals(13_000L, early.heroMs)
        val late = networkWaitNotice(network(address = false), 25_000, 20_000, learned = true)
        assertTrue(!late.countingDown)
        assertEquals(25_000L, late.heroMs)
        val learning = networkWaitNotice(network(address = false), 7_000, 20_000, learned = false)
        assertTrue("an unlearned wait has no usual time to count down to", !learning.countingDown)
        assertEquals(7_000L, learning.heroMs)
    }

    @Test
    fun aStepAlreadyPassedIsNeverShownUnfilled() {
        val fills = networkWaitNotice(network(), 0, 20_000, true).fills
        assertEquals(listOf(1_000, 1_000, 0), fills)
    }

    // --- compatibility screens -------------------------------------------------------------------

    @Test
    fun theCompatibilityPillNamesWhatThePanelIsDoing() {
        assertEquals(R.string.notice_pill_starting_up, admissionNoticePill(progress = true, signIn = false, retries = false))
        assertEquals(R.string.notice_pill_sign_in, admissionNoticePill(progress = false, signIn = true, retries = false))
        assertEquals(R.string.notice_pill_retrying, admissionNoticePill(progress = false, signIn = false, retries = true))
        assertEquals(R.string.notice_pill_action_needed, admissionNoticePill(progress = false, signIn = false, retries = false))
        assertEquals("a sign-in says so even while it retries", R.string.notice_pill_sign_in, admissionNoticePill(progress = false, signIn = true, retries = true))
    }

    @Test
    fun onlyASignInOffersTheQr() {
        assertEquals(
            AdmissionQrSlots(R.string.notice_line_sign_in_needed, R.string.notice_qr_sign_in),
            admissionQrSlots(AdmissionOutcome.SIGN_IN_REQUIRED),
        )
        assertEquals(
            AdmissionQrSlots(R.string.notice_line_sign_in_stopped, R.string.notice_qr_sign_in_again),
            admissionQrSlots(AdmissionOutcome.CREDENTIAL_REFUSED),
        )
        AdmissionOutcome.entries.filter { it != AdmissionOutcome.SIGN_IN_REQUIRED && it != AdmissionOutcome.CREDENTIAL_REFUSED }
            .forEach { assertNull(it.name, admissionQrSlots(it)) }
        // The QR's slots and its address come from the same outcomes, so a code never lacks its prompt.
        AdmissionOutcome.entries.forEach { outcome ->
            assertEquals(outcome.name, configureQrPath(outcome) != null, admissionQrSlots(outcome) != null)
        }
    }

    // --- entity holds ----------------------------------------------------------------------------

    @Test
    fun theEntityHoldIsDecidedInTodaysOrder() {
        assertEquals(EntityHoldKind.FILTER_HOLD, entityHoldKind(3, filterHold = true, problem = EntityBootstrapProblem.AUTHENTICATION, canIgnoreBlockingIssues = true))
        assertEquals(EntityHoldKind.ATTENTION, entityHoldKind(3, filterHold = false, problem = EntityBootstrapProblem.AUTHENTICATION, canIgnoreBlockingIssues = true))
        assertEquals(EntityHoldKind.TOO_MANY, entityHoldKind(3, filterHold = false, problem = null, canIgnoreBlockingIssues = false))
        assertEquals(EntityHoldKind.SCAN_REFUSED, entityHoldKind(0, filterHold = false, problem = EntityBootstrapProblem.AUTHENTICATION, canIgnoreBlockingIssues = false))
        assertEquals(EntityHoldKind.SCAN_FAILED, entityHoldKind(0, filterHold = false, problem = EntityBootstrapProblem.SYNCHRONIZATION, canIgnoreBlockingIssues = false))
        assertEquals(EntityHoldKind.LEARNING, entityHoldKind(0, filterHold = false, problem = null, canIgnoreBlockingIssues = false))
    }

    /** A hold with no number to show has a hero word and keeps today's title as its line. */
    @Test
    fun everyHoldWithoutANumberHasAWordAndItsTitle() {
        listOf(EntityHoldKind.FILTER_HOLD, EntityHoldKind.SCAN_REFUSED, EntityHoldKind.SCAN_FAILED).forEach {
            assertTrue(it.name, it.heroWord != null && it.title != null)
        }
        listOf(EntityHoldKind.LEARNING, EntityHoldKind.ATTENTION, EntityHoldKind.TOO_MANY).forEach {
            assertTrue("${it.name} shows a count, not a word", it.heroWord == null)
        }
    }

    @Test
    fun theFirstScanMovesFromReadingToFiltering() {
        assertEquals(EntityLearningNotice(null, R.string.notice_line_reading_entities, 0), entityLearningNotice(null, null))
        assertEquals(EntityLearningNotice(1_284, R.string.notice_line_reading_entities, 0), entityLearningNotice(1, 1_284))
        assertEquals(EntityLearningNotice(1_284, R.string.notice_line_building_filter, 1), entityLearningNotice(2, 1_284))
    }

    @Test
    fun entityReadingUsesTheEstimateAndNeverCompletesTheBarBeforeTheScan() {
        val reading = entityLearningNotice(1, 1_234, 2_000)
        assertEquals(R.string.notice_line_reading_entities_total, reading.line)
        assertEquals(2_000, reading.expectedTotal)
        assertEquals(listOf(617, 0, 0), reading.fills)
        assertEquals(listOf(990, 0, 0), entityLearningNotice(1, 2_400, 2_000).fills)
        assertEquals(listOf(990, 0, 0), entityLearningNotice(1, Int.MAX_VALUE, 1).fills)
        val complete = entityLearningNotice(2, 2_400, 2_000)
        assertEquals(2_400, complete.count)
        assertNull(complete.expectedTotal)
        assertEquals(listOf(1_000, 0, 0), complete.fills)
        for (absent in listOf(null, 0, -1)) {
            assertEquals(EntityLearningNotice(1_234, R.string.notice_line_reading_entities, 0),
                entityLearningNotice(1, 1_234, absent))
        }
    }

    // --- standing screen -------------------------------------------------------------------------

    @Test
    fun theStandingPillSaysWhetherThePanelIsSetUp() {
        assertEquals(R.string.notice_pill_not_set_up, standingNoticePill(stagePresent = false, identity = false, needsUser = false, everCompleted = false))
        assertEquals(R.string.notice_pill_not_set_up, standingNoticePill(stagePresent = true, identity = true, needsUser = false, everCompleted = false))
        assertEquals(R.string.notice_pill_setup, standingNoticePill(stagePresent = true, identity = false, needsUser = false, everCompleted = false))
        assertNull(standingNoticePill(stagePresent = false, identity = false, needsUser = false, everCompleted = true))
    }

    /**
     * What MainActivity.applySetupStage shows for a real journey, read the way the activity reads the
     * endpoint: `next` as its lowercase wire name and the renderer step's detail.
     */
    private fun stageFor(journey: SetupJourney.Journey): SetupStageLine? = setupStageLine(
        journey.complete,
        journey.next?.name?.lowercase().orEmpty(),
        journey.step(SetupJourney.Stage.RENDERER).detail,
    )

    private fun pillFor(journey: SetupJourney.Journey, everCompleted: Boolean): Int? {
        val identity = !journey.complete && journey.next == SetupJourney.Stage.IDENTITY
        return standingNoticePill(stageFor(journey) != null, identity, journey.needsUser, everCompleted)
    }

    @Test
    fun aSignedInPanelWaitingForItsHomeDashboardSaysSetupContinuesInTheBrowser() {
        val journey = SetupJourney.evaluate(configuredInputs().copy(homeDashboardChosen = false))
        assertEquals(SetupJourney.Stage.HOME_DASHBOARD, journey.next)
        assertEquals(true, journey.needsUser)
        assertEquals(false, journey.complete)
        assertEquals(SetupStageLine(R.string.choose_home_dashboard, R.string.continue_setup_browser), stageFor(journey))
        assertEquals(R.string.notice_pill_setup, pillFor(journey, everCompleted = false))
    }

    /**
     * The standing screen of a panel that is not set up prints no address (only the QR code and the Set up
     * button), so its one line must not send anyone to one, in any language the panel ships.
     */
    @Test
    fun aPanelThatIsNotSetUpNamesOnlyWhatItsStandingScreenShows() {
        val journey = SetupJourney.evaluate(configuredInputs().copy(identityConfirmed = false))
        assertEquals(SetupJourney.Stage.IDENTITY, journey.next)
        val hint = checkNotNull(stageFor(journey)).hint
        val name = R.string::class.java.fields.single { it.getInt(null) == hint }.name
        val addressWords = mapOf(
            "values" to "address", "values-cs" to "adres", "values-de" to "adresse", "values-es" to "dirección",
            "values-fr" to "adresse", "values-it" to "indirizzo", "values-nl" to "adres", "values-pl" to "adres",
            "values-pt-rBR" to "endereço", "values-uk" to "адрес", "values-zh-rCN" to "地址",
        )
        // Source-text reason: plain JVM tests have no resource table, so the shipped strings are read as data.
        val shipped = java.io.File("src/main/res").listFiles { file -> file.resolve("strings.xml").isFile }.orEmpty()
        assertEquals(addressWords.keys, shipped.map { it.name }.toSet())
        for ((directory, word) in addressWords) {
            val text = Regex("""<string name="$name">(.*?)</string>""")
                .find(java.io.File("src/main/res/$directory/strings.xml").readText())?.groupValues?.get(1)
            assertNotNull("$directory has no $name", text)
            assertTrue("$directory $name keeps the Set up button's label: $text", text!!.contains("%1\$s"))
            assertTrue("$directory $name points at an address the screen does not show: $text", !text.lowercase().contains(word))
        }
    }

    @Test
    fun everyStageOfAnUnfinishedSetupHasAStandingLine() {
        for (stage in SetupJourney.Stage.entries) {
            assertNotNull(stage.name, setupStageLine(complete = false, next = stage.name.lowercase(), rendererDetail = ""))
        }
    }

    private fun configuredInputs(haCredentialed: Boolean = true) = SetupJourney.Inputs(
        identityConfirmed = true,
        panelId = "alpha",
        renderer = SetupJourney.RendererChoice.Builtin,
        haUrl = "http://ha.local:8123",
        haCredentialed = haCredentialed,
        haOAuthInFlight = false,
        entityFilterAnswered = true,
        homeDashboardChosen = true,
        // Process-scoped built-in proof not yet re-earned after an app restart or update.
        proof = SetupJourney.RenderProof(),
    )

    @Test
    fun aConfiguredPanelReEarningItsRenderProofShowsNoSetupPill() {
        val journey = SetupJourney.evaluate(configuredInputs())
        // The path: the only unsatisfied stage is render_proof, in flight, and nobody is needed.
        assertEquals(SetupJourney.Stage.RENDER_PROOF, journey.next)
        assertEquals(SetupJourney.Status.IN_FLIGHT, journey.step(SetupJourney.Stage.RENDER_PROOF).status)
        assertEquals("awaiting_frontend", journey.step(SetupJourney.Stage.RENDER_PROOF).detail)
        assertEquals(false, journey.complete)
        assertEquals(false, journey.needsUser)
        assertNull("a configured panel waiting for its dashboard is not in setup", pillFor(journey, everCompleted = true))
    }

    @Test
    fun aReArmedPanelThatLostItsCredentialStillShowsTheSetupPill() {
        val journey = SetupJourney.evaluate(configuredInputs(haCredentialed = false))
        assertEquals(SetupJourney.Stage.HA_CREDENTIALS, journey.next)
        assertEquals(SetupJourney.Status.BLOCKED, journey.step(SetupJourney.Stage.HA_CREDENTIALS).status)
        assertEquals(true, journey.needsUser)
        assertEquals(R.string.notice_pill_setup, pillFor(journey, everCompleted = true))
    }

    @Test
    fun aPanelThatNeverCompletedKeepsItsSetupPillWhileNobodyIsNeeded() {
        val loading = SetupJourney.evaluate(configuredInputs())
        assertEquals(false, loading.needsUser)
        assertEquals(R.string.notice_pill_setup, pillFor(loading, everCompleted = false))
        val signingIn = SetupJourney.evaluate(configuredInputs(haCredentialed = false).copy(haOAuthInFlight = true))
        assertEquals(SetupJourney.Stage.HA_CREDENTIALS, signingIn.next)
        assertEquals(false, signingIn.needsUser)
        assertEquals(R.string.notice_pill_setup, pillFor(signingIn, everCompleted = false))
        val unclaimed = SetupJourney.evaluate(configuredInputs().copy(identityConfirmed = false))
        assertEquals(SetupJourney.Stage.IDENTITY, unclaimed.next)
        assertEquals(R.string.notice_pill_not_set_up, pillFor(unclaimed, everCompleted = false))
    }
}
