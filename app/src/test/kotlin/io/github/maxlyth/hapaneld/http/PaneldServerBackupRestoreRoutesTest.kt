package io.github.maxlyth.hapaneld.http

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class PaneldServerBackupRestoreRoutesTest {
    @Test fun `full mount previews legacy config without changing the panel`() {
        PaneldServerHttpFixture().use { fixture ->
            testApplication {
                application { fixture.mount(this) }
                val response = client.post("/api/v1/restore?dry_run=1") {
                    setBody("""{"kind":"ha-paneld-backup","schema":1,"panel_id":"source","config":{"panel_id":"restored"}}""")
                }
                assertEquals(response.bodyAsText(), HttpStatusCode.OK, response.status)
                val body = JSONObject(response.bodyAsText())
                assertEquals(true, body.getBoolean("dry_run"))
                assertEquals(27, body.getInt("config_keys"))
                assertEquals("source", body.getString("panel_id"))
                assertEquals(false, body.getBoolean("state_unavailable"))
                assertEquals("contract-panel", fixture.config.panelId)
            }
        }
    }

    @Test fun `full mount refuses malformed restore then releases admission for another request`() {
        PaneldServerHttpFixture().use { fixture ->
            testApplication {
                application { fixture.mount(this) }
                repeat(2) {
                    val response = client.post("/api/v1/restore?dry_run=1") { setBody("not a backup") }
                    assertEquals(HttpStatusCode.BadRequest, response.status)
                    assertEquals("not a ha-paneld backup", JSONObject(response.bodyAsText()).getString("error"))
                }
                val invalid = client.post("/api/v1/restore?dry_run=1") {
                    setBody("""{"kind":"ha-paneld-backup","schema":1,"config":{"panel_id":{}}}""")
                }
                assertEquals(HttpStatusCode.UnprocessableEntity, invalid.status)
                assertEquals("invalid backup config", JSONObject(invalid.bodyAsText()).getString("error"))
                val migration = client.post("/api/v1/restore?mode=migration") { setBody("not a backup") }
                assertEquals(HttpStatusCode.Forbidden, migration.status)
                assertEquals("migration-restore-refused", JSONObject(migration.bodyAsText()).getString("error"))
            }
        }
    }
}
