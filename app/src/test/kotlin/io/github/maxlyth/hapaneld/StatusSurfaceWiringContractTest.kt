package io.github.maxlyth.hapaneld

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source contracts for the branded status frame.
 *
 * These state things about views and about how screens are wired, which this gate cannot execute —
 * there is no view instrumentation here. They follow the repo's existing idiom (see
 * `LaunchScreenWiringContractTest`) of asserting the invariant where it is written.
 *
 * The most important one is [everyFullScreenStatusViewGoesThroughTheSharedFrame]: the requirement is
 * not merely that today's screens carry the mark, but that a phase added later cannot quietly get an
 * anonymous screen of its own.
 */
class StatusSurfaceWiringContractTest {

    private fun source(path: String): String = listOf(
        File("src/main/kotlin/io/github/maxlyth/hapaneld/$path"),
        File("app/src/main/kotlin/io/github/maxlyth/hapaneld/$path"),
    ).first { it.isFile }.readText()

    private val dashboard by lazy { source("DashboardActivity.kt") }
    private val surface by lazy { source("StatusSurface.kt") }
    private val spec by lazy { source("StatusSurfaceSpec.kt") }
    private val standing by lazy { source("MainActivity.kt") }
    private val admin by lazy { source("AdminLauncherActivity.kt") }
    private val service by lazy { source("PaneldService.kt") }
    private val englishStrings by lazy {
        listOf(
            File("src/main/res/values/strings.xml"),
            File("app/src/main/res/values/strings.xml"),
        ).first { it.isFile }.readText()
    }

    /**
     * Every screen the dashboard puts up in place of Home Assistant is built by [StatusSurface].
     *
     * `setContentView` is the choke point: anything that reaches it is full-screen. Only three forms
     * are allowed — the shared frame, the live dashboard container, and the Home Assistant sign-in
     * page, which is deliberately unbranded because the content it hosts is genuinely Home
     * Assistant's own and claiming it would be the opposite of the attribution this frame exists for.
     */
    @Test
    fun everyFullScreenStatusViewGoesThroughTheSharedFrame() {
        val allowed = setOf(
            // The shared branded frame. Every status screen in the activity ends up here.
            "showStatusSurface",
            // Home Assistant's own sign-in page, deliberately unbranded — see the KDoc above.
            "showPhysicalHaSignIn",
            // The live dashboard itself, which is Home Assistant, not a ha-paneld status screen.
            "buildCompatibleAndLoad",
        )
        val functions = Regex("""\n {4}(?:private |internal |override )*fun ([A-Za-z0-9_]+)\(""")
            .findAll(dashboard)
            .map { it.range.first to it.groupValues[1] }
            .toList()
        val sites = Regex("""setContentView\(""").findAll(dashboard).map { call ->
            functions.last { it.first < call.range.first }.second
        }.toSet()
        assertTrue("no setContentView found — the contract is not reading the right file", sites.isNotEmpty())
        assertEquals("a full-screen view is bypassing the branded frame", allowed, sites)
    }

    /** The frame is created once per theme and reused, which is what keeps the mark stationary. */
    @Test
    fun theDashboardKeepsOneFrameRatherThanBuildingOnePerPhase() {
        assertTrue(
            "the frame must be cached on the activity",
            dashboard.contains("private var statusSurface: StatusSurface? = null"),
        )
        assertEquals(
            "the frame must be constructed in exactly one place",
            1,
            Regex("""StatusSurface\(this,""").findAll(dashboard).count(),
        )
        assertTrue(
            "reuse must be decided by the shared rule, not re-derived here",
            dashboard.contains("statusSurfaceReusable(it.spec, it.dark, spec, dark)"),
        )
    }

    /**
     * A phase change replaces the body and nothing above it.
     *
     * If `setBody` ever touched the header — or the header were added inside it — the mark would be
     * torn down and re-inflated on every progress tick, which is the flash the requirement forbids.
     */
    @Test
    fun changingThePhaseTouchesOnlyTheBody() {
        val body = surface.substringAfter("fun setBody(").substringBefore("\n    }")
        assertTrue("setBody must clear the body", body.contains("body.removeAllViews()"))
        // Three distinct spacings, not two. A row of actions is an action GROUP: tight between its
        // own members, wider where the group meets ordinary content on either side. Reading the row
        // as ordinary content is what left the controls closer to their explanation than to each
        // other, and it is only visible on a panel, so it is pinned here.
        assertTrue(
            "an action row must be recognised as a group, not by its view type",
            body.contains("view.tag == ACTION_ROW_TAG"),
        )
        assertTrue(
            "between two actions of one group: the tighter gap",
            body.contains("isActionGroup(view) && isActionGroup(rows[index - 1]) -> spec.actionGapDp"),
        )
        assertTrue(
            "entering or leaving a group: the wider gap, which is the air above and below it",
            body.contains("isActionGroup(view) || isActionGroup(rows[index - 1]) -> spec.actionGroupGapDp"),
        )
        listOf("header", "brandMark", "brandCaptionView").forEach { forbidden ->
            assertTrue("setBody must not touch $forbidden", !body.contains(forbidden))
        }
        assertEquals(
            "the header must be attached exactly once, outside setBody",
            1,
            Regex("""addView\(header\.view,""").findAll(surface).count(),
        )
    }

