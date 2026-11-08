package io.panelassistant.android.http

import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.formUrlEncode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.panelassistant.android.ManageKind
import io.panelassistant.android.ManageTable
import io.panelassistant.android.security.LocalApprovalBroker
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Every operation the production mount registers answers alike through the stub's `/ui` socket and over HTTP: the
 * same representative request, as the page transport sends it on each door, against the same fixture state (a fresh
 * fixture for each side), gives the same outcome and the same body. An operation the fixture cannot drive is listed
 * in [UNDRIVEN] with its reason, and the list must stay exact. The fixture is the full root mount over a real
 * SQLite-backed configuration ([withFullReadServer]).
 */
class SocketHttpParityMatrixTest {
    @Test fun everyRegisteredOperationAnswersAlikeOverTheSocketAndHttp() {
        var entries: List<ManageTable.Entry> = emptyList()
        withFullReadServer { _, fixture ->
            testApplication { application { fixture.mount(this) }; startApplication() }
            entries = fixture.manageTable.names().map { requireNotNull(fixture.manageTable[it]) }
        }
        val undriven = sortedMapOf<String, String>()
        val differences = mutableListOf<String>()
        for (entry in entries) {
            val route = entry.route
            if (route == null) { undriven[entry.name] = "no HTTP route"; continue }
            val args = REQUESTS[entry.name] ?: JSONObject().apply { if (entry.name in RAW_BODY) put("body", "") }
            val overHttp = onFreshFixture { http(route, entry.name, args) }
            val overSocket = onFreshFixture { socket(entry.name, args) }
            when {
                overHttp == null || overSocket == null -> undriven[entry.name] = "times out in the fixture"
                overHttp.status == 500 && overSocket.outcome == "failed" -> undriven[entry.name] = "throws in the fixture"
                else -> compare(entry, overHttp, overSocket)?.let { difference ->
                    // A listed operation the fixture cannot drive may also answer apart; it counts as undriven.
                    if (entry.name in UNDRIVEN) undriven[entry.name] = difference else differences += "${entry.name}: $difference"
                }
            }
        }
        println("parity matrix: ${entries.size} operations, ${undriven.size} undriven")
        undriven.forEach { (name, why) -> println("undriven $name: $why") }
        assertEquals("operations that answer differently over the socket and HTTP", emptyList<String>(), differences)
        assertEquals("operations the matrix cannot drive", UNDRIVEN.keys.sorted(), undriven.keys.toList())
    }

    private class HttpAnswer(val status: Int, val type: ContentType?, val bytes: ByteArray, val disposition: String?)
    private class SocketAnswer(val outcome: String, val code: String?, val result: Any?, val bytes: ByteArray?, val disposition: String?)

    private fun <T> onFreshFixture(block: suspend ApplicationTestBuilder.() -> T): T? {
        var result: T? = null
        LocalApprovalBroker.instance.clear()
        withFullReadServer { _, fixture ->
            // Collaborators the full read server leaves out but these reads need, at their production defaults.
            mapOf<String, Any>(
                "peers" to { emptyList<io.panelassistant.android.Peer>() },
                "effectiveBrightness" to { -1 },
                "voiceMicrophone" to { null },
                "configDiscoverySuggestions" to { ConfigDiscoverySuggestions() },
            ).forEach { (name, value) ->
                PaneldServer::class.java.getDeclaredField(name).apply { isAccessible = true }.set(fixture.server, value)
            }
            testApplication {
                application { fixture.mount(this) }
                result = withTimeoutOrNull(DEADLINE_MS) { block() }
            }
        }
        return result
    }

