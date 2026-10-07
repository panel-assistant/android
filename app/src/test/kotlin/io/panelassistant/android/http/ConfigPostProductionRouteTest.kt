package io.panelassistant.android.http

import io.panelassistant.android.Config
import io.panelassistant.android.LiveSettingApplyResult
import io.panelassistant.android.LiveSettingAuthority
import io.panelassistant.android.LiveSettingRequestOutcome
import io.panelassistant.android.MqttBridge
import io.panelassistant.android.dispatchLiveSetting
import io.panelassistant.android.config.Capabilities
import io.panelassistant.android.config.ConfigBundle
import io.panelassistant.android.config.SettingsRegistry
import io.panelassistant.android.control.PowerRiskLevel
import io.panelassistant.android.control.PowerSafetyAssessment
import io.panelassistant.android.control.PowerSafetyObservation
import io.panelassistant.android.control.PrivilegedRouteObservation
import io.panelassistant.android.control.SystemController
import io.panelassistant.android.persistence.SqliteStatePreferences
import io.panelassistant.android.persistence.StateMutation
import io.panelassistant.android.persistence.StateNamespacePersistence
import io.panelassistant.android.platform.ActivityRef
import io.panelassistant.android.platform.SystemEnv
import io.panelassistant.android.mqtt.StateConverger
import io.panelassistant.android.sensors.SensorReporter
import io.panelassistant.android.shizuku.ShizukuBridge
import io.panelassistant.android.shizuku.ShizukuState
import io.panelassistant.android.security.LocalApprovalBroker
import io.panelassistant.android.util.Cached
import io.panelassistant.android.util.InstallProgress
import io.panelassistant.android.util.RendererPreparationCoordinator
import io.panelassistant.android.util.RendererPreparationState
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.routing.route
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.plugins.mutableOriginConnectionPoint
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.Executors
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Test
import sun.misc.Unsafe
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfigPostProductionRouteTest {

    @Test fun `interface language save persists and changes the sidebar while refusals preserve it`() =
        withFullReadServer { config, fixture ->
            testApplication {
                application { fixture.mount(this) }
                suspend fun schema() = client.get("/api/v1/config/schema") {
                    header(EmbedMode.HEADER, "v=1;lang=en")
                }
                for (language in listOf("de", "en")) {
                    val saved = client.submitForm("/api/v1/config", Parameters.build {
                        append("ui_language", language)
                    }) { accept(ContentType.Application.Json) }
                    assertEquals(HttpStatusCode.OK, saved.status)
                    assertEquals(language, config.uiLanguage)
                    val readBack = JSONObject(client.get("/api/v1/config").bodyAsText())
                    assertEquals(language, readBack.getJSONObject("settings").getString("ui_language"))
                    val localized = schema()
                    assertEquals(HttpStatusCode.OK, localized.status)
                    val fields = JSONArray(localized.bodyAsText())
                    val name = (0 until fields.length()).map(fields::getJSONObject)
                        .single { it.getString("key") == "friendly_name" }
                    assertEquals(if (language == "de") "de" else "en", name.getString("labelLanguage"))
                    assertEquals(if (language == "de") "Anzeigename" else "Friendly name", name.getString("label"))
                    val refused = client.submitForm("/api/v1/config", Parameters.build {
                        append("ui_language", "unsupported-locale")
                    }) { accept(ContentType.Application.Json) }
                    assertEquals(HttpStatusCode.BadRequest, refused.status)
                    assertTrue(refused.bodyAsText().startsWith("ui_language: must be one of "))
                    assertEquals(language, config.uiLanguage, "a refusal must preserve the saved language")
                }
            }
        }

    @Test fun `zoom 96 persists through the production route and invalid values explain the rule`() =
        withRouteConfig { config, _, server, _ ->
            testApplication {
                application {
                    paneldRoot({ emptySet() }, { false }, { "/setup" }) {
                        route("/api/v1") { with(server) { installDirectConfigPostRoute { Capabilities() } } }
                    }
                }
                val saved = client.submitForm("/api/v1/config", Parameters.build {
                    append("dashboard_zoom", "96")
                }) { accept(ContentType.Application.Json) }
                assertEquals(HttpStatusCode.OK, saved.status)
                assertEquals(96, config.dashboardZoom)
                for ((value, rule) in listOf("49" to "must be ≥ 50", "301" to "must be ≤ 300", "bad" to "expected an integer")) {
                    val refused = client.submitForm("/api/v1/config", Parameters.build {
                        append("dashboard_zoom", value)
                    }) { accept(ContentType.Application.Json) }
                    assertEquals(HttpStatusCode.BadRequest, refused.status)
                    assertEquals("dashboard_zoom: $rule\n", refused.bodyAsText())
                    assertEquals(96, config.dashboardZoom, "a refusal must preserve the saved value")
                }
            }
        }

    @Test fun `configure schema offers only system bars and native navigation the panel provides`() {
        for (caps in listOf(Capabilities(), Capabilities(hasNativeNavbar = true), Capabilities(hasAndroidStatusBar = true))) {
            withFullReadServer { _, fixture ->
                val observations = PaneldServer::class.java.getDeclaredField("managementObservations").run {
                    isAccessible = true
                    get(fixture.server) as ManagementObservations
                }
                val previous = requireNotNull(observations.snapCache.peek())
                observations.snapCache.set(ManagementSnapshot(
                    previous.facts, previous.live, caps, previous.capabilityRows, previous.privilege,
                    previous.densityCur, previous.densityBase, previous.fontScale, previous.wifiChronic,
                ))
                testApplication {
                    application { fixture.mount(this) }
                    val response = client.get("/api/v1/config/schema")
                    assertEquals(HttpStatusCode.OK, response.status)
                    val entries = JSONArray(response.bodyAsText())
                    val byKey = (0 until entries.length()).map(entries::getJSONObject).associateBy { it.getString("key") }
                    assertEquals(caps.hasNativeNavbar || caps.hasAndroidStatusBar,
                        byKey.getValue("dashboard_fullscreen").getBoolean("available"))
                    val options = byKey.getValue("navbar_mode").getJSONArray("options")
                    assertEquals(caps.hasNativeNavbar, (0 until options.length()).any { options.getString(it) == "Native" })
                }
            }
        }
    }

    @Test fun `full mount config reads redact secrets preserve source separation and localize schema`() =
        withFullReadServer { config, fixture ->
            config.setMqtt("", "reader", "private-test-password")
            setField(fixture.server, "configLiveValues", { mapOf("keep_awake" to "false") })
            testApplication {
                application { fixture.mount(this) }
                val response = client.get("/api/v1/config")
                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
                val body = JSONObject(response.bodyAsText())
                assertTrue(body.getBoolean("mqtt_password_set"))
                assertEquals("", body.getJSONObject("settings").getString("mqtt_password"))
                assertEquals(config.keepAwake, body.getJSONObject("settings").getBoolean("keep_awake"))
                val exported = ConfigBundle.parse(client.get("/api/v1/config/export").bodyAsText())!!
                assertEquals("false", exported.values["keep_awake"])

                val schema = client.get("/api/v1/config/schema?lang=de")
                assertEquals(HttpStatusCode.OK, schema.status)
                assertEquals("no-store", schema.headers[HttpHeaders.CacheControl])
                assertEquals(HttpHeaders.AcceptLanguage, schema.headers[HttpHeaders.Vary])
                assertTrue(schema.headers[HttpHeaders.ContentLanguage].orEmpty().contains("de"))
                val entries = JSONArray(schema.bodyAsText())
                val friendly = (0 until entries.length()).map(entries::getJSONObject)
                    .single { it.getString("key") == "friendly_name" }
                assertEquals("de", friendly.getString("labelLanguage"))
                val byKey = (0 until entries.length()).map(entries::getJSONObject)
                    .associateBy { it.getString("key") }
                val german = io.panelassistant.android.i18n.CatalogueLoader { java.io.File("src/main/assets", it).readText() }.strings("de")
                fun list(field: JSONObject, name: String) = field.getJSONArray(name).let { a -> (0 until a.length()).map(a::getString) }
                for (key in listOf("navbar_mode", "camera_resolution", "log_ship_protocol", "ui_language")) {
                    val spec = io.panelassistant.android.config.SettingsRegistry.SPECS.single { it.key == key }
                    val field = byKey[key] ?: continue
                    assertEquals(
                        list(field, "options").map { spec.optionLabelKey(it).takeIf(german::has)?.let(german::get) ?: it },
                        list(field, "optionLabels"),
                        "$key option labels are the German records, or the value itself where none exists",
                    )
                }
                assertEquals(
                    german.get("configure.enum.navbar_mode.swipe_reveal"),
                    list(byKey.getValue("navbar_mode"), "optionLabels")[list(byKey.getValue("navbar_mode"), "options").indexOf("Swipe reveal")],
                )
                assertTrue(byKey.getValue("watchdog_enabled").getBoolean("shortDescriptionUsefulInPopover"))
                assertEquals(false, byKey.getValue("silence_boot_chime").getBoolean("shortDescriptionUsefulInPopover"))
                val refused = client.get("/api/v1/config") { header(HttpHeaders.Host, "foreign.example") }
                assertEquals(HttpStatusCode.Forbidden, refused.status)
            }
        }

    @Test fun `full mount config metadata reads expose discovery without overwriting configured targets`() =
        withFullReadServer { config, fixture ->
            var discoveries = 0
            setField(fixture.server, "configDiscoverySuggestions", {
                discoveries++
                ConfigDiscoverySuggestions(mqttBroker = "mqtt.test", haUrl = "http://ha.test")
            })
            testApplication {
                application { fixture.mount(this) }
                val catalog = client.get("/api/v1/config/home-dashboards")
                assertEquals(HttpStatusCode.OK, catalog.status)
                val dashboards = JSONObject(catalog.bodyAsText())
                assertEquals(false, dashboards.getBoolean("queried"))
                assertEquals(0, dashboards.getJSONArray("items").length())
                assertEquals(false, dashboards.getJSONObject("default").getBoolean("explicit"))
                val found = JSONObject(client.get("/api/v1/config/discovery").bodyAsText())
                assertEquals("mqtt.test", found.getString("mqtt_broker"))
                assertEquals("http://ha.test", found.getString("ha_url"))
                assertEquals("", config.mqttBroker)
                assertEquals("", config.haUrl)
                config.setMqtt("configured.test", "", "")
                config.setHaConnection("http://configured.test", null)
                val configured = JSONObject(client.get("/api/v1/config/discovery").bodyAsText())
                assertEquals("", configured.getString("mqtt_broker"))
                assertEquals("", configured.getString("ha_url"))
                assertEquals(1, discoveries)
            }
        }

    private fun withFullReadServer(block: (Config, PaneldServerHttpFixture) -> Unit) =
        withRouteConfig { config, _, server, _ ->
            PaneldServerHttpFixture().use { fixture ->
                // Reuse the existing real Config/projection collaborators inside the complete root mount.
                for (name in listOf(
                    "config", "system", "sensors", "pendingLiveSettings", "stalledLiveSettings",
                    "configLiveValues", "rendererPreparation", "tameReconciliation", "revisions",
                    "managementObservations", "powerSafety", "stopping", "haArea", "pageHealth",
                    "directConfigMutationLock", "autoSleepHttpApi", "autoBrightnessHttpApi", "applySetting", "onReconfigure",
                )) {
                    val value = PaneldServer::class.java.getDeclaredField(name).run {
                        isAccessible = true
                        get(server)
                    }
                    setField(fixture.server, name, value)
                }
                // Source-text reason: the shipped catalogue is runtime input, not a source-shape assertion.
                setField(fixture.server, "catalogueLoader\$delegate", lazyOf(
                    io.panelassistant.android.i18n.CatalogueLoader { File("src/main/assets", it).readText() },
                ))
                val profileType = io.panelassistant.android.device.DeviceProfile::class.java
                setField(fixture.server, "profile", java.lang.reflect.Proxy.newProxyInstance(
                    profileType.classLoader, arrayOf(profileType),
                ) { _, method, _ ->
                    when (method.name) {
                        "getManufacturer" -> "Profile maker"
                        "getModel" -> "Profile model"
                        else -> error("Unexpected profile access: ${method.name}")
                    }
                })
                val learning = allocate(io.panelassistant.android.dashboard.EntityLearningManager::class.java)
                setField(learning, "config", config)
                setField(fixture.server, "entityLearning", learning)
                block(config, fixture)
            }
        }


    @Test fun `production bundle import exports redacted values and revision restore undoes the commit`() =
        withRouteConfig { config, _, server, _ ->
            assertTrue(config.applyBatch { config.setMqtt("", "test-user", "private-test-value") })
            config.setHaExposed("camera_enabled", true)
            testApplication {
                application {
                    paneldRoot({ emptySet() }, { false }, { "/setup" }) {
                        route("/api/v1") { with(server) { installConfigBundleRoutes() } }
                    }
                }
                val exported = client.get("/api/v1/config/export")
                assertEquals(HttpStatusCode.OK, exported.status)
                val bundle = requireNotNull(ConfigBundle.parse(exported.bodyAsText()))
                assertEquals("Contract panel", bundle.values["friendly_name"])
                assertTrue("mqtt_password" !in bundle.values)
                assertEquals("true", bundle.values["ha_expose_camera_enabled"])
                assertEquals(null, SettingsRegistry.parseExposure("ha_expose_camera_enabled"))
                val imported = client.post("/api/v1/config/import") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    setBody(ConfigBundle.fromValues(mapOf(
                        "friendly_name" to "Imported panel", "ha_expose_camera_enabled" to "false",
                    )).serialize())
                }
                assertEquals(HttpStatusCode.OK, imported.status, imported.bodyAsText())
                assertEquals("applied", JSONObject(imported.bodyAsText()).getString("status"))
                assertEquals("Imported panel", config.friendlyName)
                assertEquals(false, config.haExposed("camera_enabled", false))
                val revisions = JSONArray(client.get("/api/v1/config/revisions").bodyAsText())
                assertEquals(1, revisions.length())
                val restored = client.post("/api/v1/config/revisions/${revisions.getJSONObject(0).getLong("id")}/restore")
                assertEquals(HttpStatusCode.OK, restored.status, restored.bodyAsText())
                assertEquals("restored", JSONObject(restored.bodyAsText()).getString("status"))
                assertEquals("Contract panel", config.friendlyName)
                assertEquals(true, config.haExposed("camera_enabled", false))
                assertEquals("test-user", config.mqttUser)
                assertEquals(2, JSONArray(client.get("/api/v1/config/revisions").bodyAsText()).length())
            }
        }

    @Test fun `retired updater form and JSON posts cannot persist settings or start work`() =
        withRouteConfig { config, persistence, server, live ->
            setField(server, "onInstallComponent", { _: String, _: String, _: String ->
                error("A configuration request must not install a package")
            })
            testApplication {
                application {
                    paneldRoot({ emptySet() }, { false }, { "/setup" }) {
                        route("/api/v1") { with(server) { installDirectConfigPostRoute { Capabilities() } } }
                    }
                }
                for ((key, value) in retiredUpdaterValues) {
                    val form = client.submitForm("/api/v1/config", Parameters.build {
                        append(key, value)
                        append("friendly_name", "Must not commit")
                    }) { accept(ContentType.Application.Json) }
                    assertEquals(HttpStatusCode.BadRequest, form.status, key)
                    assertEquals("$key: unknown setting\n", form.bodyAsText())
                    val json = client.post("/api/v1/config") {
                        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        setBody(JSONObject(mapOf(key to value, "friendly_name" to "Must not commit")).toString())
                    }
                    assertEquals(HttpStatusCode.BadRequest, json.status, key)
                    assertEquals("$key: unknown setting\n", json.bodyAsText())
                }
            }
            assertEquals("Contract panel", config.friendlyName)
            assertTrue(retiredUpdaterValues.keys.none { it in persistence.initialize() })
            assertTrue(live.isEmpty(), "Retired settings must start no live apply or reconfigure")
            assertTrue(!InstallProgress.running, "Retired settings must start no package operation")
        }

    @Test fun `legacy bundle updater settings are retired while ordinary settings still import`() =
        withRouteConfig { config, persistence, server, live ->
            setField(server, "onInstallComponent", { _: String, _: String, _: String ->
                error("An imported configuration must not install a package")
            })
            testApplication {
                application {
                    paneldRoot({ emptySet() }, { false }, { "/setup" }) {
                        route("/api/v1") { with(server) { installConfigBundleRoutes() } }
                    }
                }
                val legacy = ConfigBundle.fromValues(
                    retiredUpdaterValues + mapOf("update_channel" to "stable", "friendly_name" to "Imported panel"),
                ).copy(schema = 13)
                val response = client.post("/api/v1/config/import") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    setBody(legacy.serialize())
                }
                assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
                val receipt = JSONObject(response.bodyAsText())
                assertEquals("applied", receipt.getString("status"))
                assertEquals(listOf("friendly_name"), receipt.getJSONArray("applied").let { applied ->
                    (0 until applied.length()).map(applied::getString)
                })
                assertEquals("Imported panel", config.friendlyName)
                assertEquals("Imported panel", persistence.initialize()["friendly_name"])
                assertTrue(retiredUpdaterValues.keys.none { it in persistence.initialize() })
                val exported = requireNotNull(ConfigBundle.parse(client.get("/api/v1/config/export").bodyAsText()))
                assertTrue(retiredUpdaterValues.keys.none { it in exported.values })
            }
            assertEquals(listOf("reconfigure"), live)
            assertTrue(!InstallProgress.running, "Legacy updater settings must start no package operation")
        }

    private val retiredUpdaterValues = linkedMapOf(
        "self_update" to "true",
        "update_channel" to "prerelease",
        "companion_auto_update" to "true",
        "companion_update_channel" to "prerelease",
        "webview_auto_update" to "true",
    )

    @Test fun `production backup restores retained camera exposure and ordinary stored values`() =
        withFullReadServer { config, fixture ->
            config.setHaExposed("camera_enabled", true)
            config.setPanelId("retained_panel")
            config.setFriendlyName("Original panel")
            config.setMqtt("", "retained-user", "retained-password")
            val panelId = config.panelId
            val builder = fixture.backupBuilder(null, config) { spec, live -> effectiveSettingValue(config, spec, live) }
            builder.build(CompanionBackupRequest.EXCLUDED, "").use { artifact ->
                val manifest = JSONObject(io.panelassistant.android.backup.PanelBackup.readManifest(artifact.file, 1024 * 1024)!!)
                assertTrue(manifest.getJSONObject("config").has("ha_expose_camera_enabled"))
                assertEquals("true", manifest.getJSONObject("config").getString("ha_expose_camera_enabled"))
                config.setHaExposed("camera_enabled", false)
                config.setFriendlyName("Changed panel")
                setField(fixture.server, "applySetting", { _: String, _: String -> LiveSettingRequestOutcome.APPLIED })
                setField(fixture.server, "onReconfigure", { _: Set<String> -> })
                testApplication {
                    application { fixture.mount(this) }
                    val restored = client.post("/api/v1/restore") { setBody(artifact.file.readBytes()) }
                    assertEquals(HttpStatusCode.OK, restored.status, restored.bodyAsText())
                    kotlinx.coroutines.withTimeout(5_000) {
                        while (InstallProgress.running) kotlinx.coroutines.delay(10)
                    }
                    val result = JSONObject(InstallProgress.json()).getJSONObject("result")
                    assertEquals("succeeded", result.getString("status"), result.toString())
                    assertEquals(true, config.haExposed("camera_enabled", false))
                    assertEquals("Original panel", config.friendlyName)
                    assertEquals("retained-user", config.mqttUser)
                    assertEquals("retained-password", config.mqttPassword)
                    assertEquals(panelId, config.panelId)
                }
            }
        }

    @Test fun `failed production bundle commit records no revision and starts no live effects`() =
        withRouteConfig { config, persistence, server, live ->
            persistence.failWrites = true
            testApplication {
                application {
                    paneldRoot({ emptySet() }, { false }, { "/setup" }) {
                        route("/api/v1") { with(server) { installConfigBundleRoutes() } }
                    }
                }
                val response = client.post("/api/v1/config/import") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    setBody(ConfigBundle.fromValues(mapOf(
                        "friendly_name" to "Changed",
                        "home_dashboard" to "/lovelace/changed",
                    )).serialize())
                }
                assertEquals(HttpStatusCode.InternalServerError, response.status)
                assertEquals("error", JSONObject(response.bodyAsText()).getString("status"))
                assertEquals(0, JSONArray(client.get("/api/v1/config/revisions").bodyAsText()).length())
                assertEquals("Contract panel", config.friendlyName)
                assertEquals("Contract panel", persistence.initialize()["friendly_name"])
                assertTrue(live.isEmpty(), "failed import must not dispatch hardware or reconfigure")
            }
        }
    @Test fun `production config root guards and bounded reader refuse requests without changing settings`() =
        withRouteConfig { config, _, server, _ ->
            testApplication {
                application {
                    paneldRoot({ emptySet() }, { false }, { "/setup" }) {
                        route("/api/v1") { with(server) { installDirectConfigPostRoute { Capabilities() } } }
                    }
                }
                val origin = client.submitForm("/api/v1/config", Parameters.build { append("friendly_name", "Changed") }) {
                    header(HttpHeaders.Origin, "http://elsewhere.example")
                }
                assertEquals(HttpStatusCode.Forbidden, origin.status)
                assertEquals("cross-origin refused\n", origin.bodyAsText())
                val host = client.submitForm("/api/v1/config", Parameters.build { append("friendly_name", "Changed") }) {
                    header(HttpHeaders.Host, "elsewhere.example")
                }
                assertEquals(HttpStatusCode.Forbidden, host.status)
                assertEquals("host not allowed\n", host.bodyAsText())
                val large = client.post("/api/v1/config") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    setBody(" ".repeat(PaneldServer.MAX_CONFIG_POST_BODY_BYTES.toInt() + 1))
                }
                assertEquals(HttpStatusCode.PayloadTooLarge, large.status)
                assertEquals("request too large\n", large.bodyAsText())
                assertEquals("Contract panel", config.friendlyName)
            }
        }

    @Test fun `hardened production config requires exact one-shot approval before reducing power safety`() =
        withRouteConfig { config, _, server, _ ->
            assertTrue(config.applyBatch { config.setKeepAwake(true) })
            assertTrue(config.setSecurityMode(Config.SecurityMode.HARDENED))
            LocalApprovalBroker.instance.clear()
            try {
                testApplication {
                    application {
                        intercept(ApplicationCallPipeline.Setup) {
                            context.mutableOriginConnectionPoint.remoteAddress = "192.168.50.20"
                        }
                        paneldRoot({ emptySet() }, { false }, { "/setup" }) {
                            route("/api/v1") { with(server) { installDirectConfigPostRoute { Capabilities() } } }
                        }
                    }
                    suspend fun save() = client.submitForm(
                        "/api/v1/config", Parameters.build { append("keep_awake", "false") },
                    ) { accept(ContentType.Application.Json) }
                    val pending = save()
                    val pendingBody = JSONObject(pending.bodyAsText())
                    assertEquals(HttpStatusCode.Accepted, pending.status)
                    assertEquals("approval-required", pendingBody.getString("error"))
                    assertTrue(config.keepAwake)
                    assertTrue(LocalApprovalBroker.instance.approve(pendingBody.getString("approval_id")))
                    val accepted = save()
                    assertEquals(HttpStatusCode.OK, accepted.status, accepted.bodyAsText())
                    assertEquals("saved", JSONObject(accepted.bodyAsText()).getString("status"))
                    assertEquals(false, config.keepAwake)
                    assertTrue(LocalApprovalBroker.instance.pending().isEmpty())
                }
            } finally {
                LocalApprovalBroker.instance.clear()
            }
        }

    @Test fun `failed production config commit reports failure and leaves SQLite and live effects unchanged`() =
        withRouteConfig { config, persistence, server, live ->
            persistence.failWrites = true
            testApplication {
                application {
                    paneldRoot({ emptySet() }, { false }, { "/setup" }) {
                        route("/api/v1") { with(server) { installDirectConfigPostRoute { Capabilities() } } }
                    }
                }
                val response = client.submitForm("/api/v1/config", Parameters.build {
                    append("friendly_name", "Changed")
                    append("home_dashboard", "/lovelace/changed")
                }) { accept(ContentType.Application.Json) }
                assertEquals(HttpStatusCode.InternalServerError, response.status)
                assertEquals("configuration commit failed\n", response.bodyAsText())
            }
            assertEquals("Contract panel", config.friendlyName)
            assertEquals("Contract panel", persistence.initialize()["friendly_name"])
            assertTrue(live.isEmpty(), "failed persistence must not dispatch hardware or reconfigure")
        }

    private fun withRouteConfig(block: (Config, JdbcStatePersistence, PaneldServer, MutableList<String>) -> Unit) {
        val directory = Files.createTempDirectory("config-post-admission").toFile()
        val writer = Executors.newSingleThreadExecutor()
        try {
            val persistence = JdbcStatePersistence(File(directory, "ha-paneld.db"))
            val config = Config(SqliteStatePreferences(persistence, writer))
            assertTrue(config.applyBatch {
                config.setPanelId("contract-panel")
                config.setFriendlyName("Contract panel")
                config.setHardware("Contract manufacturer", "Contract model")
                config.setDashboardPackage("com.example.dashboard")
            })
            val live = mutableListOf<String>()
            val server = routeServer(config) { key, _ ->
                live += key
                LiveSettingRequestOutcome.APPLIED
            }
            setField(server, "onReconfigure", { _: Set<String> -> live += "reconfigure" })
            block(config, persistence, server, live)
        } finally {
            writer.shutdownNow()
            directory.deleteRecursively()
        }
    }

    @Test fun `production config POST normalizes persists reads back and dispatches a live setting`() {
        val directory = Files.createTempDirectory("config-post-route").toFile()
        val database = File(directory, "ha-paneld.db")
        val writer = Executors.newSingleThreadExecutor()
        val reopenedWriter = Executors.newSingleThreadExecutor()
        try {
            val config = Config(SqliteStatePreferences(JdbcStatePersistence(database), writer))
            assertTrue(config.applyBatch {
                config.setPanelId("contract-panel")
                config.setFriendlyName("Contract panel")
                config.setHardware("Contract manufacturer", "Contract model")
                config.setDashboardPackage("com.example.dashboard")
            })
            val spec = requireNotNull(SettingsRegistry.spec("home_dashboard"))
            val runtimeRefreshes = mutableListOf<Unit>()
            val bridge = liveEffectBridge(config) { runtimeRefreshes += Unit }
            val authority = LiveSettingAuthority(setOf(spec.key))
            val server = routeServer(config) { key, normalized ->
                authority.applyOrQueueOutcome(key, normalized, config.getRaw(spec)) { appliedKey, value, previous ->
                    assertEquals(spec.key, appliedKey)
                    dispatchLiveSetting(
                        key = appliedKey,
                        value = value,
                        previousValue = previous,
                        handlers = bridge,
                    )
                    if (config.getRaw(spec) == value) {
                        LiveSettingApplyResult.APPLIED
                    } else {
                        LiveSettingApplyResult.FAILED
                    }
                }
            }

            testApplication {
                application {
                    routing {
                        route("/api/v1") {
                            with(server) {
                                installDirectConfigPostRoute { Capabilities() }
                            }
                        }
                    }
                }

                val response = client.submitForm(
                    url = "/api/v1/config",
                    formParameters = Parameters.build { append(spec.key, "/lovelace/beta") },
                ) { accept(ContentType.Application.Json) }

                val responseText = response.bodyAsText()
                assertEquals(HttpStatusCode.OK, response.status, responseText)
                val body = JSONObject(responseText)
                assertEquals("saved", body.getString("status"))
                assertEquals(listOf(spec.key), body.getJSONArray("applied").let { array ->
                    List(array.length()) { array.getString(it) }
                })
                assertEquals("/lovelace/beta", body.getJSONObject("settings").getString(spec.key))
            }

            assertEquals(1, runtimeRefreshes.size, "the concrete MqttBridge effect owner must run")
            assertEquals("/lovelace/beta", config.getRaw(spec))
            val reopened = Config(SqliteStatePreferences(JdbcStatePersistence(database), reopenedWriter))
            assertEquals(
                "/lovelace/beta",
                reopened.getRaw(spec),
                "a new production preference owner must read SQLite",
            )
        } finally {
            writer.shutdownNow()
            reopenedWriter.shutdownNow()
            directory.deleteRecursively()
        }
    }

    @Test fun `automatic bounds POST validates effective pair and persists accepted range`() {
        val directory = Files.createTempDirectory("config-post-auto-bounds").toFile()
        val database = File(directory, "ha-paneld.db")
        val writer = Executors.newSingleThreadExecutor()
        val reopenedWriter = Executors.newSingleThreadExecutor()
        try {
            val config = Config(SqliteStatePreferences(JdbcStatePersistence(database), writer))
            assertTrue(config.applyBatch {
                config.setPanelId("contract-panel")
                config.setFriendlyName("Contract panel")
                config.setHardware("Contract manufacturer", "Contract model")
                config.setDashboardPackage("com.example.dashboard")
            })
            val minimum = requireNotNull(SettingsRegistry.spec("auto_brightness_minimum_percent"))
            val maximum = requireNotNull(SettingsRegistry.spec("auto_brightness_maximum_percent"))
            assertEquals("100", config.getRaw(maximum), "older stores default to full range")
            val server = routeServer(config) { key, value ->
                when (key) {
                    minimum.key -> config.setAutoBrightnessMinimumPercent(value.toInt())
                    maximum.key -> config.setAutoBrightnessMaximumPercent(value.toInt())
                    else -> config.setRaw(requireNotNull(SettingsRegistry.spec(key)), value)
                }
                LiveSettingRequestOutcome.APPLIED
            }
            testApplication {
                application { routing { route("/api/v1") {
                    with(server) { installDirectConfigPostRoute { Capabilities() } }
                } } }
                suspend fun postBounds(values: Map<String, String>) = client.submitForm(
                    "/api/v1/config", Parameters.build { values.forEach { (key, value) -> append(key, value) } },
                ) { accept(ContentType.Application.Json) }
                val accepted = postBounds(mapOf(minimum.key to "20", maximum.key to "60"))
                assertEquals(HttpStatusCode.OK, accepted.status, accepted.bodyAsText())
                assertEquals("20", config.getRaw(minimum))
                assertEquals("60", config.getRaw(maximum))
                listOf(mapOf(maximum.key to "20"), mapOf(maximum.key to "10"),
                    mapOf(minimum.key to "60"), mapOf(minimum.key to "70"),
                    mapOf(minimum.key to "40", maximum.key to "40")).forEach { values ->
                    val refused = postBounds(values)
                    assertEquals(HttpStatusCode.BadRequest, refused.status, refused.bodyAsText())
                    assertEquals("20", config.getRaw(minimum))
                    assertEquals("60", config.getRaw(maximum))
                }
                val moved = postBounds(mapOf(minimum.key to "70", maximum.key to "80"))
                assertEquals(HttpStatusCode.OK, moved.status, moved.bodyAsText())
            }
            val reopened = Config(SqliteStatePreferences(JdbcStatePersistence(database), reopenedWriter))
            assertEquals("70", reopened.getRaw(minimum))
            assertEquals("80", reopened.getRaw(maximum))
        } finally {
            writer.shutdownNow()
            reopenedWriter.shutdownNow()
            directory.deleteRecursively()
        }
    }

    /**
     * These settings have no typed setter on the direct route: the registry writer is their only owner.
     * Each once shipped reported saved and silently discarded, so the whole production route must commit
     * them to SQLite, report them applied only from read-back, and hand them to a reopened owner.
     */
    @Test fun `production config POST persists registry-only settings through SQLite`() {
        val directory = Files.createTempDirectory("config-post-registry-only").toFile()
        val database = File(directory, "ha-paneld.db")
        val writer = Executors.newSingleThreadExecutor()
        val reopenedWriter = Executors.newSingleThreadExecutor()
        try {
            val config = Config(SqliteStatePreferences(JdbcStatePersistence(database), writer))
            assertTrue(config.applyBatch {
                config.setPanelId("contract-panel")
                config.setFriendlyName("Contract panel")
                config.setHardware("Contract manufacturer", "Contract model")
            })
            val posted = linkedMapOf(
                "dashboard_idle_return_min" to "15",
                "dashboard_network_warning" to "false",
                "voice_wake_words" to "[\"hey_jarvis\"]",
                "voice_pipelines" to "{\"hey_jarvis\":\"contract-pipeline\"}",
                "voice_audio_source" to "mic",
                "voice_sensitivity" to "high",
                "voice_mic_gain_db" to "6",
                "camera_enabled" to "true",
                "camera_resolution" to "1080p",
                "camera_fps" to "30",
                "camera_kbps" to "4000",
                "camera_exposure" to "1.5",
            )
            val specs = posted.keys.map { requireNotNull(SettingsRegistry.spec(it)) }
            specs.forEach { spec ->
                assertTrue(!spec.liveApply, "${spec.key} must stay on the ordinary registry lane")
                assertTrue(config.getRaw(spec) != posted[spec.key], "${spec.key} sample must be a real change")
            }
            val server = routeServer(config) { key, _ -> error("$key must not reach the live-setting lane") }

            testApplication {
                application {
                    routing {
                        route("/api/v1") {
                            with(server) {
                                installDirectConfigPostRoute { Capabilities() }
                            }
                        }
                    }
                }

                val response = client.submitForm(
                    url = "/api/v1/config",
                    formParameters = Parameters.build { posted.forEach { (key, value) -> append(key, value) } },
                ) { accept(ContentType.Application.Json) }

                val responseText = response.bodyAsText()
                assertEquals(HttpStatusCode.OK, response.status, responseText)
                val body = JSONObject(responseText)
                assertEquals("saved", body.getString("status"), responseText)
                assertEquals(posted.keys, body.getJSONArray("applied").let { array ->
                    List(array.length()) { array.getString(it) }
                }.toSet(), responseText)
            }

            val reopened = Config(SqliteStatePreferences(JdbcStatePersistence(database), reopenedWriter))
            specs.forEach { spec ->
                assertEquals(posted[spec.key], config.getRaw(spec), "${spec.key} read-back")
                assertEquals(posted[spec.key], reopened.getRaw(spec), "${spec.key} SQLite read-back")
            }
        } finally {
            writer.shutdownNow()
            reopenedWriter.shutdownNow()
            directory.deleteRecursively()
        }
    }

    /**
     * Build the smallest genuine production effect owner needed by this route case. The bridge's
     * home-dashboard handler owns both the durable Config write and the entity-learning target refresh; an empty
     * converger is sufficient because publication is deliberately a no-op without a registered channel.
     */
    private fun liveEffectBridge(config: Config, onDashboardTargetChanged: () -> Unit): MqttBridge =
        allocate(MqttBridge::class.java).also { bridge ->
            setField(bridge, "config", config)
            setField(bridge, "onDashboardTargetChanged", onDashboardTargetChanged)
            setField(
                bridge,
                "stateConverger",
                StateConverger(sender = { _, _, _ -> error("no state channel should publish") }),
            )
        }

    /**
     * Allocate only the production handler owner, then populate the collaborators the direct-config route
     * actually uses. This avoids pretending android.jar Context/services are functional on the JVM while
     * leaving Ktor registration, request decoding, transaction planning, response construction and the
     * private production handler intact.
     */
    private fun routeServer(
        config: Config,
        applySetting: (String, String) -> LiveSettingRequestOutcome,
    ): PaneldServer {
        val server = allocate(PaneldServer::class.java)
        val sensors = allocate(SensorReporter::class.java)
        val renderer = RendererPreparationCoordinator(
            builtinPackage = "builtin",
            state = { RendererPreparationState("com.example.dashboard", "") },
            borrow = { null },
            persist = { true },
        )
        val privilege = PrivilegedRouteObservation(
            directSuReady = true,
            helperRootReady = false,
            shizuku = ShizukuBridge.Snapshot(ShizukuState.DISABLED, ready = false),
        )
        val snap = ManagementSnapshot(
            emptyMap(), emptyMap(), Capabilities(), emptyList(), privilege,
            null, null, 1.0f, false,
        )
        val observations = ManagementObservations(
            object : android.content.ContextWrapper(null) {},
            io.panelassistant.android.control.DensityController(canSu = false),
            { error("Stopped fixture must not probe") },
            { _, _ -> error("Unexpected diagnostics") },
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            { true },
        )
        observations.snapCache.set(snap)

        setField(server, "managementObservations", observations)
        setField(server, "pageHealth", PageHealth(object : android.content.ContextWrapper(null) {}, config))
        setField(server, "config", config)
        setField(server, "system", SystemController(object : SystemEnv {
            override val ownPackage = "io.github.maxlyth.hapaneld"
            override fun isInstalled(pkg: String) = pkg == "com.example.dashboard"
            override fun launchComponent(pkg: String): String? = null
            override fun homeActivities(): List<ActivityRef> = emptyList()
            override fun defaultHome(): ActivityRef? = null
            override fun directStart(component: String) = true
        }))
        setField(server, "sensors", sensors)
        setField(server, "applySetting", applySetting)
        setField(server, "pendingLiveSettings", { emptyMap<String, String>() })
        setField(server, "stalledLiveSettings", { emptySet<String>() })
        setField(server, "configLiveValues", { emptyMap<String, String>() })
        setField(server, "onReconfigure", { _: Set<String> -> })
        setField(server, "rendererPreparation", renderer)
        setField(server, "autoSleepHttpApi", AutoSleepHttpApi.UNAVAILABLE)
        setField(server, "autoBrightnessHttpApi", AutoBrightnessHttpApi.UNAVAILABLE)
        setField(server, "tameReconciliation", TameReconcileAuthority(
            readDesired = { emptySet() },
            reconcile = { _, _ -> error("Stopped route fixture must not actuate packages") },
            stopping = { true },
        ).also { check(it.closeAndJoin(1_000)) })
        val mutationLock = Any()
        setField(server, "directConfigMutationLock", mutationLock)
        val learning = allocate(io.panelassistant.android.dashboard.EntityLearningManager::class.java)
        setField(learning, "config", config)
        setField(server, "haArea", HaAreaRuntime(
            config,
            learning,
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Job().apply { cancel() }),
            mutationLock,
        ) { true })
        setField(server, "revisions", RevisionStore(Files.createTempDirectory("config-route-revisions").toFile()))
        setField(server, "powerSafety", { safePowerAssessment() })
        setField(server, "stopping", true)
        return server
    }

    private fun safePowerAssessment(): PowerSafetyAssessment = PowerSafetyAssessment(
        level = PowerRiskLevel.SAFE,
        observation = PowerSafetyObservation(
            keepAwakeConfigured = true,
            wakeLockHeld = true,
            wifiLockRequired = false,
            wifiLockHeld = false,
            preventIdleDimConfigured = true,
            screenOffTimeoutMs = 30_000,
            interactive = true,
            pluggedMask = 1,
            stayOnWhilePluggedIn = 1,
            deviceIdleMode = false,
            ignoringBatteryOptimizations = true,
            screenOffMechanism = "test",
        ),
        reasonCodes = emptyList(),
        summary = "safe",
        action = "none",
    )

    private class JdbcStatePersistence(private val database: File) : StateNamespacePersistence {
        var failWrites = false
        init {
            connection().use { connection ->
                connection.createStatement().use {
                    it.execute(
                        "CREATE TABLE IF NOT EXISTS app_state(" +
                            "state_key TEXT PRIMARY KEY,value_type TEXT NOT NULL,value_text TEXT NOT NULL)",
                    )
                }
            }
        }

        override fun initialize(): Map<String, Any> = connection().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT state_key,value_type,value_text FROM app_state").use { rows ->
                    buildMap {
                        while (rows.next()) put(rows.getString(1), decode(rows.getString(2), rows.getString(3)))
                    }
                }
            }
        }

        override fun persist(mutation: StateMutation): Boolean = transaction { connection ->
            if (mutation.clear) connection.createStatement().use { it.executeUpdate("DELETE FROM app_state") }
            mutation.changes.forEach { (key, value) ->
                if (value == null) {
                    connection.prepareStatement("DELETE FROM app_state WHERE state_key=?").use {
                        it.setString(1, key)
                        it.executeUpdate()
                    }
                } else upsert(connection, key, value)
            }
        }

        override fun replace(snapshot: Map<String, Any>): Boolean = transaction { connection ->
            connection.createStatement().use { it.executeUpdate("DELETE FROM app_state") }
            snapshot.forEach { (key, value) -> upsert(connection, key, value) }
        }

        private fun upsert(connection: Connection, key: String, value: Any) {
            val (type, text) = encode(value)
            connection.prepareStatement(
                "INSERT INTO app_state(state_key,value_type,value_text) VALUES(?,?,?) " +
                    "ON CONFLICT(state_key) DO UPDATE SET value_type=excluded.value_type,value_text=excluded.value_text",
            ).use {
                it.setString(1, key)
                it.setString(2, type)
                it.setString(3, text)
                it.executeUpdate()
            }
        }

        private fun transaction(block: (Connection) -> Unit): Boolean = !failWrites && runCatching {
            connection().use { connection ->
                connection.autoCommit = false
                block(connection)
                connection.commit()
            }
        }.isSuccess

        private fun connection(): Connection = DriverManager.getConnection("jdbc:sqlite:${database.absolutePath}")

        private fun encode(value: Any): Pair<String, String> = when (value) {
            is Boolean -> "boolean" to value.toString()
            is Int -> "int" to value.toString()
            is Long -> "long" to value.toString()
            is Float -> "float" to value.toString()
            is String -> "string" to value
            is Set<*> -> "string_set" to value.filterIsInstance<String>().sorted().joinToString("\u0000")
            else -> error("unsupported SQLite state value ${value::class.java.name}")
        }

        private fun decode(type: String, value: String): Any = when (type) {
            "boolean" -> value.toBooleanStrict()
            "int" -> value.toInt()
            "long" -> value.toLong()
            "float" -> value.toFloat()
            "string" -> value
            "string_set" -> value.split('\u0000').filter(String::isNotEmpty).toSet()
            else -> error("unsupported SQLite state type $type")
        }
    }

    private fun <T> allocate(type: Class<T>): T = unsafe.allocateInstance(type) as T

    private fun setField(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }

    private companion object {
        val unsafe: Unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").run {
            isAccessible = true
            get(null) as Unsafe
        }
    }
}
