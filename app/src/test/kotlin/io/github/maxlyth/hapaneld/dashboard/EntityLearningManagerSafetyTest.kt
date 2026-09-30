package io.github.maxlyth.hapaneld.dashboard

import io.github.maxlyth.hapaneld.util.ByteLimitExceeded
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

class EntityLearningManagerSafetyTest {
    @Test fun bulkIssueIgnorePreflightsNonIgnorableFenceBeforeAnyMutation() {
        val ordinary = JSONObject()
            .put("type", "unbounded_selector")
            .put("blocking", true)
            .put("ignorable", true)
            .put("fingerprint", "0123456789abcdef")
        val fence = JSONObject()
            .put("type", "diagnostic_limit")
            .put("blocking", true)
            .put("ignorable", false)
            .put("fingerprint", "fedcba9876543210")

        val ordinaryOnly = blockingIssueSelection(JSONArray().put(ordinary).toString())
        assertTrue(ordinaryOnly.allIgnorable)
        assertEquals(listOf("0123456789abcdef"), ordinaryOnly.fingerprints)
        val fenced = blockingIssueSelection(JSONArray().put(ordinary).put(fence).toString())
        assertFalse(fenced.allIgnorable)
        assertEquals(listOf("0123456789abcdef"), fenced.fingerprints)
    }

    @Test fun registryProjectionCarriesEffectiveAreaFloorLabelNamesAndAreaOverrides() {
        val projection = projectDashboardRegistries(
            JSONObject("""{"result":{"entities":[
              {"ei":"light.lamp","di":"device_1","lb":["urgent"]},
              {"ei":"sensor.remote_temperature"}
            ]}}"""),
            JSONObject("""{"result":[{
              "area_id":"living_room","name":"Living Room","floor_id":"ground_floor",
              "temperature_entity_id":"sensor.remote_temperature"
            }]}"""),
            JSONObject("""{"result":[{
              "id":"device_1","area_id":"living_room","labels":["portable"]
            }]}"""),
            JSONObject("""{"result":[{"floor_id":"ground_floor","name":"Ground Floor"}]}"""),
            JSONObject("""{"result":[
              {"label_id":"urgent","name":"Needs Attention"},
              {"label_id":"portable","name":"Portable"}
            ]}"""),
        )

        assertTrue(projection.complete)
        val metadata = JSONObject(projection.metadata.getValue("light.lamp"))
        assertEquals("living_room", metadata.getString("ai"))
        assertEquals("Living Room", metadata.getString("an"))
        assertEquals("ground_floor", metadata.getString("fi"))
        assertEquals("Ground Floor", metadata.getString("fn"))
        assertEquals(setOf("portable", "urgent"), (0 until metadata.getJSONArray("lb").length()).map {
            metadata.getJSONArray("lb").getString(it)
        }.toSet())
        assertEquals(setOf("Needs Attention", "Portable"), (0 until metadata.getJSONArray("ln").length()).map {
            metadata.getJSONArray("ln").getString(it)
        }.toSet())
        assertEquals(
            setOf("sensor.remote_temperature"),
            projection.areaRegistryEntities.getValue("living_room"),
        )
    }

    @Test fun registryProjectionFailsClosedForMissingOrMalformedEntityRows() {
        fun emptyResponse() = JSONObject("""{"result":[]}""")

        val missing = projectDashboardRegistries(
            JSONObject("""{"result":{}}"""), emptyResponse(), emptyResponse(), emptyResponse(), emptyResponse(),
        )
        assertFalse(missing.complete)

        val malformed = projectDashboardRegistries(
            JSONObject("""{"result":{"entities":[{"ei":"not-an-entity"},null]}}"""),
            emptyResponse(), emptyResponse(), emptyResponse(), emptyResponse(),
        )
        assertFalse(malformed.complete)
        assertTrue(malformed.metadata.isEmpty())
    }

    @Test fun registryProjectionAcceptsACompleteEmptyRegistrySnapshot() {
        fun emptyResponse() = JSONObject("""{"result":[]}""")
        val projection = projectDashboardRegistries(
            JSONObject("""{"result":{"entities":[]}}"""),
            emptyResponse(), emptyResponse(), emptyResponse(), emptyResponse(),
        )

        assertTrue(projection.complete)
        assertTrue(projection.metadata.isEmpty())
        assertTrue(projection.areaRegistryEntities.isEmpty())
    }

