package io.panelassistant.android.util

import android.database.sqlite.SQLiteDatabase
import io.panelassistant.android.dashboard.DatabaseCompatibility
import io.panelassistant.android.dashboard.DatabaseCompatibilityBoundary
import io.panelassistant.android.dashboard.DatabaseCompatibilityDecision
import io.panelassistant.android.dashboard.DatabaseFileInspection
import io.panelassistant.android.dashboard.DatabaseOwnerState
import io.panelassistant.android.dashboard.inspectRecoveryDatabaseIsolated
import io.panelassistant.android.dashboard.observeDatabaseCompatibility
import io.panelassistant.android.platform.DaemonLongResult
import io.panelassistant.android.platform.DaemonStreamResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking

class GuardDbStartupHealthTest {
    private val session = "1".repeat(64)
    private val boot = "2".repeat(64)
    private val apk = "a".repeat(64)
    private val ordered = "d".repeat(64)
    private val settings = "e".repeat(64)

    @Test fun `maintenance startup waits for foreground and closed proof before listening`() = runBlocking {
        val gate = GuardDbMaintenanceStartupGate(CoroutineScope(SupervisorJob()))
        val events = mutableListOf<String>()
        val acknowledge = { events += "proof"; events += "closed"; false }
        val listen: suspend () -> Unit = {
            assertEquals("proof must close before the maintenance listener", "closed", events.last())
            events += "listen"
        }
        val ordinary: suspend () -> Unit = { error("ordinary owner must remain fenced") }

        val premature = runCatching { gate.launch(acknowledge, listen, ordinary) { throw it } }.exceptionOrNull()
        assertTrue(premature is IllegalStateException)
        assertTrue(events.isEmpty())

        gate.promoted()
        gate.launch(acknowledge, listen, ordinary) { throw it }.join()
        assertEquals(listOf("proof", "closed", "listen"), events)
    }

    @Test fun `cleared sentinel resumes ordinary process without opening maintenance listener`() = runBlocking {
        val gate = GuardDbMaintenanceStartupGate(CoroutineScope(SupervisorJob())).apply { promoted() }
        val events = mutableListOf<String>()
        gate.launch(
            acknowledge = { events += "cleared"; true },
            startMaintenance = { error("cleared sentinel must not bind maintenance listener") },
            resumeOrdinary = { events += "restart" },
            onFailure = { throw it },
        ).join()
        assertEquals(listOf("cleared", "restart"), events)
    }

    @Test fun `failed startup proof leaves both control planes closed`() = runBlocking {
        val gate = GuardDbMaintenanceStartupGate(CoroutineScope(SupervisorJob())).apply { promoted() }
        var maintenanceStarts = 0
        var ordinaryStarts = 0
        var failure: Throwable? = null
        gate.launch(
            acknowledge = { error("status and proof could not be reconciled") },
            startMaintenance = { maintenanceStarts++ },
            resumeOrdinary = { ordinaryStarts++ },
            onFailure = { failure = it },
        ).join()
        assertTrue(failure is IllegalStateException)
        assertEquals(0, maintenanceStarts)
        assertEquals(0, ordinaryStarts)
    }

