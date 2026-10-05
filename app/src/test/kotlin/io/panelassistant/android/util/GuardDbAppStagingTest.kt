package io.panelassistant.android.util

import android.content.ContextWrapper
import io.panelassistant.android.http.PendingUploadStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GuardDbAppStagingTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `mkdir copy inspection move and directory sync failures preserve pending source`() {
        assertFailurePreservesPending(directory = temporary.newFile("not-a-directory"))
        assertFailurePreservesPending(copy = { _, _ -> false })
        assertFailurePreservesPending(inspect = { null })
        assertFailurePreservesPending(move = { _, _ -> false })
        assertFailurePreservesPending(sync = { false })
    }

    @Test fun `only durable role copy finalizes pending claim and deletes cache copy`() {
        val directory = temporary.newFolder("role-success")
        val (store, source) = pending("success-token", "success-upload.apk")
        val staging = GuardDbAppStaging(
            directory = directory,
            inspect = ::inspection,
            syncDirectory = { true },
            validateFile = { it.isFile },
        )

        val candidate = staging.claim(GuardDbMaintenanceProtocol.Role.A, store, "success-token")

        assertNotNull(candidate)
        assertTrue(requireNotNull(candidate).file.isFile)
        assertFalse(source.exists())
        assertNull(store.peek("success-token"))
    }

    @Test fun `existing role custody is never overwritten by a new pending claim`() {
        val directory = temporary.newFolder("existing-role")
        File(directory, "guard-db-candidate-a.apk").writeText("existing")
        val (store, source) = pending("replacement-token", "replacement-upload.apk")
        val staging = GuardDbAppStaging(
            directory,
            ::inspection,
            syncDirectory = { true },
            validateFile = { it.isFile },
        )

        assertNull(staging.claim(GuardDbMaintenanceProtocol.Role.A, store, "replacement-token"))
        assertTrue(source.exists())
        assertNotNull(store.peek("replacement-token"))
        assertTrue(File(directory, "guard-db-candidate-a.apk").readText() == "existing")
    }

    @Test fun `the real staging never clears a pair a sentinel or prepared-arm record references`() {
        val files = temporary.newFolder("files")
        val noBackup = temporary.newFolder("no_backup")
        val context = object : ContextWrapper(null) {
            override fun getFilesDir() = files
            override fun getNoBackupFilesDir() = noBackup
        }
        fun stagePair() = listOf("a", "b").map { File(files, "guard-db-candidate-$it.apk").apply { writeText(it) } }
        val sessionRecords = listOf(
            "guard-db-maintenance.v1",
            ".guard-db-maintenance.v1.pending",
            "guard-db-prepared-arm.v1",
            ".guard-db-prepared-arm.v1.pending",
        )

        sessionRecords.forEach { name ->
            val record = File(noBackup, name).apply { writeText("unparseable") }
            val pair = stagePair()
            assertFalse(guardDbAppStaging(context).clear())
            pair.forEach { assertTrue("$name references ${it.name}", it.exists()) }
            record.delete()
        }

        val pair = stagePair()
        guardDbAppStaging(context).clear()
        pair.forEach { assertFalse("nothing references ${it.name}", it.exists()) }
    }

    private fun assertFailurePreservesPending(
        directory: File = temporary.newFolder(),
        inspect: (File) -> GuardDbCandidateInspection? = ::inspection,
        sync: (File) -> Boolean = { true },
        copy: (File, File) -> Boolean = { source, destination ->
            source.copyTo(destination)
            true
        },
        move: (File, File) -> Boolean = { source, destination ->
            source.renameTo(destination)
        },
    ) {
        val token = "failure-${System.nanoTime()}"
        val (store, source) = pending(token, "$token.apk")
        val staging = GuardDbAppStaging(directory, inspect, sync, copy, move, validateFile = { it.isFile })

        assertNull(staging.claim(GuardDbMaintenanceProtocol.Role.A, store, token))
        assertTrue("sole upload must survive", source.exists())
        assertNotNull("same token must remain pending", store.peek(token))
    }

    private fun pending(token: String, name: String): Pair<PendingUploadStore, File> {
        val store = PendingUploadStore(newToken = { token }).apply { open() }
        val source = temporary.newFile(name).apply { writeText("candidate-bytes") }
        val lease = (store.begin() as PendingUploadStore.BeginResult.Granted).lease
        checkNotNull(store.stage(lease, source))
        return store to source
    }

    private fun inspection(file: File): GuardDbCandidateInspection = GuardDbCandidateInspection(
        bytes = file.length(),
        sha256 = AppInstaller.sha256(file),
        versionCode = 568L,
        signerSha256 = AppInstaller.HA_PANELD.certSha256,
        contractMinimum = 11,
        contractMaximum = 14,
        expectedSchema = 14,
        settingsAuthorityVersion = GuardDbSettingsAuthority.VERSION,
        settingsAuthorityBytes = 3L,
        settingsAuthoritySha256 = "e".repeat(64),
    )
}
