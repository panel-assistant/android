package io.panelassistant.android.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import io.panelassistant.android.util.ByteLimitExceeded
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PanelBackupTest {
    @Test fun sealedEnvelopeReservationMatchesTheWireOverhead() {
        assertEquals(48L, PanelBackup.SEALED_OVERHEAD_BYTES)
        assertEquals(952L, PanelBackup.maxSealablePlaintextBytes(1_000L))
        assertEquals(0L, PanelBackup.maxSealablePlaintextBytes(47L))
    }

    @get:Rule val temporary = TemporaryFolder()
    private val plain = """{"kind":"ha-paneld-backup","config":{"mqtt_password":"s3cret"}}""".toByteArray()

    @Test fun sealThenOpenRoundTrips() {
        val bundle = PanelBackup.seal(plain, "correct horse")
        assertArrayEquals(plain, PanelBackup.open(bundle, "correct horse"))
    }

    @Test fun wrongPassphraseFailsToOpen() {
        val bundle = PanelBackup.seal(plain, "correct horse")
        assertNull(PanelBackup.open(bundle, "battery staple"))
    }

    @Test fun tamperedCiphertextIsRejected() {
        val bundle = PanelBackup.seal(plain, "pw")
        bundle[bundle.size - 1] = (bundle[bundle.size - 1].toInt() xor 0x01).toByte()
        assertNull(PanelBackup.open(bundle, "pw"))
    }

    @Test fun ciphertextIsNotPlaintext() {
        val bundle = PanelBackup.seal(plain, "pw")
        // the secret must not appear anywhere in the sealed bytes
        val hay = String(bundle, Charsets.ISO_8859_1)
        assertFalse(hay.contains("s3cret"))
        assertTrue(hay.startsWith("HPB1"))
    }

    @Test fun garbageBundleReturnsNullNotThrow() {
        assertNull(PanelBackup.open("not a bundle".toByteArray(), "pw"))
        assertNull(PanelBackup.open(ByteArray(3), "pw"))
    }

    @Test fun isSealedDistinguishesEncryptedFromPlain() {
        assertTrue(PanelBackup.isSealed(PanelBackup.seal(plain, "pw")))
        assertFalse(PanelBackup.isSealed("""{"kind":"ha-paneld-backup"}""".toByteArray()))
        assertFalse(PanelBackup.isSealed(ByteArray(2)))
    }

    @Test fun eachSealUsesFreshSaltAndIv() {
        val a = PanelBackup.seal(plain, "pw")
        val b = PanelBackup.seal(plain, "pw")
        // same plaintext + passphrase must not produce identical bundles (random salt/iv)
        assertFalse(a.contentEquals(b))
    }

    @Test fun streamingSealOpenRoundTripsAndAuthenticatesBeforeSuccess() {
        val sealed = ByteArrayOutputStream()
        PanelBackup.seal(ByteArrayInputStream(plain), sealed, "pw")
        val opened = ByteArrayOutputStream()
        assertTrue(PanelBackup.open(ByteArrayInputStream(sealed.toByteArray()), opened, "pw", 1024))
        assertArrayEquals(plain, opened.toByteArray())

        val tampered = sealed.toByteArray().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertFalse(PanelBackup.open(ByteArrayInputStream(tampered), ByteArrayOutputStream(), "pw", 1024))
        assertFalse(
            PanelBackup.open(
                ByteArrayInputStream(sealed.toByteArray()),
                ByteArrayOutputStream(),
                "wrong password",
                1024,
            ),
        )
    }

    @Test fun streamingOpenRejectsCiphertextTamperingAndEveryTruncatedTag() {
        val sealed = PanelBackup.seal(ByteArray(32 * 1024) { (it % 251).toByte() }, "pw")
        val ciphertextOffset = 4 + 16 + 12
        val tampered = sealed.copyOf().also {
            it[ciphertextOffset + 123] = (it[ciphertextOffset + 123].toInt() xor 0x40).toByte()
        }
        assertFalse(PanelBackup.open(ByteArrayInputStream(tampered), ByteArrayOutputStream(), "pw", 64 * 1024L))

        repeat(16) { removedTagBytes ->
            val truncated = sealed.copyOf(sealed.size - removedTagBytes - 1)
            assertFalse(
                "accepted a bundle missing ${removedTagBytes + 1} authentication-tag bytes",
                PanelBackup.open(ByteArrayInputStream(truncated), ByteArrayOutputStream(), "pw", 64 * 1024L),
            )
        }
    }

    @Test fun streamingOpenEnforcesPlaintextLimitBeforeAuthenticationCanSucceed() {
        val sealed = PanelBackup.seal(ByteArray(1025), "pw")
        assertThrows(ByteLimitExceeded::class.java) {
            PanelBackup.open(ByteArrayInputStream(sealed), ByteArrayOutputStream(), "pw", 1024)
        }

        val exact = ByteArrayOutputStream()
        assertTrue(PanelBackup.open(ByteArrayInputStream(sealed), exact, "pw", 1025))
        assertEquals(1025, exact.size())
    }

    @Test fun archiveRoundTripIsExactBoundedAndCleansFailedExtractions() {
        val source = temporary.newFile("source.db").apply { writeBytes(ByteArray(4096) { (it % 251).toByte() }) }
        val archive = temporary.newFile("backup.zip")
        archive.outputStream().use {
            PanelBackup.writeArchive(
                it,
                """{"kind":"ha-paneld-backup","schema":2}""",
                listOf(PanelBackup.ArchiveSource("companion/0", source)),
            )
        }
        assertEquals("""{"kind":"ha-paneld-backup","schema":2}""", PanelBackup.readManifest(archive, 1024))
        val extracted = temporary.newFile("extracted.db")
        assertTrue(PanelBackup.extractArchive(archive, listOf(PanelBackup.ArchiveTarget("companion/0", extracted, 4096))))
        assertArrayEquals(source.readBytes(), extracted.readBytes())

        val rejected = temporary.newFile("rejected.db")
        assertFalse(PanelBackup.extractArchive(archive, listOf(PanelBackup.ArchiveTarget("companion/0", rejected, 4095))))
        assertFalse(rejected.exists())
    }

    @Test fun archiveEnumerationRejectsMoreThanTheHardEntryLimit() {
        val archive = temporary.newFile("too-many-entries.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(PanelBackup.MANIFEST_ENTRY))
            zip.write("""{"kind":"ha-paneld-backup","schema":2}""".toByteArray())
            zip.closeEntry()
            repeat(PanelBackup.MAX_ARCHIVE_ENTRIES) { index ->
                zip.putNextEntry(ZipEntry("companion/$index"))
                zip.closeEntry()
            }
        }

        assertNull(PanelBackup.readManifest(archive, 1024))
    }

    @Test fun archiveCreationEnforcesTheSameManifestCeilingAsRestore() {
        val output = ByteArrayOutputStream()
        assertThrows(ByteLimitExceeded::class.java) {
            PanelBackup.writeArchive(
                output,
                "12345",
                emptyList(),
                maxManifestBytes = 4,
            )
        }
        assertEquals(0, output.size())
    }

    @Test fun archiveExtractionValidatesTheCompleteEntrySetAndAllowsExplicitEmptyText() {
        val empty = temporary.newFile("empty.txt")
        val payload = temporary.newFile("payload.txt").apply { writeText("payload") }
        val archive = temporary.newFile("multi.zip")
        archive.outputStream().use {
            PanelBackup.writeArchive(
                it,
                """{"kind":"ha-paneld-backup","schema":2}""",
                listOf(
                    PanelBackup.ArchiveSource("entity/empty.txt", empty),
                    PanelBackup.ArchiveSource("profiles/catalog.json", payload),
                ),
            )
        }
        val restoredEmpty = temporary.newFile("restored-empty.txt")
        assertTrue(
            PanelBackup.extractArchive(
                archive,
                listOf(PanelBackup.ArchiveTarget("entity/empty.txt", restoredEmpty, 16, allowEmpty = true)),
                setOf("entity/empty.txt", "profiles/catalog.json"),
            ),
        )
        assertEquals(0L, restoredEmpty.length())
        assertFalse(
            PanelBackup.extractArchive(
                archive,
                emptyList(),
                setOf("entity/empty.txt"),
            ),
        )
    }

    @Test fun artifactDeletesItsPrivateFile() {
        val file = temporary.newFile("artifact.hpb")
        PanelBackup.Artifact(file).close()
        assertFalse(file.exists())
    }
}
