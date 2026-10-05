package io.panelassistant.android

import io.panelassistant.android.control.BuiltinDashboard
import io.panelassistant.android.util.HaTransportEvidence
import io.panelassistant.android.util.HaTransportFault
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The holder's whole contract is "only the live renderer generation may describe the live renderer".
 * Every test here is a way that could be violated, because each one has a real counterpart: Android
 * overlaps activity lifetimes while replacing a task, a probe outlives the activity that launched it,
 * and a process restart must not inherit a verdict.
 */
class RendererAdmissionRuntimeTest {

    private var owner = 0L

    @Before fun acquire() {
        RendererAdmissionRuntime.reset()
        owner = BuiltinDashboard.acquireActivityOwner()
    }

    @After fun release() {
        BuiltinDashboard.releaseActivityOwner(owner)
        RendererAdmissionRuntime.reset()
    }

    private fun blocked(owner: Long, at: Long = 1_000L, outcome: AdmissionOutcome = AdmissionOutcome.TRANSPORT_FAILED) =
        RendererAdmissionRuntime.record(
            owner = owner,
            state = RendererAdmissionState.BLOCKED,
            nowElapsedMs = at,
            outcome = outcome,
            evidence = HaTransportEvidence(HaTransportFault.TLS_TRUST, "CertPathValidatorException"),
        )

    @Test fun theLiveGenerationCanRecordAndRead() {
        assertTrue(blocked(owner))
        val live = RendererAdmissionRuntime.current()!!
        val stored = live.record!!
        assertEquals(owner, live.owner)
        assertEquals(RendererAdmissionState.BLOCKED, stored.state)
        assertEquals(AdmissionOutcome.TRANSPORT_FAILED, stored.outcome)
        assertEquals(HaTransportFault.TLS_TRUST, stored.evidence.fault)
        assertEquals(1_000L, stored.observedAtElapsedMs)
    }

    @Test fun afreshProcessHasNothingToReport() {
        // Never persisted: a panel that has not decided anything says so rather than inheriting a
        // verdict from before the restart. This is also the state after every app update.
        assertNull(RendererAdmissionRuntime.current())
    }

    @Test fun aSupersededGenerationCanNeitherWriteNorBeRead() {
        assertTrue(blocked(owner))
        val successor = BuiltinDashboard.acquireActivityOwner()
        try {
            // The replacement took over, so the predecessor's verdict is no longer about the live
            // renderer — reading it would report a screen that is already gone.
            assertNull(RendererAdmissionRuntime.current())
            // And a probe the predecessor started, finishing after the handover, must be dropped
            // rather than describing its successor.
            assertFalse(blocked(owner, at = 9_000L, outcome = AdmissionOutcome.CREDENTIAL_REFUSED))
            assertNull(RendererAdmissionRuntime.current())
        } finally {
            BuiltinDashboard.releaseActivityOwner(successor)
        }
    }

    @Test fun releasingTheRendererRetiresItsVerdictWithNoTeardownHook() {
        assertTrue(blocked(owner))
        BuiltinDashboard.releaseActivityOwner(owner)
        assertNull(RendererAdmissionRuntime.current())
    }

    @Test fun theConnectionFlagAndTheVerdictStayOneValue() {
        assertTrue(RendererAdmissionRuntime.setFrontendConnected(owner, true))
        assertTrue(
            RendererAdmissionRuntime.record(
                owner = owner,
                state = RendererAdmissionState.ADMITTED,
                nowElapsedMs = 2_000L,
            ),
        )
        // A verdict recorded after a connection must not discard it: the pair is read as one tuple,
        // and "admitted" beside a lost connection flag would read as a panel that never rendered.
        //
        // `record?.state`, never `record!!.state`: the battery caught the `!!` form throwing an NPE
        // instead of asserting when the verdict WAS dropped, which the session scores as a test error
        // rather than a kill — a test that crashes has not demonstrated what it claims to check.
        val live = RendererAdmissionRuntime.current()!!
        assertTrue(live.frontendConnected)
        assertEquals(RendererAdmissionState.ADMITTED, live.record?.state)

        // The converse: a later disconnection must not discard the verdict it belongs to.
        assertTrue(RendererAdmissionRuntime.setFrontendConnected(owner, false))
        val after = RendererAdmissionRuntime.current()!!
        assertFalse(after.frontendConnected)
        assertEquals(RendererAdmissionState.ADMITTED, after.record?.state)
    }

