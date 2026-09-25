package io.github.maxlyth.hapaneld

import io.github.maxlyth.hapaneld.DashboardIdleReturnPolicy.Tick
import io.github.maxlyth.hapaneld.dashboard.EntityLearningProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The idle-return tick against an unresolved home dashboard. The reported crash: the tick read the home
 * dashboard before its switched-off guard, and the read threw "home dashboard used before
 * authenticated resolution" on panels that never enabled idle return.
 */
class DashboardIdleReturnTickTest {
    private val owner = owner("")
    private var reads = 0
    private val unresolved = { reads++; ownedHomeDashboardPath(null, owner) }
    private val noRoute: (String) -> String? = { fail("route computed without a due tick"); null }

    /** The tick's answer; a throw from the tick or the lookup is the reported crash, surfaced as a failure. */
    private fun tick(minutes: Int, idleMs: Long, home: () -> String?, route: (String) -> String? = noRoute) =
        runCatching { DashboardIdleReturnPolicy.tick(minutes, idleMs, home, route) }
            .getOrElse { fail("idle tick threw: $it"); error("unreachable") }

    @Test fun `idle return off never reads an unresolved home dashboard`() {
        assertEquals(Tick.Off, tick(0, HOUR, unresolved))
        assertEquals(0, reads)
    }

    @Test fun `idle return on but not idle long enough never reads the home dashboard`() {
        assertEquals(Tick.NotIdle, tick(5, 4 * MINUTE, unresolved))
        assertEquals(0, reads)
    }

    @Test fun `a due tick with an unresolved home dashboard waits for the resolution`() {
        assertEquals(Tick.Unresolved, tick(5, HOUR, unresolved))
        assertEquals(1, reads)
    }

    @Test fun `a resolution held for a superseded owner reads as unresolved`() {
        // A home_dashboard or credential write is visible to the tick before the activity rebuilds.
        val stale = resolved(owner("/old"), "lovelace/old")
        assertEquals(Tick.Unresolved, tick(5, HOUR, { ownedHomeDashboardPath(stale, owner) }))
    }

    @Test fun `a resolved due tick returns to the normalised home dashboard`() {
        val current = resolved(owner, "/lovelace/home/")
        var routedFrom: String? = null
        val result = tick(5, HOUR, { ownedHomeDashboardPath(current, owner) }) { home ->
            routedFrom = home
            DashboardIdleReturnPolicy.target("/other/view", null, home)
        }
        assertEquals("lovelace/home", routedFrom)
        assertEquals(Tick.Return("lovelace/home"), result)
    }

    @Test fun `a resolved due tick already at home stays put`() {
        val current = resolved(owner, "lovelace/home")
        val result = tick(5, HOUR, { ownedHomeDashboardPath(current, owner) }) { home ->
            DashboardIdleReturnPolicy.target("/lovelace/home", null, home)
        }
        assertEquals(Tick.AtHome, result)
    }

    @Test fun `owned lookup answers only for the current owner and a legal dashboard`() {
        val lookup = { resolution: OwnedHomeDashboardResolution? ->
            runCatching { ownedHomeDashboardPath(resolution, owner) }.getOrElse { fail("lookup threw: $it"); null }
        }
        assertEquals("lovelace/home", lookup(resolved(owner, "lovelace/home")))
        assertNull(lookup(null))
        assertNull(lookup(resolved(owner("/old"), "lovelace/old")))
        assertNull(lookup(resolved(owner, null)))
    }

    @Test fun `an unresolved episode is reported once and a resolved read starts a new one`() {
        val notice = DashboardIdleReturnPolicy.UnresolvedNotice()
        assertTrue(notice.shouldLog(Tick.Unresolved))
        assertFalse(notice.shouldLog(Tick.Unresolved))
        assertFalse(notice.shouldLog(Tick.Off))
        assertFalse(notice.shouldLog(Tick.NotIdle))
        assertFalse(notice.shouldLog(Tick.Unresolved))
        assertFalse(notice.shouldLog(Tick.AtHome))
        assertTrue(notice.shouldLog(Tick.Unresolved))
        assertFalse(notice.shouldLog(Tick.Return("lovelace/home")))
        assertTrue(notice.shouldLog(Tick.Unresolved))
    }

    private fun resolved(owner: HomeDashboardResolutionOwner, path: String?) = OwnedHomeDashboardResolution(
        owner,
        EntityLearningProtocol.HomeDashboardResolution(path, EntityLearningProtocol.HomeDashboardSource.EXPLICIT),
    )

    private fun owner(path: String) = HomeDashboardResolutionOwner(
        authOwner = HaAuthOwner("https://ha.example", "refresh", "client", ""),
        configuredPath = path,
    )

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }
}