    @Test fun registryProjectionSkipsAllRegistryPayloadsWhenDashboardNeedsNone() {
        val projection = projectDashboardRegistries(
            null, null, null, null, null,
            DashboardConfigurationLint.RegistryRequirements(),
        )

        assertTrue(projection.complete)
        assertTrue(projection.metadata.isEmpty())
        assertTrue(projection.areaRegistryEntities.isEmpty())
    }

    @Test fun registryProjectionForMapNeedsOnlyEntityRegistry() {
        val projection = projectDashboardRegistries(
            JSONObject("""{"result":{"entities":[{"ei":"zone.home"}]}}"""),
            null, null, null, null,
            DashboardConfigurationLint.RegistryRequirements(entities = true),
        )

        assertTrue(projection.complete)
        assertEquals(setOf("zone.home"), projection.metadata.keys)
        assertTrue(projection.areaRegistryEntities.isEmpty())
    }

    @Test fun areaProjectionDoesNotRequireUnusedFloorOrLabelRegistries() {
        val projection = projectDashboardRegistries(
            JSONObject("""{"result":{"entities":[{"ei":"light.lamp","di":"device_1"}]}}"""),
            JSONObject("""{"result":[{
              "area_id":"study","name":"Study","floor_id":"upper",
              "temperature_entity_id":"sensor.remote_temperature"
            }]}"""),
            JSONObject("""{"result":[{"id":"device_1","area_id":"study","labels":["unused"]}]}"""),
            null,
            null,
            DashboardConfigurationLint.RegistryRequirements(entities = true, areas = true, devices = true),
        )

        assertTrue(projection.complete)
        val metadata = JSONObject(projection.metadata.getValue("light.lamp"))
        assertEquals("study", metadata.getString("ai"))
        assertEquals("Study", metadata.getString("an"))
        assertFalse(metadata.has("fi"))
        assertFalse(metadata.has("lb"))
        assertEquals(setOf("sensor.remote_temperature"), projection.areaRegistryEntities.getValue("study"))
    }

    @Test fun registryProjectionFailsClosedOnlyForRequiredCapabilities() {
        val missingEntity = projectDashboardRegistries(
            null, null, null, null, null,
            DashboardConfigurationLint.RegistryRequirements(entities = true),
        )
        assertFalse(missingEntity.complete)

        val malformedUnused = projectDashboardRegistries(
            JSONObject("""{"result":{"entities":[{"ei":"zone.home"}]}}"""),
            JSONObject("""{"result":"not-an-array"}"""),
            JSONObject("""{"result":"not-an-array"}"""),
            JSONObject("""{"result":"not-an-array"}"""),
            JSONObject("""{"result":"not-an-array"}"""),
            DashboardConfigurationLint.RegistryRequirements(entities = true),
        )
        assertTrue(malformedUnused.complete)
    }

    @Test fun registryProjectionRejectsMalformedRequiredDisplayNames() {
        fun emptyResponse() = JSONObject("""{"result":[]}""")
        val projection = projectDashboardRegistries(
            JSONObject("""{"result":{"entities":[{"ei":"light.lamp","ai":"living_room"}]}}"""),
            JSONObject("""{"result":[{"area_id":"living_room"}]}"""),
            emptyResponse(),
            null,
            null,
            DashboardConfigurationLint.RegistryRequirements(entities = true, areas = true, devices = true),
        )

        assertFalse(projection.complete)
    }

    @Test fun registryProjectionRejectsMalformedEntityAndDeviceLabelFields() {
        fun emptyResponse() = JSONObject("""{"result":[]}""")
        fun project(entityLabels: String, deviceLabels: String) = projectDashboardRegistries(
            JSONObject("""{"result":{"entities":[{
              "ei":"light.lamp","di":"device_1","lb":$entityLabels
            }]}}"""),
            null,
            JSONObject("""{"result":[{"id":"device_1","labels":$deviceLabels}]}"""),
            null,
            emptyResponse(),
            DashboardConfigurationLint.RegistryRequirements(entities = true, devices = true, labels = true),
        )

        assertFalse(project("{}", "[]").complete)
        assertFalse(project("[]", "7").complete)
        assertFalse(project("[\"valid\",7]", "[]").complete)
    }