    /**
     * The status root is installed only when it is not already installed.
     *
     * Keeping one frame instance is not enough on its own. `setContentView` clears the content parent
     * before adding, so passing the same root again detaches and reattaches the header — the thing
     * this frame exists to hold still. The guard is what makes "kept between phases" true rather than
     * merely intended.
     */
    @Test
    fun theStatusRootIsInstalledOnlyWhenItIsNotAlreadyInstalled() {
        val show = dashboard.substringAfter("private fun showStatusSurface(").substringBefore("\n    }")
        assertTrue(
            "showStatusSurface must skip an install that is already in place",
            show.contains("if (statusSurfaceAlreadyInstalled(root, surface.root)) return"),
        )
        val guardIndex = show.indexOf("statusSurfaceAlreadyInstalled")
        val installIndex = show.indexOf("setContentView(")
        assertTrue("the guard must precede the install", guardIndex in 0 until installIndex)
    }

    /**
     * The standing screen keeps the mark out of the column its own status line changes.
     *
     * It used to be the column's first child, so every live stage update re-centred the column and
     * moved the mark with it — the same requirement the dashboard's screens meet, missed on the one
     * screen that already had a mark.
     */
    @Test
    fun theStandingScreenKeepsTheMarkOutOfItsChangingColumn() {
        assertTrue(
            "the standing screen must be built on the shared frame",
            standing.contains("val surface = statusSurface()") && standing.contains("surface.setBody(root)"),
        )
        assertTrue(
            "the standing screen must not build a mark of its own",
            !standing.contains("R.drawable.wordmark"),
        )
        val build = standing.substringAfter("private fun buildUi(").substringBefore("\n    private fun statusSurface")
        listOf("stageView", "hintView").forEach { changing ->
            assertTrue("$changing must live in the changing column", build.contains("$changing ="))
        }
        assertTrue(
            "the frame, not this screen, owns the background",
            !build.contains("setBackgroundColor"),
        )
    }

    /** The terminal fallback screen makes the same theme decision as everything else. */
    @Test
    fun theAdminLauncherMakesTheSharedThemeDecision() {
        assertTrue(
            "the admin screen must use the shared theme rule",
            admin.contains("StatusSurface.darkFor(this, Config(this))"),
        )
        assertTrue(
            "the admin screen must not read the system night mode directly",
            !admin.contains("UI_MODE_NIGHT_MASK"),
        )
    }

    /**
     * Every view the frame creates is coloured by the frame.
     *
     * The general form of the defect that reached review twice: a default AppCompat widget takes its
     * colours from the Activity theme, which follows the SYSTEM night setting, so anything the frame
     * did not colour explicitly disagreed with the panel's configured theme. Secondary buttons were
     * the instance; enumerating the factories closes the class.
     */
    @Test
    fun everyViewFactoryColoursItsViewFromThePalette() {
        // Only factories that create a view with ink of its own. A container that draws nothing —
        // `actionRow` holds already-coloured buttons — has no colour to get wrong, and `bandParams`
        // and `setBrandCaption` are not factories at all.
        val factories = Regex("""\n    fun (\w+)\([^)]*\)[^=]*: (?:TextView|Button|ProgressBar)""")
            .findAll(surface).map { it.groupValues[1] }.toList()
        assertTrue("no view factories found — the contract is not reading the right file", factories.size >= 4)
        val uncoloured = factories.filter { name ->
            val body = surface.substringAfter("\n    fun $name(").substringBefore("\n    fun ")
            // Colouring may be inline OR delegated to the shared state-aware helpers, which take the
            // palette themselves. What is forbidden is a view left to the platform theme.
            !body.contains("palette.") && !body.contains("statusAction")
        }
        assertEquals("views the frame creates but does not colour: $uncoloured", emptyList<String>(), uncoloured)
    }

    /** Both kinds of action are coloured, not just the emphasised one. */
    @Test
    fun secondaryActionsAreColouredTooRatherThanInheritingThePlatformTheme() {
        val action = surface.substringAfter("\n    fun action(").substringBefore("\n    fun ")
        // Both branches now delegate to the shared state-aware helpers, so neither sets a tint
        // inline. What must hold is that every branch gets its colours from the frame.
        assertEquals(
            "every branch of action() must take a state-aware background",
            2,
            Regex("""statusActionBackground\(""").findAll(action).count(),
        )
        assertEquals(
            "every branch of action() must take state-aware label colours",
            2,
            Regex("""statusActionTextColours\(""").findAll(action).count(),
        )
        // The secondary colours now live in the state-aware helper, so the action must delegate to it
        // rather than naming the palette inline — see secondaryActionsKeepTheirInteractionStates.
        assertTrue("the secondary branch must delegate to the shared state-aware surface", action.contains("statusActionBackground("))
        assertTrue("the secondary surface must come from the palette", surface.contains("palette.actionSurface"))
        assertTrue("the secondary boundary must come from the palette", surface.contains("palette.actionBorder"))
        // Both branches must colour. Primary and secondary legitimately differ in HOW — a tint versus
        // a fill with a boundary — so the property is that neither is left to the platform, not that
        // the function has no branch.
        val secondary = action.substringAfter("} else {").substringBefore("\n        }")
        assertTrue("the secondary branch must set its own background", secondary.contains("background ="))
        assertTrue("the secondary branch must set its own text colour", secondary.contains("setTextColor("))
    }

