package io.panelassistant.android.http

import io.panelassistant.android.device.profile.ProfileActivationPhase
import io.panelassistant.android.device.profile.ProfileActivationState
import io.panelassistant.android.device.profile.ProfileAdmin
import io.panelassistant.android.device.profile.ProfileBackup
import io.panelassistant.android.device.profile.ProfileRef
import io.panelassistant.android.device.profile.ProfileSelection
import io.panelassistant.android.device.profile.ProfileStatus

import io.panelassistant.android.CommandOrigin
import io.panelassistant.android.Config
import io.panelassistant.android.ManageReply
import io.panelassistant.android.backup.PanelBackup
import io.panelassistant.android.config.SettingsRegistry
import io.panelassistant.android.security.LocalApprovalBroker
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.put
import io.ktor.server.plugins.mutableOriginConnectionPoint
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import io.ktor.client.request.post
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.panelassistant.android.util.InstallProgress
import io.ktor.server.testing.testApplication
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaneldServerBackupRestoreRoutesTest {
    @After fun clearApprovals() = LocalApprovalBroker.instance.clear()

    @Test fun `full mount requires an explicit backup request before capture`() {
        PaneldServerHttpFixture().use { fixture ->
            testApplication {
                application { fixture.mount(this) }
                for ((form, error) in listOf(
                    "include_companion=invalid&allow_plaintext=1" to "invalid-include-companion",
                    "include_companion=0" to "passphrase-required",
                )) {
                    val response = client.post("/api/v1/backup") {
                        header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                        setBody(form)
                    }
                    assertEquals(HttpStatusCode.BadRequest, response.status)
                    assertEquals(error, JSONObject(response.bodyAsText()).getString("error"))
                }
            }
        }
    }

    @Test fun `full mount refuses restore while another operation owns admission`() {
        val held = requireNotNull(InstallProgress.start("existing owner"))
        try {
            PaneldServerHttpFixture().use { fixture ->
                testApplication {
                    application { fixture.mount(this) }
                    val response = client.post("/api/v1/restore?dry_run=1") { setBody("not a backup") }
                    assertEquals(HttpStatusCode.Conflict, response.status)
                    assertEquals("busy", JSONObject(response.bodyAsText()).getString("status"))
                }
            }
        } finally {
            InstallProgress.finish(held, "released")
        }
    }

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
                // Migrated voice enablement is unavailable on this fixture without a microphone.
                assertEquals(26, body.getInt("config_keys"))
                assertEquals(listOf("voice_enabled"), (0 until body.getJSONArray("config_skipped").length()).map {
                    body.getJSONArray("config_skipped").getString(it)
                })
                assertEquals("source", body.getString("panel_id"))
                assertEquals(false, body.getBoolean("state_unavailable"))
                assertEquals("contract-panel", fixture.config.panelId)
            }
        }
    }

    @Test fun `full mount refuses inverted automatic bounds before restore side effects`() {
        PaneldServerHttpFixture().use { fixture ->
            fixture.config.setRaw(requireNotNull(SettingsRegistry.spec("friendly_name")), "Contract panel")
            fixture.config.setAutoBrightnessMinimumPercent(20)
            fixture.config.setAutoBrightnessMaximumPercent(60)
            testApplication {
                application { fixture.mount(this) }
                val invalidConfigs = listOf(
                    "\"auto_brightness_minimum_percent\":\"70\",\"auto_brightness_maximum_percent\":\"60\"",
                    "\"auto_brightness_minimum_percent\":\"70\"",
                    "\"auto_brightness_maximum_percent\":\"20\"",
                )
                for (suffix in listOf("?dry_run=1", "")) {
                    for (bounds in invalidConfigs) {
                        val response = client.post("/api/v1/restore$suffix") {
                            setBody("""{"kind":"ha-paneld-backup","schema":13,"config":{"friendly_name":"Must not apply",$bounds}}""")
                        }
                        val text = response.bodyAsText()
                        assertEquals(text, HttpStatusCode.UnprocessableEntity, response.status)
                        assertEquals("invalid backup config", JSONObject(text).getString("error"))
                        assertEquals(20, fixture.config.autoBrightnessMinimumPercent)
                        assertEquals(60, fixture.config.autoBrightnessMaximumPercent)
                        assertEquals("Contract panel", fixture.config.friendlyName)
                    }
                }
            }
        }
    }

    @Test fun `rootless preview reports unavailable live settings while retaining supported values`() {
        PaneldServerHttpFixture().use { fixture ->
            testApplication {
                application { fixture.mount(this) }
                val response = client.post("/api/v1/restore?dry_run=1") {
                    setBody("""{"kind":"ha-paneld-backup","schema":15,"config":{"friendly_name":"Restored panel","ha_area":"Kitchen","network_adb":"true","silence_boot_chime":"false"}}""")
                }
                val text = response.bodyAsText()
                assertEquals(text, HttpStatusCode.OK, response.status)
                val body = JSONObject(text)
                assertEquals(2, body.getInt("config_keys"))
                assertEquals(listOf("network_adb", "silence_boot_chime"), (0 until body.getJSONArray("config_skipped").length()).map {
                    body.getJSONArray("config_skipped").getString(it)
                })
                assertTrue(body.getJSONArray("config_warnings").toString().contains("network_adb"))
                assertTrue(body.getJSONArray("config_warnings").toString().contains("silence_boot_chime"))
                assertEquals("contract-panel", fixture.config.panelId)
                assertTrue(fixture.config.haArea.isEmpty())
                assertEquals(false, InstallProgress.running)
            }
        }
    }

    @Test fun `restore preview reports the settings skipped to keep ADB off`() {
        PaneldServerHttpFixture().use { fixture ->
            assertTrue(fixture.config.setAdbKeepOff(true))
            testApplication {
                application { fixture.mount(this) }
                val response = client.post("/api/v1/restore?dry_run=1") {
                    setBody("""{"kind":"ha-paneld-backup","schema":15,"config":{"friendly_name":"Restored panel","ha_area":"Kitchen","network_adb":"true","adb_keep_off":"false"}}""")
                }
                val text = response.bodyAsText()
                assertEquals(text, HttpStatusCode.OK, response.status)
                val body = JSONObject(text)
                assertEquals(text, 2, body.getInt("config_keys"))
                val skipped = body.getJSONArray("config_skipped")
                assertEquals(setOf("network_adb", "adb_keep_off"), (0 until skipped.length()).map(skipped::getString).toSet())
                val warnings = body.getJSONArray("config_warnings")
                for (key in listOf("network_adb", "adb_keep_off")) {
                    assertTrue(text, (0 until warnings.length()).map(warnings::getString).any {
                        it.startsWith(key) && it.contains("Keep ADB off")
                    })
                }
                assertTrue(fixture.config.adbKeepOff)
                assertTrue(fixture.config.haArea.isEmpty())
                assertFalse(InstallProgress.running)
            }
        }
    }

    @Test fun `unavailable live setting is still validated before a preview can skip it`() {
        PaneldServerHttpFixture().use { fixture ->
            testApplication {
                application { fixture.mount(this) }
                for (suffix in listOf("?dry_run=1", "")) {
                    val response = client.post("/api/v1/restore$suffix") {
                        setBody("""{"kind":"ha-paneld-backup","schema":15,"config":{"network_adb":"not-a-boolean","friendly_name":"Must not apply"}}""")
                    }
                    assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
                    assertEquals("invalid backup config", JSONObject(response.bodyAsText()).getString("error"))
                    assertEquals("contract-panel", fixture.config.panelId)
                    assertEquals(false, InstallProgress.running)
                }
            }
        }
    }

    @Test fun `rootless preview and real restore refuse requested network adb in hardened mode`() {
        PaneldServerHttpFixture().use { fixture ->
            assertTrue(fixture.config.setSecurityMode(Config.SecurityMode.HARDENED))
            testApplication {
                application { fixture.mount(this) }
                for (suffix in listOf("?dry_run=1", "")) {
                    val response = client.post("/api/v1/restore$suffix") {
                        setBody("""{"kind":"ha-paneld-backup","schema":15,"config":{"network_adb":"true","friendly_name":"Must not apply"}}""")
                    }
                    val text = response.bodyAsText()
                    assertEquals(text, HttpStatusCode.Conflict, response.status)
                    assertEquals("network-adb-incompatible-with-hardened-mode", JSONObject(text).getString("error"))
                    assertEquals("contract-panel", fixture.config.panelId)
                    assertEquals(false, InstallProgress.running)
                }
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
                    assertEquals("not a Panel Assistant backup", JSONObject(response.bodyAsText()).getString("error"))
                }
                val invalid = client.post("/api/v1/restore?dry_run=1") {
                    setBody("""{"kind":"ha-paneld-backup","schema":1,"config":{"panel_id":{}}}""")
                }
                assertEquals(HttpStatusCode.UnprocessableEntity, invalid.status)
                assertEquals("invalid backup config", JSONObject(invalid.bodyAsText()).getString("error"))
            }
        }
    }

    // ---- the same restore and backup over the stub socket (protocol section 20.5) ----

    @Test fun `a sealed backup previews over the socket from a blob with its passphrase in args`() {
        PaneldServerHttpFixture().use { fixture ->
            testApplication {
                application { fixture.mount(this) }
                val sealed = PanelBackup.seal(LEGACY_BACKUP.toByteArray(), PASSPHRASE)
                val reply = socket {
                    manage("p1", "restore", JSONObject().put("blob", upload(client, sealed)).put("passphrase", PASSPHRASE).put("dry_run", 1))
                }
                assertEquals(reply.toString(), "applied", reply.optString("outcome"))
                val result = resultOf(reply)
                assertEquals(true, result.optBoolean("dry_run"))
                assertEquals("source", result.optString("panel_id"))
                assertEquals("contract-panel", fixture.config.panelId)
                assertEquals(false, InstallProgress.running)
            }
        }
    }

    @Test fun `a sealed backup previews over HTTP with its passphrase in the header`() {
        PaneldServerHttpFixture().use { fixture ->
            testApplication {
                application { fixture.mount(this) }
                val response = client.post("/api/v1/restore?dry_run=1") {
                    header("X-Backup-Passphrase", PASSPHRASE)
                    setBody(PanelBackup.seal(LEGACY_BACKUP.toByteArray(), PASSPHRASE))
                }
                val text = response.bodyAsText()
                assertEquals(text, HttpStatusCode.OK, response.status)
                val body = JSONObject(text)
                assertEquals(true, body.optBoolean("dry_run"))
                assertEquals("source", body.optString("panel_id"))
                assertEquals("contract-panel", fixture.config.panelId)
                assertEquals(false, InstallProgress.running)
            }
        }
    }

    @Test fun `a sealed backup without its passphrase is refused over either door before it is opened`() {
        PaneldServerHttpFixture().use { fixture ->
            testApplication {
                application { fixture.mount(this) }
                val sealed = PanelBackup.seal(LEGACY_BACKUP.toByteArray(), PASSPHRASE)
                val http = client.post("/api/v1/restore?dry_run=1") { setBody(sealed) }
                val reply = socket { manage("n1", "restore", JSONObject().put("blob", upload(client, sealed)).put("dry_run", 1)) }

                assertEquals(HttpStatusCode.BadRequest, http.status)
                assertEquals("restore-passphrase-required", JSONObject(http.bodyAsText()).optJSONObject("presentation")?.optString("code"))
                assertEquals(reply.toString(), "refused", reply.optString("outcome"))
                assertEquals("restore-passphrase-required", resultOf(reply).optJSONObject("presentation")?.optString("code"))
                assertEquals(false, InstallProgress.running)
            }
        }
    }

    @Test fun `a blob that fails its hash or size or was already spent is never restored`() {
        PaneldServerHttpFixture().use { fixture ->
            testApplication {
                application { fixture.mount(this) }
                val bytes = LEGACY_BACKUP.toByteArray()
                val (badHash, badSize, good, spent) = socket {
                    fun preview(id: String, blob: JSONObject) = JSONObject().put("blob", blob).put("dry_run", 1).let { id to it }
                    val wrongHash = upload(client, bytes).put("sha256", sha256(bytes + 1))
                    val wrongSize = upload(client, bytes).let { it.put("size", it.getInt("size") + 1) }
                    val right = upload(client, bytes)
                    listOf(
                        preview("b1", wrongHash), preview("b2", wrongSize), preview("b3", right), preview("b4", right),
                    ).map { (id, args) -> manage(id, "restore", args) }
                }

                assertEquals(badHash.toString(), "failed", badHash.optString("outcome"))
                assertEquals(badSize.toString(), "failed", badSize.optString("outcome"))
                assertEquals(good.toString(), "applied", good.optString("outcome"))
                assertEquals("a spent blob is not restored again", "failed", spent.optString("outcome"))
                assertEquals("contract-panel", fixture.config.panelId)
                assertEquals(false, InstallProgress.running)
            }
        }
    }

    @Test fun `restore over the socket refuses network adb in hardened mode before anything changes`() {
        PaneldServerHttpFixture().use { fixture ->
            assertTrue(fixture.config.setSecurityMode(Config.SecurityMode.HARDENED))
            testApplication {
                application {
                    intercept(io.ktor.server.application.ApplicationCallPipeline.Setup) {
                        context.mutableOriginConnectionPoint.remoteAddress = "127.0.0.1"
                    }
                    fixture.mount(this)
                }
                val reply = socket { manage("a1", "restore", JSONObject().put("blob", upload(client, NETWORK_ADB_BACKUP.toByteArray()))) }

                assertEquals(reply.toString(), "refused", reply.optString("outcome"))
                assertEquals("not_commandable", reply.optString("code"))
                assertEquals("network-adb-incompatible-with-hardened-mode", resultOf(reply).optString("error"))
                assertEquals("contract-panel", fixture.config.panelId)
                assertEquals(false, InstallProgress.running)
            }
        }
    }

    @Test fun `restore over the managed door from a LAN peer in hardened mode waits for an approval bound to that peer`() {
        PaneldServerHttpFixture().use { fixture ->
            assertTrue(fixture.config.setSecurityMode(Config.SecurityMode.HARDENED))
            testApplication {
                application { fixture.mount(this) }
                startApplication()
                val bytes = NAMED_BACKUP.toByteArray()
                val blobs = StubBlobs()
                fun restore(id: String, peer: String): ManageReply {
                    val token = blobs.mintUpload("application/octet-stream")!!.getString("url").substringAfterLast('/')
                    val args = JSONObject().put("blob", blobs.fill(token, bytes.size.toLong(), bytes.inputStream())!!)
                    val answer = LinkedBlockingQueue<ManageReply>()
                    fixture.door.manage(id, "restore", args, CommandOrigin.peer(peer), blobs, {}) { answer.put(it) }
                    val reply = answer.poll(20, TimeUnit.SECONDS)
                    assertNotNull("$id answered", reply)
                    return reply!!
                }

                val first = restore("h1", "192.0.2.9")
                assertEquals("pending_approval", first.outcome)
                val approvalId = (first.result as? JSONObject)?.optString("approval_id").orEmpty()
                assertTrue(first.result.toString(), approvalId.isNotEmpty())
                assertTrue(LocalApprovalBroker.instance.approve(approvalId))
                // Another peer sending the same restore cannot spend the first peer's approval.
                assertEquals("pending_approval", restore("h2", "192.0.2.10").outcome)
                assertEquals("contract-panel", fixture.config.panelId)
                assertFalse(InstallProgress.running)
            }
        }
    }

    @Test fun `restore over HTTP from a LAN peer in hardened mode answers the approval challenge and changes nothing`() {
        PaneldServerHttpFixture().use { fixture ->
            assertTrue(fixture.config.setSecurityMode(Config.SecurityMode.HARDENED))
            testApplication {
                application {
                    intercept(io.ktor.server.application.ApplicationCallPipeline.Setup) {
                        context.mutableOriginConnectionPoint.remoteAddress = "192.168.50.20"
                    }
                    fixture.mount(this)
                }
                val response = client.post("/api/v1/restore") { setBody(NAMED_BACKUP) }
                val text = response.bodyAsText()
                assertEquals(text, HttpStatusCode.Accepted, response.status)
                assertEquals("approval-required", JSONObject(text).optString("error"))
                assertEquals("contract-panel", fixture.config.panelId)
                assertEquals(false, InstallProgress.running)
            }
        }
    }

    private suspend fun <T> ApplicationTestBuilder.socket(block: suspend DefaultClientWebSocketSession.() -> T): T {
        var result: Result<T>? = null
        createClient { install(ClientWebSockets) }.webSocket("/ui") { result = runCatching { block() } }
        return result!!.getOrThrow()
    }

    private suspend fun DefaultClientWebSocketSession.manage(id: String, op: String, args: JSONObject): JSONObject {
        send(Frame.Text(JSONObject().put("kind", "manage").put("command_id", id).put("op", op).put("args", args).toString()))
        return withTimeout(20_000) {
            while (true) {
                val frame = JSONObject((incoming.receive() as Frame.Text).readText())
                if (frame.optString("command_id") == id && !frame.optBoolean("interim")) return@withTimeout frame
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }
    }

    /** Upload [bytes] as `blob.upload` then its PUT, and answer the reference a request names in `args.blob`. */
    private suspend fun DefaultClientWebSocketSession.upload(http: io.ktor.client.HttpClient, bytes: ByteArray): JSONObject {
        val minted = manage("u${System.nanoTime()}", "blob.upload", JSONObject())
        val url = minted.getJSONObject("result").getString("url")
        val put = http.put(url) { setBody(bytes) }
        assertEquals(HttpStatusCode.OK, put.status)
        return JSONObject(put.bodyAsText())
    }

    private fun resultOf(reply: JSONObject): JSONObject {
        assertTrue(reply.toString(), reply.has("result") && reply.opt("result") is JSONObject)
        return reply.getJSONObject("result")
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val PASSPHRASE = "correct horse"
        const val LEGACY_BACKUP = """{"kind":"ha-paneld-backup","schema":1,"panel_id":"source","config":{"panel_id":"restored"}}"""
        const val NAMED_BACKUP = """{"kind":"ha-paneld-backup","schema":15,"config":{"friendly_name":"Must not apply"}}"""
        const val NETWORK_ADB_BACKUP = """{"kind":"ha-paneld-backup","schema":15,"config":{"network_adb":"true","friendly_name":"Must not apply"}}"""
    }
    @Test fun `full mount refuses a sealed backup with the wrong passphrase before parsing it`() {
        PaneldServerHttpFixture().use { fixture ->
            testApplication {
                application { fixture.mount(this) }
                val sealed = PanelBackup.seal(
                    """{"kind":"ha-paneld-backup","schema":15,"config":{"friendly_name":"Sealed panel"}}""".toByteArray(),
                    "correct horse",
                )
                val response = client.post("/api/v1/restore?dry_run=1") {
                    header("X-Backup-Passphrase", "battery staple")
                    setBody(sealed)
                }
                val body = JSONObject(response.bodyAsText())
                assertEquals(HttpStatusCode.BadRequest, response.status)
                assertEquals("wrong passphrase or corrupt bundle", body.optString("error"))
                assertEquals("restore-passphrase-or-bundle-invalid", body.optJSONObject("presentation")?.optString("code"))
                assertEquals(false, InstallProgress.running)
            }
        }
    }

    @Test fun `full mount refuses a companion payload for a package the panel does not have`() {
        PaneldServerHttpFixture().use { fixture ->
            testApplication {
                application { fixture.mount(this) }
                val response = client.post("/api/v1/restore?dry_run=1") {
                    setBody(
                        """{"kind":"ha-paneld-backup","schema":15,"config":{"friendly_name":"Restored panel"},""" +
                            """"companion":{"pkg":"io.homeassistant.companion.android.minimal",""" +
                            """"files":[{"rel":"databases/HomeAssistantDB","b64":"ZGF0YQ=="}]}}""",
                    )
                }
                val body = JSONObject(response.bodyAsText())
                assertEquals(body.toString(), HttpStatusCode.BadRequest, response.status)
                assertEquals("companion-unsupported-package", body.optJSONObject("presentation")?.optString("code"))
                assertEquals("contract-panel", fixture.config.panelId)
                assertEquals(false, InstallProgress.running)
            }
        }
    }

    @Test fun `full mount refuses a profile catalog the panel cannot restore`() {
        PaneldServerHttpFixture().use { fixture ->
            val status = ProfileStatus(1, ProfileSelection.Auto, null, ProfileActivationState(ProfileActivationPhase.ACTIVE))
            // The interface's own planBackupRestore answers an invalid plan.
            val admin = object : ProfileAdmin {
                override fun schema() = error("unused")
                override fun drivers() = error("unused")
                override fun status() = status
                override fun list() = error("unused")
                override fun preview(rawYaml: String) = error("unused")
                override fun importProfile(rawYaml: String, previewToken: String) = error("unused")
                override fun exportProfile(ref: ProfileRef) = error("unused")
                override fun select(selection: ProfileSelection, expectedCatalogRevision: Long) = error("unused")
                override fun rollbackToLastKnownGood(expectedCatalogRevision: Long) = error("unused")
                override fun deleteProfile(ref: ProfileRef, expectedCatalogRevision: Long) = error("unused")
                override fun restoreBackup(payload: ProfileBackup, expectedCatalogRevision: Long) =
                    error("An unrestorable catalog must never be applied")
            }
            val profiles = ProfileBackup(revisions = emptyList(), selection = ProfileSelection.Auto, active = null, lastKnownGood = null).toJson()
            testApplication {
                application {
                    fixture.mount(this)
                    // After mounting: the restore route reads its profile admin per request.
                    PaneldServer::class.java.getDeclaredField("profileAdmin").apply { isAccessible = true }.set(fixture.server, admin)
                }
                val response = client.post("/api/v1/restore?dry_run=1") {
                    setBody("""{"kind":"ha-paneld-backup","schema":15,"config":{"friendly_name":"Restored panel"},"profiles":$profiles}""")
                }
                val body = JSONObject(response.bodyAsText())
                assertEquals(body.toString(), HttpStatusCode.UnprocessableEntity, response.status)
                assertEquals("restore-profile-catalog-not-restorable", body.optJSONObject("presentation")?.optString("code"))
                assertEquals("contract-panel", fixture.config.panelId)
                assertEquals(false, InstallProgress.running)
            }
        }
    }
}