    @Test fun disabledAutomaticLearningIsStartupInertUntilExplicitDemand() {
        assertFalse(shouldInitializeEntityLearningOnStart(enabled = false))
        assertTrue(shouldInitializeEntityLearningOnStart(enabled = true))
    }

    @Test fun stateProjectionBoundsFriendlyName() {
        val limits = HaStatesReadLimits(maxFriendlyNameChars = 18)
        assertEquals(
            EntityCatalogStore.StateRow("binary_sensor.window", "on", "Upper Window Group"),
            validateHaStateRow("binary_sensor.window", "on", 1, limits, "Upper Window Group"),
        )
        assertThrows(IllegalStateException::class.java) {
            validateHaStateRow("sensor.room", "on", 1, limits, "A friendly name that is too long")
        }
    }

    @Test fun stateResponseBoundsAcceptProjectionAndRejectOversizePayloadRowsAndFields() {
        val limits = HaStatesReadLimits(maxBytes = 8, maxRows = 1, maxEntityIdChars = 16, maxStateChars = 4)
        assertEquals(
            EntityCatalogStore.StateRow("sensor.room", "21.5"),
            validateHaStateRow("sensor.room", "21.5", 1, limits),
        )
        assertThrows(ByteLimitExceeded::class.java) {
            DeadlineBoundedInputStream(ByteArrayInputStream(ByteArray(9)), 8, 1_000) { 0 }.readBytes()
        }
        assertThrows(IllegalStateException::class.java) {
            validateHaStateRow("sensor.two", "off", 2, limits)
        }
        assertThrows(IllegalStateException::class.java) {
            validateHaStateRow("sensor.identifier_too_long", "on", 1, limits)
        }
        assertThrows(IllegalStateException::class.java) {
            validateHaStateRow("sensor.room", "state-too-long", 1, limits)
        }
    }

    @Test fun stateResponseStreamEnforcesMonotonicReadDeadline() {
        var now = 0L
        assertThrows(SocketTimeoutException::class.java) {
            DeadlineBoundedInputStream(
                ByteArrayInputStream(byteArrayOf(1)), 16, 1,
                nanoTime = { (now++ * 2_000_000L) },
            ).read()
        }
    }

    @Test fun stateResponseStreamHonorsThreadInterruption() {
        Thread.currentThread().interrupt()
        try {
            assertThrows(InterruptedIOException::class.java) {
                DeadlineBoundedInputStream(ByteArrayInputStream(byteArrayOf(1)), 16, 1_000) { 0 }.read()
            }
        } finally {
            Thread.interrupted()
        }
    }

    @Test fun stateResponseByteCountingDoesNotRetainAnotherPayloadCopy() {
        val payload = """[{"entity_id":"sensor.room","state":"21","attributes":{}}]""".toByteArray()
        val counted = CountingInputStream(ByteArrayInputStream(payload))
        assertEquals(1, readHaStates(counted).size)
        assertEquals(payload.size.toLong(), counted.bytesRead)
    }

    @Test fun streamingStateReaderHandlesEscapesNumbersAndNestedSkippedValues() {
        val payload = """[{"ignored":{"nested":[1,true,null,{"value":"discard"}]},"attributes":{"unit":"C","friendly_name":"Room \u2603"},"state":21.5,"entity_id":"sensor.room"}]"""

        assertEquals(
            listOf(EntityCatalogStore.StateRow("sensor.room", "21.5", "Room ☃")),
            readHaStates(ByteArrayInputStream(payload.toByteArray())),
        )
    }

    @Test fun streamingStateReaderRejectsOversizeFieldsAndTrailingDocuments() {
        val limits = HaStatesReadLimits(maxEntityIdChars = 8)
        assertThrows(IllegalStateException::class.java) {
            readHaStates(ByteArrayInputStream("""[{"entity_id":"sensor.identifier_too_long","state":"on"}]""".toByteArray()), limits)
        }
        assertThrows(IllegalStateException::class.java) {
            readHaStates(ByteArrayInputStream("[]{}".toByteArray()))
        }
    }