    /** The band measures its own content rather than being pinned to a dp box built from sp sizes. */
    @Test
    fun theBrandBandMeasuresItsOwnContentRatherThanAFixedBox() {
        val params = surface.substringAfter("fun bandParams()").substringBefore("\n    /**")
        assertTrue("the band must not be pinned to a computed dp height", !params.contains("headerHeightDp"))
        assertTrue("the band must measure its content", params.contains("WRAP_CONTENT"))
    }

    /** A screen already on the display is redrawn when the panel's shape or theme changes. */
    @Test
    fun anInstalledStatusScreenIsRedrawnOnAConfigurationChange() {
        val onChange = dashboard.substringAfter("override fun onConfigurationChanged(").substringBefore("\n    }")
        assertTrue(
            "a configuration change must converge the installed screen",
            onChange.contains("convergeStatusTheme()"),
        )
        assertTrue(
            "and only when the theme or the geometry actually moved, not on every handled event",
            onChange.contains("statusSurfaceReusable(current.spec, current.dark, spec, dark)"),
        )
        val converge = dashboard.substringAfter("private fun convergeStatusTheme(").substringBefore("\n    }")
        assertTrue(
            "the cached frame must be dropped before the redraw, or it reuses the old geometry",
            converge.indexOf("statusSurface = null") in 0 until converge.indexOf("redraw()"),
        )
        assertTrue(
            "every status screen must register how to redraw itself",
            Regex("""showStatusSurface\(surface\) \{""").findAll(dashboard).count() >= 4,
        )
    }

    /**
     * The redraw belongs to the surface that is installed, and dies with it.
     *
     * Left armed across a replacement, a later configuration change invoked it and put an obsolete
     * status screen over the live Home Assistant sign-in — which cannot be recovered by repeating the
     * transition that produced it, so the panel could be stranded mid-sign-in.
     */
    @Test
    fun theRedrawIsClearedByEveryPathThatInstallsSomethingElse() {
        val functions = Regex("""\n {4}(?:private |internal |override )*fun ([A-Za-z0-9_]+)\(""")
            .findAll(dashboard).map { it.range.first to it.groupValues[1] }.toList()
        val installers = Regex("""setContentView\(""").findAll(dashboard).map { call ->
            functions.last { it.first < call.range.first }.second
        }.toSet() - "showStatusSurface"
        assertTrue("no non-status installers found — the contract is not reading the right file", installers.isNotEmpty())
        val leaking = installers.filter { name ->
            !dashboard.substringAfter("\n    private fun $name(").substringBefore("\n    private fun ")
                .contains("statusRerender = null")
        }
        assertEquals("these install over the status frame without disarming its redraw: $leaking", emptyList<String>(), leaking)
    }

    /**
     * Paired actions stack rather than clipping off the side of a narrow panel.
     *
     * A vertical scroll cannot cure horizontal clipping, so the row has to measure itself against the
     * reading column and change direction. Added because the behaviour shipped with no assertion
     * owning it — its mutation killed nothing.
     */
    @Test
    fun pairedActionsStackWhenTheyWouldNotFit() {
        val row = surface.substringAfter("fun actionRow(").substringBefore("\n    companion object")
        assertTrue("the row must measure itself", row.contains("measure(unspecified, unspecified)"))
        assertTrue("it must compare against the reading column", row.contains("measuredWidth > columnPx"))
        assertTrue("and stack when it does not fit", row.contains("orientation = LinearLayout.VERTICAL"))
        assertTrue("stacked actions take the shared action width", row.contains("dp(spec.actionWidthDp)"))
    }

    /**
     * A rebuild reapplies what it was already showing rather than waiting for a change.
     *
     * The standing screen's poll returns early while the journey key is unchanged, so rebuilding its
     * views for a theme change left them blank: at the sign-in stage the stage line stayed hidden, the
     * generic hint stayed, and the sign-in button could be absent indefinitely.
     */
    @Test
    fun aRebuiltStandingScreenReappliesTheStateItAlreadyHas() {
        val onStart = standing.substringAfter("override fun onStart(").substringBefore("\n    override fun")
        assertTrue("a rebuild must forget what it last applied", onStart.contains("lastStageKey = null"))
        assertTrue("and reapply the journey it already has", onStart.contains("applySetupStage(journey)"))
        assertTrue("so the journey has to be retained", standing.contains("lastJourney = journey"))
    }

