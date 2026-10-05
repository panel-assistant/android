package io.panelassistant.android.control

import io.panelassistant.android.platform.RootShell
import io.panelassistant.android.util.DurableRecoveryMarker
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KioskPolicyRecoveryTest {
    @Test fun failedEnableAttemptsCleanupWithoutAcknowledgingLocalEnforcement() {
        val events = mutableListOf<String>()

        assertFalse(acquireKioskPolicy(
            enable = { events += "enable"; false },
            cleanup = { events += "cleanup"; false },
        ))
        assertEquals(listOf("enable", "cleanup"), events)
    }

    @Test fun enableArmsImmersiveBeforeMutationAndDisableClearsOnlyAfterPositiveRecovery() {
        val directory = Files.createTempDirectory("kiosk-policy-test").toFile()
        try {
            val statusFile = directory.resolve("status.pending")
            val immersiveFile = directory.resolve("immersive.pending")
            val root = RecordingRoot(result = { command ->
                when (command) {
                    KioskPolicyRecovery.IMMERSIVE_ON -> immersiveFile.isFile
                    else -> true
                }
            })
            val recovery = KioskPolicyRecovery(
                root,
                DurableRecoveryMarker(statusFile),
                DurableRecoveryMarker(immersiveFile),
                DurableRecoveryMarker(directory.resolve("migration.complete")),
            )

            assertTrue(recovery.enable())
            assertFalse(statusFile.exists())
            assertTrue(immersiveFile.isFile)
            assertTrue(recovery.disable())
            assertFalse(statusFile.exists())
            assertFalse(immersiveFile.exists())
            assertEquals(
                listOf(
                    KioskPolicyRecovery.IMMERSIVE_ON,
                    KioskPolicyRecovery.IMMERSIVE_OFF,
                    KioskPolicyRecovery.IMMERSIVE_READBACK,
                ),
                root.commands,
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun absentOwnershipNeedsNoRootCommand() {
        val directory = Files.createTempDirectory("kiosk-policy-test").toFile()
        try {
            val root = RecordingRoot(result = { false })
            val recovery = KioskPolicyRecovery(
                root,
                DurableRecoveryMarker(directory.resolve("status.pending")),
                DurableRecoveryMarker(directory.resolve("immersive.pending")),
                DurableRecoveryMarker(directory.resolve("migration.complete")),
            )

            assertTrue(recovery.disable())
            assertTrue(root.commands.isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun failedRecoveryRetainsItsMarkerForRetry() {
        val directory = Files.createTempDirectory("kiosk-policy-test").toFile()
        try {
            val statusFile = directory.resolve("status.pending")
            val statusMarker = DurableRecoveryMarker(statusFile)
            assertTrue(statusMarker.arm())
            val root = RecordingRoot(result = { command -> command != KioskPolicyRecovery.STATUS_BAR_OFF })
            val recovery = KioskPolicyRecovery(
                root,
                statusMarker,
                DurableRecoveryMarker(directory.resolve("immersive.pending")),
                DurableRecoveryMarker(directory.resolve("migration.complete")),
            )

            assertFalse(recovery.disable())
            assertTrue(statusFile.isFile)
            assertEquals(listOf(KioskPolicyRecovery.STATUS_BAR_OFF), root.commands)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun legacyKioskConfigPublishesBothObligationsBeforeOneTimeRecovery() {
        val directory = Files.createTempDirectory("kiosk-policy-test").toFile()
        try {
            val statusFile = directory.resolve("status.pending")
            val immersiveFile = directory.resolve("immersive.pending")
            val migrationFile = directory.resolve("migration.complete")
            val root = RecordingRoot(result = { command ->
                when (command) {
                    KioskPolicyRecovery.STATUS_BAR_OFF -> statusFile.isFile && immersiveFile.isFile
                    KioskPolicyRecovery.IMMERSIVE_OFF -> immersiveFile.isFile
                    else -> true
                }
            })
            val recovery = KioskPolicyRecovery(
                root,
                DurableRecoveryMarker(statusFile),
                DurableRecoveryMarker(immersiveFile),
                DurableRecoveryMarker(migrationFile),
            )

            assertTrue(recovery.recover(legacyMayBeActive = true))
            assertFalse(statusFile.exists())
            assertFalse(immersiveFile.exists())
            assertTrue(migrationFile.isFile)
            assertFalse(root.commands.contains("cmd statusbar disable-for-setup true"))
            assertFalse(root.commands.contains(KioskPolicyRecovery.IMMERSIVE_ON))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun freshInstallCompletesMigrationWithoutTouchingUnownedPlatformState() {
        val directory = Files.createTempDirectory("kiosk-policy-test").toFile()
        try {
            val migrationFile = directory.resolve("migration.complete")
            val root = RecordingRoot(result = { false })
            val recovery = KioskPolicyRecovery(
                root,
                DurableRecoveryMarker(directory.resolve("status.pending")),
                DurableRecoveryMarker(directory.resolve("immersive.pending")),
                DurableRecoveryMarker(migrationFile),
            )

            assertTrue(recovery.recover(legacyMayBeActive = false))
            assertTrue(migrationFile.isFile)
            assertTrue(root.commands.isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun unsupportedPersistentPolicyIgnoresRawLegacyConfigWithoutPublishingOwnership() {
        val directory = Files.createTempDirectory("kiosk-policy-test").toFile()
        try {
            val statusFile = directory.resolve("status.pending")
            val immersiveFile = directory.resolve("immersive.pending")
            val migrationFile = directory.resolve("migration.complete")
            val root = RecordingRoot(result = { false })
            val recovery = KioskPolicyRecovery(
                root,
                DurableRecoveryMarker(statusFile),
                DurableRecoveryMarker(immersiveFile),
                DurableRecoveryMarker(migrationFile),
                persistentPolicyEligible = false,
            )

            assertTrue(recovery.recover(legacyMayBeActive = true))
            assertFalse(recovery.enable())
            assertTrue(migrationFile.isFile)
            assertFalse(statusFile.exists())
            assertFalse(immersiveFile.exists())
            assertTrue(root.commands.isEmpty())
            assertTrue(recovery.disable())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun unsupportedPersistentPolicyStillHonorsAlreadyArmedRecoveryEvidence() {
        val directory = Files.createTempDirectory("kiosk-policy-test").toFile()
        try {
            val immersiveFile = directory.resolve("immersive.pending")
            val immersiveMarker = DurableRecoveryMarker(immersiveFile)
            assertTrue(immersiveMarker.arm())
            val root = RecordingRoot(result = { false })
            val recovery = KioskPolicyRecovery(
                root,
                DurableRecoveryMarker(directory.resolve("status.pending")),
                immersiveMarker,
                DurableRecoveryMarker(directory.resolve("migration.complete")),
                persistentPolicyEligible = false,
            )

            assertFalse(recovery.recover(legacyMayBeActive = true))
            assertTrue(immersiveFile.isFile)
            assertEquals(listOf(KioskPolicyRecovery.IMMERSIVE_OFF), root.commands)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun optimisticProfileWithoutLiveRootDoesNotPublishLegacyOrEnableOwnership() {
        val directory = Files.createTempDirectory("kiosk-policy-test").toFile()
        try {
            val statusFile = directory.resolve("status.pending")
            val immersiveFile = directory.resolve("immersive.pending")
            val migrationFile = directory.resolve("migration.complete")
            val root = RecordingRoot(availableResult = { false }, result = { false })
            val recovery = KioskPolicyRecovery(
                root,
                DurableRecoveryMarker(statusFile),
                DurableRecoveryMarker(immersiveFile),
                DurableRecoveryMarker(migrationFile),
                persistentPolicyEligible = true,
            )

            assertTrue(recovery.recover(legacyMayBeActive = true))
            assertFalse(recovery.enable())
            assertFalse(migrationFile.exists())
            assertFalse(statusFile.exists())
            assertFalse(immersiveFile.exists())
            assertTrue(root.commands.isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun migrationCompletionWaitsForVerifiedRecoveryAndLaterRetry() {
        val directory = Files.createTempDirectory("kiosk-policy-test").toFile()
        try {
            val migrationFile = directory.resolve("migration.complete")
            var policy = "immersive.full=*"
            val root = RecordingRoot(
                result = { true },
                output = { command ->
                    if (command == KioskPolicyRecovery.IMMERSIVE_READBACK) policy else null
                },
            )
            val recovery = KioskPolicyRecovery(
                root,
                DurableRecoveryMarker(directory.resolve("status.pending")),
                DurableRecoveryMarker(directory.resolve("immersive.pending")),
                DurableRecoveryMarker(migrationFile),
            )

            assertFalse(recovery.recover(legacyMayBeActive = true))
            assertFalse(migrationFile.exists())

            policy = "null"
            assertTrue(recovery.recover(legacyMayBeActive = true))
            assertTrue(migrationFile.isFile)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun successfulImmersiveOffCommandWithoutAffirmativeReadbackRetainsRecoveryEvidence() {
        val directory = Files.createTempDirectory("kiosk-policy-test").toFile()
        try {
            val immersiveFile = directory.resolve("immersive.pending")
            val immersiveMarker = DurableRecoveryMarker(immersiveFile)
            assertTrue(immersiveMarker.arm())
            val root = RecordingRoot(
                result = { true },
                output = { command ->
                    if (command == KioskPolicyRecovery.IMMERSIVE_READBACK) "immersive.full=*" else null
                },
            )
            val recovery = KioskPolicyRecovery(
                root,
                DurableRecoveryMarker(directory.resolve("status.pending")),
                immersiveMarker,
                DurableRecoveryMarker(directory.resolve("migration.complete")),
            )

            assertFalse(recovery.disable())
            assertTrue(immersiveFile.isFile)
        } finally {
            directory.deleteRecursively()
        }
    }

    private class RecordingRoot(
        private val availableResult: () -> Boolean = { true },
        private val result: (String) -> Boolean,
        private val output: (String) -> String? = { command ->
            when (command) {
                KioskPolicyRecovery.IMMERSIVE_READBACK -> "null"
                else -> null
            }
        },
    ) : RootShell {
        val commands = mutableListOf<String>()
        override fun available(): Boolean = availableResult()
        override fun run(cmd: String): Boolean = result(cmd).also { commands += cmd }
        override fun runOutput(cmd: String): String? = output(cmd).also { commands += cmd }
        override fun runBytes(cmd: String): ByteArray? = null
        override fun fireAndForget(cmd: String): Boolean = false
    }
}