    private fun state(
        origin: String = "https://ha.example",
        instanceKey: String = "instance-a",
        targetKey: String = "instance-a:dashboard-a",
        dashboardPath: String = "/dashboard-a",
        credentialFingerprint: String = "credential-a",
        learningEnabled: Boolean = true,
        applied: Boolean = true,
        filterEnabled: Boolean = true,
        filterIds: List<String> = listOf("light.a"),
        autoStatic: Boolean = true,
        autoRuntime: Boolean = true,
        overrides: Map<String, String> = emptyMap(),
        forceBootstrap: Boolean = false,
    ) = EntityLearningEffectState(
        origin, instanceKey, targetKey, dashboardPath, credentialFingerprint,
        learningEnabled, applied, filterEnabled, filterIds, autoStatic, autoRuntime,
        overrides, forceBootstrap,
    )

    @Test fun syncSnapshotRejectsEveryTargetPolicyAndCredentialChange() {
        val original = state()
        val snapshot = EntityLearningSyncSnapshot(
            7,
            "https://ha.example",
            "token",
            original.dashboardPath,
            HomeDashboardResolutionAuthority.Key(
                "https://ha.example",
                io.github.maxlyth.hapaneld.HaAuthOwner("https://ha.example", "refresh", "client", ""),
                original.dashboardPath,
            ),
            original,
        )

        assertTrue(snapshot.matchesCurrent(7, original))
        assertFalse(snapshot.matchesCurrent(8, original))
        assertFalse(snapshot.matchesCurrent(7, original.copy(routeEpoch = 1)))
        assertFalse(snapshot.matchesCurrent(7, original.copy(origin = "https://other.example")))
        assertFalse(snapshot.matchesCurrent(7, original.copy(instanceKey = "instance-b")))
        assertFalse(snapshot.matchesCurrent(7, original.copy(targetKey = "instance-a:dashboard-b")))
        assertFalse(snapshot.matchesCurrent(7, original.copy(dashboardPath = "/dashboard-b")))
        assertFalse(snapshot.matchesCurrent(7, original.copy(credentialFingerprint = "credential-b")))
        assertFalse(snapshot.matchesCurrent(7, original.copy(autoRuntime = false)))
        assertFalse(snapshot.matchesCurrent(7, original.copy(filterIds = listOf("light.b"))))
        assertFalse(snapshot.matchesCurrent(7, original.copy(forceBootstrap = true)))
        assertFalse(snapshot.matchesCurrent(7, original.copy(initialActivationPending = true)))
    }

    @Test fun effectMutationCancelsReleasesTheActiveSyncSlotAndClearsItsQueuedRerun() {
        val active = Job()
        var queuedRerun: String? = "enable-bootstrap"

        val replacementSlot = supersedeEntityLearningSync(active) { queuedRerun = null }

        assertTrue(active.isCancelled)
        assertEquals(null, replacementSlot)
        assertEquals(null, queuedRerun)
    }

    @Test fun supersessionClearsAStaleRerunAfterTheActiveSlotWasAlreadyReleased() {
        var queuedRerun: String? = "target-refresh"

        val replacementSlot = supersedeEntityLearningSync(null) { queuedRerun = null }

        assertEquals(null, replacementSlot)
        assertEquals(null, queuedRerun)
    }

    @Test fun firstEnableInvalidatesOldEffectsBeforeStartingExactlyOneBootstrapSync() {
        val events = mutableListOf<String>()
        val obsolete = Job()
        var ownedSync: Job? = obsolete
        var initializationCalls = 0
        var bootstrapStarts = 0

        val newlyInitialized = initializeEntityLearningAfterEffectInvalidation(
            invalidate = {
                events += "invalidate"
                ownedSync = supersedeEntityLearningSync(ownedSync)
            },
            initialize = {
                initializationCalls++
                events += "initialize"
                ownedSync = Job()
                bootstrapStarts++
                true
            },
        )
        if (!newlyInitialized) bootstrapStarts++

        assertTrue(newlyInitialized)
        assertEquals(1, initializationCalls)
        assertEquals("lazy initialization owns the only required bootstrap start", 1, bootstrapStarts)
        assertEquals(listOf("invalidate", "initialize"), events)
        assertTrue(obsolete.isCancelled)
        assertTrue("the initialization-owned bootstrap sync must remain active", ownedSync?.isActive == true)
    }