    /**
     * The theme-policy rebuild signature is recorded where the WebView is BUILT.
     *
     * `onNewIntent` rebuilds the WebView when the live signature differs from config, because a
     * document-start script cannot be replaced in an existing WebView. The kiosk and watchdog return
     * loops reach `onNewIntent` on every foregrounding, so if the field is never assigned from the
     * config that built the WebView, the comparison never converges and the panel tears down and
     * rebuilds its dashboard forever. The assignment must therefore sit next to the registration it
     * describes, not in the rebuild branch.
     */
    @Test
    fun theThemePolicySignatureIsRecordedWhereTheWebViewIsBuilt() {
        assertTrue(
            "the rebuild branch must compare the live signature against config",
            dashboard.contains("val nextThemeSignature = config.dashboardThemeEffective") &&
                dashboard.contains("if (nextThemeSignature != dashboardThemeSignature)"),
        )
        // Effective, not stored: Ambient must reach the WebView as the Dark, Light or Follow it
        // resolves to, and a stored-policy signature would never notice the room changing.
        assertTrue(
            "the WebView must be built from the effective policy",
            dashboard.contains("val themeSignature = config.dashboardThemeEffective"),
        )
        val build = dashboard.substringAfter("val themeSignature = config.dashboardThemeEffective")
            .substringBefore("addDocumentStartJavaScript")
        assertTrue(
            "the script must be chosen from the effective policy",
            build.contains("val forcedThemeDark = DashboardTheme.forcedDark(themeSignature)"),
        )
        assertTrue(
            "the signature must be assigned beside the script it describes",
            build.contains("dashboardThemeSignature = themeSignature"),
        )
        assertTrue(
            "and published so an ambient verdict can tell whether it still has to reach this page",
            build.contains("BuiltinDashboard.setAppliedThemeSignature(activityOwner, themeSignature)"),
        )
        assertFalse(
            "no renderer decision may read the stored policy, or Ambient would render as Follow",
            dashboard.contains("DashboardTheme.forcedDark(config.dashboardTheme)") ||
                dashboard.contains("DashboardTheme.forces(config.dashboardTheme)"),
        )
        assertTrue(
            "and the policy script must actually be registered from it",
            dashboard.contains("ExternalAuthProtocol.dashboardThemePolicyJs(forcedThemeDark)"),
        )
    }

    /**
     * One theme authority reaches every rendered surface, not only the native ones.
     *
     * The two WebView pages bake their palette and artwork into static HTML, so a persisted theme
     * change left them stale even where a system change redrew the native screens.
     */
    @Test
    fun aPersistedThemeChangeRedrawsEveryRenderedSurface() {
        assertTrue(
            "a persisted theme change must be observed",
            dashboard.contains("key == \"dashboard_theme_dark\"") &&
                dashboard.contains("key == \"dark_mode\"") &&
                // The policy is a third writer of the effective theme, so a change to it has to redraw
                // whatever is on the display now, exactly as the other two do.
                dashboard.contains("key == \"dashboard_theme\""),
        )
        val converge = dashboard.substringAfter("private fun convergeStatusTheme(").substringBefore("\n    }")
        assertTrue("it must drop the cached frame", converge.contains("statusSurface = null"))
        assertTrue("redraw an installed native surface", converge.contains("redraw()"))
        assertTrue(
            "and re-issue BOTH WebView-drawn pages",
            converge.contains("renderAuthLatchPage()") && converge.contains("reissueReconnecting()"),
        )
    }

    /**
     * The browser-drawn failure pages stay reachable when the text grows.
     *
     * `height:100vh` with vertically centred content puts the top of a tall column above the scroll
     * origin, so on a 480x480 panel at a large text size the heading and the mark became unreachable.
     */
    @Test
    fun theWebViewFailurePagesStayReachableAtLargeText() {
        val pinned = Regex("""(?<!min-)height:100vh""").findAll(dashboard).count()
        assertEquals("no page may pin itself to exactly one viewport", 0, pinned)
        assertEquals(
            "both pages must grow past the viewport rather than centring inside it",
            2,
            Regex("""min-height:100vh""").findAll(dashboard).count(),
        )
        assertEquals(
            "and align to the top so the content start stays scrollable",
            2,
            Regex("""align-items:flex-start""").findAll(dashboard).count(),
        )
    }

    /**
     * Actions keep pressed, focused and disabled feedback.
     *
     * Replacing the platform background with one flat drawable removed every state cue — and the
     * entity-filter recovery deliberately disables these buttons while it works, so a disabled control
     * that looks enabled is a screen lying about what it is doing.
     */
    @Test
    fun secondaryActionsKeepTheirInteractionStates() {
        assertTrue("the surface must be state-aware", surface.contains("StateListDrawable()"))
        val bg = surface.substringAfter("fun statusActionBackground(").substringBefore("\n}")
        listOf("state_enabled", "state_pressed", "state_focused").forEach { st ->
            assertTrue("a $st face is required", bg.contains(st))
        }
        assertTrue("the label must be state-aware too", surface.contains("fun statusActionTextColours("))
        val action = surface.substringAfter("\n    fun action(").substringBefore("\n    fun ")
        assertTrue("the action must use them", action.contains("statusActionBackground(") && action.contains("statusActionTextColours("))
    }

