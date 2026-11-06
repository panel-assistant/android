package io.panelassistant.android.control

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CdpRelayStateTest {

    @Test fun acceptedLaunchIsNotRunningWhenChildProbeFails() {
        val state = RelayProcessState(probe = { RelayExposureState.ABSENT })
        assertFalse(state.start { true })
        assertFalse(state.running())
    }

    @Test fun processDeathInvalidatesAnEarlierSuccessfulStart() {
        var alive = true
        val state = RelayProcessState(probe = {
            if (alive) RelayExposureState.PRESENT else RelayExposureState.ABSENT
        })
        assertTrue(state.start { true })
        assertTrue(state.running())
        alive = false
        assertFalse(state.running())
    }

    @Test fun failedTerminationCannotHideALiveOrphan() {
        val state = RelayProcessState({ RelayExposureState.PRESENT }, pause = {})
        assertTrue(state.start { true })
        assertFalse(state.stop { })
        assertTrue(state.running())
    }

    @Test fun successfulTerminationRequiresAVerifiedAbsentProbe() {
        var alive = true
        val state = RelayProcessState({
            if (alive) RelayExposureState.PRESENT else RelayExposureState.ABSENT
        }, pause = {})
        assertTrue(state.start { true })
        assertTrue(state.stop { alive = false })
        assertFalse(state.running())
    }

    @Test fun failedRootProbeFailsClosedForStopAndRunningState() {
        val state = RelayProcessState(probe = { RelayExposureState.UNKNOWN })
        assertFalse(state.stop { })
        assertTrue(state.running())
    }

    @Test fun truthfulNoListenerStatusCannotWeakenStrictStopVerification() {
        val state = RelayProcessState(
            probe = { RelayExposureState.ABSENT },
            stopProbe = { RelayExposureState.UNKNOWN },
        )
        assertFalse(state.running())
        assertFalse(state.stop { })
    }

    @Test fun strictAbsenceAndAlreadyAbsentStopNeverUseDisplayOrActuator() {
        var reads = 0
        val state = RelayProcessState(
            probe = { error("security proof must not read display") },
            stopProbe = { reads++; RelayExposureState.ABSENT },
        )
        repeat(3) { assertTrue(state.proveAbsent()) }
        assertTrue(state.stop { error("already absent needs no kill") })
        assertTrue(state.stopForRecovery({ error("no su query") }, { error("no fallback") }, { error("no kill") }))
        assertEquals(5, reads)
    }

    @Test fun strictUnknownAndPresentNeverBecomePassiveAbsence() {
        for (exposure in listOf(RelayExposureState.UNKNOWN, RelayExposureState.PRESENT)) {
            val state = RelayProcessState(probe = { RelayExposureState.ABSENT }, stopProbe = { exposure })
            repeat(3) { assertFalse(state.proveAbsent()) }
            assertFalse(state.running())
        }
    }

    @Test fun explicitRecoveryNeedsStructuralNoRootAndCannotOverridePositiveEvidence() {
        var exposure = RelayExposureState.UNKNOWN
        var kills = 0
        var fallbacks = 0
        val state = RelayProcessState({ RelayExposureState.ABSENT }, { exposure }, {})
        assertFalse(state.stopForRecovery({ false }, { fallbacks++; true }, { kills++ }))
        assertEquals(1, kills); assertEquals(0, fallbacks)
        assertTrue(state.stopForRecovery({ true }, { fallbacks++; true }, { kills++ }))
        assertEquals(1, kills); assertEquals(1, fallbacks)
        exposure = RelayExposureState.PRESENT
        assertFalse(state.stopForRecovery({ true }, { fallbacks++; true }, { kills++ }))
        assertEquals(2, kills); assertEquals(1, fallbacks)
    }

    @Test fun firstExplicitStopCanDiscoverStructuralNoRootWithoutWeakeningPositiveProof() {
        var structurallyUnusable = false
        var exposure = RelayExposureState.UNKNOWN
        var fallbacks = 0
        val state = RelayProcessState({ RelayExposureState.ABSENT }, { exposure }, {})
        assertTrue(state.stopForRecovery({ structurallyUnusable }, { fallbacks++; true }, { structurallyUnusable = true }))
        assertEquals(1, fallbacks)
        exposure = RelayExposureState.PRESENT
        assertFalse(state.stopForRecovery({ true }, { fallbacks++; true }, {}))
        assertEquals(1, fallbacks)
    }

    @Test fun knownExposureCannotBecomeNoRootAbsenceAfterFailedTermination() {
        val unknown = RelayExposureState.UNKNOWN
        val present = RelayExposureState.PRESENT
        for (sequence in listOf(
            listOf(present, present, unknown, unknown),
            listOf(unknown, present, unknown, unknown),
            listOf(unknown, unknown, present, unknown, unknown),
        )) {
            val replies = ArrayDeque(sequence)
            var structurallyUnusable = false
            var kills = 0
            var fallbacks = 0
            val state = RelayProcessState({ RelayExposureState.ABSENT }, { replies.removeFirst() }, {})
            assertFalse(sequence.toString(), state.stopForRecovery(
                { structurallyUnusable }, { fallbacks++; true }, { kills++; structurallyUnusable = true },
            ))
            assertEquals(1, kills); assertEquals(0, fallbacks); assertTrue(replies.isEmpty())
        }
    }

    @Test fun explicitStopRequiresFreshHelperProofAfterActuator() {
        val replies = ArrayDeque(listOf(RelayExposureState.PRESENT, RelayExposureState.PRESENT,
            RelayExposureState.PRESENT, RelayExposureState.ABSENT))
        var kills = 0
        val state = RelayProcessState({ RelayExposureState.ABSENT }, { replies.removeFirst() }, {})
        assertTrue(state.stop { kills++ })
        assertEquals(1, kills); assertTrue(replies.isEmpty())
    }

    @Test fun `relay binary is atomically staged below a root-only directory`() {
        val command = CdpRelay.startCommand(
            "/data/user/0/io.github.maxlyth.hapaneld/files/cdprelay",
            "webview_devtools_remote_123",
        )
        assertTrue(command.contains("rm -rf /data/local/.hapaneld-cdp"))
        assertTrue(command.contains("mkdir -m 700 /data/local/.hapaneld-cdp"))
        assertTrue(command.contains("chown 0:0 /data/local/.hapaneld-cdp"))
        assertTrue(command.contains("chown 0:0 /data/local/.hapaneld-cdp/cdprelay.new"))
        assertTrue(command.contains("mv -f /data/local/.hapaneld-cdp/cdprelay.new /data/local/.hapaneld-cdp/cdprelay"))
        assertFalse(command.contains("cp /data/user/0/io.github.maxlyth.hapaneld/files/cdprelay /data/local/tmp/cdprelay"))
    }

    @Test fun `socket selection ignores another apps earlier WebView`() {
        val sockets = """
            000: 00000002 00000000 00010000 0001 01 1 @webview_devtools_remote_111
            000: 00000002 00000000 00010000 0001 01 2 @webview_devtools_remote_222
        """.trimIndent()
        assertEquals("webview_devtools_remote_222", CdpRelay.selectDevToolsSocket(sockets, setOf(222)))
    }

    @Test fun `socket selection fails closed when renderer owns multiple endpoints`() {
        val sockets = "@webview_devtools_remote_222\n@webview_devtools_remote_333"
        assertNull(CdpRelay.selectDevToolsSocket(sockets, setOf(222, 333)))
    }

    @Test fun `socket selection requires a renderer pid match`() {
        assertNull(CdpRelay.selectDevToolsSocket("@webview_devtools_remote_111", setOf(222)))
        assertNull(CdpRelay.selectDevToolsSocket("@webview_devtools_remote_111", emptySet()))
    }

    @Test fun `never-rooted panel without a listener can enter Hardened mode`() {
        assertTrue(
            noRootRelayAbsent(
                priorRelayArtifact = false,
                listenerProbes = listOf(LocalRelayListenerState.ABSENT, LocalRelayListenerState.ABSENT),
            ),
        )
    }

    @Test fun `no-root admission rejects prior relay evidence listener and probe failure`() {
        assertFalse(
            noRootRelayAbsent(
                priorRelayArtifact = true,
                listenerProbes = listOf(LocalRelayListenerState.ABSENT, LocalRelayListenerState.ABSENT),
            ),
        )
        assertFalse(
            noRootRelayAbsent(
                priorRelayArtifact = false,
                listenerProbes = listOf(LocalRelayListenerState.ABSENT, LocalRelayListenerState.PRESENT),
            ),
        )
        assertFalse(
            noRootRelayAbsent(
                priorRelayArtifact = false,
                listenerProbes = listOf(LocalRelayListenerState.ABSENT, LocalRelayListenerState.UNKNOWN),
            ),
        )
    }

    @Test fun `process exit ignores stale source artifact only after repeated listener absence`() {
        assertTrue(
            noRootRelayInactiveForProcessExit(
                listOf(LocalRelayListenerState.ABSENT, LocalRelayListenerState.ABSENT),
            ),
        )
        assertFalse(
            noRootRelayInactiveForProcessExit(
                listOf(LocalRelayListenerState.ABSENT, LocalRelayListenerState.PRESENT),
            ),
        )
        assertFalse(
            noRootRelayInactiveForProcessExit(
                listOf(LocalRelayListenerState.ABSENT, LocalRelayListenerState.UNKNOWN),
            ),
        )
        assertFalse(noRootRelayInactiveForProcessExit(listOf(LocalRelayListenerState.ABSENT)))
    }

    @Test fun `non-root running status uses the real fixed listener`() {
        assertEquals(
            RelayExposureState.ABSENT,
            relayStatusExposure(RelayExposureState.UNKNOWN, LocalRelayListenerState.ABSENT),
        )
        assertEquals(
            RelayExposureState.PRESENT,
            relayStatusExposure(RelayExposureState.UNKNOWN, LocalRelayListenerState.PRESENT),
        )
        assertEquals(
            RelayExposureState.UNKNOWN,
            relayStatusExposure(RelayExposureState.UNKNOWN, LocalRelayListenerState.UNKNOWN),
        )
    }

    @Test
    fun autoAndBuiltinSelectionsExposeThisAppsOwnRenderer() {
        val own = "io.panelassistant.android"
        assertEquals(own, CdpRelay.relayRendererPackage("", own))
        assertEquals(own, CdpRelay.relayRendererPackage("builtin", own))
        assertEquals(own, CdpRelay.relayRendererPackage(own, own))
        assertEquals(
            "io.homeassistant.companion.android",
            CdpRelay.relayRendererPackage("io.homeassistant.companion.android", own),
        )
        assertNull(CdpRelay.relayRendererPackage("not a package; rm -rf /", own))
    }
}
