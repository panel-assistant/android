package io.panelassistant.android.http

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.panelassistant.android.control.ZigbeeHealthSnapshot
import io.panelassistant.android.control.ZigbeeHealthState
import io.panelassistant.android.storage.StorageHealthSeverity
import io.panelassistant.android.storage.StorageHealthSnapshot
import io.panelassistant.android.storage.StorageQuickCheck
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The `:8888` advisories as the server ships them: notice rows in a fixed order on the dashboard, and
 *  `warning_severities` aligned with `warnings` on the status endpoint. */
class NoticeRowsHttpTest {
    private val retiredGlyphs = listOf("⛔", "⚠", "ℹ", "⬆", "⟳", "🔒", "🏠", "👋")

    @Test fun `dashboard advisories render as notice rows in the established order`() {
        // A density route that can set density, so the off-100 zoom row is due.
        val density = io.panelassistant.android.control.DensityController(
            false,
            io.panelassistant.android.control.FakeRootShell(available = false, runResult = false),
            io.panelassistant.android.control.FakeDaemon(available = false),
            io.panelassistant.android.control.FakeAppSizing(),
        )
        PaneldServerHttpFixture(density = density).use { fixture ->
            fixture.enablePages()
            fixture.enableWarmDashboard()
            fixture.config.setDashboardZoom(125)
            fixture.server.set("storageHealth", { storage(StorageHealthSeverity.CRITICAL) })
            fixture.server.set("radioStatus", { ZigbeeHealthSnapshot(state = ZigbeeHealthState.RUNAWAY, joined = false) })
            testApplication {
                application { fixture.server.mount(this) }
                val response = client.get("/api/v1/info?lang=en") { header(HttpHeaders.Cookie, "wiz_escape=1") }
                assertEquals(HttpStatusCode.OK, response.status)
                val banners = JSONObject(response.bodyAsText()).getString("banners")
                val rows = rows(banners)
                assertEquals(
                    listOf(
                        "action_needed" to "Storage pressure: critical",
                        "action_needed" to "Zigbee gateway is runaway",
                        "warning" to esc(formattedString(englishCatalogue, "dashboard.banner.zoom.title", "zoom" to "125")),
                    ),
                    rows,
                )
                assertFalse(banners.contains("class=\"setup"))
                retiredGlyphs.forEach { assertFalse("retired glyph $it in $banners", banners.contains(it)) }
            }
        }
    }

    /** Every row [DashboardAdvisories.render] can draw, in render order. Two rows pairs cannot co-occur, so
     *  two renders pin the whole sequence: the Companion internal-URL row needs a Companion renderer while
     *  the zoom row needs the built-in one. */
    @Test fun `dashboard advisories keep their full render order`() {
        PaneldServerHttpFixture().use { fixture ->
            fixture.enablePages()
            fixture.enableWarmDashboard()
            fixture.config.setWakeOnWave(true)
            val owner = io.panelassistant.android.control.BuiltinDashboard.acquireActivityOwner()
            io.panelassistant.android.control.BuiltinDashboard.setActivityAuthLatched(owner, true)
            try {
                val webViewOld = PageHealth.Inputs(PanelInfo.WebViewStatus("Chrome 60", tooOld = true), hasRenderer = true, homeAssistantConfigured = false)
                val builtin = fixture.advisories(io.panelassistant.android.PanelStatus.DashboardRecoveryState.BUILTIN_RENDERER)
                fixture.config.setDashboardZoom(125)
                val first = rows(builtin.render(fixture.snapshot("disabled", densityBase = 160), webViewOld, englishCatalogue))
                assertEquals(
                    listOf(
                        "action_needed" to "Storage pressure: critical",
                        POWER,
                        "action_needed" to "Zigbee gateway is runaway",
                        AUTH,
                        "action_needed" to esc(englishCatalogue.get("runtime.renderer_recovery.builtin")),
                        "warning" to esc(formattedString(englishCatalogue, "dashboard.banner.zoom.title", "zoom" to "125")),
                        WEBVIEW,
                        TERMUX,
                        PROXIMITY,
                        SIGN_IN,
                    ),
                    first,
                )

                fixture.config.setDashboardPackage(io.panelassistant.android.util.CompanionInstaller.SUPPORTED_PACKAGES.first())
                fixture.companionCache.set(io.panelassistant.android.control.CompanionDb.ServerObservation(
                    io.panelassistant.android.control.CompanionDb.Probe.SUCCEEDED, null,
                    io.panelassistant.android.control.CompanionDb.UrlStatus(needsRepair = true, affected = 1),
                ))
                val external = fixture.advisories(io.panelassistant.android.PanelStatus.DashboardRecoveryState.EXTERNAL_RENDERER)
                val second = rows(external.render(fixture.snapshot("connecting", densityBase = 160), webViewOld, englishCatalogue))
                assertEquals(
                    listOf(
                        "action_needed" to "Storage pressure: critical",
                        POWER,
                        "action_needed" to "Zigbee gateway is runaway",
                        AUTH,
                        "action_needed" to "Dashboard app is crash-looping",
                        "action_needed" to esc(englishCatalogue.get("dashboard.banner.companion_url.title")),
                        WEBVIEW,
                        TERMUX,
                        PROXIMITY,
                        SIGN_IN,
                    ),
                    second,
                )
            } finally {
                io.panelassistant.android.control.BuiltinDashboard.releaseActivityOwner(owner)
            }
        }
    }

