package io.panelassistant.android.http

import io.panelassistant.android.control.HandBackHomeController
import io.panelassistant.android.control.HandBackHomePolicy
import io.panelassistant.android.control.TameOwnedMarker
import io.panelassistant.android.control.TameOwnedMarkers
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The refusal codes Panel Assistant turns into the owner's message when removal cannot hand the home
 * screen back. A recorded original launcher that is gone needs its own code naming it, because the only
 * fix is reinstalling that launcher; a panel with no launcher at all keeps the general code.
 */
class HandBackHomeRoutesTest {

    private val own = "io.github.maxlyth.hapaneld"
    private val vendor = "com.smartos.xinch.launcher"

    /** A real controller over a panel whose vendor launcher is [vendorEnabled] or owned and about to be re-enabled. */
    private fun controller(
        homes: List<String>,
        vendorEnabled: Boolean,
        previousHome: String?,
        mutations: MutableList<String>,
    ) = HandBackHomeController(
        ownedMarkers = {
            TameOwnedMarkers.Ready(if (vendorEnabled) emptyMap() else mapOf(vendor to TameOwnedMarker(vendor, "default")))
        },
        packageStates = { pkgs ->
            pkgs.associateWith { HandBackHomePolicy.PackageState(present = true, disabledByUser = it == vendor && !vendorEnabled) }
        },
        homeCandidates = {
            homes.map { HandBackHomePolicy.HomeCandidate(it, "$it/.Launcher", enabled = it == own || vendorEnabled) }
        },
        clearDesiredState = { mutations += "clear"; true },
        restoreOwned = { mutations += "restore" },
        enable = { mutations += "enable:$it"; true },
        setHome = { mutations += "set-home:$it"; true },
        observeHome = { "$own/.Launcher" },
        ownPackage = own,
        previousHomeComponent = { previousHome },
    )

    private fun handBack(controller: HandBackHomeController): Pair<HttpStatusCode, String> {
        var answer: Pair<HttpStatusCode, String>? = null
        testApplication {
            application {
                routing {
                    handBackHomeRoutes(
                        HandBackHomeRouteDependencies(
                            profileKnownPackages = { emptySet() },
                            handBack = controller::handBack,
                            recordTamed = { HandBackHomePolicy.RecordOutcome.UNKNOWN },
                        ),
                    )
                }
            }
            val response = client.post("/api/v1/hand-back-home")
            answer = response.status to response.bodyAsText()
        }
        return answer!!
    }

    @Test
    fun `a missing recorded original home is refused by name even with a vendor launcher to hand to`() {
        for (vendorEnabled in listOf(true, false)) {
            val mutations = mutableListOf<String>()
            val (status, body) = handBack(controller(listOf(own, vendor), vendorEnabled, "com.missing.launcher/.Home", mutations))

            assertEquals(HttpStatusCode.Conflict, status)
            assertEquals(
                """{"ok":false,"error":"original-home-missing","original_home":"com.missing.launcher/.Home"}""",
                body,
            )
            assertTrue("nothing may be mutated on a refusal: $mutations", mutations.isEmpty())
        }
    }

    @Test
    fun `a panel with no recorded original and no other launcher keeps no-replacement-home`() {
        val mutations = mutableListOf<String>()
        val (status, body) = handBack(controller(listOf(own), vendorEnabled = true, previousHome = null, mutations))

        assertEquals(HttpStatusCode.Conflict, status)
        assertEquals("""{"ok":false,"error":"no-replacement-home"}""", body)
        assertTrue("nothing may be mutated on a refusal: $mutations", mutations.isEmpty())
    }
}
