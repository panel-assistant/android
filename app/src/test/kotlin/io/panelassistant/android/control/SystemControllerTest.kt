package io.panelassistant.android.control

import io.panelassistant.android.AppIdentity
import io.panelassistant.android.device.profile.BundledProfileFixtures
import io.panelassistant.android.platform.ActivityRef
import io.panelassistant.android.platform.DaemonLongResult
import io.panelassistant.android.util.RendererPreparationCoordinator
import io.panelassistant.android.util.RendererPreparationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * SystemController over the seamed collaborators ([io.panelassistant.android.platform.SystemEnv],
 * RootShell, Daemon) — no device. Covers the daemon→su tier selection, the launcher-selection
 * algorithm (incl. the kiosk-default bug fix), the ensureDashboardHome reclaim rules, and the
 * dashboardState parsing.
 */
class SystemControllerTest {
    private var builtinOwner = 0L

    /** [BuiltinDashboard] is a process-global object — reset its mutable state so tests can't leak
     *  a crash latch / pending reload / navigate into each other. */
    @Before fun resetBuiltinDashboard() {
        BuiltinDashboard.clearRendererLatch()
        BuiltinDashboard.consumeReloadRequest()
        BuiltinDashboard.navPath = null
        builtinOwner = BuiltinDashboard.acquireActivityOwner()
        BuiltinDashboard.setActivityForeground(builtinOwner, false)
    }

    @After fun releaseBuiltinDashboard() = BuiltinDashboard.releaseActivityOwner(builtinOwner)

    private val OWN = "io.github.maxlyth.hapaneld"
    private val MIN = "io.homeassistant.companion.android.minimal" // HA Companion (minimal)
    private val FULL = "io.homeassistant.companion.android" // HA Companion (full)
    private val VENDOR = "com.vendor.launcher"

    /** Build a controller. [daemon] = null means the daemon is unavailable (su path); a map (even empty)
     *  means available with those exact-command replies. [su] is the su `run` result; [suOut] feeds runOutput. */
    private fun sc(
        env: FakeSystemEnv,
        daemon: Map<String, String>? = null,
        su: Boolean = true,
        suOut: Map<String, String> = emptyMap(),
        builtinForeground: Boolean = false,
        homeWarnings: MutableList<String>? = null,
        profileId: String = "generic",
    ): Triple<SystemController, FakeRootShell, FakeDaemon> {
        val root = FakeRootShell(outputs = suOut, runResult = su)
        val d = FakeDaemon(replies = daemon ?: emptyMap(), available = daemon != null)
        // Vendor home apps come from the bundled profile, exactly as PaneldService passes them.
        val vendorHome = BundledProfileFixtures.bundledById.getValue(profileId).profile().vendorHomePackages
        return Triple(
            SystemController(
                env, root, d, builtinForeground = { builtinForeground }, homeWarning = { homeWarnings?.add(it) },
                vendorHomePackages = vendorHome,
            ),
            root, d,
        )
    }

    private val BUILTIN = SystemController.BUILTIN_DASHBOARD
    private val DASH_HOME = ActivityRef(OWN, "io.panelassistant.android.DashboardActivity")

    @Test fun homeUiProofRequiresForegroundDashboardAndResolvedHome() {
        val ready = sc(FakeSystemEnv(default = DASH_HOME), builtinForeground = true).first
            .homeUiProof("", adminUiVisible = false)
        assertEquals("ready", ready.state)
        assertEquals("dashboard_foreground", ready.reason)

        val chooser = sc(FakeSystemEnv(default = ActivityRef("android", "com.android.internal.app.ResolverActivity")),
            builtinForeground = true).first.homeUiProof("", adminUiVisible = false)
        assertEquals("blocked", chooser.state)
        assertEquals("home_resolver", chooser.reason)
        val chooserOverAdmin = sc(FakeSystemEnv(default = ActivityRef("android", "com.android.internal.app.ResolverActivity"))).first
            .homeUiProof("", adminUiVisible = true)
        assertEquals("blocked", chooserOverAdmin.state)

        val covered = sc(FakeSystemEnv(default = DASH_HOME), builtinForeground = false).first
            .homeUiProof("", adminUiVisible = false)
        assertEquals("blocked", covered.state)
        assertEquals("dashboard_background", covered.reason)

        val unknown = sc(FakeSystemEnv(default = null), builtinForeground = true).first
            .homeUiProof("", adminUiVisible = false)
        assertEquals("unknown", unknown.state)
        assertEquals("home_unresolved", unknown.reason)
    }

    @Test fun homeUiProofAllowsOnlyVisibleAdminAsSetup() {
        val controller = sc(FakeSystemEnv(default = null), builtinForeground = false).first
        assertEquals("setup", controller.homeUiProof("", adminUiVisible = true).state)
        assertEquals("unknown", controller.homeUiProof("", adminUiVisible = false).state)
    }

    @Test fun homeUiProofRejectsFallbackAndMismatchedRendererHome() {
        val fallback = sc(FakeSystemEnv(default = ActivityRef("com.android.settings", ".FallbackHome")),
            builtinForeground = true).first
        assertEquals("blocked", fallback.homeUiProof("", adminUiVisible = false).state)
        assertEquals("blocked", fallback.homeUiProof("", adminUiVisible = true).state)

        val oldRenderer = sc(FakeSystemEnv(default = ActivityRef(MIN, ".Home")),
            builtinForeground = true).first
        assertEquals("blocked", oldRenderer.homeUiProof("", adminUiVisible = false).state)

        val ownHome = sc(FakeSystemEnv(installed = setOf(MIN), default = DASH_HOME),
            daemon = mapOf("APPSTATE $MIN" to "FG")).first
        assertEquals("blocked", ownHome.homeUiProof(MIN, adminUiVisible = false).state)

        val deliberateLauncher = sc(FakeSystemEnv(default = ActivityRef(VENDOR, ".Home")),
            builtinForeground = true).first
        assertEquals("ready", deliberateLauncher.homeUiProof("", adminUiVisible = false).state)

        val foreignFallbackName = sc(FakeSystemEnv(default = ActivityRef(VENDOR, ".FallbackHome")),
            builtinForeground = true).first
        assertEquals("ready", foreignFallbackName.homeUiProof("", adminUiVisible = false).state)
    }

    @Test fun homeUiProofUsesLiveAppStateForExternalDashboard() {
        val env = FakeSystemEnv(installed = setOf(MIN), default = ActivityRef(MIN, ".Home"))
        val foreground = sc(env, daemon = mapOf("APPSTATE $MIN" to "FG")).first
        assertEquals("ready", foreground.homeUiProof(MIN, adminUiVisible = false).state)
        val background = sc(env, daemon = mapOf("APPSTATE $MIN" to "BG")).first
        assertEquals("blocked", background.homeUiProof(MIN, adminUiVisible = false).state)
        val unobserved = sc(env, daemon = mapOf("APPSTATE $MIN" to "ERR"), su = false).first
        assertEquals("unknown", unobserved.homeUiProof(MIN, adminUiVisible = false).state)
    }

    // ---------- reboot ----------
    @Test fun nativeActionAvailabilityFollowsExecutableRoutes() {
        val noPrivilege = SystemController(
            FakeSystemEnv(installed = setOf(MIN)),
            FakeRootShell(available = false),
            FakeDaemon(available = false),
        )
        assertFalse(noPrivilege.canReboot())
        assertFalse(noPrivilege.canReloadDashboard(MIN))
        assertTrue(noPrivilege.canReloadDashboard(BUILTIN))

        val helper = sc(FakeSystemEnv(installed = setOf(MIN)), daemon = emptyMap(), su = false).first
        assertTrue(helper.canReboot())
        assertTrue(helper.canReloadDashboard(MIN))

        val root = SystemController(
            FakeSystemEnv(installed = setOf(MIN)),
            FakeRootShell(available = true),
            FakeDaemon(available = false),
        )
        assertTrue(root.canReboot())
        assertTrue(root.canReloadDashboard(MIN))
    }