    @Test fun supersededScanCannotPersistFailureOntoCurrentGeneration() {
        assertTrue(shouldPersistEntityLearningFailure(7, 7, "instance-a", "/dash", "instance-a", "/dash"))
        assertFalse(shouldPersistEntityLearningFailure(7, 8, "instance-a", "/dash", "instance-a", "/dash"))
        assertFalse(shouldPersistEntityLearningFailure(7, 7, "instance-a", "/dash", "instance-b", "/dash"))
        assertFalse(shouldPersistEntityLearningFailure(7, 7, "instance-a", "/dash", "instance-a", "/other"))
    }

    @Test fun telemetryQueuedBeforeResetCannotWriteAfterInvalidation() {
        val generation = AtomicLong(3)
        val barrier = EntityTelemetryWriteBarrier(generation::get)
        val admittedGeneration = generation.get()
        var wrote = false

        barrier.invalidateAndWrite(invalidate = { generation.incrementAndGet() }) {}

        assertFalse(barrier.writeIfCurrent(admittedGeneration) { wrote = true })
        assertFalse(wrote)
    }

    @Test fun resetWaitsForAnActiveTelemetryWriteThenClearsIt() {
        val generation = AtomicLong(8)
        val barrier = EntityTelemetryWriteBarrier(generation::get)
        val evidence = mutableListOf<String>()
        val writeEntered = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val resetAttempted = CountDownLatch(1)
        val resetFinished = CountDownLatch(1)

        val writer = thread(start = true) {
            barrier.writeIfCurrent(generation.get()) {
                writeEntered.countDown()
                releaseWrite.await()
                evidence += "old"
            }
        }
        assertTrue(writeEntered.await(2, TimeUnit.SECONDS))
        val resetter = thread(start = true) {
            resetAttempted.countDown()
            barrier.invalidateAndWrite(
                invalidate = { generation.incrementAndGet() },
                write = { evidence.clear() },
            )
            resetFinished.countDown()
        }

        assertTrue(resetAttempted.await(2, TimeUnit.SECONDS))
        assertFalse(resetFinished.await(50, TimeUnit.MILLISECONDS))
        releaseWrite.countDown()
        writer.join(2_000)
        resetter.join(2_000)

        assertFalse(writer.isAlive)
        assertFalse(resetter.isAlive)
        assertTrue(evidence.isEmpty())
    }

    @Test fun telemetryFollowUpReleasesItsBarrierBeforeEnteringTheManagerMonitor() {
        val generation = AtomicLong(5)
        val barrier = EntityTelemetryWriteBarrier(generation::get)
        val managerMonitor = Any()
        val writeEntered = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val resetHasManagerMonitor = CountDownLatch(1)
        val resetFinished = CountDownLatch(1)
        val followUpFinished = CountDownLatch(1)

        val telemetry = thread(start = true, isDaemon = true) {
            writeEntityTelemetryThen(
                barrier = barrier,
                admittedGeneration = generation.get(),
                write = {
                    writeEntered.countDown()
                    releaseWrite.await()
                },
                afterWrite = {
                    synchronized(managerMonitor) { followUpFinished.countDown() }
                },
            )
        }
        assertTrue(writeEntered.await(2, TimeUnit.SECONDS))
        val reset = thread(start = true, isDaemon = true) {
            synchronized(managerMonitor) {
                resetHasManagerMonitor.countDown()
                barrier.invalidateAndWrite(invalidate = { generation.incrementAndGet() }) {}
            }
            resetFinished.countDown()
        }

        assertTrue(resetHasManagerMonitor.await(2, TimeUnit.SECONDS))
        releaseWrite.countDown()
        assertTrue("reset must acquire the released barrier instead of deadlocking",
            resetFinished.await(2, TimeUnit.SECONDS))
        assertTrue("telemetry follow-up must enter the manager only after reset releases it",
            followUpFinished.await(2, TimeUnit.SECONDS))
        telemetry.join(2_000)
        reset.join(2_000)
        assertFalse(telemetry.isAlive)
        assertFalse(reset.isAlive)
    }