    /**
     * Two actions side by side are separated, and stacking clears that separation again.
     *
     * The gap had only ever been applied on the stacked branch, so a horizontal pair was added flush
     * and met at its borders. Both directions are asserted because the fix is a margin that is correct
     * in one orientation and wrong in the other.
     */
    @Test
    fun sideBySideActionsAreSeparatedAndStackingClearsThatSeparation() {
        val row = surface.substringAfter("fun actionRow(").substringBefore("\n    companion object")
        // The source order is: separate, measure, then the stacked branch, then the side-by-side one.
        val beforeMeasure = row.substringBefore("measure(unspecified, unspecified)")
        val stacked = row.substringAfter("orientation = LinearLayout.VERTICAL").substringBefore("} else {")
        val sideBySide = row.substringAfter("} else {")
        assertTrue(
            "a pair must be separated by the side gap",
            beforeMeasure.contains("marginStart = if (index == 0) 0 else dp(spec.actionSideGapDp)"),
        )
        // The stack decision has to see the gaps, or a pair that only just overflows stays side by
        // side and clips.
        assertTrue(
            "the gaps must be applied before the row measures itself",
            beforeMeasure.contains("addView(button)"),
        )
        assertTrue(
            "a side-by-side pair must take equal shares, or a short label renders as a stub",
            sideBySide.contains("weight = 1f"),
        )
        assertTrue(
            "stacking must clear the horizontal gap, or every stacked action is indented by it",
            stacked.contains("marginStart = 0"),
        )
        assertTrue(
            "stacking must clear the weight, or a stacked action ignores its own width",
            stacked.contains("weight = 0f"),
        )
        assertTrue(
            "the first stacked action must not carry a gap it has nothing to be separated from",
            stacked.contains("topMargin = if (index == 0) 0 else dp(spec.actionGapDp)"),
        )
    }

    /**
     * The control's own look is set here, not inherited from the platform button style.
     *
     * Theme inheritance is the mechanism behind every defect this frame has had: the artwork, the
     * cached palette, the secondary colours, and then the label itself, which arrived upper-cased and
     * widely tracked from a style nothing in this project chose.
     */
    @Test
    fun theActionSetsItsOwnLabelTreatmentRatherThanInheritingIt() {
        val action = surface.substringAfter("\n    fun action(").substringBefore("\n    fun ")
        assertTrue("the label must not be upper-cased by the platform style", action.contains("isAllCaps = false"))
        assertTrue("the label size must come from the spec", action.contains("textSize = spec.actionLabelSp"))
    }

    /**
     * Padding is applied after the background, because a background can replace it.
     *
     * `View.setBackground` adopts the new drawable's padding when it reports any, so padding set first
     * is silently discarded by a drawable that does. Ordering is the whole guarantee here, which is
     * why it is asserted as an ordering rather than as the presence of a call.
     */
    @Test
    fun theActionsPaddingIsAppliedAfterItsBackground() {
        val action = surface.substringAfter("\n    fun action(").substringBefore("\n    fun ")
        val lastBackground = action.lastIndexOf("background = statusActionBackground(")
        val padding = action.indexOf("setPadding(")
        assertTrue("the action must set its own padding", padding >= 0)
        assertTrue("every background must be installed before the padding", lastBackground >= 0)
        assertTrue("a background installed after the padding can discard it", lastBackground < padding)
    }

    /**
     * The standing screen and the status screens draw ONE control, not two that resemble each other.
     *
     * They diverged silently: the standing screen's button was accepted on hardware while the status
     * screens' actions lost their padding, kept an upper-cased label, and were added to their row
     * flush against each other. Nothing could see it, because each screen spelled its own numbers out.
     * Naming the shared constants is the fix, and this is what holds it.
     */
    @Test
    fun theStandingScreenAndTheStatusScreensDrawTheSameControl() {
        val builder = standing.substringAfter("private fun button(").substringBefore("\n    }")
        listOf(
            "STATUS_ACTION_LABEL_SP",
            "STATUS_ACTION_CORNER_DP",
            "STATUS_ACTION_PADDING_H_DP",
            "STATUS_ACTION_PADDING_V_DP",
        ).forEach { shared ->
            assertTrue(
                "the standing screen's button must take $shared from the shared control",
                builder.contains(shared),
            )
        }
        // Only the SHARED dimensions are forbidden as literals. The standing screen's own 260dp width
        // and its 8dp stack margins are that screen's layout, not the control, and re-spelling them
        // here would be inventing a shared value where none exists.
        val respelled = listOf(
            "dp($STATUS_ACTION_CORNER_DP)",
            "dp($STATUS_ACTION_PADDING_H_DP)",
            "dp($STATUS_ACTION_PADDING_V_DP)",
            "textSize = ${STATUS_ACTION_LABEL_SP.toInt()}f",
        ).filter { builder.contains(it) }
        assertEquals("the standing screen re-spells a shared value: $respelled", emptyList<String>(), respelled)
        assertTrue("the label must not be upper-cased", builder.contains("isAllCaps = false"))
    }

    /** One timer chain, owned by the screen that starts it. */
    @Test
    fun theNetworkWaitCancelsItsPreviousTimerBeforeStartingAnother() {
        val show = dashboard.substringAfter("private fun showWaitingForNetwork(").substringBefore("\n    private fun ")
        val cancel = show.indexOf("main.removeCallbacks(waitingTick)")
        val start = show.indexOf("waitingTick.run()")
        assertTrue("the wait must cancel its previous chain", cancel >= 0)
        assertTrue("the cancel must precede the restart, or a redraw doubles the timers", cancel < start)
    }

