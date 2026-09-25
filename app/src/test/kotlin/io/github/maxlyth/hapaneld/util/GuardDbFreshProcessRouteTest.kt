package io.github.maxlyth.hapaneld.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardDbFreshProcessRouteTest {
    @Test fun `failed INTENT with helper EMPTY clears to ordinary PaneldService`() {
        assertEquals(
            GuardDbFreshProcessRoute.ORDINARY_PANELD,
            guardDbFreshProcessRoute(
                GuardDbSentinelState.INTENT,
                GuardDbMaintenanceProtocol.Phase.EMPTY,
            ),
        )
    }

    @Test fun `retained baseline and armed custody route to writer free maintenance`() {
        listOf(
            GuardDbSentinelState.BASELINE_READY to GuardDbMaintenanceProtocol.Phase.EMPTY,
            GuardDbSentinelState.ARMED to GuardDbMaintenanceProtocol.Phase.WAIT_B_HEALTH,
        ).forEach { (sentinel, helper) ->
            assertEquals(
                "$sentinel with $helper",
                GuardDbFreshProcessRoute.WRITER_FREE_MAINTENANCE,
                guardDbFreshProcessRoute(sentinel, helper),
            )
        }
    }

    @Test fun `active sentinel startup resumes retained current helper before status recovery`() {
        val valid = GuardDbMaintenanceClient.StatusProbe.Valid(emptyStatus())
        val probes = ArrayDeque<GuardDbMaintenanceClient.StatusProbe>().apply {
            add(GuardDbMaintenanceClient.StatusProbe.Unsupported)
            add(valid)
        }
        val events = mutableListOf<String>()

        val result = reacquireGuardDbStartupStatus(
            statusProbe = {
                events += "probe"
                probes.removeFirst()
            },
            resumeRetainedCurrent = {
                events += "resume"
                true
            },
            pause = { events += "pause" },
        )

        assertSame(valid, result)
        assertEquals(listOf("probe", "resume", "probe"), events)
    }

    @Test fun `already valid startup status never invokes retained helper resume`() {
        val valid = GuardDbMaintenanceClient.StatusProbe.Valid(emptyStatus())
        var probes = 0
        var resumes = 0
        var pauses = 0

        val result = reacquireGuardDbStartupStatus(
            statusProbe = { probes += 1; valid },
            resumeRetainedCurrent = { resumes += 1; true },
            pause = { pauses += 1 },
        )

        assertSame(valid, result)
        assertEquals(1, probes)
        assertEquals(0, resumes)
        assertEquals(0, pauses)
    }

    @Test fun `unreachable startup exhausts each bounded window around one successful resume`() {
        val valid = GuardDbMaintenanceClient.StatusProbe.Valid(emptyStatus())
        val probes = ArrayDeque<GuardDbMaintenanceClient.StatusProbe>().apply {
            repeat(8) { add(GuardDbMaintenanceClient.StatusProbe.Unreachable) }
            add(GuardDbMaintenanceClient.StatusProbe.Unreachable)
            add(valid)
        }
        var resumes = 0
        var pauses = 0

        val result = reacquireGuardDbStartupStatus(
            statusProbe = { probes.removeFirst() },
            resumeRetainedCurrent = { resumes += 1; true },
            pause = { pauses += 1 },
        )

        assertSame(valid, result)
        assertEquals(1, resumes)
        assertEquals(8, pauses)
        assertTrue(probes.isEmpty())
    }

    @Test fun `failed retained resume preserves terminal status without another probe window`() {
        listOf(
            GuardDbMaintenanceClient.StatusProbe.Unsupported,
            GuardDbMaintenanceClient.StatusProbe.Unreachable,
        ).forEach { terminal ->
            var probes = 0
            var resumes = 0
            var pauses = 0
            val result = reacquireGuardDbStartupStatus(
                statusProbe = { probes += 1; terminal },
                resumeRetainedCurrent = { resumes += 1; false },
                pause = { pauses += 1 },
            )

            assertSame(terminal, result)
            assertEquals(1, resumes)
            assertEquals(if (terminal == GuardDbMaintenanceClient.StatusProbe.Unreachable) 8 else 1, probes)
            assertEquals(if (terminal == GuardDbMaintenanceClient.StatusProbe.Unreachable) 7 else 0, pauses)
        }
    }

    @Test fun `malformed startup status never invokes retained helper resume`() {
        var resumes = 0

        val result = reacquireGuardDbStartupStatus(
            statusProbe = { GuardDbMaintenanceClient.StatusProbe.Malformed },
            resumeRetainedCurrent = { resumes += 1; true },
            pause = { error("malformed status must not be retried") },
        )

        assertSame(GuardDbMaintenanceClient.StatusProbe.Malformed, result)
        assertEquals(0, resumes)
    }

    private fun emptyStatus() = GuardDbMaintenanceProtocol.Status(
        generation = 0L,
        phase = GuardDbMaintenanceProtocol.Phase.EMPTY,
        session = null,
        bootNonce = null,
        role = null,
        apkSha256 = null,
        versionCode = null,
        schema = null,
        baselineAppStateCount = 0L,
        error = null,
    )
}