    @Test fun nativeDescriptorChecksShareOnePrivilegeProbeAcrossLiveWakes() {
        val helper = FakeDaemon(available = false)
        val root = FakeRootShell(available = true)
        val controller = SystemController(FakeSystemEnv(installed = setOf(MIN)), root, helper)

        repeat(20) {
            assertTrue(controller.canReboot())
            assertTrue(controller.canReloadDashboard(MIN))
        }
        assertEquals(1, helper.availabilityChecks)
        assertEquals(1, root.availabilityChecks)
    }

    @Test fun failedActionRoutesDoNotReportAnAppliedPress() {
        val (reboot, _, _) = rebootController(DaemonLongResult.Reply("ERR"), su = false)
        assertFalse(reboot.reboot())

        val (reload, _, _) = sc(FakeSystemEnv(installed = setOf(MIN)), daemon = mapOf("RELOAD $MIN" to "ERR"), su = false)
        assertFalse(reload.reloadDashboard(MIN))
    }

    /** Build a controller whose helper answers REBOOT AWAIT with an exact [DaemonLongResult]. */
    private fun rebootController(
        outcome: DaemonLongResult? = null,
        su: Boolean = true,
    ): Triple<SystemController, FakeRootShell, FakeDaemon> {
        val root = FakeRootShell(runResult = su)
        val d = FakeDaemon(
            available = outcome != null,
            longOutcomes = outcome?.let { mapOf("REBOOT AWAIT" to it) } ?: emptyMap(),
        )
        return Triple(SystemController(FakeSystemEnv(), root, d, builtinForeground = { false }), root, d)
    }

    /** A panel that is genuinely going down closes the socket instead of answering, and that silence
     *  is the only success signal a reboot can produce. */
    @Test fun rebootPrefersDaemon() {
        val (c, root, d) = rebootController(DaemonLongResult.Indeterminate)
        c.reboot()
        assertEquals(listOf("REBOOT AWAIT"), d.sent)
        assertTrue("no su when the panel is already going down", root.ran.isEmpty())
    }

    @Test fun rebootAnnouncesBeforeTheDeviceCommand() {
        val events = mutableListOf<String>()
        val daemon = FakeDaemon(replies = mapOf("REBOOT AWAIT" to "OK"), onSend = { events += it })
        val controller = SystemController(FakeSystemEnv(), FakeRootShell(), daemon, beforeReboot = { events += "notice" })
        controller.reboot()
        assertEquals(listOf("notice", "REBOOT AWAIT"), events)
    }

    @Test fun rebootFallsToSu() {
        val (c, root, _) = rebootController(outcome = null)
        c.reboot()
        assertTrue("su reboot fired, got ${root.ran}", root.ran.contains("reboot"))
    }

    /**
     * The reporter's defect, at the app boundary. A helper that ran every mechanism and is still up
     * answers ERR, and that must reach the root route rather than being reported as a reboot: root
     * runs from the app's environment, not the daemon's sanitized one, which is exactly the
     * difference that made `svc power reboot` work in one and silently do nothing in the other.
     */
    @Test fun rebootTriesRootWhenTheHelperProvesThePanelIsStillUp() {
        val (c, root, d) = rebootController(DaemonLongResult.Reply("ERR"))
        c.reboot()
        assertEquals(listOf("REBOOT AWAIT"), d.sent)
        assertTrue("a proven-failed helper reboot falls through to su", root.ran.contains("reboot"))
    }

    /** A helper predating AWAIT ignores the argument and accepts immediately; that is the best
     *  evidence it can give, so it must still count as a reboot rather than losing one. */
    @Test fun rebootAcceptsAnOlderHelperThatAnswersImmediately() {
        val (c, root, d) = rebootController(DaemonLongResult.Reply("OK"))
        c.reboot()
        assertEquals(listOf("REBOOT AWAIT"), d.sent)
        assertTrue("an older helper's acceptance must not be second-guessed", root.ran.isEmpty())
    }

    /** The wait must outlast the helper's own bounded escalation (two mechanisms, 6s + 4s), or a
     *  daemon still working through it would be misread as one that already went down. */
    @Test fun rebootAwaitOutlastsTheHelperEscalationWindow() {
        val (c, _, d) = rebootController(DaemonLongResult.Reply("ERR"))
        c.reboot()
        assertEquals(listOf(15_000L), d.longTimeouts)
    }

    // ---------- resolveDashboard / dashboardState ----------
    @Test fun dashboardStateBlankAutoUsesBuiltin() =
        assertEquals(AppState.BG, sc(FakeSystemEnv(), daemon = null).first.dashboardState(""))

    @Test fun dashboardStateBlankAutoDoesNotProbeInstalledCompanion() {
        val env = FakeSystemEnv(installed = setOf(MIN))
        val (c, _, d) = sc(env, daemon = mapOf("APPSTATE $MIN" to "FG"))
        assertEquals(AppState.BG, c.dashboardState(""))
        assertFalse(d.sent.contains("APPSTATE $MIN"))
    }

    @Test fun dashboardStateDaemonRepliesMapped() {
        fun state(reply: String) = sc(
            FakeSystemEnv(installed = setOf(MIN)),
            daemon = mapOf("APPSTATE $MIN" to reply),
            suOut = emptyMap(),
        ).first.dashboardState(MIN)
        assertEquals(AppState.FG, state("FG"))
        assertEquals(AppState.BG, state("BG"))
        assertEquals(AppState.DEAD, state("DEAD"))
        assertEquals("unknown reply and no su result → UNKNOWN", AppState.UNKNOWN, state("ERR"))
    }

    @Test fun dashboardStateFallsToSuWhenDaemonProbeFails() {
        val out = mapOf("pidof" to "1234", "dumpsys window" to "mCurrentFocus=Window{$MIN/$MIN.WebViewActivity}")
        val (c, root, d) = sc(FakeSystemEnv(installed = setOf(MIN)), daemon = mapOf("APPSTATE $MIN" to "ERR"), suOut = out)
        assertEquals(AppState.FG, c.dashboardState(MIN))
        assertEquals(listOf("APPSTATE $MIN"), d.sent)
        assertTrue(root.outputRan.any { it.startsWith("pidof $MIN") })
        assertTrue(root.outputRan.any { it.startsWith("dumpsys window") })
    }

    @Test fun dashboardStateViaSuPidofAndFocus() {
        // pidof present + focus contains pkg/ → FG; not focused → BG; blank pidof → DEAD; no su → UNKNOWN.
        fun state(out: Map<String, String>) = sc(FakeSystemEnv(installed = setOf(MIN)), daemon = null, suOut = out).first.dashboardState(MIN)
        assertEquals(AppState.FG, state(mapOf("pidof" to "1234", "dumpsys window" to "mCurrentFocus=Window{$MIN/$MIN.WebViewActivity}")))
        assertEquals(AppState.BG, state(mapOf("pidof" to "1234", "dumpsys window" to "mCurrentFocus=Window{com.android.launcher/X}")))
        assertEquals(AppState.DEAD, state(mapOf("pidof" to "")))
        assertEquals("no su output → UNKNOWN", AppState.UNKNOWN, state(emptyMap()))
        assertEquals("live pid but failed focus probe → UNKNOWN", AppState.UNKNOWN, state(mapOf("pidof" to "1234")))
    }

    // ---------- reloadDashboard ----------
    @Test fun reloadPrefersDaemon() {
        val (c, root, d) = sc(FakeSystemEnv(installed = setOf(MIN)), daemon = mapOf("RELOAD $MIN" to "OK"))
        c.reloadDashboard(MIN)
        assertTrue("daemon RELOAD sent", d.sent.contains("RELOAD $MIN"))
        assertTrue("no su when daemon present", root.ran.isEmpty())
    }

