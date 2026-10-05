package io.panelassistant.android.util

import io.panelassistant.android.persistence.CleanDatabaseProof
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardDbPreparedArmTest {
    @Test fun `prepared clean proof round trip binds candidates baseline boot budget settings and security epoch`() {
        withManifest { manifest ->
            val proof = CleanDatabaseProof(
                databaseBytes = 4096L,
                sha256 = "c".repeat(64),
                userVersion = 14,
                appStateRows = 37L,
                orderedAppStateSha256 = "d".repeat(64),
                settingsSemanticSha256 = "e".repeat(64),
            )
            val prepared = GuardDbPreparedArm.create(manifest, proof)
            assertTrue(prepared.matches(manifest))
            assertEquals(proof, prepared.proof())
            assertEquals(manifest.settingsAuthority.sha256, prepared.settingsAuthoritySha256)
            assertEquals("1800000", prepared.canonical().split('\u0000')[21])
            assertEquals("42", prepared.canonical().split('\u0000')[25])

            val encoded = encodeGuardDbPreparedArm(prepared)
            assertEquals(prepared, parseGuardDbPreparedArm(encoded))
            val record = encoded.toString(Charsets.US_ASCII)
            assertTrue(record.indexOf("SETTINGS ") < record.indexOf("SECURITY_EPOCH 42"))
            assertTrue(record.indexOf("SECURITY_EPOCH 42") < record.indexOf("OVERALL_BUDGET_MS "))
            assertNull(parseGuardDbPreparedArm(encoded.toString(Charsets.US_ASCII)
                .replace(
                    "SETTINGS ${prepared.settingsAuthorityVersion} ${prepared.settingsAuthorityBytes}",
                    "SETTINGS ${prepared.settingsAuthorityVersion} ${prepared.settingsAuthorityBytes + 1L}",
                )
                .toByteArray(Charsets.US_ASCII)))
            assertNull(parseGuardDbPreparedArm(encoded.toString(Charsets.US_ASCII)
                .replace("SECURITY_EPOCH 42", "SECURITY_EPOCH 43")
                .toByteArray(Charsets.US_ASCII)))
            assertNull(parseGuardDbPreparedArm(encoded.toString(Charsets.US_ASCII)
                .replace("OVERALL_BUDGET_MS 1800000", "OVERALL_BUDGET_MS 1799999")
                .toByteArray(Charsets.US_ASCII)))
        }
    }

    @Test fun `dangling prepared arm is corrupt and cannot be cleared`() {
        withManifest { manifest ->
            val directory = Files.createTempDirectory("guard-prepared-dangling-").toFile()
            try {
                val record = directory.resolve("guard-db-prepared-arm.v1").toPath()
                Files.createSymbolicLink(record, directory.resolve("missing-prepared-arm").toPath())
                val store = GuardDbPreparedArmStore(
                    directory,
                    syncDirectory = { true },
                    validateFile = { it.isFile },
                )

                assertTrue(store.load() is GuardDbPreparedArmLoad.Corrupt)
                assertFalse(store.clear(manifest.session))
                assertFalse(store.write(prepared(manifest)))
                assertTrue(Files.isSymbolicLink(record))
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    @Test fun `prepared arm publication is idempotent but never replaces another proof`() {
        withManifest { manifest ->
            val directory = Files.createTempDirectory("guard-prepared-idempotent-").toFile()
            try {
                val store = GuardDbPreparedArmStore(
                    directory,
                    syncDirectory = { true },
                    validateFile = { it.isFile },
                )
                val first = prepared(manifest)
                assertTrue(store.write(first))
                assertTrue(store.write(first))
                assertFalse(store.write(first.copy(session = "9".repeat(64))))
                assertEquals(first, (store.load() as GuardDbPreparedArmLoad.Valid).prepared)
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    @Test fun `restart resumes an exact fsynced initial prepared publication`() {
        withManifest { manifest ->
            val directory = Files.createTempDirectory("guard-prepared-restart-").toFile()
            try {
                val expected = prepared(manifest)
                val pending = directory.resolve(".guard-db-prepared-arm.v1.pending")
                writeFsynced(pending, encodeGuardDbPreparedArm(expected))

                val restarted = GuardDbPreparedArmStore(
                    directory,
                    syncDirectory = { true },
                    validateFile = { it.isFile },
                )
                assertEquals(expected, (restarted.load() as GuardDbPreparedArmLoad.Valid).prepared)
                assertFalse(pending.exists())
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    @Test fun `pending prepared overwrite is fail closed and preserved`() {
        withManifest { manifest ->
            val directory = Files.createTempDirectory("guard-prepared-pending-mismatch-").toFile()
            try {
                val store = GuardDbPreparedArmStore(
                    directory,
                    syncDirectory = { true },
                    validateFile = { it.isFile },
                )
                val first = prepared(manifest)
                assertTrue(store.write(first))
                val pending = directory.resolve(".guard-db-prepared-arm.v1.pending")
                writeFsynced(pending, encodeGuardDbPreparedArm(first.copy(session = "9".repeat(64))))

                assertTrue(store.load() is GuardDbPreparedArmLoad.Corrupt)
                assertTrue(pending.isFile)
                assertFalse(store.write(first))
                assertTrue(pending.isFile)
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    @Test fun `malformed pending prepared proof is fail closed and preserved`() {
        withManifest { manifest ->
            val directory = Files.createTempDirectory("guard-prepared-pending-malformed-").toFile()
            try {
                val pending = directory.resolve(".guard-db-prepared-arm.v1.pending")
                writeFsynced(pending, "malformed\n".toByteArray())
                val store = GuardDbPreparedArmStore(
                    directory,
                    syncDirectory = { true },
                    validateFile = { it.isFile },
                )

                assertTrue(store.load() is GuardDbPreparedArmLoad.Corrupt)
                assertFalse(store.write(prepared(manifest)))
                assertTrue(pending.isFile)
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    @Test fun `foreign prepared pending entry is never deleted or followed`() {
        withManifest { manifest ->
            val directory = Files.createTempDirectory("guard-prepared-pending-").toFile()
            try {
                val pending = directory.resolve(".guard-db-prepared-arm.v1.pending").toPath()
                Files.createSymbolicLink(pending, directory.resolve("missing-pending-prepared").toPath())
                val store = GuardDbPreparedArmStore(
                    directory,
                    syncDirectory = { true },
                    validateFile = { it.isFile },
                )

                assertFalse(store.write(prepared(manifest)))
                assertTrue(Files.isSymbolicLink(pending))
                assertTrue(store.load() is GuardDbPreparedArmLoad.Corrupt)
                assertTrue(Files.isSymbolicLink(pending))
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    private fun prepared(manifest: GuardDbArmManifest) = GuardDbPreparedArm.create(
        manifest,
        CleanDatabaseProof(
            databaseBytes = 4096L,
            sha256 = "c".repeat(64),
            userVersion = 14,
            appStateRows = 37L,
            orderedAppStateSha256 = "d".repeat(64),
            settingsSemanticSha256 = "e".repeat(64),
        ),
    )

    private fun writeFsynced(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
    }

    private inline fun withManifest(block: (GuardDbArmManifest) -> Unit) {
        val aFile = File.createTempFile("guard-prepared-a-", ".apk")
        val bFile = File.createTempFile("guard-prepared-b-", ".apk")
        val settingsFile = File.createTempFile("guard-prepared-settings-", ".v2").apply { writeText("S2\n") }
        try {
            val authority = GuardDbSettingsAuthority(
                GuardDbSettingsAuthority.VERSION,
                settingsFile,
                settingsFile.length(),
                AppInstaller.sha256(settingsFile),
            )
            val a = GuardDbMaintenanceProtocol.Candidate(
                GuardDbMaintenanceProtocol.Role.A, aFile, 4L, "a".repeat(64), 568L, 11, 14, 14,
                authority.version, authority.bytes, authority.sha256,
            )
            val b = GuardDbMaintenanceProtocol.Candidate(
                GuardDbMaintenanceProtocol.Role.B, bFile, 4L, "b".repeat(64), 569L, 11, 15, 15,
                authority.version, authority.bytes, authority.sha256,
            )
            block(GuardDbArmManifest(
                "1".repeat(64),
                "2".repeat(64),
                a,
                b,
                1_800_000L,
                authority,
                42L,
            ))
        } finally {
            aFile.delete()
            bFile.delete()
            settingsFile.delete()
        }
    }
}
