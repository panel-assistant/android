package io.panelassistant.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RendererPreparationCoordinatorTest {
    private val borrowed = BorrowedRendererSettings(
        url = "http://ha:8123",
        accessToken = "access",
        refreshToken = "refresh",
        tokenExpiry = 1234L,
        clientId = "client",
        zoom = 125,
    )

    @Test fun zeroTimeoutCloseRejectsNewTransactionsWithoutWaitingForAnAdmittedOne() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val coordinator = RendererPreparationCoordinator(
            builtinPackage = "builtin",
            state = { RendererPreparationState("builtin", "http://ha:8123") },
            borrow = { error("ready state must not borrow") },
            persist = { error("ready state must not persist") },
        )
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val running = pool.submit {
                coordinator.transaction {
                    entered.countDown()
                    release.await()
                }
            }
            assertTrue(entered.await(1, java.util.concurrent.TimeUnit.SECONDS))

            assertFalse(coordinator.close(0L))
            assertThrows(IllegalStateException::class.java) { coordinator.transaction {} }

            release.countDown()
            running.get(1, java.util.concurrent.TimeUnit.SECONDS)
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun startupRacingClosedAdmissionReturnsClosedWhileStrictTransactionsStillReject() {
        val transactionEntered = java.util.concurrent.CountDownLatch(1)
        val releaseTransaction = java.util.concurrent.CountDownLatch(1)
        val startupSubmitted = java.util.concurrent.CountDownLatch(1)
        var ensured = false
        var launched = false
        val coordinator = RendererPreparationCoordinator(
            builtinPackage = "builtin",
            state = { RendererPreparationState("builtin", "http://ha:8123") },
            borrow = { error("ready state must not borrow") },
            persist = { error("ready state must not persist") },
        )
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val admitted = pool.submit {
                coordinator.transaction {
                    transactionEntered.countDown()
                    releaseTransaction.await()
                }
            }
            assertTrue(transactionEntered.await(1, java.util.concurrent.TimeUnit.SECONDS))
            val startup = pool.submit<RendererPreparationCoordinator.Result> {
                startupSubmitted.countDown()
                coordinator.reconcileStartup(
                    ensureHome = { _, _ -> ensured = true },
                    launchHome = { launched = true },
                )
            }
            assertTrue(startupSubmitted.await(1, java.util.concurrent.TimeUnit.SECONDS))

            assertFalse(coordinator.close(0L))
            releaseTransaction.countDown()

            admitted.get(1, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(
                RendererPreparationCoordinator.Result.CLOSED,
                startup.get(1, java.util.concurrent.TimeUnit.SECONDS),
            )
            assertFalse(ensured)
            assertFalse(launched)
            assertEquals(RendererPreparationCoordinator.Result.CLOSED, coordinator.prepareIfNeeded())
            assertEquals(
                RendererPreparationCoordinator.Result.CLOSED,
                coordinator.launchConfigured({ _, _ -> ensured = true }, { launched = true }),
            )
            assertThrows(IllegalStateException::class.java) { coordinator.transaction {} }
        } finally {
            releaseTransaction.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun preparationPersistsBeforeEnsureAndLaunch() {
        var state = RendererPreparationState("builtin", "")
        val events = mutableListOf<String>()
        val coordinator = RendererPreparationCoordinator(
            builtinPackage = "builtin",
            state = { state },
            borrow = { events += "borrow"; borrowed },
            persist = {
                events += "persist:${it.url}:${it.zoom}"
                state = state.copy(haUrl = it.url)
                true
            },
        )

        val result = coordinator.launchConfigured(
            ensureHome = { pkg, ready -> events += "ensure:$pkg:$ready" },
            launchHome = { events += "launch:$it" },
        )

        assertEquals(RendererPreparationCoordinator.Result.PREPARED, result)
        assertEquals(
            listOf("borrow", "persist:http://ha:8123:125", "ensure:builtin:true", "launch:builtin"),
            events,
        )
    }

    @Test fun automaticBlankRendererPreparesTheBuiltinDashboard() {
        var state = RendererPreparationState("", "")
        val events = mutableListOf<String>()
        val coordinator = RendererPreparationCoordinator(
            builtinPackage = "builtin",
            state = { state },
            borrow = { events += "borrow"; borrowed },
            persist = { state = state.copy(haUrl = it.url); true },
        )

        assertEquals(
            RendererPreparationCoordinator.Result.PREPARED,
            coordinator.launchConfigured({ pkg, ready -> events += "ensure:$pkg:$ready" }, { events += "launch:$it" }),
        )
        assertEquals(listOf("borrow", "ensure::true", "launch:"), events)
    }

    @Test fun failedPersistLeavesDurableBlankStateForStartupRetry() {
        var state = RendererPreparationState("builtin", "")
        var fail = true
        var launches = 0
        val coordinator = RendererPreparationCoordinator(
            builtinPackage = "builtin",
            state = { state },
            borrow = { borrowed },
            persist = {
                if (fail) false else {
                    state = state.copy(haUrl = it.url)
                    true
                }
            },
        )

        assertEquals(
            RendererPreparationCoordinator.Result.PERSIST_FAILED,
            coordinator.reconcileStartup({ _, _ -> }, { launches++ }),
        )
        assertTrue(state.haUrl.isBlank())
        assertEquals(0, launches)

        fail = false
        assertEquals(
            RendererPreparationCoordinator.Result.PREPARED,
            coordinator.reconcileStartup({ _, _ -> }, { launches++ }),
        )
        assertEquals("http://ha:8123", state.haUrl)
        assertEquals(1, launches)
    }

    @Test fun explicitConnectionIsNeverOverwrittenByBorrow() {
        val state = RendererPreparationState("builtin", "http://explicit:8123")
        var borrowed = false
        val coordinator = RendererPreparationCoordinator(
            builtinPackage = "builtin",
            state = { state },
            borrow = { borrowed = true; this.borrowed },
            persist = { error("must not persist") },
        )

        val result = coordinator.launchConfigured({ _, _ -> }, {})

        assertEquals(RendererPreparationCoordinator.Result.ALREADY_READY, result)
        assertFalse(borrowed)
    }

    @Test fun startupForegroundsReadyBuiltinAfterHomeReconciliation() {
        val state = RendererPreparationState("builtin", "http://explicit:8123")
        val events = mutableListOf<String>()
        val coordinator = RendererPreparationCoordinator(
            builtinPackage = "builtin",
            state = { state },
            borrow = { error("must not borrow") },
            persist = { error("must not persist") },
        )

        assertEquals(
            RendererPreparationCoordinator.Result.ALREADY_READY,
            coordinator.reconcileStartup(
                { pkg, ready -> events += "ensure:$pkg:$ready" },
                { events += "launch:$it" },
            ),
        )

        assertEquals(listOf("ensure:builtin:true", "launch:builtin"), events)
    }

    @Test fun startupForegroundsReadyForeignRendererAfterHomeReconciliation() {
        val state = RendererPreparationState("foreign.renderer", "")
        val events = mutableListOf<String>()
        val coordinator = RendererPreparationCoordinator(
            builtinPackage = "builtin",
            state = { state },
            borrow = { error("foreign renderer must not borrow") },
            persist = { error("foreign renderer must not persist") },
        )

        assertEquals(
            RendererPreparationCoordinator.Result.NOT_BUILTIN,
            coordinator.reconcileStartup(
                { pkg, ready -> events += "ensure:$pkg:$ready" },
                { events += "launch:$it" },
            ),
        )

        assertEquals(listOf("ensure:foreign.renderer:false", "launch:foreign.renderer"), events)
    }

    @Test fun startupAutomaticBuiltinPreparesAndLaunches() {
        var state = RendererPreparationState("", "")
        val ensured = mutableListOf<Pair<String, Boolean>>()
        var launches = 0
        val coordinator = RendererPreparationCoordinator(
            builtinPackage = "builtin",
            state = { state },
            borrow = { borrowed },
            persist = { state = state.copy(haUrl = it.url); true },
        )

        assertEquals(
            RendererPreparationCoordinator.Result.PREPARED,
            coordinator.reconcileStartup({ pkg, ready -> ensured += pkg to ready }, { launches++ }),
        )

        assertEquals(listOf("" to true), ensured)
        assertEquals(1, launches)
    }

    @Test fun startupClosingAfterHomeReconciliationDoesNotLaunchReadyRenderer() {
        val state = RendererPreparationState("builtin", "http://explicit:8123")
        val ensureEntered = java.util.concurrent.CountDownLatch(1)
        val releaseEnsure = java.util.concurrent.CountDownLatch(1)
        var launched = false
        val coordinator = RendererPreparationCoordinator(
            builtinPackage = "builtin",
            state = { state },
            borrow = { error("ready state must not borrow") },
            persist = { error("ready state must not persist") },
        )
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val running = pool.submit<RendererPreparationCoordinator.Result> {
                coordinator.reconcileStartup(
                    ensureHome = { _, _ ->
                        ensureEntered.countDown()
                        releaseEnsure.await()
                    },
                    launchHome = { launched = true },
                )
            }
            assertTrue(ensureEntered.await(1, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse(coordinator.close(100))
            releaseEnsure.countDown()

            assertEquals(
                RendererPreparationCoordinator.Result.CLOSED,
                running.get(1, java.util.concurrent.TimeUnit.SECONDS),
            )
            assertFalse(launched)
        } finally {
            releaseEnsure.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun startupCompletesLaunchPendingAfterProcessDiesBeyondBorrowCommit() {
        var state = RendererPreparationState("builtin", "")
        val firstProcess = RendererPreparationCoordinator(
            builtinPackage = "builtin",
            state = { state },
            borrow = { borrowed },
            persist = {
                state = state.copy(haUrl = it.url, launchPending = true)
                true
            },
        )
        assertEquals(RendererPreparationCoordinator.Result.PREPARED, firstProcess.prepareIfNeeded())

        var launches = 0
        val restartedProcess = RendererPreparationCoordinator(
            builtinPackage = "builtin",
            state = { state },
            borrow = { error("ready state must not borrow again") },
            persist = { error("ready state must not persist again") },
            completeLaunch = {
                state = state.copy(launchPending = false)
                true
            },
        )

        assertEquals(
            RendererPreparationCoordinator.Result.ALREADY_READY,
            restartedProcess.reconcileStartup({ _, _ -> }, { launches++ }),
        )
        assertEquals(1, launches)
        assertFalse(state.launchPending)
    }

    @Test fun startupWithoutBorrowableLoginStaysRetryableAndDoesNotLaunchBlankRenderer() {
        val state = RendererPreparationState("builtin", "")
        var ready = true
        var launches = 0
        val coordinator = RendererPreparationCoordinator("builtin", { state }, { null }, { true })

        val result = coordinator.reconcileStartup({ _, value -> ready = value }, { launches++ })

        assertEquals(RendererPreparationCoordinator.Result.NO_BORROWABLE_LOGIN, result)
        assertFalse(ready)
        assertEquals(0, launches)
        assertTrue(state.haUrl.isBlank())
    }

    @Test fun supersededRendererCannotReceiveBorrowOrStaleLaunch() {
        var state = RendererPreparationState("builtin", "")
        var persisted = false
        val launched = mutableListOf<String>()
        val coordinator = RendererPreparationCoordinator(
            builtinPackage = "builtin",
            state = { state },
            borrow = {
                state = RendererPreparationState("foreign.renderer", "")
                borrowed
            },
            persist = { persisted = true; true },
        )

        val result = coordinator.launchConfigured({ _, _ -> }, { launched += it })

        assertEquals(RendererPreparationCoordinator.Result.SUPERSEDED, result)
        assertFalse(persisted)
        assertEquals(listOf("foreign.renderer"), launched)
    }

    @Test fun transactionPreventsRendererWorkFromInterleaving() {
        val state = RendererPreparationState("foreign.renderer", "")
        val coordinator = RendererPreparationCoordinator("builtin", { state }, { null }, { true })
        val firstEntered = java.util.concurrent.CountDownLatch(1)
        val releaseFirst = java.util.concurrent.CountDownLatch(1)
        val secondEntered = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit {
                coordinator.transaction {
                    firstEntered.countDown()
                    releaseFirst.await()
                }
            }
            assertTrue(firstEntered.await(1, java.util.concurrent.TimeUnit.SECONDS))
            val second = pool.submit { coordinator.transaction { secondEntered.countDown() } }
            assertFalse(secondEntered.await(100, java.util.concurrent.TimeUnit.MILLISECONDS))
            releaseFirst.countDown()
            first.get(1, java.util.concurrent.TimeUnit.SECONDS)
            second.get(1, java.util.concurrent.TimeUnit.SECONDS)
            assertTrue(secondEntered.await(1, java.util.concurrent.TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun closeStopsAnAdmittedBorrowBeforeItCanPersistOrLaunch() {
        val state = RendererPreparationState("builtin", "", launchPending = true)
        val borrowEntered = java.util.concurrent.CountDownLatch(1)
        val releaseBorrow = java.util.concurrent.CountDownLatch(1)
        var persisted = false
        var launched = false
        val coordinator = RendererPreparationCoordinator(
            builtinPackage = "builtin",
            state = { state },
            borrow = {
                borrowEntered.countDown()
                releaseBorrow.await()
                borrowed
            },
            persist = { persisted = true; true },
        )
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val running = pool.submit<RendererPreparationCoordinator.Result> {
                coordinator.launchConfigured({ _, _ -> }, { launched = true })
            }
            assertTrue(borrowEntered.await(1, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse(coordinator.close(100))
            releaseBorrow.countDown()
            assertEquals(
                RendererPreparationCoordinator.Result.CLOSED,
                running.get(1, java.util.concurrent.TimeUnit.SECONDS),
            )
            assertFalse(persisted)
            assertFalse(launched)
            assertTrue(runCatching { coordinator.transaction {} }.isFailure)
        } finally {
            releaseBorrow.countDown()
            pool.shutdownNow()
        }
    }
}
