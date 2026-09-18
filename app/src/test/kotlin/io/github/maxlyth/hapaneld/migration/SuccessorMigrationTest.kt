package io.github.maxlyth.hapaneld.migration

import io.github.maxlyth.hapaneld.migration.SuccessorMigration.Environment
import io.github.maxlyth.hapaneld.migration.SuccessorMigration.Result
import io.github.maxlyth.hapaneld.migration.SuccessorMigration.Step
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SuccessorMigrationTest {
    private class Killed : RuntimeException("process killed")

    /** Durable markers survive a kill; everything else in [World] is the device, which also does. */
    private class FakeMarkers : SuccessorMigration.Markers {
        val values = LinkedHashMap<Step, String>()
        var completeRecorded = false
        var durable = true
        override fun done(step: Step) = step in values
        override fun value(step: Step) = values[step]
        override fun record(step: Step, value: String): Boolean {
            if (durable) values[step] = value
            return durable
        }
        override fun complete() = completeRecorded
        override fun recordComplete(): Boolean {
            if (durable) completeRecorded = true
            return durable
        }
    }

    /** The panel: two packages, one port, one HOME, and the legacy app's own retirement. */
    private class World : SuccessorMigration.Ports {
        var environment = Environment.PASSIVE
        var legacyInstalled = true
        var legacyRetired = false
        var legacyHoldsPort = true
        var home = "legacy"
        var receipt: String? = null
        var pulls = 0
        var receiptRefusal: String? = null
        var releaseRefusal: String? = null
        var retiresOnRelease = true
        var restoreSucceeds = true
        var restored = false
        var heldGrants = mutableSetOf<String>()
        val wantedGrants = setOf("ACCESSIBILITY", "OVERLAY", "BATTERY")
        var failingGrant: String? = null
        var homeClaimWorks = true
        var healthy = true
        var mqttConverged = true
        var uninstallWorks = true
        var uninstalls = 0
        val calls = mutableListOf<String>()

        /** Throw [Killed] when the call counter reaches this value, before the call has any effect. */
        var killAtCall = -1
        private var callCount = 0

        private fun call(name: String) {
            if (callCount++ == killAtCall) throw Killed()
            calls += name
        }

        override fun environment() = environment
        override fun legacyInstalled() = legacyInstalled
        override fun receiptSha256() = receipt
        override suspend fun pullReceipt(): String? {
            call("pull")
            check(!legacyRetired) { "a retired legacy app serves no backup" }
            receipt = "sha-${++pulls}"
            return receipt
        }
        override fun receiptRefusal() = receiptRefusal.also { call("verify") }
        override fun discardReceipt() { call("discard"); receipt = null }
        override suspend fun legacyRetired() = legacyRetired
        override suspend fun requestRelease(): String? {
            call("release")
            if (releaseRefusal != null) return releaseRefusal
            if (retiresOnRelease) {
                legacyRetired = true
                legacyHoldsPort = false
                home = "own"
            }
            return null
        }
        override fun portFree() = !legacyHoldsPort
        override suspend fun restoreReceipt(): Boolean {
            call("restore")
            check(environment != Environment.PASSIVE) { "restore needs the service" }
            check(legacyRetired || !legacyInstalled) { "restored before the legacy app released the panel" }
            restored = restoreSucceeds
            return restoreSucceeds
        }
        override fun missingGrants() = wantedGrants - heldGrants
        override fun claimGrant(grant: String): Boolean {
            call("grant $grant")
            if (grant == failingGrant) return false
            heldGrants += grant
            return true
        }
        override fun claimHome(): Boolean {
            call("home")
            if (homeClaimWorks) home = "own"
            return homeClaimWorks
        }
        override fun homeSettled() = home == "own"
        override fun healthy() = healthy
        override fun mqttConverged() = mqttConverged
        override fun uninstallLegacy(): Boolean {
            call("uninstall")
            check(home == "own") { "legacy package removed while it was still HOME" }
            check(restored) { "legacy package removed before the restore" }
            uninstalls++
            if (uninstallWorks) legacyInstalled = false
            return uninstallWorks
        }
    }

    private fun pass(world: World, markers: FakeMarkers) = runBlocking { SuccessorMigration(world, markers).pass() }

    /** Drive the environment transitions the runner performs, until the machine stops asking for one. */
    private fun runToRest(world: World, markers: FakeMarkers, maxPasses: Int = 12): Result {
        var result: Result = Result.NotNeeded
        repeat(maxPasses) {
            result = pass(world, markers)
            when (result) {
                Result.NeedsHeldService -> world.environment = Environment.HELD_SERVICE
                Result.NeedsRestart -> world.environment = Environment.SERVICE
                else -> return result
            }
        }
        return result
    }

    @Test fun homeIsSettledOnlyWhenRemovingTheLegacyPackageCannotStrandTheLauncher() {
        val own = "io.panelassistant.android"
        val legacy = "io.github.maxlyth.hapaneld"

        assertTrue(homeSettled(own, own, legacy))
        assertTrue("an owner's own launcher is kept", homeSettled("com.example.launcher", own, legacy))
        assertFalse(homeSettled(legacy, own, legacy))
        assertFalse("the resolver is not a HOME", homeSettled("android", own, legacy))
        assertFalse(homeSettled(null, own, legacy))
    }

    @Test fun aPanelWithOnlyTheSuccessorInstalledNeverStartsAMigration() {
        val world = World().apply { legacyInstalled = false }
        val markers = FakeMarkers()

        assertEquals(Result.NotNeeded, pass(world, markers))
        assertEquals(emptyList<String>(), world.calls)
        assertTrue(markers.values.isEmpty())
    }

    @Test fun theStepsRunInTheOneSafeOrder() {
        val world = World()
        val markers = FakeMarkers()

        assertEquals(Result.Complete, runToRest(world, markers))

        assertEquals(
            listOf(
                "pull", "verify", "release", "restore",
                "grant ACCESSIBILITY", "grant OVERLAY", "grant BATTERY", "uninstall",
            ),
            world.calls,
        )
        assertFalse(world.legacyInstalled)
        assertEquals(1, world.uninstalls)
        assertTrue(markers.completeRecorded)
    }

    @Test fun thePassiveSuccessorStopsAtTheServiceBoundaryAndTheHeldServiceAtTheRestart() {
        val world = World()
        val markers = FakeMarkers()

        assertEquals(Result.NeedsHeldService, pass(world, markers))
        assertFalse("nothing is restored while passive", world.restored)

        world.environment = Environment.HELD_SERVICE
        assertEquals(Result.NeedsRestart, pass(world, markers))
        assertTrue(world.restored)
        assertTrue("no grant is claimed before the restart", world.heldGrants.isEmpty())
    }

    @Test fun aRefusedReleaseLeavesTheSuccessorPassiveWithNothingTaken() {
        val world = World().apply { releaseRefusal = "helper-not-confirmed" }
        val markers = FakeMarkers()

        assertEquals(Result.Waiting(Step.RELEASE, "helper-not-confirmed"), pass(world, markers))

        assertEquals(listOf("pull", "verify", "release"), world.calls)
        assertEquals("legacy", world.home)
        assertTrue(world.legacyHoldsPort)
        assertFalse(markers.done(Step.RELEASE))
    }

    @Test fun anAdmittedReleaseIsNotTrustedUntilTheLegacyAppReportsItselfRetired() {
        val world = World().apply { retiresOnRelease = false }
        val markers = FakeMarkers()

        assertEquals(Result.Waiting(Step.RELEASE, "legacy app has not retired yet"), pass(world, markers))
        assertFalse(markers.done(Step.RELEASE))
    }

    @Test fun aHeldPortKeepsTheSuccessorPassive() {
        val world = World()
        val markers = FakeMarkers()
        pass(world, markers)
        world.legacyHoldsPort = true

        assertEquals(
            Result.Waiting(Step.AWAIT_PORT, "legacy app still holds the HTTP port"),
            pass(world, markers),
        )
    }

    @Test fun aReceiptThatDoesNotVerifyIsDiscardedAndPulledAgainAndNeverReleasesThePanel() {
        val world = World().apply { receiptRefusal = "discovery id is not this device" }
        val markers = FakeMarkers()

        assertEquals(Result.Waiting(Step.VERIFY, "discovery id is not this device"), pass(world, markers))
        assertEquals(listOf("pull", "verify", "discard"), world.calls)

        world.receiptRefusal = null
        assertEquals(Result.NeedsHeldService, pass(world, markers))
        assertEquals(2, world.pulls)
    }

    @Test fun everyPassPullsAFreshReceiptWhileTheLegacyAppStillRuns() {
        val world = World().apply { releaseRefusal = "helper-not-confirmed" }
        val markers = FakeMarkers()
        pass(world, markers)
        pass(world, markers)

        assertEquals(2, world.pulls)
        assertEquals("sha-2", markers.value(Step.VERIFY))
    }

    @Test fun aLostReceiptAfterReleaseNeverRemovesTheLegacyPackage() {
        val world = World()
        val markers = FakeMarkers()
        pass(world, markers)
        world.receipt = null
        world.environment = Environment.SERVICE

        assertEquals(Result.Waiting(Step.PULL, "no receipt was kept and none can be pulled"), runToRest(world, markers))
        assertTrue(world.legacyInstalled)
        assertEquals(0, world.uninstalls)
    }

    @Test fun aFailedRestoreIsRepeatedAndBlocksEverythingAfterIt() {
        val world = World().apply { restoreSucceeds = false }
        val markers = FakeMarkers()

        assertEquals(Result.Waiting(Step.RESTORE, "restore did not complete"), runToRest(world, markers))
        assertFalse(markers.done(Step.RESTORE))
        assertEquals(0, world.uninstalls)
    }

    @Test fun aGrantThatCannotBeClaimedBlocksTheRemoval() {
        val world = World().apply { failingGrant = "OVERLAY" }
        val markers = FakeMarkers()

        assertEquals(Result.Waiting(Step.CLAIM, "grants not claimed: OVERLAY"), runToRest(world, markers))
        assertTrue(world.legacyInstalled)
    }

    @Test fun onlyGrantsTheSuccessorDoesNotHoldAreClaimed() {
        val world = World().apply { heldGrants += setOf("ACCESSIBILITY", "BATTERY") }
        val markers = FakeMarkers()

        runToRest(world, markers)

        assertEquals(listOf("grant OVERLAY"), world.calls.filter { it.startsWith("grant") })
    }

    @Test fun homeIsClaimedWhenTheLegacyAppCouldNotHandItOver() {
        val world = World()
        val markers = FakeMarkers()
        pass(world, markers)
        world.home = "legacy"

        assertEquals(Result.Complete, runToRest(world, markers))
        assertTrue(world.calls.contains("home"))
    }

    @Test fun anUnclaimableHomeBlocksTheRemoval() {
        val world = World().apply { homeClaimWorks = false }
        val markers = FakeMarkers()
        pass(world, markers)
        world.home = "legacy"

        assertEquals(Result.Waiting(Step.CLAIM, "HOME was not claimed"), runToRest(world, markers))
        assertEquals(0, world.uninstalls)
    }

    @Test fun removalWaitsForHealthThenMqttThenHome() {
        val markers = FakeMarkers()
        val world = World().apply { healthy = false; mqttConverged = false }

        assertEquals(Result.Waiting(Step.CONFIRM, "health check failed"), runToRest(world, markers))
        world.healthy = true
        assertEquals(Result.Waiting(Step.CONFIRM, "MQTT has not converged"), runToRest(world, markers))
        world.mqttConverged = true
        world.home = "legacy"
        assertEquals(Result.Waiting(Step.CONFIRM, "HOME does not resolve to this app"), runToRest(world, markers))
        assertEquals(0, world.uninstalls)
    }

    @Test fun homeIsQueriedAgainAtTheRemovalWhateverAnEarlierPassConfirmed() {
        val world = World().apply { uninstallWorks = false }
        val markers = FakeMarkers()
        runToRest(world, markers)
        assertTrue(markers.done(Step.CONFIRM))
        world.uninstallWorks = true
        world.home = "legacy"

        assertEquals(Result.Waiting(Step.UNINSTALL, "HOME does not resolve to this app"), runToRest(world, markers))
        assertEquals("the failed attempt is the only one", 1, world.uninstalls)
        assertTrue(world.legacyInstalled)
    }

    @Test fun aRemovalThatLeavesThePackageInstalledIsNotComplete() {
        val world = World().apply { uninstallWorks = false }
        val markers = FakeMarkers()

        assertEquals(Result.Waiting(Step.UNINSTALL, "legacy package was not removed"), runToRest(world, markers))
        assertFalse(markers.completeRecorded)
    }

    @Test fun aCompletedMigrationNeverTouchesThePanelAgain() {
        val world = World()
        val markers = FakeMarkers()
        runToRest(world, markers)
        world.calls.clear()
        world.legacyInstalled = true // someone reinstalled the old package

        assertEquals(Result.Complete, runToRest(world, markers))
        assertEquals(emptyList<String>(), world.calls)
        assertEquals(1, world.uninstalls)
    }

    @Test fun aLegacyPackageRemovedByHandStillRestoresFromTheKeptReceipt() {
        val world = World()
        val markers = FakeMarkers()
        world.releaseRefusal = "helper-not-confirmed"
        pass(world, markers)
        world.legacyInstalled = false
        world.legacyHoldsPort = false
        world.home = "own"

        assertEquals(Result.Complete, runToRest(world, markers))
        assertTrue(world.restored)
        assertEquals(0, world.uninstalls)
    }

    @Test fun aMarkerThatIsNotDurableStopsThePassAtThatStep() {
        val world = World()
        val markers = FakeMarkers().apply { durable = false }

        assertEquals(Result.Waiting(Step.PULL, "marker not durable"), pass(world, markers))
        assertEquals(listOf("pull"), world.calls)
    }

    /**
     * Kill the process before every port call in turn, then let the machine run again on the same
     * device and markers. Whatever instant the kill lands on, the retry must converge, remove the
     * legacy package exactly once, and never remove it while it is HOME or before the restore, which
     * [World.uninstallLegacy] and [World.restoreReceipt] enforce on every call.
     */
    @Test fun aKillBeforeAnyStepConvergesOnTheRetry() {
        val uninterrupted = World().also { runToRest(it, FakeMarkers()) }.calls.size
        assertTrue("the sweep must cover every call of a whole migration", uninterrupted >= 8)

        for (killAt in 0 until uninterrupted) {
            val world = World().apply { killAtCall = killAt }
            val markers = FakeMarkers()
            val killed = runCatching { runToRest(world, markers) }.exceptionOrNull()
            assertTrue("call $killAt must be reached", killed is Killed)

            // The process restarts passive unless it had already restored, as the runner decides.
            world.killAtCall = -1
            world.environment = if (markers.done(Step.RESTORE)) Environment.SERVICE else Environment.PASSIVE
            assertEquals("kill before call $killAt", Result.Complete, runToRest(world, markers))
            assertEquals("kill before call $killAt", 1, world.uninstalls)
            assertFalse(world.legacyInstalled)
            assertEquals("own", world.home)
        }
    }

    /** The same sweep, with the kill landing after the call took effect but before its marker. */
    @Test fun aKillBetweenAnEffectAndItsMarkerConvergesOnTheRetry() {
        for (step in Step.entries) {
            val world = World()
            val markers = object : SuccessorMigration.Markers {
                val inner = FakeMarkers()
                var armed = true
                override fun done(step: Step) = inner.done(step)
                override fun value(step: Step) = inner.value(step)
                override fun record(recorded: Step, value: String): Boolean {
                    if (armed && recorded == step) { armed = false; throw Killed() }
                    return inner.record(recorded, value)
                }
                override fun complete() = inner.complete()
                override fun recordComplete() = inner.recordComplete()
            }

            val drive = {
                var result: Result = Result.NotNeeded
                for (i in 0 until 12) {
                    result = runBlocking { SuccessorMigration(world, markers).pass() }
                    when (result) {
                        Result.NeedsHeldService -> world.environment = Environment.HELD_SERVICE
                        Result.NeedsRestart -> world.environment = Environment.SERVICE
                        else -> break
                    }
                }
                result
            }
            val killed = runCatching { drive() }.exceptionOrNull()
            if (step == Step.AWAIT_PORT) {
                assertEquals("the port wait records nothing", null, killed)
                continue
            }
            assertTrue("marker for $step must be reached", killed is Killed)

            world.environment = if (markers.done(Step.RESTORE)) Environment.SERVICE else Environment.PASSIVE
            assertEquals("kill before the $step marker", Result.Complete, drive())
            assertTrue("kill before the $step marker", world.uninstalls <= 1)
            assertFalse(world.legacyInstalled)
        }
    }
}
