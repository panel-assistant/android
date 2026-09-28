package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.sensors.HaPanelAreaPrerequisite
import io.github.maxlyth.hapaneld.sensors.HaPanelAreaPrerequisitePhase
import io.github.maxlyth.hapaneld.sensors.HaPresenceSourceUpdate
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.route
import io.ktor.server.testing.testApplication
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class AutoSleepRoutesTest {
    @Test fun `auto sleep routes preserve status admission validation and source outcomes`() = testApplication {
        var outcome = HaPresenceSourceUpdate.UPDATED
        var received = ""
        val api = object : AutoSleepHttpApi {
            override fun statusJson() = """{"phase":"live"}"""
            override suspend fun historyJson(hours: Int) = """{"hours":$hours}"""
            override suspend fun prerequisite() = HaPanelAreaPrerequisite(
                HaPanelAreaPrerequisitePhase.ASSIGNED, "Kitchen", "ok",
            )
            override fun setSourceIncluded(areaKey: String, sourceKey: String, included: Boolean): HaPresenceSourceUpdate {
                received = "$areaKey/$sourceKey/$included"
                return outcome
            }
            override fun noteAreaChanged() = Unit
        }
        application {
            paneldRoot({ emptySet() }, { false }, { "/setup" }) {
                route("/api/v1") {
                    autoSleepRoutes(api, { call ->
                        if (call.request.headers["Origin"] == "https://elsewhere.invalid") {
                            call.respondText("refused\n", status = HttpStatusCode.Forbidden)
                            false
                        } else true
                    }, { call, _ -> JSONObject(call.receiveText()) })
                }
            }
        }

        assertEquals("""{"phase":"live"}""", client.get("/api/v1/auto-sleep").bodyAsText())
        val prerequisite = client.get("/api/v1/auto-sleep/prerequisite")
        assertEquals(HttpStatusCode.OK, prerequisite.status)
        assertEquals(true, JSONObject(prerequisite.bodyAsText()).getBoolean("eligible"))
        assertEquals("assigned", JSONObject(prerequisite.bodyAsText()).getString("phase"))
        assertEquals("Kitchen", JSONObject(prerequisite.bodyAsText()).getString("area_name"))
        assertEquals("""{"hours":6}""", client.get("/api/v1/auto-sleep/history").bodyAsText())
        assertEquals("""{"hours":24}""", client.get("/api/v1/auto-sleep/history?hours=24").bodyAsText())
        val invalid = client.get("/api/v1/auto-sleep/history?hours=49")
        assertEquals(HttpStatusCode.BadRequest, invalid.status)
        assertEquals("hours must be between 1 and 48\n", invalid.bodyAsText())
        val crossOrigin = client.get("/api/v1/auto-sleep/history") { header(HttpHeaders.Origin, "https://elsewhere.invalid") }
        assertEquals(HttpStatusCode.Forbidden, crossOrigin.status)

        val key = "a".repeat(64)
        suspend fun source(body: String) = client.post("/api/v1/auto-sleep/source") {
            header(HttpHeaders.ContentType, "application/json")
            setBody(body)
        }
        val bad = source("""{"area_key":"bad","source_key":"$key","included":true}""")
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        assertEquals("area_key, source_key and included are required\n", bad.bodyAsText())
        assertEquals("", received)
        val body = """{"area_key":"$key","source_key":"$key","included":false}"""
        val updated = source(body)
        assertEquals(HttpStatusCode.OK, updated.status)
        assertEquals("""{"ok":true,"included":false}""", updated.bodyAsText())
        assertEquals("$key/$key/false", received)
        for ((state, status, text) in listOf(
            Triple(HaPresenceSourceUpdate.STALE, HttpStatusCode.Conflict, "activity sources changed; reload and try again\n"),
            Triple(HaPresenceSourceUpdate.COMMIT_FAILED, HttpStatusCode.InternalServerError, "configuration commit failed\n"),
            Triple(HaPresenceSourceUpdate.UNAVAILABLE, HttpStatusCode.Conflict, "activity sources are unavailable\n"),
        )) {
            outcome = state
            val response = source(body)
            assertEquals(status, response.status)
            assertEquals(text, response.bodyAsText())
        }
    }
}
