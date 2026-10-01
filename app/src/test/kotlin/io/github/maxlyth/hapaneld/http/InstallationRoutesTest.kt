package io.github.maxlyth.hapaneld.http

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallationRoutesTest {
    @Test fun versionEndpointExplainsEveryUnavailableChoiceAndPreservesAllowedDownload() {
        PaneldServerHttpFixture().use { fixture ->
            testApplication {
                application {
                    routing {
                        installationRoutes(fixture.context, fixture.config,
                            io.github.maxlyth.hapaneld.control.fakeProfile(), fixture.pending,
                            authorizeSensitive = { _, _, _, _ -> true },
                            versionCatalogue = { _, _ -> listOf(
                                io.github.maxlyth.hapaneld.util.ReleaseCatalog.Version("1", "v1", "notes1", false),
                                io.github.maxlyth.hapaneld.util.ReleaseCatalog.Version("2", "v2", "notes2", false,
                                    unavailableReason = "older_app_id"),
                                io.github.maxlyth.hapaneld.util.ReleaseCatalog.Version("3", "v3", "notes3", false,
                                    unavailableReason = "above_panel_limit", maxVersion = "2026.5.4"),
                                io.github.maxlyth.hapaneld.util.ReleaseCatalog.Version("4", "v4", "notes4", true, "download4"),
                            ) })
                    }
                }
                val response = client.get("/install/versions?name=paneld&channel=prerelease")
                assertEquals(HttpStatusCode.OK, response.status)
                val versions = JSONObject(response.bodyAsText()).getJSONArray("versions")
                assertEquals(listOf("no_matching_asset", "older_app_id", "above_panel_limit"),
                    (0..2).map { versions.getJSONObject(it).optString("unavailableReason") })
                assertEquals("2026.5.4", versions.getJSONObject(2).optString("maxVersion"))
                assertTrue(versions.getJSONObject(3).getBoolean("installable"))
                assertEquals("download4", versions.getJSONObject(3).optString("apk"))
                assertTrue(versions.getJSONObject(3).isNull("unavailableReason"))
            }
        }
    }

    @Test fun `full mount preserves install choices admission toggles and callback ownership`() {
        val requests = mutableListOf<Triple<String, String, String>>()
        PaneldServerHttpFixture(installComponent = { name, action, version ->
            requests += Triple(name, action, version)
            true
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
                assertEquals(HttpStatusCode.NotFound, heal.status)
                val webview = client.post("/api/v1/install/component") {
                    header(HttpHeaders.ContentType, "application/x-www-form-urlencoded")
                    setBody("name=webview&action=reinstall")
                }
                assertEquals(HttpStatusCode.BadRequest, webview.status)
                assertTrue(requests.isEmpty())
                val forbidden = client.post("/api/v1/uninstall") { header(HttpHeaders.Origin, "http://elsewhere.example") }
                assertEquals(HttpStatusCode.Forbidden, forbidden.status)
                assertTrue(requests.isEmpty())
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
