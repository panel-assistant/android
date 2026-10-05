package io.panelassistant.android.shizuku

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuPolicyTest {
    @Test fun acceptsOnlyExpectedShellIdentityAndProtocol() {
        assertEquals(3, ShizukuPolicy.PROTOCOL_VERSION)
        assertTrue(ShizukuPolicy.usable(2000, ShizukuPolicy.PROTOCOL_VERSION))
        assertFalse(ShizukuPolicy.usable(0, ShizukuPolicy.PROTOCOL_VERSION))
        assertFalse(ShizukuPolicy.usable(2000, ShizukuPolicy.PROTOCOL_VERSION - 1))
    }

    @Test fun protocolUpgradeChangesTheRetainedUserServiceCacheIdentity() {
        val retainedV2Tag = ShizukuPolicy.userServiceTag(2)

        assertEquals("hapaneld-shell-v2", retainedV2Tag)
        assertEquals("hapaneld-shell-v3", ShizukuPolicy.USER_SERVICE_TAG)
        assertFalse(retainedV2Tag == ShizukuPolicy.USER_SERVICE_TAG)
    }

    @Test fun typedArgumentsAreBoundedBeforeCrossingBinder() {
        assertTrue(ShizukuPolicy.validDensity(80))
        assertTrue(ShizukuPolicy.validDensity(640))
        assertFalse(ShizukuPolicy.validDensity(79))
        assertFalse(ShizukuPolicy.validCoordinate(-1))
        assertFalse(ShizukuPolicy.validFontScale(Float.NaN))
        assertFalse(ShizukuPolicy.validApkLength(0))
        assertFalse(ShizukuPolicy.validApkLength(ShizukuPolicy.MAX_APK_BYTES + 1))
    }

    @Test fun managerStatusTextExplainsEveryLifecycleState() {
        ShizukuState.entries.forEach { state ->
            assertTrue("missing text for $state", ShizukuSetupDialog.description(state).isNotBlank())
        }
        assertEquals(ShizukuManagerIdentity.PACKAGE, ShizukuPolicy.MANAGER_PACKAGE)
    }

    @Test fun setupEntryIsHiddenUntilTheEscapeHatchIsInstalledOrConsented() {
        assertFalse(ShizukuSetupDialog.entryVisible(false, ShizukuManagerIdentity.Status.MISSING))
        assertTrue(ShizukuSetupDialog.entryVisible(false, ShizukuManagerIdentity.Status.TRUSTED))
        assertTrue(ShizukuSetupDialog.entryVisible(false, ShizukuManagerIdentity.Status.UNTRUSTED))
        assertTrue(ShizukuSetupDialog.entryVisible(true, ShizukuManagerIdentity.Status.MISSING))
    }

    @Test fun localConsentDisabledIsDistinctFromAStoppedShizukuService() {
        assertEquals(
            ShizukuState.DISABLED,
            ShizukuPolicy.idleState(ShizukuManagerIdentity.Status.TRUSTED, consentEnabled = false),
        )
        assertEquals(
            ShizukuState.STOPPED,
            ShizukuPolicy.idleState(ShizukuManagerIdentity.Status.TRUSTED, consentEnabled = true),
        )
        assertEquals(
            ShizukuState.MANAGER_MISSING,
            ShizukuPolicy.idleState(ShizukuManagerIdentity.Status.MISSING, consentEnabled = false),
        )
        assertEquals(
            ShizukuState.ERROR,
            ShizukuPolicy.disconnectedState(
                ShizukuManagerIdentity.Status.TRUSTED,
                consentEnabled = true,
                managerRunning = true,
            ),
        )
        assertEquals(
            ShizukuState.STOPPED,
            ShizukuPolicy.disconnectedState(
                ShizukuManagerIdentity.Status.TRUSTED,
                consentEnabled = true,
                managerRunning = false,
            ),
        )

        assertTrue(ShizukuSetupDialog.description(ShizukuState.DISABLED).contains("Choose Enable"))
        assertTrue(ShizukuSetupDialog.description(ShizukuState.STOPPED).contains("service is stopped"))
        assertEquals(
            ShizukuSetupDialog.PrimaryAction.ENABLE,
            ShizukuSetupDialog.primaryAction(false, ShizukuState.DISABLED, managerRunning = true),
        )
        assertEquals(
            ShizukuSetupDialog.PrimaryAction.OPEN_MANAGER,
            ShizukuSetupDialog.primaryAction(true, ShizukuState.STOPPED, managerRunning = false),
        )
    }

    @Test fun staleDisabledOrUntrustedBindingCannotBecomeReady() {
        assertFalse(ShizukuPolicy.canAcceptBinding(1, 2, true, true, true, true))
        assertFalse(ShizukuPolicy.canAcceptBinding(2, 2, true, false, true, true))
        assertFalse(ShizukuPolicy.canAcceptBinding(2, 2, true, true, false, true))
        assertFalse(ShizukuPolicy.canAcceptBinding(2, 2, false, true, true, true))
        assertTrue(ShizukuPolicy.canAcceptBinding(2, 2, true, true, true, true))
    }

    @Test fun staleRejectedBindingCannotRemoveNewerUserService() {
        assertEquals(
            ShizukuPolicy.RejectedBindingDisposition.IGNORE_STALE,
            ShizukuPolicy.rejectedBindingDisposition(1, 2, true, ShizukuState.ERROR),
        )
        assertEquals(
            ShizukuPolicy.RejectedBindingDisposition.IGNORE_STALE,
            ShizukuPolicy.rejectedBindingDisposition(2, 2, false, ShizukuState.ERROR),
        )
        assertEquals(
            ShizukuPolicy.RejectedBindingDisposition.REMOVE_CURRENT_AND_RECONNECT,
            ShizukuPolicy.rejectedBindingDisposition(2, 2, true, ShizukuState.ERROR),
        )
        assertEquals(
            ShizukuPolicy.RejectedBindingDisposition.REMOVE_CURRENT,
            ShizukuPolicy.rejectedBindingDisposition(2, 2, true, ShizukuState.INCOMPATIBLE),
        )
    }

    @Test fun deniedPermissionRequiresManualRecoveryInsteadOfAnotherPrompt() {
        assertFalse(ShizukuPolicy.shouldRequestPermission(true, rationaleRequired = true))
        assertTrue(ShizukuPolicy.shouldRequestPermission(true, rationaleRequired = false))
        assertFalse(ShizukuPolicy.shouldRequestPermission(false, rationaleRequired = true))
        assertFalse(ShizukuPolicy.shouldRequestPermission(false, rationaleRequired = false))
    }

    @Test fun deniedPermissionOffersActionableShizukuManagerRecovery() {
        assertEquals(
            ShizukuSetupDialog.PrimaryAction.OPEN_MANAGER,
            ShizukuSetupDialog.primaryAction(
                consented = true,
                state = ShizukuState.MANUAL_GRANT_REQUIRED,
                managerRunning = true,
            ),
        )
        val text = ShizukuSetupDialog.description(ShizukuState.MANUAL_GRANT_REQUIRED)
        assertTrue(text.contains("Authorized applications"))
        assertTrue(text.contains("grant access manually"))
    }

    @Test fun freshPermissionRequestRemainsDistinctFromManualRecovery() {
        assertEquals(
            ShizukuSetupDialog.PrimaryAction.REQUEST_PERMISSION,
            ShizukuSetupDialog.primaryAction(
                consented = true,
                state = ShizukuState.PERMISSION_REQUIRED,
                managerRunning = true,
            ),
        )
    }

    @Test fun outerIpcDeadlineExceedsServiceDeadlineAndReaderJoin() {
        assertEquals(15_000L, ShizukuPolicy.clientDeadline(10_000))
        assertEquals(185_000L, ShizukuPolicy.clientDeadline(180_000))
        assertEquals(Long.MAX_VALUE, ShizukuPolicy.clientDeadline(Long.MAX_VALUE))
    }

    @Test fun installDeadlineIsPositiveAndCappedBeforeCrossingBinder() {
        assertEquals(null, ShizukuPolicy.installServiceDeadline(0))
        assertEquals(42L, ShizukuPolicy.installServiceDeadline(42))
        assertEquals(
            ShizukuPolicy.MAX_INSTALL_DEADLINE_MS,
            ShizukuPolicy.installServiceDeadline(Long.MAX_VALUE),
        )
    }

    @Test fun missingOrReplacedManagerNeverTrapsLocalConsent() {
        assertTrue(ShizukuSetupDialog.disableAvailable(true, ShizukuState.MANAGER_MISSING))
        assertTrue(ShizukuSetupDialog.disableAvailable(true, ShizukuState.MANAGER_UNTRUSTED))
        assertFalse(ShizukuSetupDialog.disableAvailable(false, ShizukuState.MANAGER_MISSING))
    }

    @Test fun reconnectIsDeferredSinglePendingGenerationWithBoundedBackoff() {
        val scheduler = FakeScheduler()
        val coordinator = ShizukuReconnectCoordinator(scheduler, longArrayOf(50L, 100L, 200L))
        val runs = mutableListOf<Long>()

        assertTrue(coordinator.schedule(7) { runs += 7 })
        assertFalse(coordinator.schedule(7) { runs += -1 })
        assertEquals(50L, scheduler.tasks.single().delayMs)
        scheduler.run(0)
        assertEquals(listOf(7L), runs)

        assertTrue(coordinator.schedule(8) { runs += 8 })
        assertEquals(100L, scheduler.tasks[1].delayMs)
        scheduler.run(1)
        assertTrue(coordinator.schedule(9) { runs += 9 })
        assertEquals(200L, scheduler.tasks[2].delayMs)
        scheduler.run(2)
        assertTrue(coordinator.schedule(10) { runs += 10 })
        assertEquals(200L, scheduler.tasks[3].delayMs)
    }

    @Test fun cancelledReconnectRejectsStaleGenerationAndCanResetBackoff() {
        val scheduler = FakeScheduler()
        val coordinator = ShizukuReconnectCoordinator(scheduler, longArrayOf(50L, 100L))
        val runs = AtomicInteger()

        assertTrue(coordinator.schedule(1) { runs.incrementAndGet() })
        coordinator.cancel(resetBackoff = false)
        scheduler.run(0)
        assertEquals(0, runs.get())
        assertTrue(coordinator.schedule(2) { runs.incrementAndGet() })
        assertEquals(100L, scheduler.tasks[1].delayMs)
        coordinator.cancel(resetBackoff = true)
        assertTrue(coordinator.schedule(3) { runs.incrementAndGet() })
        assertEquals(50L, scheduler.tasks[2].delayMs)
    }

    @Test fun synchronousSchedulerCannotStrandReconnectPublication() {
        val runs = AtomicInteger()
        val coordinator = ShizukuReconnectCoordinator(
            ShizukuScheduler { _, action ->
                action()
                ShizukuScheduledHandle {}
            },
            longArrayOf(50L),
        )

        assertTrue(coordinator.schedule(1) { runs.incrementAndGet() })
        assertTrue(coordinator.schedule(2) { runs.incrementAndGet() })
        assertEquals(2, runs.get())
    }

    @Test fun rejectedScheduleDoesNotStrandReconnectPublication() {
        val calls = AtomicInteger()
        val scheduler = ShizukuScheduler { _, _ ->
            if (calls.getAndIncrement() == 0) error("scheduler rejected task")
            ShizukuScheduledHandle {}
        }
        val coordinator = ShizukuReconnectCoordinator(scheduler, longArrayOf(50L))

        assertFalse(coordinator.schedule(1) {})
        assertTrue(coordinator.schedule(2) {})
    }

    @Test fun stableBackoffResetReschedulesAfterStableReadyPeriod() {
        val scheduler = FakeScheduler()
        val coordinator = ShizukuReconnectCoordinator(scheduler, longArrayOf(50L, 100L))

        // Drive backoff to the cap.
        assertTrue(coordinator.schedule(1) {})
        scheduler.run(0)
        assertTrue(coordinator.schedule(2) {})
        assertEquals(100L, scheduler.tasks[1].delayMs)
        scheduler.run(1)

        // A stable READY period resets the backoff via the coordinator-owned handle.
        assertTrue(coordinator.scheduleBackoffReset(30_000L) { coordinator.resetBackoff() })
        assertEquals(30_000L, scheduler.tasks[2].delayMs)
        scheduler.run(2)

        assertTrue(coordinator.schedule(3) {})
        assertEquals(50L, scheduler.tasks[3].delayMs)
    }

    @Test fun cancelledStableBackoffResetLeavesBackoffAtTheCap() {
        val scheduler = FakeScheduler()
        val coordinator = ShizukuReconnectCoordinator(scheduler, longArrayOf(50L, 100L))

        assertTrue(coordinator.schedule(1) {})
        scheduler.run(0)
        assertTrue(coordinator.schedule(2) {})
        assertEquals(100L, scheduler.tasks[1].delayMs)
        scheduler.run(1)

        assertTrue(coordinator.scheduleBackoffReset(30_000L) { coordinator.resetBackoff() })
        coordinator.cancelBackoffReset()
        scheduler.run(2)

        assertTrue(coordinator.schedule(3) {})
        assertEquals(100L, scheduler.tasks[3].delayMs)
    }

    @Test fun reschedulingStableBackoffResetCancelsThePriorHandle() {
        val scheduler = FakeScheduler()
        val coordinator = ShizukuReconnectCoordinator(scheduler, longArrayOf(50L, 100L))
        val resets = AtomicInteger()

        assertTrue(coordinator.schedule(1) {})
        scheduler.run(0)

        assertTrue(coordinator.scheduleBackoffReset(30_000L) { resets.incrementAndGet() })
        assertTrue(coordinator.scheduleBackoffReset(30_000L) { resets.incrementAndGet() })
        scheduler.run(1)
        scheduler.run(2)
        assertEquals(1, resets.get())
    }

    @Test fun rejectedBindingMutationCrossesCallbackBarrierThenLeavesMain() {
        val callbackQueue = mutableListOf<Runnable>()
        val workerQueue = mutableListOf<Runnable>()
        val mutations = AtomicInteger()
        val dispatcher = ShizukuCallbackMutationDispatcher(
            postBarrier = { callbackQueue.add(it) },
            dispatchOffMain = { workerQueue.add(it) },
        )

        assertTrue(dispatcher.dispatch(Runnable { mutations.incrementAndGet() }))
        assertEquals(0, mutations.get())
        assertTrue(workerQueue.isEmpty())

        callbackQueue.single().run()
        assertEquals(0, mutations.get())
        assertEquals(1, workerQueue.size)

        workerQueue.single().run()
        assertEquals(1, mutations.get())
    }

    private class FakeScheduler : ShizukuScheduler {
        data class Task(val delayMs: Long, val action: () -> Unit, var cancelled: Boolean = false)

        val tasks = mutableListOf<Task>()

        override fun schedule(delayMs: Long, action: () -> Unit): ShizukuScheduledHandle {
            val task = Task(delayMs, action)
            tasks += task
            return ShizukuScheduledHandle { task.cancelled = true }
        }

        fun run(index: Int) {
            tasks[index].takeUnless { it.cancelled }?.action?.invoke()
        }
    }
}
