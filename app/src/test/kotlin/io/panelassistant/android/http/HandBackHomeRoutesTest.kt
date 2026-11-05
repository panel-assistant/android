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
import org.junit.Assert.assertFalse
import org.json.JSONObject
import io.panelassistant.android.ManageTable
import io.panelassistant.android.ManageRequest
import io.panelassistant.android.CommandOrigin
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
        restorationSucceeds: Boolean = false,
        setHomeSucceeds: Boolean = true,
        packageStateKnown: Boolean = true,
        clearSucceeds: Boolean = true,
        homeReadbackMatches: Boolean = true,
    ): HandBackHomeController {
        var enabled = vendorEnabled
        var home = "$own/.Launcher"
        return HandBackHomeController(
            ownedMarkers = {
                TameOwnedMarkers.Ready(if (vendorEnabled) emptyMap() else mapOf(vendor to TameOwnedMarker(vendor, "default")))
            },
            packageStates = { pkgs ->
                if (!packageStateKnown) error("inventory unavailable")
                pkgs.associateWith { HandBackHomePolicy.PackageState(present = true, disabledByUser = it == vendor && !enabled) }
            },
            homeCandidates = {
                homes.map { HandBackHomePolicy.HomeCandidate(it, "$it/.Launcher", enabled = it == own || vendorEnabled) }
            },
            clearDesiredState = { mutations += "clear"; clearSucceeds },
            restoreOwned = { mutations += "restore"; if (restorationSucceeds) enabled = true },
            enable = { mutations += "enable:$it"; true },
            setHome = { mutations += "set-home:$it"; if (setHomeSucceeds && homeReadbackMatches) home = it; setHomeSucceeds },
            observeHome = { home },
            ownPackage = own,
            previousHomeComponent = { previousHome },
        )
    }

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

    @Test fun `rootless handback returns the restored original component for the shell without claiming success`() {
        for (reportedSuccess in listOf(false, true)) {
            val mutations = mutableListOf<String>()
            val (status, body) = handBack(controller(listOf(own, vendor), false, "$vendor/.Launcher", mutations,
                restorationSucceeds = true, setHomeSucceeds = reportedSuccess, homeReadbackMatches = false))
            val result = JSONObject(body)
            assertEquals(HttpStatusCode.OK, status)
            assertEquals("$vendor/.Launcher", result.optString("home_target"))
            assertFalse(result.getBoolean("ok"))
            assertFalse(result.has("home_handed_to"))
            assertEquals(vendor, result.getJSONArray("restored").getString(0))
            assertEquals(0, result.getJSONArray("outstanding").length())
            assertEquals(listOf("clear", "restore", "set-home:$vendor/.Launcher"), mutations)
        }
    }

    @Test fun `an outstanding replacement never authorizes shell handback`() {
        val mutations = mutableListOf<String>()
        val (status, body) = handBack(controller(listOf(own, vendor), false, "$vendor/.Launcher", mutations,
            setHomeSucceeds = false))
        val result = JSONObject(body)
        assertEquals(HttpStatusCode.OK, status)
        assertEquals(vendor, result.getJSONArray("outstanding").getString(0))
        assertFalse(result.has("home_target"))
        assertFalse(result.has("home_handed_to"))
        assertEquals(listOf("clear", "restore"), mutations)
    }

    @Test fun `unsafe homes and unknown inventory refuse without offering a shell target`() {
        for (unknown in listOf(false, true)) {
            val mutations = mutableListOf<String>()
            val (status, body) = handBack(controller(listOf(own, "com.android.settings"), true, null, mutations,
                packageStateKnown = !unknown))
            assertEquals(HttpStatusCode.Conflict, status)
            assertEquals(if (unknown) "package-state-unknown" else "no-replacement-home", JSONObject(body).getString("error"))
            assertFalse(JSONObject(body).has("home_target"))
            assertTrue(mutations.isEmpty())
        }
    }

    @Test fun `uncleared desired state refuses before any shell target is offered`() {
        val mutations = mutableListOf<String>()
        val (status, body) = handBack(controller(listOf(own, vendor), true, "$vendor/.Launcher", mutations,
            setHomeSucceeds = false, clearSucceeds = false))
        assertEquals(HttpStatusCode.Conflict, status)
        assertEquals("ownership-unreadable", JSONObject(body).getString("error"))
        assertFalse(JSONObject(body).has("home_target"))
        assertEquals(listOf("clear"), mutations)
    }

    @Test fun `session operation preserves the exact shell target from the same route`() {
        val mutations = mutableListOf<String>()
        val controller = controller(listOf(own, vendor), true, "$vendor/.Launcher", mutations, setHomeSucceeds = false)
        val table = ManageTable()
        testApplication {
            application {
                routing {
                    serveOperations(table)
                    handBackHomeRoutes(HandBackHomeRouteDependencies({ emptySet() }, controller::handBack,
                        { HandBackHomePolicy.RecordOutcome.UNKNOWN }))
                }
            }
            startApplication()
            val reply = table["hand_back_home"]!!.handler.handle(
                ManageRequest("hand_back_home", JSONObject(), CommandOrigin.panelAssistant(), null))
            val result = reply.result as JSONObject
            assertEquals("applied", reply.outcome)
            assertEquals("$vendor/.Launcher", result.optString("home_target"))
            assertFalse(result.has("home_handed_to"))
            assertFalse(result.getBoolean("ok"))
            assertEquals(listOf("clear", "restore", "set-home:$vendor/.Launcher"), mutations)
        }
    }

    @Test fun `confirmed home handback needs no shell target`() {
        val mutations = mutableListOf<String>()
        val (_, body) = handBack(controller(listOf(own, vendor), true, "$vendor/.Launcher", mutations))
        val result = JSONObject(body)
        assertTrue(result.getBoolean("ok"))
        assertEquals(vendor, result.getString("home_handed_to"))
        assertFalse(result.has("home_target"))
    }

}
