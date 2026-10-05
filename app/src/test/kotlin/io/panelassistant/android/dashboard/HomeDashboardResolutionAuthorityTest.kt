package io.panelassistant.android.dashboard

import io.panelassistant.android.HaAuthOwner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Behavioural cover for the process-local resolution authority, driven through the real class rather
 * than asserted against source text: the launch cache PROMISES a live answer when it refreshes behind
 * an already rendering provisional page, and that promise is only kept if this authority actually
 * re-reads instead of replaying its cached answer.
 */
class HomeDashboardResolutionAuthorityTest {

    private val key = HomeDashboardResolutionAuthority.Key(
        baseUrl = "https://ha.example:8123",
        authOwner = HaAuthOwner("https://ha.example:8123", "refresh-a", "client-a", ""),
        configuredPath = "",
    )

    private fun resolution(path: String) = EntityLearningProtocol.HomeDashboardResolution(
        path, EntityLearningProtocol.HomeDashboardSource.USER_DEFAULT,
    )

    @Test fun `an ordinary resolve replays the process answer instead of re-reading`() = runBlocking {
        val authority = HomeDashboardResolutionAuthority()
        var reads = 0
        authority.resolve(key, { true }) { reads++; resolution("/alpha") }
        val replayed = authority.resolve(key, { true }) { reads++; resolution("/beta") }
        assertEquals(1, reads)
        assertEquals("/alpha", replayed?.path)
    }

    @Test fun `a forced refresh re-reads home assistant and republishes the new answer`() = runBlocking {
        val authority = HomeDashboardResolutionAuthority()
        var reads = 0
        authority.resolve(key, { true }) { reads++; resolution("/alpha") }
        // The account default moved while the panel was off; a forced read must observe it.
        val forced = authority.resolve(key, { true }, forceLive = true) { reads++; resolution("/beta") }
        assertEquals(2, reads)
        assertEquals("/beta", forced?.path)
        // …and the corrected answer becomes the process answer, so the next ordinary caller agrees.
        val afterwards = authority.resolve(key, { true }) { reads++; resolution("/stale") }
        assertEquals(2, reads)
        assertEquals("/beta", afterwards?.path)
    }

    @Test fun `a forced read that finds no legal dashboard is not cached`() = runBlocking {
        val authority = HomeDashboardResolutionAuthority()
        var reads = 0
        authority.resolve(key, { true }, forceLive = true) {
            reads++
            EntityLearningProtocol.HomeDashboardResolution()
        }
        // A true zero is authoritative only for that read, so the next caller must ask again.
        val next = authority.resolve(key, { true }) { reads++; resolution("/alpha") }
        assertEquals(2, reads)
        assertEquals("/alpha", next?.path)
    }

    @Test fun `a forced read is abandoned when the owner stops being current`() = runBlocking {
        val authority = HomeDashboardResolutionAuthority()
        var reads = 0
        authority.resolve(key, { true }) { reads++; resolution("/alpha") }
        val abandoned = authority.resolve(key, { false }, forceLive = true) { reads++; resolution("/beta") }
        assertNull(abandoned)
        assertEquals(1, reads)
    }

    @Test fun `an authoritative empty retires the positive answer this process is holding`() = runBlocking {
        val authority = HomeDashboardResolutionAuthority()
        var reads = 0
        authority.resolve(key, { true }) { reads++; resolution("/alpha") }
        // The account loses access to every dashboard; the forced read completes and reports zero.
        authority.resolve(key, { true }, forceLive = true) {
            reads++
            EntityLearningProtocol.HomeDashboardResolution()
        }
        // The stale positive must NOT be resurrectable by an ordinary caller, which would otherwise
        // persist and launch a path the account cannot reach.
        val next = authority.resolve(key, { true }) { reads++; EntityLearningProtocol.HomeDashboardResolution() }
        assertEquals(3, reads)
        assertNull(next?.path)
    }

    @Test fun `a transient failure preserves the positive answer`() = runBlocking {
        val authority = HomeDashboardResolutionAuthority()
        var reads = 0
        authority.resolve(key, { true }) { reads++; resolution("/alpha") }
        // A null READ is a transport/auth failure, not an authoritative zero.
        assertNull(authority.resolve(key, { true }, forceLive = true) { reads++; null })
        val replayed = authority.resolve(key, { true }) { reads++; resolution("/beta") }
        assertEquals(2, reads)
        assertEquals("/alpha", replayed?.path)
    }
}
