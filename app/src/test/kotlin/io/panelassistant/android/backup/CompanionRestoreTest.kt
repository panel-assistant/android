package io.panelassistant.android.backup

import java.io.File
import java.io.RandomAccessFile
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CompanionRestoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val pkg = "io.homeassistant.companion.android.minimal"

    private fun encoded(rel: String, value: ByteArray = "data".toByteArray()) =
        CompanionRestore.EncodedFile(rel, Base64.getEncoder().encodeToString(value))

    @Test fun completePlanIsDecodedToFilesAndOrderedBeforeExecution() {
        val result = CompanionRestore.plan(
            pkg,
            listOf(
                encoded("shared_prefs/session_0.xml", "session".toByteArray()),
                encoded("databases/HomeAssistantDB", "database".toByteArray()),
            ),
            setOf(pkg),
            temporary.root,
        ) as CompanionRestore.PlanResult.Valid
        result.plan.use { plan ->
            assertEquals(
                listOf("databases/HomeAssistantDB", "shared_prefs/session_0.xml"),
                plan.files.map { it.relativePath },
            )
            assertArrayEquals("database".toByteArray(), plan.files.first().file.readBytes())
        }
        assertEquals(emptyList<File>(), temporary.root.listFiles()?.filter { it.exists() }.orEmpty())
    }

    @Test fun packageAndFileMetadataMustBeSafeSupportedUniqueAndDecodable() {
        val unsafePackage = CompanionRestore.plan(
                "bad;pkg",
                listOf(encoded(CompanionRestore.DATABASE_FILE)),
                setOf("bad;pkg"),
                temporary.root,
            ) as CompanionRestore.PlanResult.Invalid
        assertEquals("companion-unsupported-package", unsafePackage.presentation?.code)
        assertTrue(CompanionRestore.plan(pkg, emptyList(), setOf(pkg), temporary.root) is CompanionRestore.PlanResult.Invalid)
        val unsafeFile = CompanionRestore.plan(
            pkg,
            listOf(encoded("../x")),
            setOf(pkg),
            temporary.root,
        ) as CompanionRestore.PlanResult.Invalid
        assertEquals("companion-payload-invalid", unsafeFile.presentation?.code)
        assertTrue(
            CompanionRestore.plan(
                pkg,
                listOf(encoded(CompanionRestore.DATABASE_FILE), encoded(CompanionRestore.DATABASE_FILE)),
                setOf(pkg),
                temporary.root,
            ) is CompanionRestore.PlanResult.Invalid,
        )
        assertTrue(
            CompanionRestore.plan(
                pkg,
                listOf(CompanionRestore.EncodedFile(CompanionRestore.DATABASE_FILE, "%%%")),
                setOf(pkg),
                temporary.root,
            ) is CompanionRestore.PlanResult.Invalid,
        )
        assertEquals(emptyList<File>(), temporary.root.listFiles()?.filter { it.exists() }.orEmpty())
    }

    @Test fun decodedLimitsAreInclusiveAndFailureCleansEveryStagedFile() {
        val atLimit = ByteArray(CompanionRestore.MAX_PREFERENCE_BYTES.toInt()) { 7 }
        val valid = CompanionRestore.plan(
            pkg,
            listOf(encoded("shared_prefs/session_0.xml", atLimit)),
            setOf(pkg),
            temporary.root,
        ) as CompanionRestore.PlanResult.Valid
        valid.plan.use { assertEquals(CompanionRestore.MAX_PREFERENCE_BYTES, it.files.single().size) }

        val oversized = ByteArray((CompanionRestore.MAX_PREFERENCE_BYTES + 1L).toInt()) { 9 }
        assertTrue(
            CompanionRestore.plan(
                pkg,
                listOf(
                    encoded("shared_prefs/session_0.xml", "first".toByteArray()),
                    encoded("shared_prefs/integration_0.xml", oversized),
                ),
                setOf(pkg),
                temporary.root,
            ) is CompanionRestore.PlanResult.Invalid,
        )
        assertEquals(emptyList<File>(), temporary.root.listFiles()?.filter { it.exists() }.orEmpty())
    }

    @Test fun alreadyStagedFileBoundsAreCheckedWithoutReadingIntoMemory() {
        val sparse = temporary.newFile("large.db")
        RandomAccessFile(sparse, "rw").use { it.setLength(CompanionRestore.MAX_DATABASE_BYTES + 1L) }
        val result = CompanionRestore.planFiles(
            pkg,
            listOf(CompanionRestore.FilePayload(CompanionRestore.DATABASE_FILE, sparse, deleteOnClose = false)),
            setOf(pkg),
        )
        assertTrue(result is CompanionRestore.PlanResult.Invalid)
        assertTrue("caller-owned files are retained", sparse.exists())
    }
}