    @Test fun `install and configure pages draw every advisory as a glyph-free notice row`() {
        PaneldServerHttpFixture().use { fixture ->
            fixture.enablePages()
            fixture.enableInstallPage(root = false)
            fixture.server.set("storageHealth", { storage(StorageHealthSeverity.WARNING) })
            fixture.server.set("radioStatus", { ZigbeeHealthSnapshot(state = ZigbeeHealthState.RUNAWAY, joined = false) })
            testApplication {
                application { fixture.server.mount(this) }
                for (page in listOf("/install?lang=en", "/configure?lang=en")) {
                    val html = client.get(page) { header(HttpHeaders.Cookie, "wiz_escape=1") }.bodyAsText()
                    assertFalse(page, html.contains("class=\"setup"))
                    assertTrue(page, html.contains("""<div class="notice" data-severity="update" id="verbar" style="display:none">"""))
                    assertTrue(page, html.contains("""<div id="halifebar" class="notice" style="display:none"></div>"""))
                    assertTrue(page, html.contains("""<div id="hanetbar" class="notice" style="display:none"></div>"""))
                    val rows = Regex("""<div class="notice"[^>]*>.*?</div>""").findAll(html).map { it.value }.toList()
                    assertTrue(page, rows.isNotEmpty())
                    rows.forEach { row -> retiredGlyphs.forEach { assertFalse("$page: $it in $row", row.contains(it)) } }
                }
                val install = client.get("/install?lang=en") { header(HttpHeaders.Cookie, "wiz_escape=1") }.bodyAsText()
                assertTrue(install.contains("""data-severity="action_needed"><span class="notice-pill">Action needed</span><span class="notice-text"><b class="notice-title">Zigbee gateway is runaway</b>"""))
            }
        }
    }

    @Test fun `status warning severities align one to one with warnings`() {
        PaneldServerHttpFixture().use { fixture ->
            fixture.useManagementStatus {}
            fixture.values["zigbee_router_configured"] = true
            fixture.values["zigbee_router"] = true
            fixture.server.set("storageHealth", { storage(StorageHealthSeverity.CRITICAL) })
            fixture.server.set("radioStatus", { ZigbeeHealthSnapshot(state = ZigbeeHealthState.DEGRADED_UNJOINED, joined = false) })
            testApplication {
                application { fixture.mount(this) }
                val json = JSONObject(client.get("/api/v1/status").bodyAsText())
                val warnings = json.getJSONArray("warnings")
                val severities = json.getJSONArray("warning_severities")
                assertEquals(warnings.length(), severities.length())
                val pairs = (0 until warnings.length()).associate { warnings.getString(it) to severities.getString(it) }
                assertEquals("action_needed", pairs.entries.single { it.key.startsWith("Storage pressure: critical — ") }.value)
                assertEquals("warning", pairs.entries.single { it.key.startsWith("Zigbee router is enabled but not joined — ") }.value)
                pairs.keys.forEach { warning -> retiredGlyphs.forEach { assertFalse(warning, warning.startsWith(it)) } }
            }
        }
    }