    @Test fun `selected 60 MB recovery and primary inspection run off startup caller`() = runBlocking {
        val directory = kotlin.io.path.createTempDirectory("guard-db-large-recovery").toFile()
        try {
            val target = File(directory, "ha-paneld.db").apply { writeText("primary") }
            val recovery = File(directory, "ha-paneld.db.v14.premigrate")
            RandomAccessFile(recovery, "rw").use { it.setLength(60L * 1024 * 1024) }
            val scratch = File(directory, "scratch")
            val caller = Thread.currentThread()
            val gate = GuardDbMaintenanceStartupGate(CoroutineScope(SupervisorJob())).apply { promoted() }
            var primaryInspected = false
            var recoveryInspected = false
            var decision: DatabaseCompatibilityDecision? = null
            var failure: Throwable? = null
            gate.launch(
                acknowledge = {
                    val observation = observeDatabaseCompatibility(
                        target = target,
                        boundary = DatabaseCompatibilityBoundary(1, "ha-paneld.db", 11, 14),
                        inspectDatabase = {
                            assertTrue("primary quick_check must be off startup caller", Thread.currentThread() !== caller)
                            primaryInspected = true
                            DatabaseFileInspection(15, true)
                        },
                        inspectRecoveryDatabase = { source ->
                            inspectRecoveryDatabaseIsolated(source, scratch) { copy ->
                                assertTrue("selected recovery inspection must be off startup caller", Thread.currentThread() !== caller)
                                assertEquals(60L * 1024 * 1024, copy.length())
                                recoveryInspected = true
                                DatabaseFileInspection(14, true)
                            }
                        },
                    )
                    decision = DatabaseCompatibility.decide(
                        DatabaseCompatibilityBoundary(1, "ha-paneld.db", 11, 14),
                        observation,
                        DatabaseOwnerState.RUNTIME_STARTUP,
                    )
                    false
                },
                startMaintenance = {},
                resumeOrdinary = { error("downgrade requires maintenance") },
                onFailure = { failure = it },
            ).join()
            assertEquals(null, failure)
            assertTrue(primaryInspected)
            assertTrue(recoveryInspected)
            assertTrue(decision is DatabaseCompatibilityDecision.Recover)
            assertEquals(60L * 1024 * 1024, recovery.length())
            assertTrue(scratch.listFiles().isNullOrEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun `proof failure and definite not-submitted remain retryable for same generation`() {
        val transport = HealthTransport()
        val client = GuardDbMaintenanceClient(transport)
        val attempts = GuardDbHealthAttemptGate()

        assertEquals(
            GuardDbHealthAttemptOutcome.RETRYABLE,
            submitGuardDbStartupHealth(client, waiting(), attempts) { null },
        )
        assertEquals(
            GuardDbHealthAttemptOutcome.RETRYABLE,
            submitGuardDbStartupHealth(client, waiting(), attempts) { proof() },
        )
        assertEquals(1, transport.commands.size)
    }

    @Test fun `reply loss after helper commit reconciles status and suppresses repeated onOpen`() {
        val transport = HealthTransport().apply {
            nextLong = DaemonLongResult.Indeterminate
            statusReply = statusLine(8L, GuardDbMaintenanceProtocol.Phase.B_HEALTHY)
        }
        val client = GuardDbMaintenanceClient(transport)
        val attempts = GuardDbHealthAttemptGate()

        assertEquals(
            GuardDbHealthAttemptOutcome.DURABLE,
            submitGuardDbStartupHealth(client, waiting(), attempts) { proof() },
        )
        assertEquals(
            GuardDbHealthAttemptOutcome.IN_FLIGHT,
            submitGuardDbStartupHealth(client, waiting(), attempts) { proof() },
        )
        assertEquals(1, transport.commands.size)
    }

    @Test fun `reply loss without durable phase advance permits a later retry`() {
        val transport = HealthTransport().apply {
            nextLong = DaemonLongResult.Indeterminate
            statusReply = statusLine(7L, GuardDbMaintenanceProtocol.Phase.WAIT_B_HEALTH)
        }
        val client = GuardDbMaintenanceClient(transport)
        val attempts = GuardDbHealthAttemptGate()

        assertEquals(
            GuardDbHealthAttemptOutcome.RETRYABLE,
            submitGuardDbStartupHealth(client, waiting(), attempts) { proof() },
        )
        transport.nextLong = DaemonLongResult.Reply("OK GUARDHEALTH 8 B_HEALTHY")
        assertEquals(
            GuardDbHealthAttemptOutcome.DURABLE,
            submitGuardDbStartupHealth(client, waiting(), attempts) { proof() },
        )
        assertEquals(2, transport.commands.size)
    }

    @Test fun `health command always binds semantic digests for helper-side comparison`() {
        val command = requireNotNull(guardDbStartupHealthCommand(waiting(), proof()))
        assertTrue(command.contains(" $ordered $settings PRESENT NA"))
    }

    @Test fun `A health requires the exact restored recovery proof for its phase`() {
        val waitingA = waiting().copy(
            phase = GuardDbMaintenanceProtocol.Phase.WAIT_A_HEALTH,
            role = GuardDbMaintenanceProtocol.Role.A,
            apkSha256 = apk,
            versionCode = 568L,
            schema = 14,
        )
        val restored = proof().copy(
            versionCode = 568L,
            schema = 14,
            probe = GuardDbMaintenanceProtocol.Probe.ABSENT,
            recoveryProof = GuardDbMaintenanceProtocol.RecoveryProof.RESTORED,
        )
        val restoredCommand = requireNotNull(guardDbStartupHealthCommand(waitingA, restored))
        assertTrue(restoredCommand.endsWith(" ABSENT RESTORED"))
        val missingProof = requireNotNull(guardDbStartupHealthCommand(
            waitingA,
            restored.copy(recoveryProof = GuardDbMaintenanceProtocol.RecoveryProof.NA),
        ))
        assertTrue(missingProof.contains(" FAIL "))
    }

    @Test fun `finalized handoff binds terminal status and the actually running A identity`() {
        val sentinel = GuardDbStartupSentinel(
            state = GuardDbSentinelState.ARMED,
            session = session,
            bootNonce = boot,
            aSha256 = apk,
            aVersionCode = 568L,
            aSchema = 14,
            bSha256 = "b".repeat(64),
            bVersionCode = 569L,
            bSchema = 15,
            settingsAuthorityVersion = GuardDbSettingsAuthority.VERSION,
            settingsAuthorityBytes = 3L,
            settingsAuthoritySha256 = "c".repeat(64),
            securityAuthorityEpoch = 42L,
            httpPort = 8888,
            hardened = true,
        )
        val status = GuardDbMaintenanceProtocol.Status(
            generation = 20L,
            phase = GuardDbMaintenanceProtocol.Phase.FINALIZED,
            session = session,
            bootNonce = boot,
            role = GuardDbMaintenanceProtocol.Role.A,
            apkSha256 = apk,
            versionCode = 568L,
            schema = 14,
            baselineAppStateCount = 37L,
            error = null,
            outcome = GuardDbMaintenanceProtocol.Outcome.CANARY_PASSED,
            overallDeadlineElapsedMs = 1_800_000L,
            forwardDeadlineElapsedMs = 1_320_000L,
        )
        val running = GuardDbRunningAppIdentity(apkSha256 = apk, versionCode = 568L)

        assertTrue(exactGuardDbFinalStatus(status, sentinel, running))
        GuardDbMaintenanceProtocol.Outcome.values()
            .filter { it.name.startsWith("ROLLED_BACK_") }
            .forEach { assertTrue(it.name, exactGuardDbFinalStatus(status.copy(outcome = it), sentinel, running)) }

        listOf(
            status.copy(phase = GuardDbMaintenanceProtocol.Phase.A_HEALTHY),
            status.copy(session = "3".repeat(64)),
            status.copy(bootNonce = "4".repeat(64)),
            status.copy(role = GuardDbMaintenanceProtocol.Role.B),
            status.copy(apkSha256 = "c".repeat(64)),
            status.copy(versionCode = 567L),
            status.copy(schema = 15),
            status.copy(outcome = GuardDbMaintenanceProtocol.Outcome.CANCELLED_NO_MUTATION),
            status.copy(outcome = GuardDbMaintenanceProtocol.Outcome.AMBIGUOUS),
        ).forEach { assertFalse(exactGuardDbFinalStatus(it, sentinel, running)) }
        assertFalse(exactGuardDbFinalStatus(status, sentinel, running.copy(apkSha256 = "c".repeat(64))))
        assertFalse(exactGuardDbFinalStatus(status, sentinel, running.copy(versionCode = 567L)))
    }

    @Test fun `closed canonical proof is returned only after collect checkpoint close and stability`() {
        val events = mutableListOf<String>()

        val result = collectClosedCanonicalGuardDbProof(
            collect = { events += "collect"; "proof" },
            checkpoint = { events += "checkpoint"; true },
            close = { events += "close" },
            stable = { events += "stable"; true },
        )

        assertEquals("proof", result)
        assertEquals(listOf("collect", "checkpoint", "close", "stable"), events)
    }

    @Test fun `checkpoint refusal closes the owner and returns no proof without stability read`() {
        listOf(
            listOf(1L, 0L, 0L) to false,
            listOf(0L, 1L, 0L) to false,
            listOf(0L, 0L, 1L) to false,
            listOf(0L, 0L, 0L) to true,
        ).forEach { (values, additionalRow) ->
            val events = mutableListOf<String>()
            val result = collectClosedCanonicalGuardDbProof(
                collect = { events += "collect"; "proof" },
                checkpoint = {
                    events += "checkpoint"
                    exactGuardDbTruncateCheckpoint(values, additionalRow)
                },
                close = { events += "close" },
                stable = { events += "stable"; true },
            )
            assertEquals(values.toString(), null, result)
            assertEquals(listOf("collect", "checkpoint", "close"), events)
        }
    }

    @Test fun `close or post-close stability failure returns no observed proof`() {
        val closeEvents = mutableListOf<String>()
        assertEquals(null, collectClosedCanonicalGuardDbProof(
            collect = { closeEvents += "collect"; "proof" },
            checkpoint = { closeEvents += "checkpoint"; true },
            close = { closeEvents += "close"; error("close failed") },
            stable = { closeEvents += "stable"; true },
        ))
        assertEquals(listOf("collect", "checkpoint", "close"), closeEvents)

        val unstableEvents = mutableListOf<String>()
        assertEquals(null, collectClosedCanonicalGuardDbProof(
            collect = { unstableEvents += "collect"; "proof" },
            checkpoint = { unstableEvents += "checkpoint"; true },
            close = { unstableEvents += "close" },
            stable = { unstableEvents += "stable"; false },
        ))
        assertEquals(listOf("collect", "checkpoint", "close", "stable"), unstableEvents)
    }

    @Test fun `missing or failed collection still closes and never checkpoints or stabilizes`() {
        listOf<() -> String?>(
            { null },
            { error("collection failed") },
        ).forEach { collect ->
            val events = mutableListOf<String>()
            assertEquals(null, collectClosedCanonicalGuardDbProof(
                collect = { events += "collect"; collect() },
                checkpoint = { events += "checkpoint"; true },
                close = { events += "close" },
                stable = { events += "stable"; true },
            ))
            assertEquals(listOf("collect", "close"), events)
        }
    }

    @Test fun `truncate checkpoint accepts only one exact all-zero row`() {
        assertTrue(exactGuardDbTruncateCheckpoint(listOf(0L, 0L, 0L), hasAdditionalRow = false))
        listOf(
            null,
            emptyList(),
            listOf(0L, 0L),
            listOf(0L, 0L, 0L, 0L),
            listOf(1L, 0L, 0L),
            listOf(0L, 1L, 0L),
            listOf(0L, 0L, 1L),
        ).forEach { assertFalse(exactGuardDbTruncateCheckpoint(it, hasAdditionalRow = false)) }
        assertFalse(exactGuardDbTruncateCheckpoint(listOf(0L, 0L, 0L), hasAdditionalRow = true))
    }

    @Test fun `exact A refusal raw open preserves WAL through closed checkpoint proof`() {
        val events = mutableListOf<String>()
        val openedDatabase = Any()

        val canonical = runGuardDbRefusalCheckpoint(
            open = { flags ->
                events += "open"
                assertEquals(
                    SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS or
                        SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING,
                    flags,
                )
                openedDatabase
            },
            checkpoint = { database ->
                events += "checkpoint"
                assertSame(openedDatabase, database)
                exactGuardDbTruncateCheckpoint(listOf(0L, 0L, 0L), hasAdditionalRow = false)
            },
            close = { database ->
                events += "close"
                assertSame(openedDatabase, database)
            },
            stable = { events += "stable"; true },
        )

        assertTrue(canonical)
        assertEquals(listOf("open", "checkpoint", "close", "stable"), events)
    }

    private fun waiting() = GuardDbMaintenanceProtocol.Status(
        generation = 7L,
        phase = GuardDbMaintenanceProtocol.Phase.WAIT_B_HEALTH,
        session = session,
        bootNonce = boot,
        role = GuardDbMaintenanceProtocol.Role.B,
        apkSha256 = apk,
        versionCode = 569L,
        schema = 15,
        baselineAppStateCount = 37L,
        error = null,
    )

    private fun proof() = GuardDbStartupProof(
        apkSha256 = apk,
        versionCode = 569L,
        schema = 15,
        quickCheckOk = true,
        appStateCount = 37L,
        orderedAppStateSha256 = ordered,
        settingsSemanticSha256 = settings,
        probe = GuardDbMaintenanceProtocol.Probe.PRESENT,
        recoveryProof = GuardDbMaintenanceProtocol.RecoveryProof.NA,
    )

    private fun statusLine(generation: Long, phase: GuardDbMaintenanceProtocol.Phase): String =
        "OK GUARDSTATUS $generation $phase $session $boot B $apk 569 15 37 NONE NONE 1800000 1320000"

    private class HealthTransport : GuardDbMaintenanceTransport {
        val commands = mutableListOf<String>()
        var nextLong: DaemonLongResult = DaemonLongResult.NotSubmitted
        var statusReply: String? = null

        override fun send(command: String): String? = if (command == "GUARDSTATUS") statusReply else null
        override fun sendLong(command: String, timeoutMs: Long): DaemonLongResult {
            commands += command
            return nextLong
        }
        override fun sendFile(command: String, file: File, timeoutMs: Long): DaemonStreamResult =
            DaemonStreamResult.NotSubmitted
        override fun sendBytesBounded(command: String, maxBytes: Long): ByteArray? = null
    }
}