    /** The standing screen converges its installed colours when the theme actually changed. */
    @Test
    fun theStandingScreenReconcilesItsInstalledPaletteOnReturn() {
        val onStart = standing.substringAfter("override fun onStart(").substringBefore("\n    override fun")
        assertTrue("returning must reconcile a changed theme", onStart.contains("StatusSurface.darkFor(this, config)"))
        assertTrue("it must rebuild only when the theme moved", onStart.contains("if (statusSurface?.dark != dark)"))
    }

    /** The standing screen recomputes its colours instead of caching them past a theme change. */
    @Test
    fun theStandingScreenNeverCachesItsPalette() {
        assertTrue(
            "a cached palette outlives the theme it was built for",
            !standing.contains("by lazy { statusPalette"),
        )
        assertTrue("the palette must be recomputed on read", standing.contains("get() = statusPalette("))
    }

    /**
     * Every value the spec charges is a value the builder actually applies.
     *
     * This is the general form of a defect that reached review: the layout model charged 48dp for an
     * action while the button was rendered `WRAP_CONTENT`, so the modelled screen and the drawn one
     * had quietly diverged, and a test asserting the spec's own number could not see it. Enumerating
     * the fields closes the class rather than that one instance.
     */
    @Test
    fun everySpecValueIsConsumedByTheBuilder() {
        // `compact` is the tier flag the sizes are derived FROM, not a size the builder applies.
        val notASize = setOf("compact")
        val fields = Regex("""\n {4}val (\w+): """)
            .findAll(spec.substringAfter("internal data class StatusSurfaceSpec(").substringBefore("\n) {"))
            .map { it.groupValues[1] }
            .filterNot { it in notASize }
            .toList()
        assertTrue("no spec fields found — the contract is not reading the right block", fields.size > 10)
        val unused = fields.filterNot { surface.contains("spec.$it") }
        assertEquals("spec values the builder never applies: $unused", emptyList<String>(), unused)
    }

    /**
     * The builder holds no sizes of its own, so asserting [statusSurfaceSpec] asserts the layout.
     *
     * Without this the spec tests would be measuring a model that the drawn screen is free to ignore.
     */
    @Test
    fun theBuilderTakesEverySizeFromTheSpec() {
        val literalDp = Regex("""\bdp\(\s*\d""").findAll(surface).map { it.value }.toList()
        assertEquals("a hardcoded dp value in the builder: $literalDp", emptyList<String>(), literalDp)
        val literalTextSize = Regex("""textSize\s*=\s*\d""").findAll(surface).map { it.value }.toList()
        assertEquals("a hardcoded text size in the builder: $literalTextSize", emptyList<String>(), literalTextSize)
        assertTrue("the builder must read the spec", surface.contains("spec.brandHeightDp"))
        assertTrue("the builder must read the spec", surface.contains("spec.columnWidthDp"))
        // headerHeightDp is deliberately NOT read by the builder any more: it is the fit model's
        // expectation of the band, while the drawn band measures its own content so a large font
        // scale grows it instead of clipping the caption. See theBrandBandMeasuresItsOwnContent...
        assertTrue(
            "the band must not be pinned to the model's expected height",
            !surface.contains("dp(spec.headerHeightDp)"),
        )
    }

    /** The spec stays free of Android types, which is the only reason the unit gate can assert it. */
    @Test
    fun theSpecImportsNothing() {
        val imports = spec.lines().filter { it.startsWith("import ") }
        assertEquals("the spec must stay framework-free: $imports", emptyList<String>(), imports)
    }

    /**
     * The frame is a pure function of the panel, which is what makes it survive activity recreation.
     *
     * A panel is recreated for a configuration change, a theme change or a process restart, and each
     * time the frame is built from scratch. Because nothing here remembers anything between calls,
     * the same panel cannot come back with the mark in a different place — the recreation property
     * holds by construction rather than by a test having tried it once. State of any kind would end
     * that guarantee, so this refuses it.
     */
    @Test
    fun theSpecRemembersNothingBetweenCalls() {
        val state = Regex("""\bvar\b|mutableListOf|mutableMapOf|HashMap|ArrayList|by lazy""")
            .findAll(spec).map { it.value }.toList()
        assertEquals("the spec must stay stateless: $state", emptyList<String>(), state)
    }

    /**
     * Colour comes from one palette.
     *
     * Six near-identical palettes were previously declared across three activities, which is how the
     * same panel came to draw one screen light and the next dark.
     */
    @Test
    fun theDashboardDeclaresNoPaletteOfItsOwn() {
        val hexes = Regex("""Color\.parseColor\("#""").findAll(dashboard).map { it.value }.toList()
        assertEquals("a screen is still mixing its own colours: $hexes", emptyList<String>(), hexes)
    }

    /** The two interstitials that must live in the WebView carry the mark too. */
    @Test
    fun theWebViewInterstitialsCarryTheMark() {
        listOf("renderAuthLatchPage", "showReconnecting").forEach { name ->
            val fn = dashboard.substringAfter("private fun $name(").substringBefore("\n    }")
            assertTrue("$name must render the shared brand header", fn.contains("statusBrandHtmlHeader("))
            assertTrue("$name must use the shared palette", fn.contains("statusPalette("))
        }
    }

