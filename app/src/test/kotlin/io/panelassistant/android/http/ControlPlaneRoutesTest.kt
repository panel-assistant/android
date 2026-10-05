package io.panelassistant.android.http

import io.panelassistant.android.backup.PanelBackup
import io.panelassistant.android.security.SensitiveOperation
import io.panelassistant.android.migration.IdentityMigrationSurface
import io.panelassistant.android.migration.SuccessorUploadCapability
import io.panelassistant.android.migration.SuccessorHandoff
import io.panelassistant.android.migration.InstalledSuccessorStatus
import io.panelassistant.android.util.InstallOutcome
import kotlinx.coroutines.runBlocking
import io.panelassistant.android.util.AppInstaller
import io.panelassistant.android.util.ByteLimitExceeded
import io.panelassistant.android.util.BoundedStreams
import io.panelassistant.android.util.InstallProgress
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.routing.post
import io.ktor.server.response.respondText
import io.ktor.server.application.ApplicationCall
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream

class ControlPlaneRoutesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun installedOnlyOfferUsesTheExistingRouteWithoutDownloading() = testApplication {
        var installedOnlyCalls = 0
        var ordinaryCalls = 0
        val surface = object : IdentityMigrationSurface {
            override suspend fun offerInstalledOnly(): SuccessorHandoff.Outcome {
                installedOnlyCalls++
                return SuccessorHandoff.Outcome.Launched
            }
            override suspend fun offer(): SuccessorHandoff.Outcome {
                ordinaryCalls++
                return SuccessorHandoff.Outcome.NoSuccessorAsset
            }
        }
        application { routing { identityMigrationRoutes(surface) { _, _, _, _ -> true } } }

        val retry = client.post("/api/v1/successor/offer?installed_only=1")
        assertEquals(HttpStatusCode.OK, retry.status)
        assertTrue(retry.bodyAsText().contains("\"outcome\":\"Launched\""))
        assertEquals(1, installedOnlyCalls)
        assertEquals(0, ordinaryCalls)

        assertEquals(HttpStatusCode.BadRequest, client.post("/api/v1/successor/offer?installed_only=0").status)
        assertEquals(1, installedOnlyCalls)
        assertEquals(0, ordinaryCalls)

