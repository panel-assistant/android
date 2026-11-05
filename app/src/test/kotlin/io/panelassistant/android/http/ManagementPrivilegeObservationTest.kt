package io.panelassistant.android.http

import io.panelassistant.android.control.CountingSuLaunch
import io.panelassistant.android.control.DensityController
import io.panelassistant.android.control.SuShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The privilege view behind every management snapshot (Panel Assistant's status poll, the dashboard,
 * diagnostics) is read about twice a minute. It must start no root process: su is what real root
 * actions observed, the helper is its own PING.
 */
class ManagementPrivilegeObservationTest {
    private var now = 0L
    private val su = CountingSuLaunch()
    private val shell = SuShell(launch = su::launch, nowMs = { now })
    private var helperAnswers = false

    private val observations = ManagementObservations(
        object : android.content.ContextWrapper(null) {},
        DensityController(canSu = false),
        { error("not used") },
        { _, _ -> error("not used") },
        CoroutineScope(Dispatchers.Unconfined),
        { false },
        nowMs = { now },
        root = shell,
        helperRootProbe = { helperAnswers },
    )

    @After fun close() = su.close()

    @Test fun `repeated snapshots start no su once root is proved, and still report it`() {
        assertEquals("", shell.runOutputIsolatedBounded("true", 1L, 5_000L))
        val before = su.rootWork()

        repeat(10) {
            val privilege = observations.privilegeObservation()
            assertTrue(privilege.directSuReady)
            assertTrue(privilege.rootControlReady)
            now += 61_000L
        }

        assertEquals("10 status snapshots started root work", 0, su.rootWork() - before)
    }

    @Test fun `a helper-only panel reports the helper and starts no su`() {
        helperAnswers = true
        repeat(10) {
            val privilege = observations.privilegeObservation()
            assertFalse("su was never proved, so it is not offered as ready", privilege.directSuReady)
            assertTrue(privilege.helperRootReady)
            now += 61_000L
        }
        assertEquals(0, su.rootWork())
    }
}
