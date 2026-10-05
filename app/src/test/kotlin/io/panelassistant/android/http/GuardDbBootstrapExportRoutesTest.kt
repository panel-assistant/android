package io.panelassistant.android.http

import io.panelassistant.android.platform.DaemonLongResult
import io.panelassistant.android.platform.DaemonStreamResult
import io.panelassistant.android.security.ApprovalBroker
import io.panelassistant.android.util.GuardDbAppStaging
import io.panelassistant.android.util.GuardDbMaintenanceClient
import io.panelassistant.android.util.GuardDbMaintenanceProtocol
import io.panelassistant.android.util.GuardDbMaintenanceTransport
import io.panelassistant.android.util.GuardDbPreparedArm
import io.panelassistant.android.util.GuardDbPreparedArmLoad
import io.panelassistant.android.util.GuardDbPreparedArmStore
import io.panelassistant.android.util.GuardDbSentinelState
import io.panelassistant.android.util.GuardDbSentinelStore
import io.panelassistant.android.util.GuardDbStartupSentinel
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.plugins.mutableOriginConnectionPoint
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GuardDbBootstrapExportRoutesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `database is read only after exact physical approval and success returns both lease resources`() =
        testApplication {
            val fixture = fixture()
            install(fixture)

            val pending = postExport(fixture)
            assertEquals(HttpStatusCode.Accepted, pending.status)
            assertTrue(pending.bodyAsText().contains("approval-required"))
            assertEquals(0, fixture.databaseReads)
            assertTrue(fixture.broker.approve(fixture.broker.pending().single().id))

            val created = postExport(fixture)
            assertEquals(HttpStatusCode.OK, created.status)
            assertEquals(
                "{\"capture_id\":\"$CAPTURE\"," +
                    "\"database_path\":\"/api/v1/guard-db/bootstrap/database\"," +
                    "\"expires_elapsed_realtime_ms\":121000,\"ok\":true," +
                    "\"proof_path\":\"/api/v1/guard-db/bootstrap/proof\"," +
                    "\"token\":\"$TOKEN\"}\n",
                created.bodyAsText(),
            )
            assertEquals(1, fixture.databaseReads)

            val proof = getLease(fixture, "/api/v1/guard-db/bootstrap/proof")
            assertEquals(HttpStatusCode.OK, proof.status)
            assertEquals(
                canonicalGuardDbBootstrapProof(fixture.snapshot, PEER, CAPTURE, 1_000L),
                proof.bodyAsText(),
            )
            val database = getLease(fixture, "/api/v1/guard-db/bootstrap/database")
            assertEquals(HttpStatusCode.OK, database.status)
            assertArrayEquals(DATABASE, database.bodyAsBytes())
            assertEquals(CAPTURE, database.headers[GUARD_DB_BOOTSTRAP_CAPTURE_HEADER])
            assertEquals(fixture.prepared.databaseSha256,
                database.headers[GUARD_DB_BOOTSTRAP_DATABASE_SHA256_HEADER])
            assertEquals(fixture.session, database.headers[GUARD_DB_BOOTSTRAP_SESSION_HEADER])
        }

    @Test fun `lease GET rejects mixed peer session capture and token with one secret free error`() =
        testApplication {
            val fixture = fixture()
            install(fixture)
            approveAndCreate(fixture)

            val attempts = listOf(
                LeaseRequest(peer = OTHER_PEER),
                LeaseRequest(session = "0".repeat(64)),
                LeaseRequest(capture = "d".repeat(64)),
                LeaseRequest(token = "e".repeat(64)),
            )
            attempts.forEach { attempt ->
                val response = getLease(fixture, "/api/v1/guard-db/bootstrap/proof", attempt)
                assertEquals(HttpStatusCode.Forbidden, response.status)
                val error = response.bodyAsText()
                assertEquals("{\"ok\":false,\"error\":\"bootstrap-export-unavailable\"}", error)
                assertFalse(error.contains(TOKEN))
                assertFalse(error.contains(CAPTURE))
                assertFalse(error.contains(fixture.session))
                assertFalse(error.contains(attempt.token))
                assertFalse(error.contains(attempt.capture))
                assertFalse(error.contains(attempt.session))
            }
            assertEquals(HttpStatusCode.OK,
                getLease(fixture, "/api/v1/guard-db/bootstrap/proof").status)
        }

    @Test fun `lease expires and state security helper prepared or database drift invalidates it`() {
        testApplication {
            val fixture = fixture()
            install(fixture)
            approveAndCreate(fixture)
            fixture.now = 121_000L
            assertEquals(HttpStatusCode.Forbidden,
                getLease(fixture, "/api/v1/guard-db/bootstrap/proof").status)
        }

        val drifts: List<(Fixture) -> Unit> = listOf(
            { it.snapshot = it.snapshot.copy(sentinel = it.sentinel.copy(state = GuardDbSentinelState.ARMED)) },
            { it.snapshot = it.snapshot.copy(security = it.snapshot.security.copy(authoritySha256 = "0".repeat(64))) },
            { it.snapshot = it.snapshot.copy(helper = it.snapshot.helper.copy(buildId = "0".repeat(64))) },
            { it.snapshot = it.snapshot.copy(prepared = it.prepared.copy(databaseSha256 = "0".repeat(64))) },
            { it.databaseExact = false },
        )
        drifts.forEach { drift ->
            testApplication {
                val fixture = fixture()
                install(fixture)
                approveAndCreate(fixture)
                drift(fixture)
                assertEquals(HttpStatusCode.Forbidden,
                    getLease(fixture, "/api/v1/guard-db/bootstrap/database").status)
            }
        }
    }

    @Test fun `export request grammar rejects duplicate escaped and extra members before approval`() =
        testApplication {
            val fixture = fixture()
            install(fixture)
            val invalid = listOf(
                "{\"session\":\"${fixture.session}\",\"session\":\"${fixture.session}\"," +
                    "\"capture_id\":\"$CAPTURE\"}",
                "{\"sess\\u0069on\":\"${fixture.session}\",\"capture_id\":\"$CAPTURE\"}",
                "{\"session\":\"${fixture.session}\",\"capture_id\":\"$CAPTURE\",\"extra\":true}",
            )
            invalid.forEach { body ->
                val response = postRawExport(body)
                assertEquals(HttpStatusCode.BadRequest, response.status)
                assertTrue(response.bodyAsText().contains("invalid-bootstrap-export"))
            }
            assertTrue(fixture.broker.pending().isEmpty())
            assertEquals(0, fixture.databaseReads)

            val reversed = postRawExport(
                "{ \"capture_id\" : \"$CAPTURE\", \"session\" : \"${fixture.session}\" }",
            )
            assertEquals(HttpStatusCode.Accepted, reversed.status)
            assertEquals(1, fixture.broker.pending().size)
        }

    @Test fun `GET rechecks the Hardened epoch immediately after the gated lease read`() =
        testApplication {
            val fixture = fixture()
            install(fixture)
            approveAndCreate(fixture)
            fixture.security.afterAction = { fixture.security.epoch = 42L }

            val changed = getLease(fixture, "/api/v1/guard-db/bootstrap/database")
            assertEquals(HttpStatusCode.Forbidden, changed.status)
            assertTrue(changed.bodyAsText().contains("bootstrap-export-unavailable"))
            fixture.security.epoch = 41L
            assertEquals(HttpStatusCode.Forbidden,
                getLease(fixture, "/api/v1/guard-db/bootstrap/database").status)
        }

    @Test fun `database mismatch and oversize refuse without creating a lease`() {
        testApplication {
            val fixture = fixture().apply { readResult = GuardDbBootstrapDatabaseRead.Mismatch }
            install(fixture)
            assertEquals(HttpStatusCode.Accepted, postExport(fixture).status)
            assertTrue(fixture.broker.approve(fixture.broker.pending().single().id))
            val mismatch = postExport(fixture)
            assertEquals(HttpStatusCode.Conflict, mismatch.status)
            assertTrue(mismatch.bodyAsText().contains("baseline-database-mismatch"))
            assertEquals(HttpStatusCode.Forbidden,
                getLease(fixture, "/api/v1/guard-db/bootstrap/proof").status)
        }

        testApplication {
            val fixture = fixture().apply { readResult = GuardDbBootstrapDatabaseRead.TooLarge }
            install(fixture)
            assertEquals(HttpStatusCode.Accepted, postExport(fixture).status)
            assertTrue(fixture.broker.approve(fixture.broker.pending().single().id))
            val oversizedRead = postExport(fixture)
            assertEquals(HttpStatusCode.PayloadTooLarge, oversizedRead.status)
            assertTrue(oversizedRead.bodyAsText().contains("baseline-database-too-large"))
        }

        testApplication {
            val fixture = fixture()
            fixture.snapshot = fixture.snapshot.copy(
                prepared = fixture.prepared.copy(
                    databaseBytes = MAX_GUARD_DB_BOOTSTRAP_DATABASE_BYTES + 1L,
                ),
            )
            install(fixture)
            val oversizedProof = postExport(fixture)
            assertEquals(HttpStatusCode.PayloadTooLarge, oversizedProof.status)
            assertTrue(fixture.broker.pending().isEmpty())
            assertEquals(0, fixture.databaseReads)
        }
    }

    @Test fun `approved export revalidates the exact snapshot inside the security boundary`() =
        testApplication {
            val fixture = fixture()
            install(fixture)
            assertEquals(HttpStatusCode.Accepted, postExport(fixture).status)
            assertTrue(fixture.broker.approve(fixture.broker.pending().single().id))
            fixture.security.beforeAction = {
                fixture.snapshot = fixture.snapshot.copy(
                    helper = fixture.snapshot.helper.copy(sha256 = "0".repeat(64)),
                )
            }

            val changed = postExport(fixture)
            assertEquals(HttpStatusCode.Conflict, changed.status)
            assertTrue(changed.bodyAsText().contains("bootstrap-authority-changed"))
            assertEquals(0, fixture.databaseReads)
            assertEquals(HttpStatusCode.Forbidden,
                getLease(fixture, "/api/v1/guard-db/bootstrap/proof").status)
        }

    @Test fun `a successful second export replaces the first lease`() = testApplication {
        val fixture = fixture()
        install(fixture)
        approveAndCreate(fixture)
        val first = LeaseRequest()
        fixture.captureId = "d".repeat(64)
        fixture.token = "e".repeat(64)
        assertEquals(HttpStatusCode.Accepted, postExport(fixture).status)
        assertTrue(fixture.broker.approve(fixture.broker.pending().single().id))
        assertEquals(HttpStatusCode.OK, postExport(fixture).status)

        assertEquals(HttpStatusCode.Forbidden,
            getLease(fixture, "/api/v1/guard-db/bootstrap/proof", first).status)
        assertEquals(HttpStatusCode.OK, getLease(
            fixture,
            "/api/v1/guard-db/bootstrap/proof",
            LeaseRequest(capture = fixture.captureId, token = fixture.token),
        ).status)
    }

    private suspend fun ApplicationTestBuilder.approveAndCreate(fixture: Fixture) {
        assertEquals(HttpStatusCode.Accepted, postExport(fixture).status)
        assertTrue(fixture.broker.approve(fixture.broker.pending().single().id))
        assertEquals(HttpStatusCode.OK, postExport(fixture).status)
    }

    private fun ApplicationTestBuilder.install(fixture: Fixture) {
        application {
            intercept(ApplicationCallPipeline.Setup) {
                context.mutableOriginConnectionPoint.remoteAddress =
                    context.request.headers[TEST_PEER_HEADER] ?: "127.0.0.1"
            }
            fixture.server.configureGuardDbMaintenanceApplication(this)
        }
    }

    private suspend fun ApplicationTestBuilder.postExport(fixture: Fixture) =
        postRawExport("{\"session\":\"${fixture.session}\",\"capture_id\":\"${fixture.captureId}\"}")

    private suspend fun ApplicationTestBuilder.postRawExport(body: String) =
        client.post("/api/v1/guard-db/bootstrap/export") {
            header(TEST_PEER_HEADER, PEER)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun ApplicationTestBuilder.getLease(
        fixture: Fixture,
        path: String,
        request: LeaseRequest = LeaseRequest(capture = fixture.captureId, token = fixture.token),
    ) = client.get(path) {
        header(TEST_PEER_HEADER, request.peer)
        header(GUARD_DB_BOOTSTRAP_SESSION_HEADER, request.session)
        header(GUARD_DB_BOOTSTRAP_CAPTURE_HEADER, request.capture)
        header(GUARD_DB_BOOTSTRAP_TOKEN_HEADER, request.token)
    }

    private data class LeaseRequest(
        val peer: String = PEER,
        val session: String = "1".repeat(64),
        val capture: String = CAPTURE,
        val token: String = TOKEN,
    )

    private fun fixture() = Fixture(temporary.newFolder("export-${System.nanoTime()}"))

    private class Fixture(directory: File) {
        val session = "1".repeat(64)
        val sentinel = sentinel()
        val prepared = prepared()
        var snapshot = snapshot(sentinel, prepared)
        var captureId = CAPTURE
        var token = TOKEN
        var now = 1_000L
        var databaseReads = 0
        var databaseExact = true
        var readResult: GuardDbBootstrapDatabaseRead = GuardDbBootstrapDatabaseRead.Exact(DATABASE)
        val broker = ApprovalBroker(monotonicMs = { 1_000L }, random = SecureRandom())
        val security = FakeSecurity()
        private val staging = GuardDbAppStaging(
            directory,
            inspect = { null },
            syncDirectory = { true },
            copyAndSync = { _, _ -> false },
            atomicMove = { _, _ -> false },
            validateFile = { it.isFile },
        )
        val server = GuardDbMaintenanceServer(
            context = null,
            sentinel = sentinel,
            client = GuardDbMaintenanceClient(EmptyTransport),
            staging = staging,
            preparedStore = GuardDbPreparedArmStore(directory),
            sentinelStore = GuardDbSentinelStore(directory),
            security = security,
            broker = broker,
            loadPrepared = { GuardDbPreparedArmLoad.Valid(prepared) },
            exactManifest = { null },
            refusalProof = { null },
            exactFinalStatus = { false },
            promoteArmed = { false },
            bootstrapExport = GuardDbBootstrapExportDependencies(
                snapshot = { epoch -> snapshot.takeIf { epoch == 41L } },
                readDatabase = { databaseReads++; readResult },
                databaseStillExact = { databaseExact },
                monotonicMs = { now },
                freshToken = { token },
            ),
            onFinalized = {},
        )
    }

    private class FakeSecurity : GuardDbMaintenanceSecurityAuthority {
        var epoch: Long? = 41L
        var beforeAction: () -> Unit = {}
        var afterAction: () -> Unit = {}
        override fun readyEpoch(): Long? = epoch
        override fun <T> commit(expectedEpoch: Long, action: () -> T): GuardDbMaintenanceSecurityResult<T> {
            if (epoch != expectedEpoch) return GuardDbMaintenanceSecurityResult.Changed
            val before = beforeAction
            beforeAction = {}
            before()
            val value = action()
            val after = afterAction
            afterAction = {}
            after()
            return GuardDbMaintenanceSecurityResult.Value(value)
        }
    }

    private object EmptyTransport : GuardDbMaintenanceTransport {
        override fun send(command: String): String? = null
        override fun sendLong(command: String, timeoutMs: Long) = DaemonLongResult.NotSubmitted
        override fun sendFile(command: String, file: File, timeoutMs: Long) = DaemonStreamResult.NotSubmitted
        override fun sendBytesBounded(command: String, maxBytes: Long): ByteArray? = null
    }

    private companion object {
        const val TEST_PEER_HEADER = "X-Test-Peer"
        const val PEER = "192.168.20.30"
        const val OTHER_PEER = "192.168.20.31"
        val CAPTURE = "c".repeat(64)
        val TOKEN = "f".repeat(64)
        val DATABASE = byteArrayOf(1, 2, 3, 4)
        val DATABASE_SHA = sha256(DATABASE)

        fun sentinel() = GuardDbStartupSentinel(
            GuardDbSentinelState.BASELINE_READY,
            "1".repeat(64),
            "2".repeat(64),
            "a".repeat(64),
            568L,
            14,
            "b".repeat(64),
            569L,
            15,
            2,
            3L,
            "c".repeat(64),
            41L,
            8888,
            true,
        )

        fun prepared() = GuardDbPreparedArm(
            "1".repeat(64),
            "2".repeat(64),
            4L,
            "a".repeat(64),
            568L,
            11,
            14,
            14,
            5L,
            "b".repeat(64),
            569L,
            11,
            15,
            15,
            4L,
            DATABASE_SHA,
            14,
            37L,
            "4".repeat(64),
            "5".repeat(64),
            GuardDbMaintenanceProtocol.MAX_OVERALL_BUDGET_MS,
            2,
            3L,
            "c".repeat(64),
            41L,
        )

        fun snapshot(sentinel: GuardDbStartupSentinel, prepared: GuardDbPreparedArm) =
            GuardDbBootstrapExportSnapshot(
                sentinel,
                prepared,
                GuardDbBootstrapInstalledApp(
                    4L, "a".repeat(64), "8".repeat(64), 568L, "0.9.7-test", 11, 14, 14,
                ),
                GuardDbBootstrapHelper(
                    123L,
                    "6".repeat(64),
                    "7".repeat(64),
                    GuardDbBootstrapExportSnapshot.SUPPORTED_CAPABILITIES_REPLY,
                    GuardDbBootstrapExportSnapshot.EMPTY_STATUS_REPLY,
                ),
                GuardDbBootstrapSecurity("HARDENED", 41L, "9".repeat(64), true, true),
            )

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
