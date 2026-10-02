package io.github.maxlyth.hapaneld.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ids are correlation keys on one multiplexed socket. A collision does not fail loudly — it makes
 * one message answer for another — so the arithmetic is asserted directly rather than inferred from a
 * green stream test.
 */
class HaSubscriptionIdsTest {
    private val registryEvents = 3

    private fun ids(batches: Int, registry: Boolean) =
        haSubscriptionIds(batches, registry, registryEvents)

    @Test fun everySubscriptionIdIsDistinctAcrossEveryCombinationOfDemand() {
        for (batches in 0..4) {
            for (registry in listOf(false, true)) {
                val subject = ids(batches, registry)
                val all = subject.entityBatchIds + subject.registryIds
                assertEquals("batches=$batches registry=$registry must allocate distinct ids",
                    all.size, all.toSet().size)
            }
        }
    }

    @Test fun noPingIdCanCollideWithASubscriptionId() {
        for (batches in 0..4) {
            for (registry in listOf(false, true)) {
                val subject = ids(batches, registry)
                val taken = subject.allSubscriptionIds
                (0 until 50).forEach { step ->
                    val wireId = 10 + step + subject.pingIdOffset
                    assertTrue("ping wire id $wireId collides with a subscription", wireId !in taken)
                }
            }
        }
    }

    @Test fun thePingOffsetCountsEverySubscription() {
        val subject = ids(batches = 2, registry = true)
        assertEquals(2 + registryEvents, subject.pingIdOffset)
        assertEquals(subject.allSubscriptionIds.size, subject.pingIdOffset)
    }

    @Test fun withdrawnDemandAllocatesNothingForThatKind() {
        val none = ids(batches = 1, registry = false)
        assertTrue(none.registryIds.isEmpty())
        assertEquals(1, none.pingIdOffset)
    }

    @Test fun entityBatchIdsStartAtOneAndAreContiguous() {
        assertEquals(listOf(1, 2, 3), ids(batches = 3, registry = false).entityBatchIds)
    }
}