    @Test fun reloadFallsToSuWhenDaemonRejectsVerb() {
        val env = FakeSystemEnv(installed = setOf(MIN), launchers = mapOf(MIN to "$MIN/.Main"))
        val replies = mapOf("RELOAD $MIN" to "ERR", "START $MIN/.Main" to "ERR")
        val (c, root, d) = sc(env, daemon = replies, su = true)
        c.reloadDashboard(MIN)
        assertEquals(listOf("RELOAD $MIN", "START $MIN/.Main"), d.sent)
        assertTrue(root.ran.contains("am force-stop $MIN"))
        assertTrue(root.ran.contains("am start -n $MIN/.Main"))
    }

    @Test fun reloadBusyNeverFallsThroughTheHelperTransactionBoundary() {
        val env = FakeSystemEnv(installed = setOf(MIN), launchers = mapOf(MIN to "$MIN/.Main"))
        val (c, root, d) = sc(env, daemon = mapOf("RELOAD $MIN" to "BUSY"), su = true)
        c.reloadDashboard(MIN)
        assertEquals(listOf("RELOAD $MIN"), d.sent)
        assertTrue(root.ran.isEmpty())
        assertTrue(env.directStarts.isEmpty())
    }

    @Test fun reloadRefusesWhenHelperBlocksRelaunchAfterSuForceStop() {
        val component = "$MIN/.Main"
        val env = FakeSystemEnv(installed = setOf(MIN), launchers = mapOf(MIN to component))
        val (controller, root, daemon) = sc(
            env,
            daemon = mapOf("RELOAD $MIN" to "ERR", "START $component" to "BUSY"),
            su = true,
        )

        assertFalse(controller.reloadDashboard(MIN))
        assertEquals(listOf("RELOAD $MIN", "START $component"), daemon.sent)
        assertEquals(listOf("am force-stop $MIN"), root.ran)
        assertTrue(env.directStarts.isEmpty())
    }

    @Test fun reloadViaSuForceStopThenPrivilegedStart() {
        val env = FakeSystemEnv(installed = setOf(MIN), launchers = mapOf(MIN to "$MIN/.Main"))
        val (c, root, _) = sc(env, daemon = null, su = true)
        c.reloadDashboard(MIN)
        assertTrue("force-stop ran", root.ran.contains("am force-stop $MIN"))
        assertTrue("privileged start ran", root.ran.contains("am start -n $MIN/.Main"))
        assertFalse("monkey not used when start succeeds", root.ran.any { it.startsWith("monkey") })
    }

    @Test fun reloadFallsToMonkeyWithoutLaunchComponent() {
        val (c, root, _) = sc(FakeSystemEnv(installed = setOf(MIN)), daemon = null, su = true)
        c.reloadDashboard(MIN)
        assertTrue("monkey relaunch, got ${root.ran}", root.ran.contains("monkey -p $MIN 1"))
    }

    @Test fun reloadBlankAutoUsesBuiltin() {
        val env = FakeSystemEnv()
        val (c, root, d) = sc(env, daemon = null)
        assertTrue(c.reloadDashboard(""))
        assertTrue(
            "built-in renderer relaunched via privileged or direct route",
            root.ran.contains("am start -n $OWN/io.panelassistant.android.DashboardActivity") ||
                env.directStarts.contains("$OWN/io.panelassistant.android.DashboardActivity"),
        )
        assertFalse(d.sent.any { it.startsWith("RELOAD ") })
    }

    @Test fun companionDataLeaseSuppressesReloadAndHomeLaunchUntilReleased() {
        val env = FakeSystemEnv(installed = setOf(MIN), launchers = mapOf(MIN to "$MIN/.Main"))
        val (c, root, d) = sc(env, daemon = mapOf("RELOAD $MIN" to "OK", "START $MIN/.Main" to "OK"))
        CompanionDataOperationGate.acquire(MIN)!!.use {
            c.reloadDashboard(MIN)
            c.launchHome(MIN)
            assertTrue(root.ran.isEmpty())
            assertTrue(d.sent.isEmpty())
        }

        c.launchHome(MIN)
        assertEquals(listOf("START $MIN/.Main"), d.sent)
    }

    @Test fun companionHomeLaunchWithoutOperationMarkerKeepsNoHelperSuFallback() {
        val env = FakeSystemEnv(installed = setOf(MIN), launchers = mapOf(MIN to "$MIN/.Main"))
        val (c, root, d) = sc(
            env,
            daemon = mapOf("START $MIN/.Main" to "ERR"),
        )

        c.launchHome(MIN)

        assertEquals(listOf("START $MIN/.Main"), d.sent)
        assertEquals(listOf("am start -n $MIN/.Main"), root.ran)
        assertTrue(env.directStarts.isEmpty())
    }

    @Test fun invalidStoredDashboardNeverCrossesPrivilegeBoundary() {
        val injected = "com.example;reboot"
        val (c, root, d) = sc(FakeSystemEnv(), daemon = emptyMap())
        c.reloadDashboard(injected)
        assertEquals(AppState.UNKNOWN, c.dashboardState(injected))
        assertTrue(root.ran.isEmpty())
        assertTrue(d.sent.isEmpty())
    }

    // ---------- launchLauncher (the selection algorithm) ----------
    private fun launcherEnv(default: String?, vararg homes: String) = FakeSystemEnv(
        homes = homes.map { ActivityRef(it, "L") },
        default = default?.let { ActivityRef(it, "L") },
    )

    @Test fun launcherPrefersRealDefault() {
        val (c, root, _) = sc(launcherEnv(VENDOR, VENDOR, MIN), daemon = null)
        c.launchLauncher("")
        assertTrue("real default launcher chosen", root.ran.contains("am start -n $VENDOR/L"))
    }

    @Test fun launcherSkipsKioskCompanionDefault() {
        // The bug: default home is the Companion kiosk (registers HOME) → must fall through to a real launcher.
        val (c, root, _) = sc(launcherEnv(MIN, MIN, VENDOR), daemon = null)
        c.launchLauncher("")
        assertTrue("skipped kiosk default, chose real launcher", root.ran.contains("am start -n $VENDOR/L"))
    }

    @Test fun launcherSkipsEweLinkKioskDefault() {
        // eWeLink's control panel registers HOME but is vendor kiosk garbage, not a launcher — when it's
        // the default home, the Launcher button must fall through to a real launcher (a field report).
        val ewelink = "com.eWeLinkControlPanel"
        val (c, root, _) = sc(launcherEnv(ewelink, ewelink, VENDOR), daemon = null, profileId = "nspanel-pro")
        c.launchLauncher("")
        assertTrue("skipped eWeLink kiosk, chose real launcher", root.ran.contains("am start -n $VENDOR/L"))
    }

    @Test fun launcherEweLinkOnlyHomeFallsBackToAdminLauncher() {
        // eWeLink the sole registered home → no real launcher, so open ha-paneld's admin launcher.
        val ewelink = "com.eWeLinkControlPanel"
        val (c, root, _) = sc(launcherEnv(ewelink, ewelink), daemon = null, profileId = "nspanel-pro")
        c.launchLauncher("")
        assertTrue(
            "admin launcher opened when only eWeLink registers HOME",
            root.ran.contains("am start -n io.github.maxlyth.hapaneld/io.panelassistant.android.AdminLauncherActivity"),
        )
    }

    // The bench X2i: no root, ha-paneld holds HOME, and Shelly's Stargate control app is the only other
    // HOME candidate. Landing on Stargate leaves no way to Android Settings.
    private fun x2iHomes() = FakeSystemEnv(
        homes = listOf(
            ActivityRef("cloud.shelly.stargate", "cloud.shelly.stargate.activities.SplashActivity"),
            DASH_HOME,
            ActivityRef(OWN, "$OWN.AdminLauncherActivity"),
            ActivityRef("com.android.settings", "com.android.settings.FallbackHome"),
        ),
        default = DASH_HOME,
    )