    @Test fun concurrentDisableCannotBeOvertakenByAnAlreadyRunningActivation() {
        data class DurableState(
            var learningEnabled: Boolean = true,
            var filterEnabled: Boolean = false,
            var applied: Boolean = false,
        )

        val owner = Any()
        val state = DurableState()
        val activationEntered = CountDownLatch(1)
        val releaseActivation = CountDownLatch(1)
        val disableAttempted = CountDownLatch(1)
        val disableFinished = CountDownLatch(1)

        val activation = thread(start = true) {
            withEntityLearningMutationLock(owner) {
                activationEntered.countDown()
                releaseActivation.await()
                state.filterEnabled = true
                state.applied = true
            }
        }
        assertTrue(activationEntered.await(2, TimeUnit.SECONDS))
        val disable = thread(start = true) {
            disableAttempted.countDown()
            withEntityLearningMutationLock(owner) {
                state.learningEnabled = false
                state.filterEnabled = false
                state.applied = false
            }
            disableFinished.countDown()
        }

        assertTrue(disableAttempted.await(2, TimeUnit.SECONDS))
        assertFalse("disable must wait for the active mutation", disableFinished.await(50, TimeUnit.MILLISECONDS))
        releaseActivation.countDown()
        activation.join(2_000)
        disable.join(2_000)

        assertFalse(activation.isAlive)
        assertFalse(disable.isAlive)
        assertFalse(state.learningEnabled)
        assertFalse(state.filterEnabled)
        assertFalse(state.applied)
    }

    @Test fun resetAndScanAdmissionShareTheManagerMonitorInBothDirections() {
        val owner = Any()
        val resetEntered = CountDownLatch(1)
        val releaseReset = CountDownLatch(1)
        val scanAttempted = CountDownLatch(1)
        val scanEntered = CountDownLatch(1)
        val resetter = thread(start = true) {
            withEntityLearningMutationLock(owner) {
                resetEntered.countDown()
                releaseReset.await()
            }
        }
        assertTrue(resetEntered.await(2, TimeUnit.SECONDS))
        val scanner = thread(start = true) {
            scanAttempted.countDown()
            synchronized(owner) { scanEntered.countDown() }
        }
        assertTrue(scanAttempted.await(2, TimeUnit.SECONDS))
        assertFalse("a scan cannot be admitted during reset", scanEntered.await(50, TimeUnit.MILLISECONDS))
        releaseReset.countDown()
        resetter.join(2_000)
        scanner.join(2_000)
        assertFalse(resetter.isAlive)
        assertFalse(scanner.isAlive)
        assertEquals(0L, scanEntered.count)

        val scanHolding = CountDownLatch(1)
        val releaseScan = CountDownLatch(1)
        val resetAttempted = CountDownLatch(1)
        val queuedResetEntered = CountDownLatch(1)
        val holdingScanner = thread(start = true) {
            synchronized(owner) {
                scanHolding.countDown()
                releaseScan.await()
            }
        }
        assertTrue(scanHolding.await(2, TimeUnit.SECONDS))
        val queuedReset = thread(start = true) {
            resetAttempted.countDown()
            withEntityLearningMutationLock(owner) { queuedResetEntered.countDown() }
        }
        assertTrue(resetAttempted.await(2, TimeUnit.SECONDS))
        assertFalse("reset cannot inspect admission while a scan is being admitted",
            queuedResetEntered.await(50, TimeUnit.MILLISECONDS))
        releaseScan.countDown()
        holdingScanner.join(2_000)
        queuedReset.join(2_000)
        assertFalse(holdingScanner.isAlive)
        assertFalse(queuedReset.isAlive)
        assertEquals(0L, queuedResetEntered.count)
    }

