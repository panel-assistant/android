package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.Config
import io.github.maxlyth.hapaneld.LiveSettingApplyResult
import io.github.maxlyth.hapaneld.LiveSettingAuthority
import io.github.maxlyth.hapaneld.LiveSettingRequestOutcome
import io.github.maxlyth.hapaneld.MqttBridge
import io.github.maxlyth.hapaneld.dispatchLiveSetting
import io.github.maxlyth.hapaneld.config.Capabilities
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
import io.github.maxlyth.hapaneld.util.Cached
import io.github.maxlyth.hapaneld.util.RendererPreparationCoordinator
import io.github.maxlyth.hapaneld.util.RendererPreparationState
import io.ktor.client.request.accept
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.Executors
import org.json.JSONObject
import org.junit.Test
import sun.misc.Unsafe
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfigPostProductionRouteTest {
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
        val snap = PaneldServer::class.java.declaredClasses
            .single { it.simpleName == "Snap" }
            .declaredConstructors.single().run {
                isAccessible = true
                newInstance(
                    emptyMap<String, String>(),
                    emptyMap<String, String>(),
                    Capabilities(),
                    emptyList<DiagReader.Cap>(),
                    privilege,
                    null,
                    null,
                    1.0f,
                    false,
                )
            }
        val snapCache = Cached<Any>(Long.MAX_VALUE) { snap }.also { it.set(snap) }

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
        setField(server, "rendererPreparation", renderer)
        setField(server, "directConfigMutationLock", Any())
        setField(server, "revisions", RevisionStore(Files.createTempDirectory("config-route-revisions").toFile()))
        setField(server, "snapCache", snapCache)
        setField(server, "diagCache", Cached<Any>(Long.MAX_VALUE) { Any() })
        setField(server, "densityCache", Cached<Any>(Long.MAX_VALUE) { Any() })
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

        private fun transaction(block: (Connection) -> Unit): Boolean = runCatching {
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
