package io.panelassistant.android.http

import io.panelassistant.android.config.ConfigBundle
import io.panelassistant.android.config.SettingsRegistry
import io.panelassistant.android.testsupport.PanelRuntimeRig
import io.panelassistant.android.util.InstallProgress
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Versioned user-facing restore paths must not reinstate the pre-schema-17 hidden navigation. */
internal class NavigationRestoreMigrationTest : PanelRuntimeRig() {
    @Test fun configAndFleetProfileImportsResetOldHidingAndRetainLaterChoices() {
        for (fleet in listOf(false, true)) for (schema in listOf(16, 17)) {
            withFullReadServer { config, fixture ->
                config.setRaw(requireNotNull(SettingsRegistry.spec(KEY)), "false")
                testApplication {
                    application { fixture.mount(this) }
                    val response = client.post("/api/v1/config/import" + if (fleet) "?mode=fleet" else "") {
                        setBody(ConfigBundle.fromValues(mapOf(KEY to "true"), schema = schema).serialize())
                    }
                    val body = response.bodyAsText()
                    assertEquals(body, HttpStatusCode.OK, response.status)
                    assertEquals(body, "applied", JSONObject(body).getString("status"))
                    assertEquals("fleet=$fleet schema=$schema", schema >= 17, config.dashboardNativeKiosk)
                }
            }
        }
    }

    @Test fun oldPartialImportsPreserveTheTargetsLaterNavigationChoice() {
        for (fleet in listOf(false, true)) withFullReadServer { config, fixture ->
            config.setRaw(requireNotNull(SettingsRegistry.spec(KEY)), "true")
            testApplication {
                application { fixture.mount(this) }
                val response = client.post("/api/v1/config/import" + if (fleet) "?mode=fleet" else "") {
                    setBody(ConfigBundle.fromValues(mapOf("touch_sound" to "false"), schema = 16).serialize())
                }
                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals("applied", JSONObject(response.bodyAsText()).getString("status"))
                assertTrue("An absent setting must preserve the target's choice", config.dashboardNativeKiosk)
            }
        }
    }

    @Test fun fullBackupRestoreResetsOldHidingAndRetainsLaterChoices() {
        for (schema in listOf(16, 17)) withFullReadServer { config, fixture ->
            config.setRaw(requireNotNull(SettingsRegistry.spec(KEY)), "false")
            testApplication {
                application { fixture.mount(this) }
                val response = client.post("/api/v1/restore") {
                    setBody(JSONObject().put("kind", "ha-paneld-backup").put("schema", schema)
                        .put("config", JSONObject().put(KEY, "true")).toString())
                }
                val body = response.bodyAsText()
                assertEquals(body, HttpStatusCode.OK, response.status)
                assertEquals(body, "started", JSONObject(body).getString("status"))
                assertTrue(awaitCondition { !InstallProgress.running })
                val result = JSONObject(InstallProgress.json()).getJSONObject("result")
                assertEquals(result.toString(), "succeeded", result.getString("status"))
                assertEquals("schema=$schema", schema >= 17, config.dashboardNativeKiosk)
            }
        }
    }

    @Test fun revisionRestoreResetsOldHidingAndRetainsLaterChoices() {
        for (schema in listOf(16, 17)) withFullReadServer { config, fixture ->
            config.setRaw(requireNotNull(SettingsRegistry.spec(KEY)), "false")
            val revisions = PaneldServer::class.java.getDeclaredField("revisions").run {
                isAccessible = true
                get(fixture.server) as RevisionStore
            }
            val id = revisions.snapshot(ConfigBundle.fromValues(
                mapOf(KEY to "true"), kind = ConfigBundle.KIND_REVISION, schema = schema,
            ))
            testApplication {
                application { fixture.mount(this) }
                val response = client.post("/api/v1/config/revisions/$id/restore")
                val body = response.bodyAsText()
                assertEquals(body, HttpStatusCode.OK, response.status)
                assertEquals("schema=$schema", schema >= 17, config.dashboardNativeKiosk)
            }
        }
    }

    @Test fun portableRestorePathsCannotReplaceOrExportTheChosenHttpPort() {
        for (fleet in listOf(false, true)) withFullReadServer { config, fixture ->
            config.httpPort = 14726
            config.setRaw(requireNotNull(SettingsRegistry.spec(KEY)), "true")
            testApplication {
                application { fixture.mount(this) }
                val exported = client.get("/api/v1/config/export")
                assertEquals(HttpStatusCode.OK, exported.status)
                val bundle = JSONObject(exported.bodyAsText())
                assertTrue(bundle.toString(), bundle.has("values"))
                assertFalse(bundle.toString(), bundle.getJSONObject("values").has("http_port"))
                val imported = client.post("/api/v1/config/import" + if (fleet) "?mode=fleet" else "") {
                    setBody(ConfigBundle.fromValues(mapOf(KEY to "false", "http_port" to "14727")).serialize())
                }
                val text = imported.bodyAsText()
                assertEquals(text, HttpStatusCode.OK, imported.status)
                assertTrue(text, JSONObject(text).optJSONArray("warnings").toString().contains("unknown key skipped: http_port"))
                assertEquals(14726, config.httpPort)
                assertFalse("The portable setting must still import", config.dashboardNativeKiosk)
            }
        }
        for (injectPort in listOf(false, true)) withFullReadServer { config, fixture ->
            config.httpPort = 14726
            config.setRaw(requireNotNull(SettingsRegistry.spec(KEY)), "true")
            testApplication {
                application { fixture.mount(this) }
                val settings = JSONObject().put(KEY, "false")
                if (injectPort) settings.put("http_port", "14727")
                val restored = client.post("/api/v1/restore") {
                    setBody(JSONObject().put("kind", "ha-paneld-backup").put("schema", SettingsRegistry.SCHEMA)
                        .put("config", settings).toString())
                }
                val text = restored.bodyAsText()
                if (injectPort) {
                    assertEquals(text, HttpStatusCode.UnprocessableEntity, restored.status)
                    assertTrue(text, text.contains("http_port: unknown setting"))
                    assertTrue("Rejected restore must leave portable settings unchanged", config.dashboardNativeKiosk)
                } else {
                    assertEquals(text, HttpStatusCode.OK, restored.status)
                    assertTrue(awaitCondition { !InstallProgress.running })
                    val result = JSONObject(InstallProgress.json()).optJSONObject("result")
                    assertEquals(result.toString(), "succeeded", result?.optString("status"))
                    assertFalse("The portable setting must still restore", config.dashboardNativeKiosk)
                }
                assertEquals(14726, config.httpPort)
            }
        }
    }

    private companion object {
        const val KEY = "dashboard_native_kiosk"
    }
}