    @Test fun aSupersededGenerationCannotPublishAConnection() {
        assertTrue(RendererAdmissionRuntime.setFrontendConnected(owner, true))
        val successor = BuiltinDashboard.acquireActivityOwner()
        try {
            assertFalse(RendererAdmissionRuntime.setFrontendConnected(owner, true))
            // The successor has connected nothing yet, so nothing claims it has.
            assertNull(RendererAdmissionRuntime.current())
        } finally {
            BuiltinDashboard.releaseActivityOwner(successor)
        }
    }

    @Test fun aReplacementStartsFromNothingRatherThanInheriting() {
        assertTrue(RendererAdmissionRuntime.setFrontendConnected(owner, true))
        assertTrue(blocked(owner))
        val successor = BuiltinDashboard.acquireActivityOwner()
        try {
            assertTrue(
                RendererAdmissionRuntime.record(
                    owner = successor,
                    state = RendererAdmissionState.CHECKING,
                    nowElapsedMs = 5_000L,
                ),
            )
            val live = RendererAdmissionRuntime.current()!!
            assertEquals(successor, live.owner)
            assertEquals(RendererAdmissionState.CHECKING, live.record!!.state)
            // The predecessor's connection does not carry across a generation boundary — inheriting
            // it would let a replaced renderer's success stand in for one that has not loaded yet.
            assertFalse(live.frontendConnected)
        } finally {
            BuiltinDashboard.releaseActivityOwner(successor)
        }
    }

    @Test fun anUnacquiredLeaseCannotWrite() {
        // The renderer's `frontendConnected` field is initialised before `onCreate` takes a lease.
        assertFalse(RendererAdmissionRuntime.setFrontendConnected(0L, true))
        assertFalse(blocked(0L))
        assertNull(RendererAdmissionRuntime.current())
    }

    // --- the effective-theme observation rides the same generation tuple ------------------------

    @Test fun theEffectiveThemeIsPublishedForTheLiveGenerationAndSurvivesARecord() {
        assertTrue(RendererAdmissionRuntime.setEffectiveTheme(owner, false))
        assertEquals(false, RendererAdmissionRuntime.current()!!.effectiveDark)
        // A later admission record must not wipe the observation: the two halves are written by
        // different paths at different moments and both describe the same page.
        assertTrue(RendererAdmissionRuntime.record(owner, RendererAdmissionState.ADMITTED, 2_000L))
        assertEquals(false, RendererAdmissionRuntime.current()!!.effectiveDark)
        assertTrue(RendererAdmissionRuntime.setFrontendConnected(owner, true))
        assertEquals(false, RendererAdmissionRuntime.current()!!.effectiveDark)
    }

    @Test fun aDisconnectClearsTheEffectiveThemeSoAStaleSchemeIsNeverReported() {
        RendererAdmissionRuntime.setFrontendConnected(owner, true)
        RendererAdmissionRuntime.setEffectiveTheme(owner, true)
        assertEquals(true, RendererAdmissionRuntime.current()!!.effectiveDark)
        RendererAdmissionRuntime.setFrontendConnected(owner, false)
        assertNull(RendererAdmissionRuntime.current()!!.effectiveDark)
    }

    @Test fun aSupersededGenerationCannotPublishATheme() {
        val stale = owner
        owner = BuiltinDashboard.acquireActivityOwner()
        assertFalse(RendererAdmissionRuntime.setEffectiveTheme(stale, true))
        assertNull(RendererAdmissionRuntime.current()?.effectiveDark)
    }
}