    @Test fun launcherOnShellyWallDisplayOpensAdminLauncherNotStargate() {
        // Every Shelly profile names Stargate, so the button opens the admin launcher, which offers
        // Settings and lists Stargate as an app.
        for (profileId in listOf("shelly-wall-display-x2i", "shelly-wall-display-v2", "shelly-wall-display")) {
            val env = x2iHomes()
            val (c, _, _) = sc(env, daemon = null, su = false, profileId = profileId)
            c.launchLauncher("")
            assertEquals(profileId, listOf("$OWN/io.panelassistant.android.AdminLauncherActivity"), env.directStarts)
        }
    }

    @Test fun launcherSkipsOnlyWhatTheProfileNames() {
        // The exclusion is profile data, not app code: a profile that names no vendor home app leaves
        // Stargate an ordinary launcher candidate.
        val env = x2iHomes()
        val (c, _, _) = sc(env, daemon = null, su = false, profileId = "generic")
        c.launchLauncher("")
        assertEquals(listOf("cloud.shelly.stargate/cloud.shelly.stargate.activities.SplashActivity"), env.directStarts)
    }

    @Test fun launcherHonoursConfiguredPkg() {
        val (c, root, _) = sc(launcherEnv(VENDOR, VENDOR, "com.other.home"), daemon = null)
        c.launchLauncher("com.other.home")
        assertTrue("configured launcher wins over default", root.ran.contains("am start -n com.other.home/L"))
    }

    @Test fun launcherOwnPackageAlwaysTargetsAdminActivityNotDashboard() {
        // HOME query order is deliberately hostile: DashboardActivity appears first.
        val env = FakeSystemEnv(
            homes = listOf(DASH_HOME, ActivityRef(OWN, "$OWN.AdminLauncherActivity")),
            default = DASH_HOME,
        )
        val (c, root, _) = sc(env, daemon = null)

        c.launchLauncher(OWN)

        assertEquals(listOf("am start -n $OWN/io.panelassistant.android.AdminLauncherActivity"), root.ran)
        assertEquals(OWN, c.resolvedLauncher(OWN))
    }

    @Test fun blankLauncherWithBothPanelAppsNeverOpensEitherDashboard() {
        for (own in AppIdentity.ALL) {
            val counterpart = AppIdentity.ALL.single { it != own }
            val dashboards = listOf(counterpart, own).map {
                ActivityRef(it, AppIdentity.className(it, ".DashboardActivity"))
            }
            val admin = AppIdentity.component(own, ".AdminLauncherActivity")
            for (default in dashboards) {
                for (hasLauncher in listOf(true, false)) {
                    val vendor = ActivityRef(VENDOR, "L")
                    val env = FakeSystemEnv(
                        ownPackage = own,
                        installed = AppIdentity.ALL + if (hasLauncher) setOf(VENDOR) else emptySet(),
                        homes = dashboards + if (hasLauncher) listOf(vendor) else emptyList(),
                        default = default,
                    )
                    val (controller, root, _) = sc(env)

                    controller.launchLauncher("")

                    val expected = if (hasLauncher) vendor.component else admin
                    assertEquals("own=$own default=${default.pkg} launcher=$hasLauncher",
                        listOf("am start -n $expected"), root.ran)
                }
            }
        }
    }

    @Test fun restoredCounterpartLauncherWithBothPanelAppsOpensCurrentAdmin() {
        for (own in AppIdentity.ALL.reversed()) {
            val counterpart = AppIdentity.ALL.single { it != own }
            val env = FakeSystemEnv(
                ownPackage = own,
                installed = AppIdentity.ALL,
                homes = listOf(counterpart, own).map {
                    ActivityRef(it, AppIdentity.className(it, ".DashboardActivity"))
                } + ActivityRef(VENDOR, "L"),
                default = ActivityRef(VENDOR, "L"),
            )
            val (controller, root, _) = sc(env)

            controller.launchLauncher(counterpart)

            assertEquals("restored $counterpart in $own must open this app's drawer",
                listOf("am start -n ${AppIdentity.component(own, ".AdminLauncherActivity")}"), root.ran)
            assertEquals(own, controller.resolvedLauncher(counterpart))
        }
    }

    @Test fun launcherNoneSuitableFallsBackToAdminLauncher() {
        // Only the Companion registers HOME (and it's the default) → no launcher app to land on, so
        // the key must open ha-paneld's own admin launcher instead of silently doing nothing.
        val (c, root, _) = sc(launcherEnv(MIN, MIN), daemon = null)
        c.launchLauncher("")
        assertTrue(
            "admin launcher opened as the fallback",
            root.ran.contains("am start -n io.github.maxlyth.hapaneld/io.panelassistant.android.AdminLauncherActivity"),
        )
    }

    @Test fun launcherStaleConfiguredPkgFallsBackToAdminLauncher() {
        // A configured launcher that's no longer installed degrades the same way as none-resolvable.
        val (c, root, _) = sc(launcherEnv(MIN, MIN), daemon = null)
        c.launchLauncher("com.gone.launcher")
        assertTrue(
            "admin launcher opened for a stale configured package",
            root.ran.contains("am start -n io.github.maxlyth.hapaneld/io.panelassistant.android.AdminLauncherActivity"),
        )
    }

    @Test fun launcherViaDaemonStart() {
        val (c, root, d) = sc(launcherEnv(VENDOR, VENDOR), daemon = mapOf("START $VENDOR/L" to "OK"))
        c.launchLauncher("")
        assertTrue("daemon START used", d.sent.contains("START $VENDOR/L"))
        assertTrue("no su when daemon START ok", root.ran.isEmpty())
    }

    @Test fun launcherFallsToSuWhenDaemonStartRejected() {
        val (c, root, d) = sc(launcherEnv(VENDOR, VENDOR), daemon = mapOf("START $VENDOR/L" to "ERR"))
        c.launchLauncher("")
        assertEquals(listOf("START $VENDOR/L"), d.sent)
        assertTrue("stale START falls through to su", root.ran.contains("am start -n $VENDOR/L"))
    }

    @Test fun launcherFallsToDirectStartWhenPrivilegedFails() {
        val env = launcherEnv(VENDOR, VENDOR)
        val (c, _, _) = sc(env, daemon = null, su = false) // no daemon, su run fails
        c.launchLauncher("")
        assertTrue("direct-start fallback used, got ${env.directStarts}", env.directStarts.contains("$VENDOR/L"))
    }

    // ---------- launchAdminLauncher ----------
    @Test fun adminLauncherStartsOwnComponent() {
        val (c, root, _) = sc(FakeSystemEnv(), daemon = null, su = true)
        c.launchAdminLauncher()
        assertTrue(root.ran.contains("am start -n $OWN/io.panelassistant.android.AdminLauncherActivity"))
    }

    @Test fun adminLauncherFallsToDirectStart() {
        val env = FakeSystemEnv()
        val (c, _, _) = sc(env, daemon = null, su = false)
        c.launchAdminLauncher()
        assertTrue(env.directStarts.contains("$OWN/io.panelassistant.android.AdminLauncherActivity"))
    }

    // ---------- explicit ha-paneld HOME policy ----------
    @Test fun adminHomeRecognisesPackageOwnershipAcrossPlatformComponentResolution() {
        assertTrue(sc(FakeSystemEnv(default = ActivityRef(OWN, ".AdminLauncherActivity"))).first.isAdminLauncherHome())
        assertTrue(sc(FakeSystemEnv(default = ActivityRef(OWN, "$OWN.AdminLauncherActivity"))).first.isAdminLauncherHome())
        assertTrue(sc(FakeSystemEnv(default = DASH_HOME)).first.isAdminLauncherHome())
        assertFalse(sc(FakeSystemEnv(default = ActivityRef(VENDOR, "AdminLauncherActivity"))).first.isAdminLauncherHome())
        assertFalse(sc(FakeSystemEnv(default = null)).first.isAdminLauncherHome())
    }

    @Test fun ensureAdminHomeNoopsWhenAlreadyCorrect() {
        val env = FakeSystemEnv(default = ActivityRef(OWN, "$OWN.AdminLauncherActivity"))
        val (c, root, d) = sc(env, daemon = emptyMap())

        assertTrue(c.ensureAdminLauncherHome())

        assertTrue(root.ran.isEmpty())
        assertTrue(d.sent.isEmpty())
    }

