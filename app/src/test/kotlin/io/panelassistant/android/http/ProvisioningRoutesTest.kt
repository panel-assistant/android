package io.panelassistant.android.http

import io.panelassistant.android.device.profile.ProfileActivationPhase
import io.panelassistant.android.device.profile.ProfileOrigin
import io.panelassistant.android.device.profile.ProfileRef
import io.panelassistant.android.provisioning.ProvisioningActivationSnapshot
import io.panelassistant.android.provisioning.ProvisioningCoreIdentity
import io.panelassistant.android.provisioning.ProvisioningExecutor
import io.panelassistant.android.provisioning.ProvisioningImportance
import io.panelassistant.android.provisioning.ProvisioningItemStatus
import io.panelassistant.android.provisioning.ProvisioningPlan
import io.panelassistant.android.provisioning.ProvisioningPlanItem
import io.panelassistant.android.provisioning.ProvisioningPlanState
import io.panelassistant.android.provisioning.ProvisioningProfile
import io.panelassistant.android.provisioning.ProvisioningReadResult
import io.panelassistant.android.provisioning.ProvisioningReader
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvisioningRoutesTest {
    @Test
    fun stableExactActivationServesCanonicalJsonAndTerminalTextWithoutCaching() = testApplication {
        val reader = FakeReader()
        application {
            routing {
                route("/api/v1") {
                    provisioningRoutes(ProvisioningRouteDependencies(reader) { ACTIVE })
                }
            }
        }

        val jsonResponse = client.get("/api/v1/provisioning/plan")
        assertEquals(HttpStatusCode.OK, jsonResponse.status)
        assertEquals("no-store", jsonResponse.headers[HttpHeaders.CacheControl])
        val body = JSONObject(jsonResponse.bodyAsText())
        assertEquals(1, body.getInt("plan_schema"))
        assertEquals("0.9.4-rc1", body.getJSONObject("core").getString("version"))
        assertEquals(194, body.getJSONObject("core").getInt("version_code"))
        assertEquals("test.panel", body.getJSONObject("profile").getString("id"))
        assertEquals("Test panel", body.getJSONObject("profile").getString("display_name"))
        assertEquals(REVISION, body.getJSONObject("profile").getString("revision"))
        assertEquals("bundled", body.getJSONObject("profile").getString("origin"))
        assertEquals("active", body.getJSONObject("activation").getString("state"))
        assertEquals("attention", body.getString("state"))
        val item = body.getJSONArray("items").getJSONObject(0)
        assertEquals("access.helper", item.getString("id"))
        assertEquals("required", item.getString("importance"))
        assertEquals("manual", item.getString("status"))
        assertEquals("host", item.getString("executor"))
        assertEquals("compatible", item.getString("desired_state"))
        assertEquals("missing", item.getString("observed_state"))
        assertEquals("daemon_driver_without_helper", item.getString("reason_code"))
        assertFalse(jsonResponse.bodyAsText().contains("Install arbitrary"))

        val textResponse = client.get("/api/v1/provisioning/plan.txt")
        assertEquals(HttpStatusCode.OK, textResponse.status)
        assertEquals("no-store", textResponse.headers[HttpHeaders.CacheControl])
        assertTrue(textResponse.headers[HttpHeaders.ContentType].orEmpty().startsWith("text/plain"))
        assertTrue(textResponse.bodyAsText().startsWith("ha-paneld provisioning plan\n"))
        assertTrue(textResponse.bodyAsText().contains("Panel: Test panel"))
        assertTrue(textResponse.bodyAsText().isNotBlank())
        assertEquals(2, reader.reads)
    }

    @Test
    fun applyingOrMismatchedActivationReturnsRetryable503WithoutReadingPlan() = testApplication {
        val reader = FakeReader()
        var activation = ACTIVE.copy(phase = ProfileActivationPhase.APPLYING)
        application {
            routing {
                route("/api/v1") {
                    provisioningRoutes(ProvisioningRouteDependencies(reader) { activation })
                }
            }
        }

        val applying = client.get("/api/v1/provisioning/plan")
        assertEquals(HttpStatusCode.ServiceUnavailable, applying.status)
        assertEquals("1", applying.headers[HttpHeaders.RetryAfter])
        assertEquals("no-store", applying.headers[HttpHeaders.CacheControl])
        assertEquals("profile_activation_unstable", JSONObject(applying.bodyAsText()).getString("error"))
        assertEquals(0, reader.reads)

        activation = ACTIVE.copy(activeRef = ProfileRef("other", REVISION))
        val mismatched = client.get("/api/v1/provisioning/plan.txt")
        assertEquals(HttpStatusCode.ServiceUnavailable, mismatched.status)
        assertEquals("1", mismatched.headers[HttpHeaders.RetryAfter])
        assertEquals("provisioning plan unavailable\n", mismatched.bodyAsText())
        assertEquals(0, reader.reads)
    }

    @Test
    fun unavailableObservationSnapshotIsAlsoRetryable() = testApplication {
        val reader = FakeReader(ProvisioningReadResult.Unavailable("observation_snapshot_unavailable"))
        application {
            routing {
                route("/api/v1") {
                    provisioningRoutes(ProvisioningRouteDependencies(reader) { ACTIVE })
                }
            }
        }

        val response = client.get("/api/v1/provisioning/plan")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals("1", response.headers[HttpHeaders.RetryAfter])
        assertEquals(
            "observation_snapshot_unavailable",
            JSONObject(response.bodyAsText()).getString("error"),
        )
        assertEquals(1, reader.reads)
    }

    @Test
    fun activationTransitionDuringObservationReturns503InsteadOfAStalePlan() = testApplication {
        var activation = ACTIVE
        val reader = FakeReader(onRead = {
            activation = ACTIVE.copy(phase = ProfileActivationPhase.PENDING)
        })
        application {
            routing {
                route("/api/v1") {
                    provisioningRoutes(ProvisioningRouteDependencies(reader) { activation })
                }
            }
        }

        val response = client.get("/api/v1/provisioning/plan")

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals("1", response.headers[HttpHeaders.RetryAfter])
        assertEquals(
            "profile_activation_unstable",
            JSONObject(response.bodyAsText()).getString("error"),
        )
        assertEquals(1, reader.reads)
    }

    @Test
    fun readerCancellationBypassesRetryableMapping() = testApplication {
        val reader = object : ProvisioningReader {
            override val expectedProfileRef = REF

            override suspend fun plan(
                activation: ProvisioningActivationSnapshot,
                forceRefresh: Boolean,
            ): ProvisioningReadResult {
                throw CancellationException("reader cancelled")
            }
        }
        application {
            routing {
                route("/api/v1") {
                    provisioningRoutes(ProvisioningRouteDependencies(reader) { ACTIVE })
                }
            }
        }

        val response = client.get("/api/v1/provisioning/plan")

        // Ktor 3.5's test host turns a handler CancellationException into its generic 500 response
        // instead of rethrowing it through the in-memory client. It must still bypass this route's
        // retryable-error mapping: no 503 body or Retry-After header may be synthesized.
        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertEquals(null, response.headers[HttpHeaders.RetryAfter])
        assertFalse(response.bodyAsText().contains("provisioning_plan_unavailable"))
    }

    @Test
    fun ordinaryReaderFailureRemainsRetryable() = testApplication {
        val reader = object : ProvisioningReader {
            override val expectedProfileRef = REF

            override suspend fun plan(
                activation: ProvisioningActivationSnapshot,
                forceRefresh: Boolean,
            ): ProvisioningReadResult {
                throw IllegalStateException("reader unavailable")
            }
        }
        application {
            routing {
                route("/api/v1") {
                    provisioningRoutes(ProvisioningRouteDependencies(reader) { ACTIVE })
                }
            }
        }

        val response = client.get("/api/v1/provisioning/plan")

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals(
            "provisioning_plan_unavailable",
            JSONObject(response.bodyAsText()).getString("error"),
        )
    }

    private class FakeReader(
        private val result: ProvisioningReadResult = ProvisioningReadResult.Ready(PLAN),
        private val onRead: () -> Unit = {},
    ) : ProvisioningReader {
        override val expectedProfileRef = REF
        var reads = 0

        override suspend fun plan(
            activation: ProvisioningActivationSnapshot,
            forceRefresh: Boolean,
        ): ProvisioningReadResult {
            reads++
            onRead()
            return result
        }
    }

    private companion object {
        const val REVISION = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val REF = ProfileRef("test.panel", REVISION)
        val ACTIVE = ProvisioningActivationSnapshot(ProfileActivationPhase.ACTIVE, REF, 4)
        val PROFILE = ProvisioningProfile(
            ref = REF,
            displayName = "Test panel",
            origin = ProfileOrigin.BUNDLED,
            contentVersion = "2.0.0",
            helperImportance = ProvisioningImportance.REQUIRED,
        )
        val PLAN = ProvisioningPlan(
            core = ProvisioningCoreIdentity("0.9.4-rc1", 194),
            profile = PROFILE,
            activation = ACTIVE,
            state = ProvisioningPlanState.ATTENTION,
            items = listOf(
                ProvisioningPlanItem(
                    id = "access.helper",
                    importance = ProvisioningImportance.REQUIRED,
                    status = ProvisioningItemStatus.MANUAL,
                    executor = ProvisioningExecutor.HOST,
                    desiredState = "compatible",
                    observedState = "missing",
                    reasonCode = "daemon_driver_without_helper",
                ),
            ),
        )
    }
}
