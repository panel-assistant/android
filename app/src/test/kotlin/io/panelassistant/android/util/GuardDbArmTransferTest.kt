package io.panelassistant.android.util

import io.panelassistant.android.persistence.CleanDatabaseProof
import io.panelassistant.android.platform.DaemonLongResult
import io.panelassistant.android.platform.DaemonStreamResult
import java.io.File
import java.util.ArrayDeque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardDbArmTransferTest {
    private val session = "1".repeat(64)
    private val boot = "2".repeat(64)
    private val aSha = "a".repeat(64)
    private val bSha = "b".repeat(64)

    @Test fun `lost mutation replies reconcile from same session status without replay`() = withManifest { manifest ->
        val transport = ScriptedTransport(
            shortReplies = listOf(
                status(1, GuardDbMaintenanceProtocol.Phase.STAGING),
                status(6, GuardDbMaintenanceProtocol.Phase.STAGING),
                status(9, GuardDbMaintenanceProtocol.Phase.WAIT_B_HEALTH, GuardDbMaintenanceProtocol.Role.B),
            ),
            longReplies = listOf(
                DaemonLongResult.Indeterminate,
                reply("GUARDDEFINE", 2, GuardDbMaintenanceProtocol.Phase.STAGING),
                reply("GUARDDEFINE", 3, GuardDbMaintenanceProtocol.Phase.STAGING),
                DaemonLongResult.Indeterminate,
            ),
            streamReplies = listOf(
                streamReply("GUARDSTREAM", 4, GuardDbMaintenanceProtocol.Phase.STAGING),
                streamReply("GUARDSTREAM", 5, GuardDbMaintenanceProtocol.Phase.STAGING),
                DaemonStreamResult.Indeterminate,
            ),
        )

        assertEquals(
            GuardDbArmTransferResult.Submitted(9, GuardDbMaintenanceProtocol.Phase.WAIT_B_HEALTH),
            executeGuardDbArmTransfer(GuardDbMaintenanceClient(transport), manifest, cleanProof()),
        )
        assertEquals(1, transport.longCommands.count { it.startsWith("GUARDPREPARE ") })
        assertEquals(1, transport.longCommands.count { it.startsWith("GUARDACTION ") })
        assertEquals(3, transport.shortCommands.count { it == "GUARDSTATUS" })
        assertEquals(3, transport.streamCommands.size)
        assertTrue(transport.streamCommands[0].contains(" A "))
        assertTrue(transport.streamCommands[1].contains(" B "))
        assertTrue(transport.streamCommands[2].contains(" SETTINGS "))
        assertTrue(transport.longReplies.isEmpty())
        assertTrue(transport.streamReplies.isEmpty())
    }

    @Test fun `reply loss with another session status remains indeterminate and stops`() = withManifest { manifest ->
        val otherSession = "9".repeat(64)
        val transport = ScriptedTransport(
            shortReplies = listOf(
                "OK GUARDSTATUS 1 STAGING $otherSession $boot NONE NONE 0 0 37 NONE NONE 1800000 1320000",
            ),
            longReplies = listOf(DaemonLongResult.Indeterminate),
        )

        val result = executeGuardDbArmTransfer(GuardDbMaintenanceClient(transport), manifest, cleanProof())
        assertTrue(result is GuardDbArmTransferResult.Indeterminate)
        assertEquals(1, transport.longCommands.size)
        assertEquals(0, transport.streamCommands.size)
        assertEquals(listOf("GUARDSTATUS"), transport.shortCommands)
    }

    @Test fun `capture hold statuses never settle or replay the startup mutation`() = withManifest { manifest ->
        listOf("CAPTURE_INTENT", "FAILED_NO_MUTATION").forEach { error ->
            val transport = ScriptedTransport(
                shortReplies = listOf(status(6, GuardDbMaintenanceProtocol.Phase.STAGING, error = error)),
                longReplies = listOf(
                    reply("GUARDPREPARE", 1, GuardDbMaintenanceProtocol.Phase.STAGING),
                    reply("GUARDDEFINE", 2, GuardDbMaintenanceProtocol.Phase.STAGING),
                    reply("GUARDDEFINE", 3, GuardDbMaintenanceProtocol.Phase.STAGING),
                    DaemonLongResult.Indeterminate,
                ),
                streamReplies = listOf(
                    streamReply("GUARDSTREAM", 4, GuardDbMaintenanceProtocol.Phase.STAGING),
                    streamReply("GUARDSTREAM", 5, GuardDbMaintenanceProtocol.Phase.STAGING),
                    streamReply("GUARDSTREAM", 6, GuardDbMaintenanceProtocol.Phase.STAGING),
                ),
            )

            assertTrue(executeGuardDbArmTransfer(
                GuardDbMaintenanceClient(transport), manifest, cleanProof(),
            ) is GuardDbArmTransferResult.Indeterminate)
            assertEquals(error, 1, transport.longCommands.count { it.startsWith("GUARDACTION ") })
            assertEquals(error, listOf("GUARDSTATUS"), transport.shortCommands)
        }
    }

    @Test fun `capture settles exact native prepared and autonomous B successor phases without replay`() =
        withManifest { manifest ->
            data class Scenario(
                val reply: DaemonLongResult,
                val status: String?,
                val generation: Long,
                val phase: GuardDbMaintenanceProtocol.Phase,
            )

            listOf(
                Scenario(
                    reply("GUARDACTION", 7, GuardDbMaintenanceProtocol.Phase.PREPARED),
                    null,
                    7,
                    GuardDbMaintenanceProtocol.Phase.PREPARED,
                ),
                Scenario(
                    DaemonLongResult.Indeterminate,
                    status(7, GuardDbMaintenanceProtocol.Phase.PREPARED),
                    7,
                    GuardDbMaintenanceProtocol.Phase.PREPARED,
                ),
                Scenario(
                    DaemonLongResult.Indeterminate,
                    status(8, GuardDbMaintenanceProtocol.Phase.SUBMITTED_B),
                    8,
                    GuardDbMaintenanceProtocol.Phase.SUBMITTED_B,
                ),
                Scenario(
                    DaemonLongResult.Indeterminate,
                    status(9, GuardDbMaintenanceProtocol.Phase.WAIT_B_HEALTH, GuardDbMaintenanceProtocol.Role.B),
                    9,
                    GuardDbMaintenanceProtocol.Phase.WAIT_B_HEALTH,
                ),
            ).forEach { scenario ->
                val transport = ScriptedTransport(
                    shortReplies = listOfNotNull(scenario.status),
                    longReplies = listOf(
                        reply("GUARDPREPARE", 1, GuardDbMaintenanceProtocol.Phase.STAGING),
                        reply("GUARDDEFINE", 2, GuardDbMaintenanceProtocol.Phase.STAGING),
                        reply("GUARDDEFINE", 3, GuardDbMaintenanceProtocol.Phase.STAGING),
                        scenario.reply,
                    ),
                    streamReplies = listOf(
                        streamReply("GUARDSTREAM", 4, GuardDbMaintenanceProtocol.Phase.STAGING),
                        streamReply("GUARDSTREAM", 5, GuardDbMaintenanceProtocol.Phase.STAGING),
                        streamReply("GUARDSTREAM", 6, GuardDbMaintenanceProtocol.Phase.STAGING),
                    ),
                )

                assertEquals(
                    scenario.phase.name,
                    GuardDbArmTransferResult.Submitted(scenario.generation, scenario.phase),
                    executeGuardDbArmTransfer(GuardDbMaintenanceClient(transport), manifest, cleanProof()),
                )
                assertEquals(
                    scenario.phase.name,
                    1,
                    transport.longCommands.count { it.startsWith("GUARDACTION ") },
                )
                assertEquals(
                    scenario.phase.name,
                    if (scenario.status == null) 0 else 1,
                    transport.shortCommands.count { it == "GUARDSTATUS" },
                )
                assertTrue(scenario.phase.name, transport.longReplies.isEmpty())
            }
        }

    @Test fun `invalid clean proof refuses before any helper mutation`() = withManifest { manifest ->
        val transport = ScriptedTransport()
        val result = executeGuardDbArmTransfer(
            GuardDbMaintenanceClient(transport),
            manifest,
            cleanProof().copy(settingsSemanticSha256 = ""),
        )
        assertTrue(result is GuardDbArmTransferResult.InvalidProof)
        assertTrue(transport.longCommands.isEmpty())
        assertTrue(transport.streamCommands.isEmpty())
        assertTrue(transport.shortCommands.isEmpty())
    }

    private fun cleanProof() = CleanDatabaseProof(
        databaseBytes = 4096,
        sha256 = "c".repeat(64),
        userVersion = 14,
        appStateRows = 37,
        orderedAppStateSha256 = "d".repeat(64),
        settingsSemanticSha256 = "e".repeat(64),
    )

    private fun status(
        generation: Long,
        phase: GuardDbMaintenanceProtocol.Phase,
        role: GuardDbMaintenanceProtocol.Role? = null,
        error: String = "NONE",
    ): String {
        val identity = when (role) {
            GuardDbMaintenanceProtocol.Role.A -> "A $aSha 568 14"
            GuardDbMaintenanceProtocol.Role.B -> "B $bSha 569 15"
            null -> "NONE NONE 0 0"
        }
        return "OK GUARDSTATUS $generation ${phase.name} $session $boot $identity 37 $error NONE 1800000 1320000"
    }

    private fun reply(
        verb: String,
        generation: Long,
        phase: GuardDbMaintenanceProtocol.Phase,
    ) = DaemonLongResult.Reply("OK $verb $generation ${phase.name}")

    private fun streamReply(
        verb: String,
        generation: Long,
        phase: GuardDbMaintenanceProtocol.Phase,
    ) = DaemonStreamResult.Reply("OK $verb $generation ${phase.name}")

    private inline fun withManifest(block: (GuardDbArmManifest) -> Unit) {
        val aFile = File.createTempFile("guard-arm-a-", ".apk").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val bFile = File.createTempFile("guard-arm-b-", ".apk").apply { writeBytes(byteArrayOf(5, 6, 7, 8)) }
        val settingsFile = File.createTempFile("guard-arm-settings-", ".v2").apply { writeText("S2\n") }
        try {
            val authority = GuardDbSettingsAuthority(
                GuardDbSettingsAuthority.VERSION,
                settingsFile,
                settingsFile.length(),
                AppInstaller.sha256(settingsFile),
            )
            block(
                GuardDbArmManifest(
                    session = session,
                    bootNonce = boot,
                    a = GuardDbMaintenanceProtocol.Candidate(
                        GuardDbMaintenanceProtocol.Role.A, aFile, 4, aSha, 568, 11, 14, 14,
                        authority.version, authority.bytes, authority.sha256,
                    ),
                    b = GuardDbMaintenanceProtocol.Candidate(
                        GuardDbMaintenanceProtocol.Role.B, bFile, 4, bSha, 569, 11, 15, 15,
                        authority.version, authority.bytes, authority.sha256,
                    ),
                    overallBudgetMs = 1_800_000L,
                    settingsAuthority = authority,
                    securityAuthorityEpoch = 42L,
                ),
            )
        } finally {
            aFile.delete()
            bFile.delete()
            settingsFile.delete()
        }
    }

    private class ScriptedTransport(
        shortReplies: List<String> = emptyList(),
        longReplies: List<DaemonLongResult> = emptyList(),
        streamReplies: List<DaemonStreamResult> = emptyList(),
    ) : GuardDbMaintenanceTransport {
        val shortReplies = ArrayDeque(shortReplies)
        val longReplies = ArrayDeque(longReplies)
        val streamReplies = ArrayDeque(streamReplies)
        val shortCommands = mutableListOf<String>()
        val longCommands = mutableListOf<String>()
        val streamCommands = mutableListOf<String>()

        override fun send(command: String): String? {
            shortCommands += command
            return if (shortReplies.isEmpty()) null else shortReplies.removeFirst()
        }

        override fun sendLong(command: String, timeoutMs: Long): DaemonLongResult {
            longCommands += command
            return if (longReplies.isEmpty()) DaemonLongResult.NotSubmitted else longReplies.removeFirst()
        }

        override fun sendFile(command: String, file: File, timeoutMs: Long): DaemonStreamResult {
            streamCommands += command
            return if (streamReplies.isEmpty()) DaemonStreamResult.NotSubmitted else streamReplies.removeFirst()
        }

        override fun sendBytesBounded(command: String, maxBytes: Long): ByteArray? = null
    }
}