    @Test fun healthyStartupRestoresExplicitAdminHomeAfterPreferredActivityIsCleared() {
        val adminHome = ActivityRef(OWN, "$OWN.AdminLauncherActivity")
        val env = FakeSystemEnv(default = adminHome)
        val command = "SETHOME $OWN/io.panelassistant.android.AdminLauncherActivity"
        val daemon = FakeDaemon(
            replies = mapOf(command to "OK"),
            onSend = { if (it == command) env.default = adminHome },
        )
        val system = SystemController(env, FakeRootShell(), daemon)

        fun startFreshProcess() = RendererPreparationCoordinator(
            builtinPackage = BUILTIN,
            state = { RendererPreparationState(BUILTIN, "http://ha:8123") },
            borrow = { error("ready renderer must not borrow") },
            persist = { error("ready renderer must not persist") },
        ).reconcileStartup(
            ensureHome = { pkg, ready -> system.applyLauncherHomePolicy(OWN, pkg, ready) },
            launchHome = {},
        )

        assertEquals(RendererPreparationCoordinator.Result.ALREADY_READY, startFreshProcess())
        assertTrue(daemon.sent.isEmpty())

        // Android can clear the preferred HOME activity after a crash while the saved selection remains.
        env.default = ActivityRef("android", "ResolverActivity")
        assertEquals(RendererPreparationCoordinator.Result.ALREADY_READY, startFreshProcess())
        assertEquals(OWN, env.default?.pkg)
        assertEquals(listOf(command), daemon.sent)

        startFreshProcess()
        assertEquals("an already restored HOME needs no further mutation", listOf(command), daemon.sent)
    }

    @Test fun ensureAdminHomeForceReclaimsVendorHomeViaDaemon() {
        val env = FakeSystemEnv(default = ActivityRef(VENDOR, "Home"))
        val command = "SETHOME $OWN/io.panelassistant.android.AdminLauncherActivity"
        val (c, root, d) = sc(env, daemon = mapOf(command to "OK"))

        assertTrue(c.ensureAdminLauncherHome())

        assertEquals(listOf(command), d.sent)
        assertTrue(root.ran.isEmpty())
    }

    @Test fun ensureAdminHomeFallsBackToSuAndReportsFailure() {
        val env = FakeSystemEnv(default = ActivityRef(VENDOR, "Home"))
        val command = "SETHOME $OWN/io.panelassistant.android.AdminLauncherActivity"
        val (success, successRoot, _) = sc(env, daemon = mapOf(command to "ERR"), su = true)
        val (failure, failureRoot, _) = sc(env, daemon = mapOf(command to "ERR"), su = false)

        assertTrue(success.ensureAdminLauncherHome())
        assertEquals(listOf("cmd package set-home-activity $OWN/io.panelassistant.android.AdminLauncherActivity"), successRoot.ran)
        assertFalse(failure.ensureAdminLauncherHome())
        assertEquals(listOf("cmd package set-home-activity $OWN/io.panelassistant.android.AdminLauncherActivity"), failureRoot.ran)
    }

    @Test fun admittedPeriodicHomeRouteNeverFallsThroughToAnotherPrivilegeTransport() {
        val env = FakeSystemEnv(default = ActivityRef(VENDOR, "Home"))
        val command = "SETHOME $OWN/io.panelassistant.android.AdminLauncherActivity"
        val (daemonOnly, daemonRoot, daemon) = sc(env, daemon = mapOf(command to "ERR"), su = true)

        assertFalse(daemonOnly.ensureAdminLauncherHome(PrivilegeRoute.DAEMON))
        assertEquals(listOf(command), daemon.sent)
        assertTrue(daemonRoot.ran.isEmpty())

        val (suOnly, suRoot, suDaemon) = sc(env, daemon = mapOf(command to "OK"), su = true)
        assertTrue(suOnly.ensureAdminLauncherHome(PrivilegeRoute.SU))
        assertTrue(suDaemon.sent.isEmpty())
        assertEquals(listOf("cmd package set-home-activity $OWN/io.panelassistant.android.AdminLauncherActivity"), suRoot.ran)
    }

    @Test fun launcherHomePolicyExplicitSelfOverridesButOtherSelectionRestoresAutomaticPolicy() {
        val explicitEnv = FakeSystemEnv(default = ActivityRef(VENDOR, "Home"))
        val explicitCommand = "SETHOME $OWN/io.panelassistant.android.AdminLauncherActivity"
        val (explicit, _, explicitDaemon) = sc(explicitEnv, daemon = mapOf(explicitCommand to "OK"))
        assertTrue(explicit.applyLauncherHomePolicy(OWN, MIN))
        assertEquals(listOf(explicitCommand), explicitDaemon.sent)

        val automaticEnv = FakeSystemEnv(
            installed = setOf(MIN),
            homes = listOf(ActivityRef(MIN, "Home")),
            default = ActivityRef(OWN, "$OWN.AdminLauncherActivity"),
        )
        val automaticCommand = "SETHOME $MIN/Home"
        val (automatic, _, automaticDaemon) = sc(automaticEnv, daemon = mapOf(automaticCommand to "OK"))
        assertFalse(automatic.applyLauncherHomePolicy(VENDOR, MIN))
        assertEquals(listOf(automaticCommand), automaticDaemon.sent)
    }

    @Test fun launcherHomePolicyPreservesBuiltinReadinessGateWhenLeavingExplicitSelf() {
        val env = FakeSystemEnv(homes = listOf(DASH_HOME), default = ActivityRef(OWN, "$OWN.AdminLauncherActivity"))
        val (c, root, d) = sc(env, daemon = null)

        assertFalse(c.applyLauncherHomePolicy("", BUILTIN, builtinReady = false))

        assertTrue(root.ran.isEmpty())
        assertTrue(d.sent.isEmpty())
    }

    // ---------- ensureDashboardHome (reclaim rules) ----------
    @Test fun ensureHomeNoopWhenNoDashboardInstalled() {
        val (c, root, d) = sc(FakeSystemEnv(), daemon = null)
        c.ensureDashboardHome("")
        assertTrue(root.ran.isEmpty() && d.sent.isEmpty())
    }

    @Test fun ensureHomeNoopWhenAlreadyDefault() {
        val env = FakeSystemEnv(installed = setOf(MIN), homes = listOf(ActivityRef(MIN, "Home")), default = ActivityRef(MIN, "Home"))
        val (c, root, d) = sc(env, daemon = null)
        c.ensureDashboardHome(MIN)
        assertTrue("already correct → no set-home", root.ran.isEmpty() && d.sent.isEmpty())
    }

    @Test fun ensureHomeRespectsThirdPartyLauncher() {
        val env = FakeSystemEnv(
            installed = setOf(MIN), homes = listOf(ActivityRef(MIN, "Home")), default = ActivityRef("com.thirdparty.home", "L"),
        )
        val (c, root, d) = sc(env, daemon = null)
        c.ensureDashboardHome(MIN)
        assertTrue("a deliberate 3rd-party home is left alone", root.ran.isEmpty() && d.sent.isEmpty())
    }

    @Test fun ensureHomeReclaimsFromAndroidResolverViaSu() {
        val env = FakeSystemEnv(installed = setOf(MIN), homes = listOf(ActivityRef(MIN, "Home")), default = ActivityRef("android", "Resolver"))
        val (c, root, _) = sc(env, daemon = null, su = true)
        c.ensureDashboardHome(MIN)
        assertTrue("reclaim from resolver via su", root.ran.contains("cmd package set-home-activity $MIN/Home"))
    }