    /** The mark is one asset reached one way, so replacing the artwork cannot leave a screen behind. */
    @Test
    fun everySurfaceReachesTheMarkThroughTheSharedHelpers() {
        val direct = listOf(
            "DashboardActivity.kt",
            "AdminLauncherActivity.kt",
            // Added after review: this screen loaded the artwork itself, resolving it through the
            // system theme while choosing its colours from the configured one, so a panel whose two
            // disagreed drew the mark's ink onto a background of the same shade.
            "MainActivity.kt",
        ).filter { file -> source(file).contains("R.drawable.wordmark") }
        assertEquals("a screen is loading the artwork directly: $direct", emptyList<String>(), direct)
        assertEquals(
            "the artwork must be referenced in exactly one place",
            2, // statusBrandMark (views) and StatusBrandAsset (the data: URI for the WebView pages)
            Regex("""R\.drawable\.wordmark""").findAll(surface).count(),
        )
    }

    /**
     * The frame's installer disarms the admission auto-retry, because a replaced screen must not leave
     * a timer running against a discarded view tree. That makes redrawing a blocked screen — a panel
     * theme or configuration change — dangerous in the opposite direction: recomputing the delay would
     * advance the back-off ladder and push the deadline further out every time, so a panel being
     * rotated could postpone its own recovery indefinitely. The redraw must therefore carry the
     * pending remainder across. `AdmissionCountdownOwner.remainingMs` is proven executably in
     * `DashboardRecoveryTest`; what is asserted here is that the painter actually uses it.
     */
    @Test
    fun aRedrawnAdmissionScreenCarriesItsPendingRetryRatherThanRestartingIt() {
        val painter = dashboard.substringAfter("private fun paintV2CompatibilityScreen(").substringBefore("\n    }")
        assertTrue(
            "the installer must disarm, or a replaced screen keeps its timer",
            dashboard.substringAfter("private fun showStatusSurface(").substringBefore("\n    }")
                .contains("cancelAdmissionAutoRetry()"),
        )
        assertTrue(
            "a redraw must read the deadline INSTANT before the installer disarms it",
            painter.contains("val carriedDeadlineMs = if (rearm) null else admissionCountdown.deadlineAtMs"),
        )
        assertTrue(
            "a carried deadline must suppress the fresh ladder delay entirely",
            painter.contains("if (carriedDeadlineMs != null) null"),
        )
        assertTrue(
            "a carried deadline is restored as itself, not as a recomputed duration",
            painter.contains("if (carriedDeadlineMs != null) resumeAdmissionAutoRetry(carriedDeadlineMs, title)"),
        )
        // The delay must be derived at the moment of posting, from the same clock the Handler uses, or
        // the redraw's own duration lands on the deadline instead of coming off it.
        val resume = dashboard.substringAfter("private fun resumeAdmissionAutoRetry(").substringBefore("\n    }")
        assertTrue(
            "the resumed delay must be measured from now, not carried in",
            resume.contains("(deadlineMs - SystemClock.uptimeMillis()).coerceAtLeast(0L)"),
        )
        assertTrue(resume.contains("admissionCountdown.rearmAt(deadlineMs)"))
        assertTrue(
            "the rerender hook must ask for a redraw, not a fresh failure",
            painter.contains("rearm = false"),
        )
        assertTrue(
            "the entry point remains a genuine failure, which does advance the ladder",
            dashboard.contains(
                "= paintV2CompatibilityScreen(title, detail, retryLabel, autoRetry, outcome, rearm = true)",
            ),
        )
        // The redraw must reproduce the WHOLE screen, and the offer is now part of it. A rotation that
        // carried the deadline but dropped the verdict would repaint a panel that could repair itself as
        // one that could only wait — the same class of loss the carried deadline exists to prevent, in
        // the row above the countdown rather than in the countdown.
        assertTrue(
            "the redraw must carry the verdict, not just the pending retry",
            painter.contains(
                "paintV2CompatibilityScreen(title, detail, retryLabel, autoRetry, outcome, rearm = false)",
            ),
        )
    }

