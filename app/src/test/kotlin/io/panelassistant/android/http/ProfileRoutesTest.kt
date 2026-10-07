package io.panelassistant.android.http

import io.panelassistant.android.device.profile.DeviceFacts
import io.panelassistant.android.device.profile.PassiveProfileConfidence
import io.panelassistant.android.device.profile.PassiveProfileDraft
import io.panelassistant.android.device.profile.PassiveProfileObservation
import io.panelassistant.android.device.profile.PassiveProfileReport
import io.panelassistant.android.device.profile.ProfileActivationPhase
import io.panelassistant.android.device.profile.ProfileActivationState
import io.panelassistant.android.device.profile.ProfileAdmin
import io.panelassistant.android.device.profile.ProfileDiff
import io.panelassistant.android.device.profile.ProfileDriverDescriptor
import io.panelassistant.android.device.profile.ProfileDriverKind
import io.panelassistant.android.device.profile.ProfileHelperAuthorityDemand
import io.panelassistant.android.device.profile.ProfileFieldDescriptor
import io.panelassistant.android.device.profile.ProfileIssue
import io.panelassistant.android.device.profile.ProfileIssueSeverity
import io.panelassistant.android.device.profile.ProfileLink
import io.panelassistant.android.device.profile.ProfileMutation
import io.panelassistant.android.device.profile.ProfileOrigin
import io.panelassistant.android.device.profile.ProfilePresentation
import io.panelassistant.android.device.profile.ProfilePreview
import io.panelassistant.android.device.profile.ProfileRef
import io.panelassistant.android.device.profile.ProfileRisk
import io.panelassistant.android.device.profile.ProfileSchemaDescriptor
import io.panelassistant.android.device.profile.ProfileSelection
import io.panelassistant.android.device.profile.ProfileStatus
import io.panelassistant.android.device.profile.ProfileSummary
import io.panelassistant.android.device.profile.ProfileSoc
import io.panelassistant.android.device.profile.ProfileCpuCoreCluster
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class ProfileRoutesTest {
    @Test
    fun presentationMetadataIsAdditiveBoundedAndUnknownIssuesRemainRaw() = testApplication {
        val admin = FakeProfileAdmin()
        application { routing { route("/api/v1") { profileRoutes(ProfileRouteDependencies(admin)) } } }

        val contentTypeError = JSONObject(
            client.post("/api/v1/profiles/preview") {
                contentType(ContentType.Application.Json)
                setBody("{}")
            }.bodyAsText(),
        )
        assertEquals("yaml-content-type-required", contentTypeError.getString("error"))
        assertEquals("yaml-content-type-required", contentTypeError.getString("presentation_code"))
        assertEquals(0, contentTypeError.getJSONObject("presentation_params").length())

        val preview = JSONObject(
            client.post("/api/v1/profiles/preview") {
                contentType(ContentType.parse("application/yaml"))
                setBody("presentation-test")
            }.bodyAsText(),
        )
        val known = preview.getJSONArray("issues").getJSONObject(0)
        assertEquals("Original compatibility prose.", known.getString("message"))
        assertEquals("unknown-value", known.getString("presentation_code"))
        assertEquals("opaque", known.getJSONObject("presentation_params").getString("value"))
        val raw = preview.getJSONArray("issues").getJSONObject(1)
        assertEquals("Arbitrary failure remains raw.", raw.getString("message"))
        assertFalse(raw.has("presentation_code"))
        assertFalse(raw.has("presentation_params"))
    }

    @Test
    fun presentationContractRejectsUnknownCodesMissingOrExtraParametersAndOversizedValues() {
        assertTrue(runCatching { ProfilePresentation("Not Stable") }.isFailure)
        assertTrue(runCatching { ProfilePresentation("unknown-value") }.isFailure)
        assertTrue(runCatching { ProfilePresentation("invalid-json", mapOf("value" to "extra")) }.isFailure)
        assertTrue(runCatching { ProfilePresentation("unknown-value", mapOf("value" to "x".repeat(513))) }.isFailure)
    }

    @Test
    fun plainTextCompatibilityErrorsExposeAdditivePresentationHeaders() = testApplication {
        application {
            routing {
                route("/api/v1") { profileRoutes(ProfileRouteDependencies(FakeProfileAdmin())) }
            }
        }

        val response = client.get("/api/v1/profiles/template")
        assertEquals(HttpStatusCode.NotImplemented, response.status)
        assertEquals("profile template unavailable\n", response.bodyAsText())
        assertEquals("profile-template-unavailable", response.headers["X-Profile-Presentation-Code"])
        assertEquals("{}", response.headers["X-Profile-Presentation-Params"])
    }

    @Test
    fun catalogSchemaAndDriversExposeOnlyStableAuthoringMetadata() = testApplication {
        val admin = FakeProfileAdmin()
        application { routing { route("/api/v1") { profileRoutes(ProfileRouteDependencies(admin)) } } }

        val response = client.get("/api/v1/profiles")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        val body = JSONObject(response.bodyAsText())
        assertEquals(3L, body.getLong("catalog_revision"))
        assertEquals(2, body.getJSONArray("profiles").length())
        val local = body.getJSONArray("profiles").getJSONObject(1)
        assertTrue(local.getBoolean("compatible"))
        assertEquals(0, local.getJSONArray("issues").length())
        val bundled = body.getJSONArray("profiles").getJSONObject(0)
        assertTrue(bundled.getBoolean("last_known_good"))
        assertEquals("0.1.0", bundled.getString("content_version"))
        assertEquals("draft", bundled.getString("maturity"))
        assertEquals("Test SoC", local.getJSONObject("soc").getString("model"))
        assertEquals("Arm Cortex-A55", local.getJSONObject("soc").getJSONArray("cpu_cores").getJSONObject(0).getString("architecture"))
        assertEquals("Product page", local.getJSONArray("links").getJSONObject(0).getString("label"))
        assertFalse(response.bodyAsText().contains("consent", ignoreCase = true))
        assertFalse(response.bodyAsText().contains("readiness", ignoreCase = true))

        val schema = JSONObject(client.get("/api/v1/profiles/schema").bodyAsText())
        assertEquals(1, schema.getInt("schema"))
        assertEquals(64, schema.getInt("max_bytes"))
        assertEquals("identity.id", schema.getJSONArray("fields").getJSONObject(0).getString("path"))

        val drivers = JSONObject(client.get("/api/v1/profiles/drivers").bodyAsText()).getJSONArray("drivers")
        assertEquals("relay", drivers.getJSONObject(0).getString("kind"))
        assertTrue(drivers.getJSONObject(0).getBoolean("privileged"))
    }

    @Test
    fun yamlRoutesEnforceContentTypeBoundsPreviewTokenAndExactRevisionExport() = testApplication {
        val admin = FakeProfileAdmin()
        application { routing { route("/api/v1") { profileRoutes(ProfileRouteDependencies(admin)) } } }

        assertEquals(
            HttpStatusCode.UnsupportedMediaType,
            client.post("/api/v1/profiles/preview") { contentType(ContentType.Application.Json); setBody("{}") }.status,
        )
        assertEquals(
            HttpStatusCode.PayloadTooLarge,
            client.post("/api/v1/profiles/preview") {
                contentType(ContentType.parse("application/yaml"))
                setBody("x".repeat(65))
            }.status,
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            client.post("/api/v1/profiles/preview") {
                contentType(ContentType.parse("application/yaml"))
                setBody(byteArrayOf(0xc3.toByte(), 0x28))
            }.status,
        )

        val yaml = "schema: 2\nid: imported.test\n"
        val previewResponse = client.post("/api/v1/profiles/preview") {
            contentType(ContentType.parse("application/yaml"))
            setBody(yaml)
        }
        assertEquals(HttpStatusCode.OK, previewResponse.status)
        val preview = JSONObject(previewResponse.bodyAsText())
        assertEquals("preview-token", preview.getString("preview_token"))
        assertTrue(preview.getBoolean("compatible"))
        assertEquals("identity.id", preview.getJSONArray("diff_from_active").getJSONObject(0).getString("path"))

        assertEquals(
            HttpStatusCode.BadRequest,
            client.post("/api/v1/profiles/import") {
                contentType(ContentType.parse("application/yaml")); setBody(yaml)
            }.status,
        )
        val imported = client.post("/api/v1/profiles/import") {
            contentType(ContentType.parse("application/yaml"))
            header("X-Profile-Preview-Token", "preview-token")
            setBody(yaml)
        }
        assertEquals(HttpStatusCode.OK, imported.status)
        val importedBody = JSONObject(imported.bodyAsText())
        val importedRef = importedBody.getJSONObject("imported_ref")
        assertEquals(sha256(yaml), importedRef.getString("revision"))
        assertEquals("profile-imported", importedBody.getString("presentation_code"))
        assertEquals("imported.test", importedBody.getJSONObject("presentation_params").getString("display_name"))
        assertEquals(yaml, admin.importedYaml)

        val exported = client.get("/api/v1/profiles/${admin.bundled.ref.id}/revisions/${admin.bundled.ref.revision}")
        assertEquals(HttpStatusCode.OK, exported.status)
        assertEquals("application/yaml", exported.headers[HttpHeaders.ContentType])
        assertTrue(exported.headers[HttpHeaders.ContentDisposition].orEmpty().endsWith(".yaml\""))
        assertEquals(admin.bundledYaml, exported.bodyAsText())
        assertEquals(
            HttpStatusCode.BadRequest,
            client.get("/api/v1/profiles/bad%2Fid/revisions/not-a-hash").status,
        )
    }

    @Test
    fun incompatiblePreviewProjectionSuppressesNavigationLinks() = testApplication {
        val admin = FakeProfileAdmin().apply { previewCompatible = false }
        application { routing { route("/api/v1") { profileRoutes(ProfileRouteDependencies(admin)) } } }

        val response = client.post("/api/v1/profiles/preview") {
            contentType(ContentType.parse("application/yaml"))
            setBody("schema: 2\nid: invalid.test\n")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val preview = JSONObject(response.bodyAsText())
        assertFalse(preview.getBoolean("compatible"))
        assertFalse(preview.getJSONObject("summary").getBoolean("compatible"))
        assertEquals(0, preview.getJSONObject("summary").getJSONArray("links").length())
    }

    @Test
    fun activationRollbackAndDeleteRequireConfirmationAndCatalogCompareAndSet() = testApplication {
        val admin = FakeProfileAdmin()
        var restarts = 0
        application {
            routing {
                route("/api/v1") {
                    profileRoutes(ProfileRouteDependencies(admin, requestRestart = { restarts++; true }))
                }
            }
        }

        val selection = """{"id":"${admin.local.ref.id}","revision":"${admin.local.ref.revision}","expected_catalog_revision":3}"""
        assertEquals(HttpStatusCode.Conflict, postJson("/api/v1/profiles/activate", selection).status)
        assertEquals(0, admin.selections.size)

        val stale = """{"id":"${admin.local.ref.id}","revision":"${admin.local.ref.revision}","expected_catalog_revision":2,"confirm":true}"""
        assertEquals(HttpStatusCode.Conflict, postJson("/api/v1/profiles/activate", stale).status)
        assertEquals(0, restarts)

        val activate = selection.dropLast(1) + ",\"confirm\":true}"
        val accepted = postJson("/api/v1/profiles/activate", activate)
        assertEquals(HttpStatusCode.Accepted, accepted.status)
        assertEquals(1, restarts)
        assertEquals(ProfileSelection.Pinned(admin.local.ref), admin.selections.last())

        val automatic = """{"auto":true,"expected_catalog_revision":3,"confirm":true}"""
        assertEquals(HttpStatusCode.Accepted, postJson("/api/v1/profiles/select", automatic).status)
        assertEquals(ProfileSelection.Auto, admin.selections.last())
        assertEquals(2, restarts)

        val wrongRollback = """{"expected_catalog_revision":2,"confirm":true}"""
        assertEquals(HttpStatusCode.Conflict, postJson("/api/v1/profiles/rollback", wrongRollback).status)
        val rollback = """{"expected_catalog_revision":3,"confirm":true}"""
        assertEquals(HttpStatusCode.Accepted, postJson("/api/v1/profiles/rollback", rollback).status)
        assertEquals(ProfileSelection.Pinned(admin.bundled.ref), admin.selections.last())

        val deleteWithoutConfirmation = """{"id":"${admin.local.ref.id}","revision":"${admin.local.ref.revision}","expected_catalog_revision":3}"""
        assertEquals(HttpStatusCode.Conflict, postJson("/api/v1/profiles/delete", deleteWithoutConfirmation).status)
        val delete = deleteWithoutConfirmation.dropLast(1) + ",\"confirm\":true}"
        assertEquals(HttpStatusCode.OK, postJson("/api/v1/profiles/delete", delete).status)
        assertEquals(admin.local.ref, admin.deleted)
    }

    @Test
    fun selectActivateAndDeleteAcceptFlatOrNestedRefRejectBothOrNeitherAndRollbackIgnoresAnySuppliedRef() = testApplication {
        val admin = FakeProfileAdmin()
        application { routing { route("/api/v1") { profileRoutes(ProfileRouteDependencies(admin)) } } }
        val id = admin.local.ref.id
        val revision = admin.local.ref.revision

        // flat form: works on /select
        val flat = """{"id":"$id","revision":"$revision","expected_catalog_revision":3,"confirm":true}"""
        assertEquals(HttpStatusCode.Accepted, postJson("/api/v1/profiles/select", flat).status)
        assertEquals(ProfileSelection.Pinned(admin.local.ref), admin.selections.last())

        // nested form: works identically on /activate (the alias)
        val nested = """{"ref":{"id":"$id","revision":"$revision"},"expected_catalog_revision":3,"confirm":true}"""
        assertEquals(HttpStatusCode.Accepted, postJson("/api/v1/profiles/activate", nested).status)
        assertEquals(ProfileSelection.Pinned(admin.local.ref), admin.selections.last())

        // both forms at once: rejected distinctly from a malformed ref
        val both = """{"id":"$id","revision":"$revision","ref":{"id":"$id","revision":"$revision"},"expected_catalog_revision":3,"confirm":true}"""
        val bothResponse = postJson("/api/v1/profiles/select", both)
        assertEquals(HttpStatusCode.BadRequest, bothResponse.status)
        assertEquals("profile-ref-both-forms-supplied", JSONObject(bothResponse.bodyAsText()).getString("error"))

        // neither form: unchanged pre-existing rejection
        val neither = """{"expected_catalog_revision":3,"confirm":true}"""
        val neitherResponse = postJson("/api/v1/profiles/select", neither)
        assertEquals(HttpStatusCode.BadRequest, neitherResponse.status)
        assertEquals("invalid-profile-ref", JSONObject(neitherResponse.bodyAsText()).getString("error"))

        // nested with a wrong revision: rejected as a malformed ref, not silently accepted
        val nestedWrongRevision = """{"ref":{"id":"$id","revision":"not-a-hash"},"expected_catalog_revision":3,"confirm":true}"""
        val wrongRevisionResponse = postJson("/api/v1/profiles/select", nestedWrongRevision)
        assertEquals(HttpStatusCode.BadRequest, wrongRevisionResponse.status)
        assertEquals("invalid-profile-ref", JSONObject(wrongRevisionResponse.bodyAsText()).getString("error"))

        val selectionsBeforeDelete = admin.selections.size

        // /delete: same five cases
        val deleteFlat = """{"id":"$id","revision":"$revision","expected_catalog_revision":3,"confirm":true}"""
        assertEquals(HttpStatusCode.OK, postJson("/api/v1/profiles/delete", deleteFlat).status)
        assertEquals(admin.local.ref, admin.deleted)

        admin.deleted = null
        val deleteNested = """{"ref":{"id":"$id","revision":"$revision"},"expected_catalog_revision":3,"confirm":true}"""
        assertEquals(HttpStatusCode.OK, postJson("/api/v1/profiles/delete", deleteNested).status)
        assertEquals(admin.local.ref, admin.deleted)

        val deleteBoth = """{"id":"$id","revision":"$revision","ref":{"id":"$id","revision":"$revision"},"expected_catalog_revision":3,"confirm":true}"""
        val deleteBothResponse = postJson("/api/v1/profiles/delete", deleteBoth)
        assertEquals(HttpStatusCode.BadRequest, deleteBothResponse.status)
        assertEquals("profile-ref-both-forms-supplied", JSONObject(deleteBothResponse.bodyAsText()).getString("error"))

        val deleteNeither = """{"expected_catalog_revision":3,"confirm":true}"""
        val deleteNeitherResponse = postJson("/api/v1/profiles/delete", deleteNeither)
        assertEquals(HttpStatusCode.BadRequest, deleteNeitherResponse.status)
        assertEquals("invalid-delete-request", JSONObject(deleteNeitherResponse.bodyAsText()).getString("error"))

        val deleteWrongRevision = """{"ref":{"id":"$id","revision":"not-a-hash"},"expected_catalog_revision":3,"confirm":true}"""
        val deleteWrongRevisionResponse = postJson("/api/v1/profiles/delete", deleteWrongRevision)
        assertEquals(HttpStatusCode.BadRequest, deleteWrongRevisionResponse.status)
        assertEquals("invalid-delete-request", JSONObject(deleteWrongRevisionResponse.bodyAsText()).getString("error"))

        assertEquals(selectionsBeforeDelete, admin.selections.size)

        // /rollback is audited for the same asymmetry and found not applicable: it never reads a
        // client-supplied ref (any id/revision/ref fields are ignored) and always targets the
        // server-recorded last-known-good.
        val rollbackWithIgnoredRef = """{"id":"$id","revision":"$revision","ref":{"id":"$id","revision":"$revision"},"expected_catalog_revision":3,"confirm":true}"""
        assertEquals(HttpStatusCode.Accepted, postJson("/api/v1/profiles/rollback", rollbackWithIgnoredRef).status)
        assertEquals(ProfileSelection.Pinned(admin.bundled.ref), admin.selections.last())
    }

    @Test
    fun activationApprovalSummaryNamesPinnedAndRollbackTargets() = testApplication {
        val admin = FakeProfileAdmin()
        val summaries = mutableListOf<String>()
        application {
            routing {
                route("/api/v1") {
                    profileRoutes(ProfileRouteDependencies(admin, authorize = { _, _, _, summary ->
                        summaries += summary
                        true
                    }))
                }
            }
        }
        val select = """{"id":"${admin.local.ref.id}","revision":"${admin.local.ref.revision}","expected_catalog_revision":3,"confirm":true}"""
        assertEquals(HttpStatusCode.Accepted, postJson("/api/v1/profiles/select", select).status)
        val rollback = """{"expected_catalog_revision":3,"confirm":true}"""
        assertEquals(HttpStatusCode.Accepted, postJson("/api/v1/profiles/rollback", rollback).status)

        assertTrue(summaries[0].contains(admin.local.ref.id))
        assertTrue(summaries[0].contains(admin.local.ref.revision.take(16)))
        assertTrue(summaries[1].contains(admin.bundled.ref.id))
        assertTrue(summaries[1].contains(admin.bundled.ref.revision.take(16)))
    }

    @Test
    fun activationIsAbortedWhenDestructiveLaneIsBusyOrRestartSchedulingFails() = testApplication {
        val admin = FakeProfileAdmin()
        var aborts = 0
        var restartAllowed = false
        var scheduleAccepted = true
        var abortPersisted = true
        application {
            routing {
                route("/api/v1") {
                    profileRoutes(
                        ProfileRouteDependencies(
                            admin = admin,
                            requestRestart = { scheduleAccepted },
                            restartAllowed = { restartAllowed },
                            abortPendingRestart = { aborts++; abortPersisted },
                        ),
                    )
                }
            }
        }
        val request = """{"id":"${admin.local.ref.id}","revision":"${admin.local.ref.revision}","expected_catalog_revision":3,"confirm":true}"""

        val busy = postJson("/api/v1/profiles/activate", request)
        assertEquals(HttpStatusCode.ServiceUnavailable, busy.status)
        assertEquals("destructive-operation-in-progress", JSONObject(busy.bodyAsText()).getString("error"))
        assertEquals(1, aborts)

        restartAllowed = true
        scheduleAccepted = false
        val unavailable = postJson("/api/v1/profiles/activate", request)
        assertEquals(HttpStatusCode.ServiceUnavailable, unavailable.status)
        assertEquals("profile-restart-unavailable", JSONObject(unavailable.bodyAsText()).getString("error"))
        assertEquals(2, aborts)

        abortPersisted = false
        val latent = postJson("/api/v1/profiles/activate", request)
        assertEquals(HttpStatusCode.ServiceUnavailable, latent.status)
        val latentBody = JSONObject(latent.bodyAsText())
        assertEquals("profile-activation-abort-persist-failed", latentBody.getString("error"))
        assertTrue(latentBody.getBoolean("activation_pending"))
        assertTrue(latentBody.getString("message").contains("remains pending"))
        assertEquals(3, aborts)
    }

    @Test fun restartRejectionDistinguishesDurableAbortFailureForRestoreCallers() {
        val recovered = rejectFailedProfileRestart(
            restartAllowed = true,
            requestRestart = { false },
            abortPendingRestart = { true },
        )
        assertEquals("profile-restart-unavailable", recovered?.error)
        assertTrue(recovered!!.abortPersisted)

        val latent = rejectFailedProfileRestart(
            restartAllowed = true,
            requestRestart = { false },
            abortPendingRestart = { false },
        )
        assertEquals("profile-activation-abort-persist-failed", latent?.error)
        assertFalse(latent!!.abortPersisted)
        assertTrue(latent.message.contains("remains pending"))
    }

    @Test
    fun passiveDraftAndProbeUseOnlyInjectedReadOnlyProviderAndSanitizedProjection() = testApplication {
        val admin = FakeProfileAdmin()
        val report = PassiveProfileReport(
            generatedAtEpochMs = 123,
            facts = DeviceFacts("model", "device", "version"),
            observations = listOf(
                PassiveProfileObservation("display.width", "1280", "android-display", PassiveProfileConfidence.OBSERVED),
            ),
        )
        var probes = 0
        application {
            routing {
                route("/api/v1") {
                    profileRoutes(
                        ProfileRouteDependencies(
                            admin,
                            readOnly = ProfileRouteReadOnlyProviders(
                                template = { "schema: 2\n" },
                                deviceDraft = { PassiveProfileDraft("schema: 2\n# TODO\n", report, emptyList()) },
                                latestReport = { report },
                                probe = { probes++; report },
                            ),
                        ),
                    )
                }
            }
        }

        assertEquals("schema: 2\n", client.get("/api/v1/profiles/template").bodyAsText())
        assertEquals("schema: 2\n# TODO\n", client.get("/api/v1/profiles/device-draft").bodyAsText())
        val latest = JSONObject(client.get("/api/v1/profiles/report").bodyAsText())
        assertEquals("observed", latest.getJSONArray("items").getJSONObject(0).getString("status"))
        val probe = client.post("/api/v1/profiles/probe") {
            contentType(ContentType.parse("application/yaml")); setBody("schema: 2\n")
        }
        assertEquals(HttpStatusCode.OK, probe.status)
        assertNotNull(JSONObject(probe.bodyAsText()).getJSONObject("report"))
        assertEquals(1, probes)
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.postJson(path: String, body: String) =
        client.post(path) { contentType(ContentType.Application.Json); setBody(body) }

    private class FakeProfileAdmin : ProfileAdmin {
        val bundled = summary("generic", "b".repeat(64), ProfileOrigin.BUNDLED, active = true)
        var local = summary("local.test", "a".repeat(64), ProfileOrigin.IMPORTED, selected = true)
        val bundledYaml = "schema: 2\nid: generic\n"
        var importedYaml: String? = null
        val selections = mutableListOf<ProfileSelection>()
        var deleted: ProfileRef? = null
        var previewCompatible = true
        private var profiles = mutableListOf(bundled, local)

        override fun schema() = ProfileSchemaDescriptor(
            schema = 1,
            maxBytes = 64,
            fields = listOf(ProfileFieldDescriptor("identity.id", "string", required = true, description = "Stable id")),
        )

        override fun drivers() = listOf(
            ProfileDriverDescriptor(
                "relay.sysfs",
                ProfileDriverKind.RELAY,
                "Compiled relay driver",
                privileged = true,
                helperDemand = ProfileHelperAuthorityDemand.NONE,
            ),
        )

        override fun status() = ProfileStatus(
            catalogRevision = 3,
            selection = ProfileSelection.Pinned(local.ref),
            active = bundled,
            activation = ProfileActivationState(
                phase = ProfileActivationPhase.ACTIVE,
                generation = 8,
                previous = ProfileSelection.Pinned(bundled.ref),
                desired = ProfileSelection.Pinned(local.ref),
            ),
            lastKnownGood = ProfileSelection.Pinned(bundled.ref),
        )

        override fun list(): List<ProfileSummary> = profiles

        override fun preview(rawYaml: String) = ProfilePreview(
            previewToken = "preview-token",
            contentSha256 = sha256(rawYaml),
            expiresAtEpochMs = 999,
            summary = local.copy(
                ref = ProfileRef("imported.test", sha256(rawYaml)),
                compatible = previewCompatible,
            ),
            issues = if (rawYaml == "presentation-test") {
                listOf(
                    ProfileIssue(
                        ProfileIssueSeverity.ERROR,
                        "field",
                        "Original compatibility prose.",
                        ProfilePresentation("unknown-value", mapOf("value" to "opaque")),
                    ),
                    ProfileIssue(ProfileIssueSeverity.ERROR, "field", "Arbitrary failure remains raw."),
                )
            } else emptyList(),
            diffFromActive = listOf(ProfileDiff("identity.id", "generic", "imported.test")),
            compatible = previewCompatible,
        )

        override fun importProfile(rawYaml: String, previewToken: String): ProfileMutation {
            if (previewToken != "preview-token") return rejected(ProfileIssue("preview_token", "stale preview token"))
            importedYaml = rawYaml
            local = local.copy(ref = ProfileRef("imported.test", sha256(rawYaml)))
            profiles = mutableListOf(bundled, local)
            return ProfileMutation.Success(
                status(),
                restartRequired = false,
                message = "saved",
                presentation = ProfilePresentation(
                    "profile-imported",
                    mapOf("display_name" to "imported.test", "version" to "1.0.0"),
                ),
            )
        }

        override fun exportProfile(ref: ProfileRef): String? = if (ref == bundled.ref) bundledYaml else null

        override fun select(selection: ProfileSelection, expectedCatalogRevision: Long): ProfileMutation {
            if (expectedCatalogRevision != 3L) return rejected(ProfileIssue("catalog_revision", "stale catalog revision"))
            selections += selection
            return ProfileMutation.Success(status(), restartRequired = true, message = "restart scheduled")
        }

        override fun rollbackToLastKnownGood(expectedCatalogRevision: Long): ProfileMutation {
            val currentStatus = status()
            val target = currentStatus.lastKnownGood
                ?: return ProfileMutation.Rejected(
                    currentStatus,
                    listOf(ProfileIssue(ProfileIssueSeverity.ERROR, "rollback", "No last-known-good profile.")),
                )
            return select(target, expectedCatalogRevision)
        }

        override fun deleteProfile(ref: ProfileRef, expectedCatalogRevision: Long): ProfileMutation {
            if (expectedCatalogRevision != 3L) return rejected(ProfileIssue("catalog_revision", "stale catalog revision"))
            deleted = ref
            return ProfileMutation.Success(status(), restartRequired = false, message = "deleted")
        }

        private fun rejected(issue: ProfileIssue) = ProfileMutation.Rejected(status(), listOf(issue))

        private fun ProfileIssue(path: String, message: String) =
            ProfileIssue(ProfileIssueSeverity.ERROR, path, message)

        private fun summary(
            id: String,
            revision: String,
            origin: ProfileOrigin,
            active: Boolean = false,
            selected: Boolean = false,
        ) = ProfileSummary(
            ref = ProfileRef(id, revision),
            displayName = id,
            origin = origin,
            schema = 1,
            minCoreVersion = null,
            matchesThisDevice = true,
            active = active,
            selected = selected,
            risks = if (origin == ProfileOrigin.IMPORTED) setOf(ProfileRisk.ROOT_PATHS) else emptySet(),
            contentVersion = "0.1.0",
            soc = ProfileSoc("Test SoC", 2020, listOf(ProfileCpuCoreCluster("Arm Cortex-A55", 4)))
                .takeIf { origin == ProfileOrigin.IMPORTED },
            links = if (origin == ProfileOrigin.IMPORTED) {
                listOf(ProfileLink("Product page", "https://vendor.example/panel"))
            } else {
                emptyList()
            },
        )
    }

    companion object {
        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