    @Test fun fallbackHomeIsReclaimedForBuiltinAndCompanion() {
        val fallback = ActivityRef("com.android.settings", ".FallbackHome")
        val builtinEnv = FakeSystemEnv(homes = listOf(DASH_HOME), default = fallback)
        val (builtin, builtinRoot, _) = sc(builtinEnv, daemon = null)
        builtin.applyLauncherHomePolicy("", "")
        assertEquals(listOf("cmd package set-home-activity ${DASH_HOME.component}"), builtinRoot.ran)

        val companionEnv = FakeSystemEnv(installed = setOf(MIN), homes = listOf(ActivityRef(MIN, "Home")), default = fallback)
        val (companion, companionRoot, _) = sc(companionEnv, daemon = null)
        companion.applyLauncherHomePolicy("", MIN)
        assertEquals(listOf("cmd package set-home-activity $MIN/Home"), companionRoot.ran)

        val deliberateLauncherEnv = FakeSystemEnv(homes = listOf(DASH_HOME), default = ActivityRef(VENDOR, ".FallbackHome"))
        val (deliberateLauncher, launcherRoot, _) = sc(deliberateLauncherEnv, daemon = null)
        deliberateLauncher.applyLauncherHomePolicy("", "")
        assertTrue(launcherRoot.ran.isEmpty())
    }

    @Test fun ensureHomeReclaimsFromSelfViaDaemon() {
        val env = FakeSystemEnv(installed = setOf(MIN), homes = listOf(ActivityRef(MIN, "Home")), default = ActivityRef(OWN, ".AdminLauncherActivity"))
        val (c, _, d) = sc(env, daemon = mapOf("SETHOME $MIN/Home" to "OK"))
        c.ensureDashboardHome(MIN)
        assertTrue("reclaim from ourselves via daemon SETHOME", d.sent.contains("SETHOME $MIN/Home"))
    }

    @Test fun ensureHomeFallsToSuWhenDaemonSetHomeRejected() {
        val env = FakeSystemEnv(installed = setOf(MIN), homes = listOf(ActivityRef(MIN, "Home")), default = ActivityRef(OWN, ".AdminLauncherActivity"))
        val (c, root, d) = sc(env, daemon = mapOf("SETHOME $MIN/Home" to "ERR"))
        c.ensureDashboardHome(MIN)
        assertEquals(listOf("SETHOME $MIN/Home"), d.sent)
        assertTrue("stale SETHOME falls through to su", root.ran.contains("cmd package set-home-activity $MIN/Home"))
    }

    @Test fun ensureHomeSwitchesBetweenInstalledCompanionVariants() {
        listOf(FULL to MIN, MIN to FULL).forEach { (current, target) ->
            val env = FakeSystemEnv(
                installed = setOf(FULL, MIN),
                homes = listOf(ActivityRef(FULL, "Home"), ActivityRef(MIN, "Home")),
                default = ActivityRef(current, "Home"),
            )
            val command = "SETHOME $target/Home"
            val (controller, root, daemon) = sc(env, daemon = mapOf(command to "OK"))

            controller.ensureDashboardHome(target)

            assertEquals("$current -> $target must reassign HOME", listOf(command), daemon.sent)
            assertTrue(root.ran.isEmpty())
        }
    }

    @Test fun ensureHomeNoopWhenTargetHasNoHomeActivity() {
        val env = FakeSystemEnv(installed = setOf(MIN), homes = emptyList(), default = ActivityRef("android", "R"))
        val (c, root, d) = sc(env, daemon = null, su = true)
        c.ensureDashboardHome(MIN)
        assertTrue("target has no HOME activity → nothing set", root.ran.isEmpty() && d.sent.isEmpty())
    }

    @Test fun ensureHomeLogsMissingCompanionAliasOnceUntilItsStateChanges() {
        val env = FakeSystemEnv(installed = setOf(MIN), homes = emptyList(), default = ActivityRef("android", "R"))
        val warnings = mutableListOf<String>()
        val (controller, root, daemon) = sc(env, daemon = null, homeWarnings = warnings)

        controller.ensureDashboardHome(MIN)
        controller.ensureDashboardHome(MIN)
        assertEquals(listOf("ensureHome: $MIN has no HOME activity"), warnings)
        assertTrue("a disabled alias never receives Home", root.ran.isEmpty() && daemon.sent.isEmpty())

        env.homes = listOf(ActivityRef(MIN, "Home"))
        controller.ensureDashboardHome(MIN)
        env.homes = emptyList()
        controller.ensureDashboardHome(MIN)
        assertEquals("the warning returns after the alias state changes", 2, warnings.size)
    }

    // ---------- launchHome ----------
    @Test fun launchHomeUsesDashboardLaunchComponent() {
        val env = FakeSystemEnv(installed = setOf(MIN), launchers = mapOf(MIN to "$MIN/.Main"))
        val (c, root, _) = sc(env, daemon = null, su = true)
        c.launchHome(MIN)
        assertTrue(root.ran.contains("am start -n $MIN/.Main"))
    }

    @Test fun companionLaunchAndReloadReturnToThisPanelsHome() {
        val events = mutableListOf<String>()
        val home = "/lovelace/this-panel"
        val env = FakeSystemEnv(installed = setOf(MIN), launchers = mapOf(MIN to "$MIN/.Main"))
        val controller = SystemController(
            env, FakeRootShell(), FakeDaemon(replies = mapOf("RELOAD $MIN" to "OK")),
            homeDashboard = { home },
            onCompanionHome = { pkg, path -> events += "$pkg$path" },
        )

        controller.launchHome(MIN) // boot and foreground return use this path
        controller.reloadDashboard(MIN) // navbar and remote reload use this path

        assertEquals(listOf("$MIN$home", "$MIN$home"), events)
    }

    @Test fun companionWithoutPanelHomeKeepsItsCurrentLaunchBehavior() {
        val events = mutableListOf<String>()
        val env = FakeSystemEnv(installed = setOf(MIN), launchers = mapOf(MIN to "$MIN/.Main"))
        val controller = SystemController(
            env, FakeRootShell(), FakeDaemon(replies = mapOf("RELOAD $MIN" to "OK")),
            homeDashboard = { "" },
            onCompanionHome = { pkg, path -> events += "$pkg$path" },
        )

        controller.launchHome(MIN)
        controller.reloadDashboard(MIN)

        assertTrue(events.isEmpty())
    }

    @Test fun foregroundHomeReturnUsesThePrivilegedOwnActivity() {
        val component = "$OWN/io.panelassistant.android.CompanionHomeReturnActivity"
        val (controller, _, daemon) = sc(FakeSystemEnv(), daemon = mapOf("START $component" to "OK"))

        assertTrue(controller.launchCompanionHomeReturn())
        assertEquals(listOf("START $component"), daemon.sent)

        val denied = sc(FakeSystemEnv(), daemon = mapOf("START $component" to "BUSY"))
        assertFalse(denied.first.launchCompanionHomeReturn())
        assertEquals(listOf("START $component"), denied.third.sent)

        val rootless = FakeSystemEnv()
        val fallback = sc(rootless, daemon = emptyMap(), su = false)
        assertTrue(fallback.first.launchCompanionHomeReturn())
        assertEquals(listOf(component), rootless.directStarts)
    }

    @Test fun failedOrBusyCompanionLaunchNeverRequestsHomeNavigation() {
        val events = mutableListOf<String>()
        val env = FakeSystemEnv(installed = setOf(MIN), launchers = mapOf(MIN to "$MIN/.Main"))
        val controller = SystemController(
            env, FakeRootShell(), FakeDaemon(replies = mapOf(
                "START $MIN/.Main" to "BUSY", "RELOAD $MIN" to "BUSY",
            )),
            homeDashboard = { "/lovelace/this-panel" },
            onCompanionHome = { pkg, path -> events += "$pkg$path" },
        )

        controller.launchHome(MIN)
        assertFalse(controller.reloadDashboard(MIN))

        assertTrue(events.isEmpty())
    }

    @Test fun builtinReloadDropsAnUnconsumedNavigateAndReturnsHome() {
        val (controller, _, _) = sc(FakeSystemEnv())
        BuiltinDashboard.navPath = "/lovelace/old-view"

        assertTrue(controller.reloadDashboard(BUILTIN))

        assertNull("the pending Navigate must not override home on reload", BuiltinDashboard.navPath)
        assertTrue(BuiltinDashboard.consumeReloadRequest())
    }

