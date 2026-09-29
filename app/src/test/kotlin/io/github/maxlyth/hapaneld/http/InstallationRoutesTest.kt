package io.github.maxlyth.hapaneld.http

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallationRoutesTest {
    @Test fun `full mount preserves install choices admission toggles and callback ownership`() {
        val requests = mutableListOf<Triple<String, String, String>>()
        var admit = true
        PaneldServerHttpFixture(installComponent = { name, action, version ->
            requests += Triple(name, action, version)
            admit
        }).use { fixture ->
            val staged = File.createTempFile("http-install-test", ".apk")
            val lease = (fixture.pending.begin() as PendingUploadStore.BeginResult.Granted).lease
            fixture.pending.stage(lease, staged, UploadedApkIdentity("com.example.app", "1", null))
            testApplication {
                application { fixture.mount(this) }
                val versions = client.get("/api/v1/install/versions?name=unknown&channel=")
                assertEquals(HttpStatusCode.OK, versions.status)
                assertEquals("""{"channel":"stable","versions":[]}""", versions.bodyAsText())
                val invalidIgnore = client.post("/api/v1/updates/ignore") {
                    header(HttpHeaders.ContentType, "application/x-www-form-urlencoded")
                    setBody("label=app")
                }
                assertEquals(HttpStatusCode.BadRequest, invalidIgnore.status)
                assertEquals("""{"ok":false}""", invalidIgnore.bodyAsText())
                val ignored = client.post("/api/v1/updates/ignore") {
                    header(HttpHeaders.ContentType, "application/x-www-form-urlencoded")
                    setBody("label=app&version=1.2")
                }
                assertEquals(HttpStatusCode.OK, ignored.status)
                assertEquals(mapOf("app" to "1.2"), fixture.config.ignoredUpdates)
                val disabled = client.post("/api/v1/install/apk/allow") {
                    header(HttpHeaders.ContentType, "application/x-www-form-urlencoded")
                    setBody("on=false")
                }
                assertEquals(HttpStatusCode.OK, disabled.status)
                assertEquals("""{"ok":true,"allowed":false}""", disabled.bodyAsText())
                assertFalse(fixture.config.apkUploadAllowed)
                assertNull(fixture.pending.pendingSummary())
                assertFalse(staged.exists())
                val heal = client.post("/api/v1/webview/heal")
                assertEquals(HttpStatusCode.OK, heal.status)
                assertEquals("""{"status":"started"}""", heal.bodyAsText())
                assertEquals(listOf(Triple("webview", "reinstall", "")), requests)
                admit = false
                val busy = client.post("/api/v1/webview/heal")
                assertEquals(HttpStatusCode.OK, busy.status)
                assertEquals("""{"status":"busy"}""", busy.bodyAsText())
                val forbidden = client.post("/api/v1/uninstall") { header(HttpHeaders.Origin, "http://elsewhere.example") }
                assertEquals(HttpStatusCode.Forbidden, forbidden.status)
                assertEquals(2, requests.size)
                val status = client.get("/api/v1/install/status")
                assertEquals(HttpStatusCode.OK, status.status)
                assertTrue(JSONObject(status.bodyAsText()).has("running"))
                val disabledUpload = client.post("/api/v1/install/apk") { setBody("unused") }
                assertEquals(HttpStatusCode.Forbidden, disabledUpload.status)
                val guard = client.post("/api/v1/guard-db/stage")
                assertEquals(HttpStatusCode.Forbidden, guard.status)
                assertEquals("direct-lan-required", JSONObject(guard.bodyAsText()).getString("error"))
            }
        }
    }
}
