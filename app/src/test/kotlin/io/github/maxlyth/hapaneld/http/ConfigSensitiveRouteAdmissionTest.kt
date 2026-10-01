package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.security.SensitiveOperation
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigSensitiveRouteAdmissionTest {
    private fun executeRoute(
        hardened: Boolean,
        loopback: Boolean,
        operations: List<SensitiveOperation>,
        events: MutableList<String>,
        approve: (SensitiveOperation) -> Boolean = { true },
    ): ConfigSensitiveAdmissionResult = runBlocking {
        val result = ConfigSensitiveAdmission.authorize(hardened, loopback, operations) { operation ->
            events += "authorize:${operation.name}"
            approve(operation)
        }
        if (result == ConfigSensitiveAdmissionResult.AUTHORIZED) events += "mutate"
        result
    }

    @Test fun remoteHardenedMixedClassesRejectBeforeApprovalOrMutation() {
        val events = mutableListOf<String>()
        val result = executeRoute(
            true,
            false,
            listOf(SensitiveOperation.POWER_CONFIGURATION, SensitiveOperation.PACKAGE_TAME),
            events,
        )
        assertEquals(ConfigSensitiveAdmissionResult.SEPARATE_SENSITIVE_CHANGES, result)
        assertTrue(events.isEmpty())
    }

    @Test fun relaxedAndLoopbackCombinedSavesRemainDirect() {
        listOf(false to false, true to true).forEach { (hardened, loopback) ->
            val events = mutableListOf<String>()
            val result = executeRoute(
                hardened,
                loopback,
                listOf(SensitiveOperation.POWER_CONFIGURATION, SensitiveOperation.PACKAGE_TAME),
                events,
                approve = { error("Exempt request attempted physical approval") },
            )
            assertEquals(ConfigSensitiveAdmissionResult.AUTHORIZED, result)
            assertEquals(listOf("mutate"), events)
        }
    }

    @Test fun mutationFollowsSuccessfulApprovalAndDoesNotFollowDenial() {
        val approved = mutableListOf<String>()
        val denied = mutableListOf<String>()
        assertEquals(
            ConfigSensitiveAdmissionResult.AUTHORIZED,
            executeRoute(true, false, listOf(SensitiveOperation.POWER_CONFIGURATION), approved),
        )
        assertEquals(listOf("authorize:POWER_CONFIGURATION", "mutate"), approved)
        assertEquals(
            ConfigSensitiveAdmissionResult.DENIED,
            executeRoute(true, false, listOf(SensitiveOperation.POWER_CONFIGURATION), denied) { false },
        )
        assertEquals(listOf("authorize:POWER_CONFIGURATION"), denied)
    }

    @Test fun concurrentAdmissionsKeepEachApprovalAheadOfItsMutation() {
        val workers = 16
        val start = CountDownLatch(1)
        val completed = AtomicInteger()
        val failures = Collections.synchronizedList(mutableListOf<String>())
        val executor = Executors.newFixedThreadPool(workers)
        try {
            val futures = (0 until workers).map { id ->
                executor.submit {
                    val events = mutableListOf<String>()
                    start.await()
                    val result = executeRoute(
                        true,
                        false,
                        listOf(SensitiveOperation.POWER_CONFIGURATION),
                        events,
                    )
                    if (result != ConfigSensitiveAdmissionResult.AUTHORIZED ||
                        events != listOf("authorize:POWER_CONFIGURATION", "mutate")
                    ) failures += "$id:$result:$events"
                    completed.incrementAndGet()
                }
            }
            start.countDown()
            futures.forEach { it.get(5, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
        assertEquals(workers, completed.get())
        assertTrue(failures.joinToString(), failures.isEmpty())
    }
}