    @Test fun bothCompanionVariantsCanBeForegroundedThroughTheirOwnLaunchComponents() {
        listOf(FULL, MIN).forEach { packageName ->
            val component = "$packageName/.Main"
            val env = FakeSystemEnv(
                installed = setOf(FULL, MIN),
                launchers = mapOf(FULL to "$FULL/.Main", MIN to "$MIN/.Main"),
            )
            val (controller, root, _) = sc(env, daemon = null, su = true)

            controller.launchHome(packageName)

            assertEquals("foreground $packageName", listOf("am start -n $component"), root.ran)
        }
    }

    @Test fun launchHomeBusyNeverFallsBackToSuOrDirectStart() {
        val component = "$MIN/.Main"
        val env = FakeSystemEnv(installed = setOf(MIN), launchers = mapOf(MIN to component))
        val (c, root, d) = sc(env, daemon = mapOf("START $component" to "BUSY"), su = true)
        c.launchHome(MIN)
        assertEquals(listOf("START $component"), d.sent)
        assertTrue(root.ran.isEmpty())
        assertTrue(env.directStarts.isEmpty())
    }

    @Test fun launchHomeBlankAutoUsesBuiltin() {
        val env = FakeSystemEnv(default = ActivityRef(VENDOR, "L")) // blank auto ignores launcher default
        val (c, root, _) = sc(env, daemon = null, su = true)
        c.launchHome("")
        assertEquals(listOf("am start -n $OWN/io.panelassistant.android.DashboardActivity"), root.ran)
    }

    @Test fun launchHomeBlankAutoUsesBuiltinEvenWhenForeignRendererIsInstalled() {
        val env = FakeSystemEnv(
            installed = setOf(MIN),
            launchers = mapOf(MIN to "$MIN/.Main"),
        )
        val (c, root, _) = sc(env, daemon = null, su = true)

        c.launchHome("")

        assertEquals("Auto retains the built-in renderer authority", listOf("am start -n $OWN/io.panelassistant.android.DashboardActivity"), root.ran)
        assertFalse("Auto must not silently select the installed foreign renderer", root.ran.any { it.contains(MIN) })
    }

    @Test fun launchHomeFallsToDirectStartWhenPrivilegedFails() {
        // Non-builtin home launch shares the single launch mechanism: when neither daemon nor su can
        // start the resolved dashboard component, it degrades to a direct (pre-BAL) start.
        val env = FakeSystemEnv(installed = setOf(MIN), launchers = mapOf(MIN to "$MIN/.Main"))
        val (c, _, _) = sc(env, daemon = null, su = false) // no daemon, su run fails
        c.launchHome(MIN)
        assertTrue("direct-start fallback used, got ${env.directStarts}", env.directStarts.contains("$MIN/.Main"))
    }

    @Test fun launchHomeBlankAutoUsesBuiltinWithoutAnyResolvedForeignTarget() {
        val env = FakeSystemEnv() // nothing installed, no default
        val (c, root, _) = sc(env, daemon = null)
        c.launchHome("")
        assertEquals(listOf("am start -n $OWN/io.panelassistant.android.DashboardActivity"), root.ran)
        assertTrue(env.directStarts.isEmpty())
    }

    // ---------- built-in renderer (dashboard_package = "builtin") ----------
    // The built-in renderer is ha-paneld's own DashboardActivity. resolveDashboard passes the sentinel
    // through, and dashboardState/launchHome/reloadDashboard/ensureDashboardHome special-case it so the
    // kiosk return-loop + watchdog drive it with no root/daemon probe and no foreign package.

    @Test fun builtinDashboardStateFromForegroundFlag() {
        // FG when our activity is resumed, BG otherwise (DEAD only while crash-latched — tested below).
        // No daemon/su probe is consulted.
        val env = FakeSystemEnv()
        assertEquals(AppState.FG, sc(env, daemon = null, builtinForeground = true).first.dashboardState(BUILTIN))
        assertEquals(AppState.BG, sc(env, daemon = null, builtinForeground = false).first.dashboardState(BUILTIN))
    }

    @Test fun builtinDashboardStateIgnoresDaemon() {
        // Even with a daemon available, the built-in path uses the lifecycle flag, not APPSTATE.
        val (c, _, d) = sc(FakeSystemEnv(), daemon = mapOf("APPSTATE $BUILTIN" to "DEAD"), builtinForeground = true)
        assertEquals(AppState.FG, c.dashboardState(BUILTIN))
        assertTrue("no APPSTATE probe for builtin", d.sent.isEmpty())
    }

    @Test fun builtinLaunchHomeStartsDashboardActivity() {
        val (c, root, _) = sc(FakeSystemEnv(), daemon = null, su = true)
        c.launchHome(BUILTIN)
        assertTrue("started our DashboardActivity", root.ran.contains("am start -n $OWN/io.panelassistant.android.DashboardActivity"))
    }

    @Test fun recoveryRoutingUsesTheSameBuiltinResolutionAsLaunches() {
        val (c, _, _) = sc(FakeSystemEnv())
        assertTrue(c.isBuiltinDashboardTarget(BUILTIN))
        assertTrue(c.isBuiltinDashboardTarget(OWN))
        assertFalse(c.isBuiltinDashboardTarget(MIN))
    }

    @Test fun builtinLaunchHomeFallsToDirectStart() {
        val env = FakeSystemEnv()
        val (c, _, _) = sc(env, daemon = null, su = false) // no daemon, su fails
        c.launchHome(BUILTIN)
        assertTrue("direct-start fallback", env.directStarts.contains("$OWN/io.panelassistant.android.DashboardActivity"))
    }

    @Test fun builtinReloadRelaunchesActivity() {
        // No force-stop / monkey for builtin — a singleTask relaunch is the reload (onNewIntent).
        val (c, root, d) = sc(FakeSystemEnv(), daemon = mapOf("RELOAD $BUILTIN" to "OK"))
        assertTrue(c.reloadDashboard(BUILTIN))
        assertTrue("relaunch via START", d.sent.contains("START $OWN/io.panelassistant.android.DashboardActivity"))
        assertFalse("no RELOAD force-stop path", d.sent.contains("RELOAD $BUILTIN"))
    }

    @Test fun builtinReloadReportsHelperBusyWithoutBypassingIt() {
        val env = FakeSystemEnv()
        val (c, root, d) = sc(env, daemon = mapOf("START $OWN/io.panelassistant.android.DashboardActivity" to "BUSY"))

        assertFalse(c.reloadDashboard(BUILTIN, "manual retry"))
        assertEquals(listOf("START $OWN/io.panelassistant.android.DashboardActivity"), d.sent)
        assertTrue(root.ran.isEmpty())
        assertTrue(env.directStarts.isEmpty())
        assertFalse(BuiltinDashboard.consumeReloadRequest())
        assertEquals("", BuiltinDashboard.consumeReloadReason())
    }

    @Test fun builtinReloadReportsFailedDirectStart() {
        val env = FakeSystemEnv(directStartSucceeds = false)
        val (c, root, d) = sc(env, daemon = emptyMap(), su = false)

        assertFalse(c.reloadDashboard(BUILTIN, "manual retry"))
        assertEquals(listOf("START $OWN/io.panelassistant.android.DashboardActivity"), d.sent)
        assertTrue(root.ran.contains("am start -n $OWN/io.panelassistant.android.DashboardActivity"))
        assertEquals(listOf("$OWN/io.panelassistant.android.DashboardActivity"), env.directStarts)
        assertFalse(BuiltinDashboard.consumeReloadRequest())
        assertEquals("", BuiltinDashboard.consumeReloadReason())
    }

