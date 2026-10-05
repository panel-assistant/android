package io.panelassistant.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files

class GuardDbStartupSentinelTest {
    @get:Rule val temporary = TemporaryFolder()

    private val sentinel = GuardDbStartupSentinel(
        state = GuardDbSentinelState.INTENT,
        session = "1".repeat(64),
        bootNonce = "2".repeat(64),
        aSha256 = "a".repeat(64),
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

    @Test fun `sentinel is exact candidate bound and checksum protected`() {
        val encoded = encodeGuardDbSentinel(sentinel)
        assertEquals(sentinel, parseGuardDbSentinel(encoded))
        assertNull(parseGuardDbSentinel(encoded.toString(Charsets.US_ASCII)
            .replace("A ${"a".repeat(64)}", "A ${"c".repeat(64)}").toByteArray()))
        assertNull(parseGuardDbSentinel(encoded.toString(Charsets.US_ASCII)
            .replace("SETTINGS 2 3 ${"c".repeat(64)}", "SETTINGS 2 4 ${"c".repeat(64)}").toByteArray()))
        assertNull(parseGuardDbSentinel(encoded.toString(Charsets.US_ASCII)
            .replace("SECURITY_EPOCH 42", "SECURITY_EPOCH 43").toByteArray()))
        assertNull(parseGuardDbSentinel(encoded.toString(Charsets.US_ASCII)
            .replace("HTTP_PORT 8888", "HTTP_PORT 08888").toByteArray()))
        assertNull(parseGuardDbSentinel(encoded + "TRAILING\n".toByteArray()))
    }

    @Test fun `boot nonce is stable only for one exact kernel boot identity`() {
        val first = guardDbBootNonce { "12345678-1234-1234-1234-1234567890ab" }
        assertEquals("dc69bc37c4ee0776fccf9c34ceca311b8f79458417c44d8f160c8c8956cc0d34", first)
        assertTrue(GuardDbMaintenanceProtocol.validSha256(requireNotNull(first)))
        assertEquals(first, guardDbBootNonce { "12345678-1234-1234-1234-1234567890ab" })
        assertFalse(first == guardDbBootNonce { "12345678-1234-1234-1234-1234567890ac" })
        assertNull(guardDbBootNonce { "not-a-boot-id" })
    }

    @Test fun `durable store distinguishes absence corruption intent and armed`() {
        val directory = temporary.newFolder("sentinel")
        val store = GuardDbSentinelStore(directory, syncDirectory = { true }, validateMarker = { it.isFile })
        assertTrue(store.load() is GuardDbSentinelLoad.Absent)
        assertFalse(store.write(sentinel.copy(state = GuardDbSentinelState.BASELINE_READY)))
        assertFalse(store.write(sentinel.copy(state = GuardDbSentinelState.ARMED)))
        assertTrue(store.write(sentinel))
        assertEquals(sentinel, (store.load() as GuardDbSentinelLoad.Valid).sentinel)
        assertFalse(store.promoteArmed(sentinel.session))
        assertTrue(store.promoteBaselineReady(sentinel.session))
        assertEquals(GuardDbSentinelState.BASELINE_READY,
            (store.load() as GuardDbSentinelLoad.Valid).sentinel.state)
        assertTrue(store.promoteArmed(sentinel.session))
        assertEquals(GuardDbSentinelState.ARMED, (store.load() as GuardDbSentinelLoad.Valid).sentinel.state)
        assertFalse(store.promoteBaselineReady(sentinel.session))
        assertFalse(store.clear("3".repeat(64)))
        assertTrue(store.clear(sentinel.session))
        assertTrue(store.load() is GuardDbSentinelLoad.Absent)

        assertTrue(store.write(sentinel))
        directory.resolve("guard-db-maintenance.v1").appendText("corrupt")
        assertTrue(store.load() is GuardDbSentinelLoad.Corrupt)
        assertFalse(store.clear(sentinel.session))
    }

    @Test fun `failed parent sync never reports sentinel durability`() {
        val store = GuardDbSentinelStore(
            temporary.newFolder("sync-failure"),
            syncDirectory = { false },
            validateMarker = { it.isFile },
        )
        assertFalse(store.write(sentinel))
    }

    @Test fun `restart resumes fsynced initial publication and exact forward promotions`() {
        val directory = temporary.newFolder("sentinel-restart")
        val pending = directory.resolve(".guard-db-maintenance.v1.pending")
        fun restarted() = GuardDbSentinelStore(
            directory,
            syncDirectory = { true },
            validateMarker = { it.isFile },
        )

        writeFsynced(pending, encodeGuardDbSentinel(sentinel))
        assertEquals(sentinel, (restarted().load() as GuardDbSentinelLoad.Valid).sentinel)
        assertFalse(pending.exists())

        val baselineReady = sentinel.copy(state = GuardDbSentinelState.BASELINE_READY)
        writeFsynced(pending, encodeGuardDbSentinel(baselineReady))
        assertEquals(baselineReady, (restarted().load() as GuardDbSentinelLoad.Valid).sentinel)
        assertFalse(pending.exists())

        val armed = sentinel.copy(state = GuardDbSentinelState.ARMED)
        writeFsynced(pending, encodeGuardDbSentinel(armed))
        assertEquals(armed, (restarted().load() as GuardDbSentinelLoad.Valid).sentinel)
        assertFalse(pending.exists())
    }

    @Test fun `mismatched pending promotion is fail closed and preserved`() {
        val directory = temporary.newFolder("sentinel-pending-mismatch")
        val store = GuardDbSentinelStore(directory, syncDirectory = { true }, validateMarker = { it.isFile })
        assertTrue(store.write(sentinel))
        val pending = directory.resolve(".guard-db-maintenance.v1.pending")
        writeFsynced(
            pending,
            encodeGuardDbSentinel(sentinel.copy(
                state = GuardDbSentinelState.BASELINE_READY,
                securityAuthorityEpoch = sentinel.securityAuthorityEpoch + 1L,
            )),
        )

        assertTrue(store.load() is GuardDbSentinelLoad.Corrupt)
        assertTrue(pending.isFile)
        assertFalse(store.promoteBaselineReady(sentinel.session))
        assertTrue(pending.isFile)
    }

    @Test fun `ambiguous jump and downgrade pending promotions are fail closed`() {
        listOf(
            GuardDbSentinelState.INTENT to GuardDbSentinelState.ARMED,
            GuardDbSentinelState.ARMED to GuardDbSentinelState.BASELINE_READY,
        ).forEachIndexed { index, (currentState, pendingState) ->
            val directory = temporary.newFolder("sentinel-pending-order-$index")
            val store = GuardDbSentinelStore(
                directory,
                syncDirectory = { true },
                validateMarker = { it.isFile },
            )
            assertTrue(store.write(sentinel))
            if (currentState == GuardDbSentinelState.ARMED) {
                assertTrue(store.promoteBaselineReady(sentinel.session))
                assertTrue(store.promoteArmed(sentinel.session))
            }
            val pending = directory.resolve(".guard-db-maintenance.v1.pending")
            writeFsynced(pending, encodeGuardDbSentinel(sentinel.copy(state = pendingState)))

            assertTrue(store.load() is GuardDbSentinelLoad.Corrupt)
            assertTrue(pending.isFile)
        }
    }

    @Test fun `malformed pending sentinel is fail closed and preserved`() {
        val directory = temporary.newFolder("sentinel-pending-malformed")
        val pending = directory.resolve(".guard-db-maintenance.v1.pending")
        writeFsynced(pending, "malformed\n".toByteArray())
        val store = GuardDbSentinelStore(directory, syncDirectory = { true }, validateMarker = { it.isFile })

        assertTrue(store.load() is GuardDbSentinelLoad.Corrupt)
        assertFalse(store.write(sentinel))
        assertTrue(pending.isFile)
    }

    @Test fun `dangling marker is corrupt and cannot be cleared as absent`() {
        val directory = temporary.newFolder("sentinel-dangling")
        val marker = directory.resolve("guard-db-maintenance.v1").toPath()
        Files.createSymbolicLink(marker, directory.resolve("missing-sentinel").toPath())
        val store = GuardDbSentinelStore(directory, syncDirectory = { true }, validateMarker = { it.isFile })

        assertTrue(store.load() is GuardDbSentinelLoad.Corrupt)
        assertFalse(store.write(sentinel))
        assertFalse(store.clear(sentinel.session))
        assertTrue(Files.isSymbolicLink(marker))
    }

    @Test fun `foreign pending entry is never deleted or followed`() {
        val directory = temporary.newFolder("sentinel-pending")
        val pending = directory.resolve(".guard-db-maintenance.v1.pending").toPath()
        Files.createSymbolicLink(pending, directory.resolve("missing-pending-sentinel").toPath())
        val store = GuardDbSentinelStore(directory, syncDirectory = { true }, validateMarker = { it.isFile })

        assertFalse(store.write(sentinel))
        assertTrue(Files.isSymbolicLink(pending))
        assertTrue(store.load() is GuardDbSentinelLoad.Corrupt)
        assertTrue(Files.isSymbolicLink(pending))
    }

    private fun writeFsynced(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
    }
}
