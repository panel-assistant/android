package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.Config
import io.github.maxlyth.hapaneld.LiveSettingApplyResult
import io.github.maxlyth.hapaneld.LiveSettingAuthority
import io.github.maxlyth.hapaneld.LiveSettingRequestOutcome
import io.github.maxlyth.hapaneld.MqttBridge
import io.github.maxlyth.hapaneld.dispatchLiveSetting
import io.github.maxlyth.hapaneld.config.Capabilities
import io.github.maxlyth.hapaneld.config.ConfigBundle
import io.github.maxlyth.hapaneld.config.SettingsRegistry
import io.github.maxlyth.hapaneld.control.PowerRiskLevel
import io.github.maxlyth.hapaneld.control.PowerSafetyAssessment
import io.github.maxlyth.hapaneld.control.PowerSafetyObservation
import io.github.maxlyth.hapaneld.control.PrivilegedRouteObservation
import io.github.maxlyth.hapaneld.control.SystemController
import io.github.maxlyth.hapaneld.persistence.SqliteStatePreferences
import io.github.maxlyth.hapaneld.persistence.StateMutation
import io.github.maxlyth.hapaneld.persistence.StateNamespacePersistence
import io.github.maxlyth.hapaneld.platform.ActivityRef
import io.github.maxlyth.hapaneld.platform.SystemEnv
import io.github.maxlyth.hapaneld.mqtt.StateConverger
import io.github.maxlyth.hapaneld.sensors.SensorReporter
import io.github.maxlyth.hapaneld.shizuku.ShizukuBridge
import io.github.maxlyth.hapaneld.shizuku.ShizukuState
import io.github.maxlyth.hapaneld.security.LocalApprovalBroker
import io.github.maxlyth.hapaneld.util.Cached
import io.github.maxlyth.hapaneld.util.InstallProgress
import io.github.maxlyth.hapaneld.util.RendererPreparationCoordinator
import io.github.maxlyth.hapaneld.util.RendererPreparationState
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

    @Test fun `production bundle import exports redacted values and revision restore undoes the commit`() =
        withRouteConfig { config, _, server, _ ->
            assertTrue(config.applyBatch { config.setMqtt("", "test-user", "private-test-value") })
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
                val imported = client.post("/api/v1/config/import") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    setBody(ConfigBundle.fromValues(mapOf("friendly_name" to "Imported panel")).serialize())
                }
                assertEquals(HttpStatusCode.OK, imported.status, imported.bodyAsText())
                assertEquals("applied", JSONObject(imported.bodyAsText()).getString("status"))
                assertEquals("Imported panel", config.friendlyName)
                val revisions = JSONArray(client.get("/api/v1/config/revisions").bodyAsText())
                assertEquals(1, revisions.length())
                val restored = client.post("/api/v1/config/revisions/${revisions.getJSONObject(0).getLong("id")}/restore")
                assertEquals(HttpStatusCode.OK, restored.status, restored.bodyAsText())
                assertEquals("restored", JSONObject(restored.bodyAsText()).getString("status"))
                assertEquals("Contract panel", config.friendlyName)
                assertEquals(2, JSONArray(client.get("/api/v1/config/revisions").bodyAsText()).length())
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
            io.github.maxlyth.hapaneld.control.DensityController(canSu = false),
            { error("Stopped fixture must not probe") },
            { _, _ -> error("Unexpected diagnostics") },
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            { true },
        )
        observations.snapCache.set(snap)

        setField(server, "managementObservations", observations)
        setField(server, "config", config)
        setField(server, "system", SystemController(object : SystemEnv {
            override val ownPackage = "io.github.maxlyth.hapaneld"
            override fun isInstalled(pkg: String) = pkg == "com.example.dashboard"
            override fun launchComponent(pkg: String): String? = null
            override fun homeActivities(): List<ActivityRef> = emptyList()
            override fun defaultHome(): ActivityRef? = null
            override fun directStart(component: String) = Unit
        }))
        setField(server, "sensors", sensors)
        setField(server, "applySetting", applySetting)
        setField(server, "pendingLiveSettings", { emptyMap<String, String>() })
        setField(server, "stalledLiveSettings", { emptySet<String>() })
        setField(server, "configLiveValues", { emptyMap<String, String>() })
        setField(server, "onReconfigure", { _: Set<String> -> })
        setField(server, "onSelfUpdateChannelCommitted", {
            _: SelfUpdateChannelPreflight.Ready?, _: InstallProgress.Ticket?, before: String, after: String ->
            assertEquals(before, after, "Route fixture must not switch update channels")
        })
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
        val learning = allocate(io.github.maxlyth.hapaneld.dashboard.EntityLearningManager::class.java)
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