    /**
     * A repair that outlives the screen it was started from must not write to it.
     *
     * The install runs for minutes. In that time the panel can be rotated, its theme can change, or the
     * activity can be replaced outright — and the poll is a delayed callback holding two views from a
     * view tree that no longer exists. This is the activity's existing idiom for exactly that hazard, and
     * it is asserted here because there is no view instrumentation in this gate to execute it.
     */
    @Test
    fun aRepairThatOutlivesItsScreenStopsRatherThanWritingToIt() {
        val poll = dashboard.substringAfter("private fun pollWebViewRepair(").substringBefore("\n    }")
        assertTrue(
            "the poll must re-test the activity lease, not assume the screen it started on is still live",
            poll.contains("if (destroyed || !BuiltinDashboard.ownsActivity(activityOwner)) return@postDelayed"),
        )
        assertTrue(
            "a detached button belongs to a discarded tree and ends the poll",
            poll.contains("if (!button.isAttachedToWindow) return@postDelayed"),
        )
        assertTrue("the poll must re-arm itself rather than run on a fixed schedule", poll.contains("pollWebViewRepair(button, note)"))
        // MEASURED ON A PANEL, not reasoned about: across a real 150-second repair the installer's slot
        // read {"running":true,"message":"Working…"} on every single poll and never changed, because it
        // reports one terminal string and has no progress callback. A screen that adopted that would
        // trade its own sentence for a word within one tick. So the running branch must not touch the
        // note at all — the absence of an assignment there IS the contract.
        val running = poll.substringAfter("if (progress.running) {").substringBefore("}")
        assertTrue(
            "a running install must not overwrite the screen's own explanation",
            !running.contains("note?.text"),
        )
        // And an empty terminal message is the tell that this process's slot was cleared by the restart
        // a SUCCESSFUL repair causes — so it must not be reported as a failure.
        assertTrue(
            "a blank terminal message must not be narrated as a failure",
            dashboard.contains("progress.message.takeIf { it.isNotBlank() }") &&
                dashboard.contains("?: getString(R.string.update_stopped_unknown)") &&
                englishStrings.contains(
                    "<string name=\"update_stopped_unknown\">The update stopped without saying why, " +
                        "and nothing on this panel was changed.</string>",
                ),
        )
    }

    /**
     * Nothing paints a finished repair, and that absence is load-bearing rather than accidental.
     *
     * Installing an engine only half-repairs the panel: a provider binds once per process, so the panel
     * restarts to use what it installed. The ordinary end of a successful repair is this screen vanishing
     * with the process that drew it. A "done" state would be a claim made by code that cannot observe it,
     * on a screen that in the successful case is already gone.
     */
    @Test
    fun aSuccessfulRepairIsNeverPainted() {
        val driver = dashboard.substringAfter("private fun startWebViewRepair(").substringBefore("private fun buildAndLoad")
        listOf("Installed", "Success", "succeeded", "Done", "Updated —", "Finished").forEach {
            assertTrue("a repair must not announce an outcome it cannot observe: $it", !driver.contains(it))
        }
        assertTrue(
            "every path out of a started install is the poll, which only ever reports a stop",
            driver.contains("WebViewRepairRequest.STARTED ->") && driver.contains("pollWebViewRepair(button, note)"),
        )
    }

    /**
     * The panel asks the service what it can do rather than working it out beside the screen.
     *
     * Both inputs are privileged questions: a cold su probe forks a process and waits for it, and the
     * caller here is the drawing thread of a panel that is already failing. A second derivation would
     * also be free to disagree with the one the Install page shows, on the same panel, at the same moment.
     */
    @Test
    fun theScreenAsksForTheCapabilityRatherThanDerivingIt() {
        listOf("recommendedWebView", "Su.available", "HelperClient", "WebViewInstaller").forEach {
            assertTrue("the activity must not re-derive repair capability: $it", !dashboard.contains(it))
        }
        assertTrue(dashboard.contains("WebViewRepairRuntime.capability()"))
        assertTrue(dashboard.contains("WebViewRepairRuntime.request()"))
        assertTrue(dashboard.contains("WebViewRepairRuntime.progress()"))
        // And the service starts it through the SAME entry point the Install page posts to, so the two
        // cannot end up with different ideas of what "already installing" means.
        assertTrue(
            "the repair must reuse the existing install lane, not open a second one",
            service.contains("""installComponent("webview", "reinstall", "")"""),
        )
        val operation = service.substringAfter("private suspend fun completeOperation(")
            .substringBefore("private fun launchOperation(")
        assertTrue(
            "the destructive lane must remain owned through post-install activation",
            operation.indexOf("after(result)") in 0 until
                operation.indexOf("InstallProgress.finish(progress, result, presentation = resultPresentation)"),
        )
        assertTrue(
            "the capability probe must not run on the caller's thread",
            service.substringAfter("private fun refreshWebViewRepairCapability(").substringBefore("\n    }")
                .contains("scope.launch"),
        )
    }

    /**
     * The code and the Configure button are the same repair, so a screen shows one or the other.
     *
     * Offering both would put two controls for one job on a 480-pixel screen, and the code exists
     * precisely because that job is the one worth doing somewhere other than on the panel.
     */
    @Test
    fun aCodeReplacesTheButtonItDuplicates() {
        val painter = dashboard.substringAfter("private fun paintV2CompatibilityScreen(").substringBefore("\n    }")
        assertTrue("the Configure action must be conditional on there being no code", painter.contains("if (qr == null) {"))
        assertTrue(
            "the repair action must be offered only where the panel can actually perform it",
            painter.contains("if (repair == WebViewRepairOffer.OFFER) {"),
        )
        assertTrue(
            "a code is only drawn for an address somebody could reach",
            painter.contains("scannableHost(localIpv4(), localIpv6())"),
        )
        assertTrue(
            "the address is printed beside the code, so a phone that cannot scan can still be typed into",
            painter.contains("phoneAddress"),
        )
    }

    /** A screen shown because something failed must not itself depend on the network. */
    @Test
    fun theBrandNeverReachesTheNetwork() {
        listOf("http://", "https://", "URL(", "HttpURLConnection").forEach { forbidden ->
            assertTrue("the brand helpers must stay local: $forbidden", !surface.contains(forbidden))
        }
    }
}