    /** The WebView-too-old warning links this panel model's update steps on all three surfaces — the
     *  Dashboard banner, the Install tab notice and the status warning — never the hardware index. */
    @Test fun `webview too old warnings link the panel model's update steps`() {
        val expected = mapOf(
            "tpa10" to "https://panel-assistant.io/go/docs?page=hardware/tpa10#webview-update-this-first",
            "shelly-wall-display" to "https://panel-assistant.io/go/docs?page=hardware/shelly-wall-display#webview",
            "nspanel-pro" to "https://panel-assistant.io/go/update-webview",
        )
        val finding = HealthAudit.Finding(HealthAudit.Kind.WEBVIEW_OLD, "Chrome 60")
        val title = englishCatalogue.get("dashboard.banner.webview_old.title")
        expected.forEach { (profileId, url) ->
            val link = """<a href="$url" target="_blank" rel="noopener">"""
            PaneldServerHttpFixture().use { fixture ->
                fixture.enablePages()
                fixture.enableWarmDashboard()
                val webViewOld = PageHealth.Inputs(PanelInfo.WebViewStatus("Chrome 60", tooOld = true), hasRenderer = true, homeAssistantConfigured = true)
                val banners = fixture.advisories(io.panelassistant.android.PanelStatus.DashboardRecoveryState.NONE, profileId)
                    .render(fixture.snapshot("disabled", densityBase = 160), webViewOld, englishCatalogue)
                val banner = banners.split("""<div class="notice" """).single { it.contains(esc(title)) }
                assertWebViewLink("$profileId dashboard", banner, link)
                assertFalse("$profileId dashboard keeps developer small print: $banner", banner.contains("<small>"))
                val install = installWarning(finding, canInstallCompanion = false, strings = englishCatalogue) { profileId }
                assertWebViewLink("$profileId install", install, link)
                val status = statusWarnings(fixture, listOf(finding), profileId).single { it.contains("System WebView is too old") }
                assertWebViewLink("$profileId status", status, link)
            }
        }
    }

    private fun assertWebViewLink(surface: String, html: String, link: String) {
        assertFalse("$surface links the hardware index: $html", html.contains("hardware/readme"))
        assertTrue("$surface lacks $link: $html", html.contains(link))
    }

    /** GET /api/v1/status's warnings for [findings], composed by the production status projection. */
    private fun statusWarnings(fixture: PaneldServerHttpFixture, findings: List<HealthAudit.Finding>, profileId: String): List<String> {
        val power = io.panelassistant.android.control.PowerSafetyAdvisory(
            io.panelassistant.android.control.PowerSafetyAssessment(
                io.panelassistant.android.control.PowerRiskLevel.SAFE,
                io.panelassistant.android.control.PowerSafetyObservation(true, true, false, false, true, 60_000, true, 1, 1, false, true, "none"),
                emptyList(), "safe", "none",
            ),
            io.panelassistant.android.control.PowerRepairCapability.APP_ONLY,
            io.panelassistant.android.control.PowerSafetyAdvisoryAction.NONE, null, false,
        )
        val json = JSONObject(
            managementStatusJson(
                fixture.config, fixture.snapshot("disabled", densityBase = 160), io.panelassistant.android.control.CompanionDb.ServerObservation.EMPTY, power,
                null, HealthAudit.storage(StorageHealthSnapshot.UNCHECKED),
                StatusHealth(emptyList(), findings, { io.panelassistant.android.PanelStatus.DashboardRecoveryState.NONE }, { null to null }, { null }),
                renderer = { io.panelassistant.android.RendererAdmissionPresentation.of(io.panelassistant.android.RendererMode.NONE, "", "auto", null, 0, 0, null, 0) },
                camera = { io.panelassistant.android.camera.CameraPresentation.absent() },
                databaseObservationNonce = null,
                permissions = { emptyMap() },
                english = englishCatalogue,
                profileId = { profileId },
            ),
        )
        val warnings = json.getJSONArray("warnings")
        return (0 until warnings.length()).map { warnings.getString(it) }
    }

    @Test fun `status without warnings still carries an empty severity list`() {
        PaneldServerHttpFixture().use { fixture ->
            fixture.useManagementStatus {}
            testApplication {
                application { fixture.mount(this) }
                val json = JSONObject(client.get("/api/v1/status").bodyAsText())
                assertTrue(json.has("warning_severities"))
                assertEquals(json.getJSONArray("warnings").length(), json.getJSONArray("warning_severities").length())
            }
        }
    }

