package io.github.maxlyth.hapaneld.http

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiRouteSpecContractTest {
    @Test fun activeLiteralMachineRoutesMatchOpenApiMethods() {
        val working = File(requireNotNull(System.getProperty("user.dir")))
        val root = listOf(File(working, "app"), working).first {
            File(it, "src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt").isFile
        }
        val panel = File(root, "src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt").readText()
        val apiStart = panel.indexOf("route(\"/api/v1\")")
        val apiBlock = panel.substring(apiStart, panel.indexOf("startOwnedHttpServer(", apiStart))
        val active = literalRoutes(apiBlock).mapTo(linkedSetOf()) { (method, path) ->
            "$method /api/v1$path"
        }

        val directConfigStart = panel.indexOf("internal fun Route.installDirectConfigPostRoute")
        val directConfigBlock = panel.substring(
            directConfigStart,
            panel.indexOf("private suspend fun handleConfigPost", directConfigStart),
        )
        literalRoutes(directConfigBlock).mapTo(active) { (method, path) -> "$method /api/v1$path" }

        val profiles = File(root, "src/main/kotlin/io/github/maxlyth/hapaneld/http/ProfileRoutes.kt").readText()
        val activeProfiles = profiles.substring(
            profiles.indexOf("fun Route.profileRoutes"),
            profiles.indexOf("fun Route.unavailableProfileRoutes"),
        )
        literalRoutes(activeProfiles).mapTo(active) { (method, path) -> "$method /api/v1/profiles$path" }
        active += "GET /api/v1/profiles" // route("/profiles") { get { … } }

        val provisioning = File(root, "src/main/kotlin/io/github/maxlyth/hapaneld/http/ProvisioningRoutes.kt").readText()
        literalRoutes(provisioning).mapTo(active) { (method, path) -> "$method /api/v1/provisioning$path" }

        val configReads = File(root, "src/main/kotlin/io/github/maxlyth/hapaneld/http/ConfigReadRoutes.kt").readText()
        literalRoutes(configReads).mapTo(active) { (method, path) -> "$method /api/v1$path" }

        val haOAuth = File(root, "src/main/kotlin/io/github/maxlyth/hapaneld/http/HaOAuthRoutes.kt").readText()
        literalRoutes(haOAuth).mapTo(active) { (method, path) -> "$method /api/v1/ha/oauth$path" }

        val control = File(root, "src/main/kotlin/io/github/maxlyth/hapaneld/http/ControlPlaneRoutes.kt").readText()
        active += "POST /play"
        active += "POST /api/v1/play"
        literalRoutes(control).filter { it.second != "/play" }.mapTo(active) { (method, path) ->
            "$method /api/v1$path"
        }
        active += "GET /health"

        val handBackHome = File(root, "src/main/kotlin/io/github/maxlyth/hapaneld/http/HandBackHomeRoutes.kt").readText()
        literalRoutes(handBackHome).mapTo(active) { (method, path) -> "$method /api/v1$path" }

        val panelAssistantTransport =
            File(root, "src/main/kotlin/io/github/maxlyth/hapaneld/http/PanelAssistantTransportRoutes.kt").readText()
        literalRoutes(panelAssistantTransport).mapTo(active) { (method, path) -> "$method /api/v1$path" }

        val guardDb = File(root, "src/main/kotlin/io/github/maxlyth/hapaneld/http/GuardDbBootstrapRoutes.kt").readText()
        literalRoutes(guardDb).mapTo(active) { (method, path) ->
            "$method /api/v1/guard-db$path"
        }

        val guardDbMaintenance =
            File(root, "src/main/kotlin/io/github/maxlyth/hapaneld/http/GuardDbMaintenanceServer.kt").readText()
        literalRoutes(guardDbMaintenance).forEach { (method, path) ->
            if (path == "/health") active += "$method $path"
            else active += "$method /api/v1/guard-db$path"
        }

        val spec = JSONObject(File(root, "src/main/assets/openapi.json").readText()).getJSONObject("paths")
        val documented = linkedSetOf<String>()
        for (path in spec.keys()) {
            val operations = spec.getJSONObject(path)
            for (method in operations.keys()) {
                if (method in setOf("get", "post")) {
                    documented += "${method.uppercase()} $path"
                }
            }
        }

        assertEquals(
            "OpenAPI must describe every active literal machine route and no removed route",
            active.sorted(),
            documented.sorted(),
        )
    }

    @Test fun explorerSupportsParametersAndStructuredOrBinaryBodies() {
        val html = asset("api.html").readText()
        val script = asset("api.js").readText()
        assertTrue(
            "the explorer behavior must load from its shipped external script",
            html.contains("<script src=\"assets/api.js\"></script>"),
        )
        assertTrue("path parameters must be substituted", "where === \"path\"" in script)
        assertTrue("query parameters must be encoded", "where === \"query\"" in script)
        assertTrue("header parameters must be sent", "where === \"header\"" in script)
        assertTrue("JSON bodies must be selectable", "\"application/json\"" in script)
        assertTrue("YAML bodies must be selectable", "\"application/yaml\"" in script)
        assertTrue("binary restore/APK bodies must use a file input", "file.type = \"file\"" in script)
    }

    @Test fun peersSpecDescribesThePersistentRosterWithoutAnIgnoredRefreshParameter() {
        val peers = JSONObject(asset("openapi.json").readText())
            .getJSONObject("paths")
            .getJSONObject("/api/v1/peers")
            .getJSONObject("get")
        assertTrue(peers.getString("summary").contains("persistent roster"))
        assertFalse(peers.has("parameters"))
    }

    @Test fun installConfigImportConsumesPreviewHash() {
        val source = asset("install.js").readText()
        assertTrue("import must preview before applying", "api/v1/config/import?dry_run=1" in source)
        assertTrue("apply must carry the preview config hash", "expected_cfg=" in source && "dry.expected_cfg" in source)
        assertTrue("stale apply must require a fresh preview", "response.status === 409" in source)
    }

    private fun literalRoutes(source: String): List<Pair<String, String>> =
        Regex("(?m)^\\s*(get|post)\\(\"([^\"]+)\"")
            .findAll(source)
            .map { it.groupValues[1].uppercase() to it.groupValues[2] }
            .toList()

    private fun asset(name: String): File {
        val working = File(requireNotNull(System.getProperty("user.dir")))
        return listOf(File(working, "app/src/main/assets/$name"), File(working, "src/main/assets/$name"))
            .first { it.isFile }
    }
}
