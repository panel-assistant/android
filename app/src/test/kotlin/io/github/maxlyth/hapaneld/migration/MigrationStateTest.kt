package io.github.maxlyth.hapaneld.migration

import io.github.maxlyth.hapaneld.http.releaseReply
import io.github.maxlyth.hapaneld.migration.SuccessorMigration.Step
import io.ktor.http.HttpStatusCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MigrationStateTest {
    @get:Rule val temp = TemporaryFolder()

    private fun disposition(
        isBridge: Boolean = false,
        bridgeRetired: Boolean = false,
        legacyInstalled: Boolean = false,
        migrationStarted: Boolean = false,
        migrationComplete: Boolean = false,
        released: Boolean = false,
        restored: Boolean = false,
    ) = startDisposition(isBridge, bridgeRetired, legacyInstalled, migrationStarted, migrationComplete, released, restored)

    @Test fun aPanelWithOnePackageStartsExactlyAsItAlwaysDid() {
        assertEquals(StartDisposition.NORMAL, disposition(isBridge = true))
        assertEquals(StartDisposition.NORMAL, disposition(isBridge = false))
    }

    @Test fun aRetiredBridgeStaysIdleWhateverElseIsTrue() {
        assertEquals(StartDisposition.RETIRED_BRIDGE, disposition(isBridge = true, bridgeRetired = true))
    }

    @Test fun aBridgeIsNeverTreatedAsAMigratingSuccessor() {
        assertEquals(
            StartDisposition.NORMAL,
            disposition(isBridge = true, legacyInstalled = true, migrationStarted = true, released = true),
        )
    }

    @Test fun aSuccessorBesideTheLegacyAppIsPassiveUntilThePanelIsReleased() {
        assertEquals(StartDisposition.PASSIVE_SUCCESSOR, disposition(legacyInstalled = true))
        assertEquals(StartDisposition.PASSIVE_SUCCESSOR, disposition(legacyInstalled = true, migrationStarted = true))
    }

    @Test fun aStartedMigrationStaysPassiveEvenIfTheLegacyPackageHasGone() {
        assertEquals(StartDisposition.PASSIVE_SUCCESSOR, disposition(migrationStarted = true))
    }

    @Test fun aReleasedPanelStartsTheServiceHeldAndARestoredOneStartsItNormally() {
        assertEquals(
            StartDisposition.HELD_SUCCESSOR,
            disposition(legacyInstalled = true, migrationStarted = true, released = true),
        )
        assertEquals(
            StartDisposition.NORMAL,
            disposition(legacyInstalled = true, migrationStarted = true, released = true, restored = true),
        )
    }

    @Test fun aCompletedMigrationIsNormalEvenIfTheLegacyPackageReappears() {
        assertEquals(
            StartDisposition.NORMAL,
            disposition(legacyInstalled = true, migrationStarted = true, migrationComplete = true),
        )
    }

    @Test fun activitiesShowOnlyWhenTheServiceWouldRunNormally() {
        assertEquals(ActivityDisposition.SHOW, activityDisposition(StartDisposition.NORMAL))
        assertEquals(ActivityDisposition.SHOW, activityDisposition(StartDisposition.HELD_SUCCESSOR))
        assertEquals(ActivityDisposition.FORWARD_TO_SUCCESSOR, activityDisposition(StartDisposition.RETIRED_BRIDGE))
        assertEquals(ActivityDisposition.RUN_MIGRATION_UNSEEN, activityDisposition(StartDisposition.PASSIVE_SUCCESSOR))
    }

    @Test fun markersAreDurablePerStepAndSurviveANewInstance() {
        val state = MigrationState(temp.root)
        assertFalse(state.started())
        assertFalse(state.done(Step.PULL))

        assertTrue(state.record(Step.PULL, "abc123"))
        assertTrue(state.record(Step.AWAIT_PORT))

        val reopened = MigrationState(temp.root)
        assertTrue(reopened.started())
        assertEquals("abc123", reopened.value(Step.PULL))
        assertEquals("done", reopened.value(Step.AWAIT_PORT))
        assertNull(reopened.value(Step.VERIFY))
        assertFalse(reopened.complete())
    }

    @Test fun eachStepHasItsOwnMarkerFile() {
        val state = MigrationState(temp.root)
        Step.entries.forEach { state.record(it, it.name) }

        Step.entries.forEach { assertEquals(it.name, state.value(it)) }
        assertEquals(Step.entries.size, temp.root.resolve("identity-migration").list()!!.size)
    }

    @Test fun completionIsSeparateFromTheLastStep() {
        val state = MigrationState(temp.root)
        state.record(Step.UNINSTALL)
        assertFalse(state.complete())

        assertTrue(state.recordComplete())
        assertTrue(MigrationState(temp.root).complete())
    }

    @Test fun theLegacyPortIsKeptOnlyWhenItIsAPort() {
        val port = LegacyPort(temp.root)
        assertNull(port.read())
        assertFalse(port.accept(0))
        assertFalse(port.accept(70_000))
        assertNull(port.read())

        assertTrue(port.accept(8889))
        assertEquals(8889, LegacyPort(temp.root).read())
    }

    @Test fun releaseRepliesDistinguishAdmissionRetirementAndEachRefusal() {
        assertEquals(HttpStatusCode.Accepted, releaseReply(BridgeRelease.Outcome.Releasing).first)
        assertEquals("""{"ok":true,"status":"releasing"}""", releaseReply(BridgeRelease.Outcome.Releasing).second)
        assertEquals(HttpStatusCode.OK, releaseReply(BridgeRelease.Outcome.AlreadyReleased).first)

        fun refused(refusal: BridgeRelease.Refusal, detail: String? = null) =
            releaseReply(BridgeRelease.Outcome.Refused(refusal, detail))
        assertEquals(HttpStatusCode.NotFound, refused(BridgeRelease.Refusal.NOT_A_BRIDGE).first)
        assertEquals(HttpStatusCode.Forbidden, refused(BridgeRelease.Refusal.NOT_LOOPBACK).first)
        assertEquals(HttpStatusCode.Forbidden, refused(BridgeRelease.Refusal.BAD_TOKEN).first)
        assertEquals(HttpStatusCode.Forbidden, refused(BridgeRelease.Refusal.UNTRUSTED_SUCCESSOR).first)
        assertEquals(HttpStatusCode.Conflict, refused(BridgeRelease.Refusal.QUIESCE_UNAVAILABLE).first)
        assertEquals(
            HttpStatusCode.Conflict to
                """{"ok":false,"error":"helper-not-confirmed","detail":"running helper is not the bundled build"}""",
            refused(BridgeRelease.Refusal.HELPER_NOT_CONFIRMED, "running helper is not the bundled build"),
        )
    }
}