    @Test fun cancelledScanCannotRepopulateOverridesAfterResetClearsEvidence() {
        val owner = Any()
        val evidence = mutableListOf<String>()
        val scanCommitted = CountDownLatch(1)
        val releaseScanCommit = CountDownLatch(1)
        val resetAttempted = CountDownLatch(1)
        val resetFinished = CountDownLatch(1)

        val cancelledScan = thread(start = true, isDaemon = true) {
            commitEntityLearningSyncEvidence(
                owner = owner,
                ensureCurrent = {},
                commitSync = {
                    evidence += "scan"
                    scanCommitted.countDown()
                    releaseScanCommit.await()
                },
                applyStoredOverrides = { evidence += "override" },
                publishDiagnostics = {},
            )
        }
        assertTrue(scanCommitted.await(2, TimeUnit.SECONDS))
        val reset = thread(start = true, isDaemon = true) {
            resetAttempted.countDown()
            withEntityLearningMutationLock(owner) { evidence.clear() }
            resetFinished.countDown()
        }
        assertTrue(resetAttempted.await(2, TimeUnit.SECONDS))
        assertFalse(
            "reset must not split scan evidence from its stored overrides",
            resetFinished.await(50, TimeUnit.MILLISECONDS),
        )

        releaseScanCommit.countDown()
        cancelledScan.join(2_000)
        reset.join(2_000)
        assertFalse(cancelledScan.isAlive)
        assertFalse(reset.isAlive)
        assertTrue("the later reset must win over every scan evidence write", evidence.isEmpty())
    }

    @Test fun queuedPromotionRequiresUnchangedEligiblePolicyAndNoBlockers() {
        val original = state()
        val queued = EntityLearningPromotionSnapshot(11, original)

        assertTrue(queued.isEligible(11, original, blockingIssues = 0))
        assertFalse(queued.isEligible(12, original, blockingIssues = 0))
        assertFalse(queued.isEligible(11, original.copy(learningEnabled = false), blockingIssues = 0))
        assertFalse(queued.isEligible(11, original.copy(applied = false), blockingIssues = 0))
        assertFalse(queued.isEligible(11, original.copy(autoRuntime = false), blockingIssues = 0))
        assertFalse(queued.isEligible(11, original.copy(targetKey = "instance-b:dashboard-a"), blockingIssues = 0))
        assertFalse(queued.isEligible(11, original, blockingIssues = 1))
    }

    @Test fun quietSuspendingOperationCannotOutliveWallClockDeadline() = runTest {
        var timedOut = false
        try {
            withEntityLearningDeadline(1_000) { awaitCancellation() }
        } catch (_: TimeoutCancellationException) {
            timedOut = true
        }
        assertTrue(timedOut)
    }

    @Test fun overridePreferenceFailureNeverTouchesTheCatalog() {
        val events = mutableListOf<String>()
        val failed = runCatching {
            runEntityOverrideTransaction(
                commitOverridePreferences = { events += "prefs-failed"; false },
                applyStoreOverride = { events += "store" },
                commitActiveFilter = { events += "filter"; true },
                restoreStoreOverride = { events += "store-rollback" },
                restoreOverridePreferences = { events += "prefs-rollback"; true },
            )
        }.isFailure

        assertTrue(failed)
        assertEquals(listOf("prefs-failed"), events)
    }

    @Test fun activeFilterFailureRollsBackBothOverrideStoresAndCannotReportSuccess() {
        val events = mutableListOf<String>()
        val failed = runCatching {
            runEntityOverrideTransaction(
                commitOverridePreferences = { events += "prefs"; true },
                applyStoreOverride = { events += "store" },
                commitActiveFilter = { events += "filter-failed"; false },
                restoreStoreOverride = { events += "store-rollback" },
                restoreOverridePreferences = { events += "prefs-rollback"; true },
            )
        }.isFailure

        assertTrue(failed)
        assertEquals(
            listOf("prefs", "store", "filter-failed", "store-rollback", "prefs-rollback"),
            events,
        )
    }

    @Test fun successfulOverrideTransactionDoesNotRunRollback() {
        val events = mutableListOf<String>()
        runEntityOverrideTransaction(
            commitOverridePreferences = { events += "prefs"; true },
            applyStoreOverride = { events += "store" },
            commitActiveFilter = { events += "filter"; true },
            restoreStoreOverride = { events += "store-rollback" },
            restoreOverridePreferences = { events += "prefs-rollback"; true },
        )
        assertEquals(listOf("prefs", "store", "filter"), events)
    }

