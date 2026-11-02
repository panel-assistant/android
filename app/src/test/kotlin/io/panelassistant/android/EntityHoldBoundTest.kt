package io.panelassistant.android

import io.panelassistant.android.dashboard.EntityBootstrapProblem
import io.panelassistant.android.dashboard.RendererEntityHold
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * No entity hold may keep a panel without its dashboard indefinitely (maintainer, 2026-10-28). Every kind
 * of hold says what is happening, keeps retrying on its own, and once it has stood for
 * [RendererEntityHold.BOUND_MS] opens the dashboard unfiltered, which is what the panel ran before entity
 * filtering, while status reports the hold it was released from. Each kind was seen holding on hardware or
 * in the code with nothing that would ever end it: a first scan Home Assistant never answered, a refused
 * scan, a filter the web viewer cannot install (its retry ladder stops after three attempts), and a flagged
 * rule nobody came to decide.
 *
 * Driven from the activity's first build through the gate's own timers on a virtual clock ([EntityHoldRig]).
 */
class EntityHoldBoundTest {
    private var rig: EntityHoldRig? = null

    @After fun close() { rig?.close() }

    @Test fun `a first scan that never answers opens the dashboard unfiltered within the bound`() =
        assertBounded(EntityHoldKind.LEARNING, EntityHoldRig.freshLearner()) {}

    @Test fun `a scan that could not finish opens the dashboard unfiltered within the bound`() =
        assertBounded(EntityHoldKind.SCAN_FAILED, EntityHoldRig.freshLearner()) { problem = EntityBootstrapProblem.SYNCHRONIZATION }

    @Test fun `a refused scan hands the panel to admission within the bound`() =
        assertBounded(EntityHoldKind.SCAN_REFUSED, EntityHoldRig.freshLearner()) { problem = EntityBootstrapProblem.AUTHENTICATION }

    @Test fun `flagged rules the panel may ignore do not keep the dashboard away past the bound`() =
        assertBounded(EntityHoldKind.ATTENTION, EntityHoldRig.freshLearner()) { blockingIssues = 3; canIgnore = true }

    @Test fun `flagged rules the panel cannot ignore do not keep the dashboard away past the bound`() =
        assertBounded(EntityHoldKind.TOO_MANY, EntityHoldRig.freshLearner()) { blockingIssues = 70; canIgnore = false }

    @Test fun `a filter the web viewer cannot install opens the dashboard unfiltered within the bound`() =
        // An explicit dashboard with a learned set: learning has its list, and only the document-start
        // interceptor is missing (the JVM's web viewer supports no document-start script).
        assertBounded(EntityHoldKind.FILTER_HOLD, EntityHoldRig.learned(homeDashboard = "lovelace")) {}

    @Test fun `a filter that fails to install at creation is bounded from its first hold, whatever its retries`() {
        // The web viewer offers document-start scripts, so admission builds; installing the filter on the new
        // WebView then fails every time, which paints the hold again, and its retry ladder rebuilds at 30 s.
        val rig = EntityHoldRig(EntityHoldRig.learned(homeDashboard = "lovelace"), documentStartSupported = true).also { rig = it }
        rig.start()
        assertEquals("the failed install holds the renderer", EntityHoldKind.FILTER_HOLD, rig.shown.lastOrNull())
        assertTrue(rig.admitted.isEmpty())

        rig.advance(RendererEntityHold.BOUND_MS - 1_000L)
        assertTrue("the 30 s retry held again: ${rig.shown}", rig.shown.size >= 2)
        assertTrue("still held just inside the bound", rig.admitted.isEmpty())

        rig.advance(2_000L)
        assertEquals("the bound counts from the first hold, not the latest retry", 1, rig.admitted.size)
        assertTrue("unfiltered: ${rig.admitted.single()}", rig.admitted.single().startsWith("disabled"))
        assertEquals(true, RendererEntityHold.snapshot()?.released)
    }

    @Test fun `a filter hold on a dark panel is still bounded`() =
        assertBounded(EntityHoldKind.FILTER_HOLD, EntityHoldRig.learned(homeDashboard = "lovelace"), awake = false) {}