        assertEquals(HttpStatusCode.OK, client.post("/api/v1/successor/offer").status)
        assertEquals(1, ordinaryCalls)
    }

    @Test
    fun successorPurposeIsUnavailableWithoutAnActiveBridge() = testApplication {
        val pending = PendingUploadStore().apply { open() }
        val upload = ApkUploadRouteDependencies(
            enabled = { true },
            rootAvailable = { true },
            pending = pending,
            createStagingFile = { temporary.newFile("not-a-bridge.apk") },
            inspect = { UploadedApkIdentity("io.panelassistant.android", "0.9.8", "signed") },
            startInstall = { _, _ -> error("a non-bridge must never install a migration upload") },
        )
        application { routing { controlPlaneRoutes(dependencies(apkUpload = upload)) } }

        val response = client.post("/api/v1/install/apk?migration=successor&sha256=${"a".repeat(64)}") {
            setBody("apk")
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("""{"ok":false,"error":"not-a-bridge"}""", response.bodyAsText())
        assertNull(pending.pendingSummary())
    }

    @Test
    fun successorUploadRoutePinsIdentityDigestAndPurposeThroughCommit() = testApplication {
        val signer = "b".repeat(64)
        val capability = SuccessorUploadCapability("io.panelassistant.android", "0.9.8", 949, signer)
        var helperReady = false
        var installedStatus: InstalledSuccessorStatus = InstalledSuccessorStatus.Absent
        var genericEnabled = false
        var identity = UploadedApkIdentity(capability.pkg, capability.version, signer, setOf(signer), capability.versionCode)
        val expected = temporary.newFile("expected-successor.apk").apply { writeText("apk") }
        val digest = AppInstaller.sha256(expected)
        val pending = PendingUploadStore(newToken = { "migration-token" }).apply { open() }
        val installed = mutableListOf<PendingUploadStore.Entry>()
        var fileId = 0
        val upload = ApkUploadRouteDependencies(
            enabled = { genericEnabled },
            rootAvailable = { true },
            pending = pending,
            createStagingFile = { temporary.newFile("successor-${++fileId}.apk") },
            inspect = { identity },
            startInstall = { entry, ticket ->
                installed += entry
                entry.file.delete()
                InstallProgress.finish(ticket, "done")
            },
        )
        val surface = object : IdentityMigrationSurface {
            override fun successorUploadCapability() = capability.takeIf { helperReady }
            override fun installedSuccessorStatus() = installedStatus
        }
        application { routing { controlPlaneRoutes(dependencies(apkUpload = upload, identityMigration = surface)) } }

        val unavailable = client.get("/api/v1/successor")
        assertEquals(HttpStatusCode.NotFound, unavailable.status)
        assertEquals("""{"ok":false,"error":"not-a-bridge"}""", unavailable.bodyAsText())
        val blockedStage = client.post("/api/v1/install/apk?migration=successor&sha256=$digest") { setBody("apk") }
        assertEquals(HttpStatusCode.NotFound, blockedStage.status)
        assertNull(pending.pendingSummary())
        helperReady = true
        val probe = client.get("/api/v1/successor")
        assertEquals(HttpStatusCode.OK, probe.status)
        assertEquals("""{"package":"io.panelassistant.android","version":"0.9.8"}""", probe.bodyAsText())
        installedStatus = InstalledSuccessorStatus.Trusted(950)
        assertEquals(
            """{"package":"io.panelassistant.android","version":"0.9.8","installed_version_code":950}""",
            client.get("/api/v1/successor").bodyAsText(),
        )
        installedStatus = InstalledSuccessorStatus.Untrusted
        assertEquals(
            """{"package":"io.panelassistant.android","version":"0.9.8","installed_untrusted":true}""",
            client.get("/api/v1/successor").bodyAsText(),
        )
        installedStatus = InstalledSuccessorStatus.Absent

        suspend fun stage(hash: String = digest, body: String = "apk") =
            client.post("/api/v1/install/apk?migration=successor&sha256=$hash") { setBody(body) }

        val malformed = stage("A".repeat(64))
        assertEquals(HttpStatusCode.BadRequest, malformed.status)
        assertNull(pending.pendingSummary())
        val wrongDigest = stage("a".repeat(64))
        assertEquals(HttpStatusCode.UnprocessableEntity, wrongDigest.status)
        assertNull(pending.pendingSummary())

        val correct = identity
        for (bad in listOf(
            correct.copy(pkg = "io.github.maxlyth.hapaneld"),
            correct.copy(version = "0.9.7"),
            correct.copy(versionCode = 948),
            correct.copy(signerSha256s = setOf(signer, "c".repeat(64))),
            correct.copy(signerSha256s = setOf("c".repeat(64))),
        )) {
            identity = bad
            assertEquals(HttpStatusCode.UnprocessableEntity, stage().status)
            assertNull(pending.pendingSummary())
        }
        identity = correct
        assertEquals(HttpStatusCode.OK, stage().status)
        assertEquals(digest, pending.peek("migration-token")?.migrationSha256)

        helperReady = false
        assertCommit("migration-token", HttpStatusCode.NotFound, """{"status":"not-a-bridge"}""")
        assertNotNull(pending.peek("migration-token"))
        assertTrue(installed.isEmpty())
        helperReady = true
        pending.peek("migration-token")!!.file.writeText("changed")
        assertCommit("migration-token", HttpStatusCode.Conflict, """{"status":"migration-invalid"}""")
        assertNull(pending.pendingSummary())
        assertTrue(installed.isEmpty())

        assertEquals(HttpStatusCode.OK, stage().status)
        assertCommit("migration-token", HttpStatusCode.OK, """{"status":"started"}""")
        assertEquals(1, installed.size)
        assertEquals(digest, installed.single().migrationSha256)

        genericEnabled = true
        val generic = client.post("/api/v1/install/apk") { setBody("apk") }
        assertEquals(HttpStatusCode.OK, generic.status)
        assertCommit("migration-token", HttpStatusCode.OK, """{"status":"started"}""")
        assertEquals(2, installed.size)
        assertNull(installed.last().migrationSha256)
    }

    @Test
    fun successorCommitUsesInstallerThenInstalledOnlyHandoff() = testApplication {
        val signer = "b".repeat(64)
        val capability = SuccessorUploadCapability("io.panelassistant.android", "0.9.8", 949, signer)
        val identity = UploadedApkIdentity(capability.pkg, capability.version, signer, setOf(signer), capability.versionCode)
        val digest = AppInstaller.sha256(temporary.newFile("install-expected.apk").apply { writeText("apk") })
        var ready = true
        var installedStatus: InstalledSuccessorStatus = InstalledSuccessorStatus.Absent
        var installedAfterInstall: InstalledSuccessorStatus = InstalledSuccessorStatus.Trusted(948)
        var outcome: InstallOutcome = InstallOutcome.Succeeded
        var handoff: SuccessorHandoff.Outcome = SuccessorHandoff.Outcome.Launched
        var installs = 0
        var handoffs = 0
        var afterClaim: (PendingUploadStore.Entry) -> Unit = {}
        var result = ""
        val surface = object : IdentityMigrationSurface {
            override fun successorUploadCapability() = capability.takeIf { ready }
            override fun installedSuccessorStatus() = installedStatus
            override suspend fun offer(): SuccessorHandoff.Outcome = error("LAN migration must never request a download")
            override suspend fun offerInstalledOnly(): SuccessorHandoff.Outcome {
                handoffs++
                return handoff
            }
        }
        var fileId = 0
        val pending = PendingUploadStore(newToken = { "install-token" }).apply { open() }
        val upload = ApkUploadRouteDependencies(
            enabled = { true }, rootAvailable = { true }, pending = pending,
            createStagingFile = { temporary.newFile("install-${++fileId}.apk") },
            inspect = { identity },
            startInstall = { entry, ticket ->
                afterClaim(entry)
                result = runBlocking {
                    installUploadedApk(entry, surface, install = {
                        installs++
                        if (outcome == InstallOutcome.Succeeded) installedStatus = installedAfterInstall
                        outcome
                    })
                }
                assertFalse(entry.file.exists())
                InstallProgress.finish(ticket, result)
            },
        )
        application { routing { controlPlaneRoutes(dependencies(apkUpload = upload, identityMigration = surface)) } }
        suspend fun commit() {
            assertEquals(HttpStatusCode.OK, client.post("/api/v1/install/apk?migration=successor&sha256=$digest") {
                setBody("apk")
            }.status)
            assertCommit("install-token", HttpStatusCode.OK, """{"status":"started"}""")
        }

        afterClaim = { ready = false }
        commit()
        assertEquals("migration unavailable", result)
        assertEquals(0, installs)
        assertEquals(0, handoffs)

        ready = true
        afterClaim = { it.file.writeText("changed after claim") }
        commit()
        assertEquals("migration hash mismatch", result)
        assertEquals(0, installs)
        assertEquals(0, handoffs)

        afterClaim = {}
        outcome = InstallOutcome.Retryable("helper install failed")
        commit()
        assertEquals("helper install failed", result)
        assertEquals(1, installs)
        assertEquals(0, handoffs)

        outcome = InstallOutcome.Succeeded
        commit()
        assertEquals("installed successor was not confirmed", result)
        assertEquals(2, installs)
        assertEquals(0, handoffs)

        installedAfterInstall = InstalledSuccessorStatus.Trusted(949)
        commit()
        assertEquals("OK", result)
        assertEquals(3, installs)
        assertEquals(1, handoffs)

        handoff = SuccessorHandoff.Outcome.HelperNotConfirmed("helper lost")
        commit()
        assertEquals("installed-only handover: helper lost", result)
        assertEquals(3, installs)
        assertEquals(2, handoffs)

        handoff = SuccessorHandoff.Outcome.Launched
        installedStatus = InstalledSuccessorStatus.Trusted(950)
        commit()
        assertEquals("OK", result)
        assertEquals(3, installs)
        assertEquals(3, handoffs)

        installedStatus = InstalledSuccessorStatus.Untrusted
        commit()
        assertEquals("untrusted successor installed", result)
        assertEquals(3, installs)
        assertEquals(3, handoffs)
    }

    @Test
    fun playRoutesParseBoundedBodiesAndReflectAudioAdmission() = testApplication {
        val accepted = mutableListOf<String>()
        var admitting = true
        application {
            routing {
                controlPlaneRoutes(
                    dependencies(
                        playAudio = { url -> admitting.also { if (it) accepted += url } },
                    ),
                )
            }
        }

        val root = client.post("/play") { setBody("https://panel.test/root.mp3") }
        assertEquals(HttpStatusCode.OK, root.status)
        assertEquals("playing\n", root.bodyAsText())

        val versioned = client.post("/api/v1/play") { setBody("""{"url":"https://panel.test/v1.mp3"}""") }
        assertEquals(HttpStatusCode.OK, versioned.status)
        assertEquals("playing\n", versioned.bodyAsText())
        assertEquals(listOf("https://panel.test/root.mp3", "https://panel.test/v1.mp3"), accepted)

        val missing = client.post("/play") { setBody("not a URL") }
        assertEquals(HttpStatusCode.BadRequest, missing.status)
        assertEquals("no-url\n", missing.bodyAsText())

        admitting = false
        val stopping = client.post("/play") { setBody("https://panel.test/stopping.mp3") }
        assertEquals(HttpStatusCode.ServiceUnavailable, stopping.status)
        assertEquals("stopping\n", stopping.bodyAsText())
        assertEquals(2, accepted.size)

        val oversized = client.post("/play") { setBody("x".repeat(PaneldServer.MAX_PLAY_BODY_BYTES.toInt() + 1)) }
        assertEquals(HttpStatusCode.PayloadTooLarge, oversized.status)
        assertEquals("body-too-large\n", oversized.bodyAsText())
    }

    @Test
    fun componentRouteValidatesBeforeAdmissionAndPreservesBusyContract() = testApplication {
        val admitted = mutableListOf<Triple<String, String, String>>()
        var admitting = true
        application {
            routing {
                controlPlaneRoutes(
                    dependencies(
                        installedComponentVersion = { name -> if (name == "paneld") "0.9.3" else null },
                        installComponent = { name, action, version ->
                            admitted += Triple(name, action, version)
                            admitting
                        },
                    ),
                )
            }
        }

        assertJsonPost("name=unknown", HttpStatusCode.BadRequest, """{"status":"bad-component"}""")
        assertJsonPost("name=paneld&action=remove", HttpStatusCode.BadRequest, """{"status":"bad-action"}""")
        assertJsonPost("name=paneld&version=bad%2Ftag", HttpStatusCode.BadRequest, """{"status":"bad-version"}""")
        assertEquals(emptyList<Triple<String, String, String>>(), admitted)

        assertJsonPost(
            "name=paneld&version=v0.9.2",
            HttpStatusCode.Conflict,
            """{"status":"downgrade-refused"}""",
        )
        assertEquals(emptyList<Triple<String, String, String>>(), admitted)

        assertJsonPost(
            "name=paneld&version=v0.9.2&allow_downgrade=true",
            HttpStatusCode.OK,
            """{"status":"started"}""",
        )
        assertEquals(Triple("paneld", "update", "v0.9.2"), admitted.last())
        admitted.clear()

        assertJsonPost("name=paneld", HttpStatusCode.OK, """{"status":"started"}""")
        assertEquals(listOf(Triple("paneld", "update", "")), admitted)

        admitting = false
        assertJsonPost(
            "name=companion&action=reinstall&version=v0.9.2-rc2",
            HttpStatusCode.OK,
            """{"status":"busy"}""",
        )
        assertEquals(Triple("companion", "reinstall", "v0.9.2-rc2"), admitted.last())
    }

    @Test
    fun backupRouteComposesSharedAdmissionBoundedFailuresAndDownloadMetadata() = testApplication {
        var artifactId = 0
        fun artifact(bytes: ByteArray): PanelBackup.Artifact = PanelBackup.Artifact(
            temporary.newFile("backup-${++artifactId}.hpb").apply { writeBytes(bytes) },
        )
        var build: suspend (CompanionBackupRequest, String) -> PanelBackup.Artifact = { _, _ -> artifact(byteArrayOf(1, 2, 3)) }
        val buildCalls = mutableListOf<Pair<CompanionBackupRequest, String>>()
        application {
            routing {
                controlPlaneRoutes(
                    dependencies(
                        buildBackup = { request, passphrase ->
                            buildCalls += request to passphrase
                            build(request, passphrase)
                        },
                    ),
                )
            }
        }

        assertJsonPost(
            "include_companion=true",
            HttpStatusCode.BadRequest,
            """{"ok":false,"error":"passphrase-required","message":"A backup contains credentials. Supply a passphrase, or explicitly acknowledge plaintext export with allow_plaintext=1."}""",
            path = "/api/v1/backup",
        )
        assertEquals(emptyList<Pair<CompanionBackupRequest, String>>(), buildCalls)

        val held = assertNotNullTicket(InstallProgress.start("held"))
        try {
            assertJsonPost(
                "include_companion=true&passphrase=protected",
                HttpStatusCode.Conflict,
                """{"ok":false,"error":"busy"}""",
                path = "/api/v1/backup",
            )
            assertJsonPost(
                "include_companion=false&passphrase=expensive",
                HttpStatusCode.Conflict,
                """{"ok":false,"error":"busy"}""",
                path = "/api/v1/backup",
            )
            assertEquals(emptyList<Pair<CompanionBackupRequest, String>>(), buildCalls)
        } finally {
            InstallProgress.finish(held, "released")
        }

        build = { _, _ -> throw ByteLimitExceeded(32) }
        assertJsonPost(
            "include_companion=1&allow_plaintext=1",
            HttpStatusCode.PayloadTooLarge,
            """{"ok":false,"error":"companion-backup-too-large","presentation":{"code":"backup-companion-too-large","params":{}}}""",
            path = "/api/v1/backup",
        )
        assertOperationLaneReleased()

        build = { _, _ -> throw CompanionBackupUnavailable("Companion needs \"su\"") }
        assertJsonPost(
            "include_companion=true&allow_plaintext=true",
            HttpStatusCode.UnprocessableEntity,
            """{"ok":false,"error":"Companion needs \"su\""}""",
            path = "/api/v1/backup",
        )
        assertOperationLaneReleased()

        val retainedSource = temporary.newFile("retained-route.zip").apply { writeText("secret") }
        val retainedPlaintext = object : java.io.File(retainedSource.path) {
            override fun delete(): Boolean = false
        }
        val withdrawnSource = temporary.newFile("withdrawn-route.hpb").apply { writeText("sealed") }
        var artifactDeleteAttempts = 0
        val withdrawnArtifact = object : java.io.File(withdrawnSource.path) {
            override fun delete(): Boolean {
                artifactDeleteAttempts++
                throw java.io.IOException("sealed cleanup failed")
            }
        }
        val ownedSource = temporary.newFile("owned-route.payload").apply { writeText("temporary") }
        var ownedDeleteAttempts = 0
        val ownedFile = object : java.io.File(ownedSource.path) {
            override fun delete(): Boolean {
                ownedDeleteAttempts++
                throw java.io.IOException("owned cleanup failed")
            }
        }
        var captureCloseAttempts = 0
        build = { _, _ ->
            withBackupCaptureAndPlaintext(
                capture = java.io.Closeable {
                    captureCloseAttempts++
                    throw java.io.IOException("capture close failed")
                },
                createPlaintext = { retainedPlaintext },
            ) { _, plain ->
                withBackupArtifactCleanup(
                    plain = plain,
                    sealed = { withdrawnArtifact },
                    ownedFiles = { listOf(ownedFile) },
                ) { encryptedBackupArtifact(plain, withdrawnArtifact) }
            }
        }
        assertJsonPost(
            "include_companion=true&passphrase=protected",
            HttpStatusCode.InsufficientStorage,
            """{"ok":false,"error":"backup-staging-retained","message":"Sensitive temporary backup data could not be removed. Check panel storage, then retry; no backup was downloaded.","presentation":{"code":"backup-staging-retained","params":{}}}""",
            path = "/api/v1/backup",
        )
        assertTrue(retainedSource.exists())
        assertTrue(withdrawnSource.exists())
        assertTrue(ownedSource.exists())
        assertEquals(2, artifactDeleteAttempts)
        assertEquals(1, ownedDeleteAttempts)
        assertEquals(1, captureCloseAttempts)
        assertFalse(BackupDeliveryGate.occupied())
        assertOperationLaneReleased()

        var laneHeldWhenArtifactDeleted = false
        var deliveryHeldWhenArtifactDeleted = false
        build = { request, passphrase ->
            assertEquals(CompanionBackupRequest.REQUIRED, request)
            assertEquals("secret", passphrase)
            val source = temporary.newFile("tracked-backup.hpb").apply { writeBytes(byteArrayOf(7, 8, 9)) }
            val tracked = object : java.io.File(source.path) {
                override fun delete(): Boolean {
                    laneHeldWhenArtifactDeleted = InstallProgress.running
                    deliveryHeldWhenArtifactDeleted = BackupDeliveryGate.occupied()
                    return super.delete()
                }
            }
            PanelBackup.Artifact(tracked)
        }
        val success = client.post("/api/v1/backup") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("include_companion=true&passphrase=secret")
        }
        assertEquals(HttpStatusCode.OK, success.status)
        assertEquals(ContentType.Application.OctetStream, success.contentType())
        assertEquals("attachment; filename=\"test-panel-backup.hpb\"", success.headers[HttpHeaders.ContentDisposition])
        assertArrayEquals(byteArrayOf(7, 8, 9), success.bodyAsBytes())
        assertFalse("slow delivery does not monopolize the destructive operation lane", laneHeldWhenArtifactDeleted)
        assertTrue("artifact lifetime remains inside the single-delivery gate", deliveryHeldWhenArtifactDeleted)
        assertOperationLaneReleased()

        build = { request, passphrase ->
            assertEquals(CompanionBackupRequest.EXCLUDED, request)
            assertEquals("", passphrase)
            PanelBackup.Artifact(
                temporary.newFile("plaintext-backup.zip").apply { writeBytes(byteArrayOf(4, 5, 6)) },
                "zip",
            )
        }
        val plaintext = client.post("/api/v1/backup") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("include_companion=false&allow_plaintext=1")
        }
        assertEquals(HttpStatusCode.OK, plaintext.status)
        assertEquals("attachment; filename=\"test-panel-backup.zip\"", plaintext.headers[HttpHeaders.ContentDisposition])
        assertArrayEquals(byteArrayOf(4, 5, 6), plaintext.bodyAsBytes())
        assertOperationLaneReleased()
    }

    /**
     * The route's whole job here is to stop guessing. An omitted `include_companion` used to arrive at the
     * builder as `true`, so the simplest possible backup request refused with 422 on every panel that has
     * no HA Companion installed; an unrecognised value arrived as `false`, so a caller who typed something
     * they believed meant yes was handed a config-only archive without being told. Both are now carried
     * through as distinct facts, and the second is refused outright.
     */
    @Test
    fun backupRouteCarriesAnOmittedCompanionRequestApartFromExplicitOnesAndRefusesUninterpretableValues() = testApplication {
        val buildCalls = mutableListOf<CompanionBackupRequest>()
        val authorized = mutableListOf<SensitiveOperation>()
        application {
            routing {
                controlPlaneRoutes(
                    dependencies(
                        buildBackup = { request, _ ->
                            buildCalls += request
                            PanelBackup.Artifact(
                                temporary.newFile("companion-request-${buildCalls.size}.zip").apply {
                                    writeBytes(byteArrayOf(9))
                                },
                                "zip",
                            )
                        },
                        authorize = { _, operation, _, _ ->
                            authorized += operation
                            true
                        },
                    ),
                )
            }
        }

        suspend fun requestBackup(companionParameter: String?): io.ktor.client.statement.HttpResponse =
            client.post("/api/v1/backup") {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody("allow_plaintext=1" + (companionParameter?.let { "&include_companion=$it" } ?: ""))
            }

        // Omission is its own answer, not a synonym for either explicit value. This is the reported defect:
        // the caller asked for "this panel's backup" and never mentioned the Companion.
        assertEquals(HttpStatusCode.OK, requestBackup(null).status)
        assertEquals(listOf(CompanionBackupRequest.OMITTED), buildCalls)
        assertOperationLaneReleased()

        buildCalls.clear()
        listOf("true", "1").forEach { raw ->
            assertEquals("include_companion=$raw must reach the builder", HttpStatusCode.OK, requestBackup(raw).status)
            assertOperationLaneReleased()
        }
        assertEquals(
            "an explicit request for the Companion login must never be downgraded in transit",
            listOf(CompanionBackupRequest.REQUIRED, CompanionBackupRequest.REQUIRED),
            buildCalls,
        )

        buildCalls.clear()
        listOf("false", "0").forEach { raw ->
            assertEquals("include_companion=$raw must reach the builder", HttpStatusCode.OK, requestBackup(raw).status)
            assertOperationLaneReleased()
        }
        assertEquals(
            listOf(CompanionBackupRequest.EXCLUDED, CompanionBackupRequest.EXCLUDED),
            buildCalls,
        )

        buildCalls.clear()
        authorized.clear()
        // "yes"/"TRUE" are the values a caller reaches for when they mean the login is wanted, and "" is
        // what a half-built form submits. Reading any of them as "exclude" is the silent downgrade.
        listOf("yes", "TRUE", "", "no", "on", "2").forEach { raw ->
            val response = requestBackup(raw)
            assertEquals("include_companion=$raw must be refused", HttpStatusCode.BadRequest, response.status)
            assertEquals(
                """{"ok":false,"error":"invalid-include-companion","message":"include_companion must be true, 1, false or 0. Omit it to include the HA Companion login only when it is installed."}""",
                response.bodyAsText(),
            )
            assertOperationLaneReleased()
        }
        assertEquals(
            "no archive may be built from a request that was not understood",
            emptyList<CompanionBackupRequest>(),
            buildCalls,
        )
        assertEquals(
            "an uninterpretable request must not spend an approval challenge",
            emptyList<SensitiveOperation>(),
            authorized,
        )
        assertFalse(BackupDeliveryGate.occupied())
    }

    @Test
    fun apkUploadRouteComposesCapabilityLifetimeBoundsInspectionAndTokenHandoff() = testApplication {
        var enabled = false
        var rootAvailable = true
        var stagingFails = false
        var identity: UploadedApkIdentity? = UploadedApkIdentity("example.panel", "1.2.3", null)
        var closeDuringInspection = false
        var usableSpace = Long.MAX_VALUE
        var timeOutReceipt = false
        var installed: PendingUploadStore.Entry? = null
        var fileId = 0
        val stagedFiles = mutableListOf<java.io.File>()
        val pending = PendingUploadStore { "upload-token" }
        val upload = ApkUploadRouteDependencies(
            enabled = { enabled },
            rootAvailable = { rootAvailable },
            pending = pending,
            createStagingFile = {
                if (stagingFails) error("no staging space")
                temporary.newFile("upload-${++fileId}.apk").also { stagedFiles += it }
            },
            inspect = {
                if (closeDuringInspection) pending.close()
                identity
            },
            startInstall = { entry, progress ->
                installed = entry
                InstallProgress.finish(progress, "installed")
            },
            maxBytes = 4,
            usableSpace = { usableSpace },
            receiveBody = { input, output, maxBytes ->
                if (timeOutReceipt) throw BodyReceiptTimeout()
                BoundedStreams.copy(input, output, maxBytes)
            },
        )
        application { routing { controlPlaneRoutes(dependencies(apkUpload = upload)) } }

        assertUpload("apk", HttpStatusCode.Forbidden, """{"ok":false,"error":"disabled"}""")
        assertCommit("upload-token", HttpStatusCode.Forbidden, """{"status":"disabled"}""")

        enabled = true
        rootAvailable = false
        assertUpload("apk", HttpStatusCode.ServiceUnavailable, """{"ok":false,"error":"no-root"}""")

        rootAvailable = true
        assertUpload("apk", HttpStatusCode.ServiceUnavailable, """{"ok":false,"error":"stopping"}""")

        pending.open()
        stagingFails = true
        assertUpload("apk", HttpStatusCode.InternalServerError, """{"ok":false,"error":"upload-staging-failed"}""")

        stagingFails = false
        val beforeOversized = stagedFiles.size
        assertUpload("12345", HttpStatusCode.PayloadTooLarge, """{"ok":false,"error":"upload-too-large"}""")
        assertEquals(beforeOversized, stagedFiles.size)

        // A body that genuinely does not fit the free space is still refused before anything is written.
        usableSpace = 2L
        assertUpload("apk", HttpStatusCode.InsufficientStorage, """{"ok":false,"error":"insufficient-storage"}""")
        assertFalse(stagedFiles.last().exists())

        usableSpace = Long.MAX_VALUE
        timeOutReceipt = true
        assertUpload("apk", HttpStatusCode.RequestTimeout, """{"ok":false,"error":"upload-timeout"}""")
        assertFalse(stagedFiles.last().exists())
        timeOutReceipt = false

        assertUpload("", HttpStatusCode.BadRequest, """{"ok":false,"error":"upload-failed"}""")
        assertFalse(stagedFiles.last().exists())

        identity = null
        assertUpload("apk", HttpStatusCode.BadRequest, """{"ok":false,"error":"not-an-apk"}""")
        assertFalse(stagedFiles.last().exists())

        identity = UploadedApkIdentity("example.panel", "1.2.3", null)
        closeDuringInspection = true
        assertUpload("late", HttpStatusCode.ServiceUnavailable, """{"ok":false,"error":"stopping"}""")
        assertFalse(stagedFiles.last().exists())

        pending.open()
        closeDuringInspection = false
        // An upload that fits its actual staging requirement is admitted with no surplus demanded: four
        // usable bytes for a four-byte body. The retired fixed 64 MiB reserve refused exactly this.
        usableSpace = 4L
        assertUpload(
            "good",
            HttpStatusCode.OK,
            """{"ok":true,"token":"upload-token","package":"example.panel","version":"1.2.3","signer":"unsigned"}""",
        )
        // A further upload supersedes the staged one rather than being refused until its TTL; the
        // superseded token is dead, so nothing can be installed under it.
        assertUpload(
            "next",
            HttpStatusCode.OK,
            """{"ok":true,"token":"upload-token","package":"example.panel","version":"1.2.3","signer":"unsigned"}""",
        )
        assertUpload(
            "good",
            HttpStatusCode.OK,
            """{"ok":true,"token":"upload-token","package":"example.panel","version":"1.2.3","signer":"unsigned"}""",
        )
        assertCommit("wrong-token", HttpStatusCode.Conflict, """{"status":"stale-or-missing"}""")

        val held = assertNotNullTicket(InstallProgress.start("held"))
        try {
            assertCommit("upload-token", HttpStatusCode.OK, """{"status":"busy"}""")
        } finally {
            InstallProgress.finish(held, "released")
        }

        assertCommit("upload-token", HttpStatusCode.OK, """{"status":"started"}""")
        assertNotNull(installed)
        assertTrue(installed!!.file.exists())
        assertArrayEquals("good".toByteArray(), installed!!.file.readBytes())
        installed!!.file.delete()
        assertFalse(InstallProgress.running)
    }

    /** A URL is a source for the SAME review flow, so it must reach the same staged file, the same
     *  token and the same commit route — and must refuse everything the upload route refuses, before
     *  it touches the network. Each `fetched` assertion pins whether the download was actually
     *  attempted, because a gate that passes by never running is the failure mode worth catching. */
    @Test
    fun apkFetchFromUrlRouteComposesCapabilityUrlAdmissionBoundedFailuresAndTokenHandoff() = testApplication {
        var enabled = false
        var rootAvailable = true
        var stagingFails = false
        var identity: UploadedApkIdentity? = UploadedApkIdentity("example.panel", "1.2.3", null)
        var result = AppInstaller.DownloadResult.Succeeded
        var usableSpace = Long.MAX_VALUE
        var installed: PendingUploadStore.Entry? = null
        var fileId = 0
        var approved = true
        val approvalAsked = mutableListOf<Triple<SensitiveOperation, String, String>>()
        val stagedFiles = mutableListOf<java.io.File>()
        val fetched = mutableListOf<Pair<String, Long>>()
        val pending = PendingUploadStore { "fetch-token" }
        val upload = ApkUploadRouteDependencies(
            enabled = { enabled },
            rootAvailable = { rootAvailable },
            pending = pending,
            createStagingFile = {
                if (stagingFails) error("no staging space")
                temporary.newFile("fetch-${++fileId}.apk").also { stagedFiles += it }
            },
            inspect = { identity },
            startInstall = { entry, progress ->
                installed = entry
                InstallProgress.finish(progress, "installed")
            },
            maxBytes = 4,
            usableSpace = { usableSpace },
            fetch = { url, dest, maxBytes, _ ->
                fetched += url to maxBytes
                if (result == AppInstaller.DownloadResult.Succeeded) dest.writeBytes("good".toByteArray())
                result
            },
        )
        application {
            routing {
                controlPlaneRoutes(
                    dependencies(
                        apkUpload = upload,
                        authorize = { call, operation, payload, summary ->
                            approvalAsked += Triple(operation, payload, summary)
                            if (approved) true else {
                                call.respondText(
                                    """{"ok":false,"error":"approval-required"}""",
                                    ContentType.Application.Json,
                                    HttpStatusCode.Accepted,
                                )
                                false
                            }
                        },
                    ),
                )
            }
        }
        val url = "https://example.test/app.apk"

        assertFetch(url, HttpStatusCode.Forbidden, """{"ok":false,"error":"disabled"}""")

        enabled = true
        rootAvailable = false
        assertFetch(url, HttpStatusCode.ServiceUnavailable, """{"ok":false,"error":"no-root"}""")
        rootAvailable = true

        // A refused scheme, a relative reference and an empty field are rejected as bad input, never as
        // a failed download, so an operator can tell a typo from an unreachable host.
        assertFetch("http://example.test/app.apk", HttpStatusCode.BadRequest, """{"ok":false,"error":"invalid-url"}""")
        assertFetch("example.test/app.apk", HttpStatusCode.BadRequest, """{"ok":false,"error":"invalid-url"}""")
        assertFetch("", HttpStatusCode.BadRequest, """{"ok":false,"error":"invalid-url"}""")

        // A malformed owner identifier is refused as input, never silently replaced with an unowned fetch.
        assertFetch(url, HttpStatusCode.BadRequest, """{"ok":false,"error":"invalid-request"}""", request = "")
        assertFetch(url, HttpStatusCode.BadRequest, """{"ok":false,"error":"invalid-request"}""", request = "bad id!")

        // THE gate this whole revision exists for: authority is settled BEFORE the panel is aimed at
        // anything. An unapproved caller must not be able to make the panel emit even one request.
        approved = false
        assertFetch(url, HttpStatusCode.Accepted, """{"ok":false,"error":"approval-required"}""")
        assertEquals(0, fetched.size)
        // The approver is told which host the panel would be aimed at, under its own operation.
        assertEquals(SensitiveOperation.APK_FETCH, approvalAsked.last().first)
        assertEquals("Download an APK from example.test", approvalAsked.last().third)

        // What is approved must be THE DESTINATION. An approval bound to anything else would let a
        // granted approval be replayed to send the panel somewhere the operator never saw.
        val approvedPayload = approvalAsked.last().second
        assertFetch("https://elsewhere.test/app.apk", HttpStatusCode.Accepted, """{"ok":false,"error":"approval-required"}""")
        assertNotEquals(
            "a different destination must not reuse the same approval payload",
            approvedPayload,
            approvalAsked.last().second,
        )
        assertFetch(url, HttpStatusCode.Accepted, """{"ok":false,"error":"approval-required"}""")
        assertEquals(
            "the same destination must present the same approval payload, so an approval is usable on retry",
            approvedPayload,
            approvalAsked.last().second,
        )
        assertEquals(0, fetched.size)
        approved = true

        assertFetch(url, HttpStatusCode.ServiceUnavailable, """{"ok":false,"error":"stopping"$OWNER}""")

        pending.open()
        stagingFails = true
        assertFetch(url, HttpStatusCode.InternalServerError, """{"ok":false,"error":"upload-staging-failed"$OWNER}""")
        stagingFails = false

        usableSpace = 0L
        assertFetch(url, HttpStatusCode.InsufficientStorage, """{"ok":false,"error":"insufficient-storage"$OWNER}""")
        assertFalse(stagedFiles.last().exists())

        // Nothing above this line may have reached the network: capability, URL shape, lifetime and
        // storage are all decided before the panel is asked to fetch anything.
        assertEquals(0, fetched.size)

        // A ceiling imposed by the panel's own free space reports as storage, not as an oversized file.
        usableSpace = 2L
        result = AppInstaller.DownloadResult.TooLarge
        assertFetch(url, HttpStatusCode.InsufficientStorage, """{"ok":false,"error":"insufficient-storage"$OWNER}""")
        assertEquals(1, fetched.size)
        assertEquals(url to 2L, fetched.last())
        assertFalse(stagedFiles.last().exists())

        usableSpace = Long.MAX_VALUE
        assertFetch(url, HttpStatusCode.PayloadTooLarge, """{"ok":false,"error":"fetch-too-large"$OWNER}""")
        assertEquals(url to 4L, fetched.last())
        assertFalse(stagedFiles.last().exists())

        result = AppInstaller.DownloadResult.TimedOut
        assertFetch(url, HttpStatusCode.RequestTimeout, """{"ok":false,"error":"fetch-timeout"$OWNER}""")
        assertFalse(stagedFiles.last().exists())

        result = AppInstaller.DownloadResult.Failed
        assertFetch(url, HttpStatusCode.BadGateway, """{"ok":false,"error":"fetch-failed"$OWNER}""")
        assertFalse(stagedFiles.last().exists())

        // Bytes that arrive but are not an APK are refused after inspection, and still leave nothing.
        result = AppInstaller.DownloadResult.Succeeded
        identity = null
        assertFetch(url, HttpStatusCode.BadRequest, """{"ok":false,"error":"not-an-apk"$OWNER}""")
        assertFalse(stagedFiles.last().exists())

        // Every failure above released its lease: this fetch is admitted rather than answered busy.
        identity = UploadedApkIdentity("example.panel", "1.2.3", null)
        assertFetch(
            url,
            HttpStatusCode.OK,
            """{"ok":true,"token":"fetch-token","package":"example.panel","version":"1.2.3","signer":"unsigned"$OWNER}""",
        )

        // A staged token is the operator's LAST intent, not a lock: starting another fetch retires it
        // and issues a new one, rather than stranding them behind it until the TTL expires.
        assertFetch(
            url,
            HttpStatusCode.OK,
            """{"ok":true,"token":"fetch-token","package":"example.panel","version":"1.2.3","signer":"unsigned"$OWNER}""",
        )
        assertCommit("wrong-token", HttpStatusCode.Conflict, """{"status":"stale-or-missing"}""")

        // The unchanged commit route installs exactly the fetched bytes.
        assertCommit("fetch-token", HttpStatusCode.OK, """{"status":"started"}""")
        assertNotNull(installed)
        assertTrue(installed!!.file.exists())
        assertArrayEquals("good".toByteArray(), installed!!.file.readBytes())
        installed!!.file.delete()
        assertFalse(InstallProgress.running)
    }

    /** Cancel must stop the operator's own download and nothing else. A cancel that stopped "whatever
     *  is running" would abort the replacement of a fetch the operator had already given up on — which
     *  is precisely the moment they are most likely to press it. */
    @Test
    fun apkFetchCancellationStopsOnlyTheRequestThatOwnsItAndFreesTheSlot() = testApplication {
        var cancelDuring: String? = null
        var cancelReported: Boolean? = null
        var cancelSucceedsAnyway = false
        var fileId = 0
        val stagedFiles = mutableListOf<java.io.File>()
        val pending = PendingUploadStore { "fetch-token" }.apply { open() }
        val upload = ApkUploadRouteDependencies(
            enabled = { true },
            rootAvailable = { true },
            pending = pending,
            createStagingFile = { temporary.newFile("cancel-${++fileId}.apk").also { stagedFiles += it } },
            inspect = { UploadedApkIdentity("example.panel", "1.2.3", null) },
            startInstall = { _, ticket -> InstallProgress.finish(ticket, "done") },
            maxBytes = 16,
            usableSpace = { Long.MAX_VALUE },
            fetch = { _, dest, _, abort ->
                // A cancel arriving mid-transfer, driven deterministically instead of with threads.
                cancelDuring?.let { cancelReported = pending.cancelPanelWork(it) }
                if (cancelSucceedsAnyway) {
                    dest.writeBytes("good".toByteArray())
                    AppInstaller.DownloadResult.Succeeded
                } else if (abort.isAborted) {
                    AppInstaller.DownloadResult.Aborted
                } else {
                    dest.writeBytes("good".toByteArray())
                    AppInstaller.DownloadResult.Succeeded
                }
            },
        )
        application { routing { controlPlaneRoutes(dependencies(apkUpload = upload)) } }
        val url = "https://example.test/app.apk"

        // A cancel naming somebody else's request stops nothing, and the download completes normally.
        cancelDuring = "someone-elses-request"
        assertFetch(
            url,
            HttpStatusCode.OK,
            """{"ok":true,"token":"fetch-token","package":"example.panel","version":"1.2.3","signer":"unsigned"$OWNER}""",
        )
        assertEquals(false, cancelReported)
        assertCommit("fetch-token", HttpStatusCode.OK, """{"status":"started"}""")
        assertFalse(InstallProgress.running)

        // The owning request stops its own download, and says so rather than blaming the link.
        cancelDuring = FETCH_REQUEST
        assertFetch(url, HttpStatusCode(499, "Client Closed Request"), """{"ok":false,"error":"cancelled"$OWNER}""")
        assertEquals(true, cancelReported)
        assertFalse("a cancelled download must leave nothing staged", stagedFiles.last().exists())

        // The harder case: the transfer SUCCEEDS while the operator is cancelling. Reporting success
        // and handing back an installable token would contradict what they were just told.
        cancelSucceedsAnyway = true
        assertFetch(url, HttpStatusCode(499, "Client Closed Request"), """{"ok":false,"error":"cancelled"$OWNER}""")
        assertFalse("a cancelled fetch must not stage its bytes", stagedFiles.last().exists())
        assertCommit("fetch-token", HttpStatusCode.Conflict, """{"status":"stale-or-missing"}""")
        cancelSucceedsAnyway = false

        // Cancelling released the slot immediately: the next fetch is admitted, not answered busy.
        cancelDuring = null
        assertFetch(
            url,
            HttpStatusCode.OK,
            """{"ok":true,"token":"fetch-token","package":"example.panel","version":"1.2.3","signer":"unsigned"$OWNER}""",
        )
        pending.clear()
    }

    @Test fun apkFetchCancelRouteRefusesMalformedOwnersAndReportsWhenNothingWasStopped() = testApplication {
        val upload = ApkUploadRouteDependencies(
            enabled = { true },
            rootAvailable = { true },
            pending = PendingUploadStore().apply { open() },
            createStagingFile = { error("unused") },
            inspect = { error("unused") },
            startInstall = { _, _ -> error("unused") },
        )
        application { routing { controlPlaneRoutes(dependencies(apkUpload = upload)) } }

        assertCancel("", HttpStatusCode.BadRequest, """{"ok":false,"error":"invalid-request"}""")
        assertCancel("has space", HttpStatusCode.BadRequest, """{"ok":false,"error":"invalid-request"}""")
        // Honest about doing nothing, rather than reporting a success it did not perform.
        assertCancel("nothing-in-flight", HttpStatusCode.OK, """{"ok":true,"cancelled":false}""")
    }

    /** Discard is deliberately ungated, like the fetch cancel above: it must keep working while the
     *  enable/root state changes around it, it removes uncommitted bytes rather than installing
     *  anything, and it grants nothing a LAN client does not already have, because a new upload
     *  already supersedes a staged entry. The pending probe is its read side, and because it answers
     *  any LAN client it must name what is pending without ever carrying the commit token. (Issue #96) */
    @Test fun apkDiscardRetiresOnlyThePendingEntryAndThePendingProbeNeverLeaksTheToken() = testApplication {
        var enabled = true
        var fileId = 0
        var discardDuringAuthorize = false
        val stagedFiles = mutableListOf<java.io.File>()
        var installed: PendingUploadStore.Entry? = null
        val pending = PendingUploadStore(newDiscardId = { "probe-ref" }, newToken = { "secret-token" })
        val upload = ApkUploadRouteDependencies(
            enabled = { enabled },
            rootAvailable = { true },
            pending = pending,
            createStagingFile = { temporary.newFile("discard-${++fileId}.apk").also { stagedFiles += it } },
            inspect = { UploadedApkIdentity("example.panel", "1.2.3", null) },
            startInstall = { entry, progress ->
                installed = entry
                InstallProgress.finish(progress, "installed")
            },
            maxBytes = 16,
        )
        application {
            routing {
                controlPlaneRoutes(
                    dependencies(
                        apkUpload = upload,
                        // Models the operator discarding WHILE the commit awaits its Hardened approval
                        // — the reorder's remaining race, forced deterministically.
                        authorize = { _, _, _, _ ->
                            if (discardDuringAuthorize) pending.discard("secret-token")
                            true
                        },
                    ),
                )
            }
        }

        val inspected = """{"ok":true,"token":"secret-token","package":"example.panel","version":"1.2.3","signer":"unsigned"}"""

        // A discard must name what it removes; a blind discard was the review's stale-card finding.
        assertDiscard("", HttpStatusCode.BadRequest, """{"ok":false,"error":"invalid-request"}""")
        // A scoped discard when nothing is pending is an idempotent success, not an error.
        assertDiscard("token=probe-ref", HttpStatusCode.OK, """{"ok":true,"discarded":false}""")
        assertPending("""{"pending":false}""")

        pending.open()
        assertUpload("good", HttpStatusCode.OK, inspected)

        // The probe names the pending upload and its discard reference — never the commit token,
        // which is the install authority and must reach only the client that staged the upload.
        val probeBody = assertPending("""{"pending":true,"discard":"probe-ref","package":"example.panel","version":"1.2.3","signer":"unsigned"}""")
        assertFalse("the probe must never carry the token", probeBody.contains("secret-token"))
        // And the reference it hands out cannot commit anything.
        assertCommit("probe-ref", HttpStatusCode.Conflict, """{"status":"stale-or-missing"}""")

        // A reference to an entry this slot no longer holds removes nothing.
        assertDiscard("token=wrong-token", HttpStatusCode.Conflict, """{"ok":false,"error":"different-pending"}""")
        assertTrue("a mismatched discard must not delete the pending file", stagedFiles.last().exists())

        // The exact token retires the entry, deletes its bytes and kills the commit route.
        assertDiscard("token=secret-token", HttpStatusCode.OK, """{"ok":true,"discarded":true}""")
        assertFalse(stagedFiles.last().exists())
        assertCommit("secret-token", HttpStatusCode.Conflict, """{"status":"stale-or-missing"}""")
        assertPending("""{"pending":false}""")

        // The probe's reference is the post-reload recovery: it removes exactly the probed entry.
        assertUpload("next", HttpStatusCode.OK, inspected)
        assertDiscard("token=probe-ref", HttpStatusCode.OK, """{"ok":true,"discarded":true}""")
        assertFalse(stagedFiles.last().exists())

        // Disabling the capability wedges neither cleanup surface — both still answer honestly.
        enabled = false
        assertPending("""{"pending":false}""")
        assertDiscard("token=probe-ref", HttpStatusCode.OK, """{"ok":true,"discarded":false}""")
        enabled = true

        // A committed upload was claimed only when its install genuinely started, so it has left the
        // slot for good: a discard during the running install truthfully finds nothing, touches
        // nothing, and nothing can reappear behind the answer.
        assertUpload("keep", HttpStatusCode.OK, inspected)
        assertCommit("secret-token", HttpStatusCode.OK, """{"status":"started"}""")
        assertNotNull(installed)
        assertDiscard("token=secret-token", HttpStatusCode.OK, """{"ok":true,"discarded":false}""")
        assertTrue("the install's bytes must be untouched by a later discard", installed!!.file.exists())
        installed!!.file.delete()
        assertFalse(InstallProgress.running)

        // A busy operation lane refuses the commit BEFORE the entry is claimed, so the upload stays
        // pending — probeable and discardable — for the whole busy period. Nothing was taken, so
        // nothing has to come back: the state a shared claim marker used to misrepresent under
        // overlapping commits simply does not exist.
        assertUpload("race", HttpStatusCode.OK, inspected)
        val held = assertNotNullTicket(InstallProgress.start("held"))
        try {
            assertCommit("secret-token", HttpStatusCode.OK, """{"status":"busy"}""")
            assertPending("""{"pending":true,"discard":"probe-ref","package":"example.panel","version":"1.2.3","signer":"unsigned"}""")
            assertDiscard("token=secret-token", HttpStatusCode.OK, """{"ok":true,"discarded":true}""")
            assertFalse(stagedFiles.last().exists())
        } finally {
            InstallProgress.finish(held, "released")
        }
        assertCommit("secret-token", HttpStatusCode.Conflict, """{"status":"stale-or-missing"}""")

        // The reorder's remaining race: the operator discards while the commit is awaiting approval.
        // The claim then finds nothing — and the just-taken operation lane must be released, because a
        // stranded lane would block every later install, repair and restore.
        assertUpload("approved-away", HttpStatusCode.OK, inspected)
        discardDuringAuthorize = true
        assertCommit("secret-token", HttpStatusCode.Conflict, """{"status":"stale-or-missing"}""")
        discardDuringAuthorize = false
        assertFalse(stagedFiles.last().exists())
        assertOperationLaneReleased()
    }

    /** The abort must win a race it cannot see: an operator can press Cancel while the panel is still
     *  connecting, before there is any connection to close. */
    @Test fun downloadAbortRefusesAConnectionAttachedAfterTheOperatorCancelled() {
        val abort = io.panelassistant.android.util.DownloadAbort()
        assertFalse(abort.isAborted)
        abort.abort()
        assertTrue(abort.isAborted)

        var disconnected = false
        val connection = object : java.net.HttpURLConnection(java.net.URL("https://example.test/app.apk")) {
            override fun connect() = Unit
            override fun usingProxy() = false
            override fun disconnect() { disconnected = true }
        }
        assertFalse("a download must not start once its owner has cancelled", abort.attach(connection))
        assertTrue("the late connection must be closed, not left open", disconnected)
    }

    @Test fun apkFetchRequestIdAdmissionAcceptsOnlyBoundedOpaqueIdentifiers() {
        assertEquals("req-1", validApkFetchRequestId("  req-1  "))
        assertEquals("A_b-9", validApkFetchRequestId("A_b-9"))
        assertNull(validApkFetchRequestId(""))
        assertNull(validApkFetchRequestId("   "))
        assertNull(validApkFetchRequestId("has space"))
        assertNull(validApkFetchRequestId("quote\"injection"))
        assertNull(validApkFetchRequestId("newline\nin-it"))
        assertNull(validApkFetchRequestId("a".repeat(APK_FETCH_REQUEST_ID_MAX_CHARS + 1)))
        assertEquals(
            "a".repeat(APK_FETCH_REQUEST_ID_MAX_CHARS),
            validApkFetchRequestId("a".repeat(APK_FETCH_REQUEST_ID_MAX_CHARS)),
        )
    }

    @Test fun apkFetchUrlAdmissionAcceptsOnlyExplicitBoundedHttpsLinks() {
        assertEquals("https://example.test/app.apk", validApkFetchUrl("  https://example.test/app.apk  "))
        assertEquals("HTTPS://example.test/app.apk", validApkFetchUrl("HTTPS://example.test/app.apk"))

        assertNull(validApkFetchUrl("http://example.test/app.apk"))
        assertNull(validApkFetchUrl("ftp://example.test/app.apk"))
        assertNull(validApkFetchUrl("file:///data/local/tmp/app.apk"))
        assertNull(validApkFetchUrl("example.test/app.apk"))
        assertNull(validApkFetchUrl("/app.apk"))
        assertNull(validApkFetchUrl(""))
        assertNull(validApkFetchUrl("   "))
        assertNull(validApkFetchUrl("https:///app.apk"))
        assertNull(validApkFetchUrl("https://example.test/a b.apk"))

        // The bound is on the whole link, and it is inclusive at the limit.
        assertEquals("https://example.test/ok.apk", validApkFetchUrl("https://example.test/ok.apk", maxChars = 27))
        assertNull(validApkFetchUrl("https://example.test/ok.apk", maxChars = 26))
    }

    @Test fun bodyReaderEnforcesAbsoluteElapsedDeadlineWithoutSleeping() {
        var now = 0L
        val source = object : ByteArrayInputStream("payload".toByteArray()) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                super.read(buffer, offset, length).also { now = 2_000_000L }
        }

        assertThrows(BodyReceiptTimeout::class.java) {
            DeadlineBoundedBody.readBytes(
                source,
                maxBytes = 64,
                timeoutMs = 1,
                nanoTime = { now },
                deadlineFactory = { _, _ -> AutoCloseable {} },
            )
        }
    }

    @Test fun sharedMaterializedBodyReaderMapsTheDeadlineWithoutWaiting() = testApplication {
        application {
            routing {
                post("/bounded") {
                    val receipt = receiveBoundedBody(
                        call,
                        maxBytes = 64,
                        timeoutMs = 7,
                        reader = { _, maxBytes, timeoutMs ->
                            assertEquals(64L, maxBytes)
                            assertEquals(7L, timeoutMs)
                            throw BodyReceiptTimeout()
                        },
                    )
                    when (receipt) {
                        BoundedBodyReceipt.TimedOut ->
                            call.respondText("timeout", status = HttpStatusCode.RequestTimeout)
                        BoundedBodyReceipt.TooLarge ->
                            call.respondText("too-large", status = HttpStatusCode.PayloadTooLarge)
                        is BoundedBodyReceipt.Received ->
                            call.respondText("received")
                    }
                }
            }
        }

        val response = client.post("/bounded") { setBody("payload") }
        assertEquals(HttpStatusCode.RequestTimeout, response.status)
        assertEquals("timeout", response.bodyAsText())
    }

    @Test fun uploadCapacityAdmitsTheActualRequirementAndBudgetsUnknownBodiesAtTheMaximum() {
        assertEquals(0L, uploadStagingLimit(usableBytes = 0L, maxPayloadBytes = 4L))
        assertEquals(3L, uploadStagingLimit(usableBytes = 3L, maxPayloadBytes = 4L))
        assertEquals(4L, uploadStagingLimit(usableBytes = Long.MAX_VALUE, maxPayloadBytes = 4L))
        assertEquals(0L, uploadStagingLimit(usableBytes = Long.MAX_VALUE, maxPayloadBytes = -1L))
    }

    @Test fun apkCommitApprovalNamesTheInspectedPackageVersionAndSigner() = testApplication {
        val pending = PendingUploadStore(newToken = { "inspected-token" }).apply { open() }
        val lease = (pending.begin() as PendingUploadStore.BeginResult.Granted).lease
        val identity = UploadedApkIdentity("com.example.panel", "2.4.1", "abcdef0123456789deadbeef")
        pending.stage(lease, temporary.newFile("inspected.apk").apply { writeText("apk") }, identity)
        var summary = ""
        val upload = ApkUploadRouteDependencies(
            enabled = { true },
            rootAvailable = { true },
            pending = pending,
            createStagingFile = { error("unused") },
            inspect = { error("unused") },
            startInstall = { _, ticket -> InstallProgress.finish(ticket, "done") },
        )
        application {
            routing {
                controlPlaneRoutes(dependencies(apkUpload = upload, authorize = { _, _, _, value ->
                    summary = value
                    true
                }))
            }
        }

        assertCommit("inspected-token", HttpStatusCode.OK, """{"status":"started"}""")
        assertTrue(summary.contains("com.example.panel"))
        assertTrue(summary.contains("2.4.1"))
        assertTrue(summary.contains("abcdef0123456789"))
    }

    @Test fun apkApprovalSummaryStripsDisplayControlsBoundsEveryFieldAndKeepsSignerAheadOfVersion() {
        val summary = uploadedApkApprovalSummary(
            UploadedApkIdentity(
                pkg = "p".repeat(32) + "\u202E\n" + "p".repeat(64),
                signerSha256 = "a".repeat(32) + "\u2066\u0000" + "a".repeat(64),
                version = "v".repeat(10) + "\u202D\u2029" + "v".repeat(64),
            ),
        )

        val boundedPackage = "p".repeat(64)
        val boundedSigner = "a".repeat(64)
        val boundedVersion = "v".repeat(20)
        assertEquals(
            "Install $boundedPackage (signer $boundedSigner) version $boundedVersion",
            summary,
        )
        assertTrue(summary.indexOf(boundedPackage) < summary.indexOf(boundedSigner))
        assertTrue(summary.indexOf(boundedSigner) < summary.indexOf(boundedVersion))
        assertTrue("summary must fit the approval broker's 180-character cap", summary.length <= 180)
        assertFalse(summary.any { Character.getType(it) == Character.CONTROL.toInt() })
        assertFalse(summary.any { Character.getType(it) == Character.FORMAT.toInt() })
    }

    /**
     * A bundle whose panel state could not be read still downloads — losing the config projection too
     * would punish exactly the panel that most needs a backup — but the owner is told now. Reporting the
     * same "backup ready" would move the silence out of the manifest and into the progress line.
     */
    @Test
    fun backupTerminalSeparatesAnIncompleteArchiveFromACompleteOne() = testApplication {
        var stateUnavailable = false
        application {
            routing {
                controlPlaneRoutes(
                    dependencies(
                        buildBackup = { _, _ ->
                            PanelBackup.Artifact(
                                temporary.newFile("state-marker-$stateUnavailable.zip").apply {
                                    writeBytes(byteArrayOf(7))
                                },
                                "zip",
                                stateUnavailable,
                            )
                        },
                    ),
                )
            }
        }

        suspend fun requestBackup() = client.post("/api/v1/backup") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("allow_plaintext=1")
        }

        assertEquals(HttpStatusCode.OK, requestBackup().status)
        val complete = InstallProgress.presentationSnapshot()
        assertEquals("backup ready", complete.message)
        assertEquals("backup-ready", complete.presentation?.code)
        assertOperationLaneReleased()

        stateUnavailable = true
        val incomplete = requestBackup()
        assertEquals("an incomplete archive is still delivered", HttpStatusCode.OK, incomplete.status)
        assertArrayEquals(byteArrayOf(7), incomplete.bodyAsBytes())
        val marked = InstallProgress.presentationSnapshot()
        assertEquals("backup ready without panel state", marked.message)
        assertEquals("backup-ready-state-unavailable", marked.presentation?.code)
        assertNotEquals(
            "the incomplete terminal must not reuse the complete one",
            complete.presentation?.code,
            marked.presentation?.code,
        )
        assertOperationLaneReleased()
    }

    private fun dependencies(
        playAudio: (String) -> Boolean = { true },
        installComponent: (String, String, String) -> Boolean = { _, _, _ -> true },
        installedComponentVersion: (String) -> String? = { null },
        buildBackup: suspend (CompanionBackupRequest, String) -> PanelBackup.Artifact = { _, _ ->
            PanelBackup.Artifact(temporary.newFile("unused-backup.hpb"))
        },
        apkUpload: ApkUploadRouteDependencies = unusedApkUpload(),
        identityMigration: IdentityMigrationSurface = IdentityMigrationSurface.NONE,
        authorize: suspend (ApplicationCall, SensitiveOperation, String, String) -> Boolean = { _, _, _, _ -> true },
    ) = ControlPlaneRouteDependencies(
        playAudio = playAudio,
        installComponent = installComponent,
        installedComponentVersion = installedComponentVersion,
        buildBackup = buildBackup,
        backupFileStem = { "test-panel" },
        apkUpload = apkUpload,
        identityMigration = identityMigration,
        authorize = authorize,
    )

    private fun unusedApkUpload() = ApkUploadRouteDependencies(
        enabled = { false },
        rootAvailable = { false },
        pending = PendingUploadStore(),
        createStagingFile = { error("unused") },
        inspect = { error("unused") },
        startInstall = { _, _ -> error("unused") },
    )

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.assertJsonPost(
        body: String,
        status: HttpStatusCode,
        responseBody: String,
        path: String = "/api/v1/install/component",
    ) {
        val response = client.post(path) {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(body)
        }
        assertEquals(status, response.status)
        assertEquals(responseBody, response.bodyAsText())
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.assertUpload(
        body: String,
        status: HttpStatusCode,
        responseBody: String,
    ) {
        val response = client.post("/api/v1/install/apk") { setBody(body) }
        assertEquals(status, response.status)
        assertEquals(responseBody, response.bodyAsText())
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.assertFetch(
        url: String,
        status: HttpStatusCode,
        responseBody: String,
        request: String = FETCH_REQUEST,
    ) {
        val response = client.post("/api/v1/install/apk/from-url") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(
                "url=" + java.net.URLEncoder.encode(url, "UTF-8") +
                    "&request=" + java.net.URLEncoder.encode(request, "UTF-8"),
            )
        }
        assertEquals(status, response.status)
        assertEquals(responseBody, response.bodyAsText())
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.assertCancel(
        request: String,
        status: HttpStatusCode,
        responseBody: String,
    ) {
        val response = client.post("/api/v1/install/apk/fetch/cancel") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("request=" + java.net.URLEncoder.encode(request, "UTF-8"))
        }
        assertEquals(status, response.status)
        assertEquals(responseBody, response.bodyAsText())
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.assertDiscard(
        body: String,
        status: HttpStatusCode,
        responseBody: String,
    ) {
        val response = client.post("/api/v1/install/apk/discard") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(body)
        }
        assertEquals(status, response.status)
        assertEquals(responseBody, response.bodyAsText())
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.assertPending(responseBody: String): String {
        val response = client.get("/api/v1/install/apk/pending")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertEquals(responseBody, body)
        return body
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.assertCommit(
        token: String,
        status: HttpStatusCode,
        responseBody: String,
    ) {
        val response = client.post("/api/v1/install/apk/commit") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("token=$token")
        }
        assertEquals(status, response.status)
        assertEquals(responseBody, response.bodyAsText())
    }

    private fun assertOperationLaneReleased() {
        val ticket = assertNotNullTicket(InstallProgress.start("probe"))
        InstallProgress.finish(ticket, "released")
        assertFalse(InstallProgress.running)
    }

    private fun assertNotNullTicket(ticket: InstallProgress.Ticket?): InstallProgress.Ticket {
        assertNotNull(ticket)
        return ticket!!
    }

    private companion object {
        const val FETCH_REQUEST = "req-1"

        /** Every answer to a request-owned fetch echoes its owner, so a client can drop a stale reply. */
        const val OWNER = ",\"request\":\"$FETCH_REQUEST\""
    }
}
