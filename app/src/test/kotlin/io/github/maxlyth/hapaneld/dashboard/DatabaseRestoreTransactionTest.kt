package io.github.maxlyth.hapaneld.dashboard

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabaseRestoreTransactionTest {
    private val session = "1".repeat(64)
    private val guard = DatabaseRestoreGuardBinding(session, 16L)

    @Test fun `every durable cut either resumes or safely restarts before canonical mutation`() {
        listOf(false, true).forEach { withPriorAside ->
            DatabaseRestoreCut.values().forEach { selected ->
                withFiles { directory, target, staged ->
                    val label = "$selected priorAside=$withPriorAside"
                    if (withPriorAside) supersededFile(target, 15).writeText("prior-schema15")
                    var fired = false
                    val first = DatabaseRestoreTransaction(target, guard) { cut ->
                        if (!fired && cut == selected) {
                            fired = true
                            throw SimulatedProcessDeath
                        }
                    }
                    try {
                        first.restore(staged, sourceSchema = 15, stagedSchema = 14, checkpoint = { true })
                        throw AssertionError("cut $label was not reached")
                    } catch (_: SimulatedProcessDeath) {
                        // New process below owns all reconciliation.
                    }
                    assertTrue("cut $label must fire", fired)

                    val restarted = DatabaseRestoreTransaction(target, guard)
                    val initial = restarted.reconcile()
                    val result = if (initial is DatabaseRestoreResult.Absent) {
                        // Before PREPARED publication, the exact orphan copy is discarded because the
                        // canonical source was never moved. Retrying creates a fresh bound transaction.
                        restarted.restore(staged, 15, 14, checkpoint = { true })
                    } else initial
                    val restored = result as? DatabaseRestoreResult.Restored
                        ?: throw AssertionError("cut $label settled as $result")
                    assertEquals(label, SchemaReconcileAction.RESTORED, restored.reconcile.action)
                    assertEquals(label, 15, restored.reconcile.fromVersion)
                    assertEquals(label, 14, restored.reconcile.toVersion)
                    assertEquals(label, guard, restored.record.guard)
                    assertEquals(label, "schema14", target.readText())
                    assertEquals(label, "schema15", supersededFile(target, 15).readText())
                    listOf("-wal", "-shm", "-journal", ".tmp").forEach { suffix ->
                        assertFalse(label, File(target.path + suffix).exists())
                        assertFalse(label, File(supersededFile(target, 15).path + suffix).exists())
                    }
                    assertEquals(
                        label,
                        listOf(supersededFile(target, 15).name),
                        directory.listFiles()!!.filter { it.name.contains(".superseded") }.map { it.name },
                    )
                    assertTrue(label, File(directory, ".ha-paneld.db.restore.v1").isFile)
                }
            }
        }
    }

    @Test fun `restart after completed rename retains durable RESTORED proof`() = withFiles { _, target, staged ->
        val result = DatabaseRestoreTransaction(
            target,
            guard,
            ownedStableSidecar = { file, requireEmpty -> !requireEmpty || file.length() == 0L },
        )
            .restore(staged, 15, 14, checkpoint = { true })
        assertTrue(result is DatabaseRestoreResult.Restored)

        val restarted = DatabaseRestoreTransaction(target, guard).reconcile()
            as DatabaseRestoreResult.Restored
        assertEquals(SchemaReconcileAction.RESTORED, restarted.reconcile.action)
        assertEquals(15, restarted.reconcile.fromVersion)
        assertEquals(14, restarted.reconcile.restoredVersion)
        assertEquals(16L, restarted.record.guard?.generation)
    }

    @Test fun `restart after owned WAL open normalizes inert sidecars and retains Guard receipt`() =
        withFiles { directory, target, staged ->
            val sidecarChecks = mutableListOf<Pair<String, Boolean>>()
            val transaction = DatabaseRestoreTransaction(
                target,
                guard,
                ownedStableSidecar = { file, requireEmpty ->
                    sidecarChecks += file.name to requireEmpty
                    !requireEmpty || file.length() == 0L
                },
            )
            assertTrue(transaction.restore(staged, 15, 14, checkpoint = { true }) is DatabaseRestoreResult.Restored)
            File(target.path + "-wal").writeBytes(byteArrayOf())
            File(target.path + "-shm").writeText("owned-open-shm")

            val restarted = DatabaseRestoreTransaction(
                target,
                guard,
                ownedStableSidecar = { file, requireEmpty ->
                    sidecarChecks += file.name to requireEmpty
                    !requireEmpty || file.length() == 0L
                },
            ).reconcile() as? DatabaseRestoreResult.Restored
                ?: throw AssertionError("inert restored-open sidecars did not resume")

            assertEquals(guard, restarted.record.guard)
            assertEquals(
                listOf("${target.name}-wal" to true, "${target.name}-shm" to false),
                sidecarChecks,
            )
            assertFalse(File(target.path + "-wal").exists())
            assertFalse(File(target.path + "-shm").exists())
            assertTrue(File(directory, ".ha-paneld.db.restore.v1").isFile)
            assertEquals("schema14", target.readText())
            assertEquals("schema15", supersededFile(target, 15).readText())
        }

    @Test fun `restart after ordinary WAL open resumes before second open consumes receipt`() =
        withFiles { directory, target, staged ->
            val owned: (File, Boolean) -> Boolean = { file, requireEmpty ->
                !requireEmpty || file.length() == 0L
            }
            assertTrue(
                DatabaseRestoreTransaction(target, guard = null, ownedStableSidecar = owned)
                    .restore(staged, 15, 14, checkpoint = { true }) is DatabaseRestoreResult.Restored,
            )
            File(target.path + "-wal").writeBytes(byteArrayOf())
            File(target.path + "-shm").writeText("owned-open-shm")

            val restarted = DatabaseRestoreTransaction(target, guard = null, ownedStableSidecar = owned)
            assertTrue(restarted.reconcile() is DatabaseRestoreResult.Restored)
            assertTrue(File(directory, ".ha-paneld.db.restore.v1").isFile)
            assertFalse(File(target.path + "-wal").exists())
            assertFalse(File(target.path + "-shm").exists())

            // SQLiteOpenHelper's second open may recreate the same inert topology before onOpen.
            File(target.path + "-wal").writeBytes(byteArrayOf())
            File(target.path + "-shm").writeText("owned-second-open-shm")
            (assertTrue(restarted.settleRestoredAfterOpen() !is DatabaseRestoreOpenedReceipt.Hold))
            assertFalse(File(directory, ".ha-paneld.db.restore.v1").exists())
        }

    @Test fun `open lease keeps a concurrent reconciler away from live restored WAL`() =
        withFiles { directory, target, staged ->
            val owned: (File, Boolean) -> Boolean = { file, requireEmpty ->
                !requireEmpty || file.length() == 0L
            }
            assertTrue(
                DatabaseRestoreTransaction(target, guard = null, ownedStableSidecar = owned)
                    .restore(staged, 15, 14, checkpoint = { true }) is DatabaseRestoreResult.Restored,
            )
            val lease = DatabaseRestoreOpenLease()
            val firstOpened = CountDownLatch(1)
            val releaseFirst = CountDownLatch(1)
            val secondAttempting = CountDownLatch(1)
            val secondEntered = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            val firstResult = AtomicReference<DatabaseRestoreResult>()
            val secondResult = AtomicReference<DatabaseRestoreResult>()
            try {
                val first = executor.submit {
                    lease.reconcileAndOpen(
                        establishedGuardReceipt = { DatabaseRestoreEstablishedReceipt.MISMATCH },
                        reconcile = {
                            val restored = DatabaseRestoreTransaction(
                                target,
                                guard = null,
                                ownedStableSidecar = owned,
                            ).reconcile()
                            firstResult.set(restored)
                            restored is DatabaseRestoreResult.Restored
                        },
                        open = { joiningRetainedGuard ->
                            assertFalse(joiningRetainedGuard)
                            File(target.path + "-wal").writeBytes(byteArrayOf())
                            File(target.path + "-shm").writeText("live-owned-shm")
                            firstOpened.countDown()
                            assertTrue(releaseFirst.await(5, TimeUnit.SECONDS))
                            assertTrue(
                                DatabaseRestoreTransaction(target, guard = null, ownedStableSidecar = owned)
                                    .settleRestoredAfterOpen() !is DatabaseRestoreOpenedReceipt.Hold,
                            )
                            null
                        },
                    )
                }
                assertTrue(firstOpened.await(5, TimeUnit.SECONDS))
                val second = executor.submit {
                    secondAttempting.countDown()
                    lease.reconcileAndOpen(
                        establishedGuardReceipt = { DatabaseRestoreEstablishedReceipt.MISMATCH },
                        reconcile = {
                            secondEntered.countDown()
                            val result = DatabaseRestoreTransaction(
                                target,
                                guard = null,
                                ownedStableSidecar = owned,
                            ).reconcile()
                            secondResult.set(result)
                            result is DatabaseRestoreResult.Restored
                        },
                        open = { throw AssertionError("receipt-absent second owner tried a restored open") },
                    )
                }
                assertTrue(secondAttempting.await(5, TimeUnit.SECONDS))
                assertFalse(
                    "second reconcile entered while restored WAL was live",
                    secondEntered.await(250, TimeUnit.MILLISECONDS),
                )
                assertTrue(File(target.path + "-wal").isFile)
                assertTrue(File(target.path + "-shm").isFile)
                assertTrue(File(directory, ".ha-paneld.db.restore.v1").isFile)

                releaseFirst.countDown()
                first.get(5, TimeUnit.SECONDS)
                second.get(5, TimeUnit.SECONDS)
                assertTrue(firstResult.get() is DatabaseRestoreResult.Restored)
                assertTrue(secondResult.get() is DatabaseRestoreResult.Absent)
                assertTrue("the later receipt-absent reconcile must not unlink a live WAL", File(target.path + "-wal").isFile)
                assertTrue(File(target.path + "-shm").isFile)
                assertFalse(File(directory, ".ha-paneld.db.restore.v1").exists())
            } finally {
                releaseFirst.countDown()
                executor.shutdownNow()
            }
        }

    @Test fun `failed restored open poisons lease before a later owner can reconcile live sidecars`() =
        withFiles { directory, target, staged ->
            val owned: (File, Boolean) -> Boolean = { file, requireEmpty ->
                !requireEmpty || file.length() == 0L
            }
            val transaction = DatabaseRestoreTransaction(target, guard = null, ownedStableSidecar = owned)
            assertTrue(transaction.restore(staged, 15, 14, checkpoint = { true }) is DatabaseRestoreResult.Restored)
            val lease = DatabaseRestoreOpenLease()

            val failure = assertThrows(IllegalStateException::class.java) {
                lease.reconcileAndOpen(
                    establishedGuardReceipt = { DatabaseRestoreEstablishedReceipt.MISMATCH },
                    reconcile = { transaction.reconcile() is DatabaseRestoreResult.Restored },
                    open = { joiningRetainedGuard ->
                        assertFalse(joiningRetainedGuard)
                        File(target.path + "-wal").writeBytes(byteArrayOf())
                        File(target.path + "-shm").writeText("live-failed-open-shm")
                        throw IllegalStateException("failed after SQLite open")
                    },
                )
            }
            assertEquals("failed after SQLite open", failure.message)

            val held = assertThrows(DatabaseRestoreHoldException::class.java) {
                lease.reconcileAndOpen(
                    establishedGuardReceipt = { throw AssertionError("poisoned lease inspected a receipt") },
                    reconcile = { throw AssertionError("poisoned lease re-entered destructive reconcile") },
                    open = { throw AssertionError("poisoned lease opened SQLite") },
                )
            }
            assertEquals("database restore open did not settle", held.message)
            assertTrue(File(target.path + "-wal").isFile)
            assertTrue(File(target.path + "-shm").isFile)
            assertTrue(File(directory, ".ha-paneld.db.restore.v1").isFile)
        }

    @Test fun `reentrant restored owner cannot reconcile while the first open owns live sidecars`() =
        withFiles { directory, target, staged ->
            val owned: (File, Boolean) -> Boolean = { file, requireEmpty ->
                !requireEmpty || file.length() == 0L
            }
            val transaction = DatabaseRestoreTransaction(target, guard = null, ownedStableSidecar = owned)
            assertTrue(transaction.restore(staged, 15, 14, checkpoint = { true }) is DatabaseRestoreResult.Restored)
            val lease = DatabaseRestoreOpenLease()
            lease.reconcileAndOpen(
                establishedGuardReceipt = { DatabaseRestoreEstablishedReceipt.MISMATCH },
                reconcile = {
                    val held = assertThrows(DatabaseRestoreHoldException::class.java) {
                        lease.reconcileAndOpen(
                            establishedGuardReceipt = { throw AssertionError("reentrant owner inspected a receipt") },
                            reconcile = { throw AssertionError("reentrant owner nested inside reconcile") },
                            open = { throw AssertionError("reentrant owner opened inside reconcile") },
                        )
                    }
                    assertEquals("database restore admission already in progress", held.message)
                    transaction.reconcile() is DatabaseRestoreResult.Restored
                },
                open = { joiningRetainedGuard ->
                    assertFalse(joiningRetainedGuard)
                    File(target.path + "-wal").writeBytes(byteArrayOf())
                    File(target.path + "-shm").writeText("live-reentrant-shm")
                    val held = assertThrows(DatabaseRestoreHoldException::class.java) {
                        lease.reconcileAndOpen(
                            establishedGuardReceipt = { throw AssertionError("reentrant owner inspected a receipt") },
                            reconcile = { throw AssertionError("reentrant owner reconciled live sidecars") },
                            open = { throw AssertionError("reentrant owner opened SQLite") },
                        )
                    }
                    assertEquals("database restore admission already in progress", held.message)
                    (assertTrue(transaction.settleRestoredAfterOpen() !is DatabaseRestoreOpenedReceipt.Hold))
                    null
                },
            )
            assertFalse(File(directory, ".ha-paneld.db.restore.v1").exists())
            assertTrue(File(target.path + "-wal").isFile)
            assertTrue(File(target.path + "-shm").isFile)
        }

    @Test fun `retained Guard open makes a concurrent owner join without unlinking live WAL`() =
        withFiles { directory, target, staged ->
            val owned: (File, Boolean) -> Boolean = { file, requireEmpty ->
                !requireEmpty || file.length() == 0L
            }
            assertTrue(
                DatabaseRestoreTransaction(target, guard, ownedStableSidecar = owned)
                    .restore(staged, 15, 14, checkpoint = { true }) is DatabaseRestoreResult.Restored,
            )
            val lease = DatabaseRestoreOpenLease()
            val firstOpened = CountDownLatch(1)
            val releaseFirst = CountDownLatch(1)
            val secondAttempting = CountDownLatch(1)
            val secondJoined = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val first = executor.submit {
                    lease.reconcileAndOpen(
                        establishedGuardReceipt = { DatabaseRestoreEstablishedReceipt.MISMATCH },
                        reconcile = {
                            DatabaseRestoreTransaction(target, guard, ownedStableSidecar = owned)
                                .reconcile() is DatabaseRestoreResult.Restored
                        },
                        open = { joiningRetainedGuard ->
                            assertFalse(joiningRetainedGuard)
                            File(target.path + "-wal").writeBytes(byteArrayOf())
                            File(target.path + "-shm").writeText("live-guard-shm")
                            firstOpened.countDown()
                            assertTrue(releaseFirst.await(5, TimeUnit.SECONDS))
                            val receipt = DatabaseRestoreTransaction(target, guard, ownedStableSidecar = owned)
                                .settleRestoredAfterOpen()
                            (receipt as DatabaseRestoreOpenedReceipt.GuardRetained).record
                        },
                    )
                }
                assertTrue(firstOpened.await(5, TimeUnit.SECONDS))
                val second = executor.submit {
                    secondAttempting.countDown()
                    lease.reconcileAndOpen(
                        establishedGuardReceipt = { record ->
                            DatabaseRestoreTransaction(target, guard, ownedStableSidecar = owned)
                                .establishedGuardReceipt(record)
                        },
                        reconcile = { throw AssertionError("retained Guard owner re-entered destructive reconcile") },
                        open = { joiningRetainedGuard ->
                            assertTrue(joiningRetainedGuard)
                            assertTrue(File(target.path + "-wal").isFile)
                            assertTrue(File(target.path + "-shm").isFile)
                            assertTrue(File(directory, ".ha-paneld.db.restore.v1").isFile)
                            secondJoined.countDown()
                            null
                        },
                    )
                }
                assertTrue(secondAttempting.await(5, TimeUnit.SECONDS))
                assertFalse(
                    "second Guard owner joined before the first open settled",
                    secondJoined.await(250, TimeUnit.MILLISECONDS),
                )
                assertTrue(File(target.path + "-wal").isFile)
                assertTrue(File(target.path + "-shm").isFile)

                releaseFirst.countDown()
                first.get(5, TimeUnit.SECONDS)
                second.get(5, TimeUnit.SECONDS)
                assertTrue(secondJoined.await(5, TimeUnit.SECONDS))
                assertTrue(File(target.path + "-wal").isFile)
                assertTrue(File(target.path + "-shm").isFile)
                assertTrue("Guard receipt must remain durable", File(directory, ".ha-paneld.db.restore.v1").isFile)
            } finally {
                releaseFirst.countDown()
                executor.shutdownNow()
            }
        }

    @Test fun `established Guard lease admits terminal receipt clear but holds changed receipt`() {
        fun establish(target: File, staged: File): Pair<DatabaseRestoreOpenLease, DatabaseRestoreTransaction> {
            val owned: (File, Boolean) -> Boolean = { file, requireEmpty ->
                !requireEmpty || file.length() == 0L
            }
            val transaction = DatabaseRestoreTransaction(target, guard, ownedStableSidecar = owned)
            assertTrue(transaction.restore(staged, 15, 14, checkpoint = { true }) is DatabaseRestoreResult.Restored)
            val lease = DatabaseRestoreOpenLease()
            lease.reconcileAndOpen(
                establishedGuardReceipt = { DatabaseRestoreEstablishedReceipt.MISMATCH },
                reconcile = { transaction.reconcile() is DatabaseRestoreResult.Restored },
                open = { joiningRetainedGuard ->
                    assertFalse(joiningRetainedGuard)
                    File(target.path + "-wal").writeBytes(byteArrayOf())
                    File(target.path + "-shm").writeText("live-guard-shm")
                    val receipt = transaction.settleRestoredAfterOpen()
                    (receipt as DatabaseRestoreOpenedReceipt.GuardRetained).record
                },
            )
            return lease to transaction
        }

        withFiles { directory, target, staged ->
            val (lease, transaction) = establish(target, staged)
            val racingClear = DatabaseRestoreTransaction(
                target,
                guard,
                beforeEstablishedReceiptRead = { assertTrue(transaction.clearRestored(session)) },
            )
            var joined = false
            lease.reconcileAndOpen(
                establishedGuardReceipt = racingClear::establishedGuardReceipt,
                reconcile = { throw AssertionError("cleared established receipt re-entered reconcile") },
                open = { joiningRetainedGuard ->
                    assertTrue(joiningRetainedGuard)
                    joined = true
                    null
                },
            )
            assertTrue(joined)
            assertFalse(File(directory, ".ha-paneld.db.restore.v1").exists())
            assertTrue(File(target.path + "-wal").isFile)
            assertTrue(File(target.path + "-shm").isFile)
        }

        withFiles { directory, target, staged ->
            val (lease, transaction) = establish(target, staged)
            val receipt = File(directory, ".ha-paneld.db.restore.v1")
            receipt.writeText("changed-receipt")
            assertThrows(DatabaseRestoreHoldException::class.java) {
                lease.reconcileAndOpen(
                    establishedGuardReceipt = transaction::establishedGuardReceipt,
                    reconcile = { throw AssertionError("changed established receipt re-entered reconcile") },
                    open = { throw AssertionError("changed established receipt opened") },
                )
            }
            assertEquals("changed-receipt", receipt.readText())
            assertTrue(File(target.path + "-wal").isFile)
            assertTrue(File(target.path + "-shm").isFile)
        }

        withFiles { directory, target, staged ->
            val (lease, transaction) = establish(target, staged)
            val failure = assertThrows(IllegalStateException::class.java) {
                lease.reconcileAndOpen(
                    establishedGuardReceipt = transaction::establishedGuardReceipt,
                    reconcile = { throw AssertionError("established Guard join re-entered reconcile") },
                    open = { joiningRetainedGuard ->
                        assertTrue(joiningRetainedGuard)
                        throw IllegalStateException("failed while joining established Guard owner")
                    },
                )
            }
            assertEquals("failed while joining established Guard owner", failure.message)
            val held = assertThrows(DatabaseRestoreHoldException::class.java) {
                lease.reconcileAndOpen(
                    establishedGuardReceipt = { throw AssertionError("poisoned lease inspected Guard receipt") },
                    reconcile = { throw AssertionError("poisoned lease reconciled") },
                    open = { throw AssertionError("poisoned lease retried Guard join") },
                )
            }
            assertEquals("database restore open did not settle", held.message)
            assertTrue(File(directory, ".ha-paneld.db.restore.v1").isFile)
            assertTrue(File(target.path + "-wal").isFile)
            assertTrue(File(target.path + "-shm").isFile)
        }
    }

    @Test fun `restored restart rejects journal nonempty unproved and drifted topologies`() {
        fun assertHeld(label: String, trusted: (File, Boolean) -> Boolean, mutate: (File, File, File) -> Unit) =
            withFiles { directory, target, staged ->
                val transaction = DatabaseRestoreTransaction(
                    target,
                    guard = null,
                    ownedStableSidecar = trusted,
                )
                assertTrue(transaction.restore(staged, 15, 14, checkpoint = { true }) is DatabaseRestoreResult.Restored)
                mutate(directory, target, staged)
                val before = directory.listFiles()!!.associate { it.name to it.readSafeBytes() }

                val restarted = DatabaseRestoreTransaction(
                    target,
                    guard = null,
                    ownedStableSidecar = trusted,
                ).reconcile()

                assertTrue(label, restarted is DatabaseRestoreResult.Hold)
                assertTrue(label, File(directory, ".ha-paneld.db.restore.v1").isFile)
                assertEquals(label, before, directory.listFiles()!!.associate { it.name to it.readSafeBytes() })
            }

        val exactOwned: (File, Boolean) -> Boolean = { file, requireEmpty ->
            !requireEmpty || file.length() == 0L
        }
        assertHeld("rollback journal", exactOwned) { _, target, _ ->
            File(target.path + "-journal").writeBytes(byteArrayOf())
        }
        assertHeld("nonempty WAL", exactOwned) { _, target, _ ->
            File(target.path + "-wal").writeText("committed-frame")
        }
        assertHeld("unproved empty WAL", { _, _ -> false }) { _, target, _ ->
            File(target.path + "-wal").writeBytes(byteArrayOf())
        }
        assertHeld("unproved SHM", { _, _ -> false }) { _, target, _ ->
            File(target.path + "-shm").writeText("unproved-shm")
        }
        assertHeld("canonical target drift", exactOwned) { _, target, _ ->
            target.writeText("changed-after-open")
        }
        assertHeld("superseded companion drift", exactOwned) { _, target, _ ->
            File(supersededFile(target, 15).path + "-wal").writeBytes(byteArrayOf())
        }
    }

    @Test fun `sidecar authority rejects foreign hardlinked and changing identities`() {
        val exact = DatabaseRestoreSidecarIdentity(
            regular = true,
            device = 1L,
            inode = 2L,
            uid = 1000,
            gid = 1000,
            links = 1L,
            bytes = 0L,
            sha256 = "a".repeat(64),
        )
        assertTrue(exactOwnedStableDatabaseSidecar(exact, exact, 1000, requireEmpty = true))
        assertFalse(exactOwnedStableDatabaseSidecar(exact.copy(uid = 0), exact.copy(uid = 0), 1000, true))
        assertFalse(exactOwnedStableDatabaseSidecar(exact.copy(gid = 0), exact.copy(gid = 0), 1000, true))
        assertFalse(exactOwnedStableDatabaseSidecar(exact.copy(links = 2), exact.copy(links = 2), 1000, true))
        assertFalse(exactOwnedStableDatabaseSidecar(exact, exact.copy(sha256 = "b".repeat(64)), 1000, true))
        assertFalse(exactOwnedStableDatabaseSidecar(exact.copy(bytes = 1), exact.copy(bytes = 1), 1000, true))
        assertFalse(exactOwnedStableDatabaseSidecar(exact.copy(regular = false), exact.copy(regular = false), 1000, false))
    }

    @Test fun `unproved sidecar is retained and holds before source rename`() = withFiles { _, target, staged ->
        val wal = File(target.path + "-wal").apply { writeBytes(byteArrayOf()) }
        val result = DatabaseRestoreTransaction(
            target,
            guard,
            ownedStableSidecar = { _, _ -> false },
        ).restore(staged, 15, 14, checkpoint = { true })

        assertTrue(result is DatabaseRestoreResult.Hold)
        assertTrue(wal.exists())
        assertEquals("schema15", target.readText())
        assertFalse(supersededFile(target, 15).exists())
    }

    @Test fun `even an empty rollback journal remains fail closed`() = withFiles { _, target, staged ->
        val journal = File(target.path + "-journal").apply { writeBytes(byteArrayOf()) }

        val result = DatabaseRestoreTransaction(
            target,
            guard,
            ownedStableSidecar = { _, _ -> true },
        ).restore(staged, 15, 14, checkpoint = { true })

        assertTrue(result is DatabaseRestoreResult.Hold)
        assertTrue(journal.exists())
        assertEquals("schema15", target.readText())
        assertFalse(supersededFile(target, 15).exists())
    }

    @Test fun `ordinary receipt is consumed after successful owned WAL open`() = withFiles { directory, target, staged ->
        val sidecarChecks = mutableListOf<Pair<String, Boolean>>()
        val transaction = DatabaseRestoreTransaction(
            target,
            guard = null,
            ownedStableSidecar = { file, requireEmpty ->
                sidecarChecks += file.name to requireEmpty
                !requireEmpty || file.length() == 0L
            },
        )
        assertTrue(transaction.restore(staged, 15, 14, checkpoint = { true }) is DatabaseRestoreResult.Restored)
        assertTrue(File(directory, ".ha-paneld.db.restore.v1").isFile)
        File(target.path + "-wal").writeBytes(byteArrayOf())
        File(target.path + "-shm").writeText("owned-open-shm")

        (assertTrue(transaction.settleRestoredAfterOpen() !is DatabaseRestoreOpenedReceipt.Hold))
        assertEquals(
            listOf("${target.name}-wal" to true, "${target.name}-shm" to false),
            sidecarChecks,
        )
        assertFalse(File(directory, ".ha-paneld.db.restore.v1").exists())
        assertTrue(DatabaseRestoreTransaction(target).reconcile() is DatabaseRestoreResult.Absent)
    }

    @Test fun `ordinary receipt rejects a nonempty open WAL`() = withFiles { directory, target, staged ->
        val transaction = DatabaseRestoreTransaction(
            target,
            guard = null,
            ownedStableSidecar = { file, requireEmpty -> !requireEmpty || file.length() == 0L },
        )
        assertTrue(transaction.restore(staged, 15, 14, checkpoint = { true }) is DatabaseRestoreResult.Restored)
        val wal = File(target.path + "-wal").apply { writeText("live-frame") }

        (assertFalse(transaction.settleRestoredAfterOpen() !is DatabaseRestoreOpenedReceipt.Hold))
        assertEquals("live-frame", wal.readText())
        assertTrue(File(directory, ".ha-paneld.db.restore.v1").isFile)
        assertEquals("schema14", target.readText())
        assertEquals("schema15", supersededFile(target, 15).readText())
    }

    @Test fun `ordinary receipt rejects an unproved empty open WAL`() = withFiles { directory, target, staged ->
        val transaction = DatabaseRestoreTransaction(
            target,
            guard = null,
            ownedStableSidecar = { _, _ -> false },
        )
        assertTrue(transaction.restore(staged, 15, 14, checkpoint = { true }) is DatabaseRestoreResult.Restored)
        val wal = File(target.path + "-wal").apply { writeBytes(byteArrayOf()) }

        (assertFalse(transaction.settleRestoredAfterOpen() !is DatabaseRestoreOpenedReceipt.Hold))
        assertTrue(wal.exists())
        assertTrue(File(directory, ".ha-paneld.db.restore.v1").isFile)
    }

    @Test fun `ordinary receipt retains proof when restored topology drifts`() = withFiles { directory, target, staged ->
        val transaction = DatabaseRestoreTransaction(target, guard = null)
        assertTrue(transaction.restore(staged, 15, 14, checkpoint = { true }) is DatabaseRestoreResult.Restored)
        target.writeText("changed-after-open")

        (assertFalse(transaction.settleRestoredAfterOpen() !is DatabaseRestoreOpenedReceipt.Hold))
        assertEquals("changed-after-open", target.readText())
        assertTrue(File(directory, ".ha-paneld.db.restore.v1").isFile)
        assertEquals("schema15", supersededFile(target, 15).readText())
    }

    @Test fun `ordinary consumer never clears a Guard receipt`() = withFiles { directory, target, staged ->
        val transaction = DatabaseRestoreTransaction(
            target,
            guard,
            ownedStableSidecar = { file, requireEmpty -> !requireEmpty || file.length() == 0L },
        )
        assertTrue(transaction.restore(staged, 15, 14, checkpoint = { true }) is DatabaseRestoreResult.Restored)
        File(target.path + "-wal").writeBytes(byteArrayOf())
        File(target.path + "-shm").writeText("owned-open-shm")

        (assertTrue(transaction.settleRestoredAfterOpen() !is DatabaseRestoreOpenedReceipt.Hold))
        assertTrue(File(directory, ".ha-paneld.db.restore.v1").isFile)
        assertTrue(transaction.reconcile() is DatabaseRestoreResult.Restored)
        assertFalse(File(target.path + "-wal").exists())
        assertFalse(File(target.path + "-shm").exists())
        assertTrue(File(directory, ".ha-paneld.db.restore.v1").isFile)
        assertFalse(transaction.clearRestored("2".repeat(64)))
        assertTrue(transaction.clearRestored(session))
        assertFalse(File(directory, ".ha-paneld.db.restore.v1").exists())
    }

    @Test fun `checkpoint or live sidecar failure mutates no canonical file`() = withFiles { _, target, staged ->
        val failedCheckpoint = DatabaseRestoreTransaction(target, guard)
            .restore(staged, 15, 14, checkpoint = { false })
        assertTrue(failedCheckpoint is DatabaseRestoreResult.Hold)
        assertEquals("schema15", target.readText())
        assertFalse(supersededFile(target, 15).exists())

        File(target.path + "-wal").writeText("committed-frame")
        val liveWal = DatabaseRestoreTransaction(target, guard)
            .restore(staged, 15, 14, checkpoint = { true })
        assertTrue(liveWal is DatabaseRestoreResult.Hold)
        assertEquals("schema15", target.readText())
        assertEquals("committed-frame", File(target.path + "-wal").readText())
        assertFalse(supersededFile(target, 15).exists())
    }

    @Test fun `zero WAL and regular SHM are normalized before main-only restore`() = withFiles { _, target, staged ->
        File(target.path + "-wal").writeBytes(byteArrayOf())
        File(target.path + "-shm").writeText("recognized-app-shm")

        val result = DatabaseRestoreTransaction(
            target,
            guard,
            ownedStableSidecar = { file, requireEmpty -> !requireEmpty || file.length() == 0L },
        )
            .restore(staged, 15, 14, checkpoint = { true })

        assertTrue(result is DatabaseRestoreResult.Restored)
        listOf("-wal", "-shm", "-journal").forEach { suffix ->
            assertFalse(File(target.path + suffix).exists())
            assertFalse(File(supersededFile(target, 15).path + suffix).exists())
        }
    }

    @Test fun `unknown partial and foreign topologies hold`() = withFiles { directory, target, staged ->
        File(directory, ".ha-paneld.db.restore.v1").writeText("corrupt")
        assertTrue(DatabaseRestoreTransaction(target, guard).reconcile() is DatabaseRestoreResult.Hold)
        File(directory, ".ha-paneld.db.restore.v1").delete()

        supersededFile(target, 16).writeText("foreign")
        val result = DatabaseRestoreTransaction(target, guard)
            .restore(staged, 15, 14, checkpoint = { true })
        assertTrue(result is DatabaseRestoreResult.Hold)
        assertEquals("schema15", target.readText())
        assertEquals("foreign", supersededFile(target, 16).readText())
    }

    @Test fun `malformed companion and multiple superseded topologies remain fail closed`() {
        fun assertHeld(label: String, arrange: (File, File, File) -> Unit) =
            withFiles { directory, target, staged ->
                arrange(directory, target, staged)
                val before = directory.listFiles()!!.associate { it.name to it.readSafeBytes() }

                val result = DatabaseRestoreTransaction(target, guard)
                    .restore(staged, 15, 14, checkpoint = { true })

                assertTrue(label, result is DatabaseRestoreResult.Hold)
                assertEquals(label, "schema15", target.readText())
                assertEquals(label, before, directory.listFiles()!!.associate { it.name to it.readSafeBytes() })
            }

        assertHeld("expected destination is a directory") { _, target, _ ->
            assertTrue(supersededFile(target, 15).mkdir())
        }
        assertHeld("expected destination is a symbolic link") { _, target, staged ->
            Files.createSymbolicLink(supersededFile(target, 15).toPath(), staged.toPath())
        }
        assertHeld("expected destination is a hard link to the canonical database") { _, target, _ ->
            Files.createLink(supersededFile(target, 15).toPath(), target.toPath())
        }
        assertHeld("expected destination is empty") { _, target, _ ->
            supersededFile(target, 15).writeBytes(byteArrayOf())
        }
        assertHeld("expected destination has a SQLite companion") { _, target, _ ->
            supersededFile(target, 15).writeText("prior")
            File(supersededFile(target, 15).path + "-wal").writeText("retained-wal")
        }
        assertHeld("expected and foreign destinations both exist") { _, target, _ ->
            supersededFile(target, 15).writeText("prior")
            supersededFile(target, 16).writeText("foreign")
        }
        assertHeld("malformed superseded name exists") { directory, target, _ ->
            File(directory, "${target.name}.vbad.superseded").writeText("malformed")
        }
    }

    @Test fun `fixed restore entries fail closed for corrupt directory live link and dangling link`() =
        withFiles { directory, target, staged ->
            val names = listOf(
                ".ha-paneld.db.restore.v1",
                ".ha-paneld.db.restore.v1.tmp",
                ".ha-paneld.db.restore.prepared.v1",
            )
            assertTrue(DatabaseRestoreTransaction(target, guard).reconcile() is DatabaseRestoreResult.Absent)
            names.forEach { name ->
                val entry = File(directory, name)
                val cases = buildList<Pair<String, () -> Unit>> {
                    if (name != ".ha-paneld.db.restore.prepared.v1") {
                        add("corrupt" to { entry.writeText("corrupt") })
                    }
                    add("directory" to { assertTrue(entry.mkdir()) })
                    add("live-link" to { Files.createSymbolicLink(entry.toPath(), staged.toPath()) })
                    add("dangling-link" to {
                        Files.createSymbolicLink(entry.toPath(), File(directory, "missing-target").toPath())
                    })
                }
                cases.forEach { (case, create) ->
                    create()
                    val transaction = DatabaseRestoreTransaction(target, guard)
                    assertTrue("$name $case must hold", transaction.reconcile() is DatabaseRestoreResult.Hold)
                    assertFalse("$name $case must block terminal clear", transaction.clearRestored(session))
                    Files.delete(entry.toPath())
                }
            }
            val prepared = File(directory, ".ha-paneld.db.restore.prepared.v1")
            prepared.writeText("orphan-before-publication")
            assertTrue(DatabaseRestoreTransaction(target, guard).reconcile() is DatabaseRestoreResult.Absent)
            assertFalse(prepared.exists())
        }

    @Test fun `record encoding binds exact target files hashes schemas and Guard generation`() = withFiles {
            directory, target, _ ->
        val record = DatabaseRestoreRecord(
            DatabaseRestoreState.SOURCE_ASIDE,
            directory.absoluteFile.normalize().path,
            target.name,
            15,
            4096,
            "a".repeat(64),
            14,
            2048,
            "b".repeat(64),
            supersededFile(target, 15).name,
            guard,
        )
        val encoded = encodeDatabaseRestoreRecord(record)
        assertEquals(record, parseDatabaseRestoreRecord(encoded))
        assertEquals(null, parseDatabaseRestoreRecord(encoded.copyOf().also { it[20] = 'X'.code.toByte() }))
        assertEquals(null, parseDatabaseRestoreRecord(
            encoded.toString(Charsets.US_ASCII).replace("GUARD $session 16", "GUARD $session 14")
                .toByteArray(Charsets.US_ASCII),
        ))
    }

    private inline fun withFiles(block: (File, File, File) -> Unit) {
        val directory = Files.createTempDirectory("guard-db-restore-").toFile()
        try {
            val target = File(directory, "ha-paneld.db").apply { writeText("schema15") }
            val staged = File(directory, "validated-v14").apply { writeText("schema14") }
            block(directory, target, staged)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun File.readSafeBytes(): List<Byte>? =
        takeIf { Files.isRegularFile(toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) }
            ?.readBytes()?.toList()

    private data object SimulatedProcessDeath : Error()
}