    @Test fun failedResetPreferenceCommitNeverTouchesTheCatalog() {
        val events = mutableListOf<String>()
        val failed = runCatching {
            runEntityEvidenceResetTransaction(
                commitPreferences = { events += "prefs-failed"; false },
                resetStore = { events += "store" },
                restorePreferences = { events += "prefs-rollback"; true },
                afterSuccess = { events += "cache-clear" },
            )
        }.isFailure

        assertTrue(failed)
        assertEquals(listOf("prefs-failed"), events)
    }

    @Test fun failedCatalogResetRestoresPreferencesBeforeReturningTheStoreFailure() {
        val events = mutableListOf<String>()
        var publishedState = "original"
        var blockingIssues = 3
        var dynamicExpressions = "[\"template\"]"
        val storeFailure = IllegalStateException("store failed")

        val failure = runCatching {
            runEntityEvidenceResetTransaction(
                commitPreferences = { events += "prefs-reset"; publishedState = "reset"; true },
                resetStore = { events += "store-failed"; throw storeFailure },
                restorePreferences = { events += "prefs-rollback"; publishedState = "original"; true },
                afterSuccess = {
                    events += "cache-clear"
                    blockingIssues = 0
                    dynamicExpressions = "[]"
                },
            )
        }.exceptionOrNull()

        assertTrue(failure === storeFailure)
        assertEquals("original", publishedState)
        assertEquals(3, blockingIssues)
        assertEquals("[\"template\"]", dynamicExpressions)
        assertEquals(listOf("prefs-reset", "store-failed", "prefs-rollback"), events)
        assertTrue(storeFailure.suppressed.isEmpty())
    }

    @Test fun failedResetRollbackIsAttachedWithoutMaskingTheStoreFailure() {
        val storeFailure = IllegalStateException("store failed")

        val failure = runCatching {
            runEntityEvidenceResetTransaction(
                commitPreferences = { true },
                resetStore = { throw storeFailure },
                restorePreferences = { false },
                afterSuccess = {},
            )
        }.exceptionOrNull()

        assertTrue(failure === storeFailure)
        assertEquals(1, storeFailure.suppressed.size)
        assertEquals("entity evidence reset preference rollback failed", storeFailure.suppressed.single().message)
    }

    @Test fun successfulResetTransactionDoesNotRunRollback() {
        val events = mutableListOf<String>()
        var blockingIssues = 3
        var dynamicExpressions = "[\"template\"]"

        runEntityEvidenceResetTransaction(
            commitPreferences = { events += "prefs-reset"; true },
            resetStore = { events += "store-reset" },
            restorePreferences = { events += "prefs-rollback"; true },
            afterSuccess = {
                events += "cache-clear"
                blockingIssues = 0
                dynamicExpressions = "[]"
            },
        )

        assertEquals(listOf("prefs-reset", "store-reset", "cache-clear"), events)
        assertEquals(0, blockingIssues)
        assertEquals("[]", dynamicExpressions)
    }

    @Test fun authenticatedHaConfigAddsBoundedValidIdentityAliases() {
        assertEquals(
            listOf(
                "https://configured.example/ha",
                "http://homeassistant.local:8123",
                "https://public.example/ha",
            ),
            haInstanceCandidateUrls(
                "https://configured.example/ha",
                """{
                  "internal_url":"http://homeassistant.local:8123",
                  "external_url":"https://public.example/ha",
                  "base_url":"javascript:alert(1)"
                }""",
            ),
        )
    }

    @Test fun configuredIdentityCandidateSurvivesMissingMalformedOrDuplicateConfig() {
        val configured = "https://configured.example"
        assertEquals(listOf(configured), haInstanceCandidateUrls(configured, null))
        assertEquals(listOf(configured), haInstanceCandidateUrls(configured, "not-json"))
        assertEquals(
            listOf(configured),
            haInstanceCandidateUrls(
                configured,
                """{"internal_url":"$configured","external_url":"","base_url":"ftp://invalid.example"}""",
            ),
        )
        assertEquals(
            listOf(configured),
            haInstanceCandidateUrls(
                configured,
                """{"internal_url":"https://oversized.example/${"a".repeat(3_000)}"}""",
            ),
        )
    }
}
