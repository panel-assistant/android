package io.panelassistant.android.http

import io.panelassistant.android.testsupport.scratchDirectory

import io.panelassistant.android.PanelCommandDispatcher
import io.panelassistant.android.PanelStateCore
import io.panelassistant.android.Config
import io.panelassistant.android.LiveSettingApplyResult
import io.panelassistant.android.LiveSettingAuthority
import io.panelassistant.android.LiveSettingRequestOutcome
import io.panelassistant.android.dispatchLiveSetting
import io.panelassistant.android.config.Capabilities
import io.panelassistant.android.config.ConfigBundle
import io.panelassistant.android.config.SettingsRegistry
import io.panelassistant.android.control.PowerRiskLevel
import io.panelassistant.android.control.PowerSafetyAssessment
import io.panelassistant.android.control.PowerSafetyObservation
import io.panelassistant.android.control.PrivilegedRouteObservation
import io.panelassistant.android.control.SystemController
import io.panelassistant.android.haToken
import io.panelassistant.android.haUrl
import io.panelassistant.android.haRefreshToken
import io.panelassistant.android.haAuthSnapshot
import io.panelassistant.android.stableOwner
import io.panelassistant.android.setHaRefreshToken
import io.panelassistant.android.persistence.SqliteStatePreferences
import io.panelassistant.android.persistence.StateMutation
import io.panelassistant.android.persistence.StateNamespacePersistence
import io.panelassistant.android.platform.ActivityRef
import io.panelassistant.android.platform.SystemEnv
import io.panelassistant.android.setHaConnection
import io.panelassistant.android.state.StateConverger
import io.panelassistant.android.sensors.SensorReporter
import io.panelassistant.android.sensors.ProximityCalibrationEngine
import io.panelassistant.android.sensors.ProximityCalibrationRuntime
import io.panelassistant.android.dashboard.EntityCatalogStore
import io.panelassistant.android.persistence.ProximityCalibrationBackup
import io.panelassistant.android.security.LocalApprovalBroker
import io.panelassistant.android.util.Cached
import io.panelassistant.android.util.InstallProgress
import io.panelassistant.android.util.RendererPreparationCoordinator
import io.panelassistant.android.util.RendererPreparationState
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.accept
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Test
import sun.misc.Unsafe
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigPostProductionRouteTest {

    @Test fun `interface language save persists and changes the sidebar while refusals preserve it`() =
        withFullReadServer { config, fixture ->
            testApplication {
                application { fixture.mount(this) }
                // The schema as the sidebar relay reads it, with the Home Assistant user's language in `args.embed`.
                fun schema(): JSONArray {
                    val answer = java.util.concurrent.LinkedBlockingQueue<io.panelassistant.android.ManageReply>()
                    fixture.door.manage(
                        java.util.UUID.randomUUID().toString(), "config.schema", JSONObject().put("embed", "v=1;lang=en"),
                        io.panelassistant.android.CommandOrigin.user("0123456789abcdef0123456789abcdef").withConnection("relay-1"),
                    ) { answer.put(it) }
                    val reply = answer.poll(20, java.util.concurrent.TimeUnit.SECONDS)
                    assertEquals("applied", reply?.outcome)
                    return reply!!.result as JSONArray
                }
                for (language in listOf("de", "en")) {
                    val saved = client.submitForm("/api/v1/config", Parameters.build {
                        append("ui_language", language)
                    }) { accept(ContentType.Application.Json) }
                    assertEquals(HttpStatusCode.OK, saved.status)
                    assertEquals(language, config.uiLanguage)
                    val readBack = JSONObject(client.get("/api/v1/config").bodyAsText())
                    assertEquals(language, readBack.getJSONObject("settings").getString("ui_language"))
                    val fields = schema()
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

    @Test fun `a config read and settings writes answer the same over the stub socket as over HTTP`() =
        withFullReadServer { _, fixture ->
            testApplication {
                application { fixture.mount(this) }
                // The same request over each door in turn, so both see the same state. Over HTTP a settings write
                // asks for JSON, as the Configure page always has; without it the route answers its HTML form page.
                val http = mutableMapOf<String, io.ktor.client.statement.HttpResponse>()
                val socket = mutableMapOf<String, JSONObject>()
                createClient { install(io.ktor.client.plugins.websocket.WebSockets) }.webSocket("/ui") {
                    for ((id, language) in listOf("read" to null, "saved" to "de", "refused" to "unsupported-locale")) {
                        http[id] = if (language == null) client.get("/api/v1/config")
                        else client.submitForm("/api/v1/config", Parameters.build { append("ui_language", language) }) {
                            accept(ContentType.Application.Json)
                        }
                        // Put the setting back, so the socket's write changes it exactly as the HTTP write did.
                        if (id == "saved") client.submitForm("/api/v1/config", Parameters.build { append("ui_language", "auto") }) {
                            accept(ContentType.Application.Json)
                        }
                        val frame = if (language == null) JSONObject().put("op", "config")
                        else JSONObject().put("op", "settings").put("args", JSONObject().put("ui_language", language))
                        send(Frame.Text(frame.put("kind", "manage").put("command_id", id).toString()))
                        while (id !in socket) {
                            val answer = JSONObject((incoming.receive() as Frame.Text).readText())
                            if (answer.optString("outcome") != "pending_approval") socket[answer.getString("command_id")] = answer
                        }
                    }
                }
                val httpRead = http.getValue("read")
                val httpSaved = http.getValue("saved")
                val httpRefused = http.getValue("refused")
                fun body(text: String): String = runCatching { JSONObject(text).toString() }.getOrDefault(text)

                assertEquals(HttpStatusCode.OK, httpRead.status)
                assertEquals("applied", socket.getValue("read").getString("outcome"))
                assertEquals(body(httpRead.bodyAsText()), socket.getValue("read").get("result").toString())
                assertEquals(HttpStatusCode.OK, httpSaved.status)
                assertEquals("applied", socket.getValue("saved").getString("outcome"))
                assertEquals(body(httpSaved.bodyAsText()), socket.getValue("saved").get("result").toString())
                assertEquals(HttpStatusCode.BadRequest, httpRefused.status)
                assertEquals("refused", socket.getValue("refused").getString("outcome"))
                assertEquals("invalid_value", socket.getValue("refused").getString("code"))
                assertEquals(httpRefused.bodyAsText(), socket.getValue("refused").getString("result"))
            }
        }

    @Test fun `home dashboard refuses overlong routes before changing stored state`() =
        withRouteConfig { config, _, _, _ ->
            val spec = requireNotNull(SettingsRegistry.spec("home_dashboard"))
            assertTrue(config.applyBatch { config.setRaw(spec, "/lovelace/original") })
            val commands = liveEffectBridge(config) {}
            val server = routeServer(config) { key, value ->
                dispatchLiveSetting(key = key,
                    value = io.panelassistant.android.liveSettingValue(spec, value),
                    previousValue = config.getRaw(spec), handlers = commands)
                LiveSettingRequestOutcome.APPLIED
            }
            fun route(length: Int, char: Char = 'a') = "/lovelace/view?q=" + char.toString().repeat(length - "/lovelace/view?q=".length)
            testApplication {
                application { routing { route("/api/v1") {
                    with(server) { installDirectConfigPostRoute { Capabilities() } }
                } } }
                for (value in listOf(route(300), route(256).removePrefix("/"))) {
                    val before = config.getRaw(spec)
                    val response = client.submitForm("/api/v1/config", Parameters.build { append(spec.key, value) }) {
                        accept(ContentType.Application.Json)
                    }
                    assertEquals(HttpStatusCode.BadRequest, response.status)
                    assertTrue(response.bodyAsText().contains("must be at most 255 characters"))
                    assertEquals(before, config.getRaw(spec))
                }
                for (value in listOf(route(255), route(255, 'b').removePrefix("/"))) {
                    val expected = if (value.startsWith("/")) value else "/$value"
                    val response = client.submitForm("/api/v1/config", Parameters.build { append(spec.key, value) }) {
                        accept(ContentType.Application.Json)
                    }
                    assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
                    assertEquals(expected, config.getRaw(spec))
                    val descriptor = requireNotNull(io.panelassistant.android.panelassistant.PanelAssistantChannelCatalog.describe("home_dashboard"))
                    assertEquals(io.panelassistant.android.panelassistant.PanelAssistantWireValue.Known(expected),
                        io.panelassistant.android.panelassistant.PanelAssistantValueTranslation.translate(descriptor,
                            StateConverger.Observation.Known(config.getRaw(spec))))
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

    @Test fun `configure schema offers only system bars, native navigation and the volume pip the panel provides`() {
        for (caps in listOf(
            Capabilities(), Capabilities(hasNativeNavbar = true), Capabilities(hasAndroidStatusBar = true),
            Capabilities(hasSpeaker = true),
        )) {
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
                    assertFalse(byKey.getValue("screen").getBoolean("ha"), "screen is commandable")
                    assertFalse(byKey.getValue("volume").getBoolean("ha"), "volume is commandable")
                    // With a speaker the volume is the media player's, so there is no number to expose.
                    assertEquals(!caps.hasSpeaker, byKey.getValue("volume").getBoolean("available"))
                }
            }
        }
    }

    @Test fun `configure schema offers the expose pip only where hiding changes what Home Assistant receives`() =
        withFullReadServer { _, fixture ->
            testApplication {
                application { fixture.mount(this) }
                val response = client.get("/api/v1/config/schema")
                assertEquals(HttpStatusCode.OK, response.status)
                val entries = JSONArray(response.bodyAsText())
                val rows = (0 until entries.length()).map(entries::getJSONObject).associateBy { it.getString("key") }
                // These settings are Panel Assistant entities that report and take commands whatever the pip
                // says: the exposure flag reaches neither their channel nor their descriptor. Offering the pip
                // promises a change in Home Assistant that never happens.
                val commandable = listOf(
                    "auto_sleep", "navbar_mode", "wake_on_wave", "kiosk_lock",
                    "touch_sound", "auto_brightness", "cpu_governor", "network_adb",
                ).filter(rows::containsKey)
                assertTrue("auto_sleep" in commandable && "touch_sound" in commandable, "commandable rows are in the schema")
                for (key in commandable) {
                    assertFalse(rows.getValue(key).getBoolean("ha"), "$key offers an expose pip that changes nothing")
                }
                // Read-only sensors keep the pip, because it withholds their reading from Home Assistant.
                assertTrue(rows.getValue("diag_cpu").getBoolean("ha"), "a diagnostic sensor keeps its pip")
                assertTrue(rows.getValue("illuminance").getBoolean("ha"), "a telemetry sensor keeps its pip")
            }
        }

    /** A bundle import and a revision restore change the interface language the panel's own screens use at once. */
    @Test fun `an imported or restored interface language applies the native locale at once`() =
        withRouteConfig { config, _, server, _ ->
            val observations = PaneldServer::class.java.getDeclaredField("managementObservations")
                .apply { isAccessible = true }.get(server) as ManagementObservations
            val observedHardware = requireNotNull(observations.snapCache.peek())
            // The panel is running in French, as any earlier save would have left it.
            config.setRaw(requireNotNull(SettingsRegistry.spec("ui_language")), "fr")
            io.panelassistant.android.NativeLocale.apply("fr")
            try {
                testApplication {
                    application {
                        paneldRoot({ emptySet() }, { false }, { "/setup" }) {
                            route("/api/v1") { with(server) { installConfigBundleRoutes() } }
                        }
                    }
                    val imported = client.post("/api/v1/config/import") {
                        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        setBody(ConfigBundle.fromValues(mapOf("ui_language" to "de")).serialize())
                    }
                    assertEquals(HttpStatusCode.OK, imported.status, imported.bodyAsText())
                    assertEquals("applied", JSONObject(imported.bodyAsText()).getString("status"))
                    assertEquals("de", config.uiLanguage)
                    assertEquals(
                        "de", androidx.appcompat.app.AppCompatDelegate.getApplicationLocales().toLanguageTags(),
                        "an imported language must reach the native locale, not wait for a restart",
                    )

                    observations.snapCache.set(observedHardware)
                    val revisions = JSONArray(client.get("/api/v1/config/revisions").bodyAsText())
                    val restored = client.post("/api/v1/config/revisions/${revisions.getJSONObject(0).getLong("id")}/restore")
                    assertEquals(HttpStatusCode.OK, restored.status, restored.bodyAsText())
                    assertEquals("fr", config.uiLanguage)
                    assertEquals(
                        "fr", androidx.appcompat.app.AppCompatDelegate.getApplicationLocales().toLanguageTags(),
                        "a restored language must reach the native locale, not wait for a restart",
                    )
                }
            } finally {
                io.panelassistant.android.NativeLocale.apply("en")
                androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                    androidx.core.os.LocaleListCompat.getEmptyLocaleList(),
                )
            }
        }

    @Test fun `a lone Home Assistant address change on the built-in renderer is refused instead of signing the panel out`() =
        withRouteConfig { config, _, server, live ->
            assertTrue(config.applyBatch {
                config.setDashboardPackage(SystemController.BUILTIN_DASHBOARD)
                config.setHaConnection("http://ha.test", "signed-in-token")
            })
            assertEquals(Config.SecurityMode.RELAXED, config.securityMode)
            testApplication {
                application {
                    paneldRoot({ emptySet() }, { false }, { "/setup" }) {
                        route("/api/v1") { with(server) { installDirectConfigPostRoute { Capabilities() } } }
                    }
                }
                val moved = client.submitForm("/api/v1/config", Parameters.build {
                    append("ha_url", "http://ha-moved.test")
                }) { accept(ContentType.Application.Json) }
                assertEquals(HttpStatusCode.Conflict, moved.status, moved.bodyAsText())
                assertEquals("ha-sign-in-required", JSONObject(moved.bodyAsText()).getString("error"))
                assertEquals("http://ha.test", config.haUrl)
                assertEquals("signed-in-token", config.haToken)
                assertTrue(live.isEmpty(), "a refused save must not reconfigure the renderer")

                // The same address (trailing slash only) is not an origin change and stays admissible.
                val same = client.submitForm("/api/v1/config", Parameters.build {
                    append("ha_url", "http://ha.test/")
                    append("friendly_name", "Still signed in")
                }) { accept(ContentType.Application.Json) }
                // This rig's renderer stand-in cannot prepare the Built-in renderer, so the save answers with
                // that effect failed; what matters is that it was admitted and committed.
                assertTrue(same.status != HttpStatusCode.Conflict, same.bodyAsText())
                assertEquals("Still signed in", config.friendlyName)
                assertEquals("signed-in-token", config.haToken)
            }
        }

    @Test fun `a first Home Assistant address keeps a credential that arrived before it`() =
        withRouteConfig { config, _, server, _ ->
            assertTrue(config.applyBatch {
                config.setDashboardPackage(SystemController.BUILTIN_DASHBOARD)
                config.setHaRefreshToken("first-refresh")
            })
            testApplication {
                application {
                    paneldRoot({ emptySet() }, { false }, { "/setup" }) {
                        route("/api/v1") { with(server) { installDirectConfigPostRoute { Capabilities() } } }
                    }
                }
                // Configure commits fields one at a time, so a token can be stored before any address.
                val first = client.submitForm("/api/v1/config", Parameters.build {
                    append("ha_url", "http://ha.test")
                }) { accept(ContentType.Application.Json) }
                // Admitted and committed (this rig's renderer stand-in fails only the Built-in renderer effect).
                assertTrue(first.status != HttpStatusCode.Conflict, first.bodyAsText())
                assertEquals("http://ha.test", config.haUrl)
                assertEquals("first-refresh", config.haRefreshToken)
            }
        }

    @Test fun `full mount config reads redact secrets preserve source separation and localize schema`() =
        withFullReadServer { config, fixture ->
            config.setHaConnection("http://ha.test", "private-test-password")
            setField(fixture.server, "configLiveValues", { mapOf("keep_awake" to "false") })
            testApplication {
                application { fixture.mount(this) }
                val response = client.get("/api/v1/config")
                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
                val text = response.bodyAsText()
                val body = JSONObject(text)
                assertFalse(text.contains("private-test-password"), "a credential is never read back")
                assertEquals("", body.getJSONObject("settings").getString("ha_token"))
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
                ConfigDiscoverySuggestions(haUrl = "http://ha.test")
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
                assertEquals(setOf("ha_url"), found.keys().asSequence().toSet())
                assertEquals("http://ha.test", found.getString("ha_url"))
                assertEquals("", config.haUrl)
                config.setHaConnection("http://configured.test", null)
                val configured = JSONObject(client.get("/api/v1/config/discovery").bodyAsText())
                assertEquals("", configured.getString("ha_url"))
                assertEquals(1, discoveries)
            }
        }

    @Test fun `the socket imports the bundle text the page sends as its body`() =
        withRouteConfig { config, _, server, _ ->
            val table = io.panelassistant.android.ManageTable()
            testApplication {
                application {
                    paneldRoot({ emptySet() }, { false }, { "/setup" }) {
                        serveOperations(table)
                        route("/api/v1") { with(server) { installConfigBundleRoutes() } }
                        socketDoorOnto(table)
                    }
                }
                val bundle = ConfigBundle.fromValues(mapOf("friendly_name" to "Imported over the socket")).serialize()

                val reply = socketRequest("config.import", JSONObject().put("body", bundle))

                assertEquals("applied", reply.getString("outcome"), reply.toString())
                assertEquals("Imported over the socket", config.friendlyName)
            }
        }

    @Test fun `production bundle import exports redacted values and revision restore undoes the commit`() =
        withRouteConfig { config, _, server, _ ->
            val observations = PaneldServer::class.java.getDeclaredField("managementObservations")
                .apply { isAccessible = true }.get(server) as ManagementObservations
            val observedHardware = requireNotNull(observations.snapCache.peek())
            config.setHaConnection("http://ha.test", "private-test-value")
            config.setHaExposed("touch_sound", true)
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
                assertTrue("ha_token" !in bundle.values)
                assertEquals("true", bundle.values["ha_expose_touch_sound"])
                val imported = client.post("/api/v1/config/import") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    setBody(ConfigBundle.fromValues(mapOf(
                        "friendly_name" to "Imported panel", "ha_expose_touch_sound" to "false",
                    )).serialize())
                }
                assertEquals(HttpStatusCode.OK, imported.status, imported.bodyAsText())
                assertEquals("applied", JSONObject(imported.bodyAsText()).getString("status"))
                assertEquals("Imported panel", config.friendlyName)
                assertEquals(false, config.haExposed("touch_sound", true))
                observations.snapCache.set(observedHardware)
                    val revisions = JSONArray(client.get("/api/v1/config/revisions").bodyAsText())
                assertEquals(1, revisions.length())
                val restored = client.post("/api/v1/config/revisions/${revisions.getJSONObject(0).getLong("id")}/restore")
                assertEquals(HttpStatusCode.OK, restored.status, restored.bodyAsText())
                assertEquals("partial", JSONObject(restored.bodyAsText()).getString("status"))
                assertEquals("Contract panel", config.friendlyName)
                assertEquals(true, config.haExposed("touch_sound", false))
                assertEquals("private-test-value", config.haToken)
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

    @Test fun `production backup restores exposure choices and ordinary stored values`() =
        withFullReadServer { config, fixture ->
            config.setHaExposed("touch_sound", false)
            config.setPanelId("retained_panel")
            config.setRaw(requireNotNull(SettingsRegistry.spec("friendly_name")), "Original panel")
            val panelId = config.panelId
            val builder = fixture.backupBuilder(null, config) { spec, live -> effectiveSettingValue(config, spec, live) }
            builder.build(CompanionBackupRequest.EXCLUDED, "").use { artifact ->
                val manifest = JSONObject(io.panelassistant.android.backup.PanelBackup.readManifest(artifact.file, 1024 * 1024)!!)
                assertEquals("false", manifest.getJSONObject("config").getString("ha_expose_touch_sound"))
                config.setHaExposed("touch_sound", true)
                config.setRaw(requireNotNull(SettingsRegistry.spec("friendly_name")), "Changed panel")
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
                    assertEquals("partial", result.getString("status"), result.toString())
                    val configResult = result.getJSONObject("config")
                    assertEquals("partial", configResult.getString("status"))
                    val unavailable = configResult.getString("detail")
                    assertTrue(unavailable.contains("voice_enabled"), unavailable)
                    assertTrue(unavailable.contains("network_adb"), unavailable)
                    assertEquals(false, config.haExposed("touch_sound", true))
                    assertEquals("Original panel", config.friendlyName)
                    assertEquals(panelId, config.panelId)
                }
            }
        }

    @Test fun `backup and production restore retain explicit owner choices and report failed choices`() =
        withFullReadServer { config, fixture ->
            val row = io.panelassistant.android.persistence.DashboardIgnoredIssue("owner", "/lovelace", "0123456789abcdef", 1L)
            val issues = object : io.panelassistant.android.persistence.DashboardIssueCustody {
                val stored = linkedSetOf<io.panelassistant.android.persistence.DashboardIgnoredIssue>()
                var refuse = false
                override fun export() = listOf(row)
                override fun restore(rows: List<io.panelassistant.android.persistence.DashboardIgnoredIssue>): Int {
                    if (refuse) return 0
                    stored.addAll(rows)
                    return rows.count(stored::contains)
                }
            }
            config.setPanelId("owner_panel")
            config.ignoreUpdate("Panel Assistant", "1.0.0-preview1")
            config.prefs.edit().putString("ui_dashboard_layout", "[{\"id\":\"presence\",\"x\":2}]").apply()
            config.setApkUploadAllowed(false)
            val hidden = config.prefs.all.filterKeys { it in setOf("update_ignored", "ui_dashboard_layout", "allow_apk_upload") }
                .map { (key, value) -> io.panelassistant.android.persistence.ConfigVault.StateRow(
                    "config", key, if (value is Boolean) "boolean" else "string",
                    if (value is Boolean) if (value) "1" else "0" else value.toString(), 1L,
                ) }
            val builder = fixture.backupBuilder(null, config, dashboardIssues = issues, exportState = { hidden }) { spec, live ->
                effectiveSettingValue(config, spec, live)
            }
            builder.build(CompanionBackupRequest.EXCLUDED, "").use { artifact ->
                val bytes = rewriteManifest(artifact.file) { manifest ->
                    manifest.getJSONObject("entity_state").put("instance_key", "owner")
                }
                config.ignoreUpdate("Panel Assistant", "changed")
                config.prefs.edit().putString("ui_dashboard_layout", "changed").apply()
                config.setApkUploadAllowed(true)
                setField(fixture.server, "dashboardIssueCustody", issues)
                setField(fixture.server, "applySetting", { _: String, _: String -> LiveSettingRequestOutcome.APPLIED })
                setField(fixture.server, "onReconfigure", { _: Set<String> -> })
                setField(fixture.server, "onDurableStateRestored", {})
                val observations = PaneldServer::class.java.getDeclaredField("managementObservations")
                    .apply { isAccessible = true }.get(fixture.server) as ManagementObservations
                val snapshot = observations.snapCache.get()
                testApplication {
                    application { fixture.mount(this) }
                    suspend fun restore(payload: ByteArray): JSONObject {
                        observations.snapCache.set(snapshot)
                        val response = client.post("/api/v1/restore") { setBody(payload) }
                        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
                        kotlinx.coroutines.withTimeout(5_000) {
                            while (InstallProgress.running) kotlinx.coroutines.delay(10)
                        }
                        val status = JSONObject(InstallProgress.json())
                        assertTrue(status.has("result"), status.toString())
                        return status.getJSONObject("result")
                    }
                    repeat(2) {
                        val result = restore(bytes)
                        assertEquals("1.0.0-preview1", config.ignoredUpdates["Panel Assistant"])
                        assertEquals("[{\"id\":\"presence\",\"x\":2}]", config.prefs.getString("ui_dashboard_layout", ""))
                        assertEquals(false, config.apkUploadAllowed)
                        assertEquals(setOf(row), issues.stored)
                        assertEquals("succeeded", result.getJSONObject("state").getString("status"))
                        assertEquals(4, result.getJSONObject("state").getInt("items"))
                    }
                    issues.refuse = true
                    val failed = restore(bytes)
                    assertEquals("partial", failed.getString("status"))
                    assertEquals("partial", failed.getJSONObject("state").getString("status"))
                    assertTrue(failed.getJSONObject("state").getString("detail").contains("failed=1"))
                    assertEquals(false, config.apkUploadAllowed)
                    val malformed = rewriteManifest(artifact.file) {
                        it.put("ignored_dashboard_issues", org.json.JSONArray().put(JSONObject().put("fingerprint", "bad")))
                    }
                    config.prefs.edit().putString("ui_dashboard_layout", "keep on refusal").apply()
                    val response = client.post("/api/v1/restore") { setBody(malformed) }
                    assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
                    assertEquals("keep on refusal", config.prefs.getString("ui_dashboard_layout", ""))
                }
            }
        }

    /** The model table as the panel holds it: keyed by fingerprint, and only ever added to by a restore. */
    private class CalibrationTable(vararg rows: EntityCatalogStore.ProximityModelRow) :
        io.panelassistant.android.persistence.ProximityCalibrationCustody {
        val rows = rows.associateBy { it.fingerprint }.toMutableMap()
        override fun export() = rows.values.filter { it.ready }
        override fun restore(rows: List<EntityCatalogStore.ProximityModelRow>) =
            rows.count { this.rows.putIfAbsent(it.fingerprint, it) == null }
    }

    private fun savedCalibration(source: String = "android-hal|fixture-sensor|build=fixture") = EntityCatalogStore.ProximityModelRow(
        fingerprint = ProximityCalibrationRuntime.calibrationKey("nspanel_86", source),
        algorithmVersion = ProximityCalibrationRuntime.STORAGE_VERSION,
        behaviorSignature = "explicit-calibration-v2",
        snapshotJson = ProximityCalibrationRuntime.encode(
            ProximityCalibrationEngine.Calibration(mode = ProximityCalibrationEngine.Mode.RANGED, clearRaw = 120f, nearRaw = 900f),
        ),
        ready = true,
        updatedAt = 1_800_000_000_000L,
    )

    /**
     * Backup on one install, reinstall (an empty model table), restore: the calibration comes back, the
     * running runtime is told to take it up, and a calibration the new install already made is kept.
     * Onto another panel, or from a row that will not load, nothing is written.
     */
    @Test fun `a reinstall and restore of the same panel brings its proximity calibration back`() =
        withFullReadServer { config, fixture ->
            config.setPanelId("calibrated_panel")
            val saved = savedCalibration()
            val other = savedCalibration(source = "android-hal|other-sensor|build=fixture")
            val builder = fixture.backupBuilder(null, config, calibrations = CalibrationTable(saved, other)) { spec, live ->
                effectiveSettingValue(config, spec, live)
            }
            builder.build(CompanionBackupRequest.EXCLUDED, "").use { artifact ->
                setField(fixture.server, "applySetting", { _: String, _: String -> LiveSettingRequestOutcome.APPLIED })
                setField(fixture.server, "onReconfigure", { _: Set<String> -> })
                var reloads = 0
                setField(fixture.server, "onDurableStateRestored", { reloads++; Unit })
                // The fixture warms the 15 s management snapshot once; four restores can outlast it.
                val observations = PaneldServer::class.java.getDeclaredField("managementObservations")
                    .apply { isAccessible = true }.get(fixture.server) as ManagementObservations
                val snapshot = observations.snapCache.get()
                testApplication {
                    application { fixture.mount(this) }
                    suspend fun restore(bytes: ByteArray, into: CalibrationTable) {
                        setField(fixture.server, "proximityCalibrations", into)
                        observations.snapCache.set(snapshot)
                        val restored = client.post("/api/v1/restore") { setBody(bytes) }
                        assertEquals(HttpStatusCode.OK, restored.status, restored.bodyAsText())
                        kotlinx.coroutines.withTimeout(5_000) {
                            while (InstallProgress.running) kotlinx.coroutines.delay(10)
                        }
                    }

                    val reinstalled = CalibrationTable()
                    restore(artifact.file.readBytes(), reinstalled)
                    assertEquals(mapOf(saved.fingerprint to saved, other.fingerprint to other), reinstalled.rows.toMap())
                    assertEquals(1, reloads)

                    // A calibration made on this install since is never replaced by the archive's older copy.
                    val recalibrated = saved.copy(snapshotJson = ProximityCalibrationRuntime.encode(
                        ProximityCalibrationEngine.Calibration(mode = ProximityCalibrationEngine.Mode.RANGED, clearRaw = 130f, nearRaw = 950f),
                    ), updatedAt = saved.updatedAt + 1)
                    val kept = CalibrationTable(recalibrated)
                    restore(artifact.file.readBytes(), kept)
                    assertEquals(recalibrated, kept.rows[saved.fingerprint])
                    assertEquals(other, kept.rows[other.fingerprint])

                    // A malformed row is dropped on its own; the rest of the restore and the good row still land.
                    val damaged = rewriteManifest(artifact.file) { manifest ->
                        manifest.getJSONArray(ProximityCalibrationBackup.SECTION_KEY).getJSONObject(1).put("snapshot", "{\"version\":9}")
                    }
                    val partial = CalibrationTable()
                    restore(damaged, partial)
                    assertEquals(mapOf(saved.fingerprint to saved), partial.rows.toMap())

                    // Another panel: its hardware is not this one's, so nothing is written.
                    config.setPanelId("another_panel")
                    val elsewhere = CalibrationTable()
                    restore(artifact.file.readBytes(), elsewhere)
                    assertTrue(elsewhere.rows.isEmpty())
                }
            }
        }

    private fun rewriteManifest(archive: File, change: (JSONObject) -> Unit): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipFile(archive).use { source ->
            java.util.zip.ZipOutputStream(out).use { zip ->
                source.entries().asSequence().forEach { entry ->
                    var bytes = source.getInputStream(entry).readBytes()
                    if (entry.name == io.panelassistant.android.backup.PanelBackup.MANIFEST_ENTRY) {
                        bytes = JSONObject(String(bytes, Charsets.UTF_8)).also(change).toString().toByteArray(Charsets.UTF_8)
                    }
                    zip.putNextEntry(java.util.zip.ZipEntry(entry.name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }
        return out.toByteArray()
    }

    @Test fun `a backup written by an MQTT release restores supported values without its broker settings`() =
        withFullReadServer { config, fixture ->
            config.setRaw(requireNotNull(SettingsRegistry.spec("friendly_name")), "Original panel")
            config.setRaw(requireNotNull(SettingsRegistry.spec(SettingsRegistry.ADDRESS_FAMILY_KEY)), "Force IPv4")
            val builder = fixture.backupBuilder(null, config) { spec, live -> effectiveSettingValue(config, spec, live) }
            builder.build(CompanionBackupRequest.EXCLUDED, "").use { artifact ->
                // The last MQTT release wrote the same archive at the same schema, plus its broker settings and
                // whether it was connected.
                val legacy = Files.createTempFile(artifact.file.parentFile.toPath(), "legacy", ".zip").toFile()
                java.util.zip.ZipFile(artifact.file).use { source ->
                    java.util.zip.ZipOutputStream(legacy.outputStream()).use { out ->
                        source.entries().asSequence().forEach { entry ->
                            val bytes = source.getInputStream(entry).readBytes()
                            out.putNextEntry(java.util.zip.ZipEntry(entry.name))
                            if (entry.name == io.panelassistant.android.backup.PanelBackup.MANIFEST_ENTRY) {
                                val manifest = JSONObject(String(bytes, Charsets.UTF_8))
                                manifest.getJSONObject("config")
                                    .put("mqtt_broker", "tcp://ha.example.test:1883")
                                    .put("mqtt_user", "ha-paneld")
                                    .put("mqtt_password", "fixture-password")
                                manifest.put("mqtt_connected", true)
                                out.write(manifest.toString().toByteArray(Charsets.UTF_8))
                            } else {
                                out.write(bytes)
                            }
                            out.closeEntry()
                        }
                    }
                }
                config.setRaw(requireNotNull(SettingsRegistry.spec("friendly_name")), "Changed panel")
                config.setRaw(requireNotNull(SettingsRegistry.spec(SettingsRegistry.ADDRESS_FAMILY_KEY)), "Automatic")
                setField(fixture.server, "applySetting", { _: String, _: String -> LiveSettingRequestOutcome.APPLIED })
                setField(fixture.server, "onReconfigure", { _: Set<String> -> })
                testApplication {
                    application { fixture.mount(this) }
                    val restored = client.post("/api/v1/restore") { setBody(legacy.readBytes()) }
                    assertEquals(HttpStatusCode.OK, restored.status, restored.bodyAsText())
                    kotlinx.coroutines.withTimeout(5_000) {
                        while (InstallProgress.running) kotlinx.coroutines.delay(10)
                    }
                    val result = JSONObject(InstallProgress.json()).getJSONObject("result")
                    assertEquals("partial", result.getString("status"), result.toString())
                    val configResult = result.getJSONObject("config")
                    assertEquals("partial", configResult.getString("status"))
                    val unavailable = configResult.getString("detail")
                    assertTrue(unavailable.contains("voice_enabled"), unavailable)
                    assertTrue(unavailable.contains("network_adb"), unavailable)
                    assertEquals("Original panel", config.friendlyName)
                    assertEquals("Force IPv4", config.addressFamily)
                    val exported = requireNotNull(ConfigBundle.parse(client.get("/api/v1/config/export").bodyAsText()))
                    assertTrue(exported.values.keys.none { it.startsWith("mqtt_") }, exported.values.keys.toString())
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

    @Test fun `a concurrent successful save makes the older config POST return conflict without effects`() =
        assertConcurrentConfigConflict(
            staleValues = mapOf("friendly_name" to "Older edit", "home_dashboard" to "/lovelace/older"),
            winningValues = mapOf("friendly_name" to "Newer edit"),
        )

    @Test fun `a concurrent brightness range save refuses an older now invalid range without effects`() =
        assertConcurrentConfigConflict(
            staleValues = mapOf("auto_brightness_minimum_percent" to "50"),
            winningValues = mapOf("auto_brightness_maximum_percent" to "40"),
        )

    private fun assertConcurrentConfigConflict(
        staleValues: Map<String, String>,
        winningValues: Map<String, String>,
    ) = withRouteConfig { config, persistence, server, live ->
        assertTrue(config.applyBatch {
            config.setAutoBrightnessMinimumPercent(10)
            config.setAutoBrightnessMaximumPercent(100)
        })
        val validating = CompletableDeferred<Unit>()
        val resumeValidation = CompletableDeferred<Unit>()
        setField(server, "autoBrightnessHttpApi", object : AutoBrightnessHttpApi by AutoBrightnessHttpApi.UNAVAILABLE {
            override suspend fun validateHaSource(entityId: String): AutoBrightnessHttpValidation {
                val owner = config.haAuthSnapshot().stableOwner()
                validating.complete(Unit)
                resumeValidation.await()
                return AutoBrightnessHttpValidation(AutoBrightnessHttpAction.ok(), owner)
            }
        })
        testApplication {
            application {
                routing { route("/api/v1") { with(server) { installDirectConfigPostRoute { Capabilities() } } } }
            }
            suspend fun save(values: Map<String, String>) = client.submitForm(
                "/api/v1/config", Parameters.build { values.forEach { (key, value) -> append(key, value) } },
            ) { accept(ContentType.Application.Json) }
            coroutineScope {
                // Source validation is a real suspending admission boundary, before the mutation ticket.
                // Hold only that external answer while another HTTP caller commits a newer baseline.
                val stale = async { save(staleValues + ("auto_brightness_ha_entity" to "sensor.ambient_light")) }
                try {
                    withTimeout(10_000) { validating.await() }
                    val winner = save(winningValues)
                    assertEquals(HttpStatusCode.OK, winner.status, winner.bodyAsText())
                    assertEquals("saved", JSONObject(winner.bodyAsText()).getString("status"))
                    val saved = persistence.initialize()
                    winningValues.forEach { (key, value) ->
                        assertEquals(value, config.getRaw(requireNotNull(SettingsRegistry.spec(key))))
                    }
                    live.clear()
                    persistence.written.clear()
                    resumeValidation.complete(Unit)

                    val refused = withTimeout(10_000) { stale.await() }
                    val text = refused.bodyAsText()
                    assertEquals(HttpStatusCode.Conflict, refused.status, text)
                    val body = JSONObject(text)
                    assertEquals("configuration-stale", body.getString("status"), text)
                    for (name in listOf("applied", "pending", "rejected")) {
                        assertEquals(0, body.getJSONArray(name).length(), text)
                    }
                    assertEquals(saved, persistence.initialize(), "refusal must preserve the newer durable settings")
                    assertTrue(persistence.written.isEmpty(), "a stale request must write no settings")
                    assertTrue(live.isEmpty(), "a stale request must dispatch no live or reconfiguration effects")

                    val ticket = requireNotNull(InstallProgress.start("fixture")) {
                        "the refused request must release its mutation ticket"
                    }
                    InstallProgress.finish(ticket, "fixture complete")
                    val retry = save(mapOf("friendly_name" to "Fresh retry"))
                    assertEquals(HttpStatusCode.OK, retry.status, retry.bodyAsText())
                    assertEquals("Fresh retry", persistence.initialize()["friendly_name"])
                } finally {
                    resumeValidation.complete(Unit)
                    stale.cancelAndJoin()
                }
            }
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

    @Test fun `production config POST normalizes persists reads back and dispatches a live setting`() {
        val directory = scratchDirectory("config-post-route").toFile()
        val database = File(directory, "ha-paneld.db")
        val writer = Executors.newSingleThreadExecutor()
        val reopenedWriter = Executors.newSingleThreadExecutor()
        try {
            val config = Config(SqliteStatePreferences(JdbcStatePersistence(database), writer))
            assertTrue(config.applyBatch {
                config.setPanelId("contract-panel")
                config.setRaw(requireNotNull(SettingsRegistry.spec("friendly_name")), "Contract panel")
                config.setRaw(requireNotNull(SettingsRegistry.spec("manufacturer")), "Contract manufacturer")
                config.setRaw(requireNotNull(SettingsRegistry.spec("model")), "Contract model")
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
                        value = io.panelassistant.android.liveSettingValue(spec, value),
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

            assertEquals(1, runtimeRefreshes.size, "the concrete dispatcher effect owner must run")
            assertEquals("/lovelace/beta", config.getRaw(spec))
            // The live handler stores the route with apply(), which reaches SQLite on the writer thread: let that
            // write finish before a second owner reads the database, or this reads a write still in flight.
            writer.submit {}.get(10, java.util.concurrent.TimeUnit.SECONDS)
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
        val directory = scratchDirectory("config-post-auto-bounds").toFile()
        val database = File(directory, "ha-paneld.db")
        val writer = Executors.newSingleThreadExecutor()
        val reopenedWriter = Executors.newSingleThreadExecutor()
        try {
            val config = Config(SqliteStatePreferences(JdbcStatePersistence(database), writer))
            assertTrue(config.applyBatch {
                config.setPanelId("contract-panel")
                config.setRaw(requireNotNull(SettingsRegistry.spec("friendly_name")), "Contract panel")
                config.setRaw(requireNotNull(SettingsRegistry.spec("manufacturer")), "Contract manufacturer")
                config.setRaw(requireNotNull(SettingsRegistry.spec("model")), "Contract model")
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
        val directory = scratchDirectory("config-post-registry-only").toFile()
        val database = File(directory, "ha-paneld.db")
        val writer = Executors.newSingleThreadExecutor()
        val reopenedWriter = Executors.newSingleThreadExecutor()
        try {
            val config = Config(SqliteStatePreferences(JdbcStatePersistence(database), writer))
            assertTrue(config.applyBatch {
                config.setPanelId("contract-panel")
                config.setRaw(requireNotNull(SettingsRegistry.spec("friendly_name")), "Contract panel")
                config.setRaw(requireNotNull(SettingsRegistry.spec("manufacturer")), "Contract manufacturer")
                config.setRaw(requireNotNull(SettingsRegistry.spec("model")), "Contract model")
            })
            val posted = linkedMapOf(
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

    @Test fun `a Configure save writes only the settings it changes`() {
        val directory = scratchDirectory("config-post-write-set").toFile()
        val database = File(directory, "ha-paneld.db")
        val writer = Executors.newSingleThreadExecutor()
        try {
            val persistence = JdbcStatePersistence(database)
            val config = Config(SqliteStatePreferences(persistence, writer))
            assertTrue(config.applyBatch {
                config.setPanelId("contract_panel")
                config.setRaw(requireNotNull(SettingsRegistry.spec("friendly_name")), "Contract panel")
                config.setRaw(requireNotNull(SettingsRegistry.spec("manufacturer")), "Contract manufacturer")
                config.setRaw(requireNotNull(SettingsRegistry.spec("model")), "Contract model")
                config.setDashboardPackage("com.example.dashboard")
            })
            // The Configure form posts every field, so a save that renames the panel resubmits the rest unchanged.
            val unchanged = listOf(
                "panel_id", "manufacturer", "model", "keep_awake", "room_temp_offset",
                "dashboard_package", "launcher_package", "dashboard_fullscreen", "dashboard_native_kiosk",
                "dashboard_zoom", "dark_mode", "dashboard_theme",
            ).associateWith { config.getRaw(requireNotNull(SettingsRegistry.spec(it))) }
            val server = routeServer(config) { key, _ -> error("$key must not reach the live-setting lane") }
            persistence.written.clear()

            testApplication {
                application {
                    routing { route("/api/v1") { with(server) { installDirectConfigPostRoute { Capabilities() } } } }
                }
                val response = client.submitForm(
                    url = "/api/v1/config",
                    formParameters = Parameters.build {
                        unchanged.forEach { (key, value) -> append(key, value) }
                        append("friendly_name", "Renamed panel")
                    },
                ) { accept(ContentType.Application.Json) }
                assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            }

            assertEquals("Renamed panel", config.friendlyName)
            val written = persistence.written.flatten()
            assertEquals(1, written.count { it == "friendly_name" }, "written: $written")
            assertEquals(emptySet(), unchanged.keys.intersect(written.toSet()), "resubmitted values were rewritten: ${persistence.written}")
        } finally {
            writer.shutdownNow()
            directory.deleteRecursively()
        }
    }

    /**
     * Build the smallest genuine production effect owner needed by this route case. The bridge's
     * home-dashboard handler owns both the durable Config write and the entity-learning target refresh; an empty
     * converger is sufficient because publication is deliberately a no-op without a registered channel.
     */
    private fun liveEffectBridge(config: Config, onDashboardTargetChanged: () -> Unit): PanelCommandDispatcher =
        allocate(PanelCommandDispatcher::class.java).also { commands ->
            setField(commands, "config", config)
            setField(commands, "onDashboardTargetChanged", onDashboardTargetChanged)
            setField(
                commands,
                "core",
                allocate(PanelStateCore::class.java).also { setField(it, "converger", StateConverger()) },
            )
        }

    /**
     * Allocate only the production handler owner, then populate the collaborators the direct-config route
     * actually uses. This avoids pretending android.jar Context/services are functional on the JVM while
     * leaving Ktor registration, request decoding, transaction planning, response construction and the
     * private production handler intact.
     */
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
