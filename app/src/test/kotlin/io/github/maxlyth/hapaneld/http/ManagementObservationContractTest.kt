package io.github.maxlyth.hapaneld.http

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagementObservationContractTest {
    private fun source(path: String): String =
        File("src/main/kotlin/io/github/maxlyth/hapaneld/$path").readText()

    @Test fun dashboardSnapshotCollectsOnePrivilegeAndOneServiceProjection() {
        val server = source("http/PaneldServer.kt")
        val routes = server.substring(
            server.indexOf("private fun privilegeObservation()"),
            server.indexOf("private val snapCache"),
        )
        val snapshot = server.substring(
            server.indexOf("private val snapCache"),
            server.indexOf("private val diagCache"),
        )

        assertEquals(1, Regex("Su\\.availableCachedIsolated\\(\\)").findAll(routes).count())
        assertEquals(1, Regex("HelperClient::available").findAll(routes).count())
        assertEquals(1, Regex("ShizukuBridge::snapshot").findAll(routes).count())
        assertEquals(1, Regex("privilegeObservation\\(\\)").findAll(snapshot).count())
        assertEquals(1, Regex("managementProjection\\(privilege\\)").findAll(snapshot).count())
        assertEquals(1, Regex("density\\.observeSizing\\(privilege\\)").findAll(snapshot).count())
        assertTrue(snapshot.contains("densityCache.getWithSupplier"))
        assertTrue(snapshot.contains("capabilityRows = management.capabilityRows"))
        assertFalse(snapshot.contains("DiagReader.capabilities"))
        assertTrue(snapshot.contains("privilege = privilege"))
        assertFalse(snapshot.contains("val rootOk:"))
        assertFalse(snapshot.contains("val captureOk:"))
        assertFalse(snapshot.contains("val shizukuReady:"))
        assertFalse(snapshot.contains("rootOk()"))
        assertFalse(snapshot.contains("captureOk()"))
        assertFalse(snapshot.contains("shellOk()"))
    }

    @Test fun warmRenderingAndStatusConsumeStoredCapabilityRows() {
        val server = source("http/PaneldServer.kt")
        val service = source("PaneldService.kt")
        val status = server.substring(
            server.indexOf("private fun statusJson()"),
            server.indexOf("private fun statusWarning"),
        )
        val rows = server.substring(
            server.indexOf("private fun capRowsHtml("),
            server.indexOf("private fun rootLockBanner"),
        )

        assertEquals(0, Regex("DiagReader\\.capabilities\\(").findAll(server).count())
        assertEquals(1, Regex("DiagReader\\.capabilities\\(this, profile, privilege\\)")
            .findAll(service).count())
        assertTrue(status.contains("val management = snapStaleOk()"))
        assertTrue(status.contains("management.capabilityRows"))
        assertFalse(status.contains("DiagReader.capabilities"))
        assertFalse(rows.contains("DiagReader.capabilities"))
        assertTrue(server.contains("capRowsHtml(s.capabilityRows, strings)"))
        assertTrue(server.contains("s?.let { capRowsHtml(it.capabilityRows, strings) }"))
    }

    @Test fun serviceFactsLiveValuesAndCapabilitiesReuseOneControllerObservation() {
        val service = source("PaneldService.kt")
        val observe = service.substring(
            service.indexOf("private fun observeManagementControllers("),
            service.indexOf("private fun projectLiveValues"),
        )
        val projection = service.substring(
            service.indexOf("private fun managementProjection("),
            service.indexOf("/** Ordered facts"),
        )
        val facts = service.substring(
            service.indexOf("private fun panelInfo("),
            service.indexOf("private fun ledLabel("),
        )

        listOf(
            "cpu.currentTier(allowRootFallback = privilege.directSuReady)",
            "cpu.available(allowRootFallback = privilege.directSuReady)",
            "adb.isActive(allowRootCrossCheck = privilege.directSuReady)",
            "zigbee.observe(includeRole = true, directSuReady = privilege.directSuReady)",
            "relay.count(allowRootProbe = privilege.directSuReady)",
            "relay.ledCount()",
        ).forEach { call -> assertEquals(call, 1, Regex(Regex.escape(call)).findAll(observe).count()) }
        assertTrue(projection.contains("val controllers = observeManagementControllers(privilege)"))
        assertTrue(projection.contains("facts = panelInfo(controllers, diagnostic.rgbLedReady, wifi)"))
        assertTrue(projection.contains("capabilities = capabilitiesSnapshot(privilege, controllers)"))
        assertTrue(projection.contains("capabilityRows = diagnostic.rows"))
        assertFalse(facts.contains("zigbee.status()"))
        assertFalse(facts.contains("relay.count()"))
        assertFalse(facts.contains("cpu.currentTier()"))
        assertFalse(facts.contains("adb.isPersisted()"))
        assertFalse(facts.contains("adb.isActive()"))
        assertFalse(service.contains("led.available()"))
        assertFalse(service.contains("it.name == \"RGB LED\""))
    }

    @Test fun runtimeCardAndDiagnosticsShareBoundedAppDatabaseAndPolicyFacts() {
        val server = source("http/PaneldServer.kt")
        val service = source("PaneldService.kt")
        val diagnostic = source("http/DiagReader.kt")

        assertTrue(service.contains("PanelInfo.databaseSummary(entityLearning.databaseUsage())"))
        assertTrue(service.contains("\"App database\""))
        assertTrue(service.contains("\"Hardened · high-impact remote actions need physical on-panel approval\""))
        assertTrue(service.contains("\"Android dashboard lock\" to if (config.kioskLock) \"on\" else \"off\""))
        assertTrue(service.contains("\"Prevent idle dim\" to preventIdleDimDiagnostic"))
        assertTrue(server.replace(Regex("\\s+"), " ").contains(
            "\"Wi-Fi stability\", HA_NETWORK_FACT, HA_RENDERER_FACT, \"MQTT state\", \"State convergence\", \"Local-state sync\", \"App database\", \"Security mode\", \"Audio playback\"",
        ))
        assertTrue(diagnostic.substring(diagnostic.indexOf("private val PUBLIC_PANEL_FACTS")).contains("\"App database\""))
        assertTrue(diagnostic.substring(diagnostic.indexOf("private val PUBLIC_PANEL_FACTS")).contains("\"Prevent idle dim\""))
    }

    @Test fun mqttCapabilityRecoveryRetainsPositiveStickyZigbeeBackoff() {
        val service = source("PaneldService.kt")
        val sticky = service.substring(
            service.indexOf("private val zigbeePresence = SuccessStickyProbe"),
            service.indexOf("/** This panel's capability snapshot"),
        )
        val capability = service.substring(
            service.indexOf("private fun capabilitiesSnapshot(\n        directSuReady:"),
            service.indexOf("/** Run one destructive operation"),
        )

        assertTrue(sticky.contains("zigbee.observe(includeRole = false)"))
        assertTrue(sticky.contains("initialBackoffMs = 5_000L"))
        assertTrue(sticky.contains("maxBackoffMs = 300_000L"))
        assertTrue(capability.contains("zigbeePresence.get() == true"))
        assertFalse(capability.contains("zigbee.observe(includeRole = false)"))
    }

    @Test fun observationDtosOwnNoLifecycleOrCachingMachinery() {
        val privilege = source("control/PrivilegedRouteObservation.kt")
        val forbidden = listOf("Thread(", "ExecutorService", "CoroutineScope", "Cached<", "retry(", "generation:")

        forbidden.forEach { word -> assertFalse("privilege observation owns $word", privilege.contains(word)) }
        assertTrue(privilege.contains("val rootControlReady: Boolean = directSuReady || helperRootReady"))
        assertTrue(privilege.contains("val typedShellControlReady: Boolean = rootControlReady || shizuku.ready"))
    }

    @Test fun installRenderingReusesOneSnapshotWhileEffectAdmissionRemainsLive() {
        val server = source("http/PaneldServer.kt")
        val install = server.substring(
            server.indexOf("private fun installBody(strings: AppStrings)"),
            server.indexOf("private fun installWarning"),
        )

        assertEquals(1, Regex("snapStaleOk\\(\\)").findAll(install).count())
        assertTrue(install.contains("val root = management.privilege.rootControlReady"))
        assertTrue(install.contains("val installer = management.privilege.typedShellControlReady"))
        assertTrue(install.contains("tameCardHtml(root, strings)"))
        assertTrue(install.contains("val displaySizing = densityCache.peek()"))
        assertTrue(install.contains("displayCardHtml(management.privilege.typedShellControlReady, displaySizing, strings)"))
        assertFalse(install.contains("densityCache.get()"))
        assertFalse(install.contains("rootOk()"))
        assertFalse(install.contains("HelperClient.available()"))
        assertTrue(server.contains("rootAvailable = { rootOk() }"))
    }

    @Test fun healthAndPageFingerprintsNeverOpenPrivilegedRoutes() {
        val server = source("http/PaneldServer.kt")
        val service = source("PaneldService.kt")
        val renderHash = server.substring(
            server.indexOf("private fun renderConfigConcurrencyHash()"),
            server.indexOf("private fun configConcurrencyHash("),
        )
        val configLive = service.substring(
            service.indexOf("private fun currentConfigLiveValues()"),
            service.indexOf("private fun managementProjection("),
        )

        assertEquals(5, Regex("renderConfigConcurrencyHash\\(\\)").findAll(server).count())
        assertTrue(renderHash.contains("configConcurrencyHash(currentValues())"))
        assertFalse(configLive.contains("cpu.currentTier()"))
        assertFalse(configLive.contains("Su."))
        // touch_sound is deliberately absent. A live-map key overrides the persisted registry value on
        // every surface that reports it, and the platform sound-effects flag has other writers, so an
        // observation standing in for the setting let firmware drift queue an apply nobody requested.
        // TouchSoundDriftContractTest owns the full invariant.
        assertFalse(configLive.contains("touchSound"))
        assertTrue(configLive.contains("adb.isPersisted()"))
        assertTrue(configLive.contains("config.zigbeeRouterEnabled"))
        assertFalse(server.contains("cfg=\${configConcurrencyHash()}"))
        assertFalse(server.contains("data-cfg=\"\${configConcurrencyHash()}\""))
    }

    @Test fun companionObservationReusesOneLastKnownPayloadAndNoDuplicateStartupRead() {
        val server = source("http/PaneldServer.kt")
        val service = source("PaneldService.kt")
        val cache = server.substring(
            server.indexOf("private val companionServerCache"),
            server.indexOf("private fun privilegeObservation()"),
        )
        val prewarm = server.substring(server.indexOf("fun prewarm()"), server.indexOf("fun stop()"))

        assertEquals(1, Regex("CompanionDb\\.observeServers\\(appContext, Su\\)").findAll(cache).count())
        assertTrue(cache.contains("CompanionInstaller.installedPkg(appContext) == null"))
        assertTrue(cache.contains("CompanionDb.ServerObservation.UNKNOWN"))
        assertTrue(cache.contains("retainLastKnownServerObservation(companionServerCache.peek(), observed)"))
        assertTrue(prewarm.indexOf("snapCache.get()") < prewarm.indexOf("companionServerCache.get()"))
        assertFalse(server.contains("companionUrlCache"))
        assertFalse(service.contains("CompanionDb.serverUrl(this@PaneldService, Su)"))
    }

    @Test fun companionWarningsRemainScopedToACompanionRenderer() {
        val server = source("http/PaneldServer.kt")
        val companionDb = source("control/CompanionDb.kt")
        val status = server.substring(server.indexOf("private fun statusJson()"), server.indexOf("private fun statusWarning"))
        val adHoc = server.substring(server.indexOf("private fun adHocWarnings("), server.indexOf("// Built-in renderer zoomed"))

        // Both surfaces route the companion internal-URL decision through the one shared, scope-guarded
        // authority instead of each re-deciding renderer scope + repair/probe selection inline.
        listOf(status, adHoc).forEach { warnings ->
            assertTrue(warnings.contains("CompanionDb.warning(config.dashboardPackage, companion, management.privilege.directSuReady)"))
            assertFalse(warnings.contains("CompanionDb.warningApplies(config.dashboardPackage)"))
        }
        // The renderer scope + repair/probe selection live exactly once, in that shared decision.
        val warning = companionDb.substring(companionDb.indexOf("internal fun warning("))
        assertTrue(warning.contains("if (!warningApplies(dashboardPackage)) return null"))
        assertTrue(warning.contains("status.needsRepair -> Warning.NeedsRepair(status.affected)"))
        assertTrue(warning.contains("observation.probe == Probe.FAILED && directSuReady -> Warning.ProbeFailed"))
    }

    @Test fun passivePageRenderingNeverStartsColdHardwareProbes() {
        val server = source("http/PaneldServer.kt")
        val logs = server.substring(server.indexOf("private fun logsBody(strings: AppStrings)"), server.indexOf("private fun fleetBody(strings: AppStrings)"))
        val banners = server.substring(server.indexOf("private fun bannersHtml("), server.indexOf("private fun adHocWarnings("))
        val display = server.substring(server.indexOf("private fun displayCardHtml("), server.indexOf("private fun asset("))

        assertFalse(logs.contains("suCache."))
        assertTrue(logs.contains("strings.get(\"logs.source.system_root_check\")"))
        assertTrue(banners.contains("companionServersForRender()"))
        assertFalse(banners.contains("companionServersStaleOk()"))
        assertFalse(display.contains("densityCache.get()"))
    }

    @Test fun logsToolbarUsesSharedResponsiveControls() {
        val server = source("http/PaneldServer.kt")
        val logs = server.substring(server.indexOf("private fun logsBody(strings: AppStrings)"), server.indexOf("private fun fleetBody(strings: AppStrings)"))
        val css = File("src/main/assets/info.css").readText()

        assertTrue(logs.contains("<div class=\"log-toolbar\">"))
        assertTrue(logs.contains("id=\"lg-src-app\" class=\"pbtn on\""))
        assertTrue(logs.contains("id=\"lg-src-system\" class=\"pbtn\""))
        assertTrue(logs.contains("id=\"lg-filter\" class=\"log-filter\""))
        assertTrue(logs.contains("<span class=\"log-actions\">"))
        assertTrue(logs.indexOf("lg-src-app") < logs.indexOf("lg-level"))
        assertTrue(logs.indexOf("lg-level") < logs.indexOf("lg-filter"))
        assertTrue(logs.indexOf("lg-filter") < logs.indexOf("lg-pause"))
        assertTrue(css.contains(".log-toolbar{display:flex;align-items:center;flex-wrap:wrap;gap:8px"))
        assertTrue(css.contains(".log-actions{margin-left:auto}"))
    }

    @Test fun explicitlyBlockingManagementPagesStayOnTheIoDispatcher() {
        val server = source("http/PaneldServer.kt")
        val installRoute = server.substring(server.indexOf("get(\"/install\")"), server.indexOf("get(\"/fleet\")"))
        val statusRoute = server.substring(server.indexOf("get(\"/status\")"), server.indexOf("post(\"/updates/ignore\")"))

        val compactInstallRoute = installRoute.replace(Regex("\\s+"), " ")
        assertTrue(compactInstallRoute.contains(
            "withContext(Dispatchers.IO) { page(\"install\", strings.get(\"shell.nav.install\"), installBody(strings), strings, call.embedMode()) }",
        ))
        assertTrue(statusRoute.contains("withContext(Dispatchers.IO)"))
        assertTrue(statusRoute.contains("statusJson("))
        assertTrue(statusRoute.indexOf("refreshedStatusStorage(") < statusRoute.indexOf("statusJson("))
    }
}
