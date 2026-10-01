package io.github.maxlyth.hapaneld.http

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

class AutoBrightnessRoutesTest {
    @Test fun `adaptive brightness endpoints retain HTTP contracts`() = testApplication {
        var selected = "unset"
        val api = object : AutoBrightnessHttpApi {
            override fun statusJson() = """{"state":"live"}"""
            override fun historyJson(hours: Int, sensitivity: Int?, minimumPercent: Int?, maximumPercent: Int?) =
                """{"hours":$hours,"sensitivity":$sensitivity,"minimum":$minimumPercent,"maximum":$maximumPercent}"""
            override fun haSourcesJson(query: String, limit: Int) = """{"query":"$query","limit":$limit}"""
            override suspend fun validateHaSource(entityId: String) = AutoBrightnessHttpValidation(AutoBrightnessHttpAction.ok())
            override suspend fun selectHaSource(entityId: String?): AutoBrightnessHttpAction {
                selected = entityId ?: "panel"
                return AutoBrightnessHttpAction(202, """{"selected":"$selected"}""")
            }
            override fun resetHistory() = AutoBrightnessHttpAction(202, """{"reset":true}""")
            override fun resumeFullAuto() = AutoBrightnessHttpAction(200, """{"resumed":true}""")
        }
        application {
            paneldRoot({ emptySet() }, { false }, { "/setup" }) {
                route("/api/v1") {
                    autoBrightnessRoutes(api, { call ->
                        if (call.request.headers["Origin"] == "https://elsewhere.invalid") {
                            call.respondText("refused\n", status = HttpStatusCode.Forbidden)
                            false
                        } else true
                    }, { call, _ -> JSONObject(call.receiveText().ifBlank { "{}" }) })
                }
            }
        }
        val status = client.get("/api/v1/auto-brightness")
        assertEquals("no-store", status.headers[HttpHeaders.CacheControl])
        assertEquals("""{"state":"live"}""", status.bodyAsText())
        val history = client.get("/api/v1/auto-brightness/history?hours=24&sensitivity=40&minimum_percent=10&maximum_percent=60")
        assertEquals("no-store", history.headers[HttpHeaders.CacheControl])
        assertEquals("""{"hours":24,"sensitivity":40,"minimum":10,"maximum":60}""", history.bodyAsText())
        val invalid = client.get("/api/v1/auto-brightness/history?hours=169")
        assertEquals(HttpStatusCode.BadRequest, invalid.status)
        assertEquals("hours must be between 1 and 168\n", invalid.bodyAsText())
        val crossOrigin = client.get("/api/v1/auto-brightness/history") { header(HttpHeaders.Origin, "https://elsewhere.invalid") }
        assertEquals(HttpStatusCode.Forbidden, crossOrigin.status)
        assertEquals("""{"query":"abc","limit":200}""", client.get("/api/v1/auto-brightness/sources?q=%20abc%20&limit=999").bodyAsText())

        suspend fun source(body: String) = client.post("/api/v1/auto-brightness/source") {
            header(HttpHeaders.ContentType, "application/json")
            setBody(body)
        }
        val missing = source("{}")
        assertEquals(HttpStatusCode.BadRequest, missing.status)
        assertEquals("entity_id is required (null selects the panel sensor)\n", missing.bodyAsText())
        val wrongType = source("""{"entity_id":42}""")
        assertEquals(HttpStatusCode.BadRequest, wrongType.status)
        assertEquals("entity_id must be a string or null\n", wrongType.bodyAsText())
        val panel = source("""{"entity_id":null}""")
        assertEquals(HttpStatusCode.Accepted, panel.status)
        assertEquals("""{"selected":"panel"}""", panel.bodyAsText())
        assertEquals("panel", selected)
        val reset = client.post("/api/v1/auto-brightness/reset")
        assertEquals(HttpStatusCode.Accepted, reset.status)
        assertEquals("""{"reset":true}""", reset.bodyAsText())
        val resume = client.post("/api/v1/auto-brightness/resume")
        assertEquals(HttpStatusCode.OK, resume.status)
        assertEquals("""{"resumed":true}""", resume.bodyAsText())
    }
}
