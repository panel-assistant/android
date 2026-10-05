package io.panelassistant.android.sensors

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class HaLifecycleRuntimeTest {
    private var now = 0L
    private var installed: HaLifecycleCoordinator? = null

    private fun coordinator() = HaLifecycleCoordinator(nowMs = { now })

    private fun install(): HaLifecycleCoordinator = coordinator().also {
        HaLifecycleRuntime.install(it)
        installed = it
    }

    @Before fun reset() { now = 0L }
    @After fun teardown() { installed?.let(HaLifecycleRuntime::uninstall) }

    @Test fun anUninstalledRuntimeClaimsNothingAndNeverThrows() {
        assertNull(HaLifecycleRuntime.statusText())
        assertNull("no owner means no snapshot, not a default one", HaLifecycleRuntime.snapshot())
        assertFalse(HaLifecycleRuntime.watching)
    }

    @Test fun aPanelWatchingWithNeitherSourceSaysNothingAtAll() {
        val c = install()
        HaLifecycleRuntime.setNativeWatching(c, false)
        assertNull("no source means the row is omitted entirely", HaLifecycleRuntime.statusText())
    }

    @Test fun aPredecessorsLateTeardownCannotEraseTheSuccessorsInstallation() {
        val predecessor = install()
        val successor = coordinator()
        HaLifecycleRuntime.install(successor)
        installed = successor
        HaLifecycleRuntime.setNativeWatching(successor, true)
        successor.onNativeNotice(HaLifecycleNotice(HaLifecyclePhase.SHUTTING_DOWN, HaLifecycleReason.UNKNOWN, null, null))

        assertFalse("a superseded owner clears nothing", HaLifecycleRuntime.uninstall(predecessor))
        assertEquals(
            "the successor's live state survives the predecessor's timed-out teardown",
            HaLifecycleState.SHUTTING_DOWN,
            HaLifecycleRuntime.snapshot()?.state,
        )
    }

    @Test fun uninstallingTheCurrentOwnerReportsItSoConsumersCanBeToldToRedraw() {
        val c = install()
        assertTrue("clearing the live owner is a change consumers must hear about", HaLifecycleRuntime.uninstall(c))
        assertNull(HaLifecycleRuntime.snapshot())
        installed = null
    }

    @Test fun aSupersededOwnersWatchFlagWriteIsIgnored() {
        val predecessor = install()
        val successor = coordinator()
        HaLifecycleRuntime.install(successor)
        installed = successor

        HaLifecycleRuntime.setNativeWatching(predecessor, true)
        assertFalse("a dead owner cannot claim the native route is watched", HaLifecycleRuntime.watching)
        HaLifecycleRuntime.setNativeWatching(successor, true)
        assertTrue(HaLifecycleRuntime.watching)
    }

    @Test fun installingReplacesTheWholeOwnershipAtomically() {
        val predecessor = install()
        HaLifecycleRuntime.setNativeWatching(predecessor, true)
        val successor = coordinator()
        HaLifecycleRuntime.install(successor)
        installed = successor
        assertFalse(
            "the predecessor's watch flag does not leak into the successor's installation",
            HaLifecycleRuntime.watching,
        )
    }

    @Test fun disablingTheLastWatchRetiresEverythingConsumersCanRender() {
        val c = install()
        HaLifecycleRuntime.setNativeWatching(c, true)
        c.onNativeNotice(HaLifecycleNotice(HaLifecyclePhase.SHUTTING_DOWN, HaLifecycleReason.UNKNOWN, null, null))
        assertEquals(HaLifecycleState.SHUTTING_DOWN, HaLifecycleRuntime.snapshot()?.state)

        assertTrue("the caller must learn it has to poke consumers", HaLifecycleRuntime.setNativeWatching(c, false))
        assertNull("an unreportable holder renders nothing", HaLifecycleRuntime.snapshot())
        assertNull(HaLifecycleRuntime.statusText())
    }

    @Test fun anUnchangedWatchFlagReportsNoChangeSoConsumersAreNotWokenForNothing() {
        val c = install()
        assertTrue(HaLifecycleRuntime.setNativeWatching(c, true))
        assertFalse("setting the same value twice is not a change", HaLifecycleRuntime.setNativeWatching(c, true))
    }

    @Test fun aSnapshotIsDiscardedIfOwnershipChangedWhileItWasRead() {
        val c = install()
        HaLifecycleRuntime.setNativeWatching(c, true)
        c.onNativeNotice(HaLifecycleNotice(HaLifecyclePhase.SHUTTING_DOWN, HaLifecycleReason.UNKNOWN, null, null))
        assertEquals(HaLifecycleState.SHUTTING_DOWN, HaLifecycleRuntime.snapshot()?.state)

        HaLifecycleRuntime.uninstall(c)
        installed = null
        assertNull("a superseded owner's state is never returned", HaLifecycleRuntime.snapshot())
    }
}
