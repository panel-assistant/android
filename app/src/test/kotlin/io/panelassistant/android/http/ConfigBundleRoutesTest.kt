package io.panelassistant.android.http

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.ktor.server.application.Application
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.panelassistant.android.HaAuthSnapshot
import io.panelassistant.android.HaCredentialManager
import io.panelassistant.android.LoopbackHa
import io.panelassistant.android.haRouteEpoch
import io.panelassistant.android.LiveSettingRequestOutcome
import io.panelassistant.android.haAuthSnapshot
import io.panelassistant.android.setHaClientId
import io.panelassistant.android.setHaConnection
import io.panelassistant.android.setHaRefreshToken
import io.panelassistant.android.setHaTokenExpiry
import io.panelassistant.android.config.Capabilities
import io.panelassistant.android.config.ConfigBundle
import io.panelassistant.android.config.SettingsRegistry
import io.panelassistant.android.control.SystemController
import io.panelassistant.android.platform.ActivityRef
import io.panelassistant.android.platform.SystemEnv
import io.panelassistant.android.util.BorrowedRendererSettings
import io.panelassistant.android.util.RendererPreparationCoordinator
import io.panelassistant.android.util.RendererPreparationState
import io.panelassistant.android.util.InstallProgress
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigBundleRoutesTest {
    @Test fun malformedStoredIntegersExportAndStrictlyReimportWithoutWriting() {
        for (stored in listOf(-50, 50, 23)) PaneldServerHttpFixture().use { fixture ->
            fixture.values["panel_id"] = "contract_panel"
            fixture.values["camera_fps"] = stored
            fixture.values["friendly_name"] = "Contract panel"
            val before = fixture.values.toMap()
            testApplication {
                application { mountConfigBundleRoutes(fixture) }
                val exported = client.get("/api/v1/config/export")
                assertEquals(HttpStatusCode.OK, exported.status)
                val text = exported.bodyAsText()
                val bundle = requireNotNull(ConfigBundle.parse(text))
                assertEquals(fixture.config.cameraFps.toString(), bundle.values["camera_fps"])
                val preview = client.post("/api/v1/config/import?strict=1&dry_run=1") {
                    setBody(text)
                }
                assertEquals(preview.bodyAsText(), HttpStatusCode.OK, preview.status)
                assertEquals(preview.bodyAsText(), 0, JSONObject(preview.bodyAsText()).getJSONArray("changes").length())
                assertEquals(before, fixture.values)
                // Incoming invalid values are still rejected; only effective reads clamp legacy state.
                val invalid = client.post("/api/v1/config/import?strict=1&dry_run=1") {
                    setBody(ConfigBundle.fromValues(mapOf("camera_fps" to "50")).serialize())
                }
                assertEquals(invalid.bodyAsText(), HttpStatusCode.UnprocessableEntity, invalid.status)
                assertEquals(before, fixture.values)
            }
        }
    }

    @Test fun `fleet import previews portable settings and skips device local and secret ones`() {
        PaneldServerHttpFixture().use { fixture ->
            testApplication {
                application { mountConfigBundleRoutes(fixture) }
                val response = client.post("/api/v1/config/import?mode=fleet&dry_run=1") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    setBody(ConfigBundle.fromValues(mapOf(
                        "touch_sound" to "false",
                        "panel_id" to "fleet_source",
                        "ha_token" to "source-panel-token",
                    )).serialize())
                }
                val text = response.bodyAsText()
                assertEquals(text, HttpStatusCode.OK, response.status)
                val body = JSONObject(text)
                val skipped = body.optJSONArray("skipped") ?: JSONArray()
                val skippedKeys = (0 until skipped.length()).map { skipped.getString(it) }
                val changes = body.optJSONArray("changes") ?: JSONArray()
                val changedKeys = (0 until changes.length()).map { changes.getJSONObject(it).optString("key") }
                assertTrue(text, "panel_id" in skippedKeys && "ha_token" in skippedKeys)
                assertFalse(text, "panel_id" in changedKeys || "ha_token" in changedKeys)
                assertTrue(text, "touch_sound" in changedKeys)
            }
        }
    }

    @Test fun `revision restore refused by another operation is not reported as restored`() {
        PaneldServerHttpFixture().use { fixture ->
            testApplication {
                application { mountConfigBundleRoutes(fixture) }
                val imported = client.post("/api/v1/config/import") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    setBody(ConfigBundle.fromValues(mapOf("friendly_name" to "Imported panel")).serialize())
                }
                assertEquals(imported.bodyAsText(), HttpStatusCode.OK, imported.status)
                assertEquals("Imported panel", fixture.config.friendlyName)
                val revisions = JSONArray(client.get("/api/v1/config/revisions").bodyAsText())
                assertEquals(1, revisions.length())
                val held = requireNotNull(InstallProgress.start("existing owner"))
                try {
                    val restored = client.post("/api/v1/config/revisions/${revisions.getJSONObject(0).getLong("id")}/restore")
                    val text = restored.bodyAsText()
                    assertEquals(text, HttpStatusCode.Conflict, restored.status)
                    assertEquals("database-compatibility-refused", JSONObject(text).optString("status"))
                    assertEquals("Imported panel", fixture.config.friendlyName)
                } finally {
                    InstallProgress.finish(held, "released")
                }
            }
        }
    }

    @Test fun `replaying a revision clears credentials that were explicitly empty`() {
        PaneldServerHttpFixture().use { fixture ->
            val config = fixture.config
            val url = "http://ha.test:8123"
            assertTrue(config.setHaConnection(url, ""))
            testApplication {
                application { mountConfigBundleRoutes(fixture) }
                val imported = client.post("/api/v1/config/import") {
                    setBody(ConfigBundle.fromValues(mapOf(
                        "ha_url" to url,
                        "ha_token" to "access",
                        "ha_refresh_token" to "refresh",
                        "ha_token_expiry" to "2345678901",
                        "ha_client_id" to "oauth-client",
                    )).serialize())
                }
                assertEquals(imported.bodyAsText(), HttpStatusCode.OK, imported.status)
                assertEquals(HaAuthSnapshot(url, "access", "refresh", 2_345_678_901L, "oauth-client"), config.haAuthSnapshot())
                val revisions = JSONArray(client.get("/api/v1/config/revisions").bodyAsText())
                val revision = revisions.getJSONObject(0).getLong("id")
                val restored = client.post("/api/v1/config/revisions/$revision/restore")
                val text = restored.bodyAsText()
                assertEquals(text, HttpStatusCode.OK, restored.status)
                assertEquals(HaAuthSnapshot(url, "", "", 0L, ""), config.haAuthSnapshot())
                val applied = JSONObject(text).getJSONArray("applied")
                assertTrue(text, (0 until applied.length()).any { applied.getString(it) == "ha_client_id" })
            }
        }
    }

    @Test fun `tokenless import skips orphaned provenance before preview and approval`() {
        PaneldServerHttpFixture().use { fixture ->
            val config = fixture.config
            assertTrue(config.setHaConnection("http://ha.test:8123", "access"))
            config.setHaRefreshToken("refresh")
            config.setHaTokenExpiry(2_345_678_901L)
            config.setHaClientId("panel-client")
            val before = config.haAuthSnapshot()
            val approvals = mutableListOf<String>()
            val bundle = ConfigBundle.fromValues(mapOf("ha_client_id" to "source-client", "ha_token_expiry" to "1"))
            testApplication {
                application { mountConfigBundleRoutes(fixture) { approvals += it } }
                val preview = client.post("/api/v1/config/import?dry_run=1") { setBody(bundle.serialize()) }
                val dryRun = JSONObject(preview.bodyAsText())
                val noOp = client.post("/api/v1/config/import") { setBody(bundle.serialize()) }
                val applied = client.post("/api/v1/config/import") {
                    setBody(bundle.copy(values = bundle.values + ("friendly_name" to "Imported panel")).serialize())
                }
                assertEquals(dryRun.toString(), HttpStatusCode.OK, preview.status)
                assertEquals(noOp.bodyAsText(), HttpStatusCode.OK, noOp.status)
                assertEquals(applied.bodyAsText(), HttpStatusCode.OK, applied.status)
                assertEquals(0, dryRun.getJSONArray("changes").length())
                assertEquals(setOf("ha_client_id", "ha_token_expiry"), dryRun.getJSONArray("skipped").let {
                    (0 until it.length()).map(it::getString).toSet()
                })
                assertEquals("no-op", JSONObject(noOp.bodyAsText()).getString("status"))
                assertEquals(listOf("Import 1 panel setting"), approvals)
                assertEquals("Imported panel", config.friendlyName)
                assertEquals(before, config.haAuthSnapshot())
            }
        }
    }

    @Test fun `an import that moves the panel to another Home Assistant drops the previous sign-in`() {
        importOntoSignedInPanel("", mapOf("ha_url" to "http://b.test:8123")) { config ->
            assertEquals(HaAuthSnapshot("http://b.test:8123", "", "", 0L, ""), config.haAuthSnapshot())
        }
    }

    @Test fun `a fleet import that moves the panel to another Home Assistant drops the previous sign-in`() {
        importOntoSignedInPanel("?mode=fleet", mapOf("ha_url" to "http://b.test:8123")) { config ->
            assertEquals(HaAuthSnapshot("http://b.test:8123", "", "", 0L, ""), config.haAuthSnapshot())
        }
    }

    @Test fun `an import that moves the panel keeps the sign-in it carries for the new address`() {
        importOntoSignedInPanel("", mapOf(
            "ha_url" to "http://b.test:8123",
            "ha_token" to "b-access",
            "ha_refresh_token" to "b-refresh",
            "ha_token_expiry" to "2345678901",
            "ha_client_id" to "b-client",
        )) { config ->
            assertEquals(
                HaAuthSnapshot("http://b.test:8123", "b-access", "b-refresh", 2_345_678_901L, "b-client"),
                config.haAuthSnapshot(),
            )
        }
    }

    // The old app's default export leaves the tokens out as secrets but names its client id, blank when it never
    // signed in; Home Assistant then refuses the panel's own refresh token.
    @Test fun `an import without sign-in tokens keeps the panel signed in to the same Home Assistant`() {
        LoopbackHa().use { ha ->
            HaCredentialManager.forgetRefusal()
            ha.requiredClientId = "panel-client"
            ha.answer(200, """{"access_token":"fresh","expires_in":1800,"token_type":"Bearer"}""")
            PaneldServerHttpFixture().use { fixture ->
                val config = fixture.config
                assertTrue(config.setHaConnection(ha.url, "stale-access"))
                config.setHaRefreshToken("panel-refresh")
                config.setHaTokenExpiry(1L)
                config.setHaClientId("panel-client")
                val before = config.haAuthSnapshot()
                val epoch = config.haRouteEpoch()
                testApplication {
                    application { mountConfigBundleRoutes(fixture) }
                    val response = client.post("/api/v1/config/import") {
                        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        setBody(ConfigBundle.fromValues(mapOf(
                            "ha_url" to ha.url, "friendly_name" to "Imported", "ha_client_id" to "",
                        )).serialize())
                    }
                    assertEquals(response.bodyAsText(), HttpStatusCode.OK, response.status)
                }
                assertEquals(before, config.haAuthSnapshot())
                assertEquals(epoch, config.haRouteEpoch())
                val refreshed = HaCredentialManager.resolve(config, force = true)
                HaCredentialManager.forgetRefusal()
                assertFalse("Home Assistant refused the refresh", refreshed.rejected)
                assertEquals("fresh", refreshed.session?.accessToken)
            }
        }
    }

    private fun importOntoSignedInPanel(
        query: String,
        values: Map<String, String>,
        check: (io.panelassistant.android.Config) -> Unit,
    ) {
        PaneldServerHttpFixture().use { fixture ->
            val config = fixture.config
            assertTrue(config.setHaConnection("http://a.test:8123", "a-access"))
            config.setHaRefreshToken("a-refresh")
            config.setHaTokenExpiry(9_999_999_999L)
            config.setHaClientId("a-client")
            testApplication {
                application { mountConfigBundleRoutes(fixture) }
                val response = client.post("/api/v1/config/import$query") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    setBody(ConfigBundle.fromValues(values).serialize())
                }
                assertEquals(response.bodyAsText(), HttpStatusCode.OK, response.status)
            }
            check(config)
        }
    }

    /** The production route owner over the fixture's real Config, with sensitive admission granted. */
    private fun Application.mountConfigBundleRoutes(
        fixture: PaneldServerHttpFixture,
        onApproval: (String) -> Unit = {},
    ) {
        val config = fixture.config
        // This fixture has no device model to name the panel from.
        config.setRaw(requireNotNull(SettingsRegistry.spec("friendly_name")), "Contract panel")
        val revisions = RevisionStore(fixture.directory)
        val values = ConfigValueProjection(
            config = config,
            configLiveValues = { emptyMap() },
            renderedLiveValues = { error("No page render") },
            pendingLiveSettings = { emptyMap() },
            stalledLiveSettings = { emptySet() },
            proximityJson = { error("No page render") },
            powerSafetyJson = { error("No page render") },
            haAreaCatalogJson = { error("No page render") },
        )
        val transaction = AcceptedConfigTransaction(
            config = config,
            revisions = revisions,
            rendererPreparation = RendererPreparationCoordinator(
                builtinPackage = "builtin",
                state = { RendererPreparationState("builtin", "") },
                borrow = { BorrowedRendererSettings("http://ha.test", "", "", 0, "", null) },
                persist = { error("No built-in renderer") },
            ),
            system = SystemController(object : SystemEnv {
                override val ownPackage = "io.github.maxlyth.hapaneld"
                override fun isInstalled(pkg: String) = false
                override fun launchComponent(pkg: String): String? = error("No launch")
                override fun homeActivities(): List<ActivityRef> = error("No HOME")
                override fun defaultHome(): ActivityRef? = error("No HOME")
                override fun directStart(component: String) = error("No launch")
            }),
            values = values,
            applySetting = { _, _ -> LiveSettingRequestOutcome.APPLIED },
            onEntityTargetChanged = {},
            setEntityLearningEnabled = { true },
            effectiveDashboardIsBuiltin = { false },
            requestTameReconcileAfterCommit = { true },
            snapInvalidate = {},
            onReconfigure = {},
        )
        routing {
            route("/api/v1") {
                configBundleRoutes {
                    ConfigBundleRoutes(
                        config = config,
                        revisions = revisions,
                        values = values,
                        transaction = transaction,
                        authorizeSensitive = { _, _, _, description -> onApproval(description); true },
                        rejectHardenedNetworkAdb = { _, _ -> false },
                        planEntityBackup = ::planEntityBackup,
                        capabilities = { Capabilities(networkAdb = true) },
                    )
                }
            }
        }
    }
}