    @Test fun builtinEnsureHomeSetsDashboardActivityFromResolver() {
        val env = FakeSystemEnv(homes = listOf(DASH_HOME), default = ActivityRef("android", "Resolver"))
        val (c, root, _) = sc(env, daemon = null, su = true)
        c.ensureDashboardHome(BUILTIN)
        assertTrue("set home to DashboardActivity", root.ran.contains("cmd package set-home-activity ${DASH_HOME.component}"))
    }

    @Test fun builtinEnsureHomeNoopWhenAlreadyDefault() {
        val env = FakeSystemEnv(homes = listOf(DASH_HOME), default = DASH_HOME)
        val (c, root, d) = sc(env, daemon = null, su = true)
        c.ensureDashboardHome(BUILTIN)
        assertTrue("already our home → nothing set", root.ran.isEmpty() && d.sent.isEmpty())
    }

    @Test fun builtinEnsureHomeRespectsThirdPartyLauncher() {
        val env = FakeSystemEnv(homes = listOf(DASH_HOME), default = ActivityRef("com.thirdparty.home", "L"))
        val (c, root, d) = sc(env, daemon = null, su = true)
        c.ensureDashboardHome(BUILTIN)
        assertTrue("a deliberate 3rd-party home is left alone", root.ran.isEmpty() && d.sent.isEmpty())
    }

    @Test fun builtinEnsureHomeReclaimsFromCompanion() {
        // ensureDashboardHome itself set the Companion as default home on every pre-0.9 panel — switching
        // to the built-in renderer must be able to take HOME back from it (it is NOT a third-party choice).
        val env = FakeSystemEnv(homes = listOf(DASH_HOME), default = ActivityRef(MIN, "Home"))
        val (c, root, _) = sc(env, daemon = null, su = true)
        c.ensureDashboardHome(BUILTIN)
        assertTrue("reclaimed home from the Companion", root.ran.contains("cmd package set-home-activity ${DASH_HOME.component}"))
    }

    @Test fun builtinReloadWithOwnPackageNameRoutesBuiltin() {
        // The navbar resolves the renderer to our own package name; the foreign-app path would
        // `am force-stop` ha-paneld itself (killing service+MQTT+web UI). Own package = builtin.
        val (c, root, d) = sc(FakeSystemEnv(), daemon = mapOf("START $OWN/io.panelassistant.android.DashboardActivity" to "OK"))
        c.reloadDashboard(OWN)
        assertTrue("relaunch via START", d.sent.contains("START $OWN/io.panelassistant.android.DashboardActivity"))
        assertFalse("no force-stop of ourselves", d.sent.any { it.startsWith("RELOAD") } || root.ran.any { it.contains("force-stop") })
    }

    // ---------- renderer crash latch (process-global budget) ----------

    private fun exhaustRebuildBudget() {
        repeat(BuiltinDashboard.MAX_REBUILDS) { assertTrue(BuiltinDashboard.consumeRebuildBudget(0L)) }
        assertFalse("budget exhausted → latch", BuiltinDashboard.consumeRebuildBudget(0L))
    }

    @Test fun builtinStateDeadWhileCrashLatched() {
        exhaustRebuildBudget()
        // DEAD hands the situation to the watchdog's crash-loop backoff; the kiosk loop ignores DEAD.
        assertEquals(AppState.DEAD, sc(FakeSystemEnv(), daemon = null, builtinForeground = true).first.dashboardState(BUILTIN))
    }

    @Test fun builtinLaunchRefusedWhileCrashLatched() {
        exhaustRebuildBudget()
        val env = FakeSystemEnv()
        val (c, root, d) = sc(env, daemon = emptyMap())
        c.launchHome(BUILTIN)
        assertTrue("no relaunch while latched", root.ran.isEmpty() && d.sent.isEmpty() && env.directStarts.isEmpty())
    }

    @Test fun builtinExplicitReloadClearsCrashLatch() {
        exhaustRebuildBudget()
        val (c, _, d) = sc(FakeSystemEnv(), daemon = mapOf("START $OWN/io.panelassistant.android.DashboardActivity" to "OK"))
        c.reloadDashboard(BUILTIN) // deliberate reload = retry consent
        assertTrue("latch cleared, relaunch proceeds", d.sent.contains("START $OWN/io.panelassistant.android.DashboardActivity"))
        assertTrue("reload intent flagged for onNewIntent", BuiltinDashboard.consumeReloadRequest())
        assertEquals("fresh budget after clear", true, BuiltinDashboard.consumeRebuildBudget(0L))
    }

    @Test fun rebuildBudgetWindowResets() {
        repeat(BuiltinDashboard.MAX_REBUILDS) { assertTrue(BuiltinDashboard.consumeRebuildBudget(0L)) }
        // Past the window the counter resets instead of latching — occasional crashes are fine.
        assertTrue(BuiltinDashboard.consumeRebuildBudget(BuiltinDashboard.REBUILD_WINDOW_MS + 1))
        assertFalse("latch not engaged by spaced crashes", BuiltinDashboard.rendererLatched(BuiltinDashboard.REBUILD_WINDOW_MS + 1))
    }

    @Test fun rendererLatchExpires() {
        exhaustRebuildBudget()
        assertTrue(BuiltinDashboard.rendererLatched(BuiltinDashboard.RENDERER_LATCH_MS - 1))
        assertFalse("latch expires → automatic relaunches resume", BuiltinDashboard.rendererLatched(BuiltinDashboard.RENDERER_LATCH_MS))
    }

    // ---------- one-shot navigate + reload-intent flags ----------

    @Test fun navPathConsumedOnce() {
        BuiltinDashboard.navPath = "rooms/main"
        assertEquals("rooms/main", BuiltinDashboard.consumeNavPath())
        assertNull("navigate is one-shot — later rebuilds return to home", BuiltinDashboard.consumeNavPath())
    }

    @Test fun reloadRequestConsumedOnce() {
        assertFalse("no reload pending by default (kiosk snap-back = foreground only)", BuiltinDashboard.consumeReloadRequest())
        BuiltinDashboard.requestExplicitReload()
        assertTrue(BuiltinDashboard.consumeReloadRequest())
        assertFalse(BuiltinDashboard.consumeReloadRequest())
    }

    // ---------- identity migration: starting the other panel-app id ----------

    private val SUCCESSOR_MAIN = "io.panelassistant.android/.MainActivity"

    @Test fun launchPanelAppStartsTheSuccessorByItsFullyQualifiedActivity() {
        val (c, root, d) = sc(FakeSystemEnv(), daemon = mapOf("START $SUCCESSOR_MAIN" to "OK"))
        assertTrue(c.launchPanelApp("io.panelassistant.android"))
        assertEquals(listOf("START $SUCCESSOR_MAIN"), d.sent)
        assertTrue(root.ran.isEmpty())
    }

    @Test fun launchPanelAppFallsBackToSuAndReportsAFailedStart() {
        val (viaSu, suRoot, _) = sc(FakeSystemEnv(), daemon = null, su = true)
        assertTrue(viaSu.launchPanelApp("io.panelassistant.android"))
        assertEquals(listOf("am start -n $SUCCESSOR_MAIN"), suRoot.ran)

        val env = FakeSystemEnv()
        val (failed, _, _) = sc(env, daemon = null, su = false)
        assertFalse(failed.launchPanelApp("io.panelassistant.android"))
        assertTrue("never a direct start of another package", env.directStarts.isEmpty())
    }

    @Test fun launchPanelAppRefusesItselfAndEveryOtherPackage() {
        val (c, root, d) = sc(FakeSystemEnv(), daemon = emptyMap())
        assertFalse(c.launchPanelApp(OWN))
        assertFalse(c.launchPanelApp(MIN))
        assertTrue(root.ran.isEmpty() && d.sent.isEmpty())
    }

    @Test fun aHelperThatIsBusyBlocksTheSuccessorStartWithoutAnSuBypass() {
        val (c, root, _) = sc(FakeSystemEnv(), daemon = mapOf("START $SUCCESSOR_MAIN" to "BUSY"))
        assertFalse(c.launchPanelApp("io.panelassistant.android"))
        assertTrue(root.ran.isEmpty())
    }
}