    private suspend fun ApplicationTestBuilder.http(route: ManageTable.Route, name: String, args: JSONObject): HttpAnswer {
        val all = JSONObject(args.toString()).apply { route.fixedArgs.forEach { (k, v) -> put(k, v) } }
        var path = route.path
        Regex("\\{([a-zA-Z_]+)}").findAll(route.path).forEach { match ->
            path = path.replace(match.value, all.optString(match.groupValues[1]))
            all.remove(match.groupValues[1])
        }
        val pairs = all.keys().asSequence().map { it to all.get(it).toString() }.toList()
        val response: HttpResponse = client.request(path) {
            method = HttpMethod.parse(route.method)
            header(HttpHeaders.Accept, ContentType.Application.Json.toString())
            header(HttpHeaders.AcceptLanguage, LANGUAGE)
            // A same-origin page, as the browser marks one.
            header(HttpHeaders.Host, "localhost")
            header(HttpHeaders.Origin, "http://localhost")
            header("Sec-Fetch-Site", "same-origin")
            FIXED_HEADERS[name]?.forEach { (k, v) -> header(k, v) }
            if (method == HttpMethod.Get) {
                if (pairs.isNotEmpty()) url { pairs.forEach { (k, v) -> parameters.append(k, v) } }
            } else when (name) {
                in JSON_BODY -> { header(HttpHeaders.ContentType, ContentType.Application.Json.toString()); setBody(args.toString()) }
                in RAW_BODY -> { header(HttpHeaders.ContentType, ContentType.Text.Plain.toString()); setBody(args.optString("body")) }
                else -> { header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString()); setBody(pairs.formUrlEncode()) }
            }
        }
        return HttpAnswer(
            response.status.value, response.headers[HttpHeaders.ContentType]?.let(ContentType::parse),
            response.bodyAsBytes(), response.headers[HttpHeaders.ContentDisposition],
        )
    }

    private suspend fun ApplicationTestBuilder.socket(name: String, args: JSONObject): SocketAnswer {
        lateinit var answer: JSONObject
        createClient { install(WebSockets) }.webSocket("/ui", request = {
            header(HttpHeaders.Host, "localhost")
            header(HttpHeaders.AcceptLanguage, LANGUAGE)
        }) {
            send(Frame.Text(JSONObject().put("kind", "manage").put("command_id", "m1").put("op", name).put("args", args).toString()))
            while (true) {
                val frame = JSONObject((incoming.receive() as Frame.Text).readText())
                if (frame.optString("command_id") == "m1" && !frame.optBoolean("interim")) { answer = frame; break }
            }
        }
        val result = answer.opt("result")
        val blob = (result as? JSONObject)?.optJSONObject("blob")
        val bytes = blob?.let { client.get(it.getString("url")).bodyAsBytes() }
        val disposition = answer.optJSONObject("headers")?.optString("content-disposition")?.ifEmpty { null }
        return SocketAnswer(answer.getString("outcome"), answer.optString("code").ifEmpty { null }, result, bytes, disposition)
    }

    /** Null when the two answers agree; otherwise what differs. */
    private fun compare(entry: ManageTable.Entry, http: HttpAnswer, socket: SocketAnswer): String? {
        val expected = manageReplyFor(http.status, null, null, emptyList())
        if (expected.outcome != socket.outcome || expected.code != socket.code) {
            return "HTTP ${http.status} reads ${expected.outcome}/${expected.code}, socket ${socket.outcome}/${socket.code}"
        }
        if (http.disposition != socket.disposition) return "download named ${http.disposition} vs ${socket.disposition}"
        val text = String(http.bytes, Charsets.UTF_8)
        if (socket.bytes != null) {
            if (socket.bytes.contentEquals(http.bytes)) return null
            val both = runCatching { JSONTokener(text).nextValue() to JSONTokener(String(socket.bytes, Charsets.UTF_8)).nextValue() }.getOrNull()
            return if (both != null && canonical(both.first) == canonical(both.second)) null else "blob bytes differ"
        }
        val json = http.type?.match(ContentType.Application.Json) == true
        return when {
            json && text.isNotBlank() -> {
                val same = canonical(JSONTokener(text).nextValue()) == canonical(socket.result)
                if (same) null else "body ${text.take(200)} vs ${socket.result.toString().take(200)}"
            }
            text.isEmpty() -> if (socket.result == null || socket.result == "" || (socket.result as? JSONObject)?.length() == 0) null else "empty body vs ${socket.result.toString().take(200)}"
            else -> if (socket.result == text) null else "text ${text.take(200)} vs ${socket.result.toString().take(200)}"
        }.also { if (entry.kind == ManageKind.READ && it != null) Unit }
    }

    /** A JSON value with its object keys sorted, so two encodings of one value compare equal. */
    private fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().filter { it !in VOLATILE }.sorted()
            .joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.opt(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.opt(it)) }
        is String -> JSONObject.quote(value)
        is Number -> value.toDouble().toString()
        null, JSONObject.NULL -> "null"
        else -> value.toString()
    }

    private companion object {
        const val DEADLINE_MS = 8_000L
        /** Not English, so a door that dropped the browser's language answers differently. */
        const val LANGUAGE = "de"

        /** Keys whose values are the moment of the request, which two fixtures never share. */
        val VOLATILE = setOf("exported_at")

        /** Representative requests where an empty one would not reach the handler's work. */
        val REQUESTS: Map<String, JSONObject> = mapOf(
            "settings" to JSONObject().put("friendly_name", "Parity panel"),
        )

        /** Routes the page transport sends a JSON body to (transport.js `b: "json"`). */
        val JSON_BODY = setOf(
            "auto_sleep.source", "auto_sleep.presence_sources.choose", "dashboard.entity_filter.set", "dashboard.entities.activate", "dashboard.entities.issues.set", "dashboard.entities.override",
            "dashboard.entities.overrides", "dashboard.entities.policy", "dashboard.entities.reset", "dashboard.entities.sync.set",
            "profiles.compare", "profiles.preview", "profiles.validate", "profiles.activate", "profiles.delete",
            "profiles.rollback", "profiles.save", "profiles.select", "voice.wake_words.set",
        )

        /** Routes the page transport sends a raw body to (transport.js `b: "raw"`). */
        val RAW_BODY = setOf("restore", "config.import", "install.apk", "profiles.probe", "profiles.import")

        /** Fixed request headers the page transport adds (transport.js `H`). */
        val FIXED_HEADERS = mapOf("proximity.calibration" to mapOf("X-Proximity-UI" to "1"))

        /** Operations this fixture cannot drive on either door, and why. */
        val UNDRIVEN: Map<String, String> = mapOf(
            "action.dashboard" to "needs the remote control owner, which the read server leaves out; SocketDoorParityTest drives it",
            "apps" to "lists launchers through Android's PackageManager, absent on the JVM",
            "companion.repair_url" to "the fixture refuses a Companion repair",
            "dashboard.entities" to "needs the entity catalogue store, which the fixture's entity learning manager is built without",
            "dashboard.entities.export" to "needs the entity catalogue store, which the fixture's entity learning manager is built without; the export streams, so HTTP has sent 200 before its writer fails",
            "dashboard.entities.issues" to "needs the entity catalogue store, which the fixture's entity learning manager is built without",
            "dashboard.entities.sync" to "needs the entity catalogue store, which the fixture's entity learning manager is built without",
            "dashboard.entities.sync.set" to "needs the entity catalogue store, which the fixture's entity learning manager is built without",
            "dashboard.entity_filter" to "needs the entity catalogue store, which the fixture's entity learning manager is built without",
            "diag" to "the fixture refuses diagnostics collection",
            "display.density" to "needs the helper daemon the density controller talks to",
            "hand_back_home" to "needs the vendor home packages the system controller is built with",
            "info" to "renders from the setup journey, which needs the device profile and an Android context",
            "page.configure" to "renders from the setup journey, which needs the device profile and an Android context",
            "page.entities" to "renders from the setup journey, which needs the device profile and an Android context",
            "page.info" to "renders from the setup journey, which needs the device profile and an Android context",
            "page.install" to "renders from the setup journey, which needs the device profile and an Android context",
            "page.logs" to "renders from the setup journey, which needs the device profile and an Android context",
            "page.profiles" to "renders from the setup journey, which needs the device profile and an Android context",
            "page.setup" to "renders from the setup journey, which needs the device profile and an Android context",
            "power_safety.repair" to "the fixture refuses a power-safety repair",
            "sensors" to "needs live sensor readings; the fixture's sensor reporter is allocated without them",
            "setup" to "renders from the setup journey, which needs the device profile and an Android context",
            "setup.attest" to "renders from the setup journey, which needs the device profile and an Android context",
            "setup.entity_filter" to "renders from the setup journey, which needs the device profile and an Android context",
            "setup.home_dashboard" to "renders from the setup journey, which needs the device profile and an Android context",
            "setup.identity" to "renders from the setup journey, which needs the device profile and an Android context",
            "status" to "reads the package version through Android's PackageManager",
            "tame" to "needs the tame controller, which the fixture leaves out",
        )
    }
}