    @Test fun `a released learning hold rebuilds the dashboard filtered once learning commits its list`() {
        val rig = EntityHoldRig(EntityHoldRig.freshLearner()).also { rig = it }
        rig.start()
        rig.advance(RendererEntityHold.BOUND_MS + 1_000L)
        assertEquals("released unfiltered", 1, rig.admitted.size)
        rig.advance(60_000L)
        assertEquals("nothing to rebuild while learning still has no list", 0, rig.reconciles)

        assertTrue(rig.config.commitDashboardEntitySubscription(true, listOf("light.office"), applied = true))
        rig.advance(10_000L)

        assertEquals("the cleared hold asks for one filtered rebuild", 1, rig.reconciles)
        assertEquals("nothing is held or released any more", null, RendererEntityHold.snapshot())
        assertTrue(
            "the rebuild prepares the learned list",
            kotlinx.coroutines.runBlocking { rig.gate.prepareEntityFilter(rig.config) }.signature.startsWith("enabled:"),
        )
        rig.advance(60_000L)
        assertEquals("one rebuild, not one per check", 1, rig.reconciles)
    }

    @Test fun `a recreated activity does not restart the bound`() {
        val config = EntityHoldRig.freshLearner()
        val first = EntityHoldRig(config)
        try {
            first.start()
            first.advance(60_000L)
            // The activity is recreated (a rotation, a kiosk return to a destroyed task): a new owner, a new gate.
            val second = EntityHoldRig(config, startAt = first.now).also { rig = it }
            second.start()
            assertEquals(listOf(EntityHoldKind.LEARNING), second.shown)
            second.advance(RendererEntityHold.BOUND_MS - 60_000L + 1_000L)
            assertEquals("the recreated hold keeps the time the first one started", 1, second.admitted.size)
        } finally {
            first.gate.stopLease() // its owner was superseded by the second; closing it would end the shared hold
        }
    }

    @Test fun `a hold left behind by an activity that went away does not cut a later hold short`() {
        // An activity held, then went away (the renderer switched, the task killed) with nothing timing it.
        RendererEntityHold.holding(EntityHoldKind.LEARNING.wire, 0L)
        val rig = EntityHoldRig(EntityHoldRig.freshLearner(), startAt = 10 * RendererEntityHold.BOUND_MS).also { rig = it }
        rig.start()
        rig.advance(RendererEntityHold.BOUND_MS - 1_000L)
        assertTrue("a new hold gets its whole time", rig.admitted.isEmpty())
        rig.advance(2_000L)
        assertEquals(1, rig.admitted.size)
    }

    private fun assertBounded(
        kind: EntityHoldKind,
        config: Config,
        awake: Boolean = true,
        state: EntityHoldRig.() -> Unit,
    ) {
        val rig = EntityHoldRig(config, screenAwake = awake).also { rig = it }
        rig.state()
        rig.start()

        assertEquals("the hold says what is happening", kind, rig.shown.lastOrNull())
        assertTrue("held, not yet built", rig.admitted.isEmpty())
        val holding = requireNotNull(RendererEntityHold.snapshot()) { "status reports the hold" }
        assertEquals(kind.wire, holding.kind)
        assertFalse(holding.released)

        rig.advance(RendererEntityHold.BOUND_MS - 1_000L)
        assertTrue("still held just inside the bound", rig.admitted.isEmpty())

        rig.advance(2_000L)
        assertEquals("the dashboard opens once the bound passes", 1, rig.admitted.size)
        assertTrue(
            "unfiltered, whatever the filter was: ${rig.admitted.single()}",
            rig.admitted.single().startsWith("disabled"),
        )
        val released = requireNotNull(RendererEntityHold.snapshot()) { "status reports the release" }
        assertEquals(kind.wire, released.kind)
        assertTrue(released.released)

        // Behind the open dashboard the panel keeps working on the cause, and never re-holds while it does.
        rig.advance(15 * 60_000L)
        assertEquals("never pulled back behind the hold", 1, rig.admitted.size)
        if (kind != EntityHoldKind.FILTER_HOLD) {
            assertTrue("learning keeps retrying on its own", rig.retries.isNotEmpty() || rig.probes.isNotEmpty())
        }
    }
}