    /** (data-severity, title) per row; an untitled row reports its detail text with tags stripped. */
    private fun rows(banners: String) = banners.split("""<div class="notice" """).drop(1).map { row ->
        val severity = Regex("""^data-severity="([a-z_]+)"""").find(row)!!.groupValues[1]
        val title = Regex("""<b class="notice-title">(.*?)</b>""").find(row)?.groupValues?.get(1)
            ?: Regex("""<span class="notice-detail">(.*?)</span></span>""").find(row)!!.groupValues[1].replace(Regex("<[^>]+>"), "")
        severity to title
    }

    private fun PaneldServerHttpFixture.snapshot(link: String, densityBase: Int?) = ManagementSnapshot(
        mapOf("Link" to link, "System WebView" to "Chrome 60"), emptyMap(), io.panelassistant.android.config.Capabilities(),
        emptyList(), io.panelassistant.android.control.PrivilegedRouteObservation(directSuReady = true, helperRootReady = false),
        200, densityBase, 1.0f, false,
    )

    /** The production class over the fixture's real Config, sensors, observations and setup state; lambdas
     *  stand in for the probes so every row can be lit at once. */
    private fun PaneldServerHttpFixture.advisories(
        recovery: io.panelassistant.android.PanelStatus.DashboardRecoveryState,
        profileId: String = "generic",
    ): DashboardAdvisories {
        fun <T> field(name: String): T = PaneldServer::class.java.getDeclaredField(name).run {
            isAccessible = true
            @Suppress("UNCHECKED_CAST") (get(server) as T)
        }
        val observations = field<ManagementObservations>("managementObservations")
        observations.termuxBridgeCache.set(TermuxBridgeProbe.State.RUNNING)
        return DashboardAdvisories(
            config, context, field("sensors"), observations, field("setupState"), PageHealth(context, config),
            haSignInNeededForEffectiveDashboard = { true },
            powerSafetyAdvisory = {
                io.panelassistant.android.control.PowerSafetyAdvisory(
                    io.panelassistant.android.control.PowerSafetyAssessment(
                        level = io.panelassistant.android.control.PowerRiskLevel.AT_RISK,
                        observation = io.panelassistant.android.control.PowerSafetyObservation(
                            keepAwakeConfigured = false, wakeLockHeld = false,
                            wifiLockRequired = false, wifiLockHeld = false,
                            preventIdleDimConfigured = false, screenOffTimeoutMs = 30_000,
                            interactive = true, pluggedMask = 1, stayOnWhilePluggedIn = 0,
                            deviceIdleMode = false, ignoringBatteryOptimizations = false,
                            screenOffMechanism = "test",
                        ),
                        reasonCodes = emptyList(), summary = "at risk", action = "repair",
                    ),
                    io.panelassistant.android.control.PowerRepairCapability.APP_ONLY,
                    io.panelassistant.android.control.PowerSafetyAdvisoryAction.NONE, null, false,
                )
            },
            storageHealth = { storage(StorageHealthSeverity.CRITICAL) },
            catalogueLoader = { testCatalogue },
            radioStatus = { ZigbeeHealthSnapshot(state = ZigbeeHealthState.RUNAWAY, joined = false) },
            dashboardRecoveryState = { recovery },
            appSizingReady = { true },
            profileId = { profileId },
        )
    }

    private companion object {
        val POWER = "action_needed" to "Panel power safety: at risk"
        val AUTH = "action_needed" to esc(englishCatalogue.get("dashboard.banner.auth_rejected.title"))
        val WEBVIEW = "action_needed" to esc(englishCatalogue.get("dashboard.banner.webview_old.title"))
        val TERMUX = "warning" to "A Zigbee bridge is running in Termux on this panel. It may interfere with the panel's Zigbee gateway. Review the Termux bridge before enabling Zigbee gateway features."
        val PROXIMITY = "warning" to esc(englishCatalogue.get("configure.proximity.setup.unavailable"))
        val SIGN_IN = "warning" to esc(englishCatalogue.get("dashboard.banner.ha_sign_in.title"))
    }

    private fun PaneldServer.set(name: String, value: Any) {
        PaneldServer::class.java.getDeclaredField(name).apply { isAccessible = true }.set(this, value)
    }

    private fun storage(severity: StorageHealthSeverity) = StorageHealthSnapshot(
        severity = severity,
        pressureSeverity = severity,
        checkedAtMillis = 1_700_000_000_000L,
        usableBytes = 30L * 1024 * 1024,
        totalBytes = 4L * 1024 * 1024 * 1024,
        usedPercent = 99.3,
        mainDatabaseBytes = 20L * 1024 * 1024,
        walBytes = 3L * 1024 * 1024,
        sidecarBytes = 64L * 1024,
        pageSizeBytes = 4_096L,
        pageCount = 1_234L,
        freelistCount = 45L,
        schemaVersion = 11,
        quickCheck = StorageQuickCheck.OK,
        databaseFailureKind = null,
        databaseFailureOperation = null,
    )
}
