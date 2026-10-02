package io.github.maxlyth.hapaneld.sensors

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Frame classification for the shared Home Assistant socket.
 *
 * These assertions live at the decision itself rather than behind the stream's fake transport: a fake
 * that emits an already-classified message proves nothing about how a real frame is classified, so
 * this exercises actual result and registry/entity routing.
 */
class HaSocketFrameRoutingTest {
    private val registryIds = setOf(8, 9, 10)

    private fun result(id: Int, success: Boolean, message: String? = null) = JSONObject()
        .put("id", id)
        .put("type", "result")
        .put("success", success)
        .also { json -> message?.let { json.put("error", JSONObject().put("message", it)) } }

    @Test fun aRefusedEntitySubscriptionRemainsFatal() {
        val outcome = haResultOutcome(result(1, success = false, message = "boom"), 240)
        assertTrue(outcome is HaResultOutcome.Fatal)
        assertEquals("boom", (outcome as HaResultOutcome.Fatal).message)
    }

    @Test fun aRefusedRegistrySubscriptionRemainsFatal() {
        registryIds.forEach { id ->
            assertTrue(
                "id $id is a registry subscription and must stay fatal",
                haResultOutcome(result(id, success = false, message = "nope"), 240)
                    is HaResultOutcome.Fatal,
            )
        }
    }

    @Test fun aSuccessfulResultForAnEntitySubscriptionCarriesNothing() {
        // Unchanged: entity coverage is still not tracked. The panel reports what it OBSERVES rather
        // than which entity routes it believes are covered.
        assertEquals(HaResultOutcome.Ignored, haResultOutcome(result(1, true), 240))
    }

    @Test fun aFatalResultWithoutAnErrorMessageStillCarriesAReason() {
        val outcome = haResultOutcome(result(1, success = false), 240)
        assertEquals(
            HaResultOutcome.Fatal("Home Assistant rejected the entity subscription"),
            outcome,
        )
    }

    @Test fun aBlankErrorMessageFallsBackRatherThanReportingNothing() {
        val outcome = haResultOutcome(result(1, success = false, message = "   "), 240)
        assertEquals(HaResultOutcome.Fatal("Home Assistant rejected the entity subscription"), outcome)
    }

    @Test fun anOversizedErrorMessageIsTruncated() {
        val outcome = haResultOutcome(result(1, success = false, message = "x".repeat(500)), 240)
        assertEquals(240, (outcome as HaResultOutcome.Fatal).message.length)
    }

    @Test fun registryIdsRouteToRegistryAndUnknownIdsToEntities() {
        registryIds.forEach { assertEquals(HaEventRoute.Registry, haEventRoute(it, registryIds)) }
        assertEquals(HaEventRoute.Entities, haEventRoute(1, registryIds))
        assertEquals(HaEventRoute.Entities, haEventRoute(9999, registryIds))
    }

    @Test fun theRouteIsDecidedByIdAloneAndNeverReadsTheEventBody() {
        // A frame whose body claims a different type must not change the routing: trusting the payload
        // would let an entity event impersonate a registry change.
        val route = haEventRoute(1, registryIds)
        assertEquals(HaEventRoute.Entities, route)
    }
}
