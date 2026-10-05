package io.panelassistant.android.control

import io.panelassistant.android.util.DurableRecoveryMarker
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavbarOverscanRecoveryTest {
    @Test fun positiveCropPublishesRecoveryEvidenceBeforeMutation() = withRecovery { file, recovery, commands ->
        commands.runResult = { file.isFile }

        assertTrue(recovery.applyBottom(56))
        assertTrue(file.isFile)
        assertTrue(commands.runs.contains("wm overscan 0,0,0,56"))
    }

    @Test fun resetNeedsAnAffirmativeZeroDisplayReadbackBeforeClearing() = withRecovery { file, recovery, commands ->
        assertTrue(DurableRecoveryMarker(file).arm())
        commands.output = """
            DisplayInfo{"Built-in", app 800 x 424, real 800 x 480, overscan (0,0,0,56)}
            mOverscanScreen=(0,0) 800x424
        """.trimIndent()

        assertFalse(recovery.resetAndVerify())
        assertTrue(file.isFile)

        commands.output = """
            DisplayInfo{"Built-in", app 800 x 480, real 800 x 480}
            mOverscanScreen=(0,0) 800x480
        """.trimIndent()
        assertTrue(recovery.resetAndVerify())
        assertFalse(file.exists())
    }

    @Test fun malformedOrMissingReadbackRemainsRetryable() = withRecovery { file, recovery, commands ->
        assertTrue(DurableRecoveryMarker(file).arm())
        commands.output = "DisplayInfo unavailable"

        assertFalse(recovery.resetAndVerify())
        assertTrue(file.isFile)
    }

    @Test fun explicitZeroOverscanIsAlsoAccepted() {
        assertTrue(
            NavbarOverscanRecovery.displayOverscanIsZero(
                """
                    DisplayInfo{"Built-in", app 800 x 480, real 800 x 480, overscan (0,0,0,0)}
                    mOverscanScreen=(0,0) 800x480
                """.trimIndent(),
            ),
        )
        assertFalse(
            NavbarOverscanRecovery.displayOverscanIsZero(
                """
                    DisplayInfo{"Built-in", app 800 x 479, real 800 x 480, overscan (0,0,0,1)}
                    mOverscanScreen=(0,0) 800x479
                """.trimIndent(),
            ),
        )
    }

    @Test fun omittedOverscanFieldWithoutMatchingPolicyGeometryIsNotProof() {
        assertFalse(
            NavbarOverscanRecovery.displayOverscanIsZero(
                "DisplayInfo{\"Built-in\", app 800 x 480, real 800 x 480}",
            ),
        )
    }

    @Test fun absentOwnershipNeedsNoRootCommand() = withRecovery { _, recovery, commands ->
        assertTrue(recovery.resetAndVerify())
        assertTrue(commands.outputs.isEmpty())
    }

    // --- platforms without the subcommand (modern AOSP removed `wm overscan`) -------------------

    private val unknownCommand = "Unknown command: overscan\n${NavbarOverscanRecovery.RC_MARKER}=255"

    /** `Su.runOutput` discards output on a non-zero exit and `wm` exits 255 for an unknown subcommand,
     *  so the probe must keep the shell's own status zero and carry the crop command's status in the
     *  payload instead — otherwise it could never tell "no such command" from "no root". */
    @Test fun theProbeCarriesTheCropCommandStatusInItsPayload() {
        assertTrue(NavbarOverscanRecovery.SUPPORT_PROBE.startsWith("wm overscan 0,0,0,0"))
        assertTrue(NavbarOverscanRecovery.SUPPORT_PROBE.contains("echo ${NavbarOverscanRecovery.RC_MARKER}=\$?"))
    }

    /**
     * The failure mode that returned this lane from review. `SUPPORTED` must rest on the crop command
     * reporting success, never on "the payload did not look like the one refusal I recognise". A denied
     * permission, an absent `wm`, a dead window service or a differently worded refusal would otherwise
     * be cached as supported, arm the durable marker and recreate the permanent wedge.
     */
    @Test fun anUnrecognisedFailureIsNeverMistakenForSupport() = withRecovery { file, recovery, commands ->
        val rc = NavbarOverscanRecovery.RC_MARKER
        val failures = listOf(
            "/system/bin/sh: wm: not found\n$rc=127",
            "Permission denied\n$rc=1",
            "cmd: Can't find service: window\n$rc=1",
            "wm: this build does not provide overscan\n$rc=1",
            "Killed\n$rc=137",
            "truncated output with no status marker at all",
        )
        for (payload in failures) {
            commands.probeOutput = payload
            assertEquals(payload, OverscanSupport.UNKNOWN, recovery.support())
        }
        // None of them may be cached: a later working probe must still be able to learn the truth.
        assertEquals(failures.size, commands.probes)

        // And an indeterminate probe may neither arm the marker nor clear one.
        commands.probeOutput = failures.first()
        assertTrue(recovery.applyBottom(56))
        assertFalse("an indeterminate probe must not arm the marker", file.exists())
    }

    @Test fun classificationRestsOnTheReportedStatusNotOnTheWording() {
        val rc = NavbarOverscanRecovery.RC_MARKER
        assertEquals(OverscanSupport.SUPPORTED, NavbarOverscanRecovery.classifySupport("$rc=0"))
        // Success wins even if the output happens to mention the phrase.
        assertEquals(
            OverscanSupport.SUPPORTED,
            NavbarOverscanRecovery.classifySupport("note: unknown command elsewhere\n$rc=0"),
        )
        assertEquals(
            OverscanSupport.UNIMPLEMENTED,
            NavbarOverscanRecovery.classifySupport("Unknown command: overscan\n$rc=255"),
        )
        assertEquals(OverscanSupport.UNKNOWN, NavbarOverscanRecovery.classifySupport("boom\n$rc=1"))
        assertEquals(OverscanSupport.UNKNOWN, NavbarOverscanRecovery.classifySupport(""))
    }

    @Test fun probeReadsThePayloadBecauseAFailedExitYieldsNoOutput() = withRecovery { _, recovery, commands ->
        commands.probeOutput = unknownCommand
        assertEquals(OverscanSupport.UNIMPLEMENTED, recovery.support())

        commands.probeOutput = "${NavbarOverscanRecovery.RC_MARKER}=0"
        assertEquals(OverscanSupport.UNIMPLEMENTED, recovery.support())
        assertEquals("a conclusive answer is cached", 1, commands.probes)
    }

    @Test fun anUnavailableRootShellIsNotEvidenceAboutThePlatform() = withRecovery { _, recovery, commands ->
        commands.probeOutput = null
        assertEquals(OverscanSupport.UNKNOWN, recovery.support())

        // Nothing was learned, so the answer must not be cached against a later working shell.
        commands.probeOutput = "${NavbarOverscanRecovery.RC_MARKER}=0"
        assertEquals(OverscanSupport.SUPPORTED, recovery.support())
        assertEquals(2, commands.probes)
    }

    /** The wedge: arming for a command the platform cannot run left a marker that could never clear. */
    @Test fun noCropIsClaimedWhereTheCommandDoesNotExist() = withRecovery { file, recovery, commands ->
        commands.probeOutput = unknownCommand

        assertTrue("Always on must still succeed and draw its bar", recovery.applyBottom(56))
        assertFalse("arming here is what wedged every later transition", file.exists())
        assertTrue("no crop may be attempted", commands.runs.none { it.startsWith("wm overscan") })
    }

    @Test fun anImpossibleCropIsNotLeftPendingForever() = withRecovery { file, recovery, commands ->
        // A marker written by an older build, on a platform that cannot have cropped anything.
        assertTrue(DurableRecoveryMarker(file).arm())
        commands.probeOutput = unknownCommand

        assertTrue(recovery.resetAndVerify())
        assertFalse("the marker must clear rather than retry forever", file.exists())
        assertFalse(
            "a readback that can never succeed must not be attempted",
            commands.outputs.contains(NavbarOverscanRecovery.RESET_AND_READBACK),
        )
    }

    /** End-to-end regression: this exact sequence left the controller unable to reach any mode. */
    @Test fun everyModeChangeStaysPossibleAfterAlwaysOnOnSuchAPlatform() = withRecovery { file, recovery, commands ->
        commands.probeOutput = unknownCommand
        commands.runResult = { false } // the platform refuses `wm overscan` however it is invoked

        assertTrue(recovery.applyBottom(56))   // Always on
        assertTrue(recovery.applyBottom(0))    // back to Off / Swipe reveal
        assertTrue(recovery.applyBottom(56))   // and Always on again
        assertFalse(file.exists())
    }

    @Test fun anUnknownProbeKeepsTheMarkerForALaterRetry() = withRecovery { file, recovery, commands ->
        assertTrue(DurableRecoveryMarker(file).arm())
        commands.probeOutput = null
        commands.output = null // the readback cannot run either

        assertFalse(recovery.resetAndVerify())
        assertTrue("an unreachable shell must not be read as 'nothing is cropped'", file.isFile)
    }

    private fun withRecovery(
        block: (file: java.io.File, recovery: NavbarOverscanRecovery, commands: Commands) -> Unit,
    ) {
        val directory = Files.createTempDirectory("navbar-overscan-test").toFile()
        try {
            val file = directory.resolve("overscan.pending")
            val commands = Commands()
            val recovery = NavbarOverscanRecovery(
                marker = DurableRecoveryMarker(file),
                run = { command -> commands.runs += command; commands.runResult(command) },
                runOutput = { command -> commands.outputs += command; commands.respond(command) },
            )
            block(file, recovery, commands)
        } finally {
            directory.deleteRecursively()
        }
    }

    private class Commands {
        val runs = mutableListOf<String>()
        val outputs = mutableListOf<String>()
        var runResult: (String) -> Boolean = { true }
        var output: String? = null

        /** Support-probe reply. A platform that HAS the subcommand prints nothing and reports status
         *  zero, so the default keeps every pre-existing case on the supported path. */
        var probeOutput: String? = "${NavbarOverscanRecovery.RC_MARKER}=0"

        fun respond(command: String): String? =
            if (command == NavbarOverscanRecovery.SUPPORT_PROBE) probeOutput else output

        val probes: Int get() = outputs.count { it == NavbarOverscanRecovery.SUPPORT_PROBE }
    }
}
