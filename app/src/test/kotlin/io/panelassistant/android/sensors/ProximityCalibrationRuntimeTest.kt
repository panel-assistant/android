package io.panelassistant.android.sensors

import io.panelassistant.android.dashboard.EntityCatalogStore
import io.panelassistant.android.device.profile.ProfileProximityCalibration
import io.panelassistant.android.device.profile.ProfileWaveCalibration
import io.panelassistant.android.sensors.ProximityCalibrationEngine.Calibration
import io.panelassistant.android.sensors.ProximityCalibrationEngine.Mode
import io.panelassistant.android.sensors.ProximityCalibrationEngine.WaveCalibration
import io.panelassistant.android.sensors.ProximityCalibrationEngine.WavePattern
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Drives the production runtime, sparse live edges and its injected atomic model-store boundary. */
class ProximityCalibrationRuntimeTest {
    @Test fun recoveredIdleSourceDoesNotKeepReportingConnectionLoss() {
        val fixture = Fixture()
        fixture.runtime.sourceUnavailable(fixture.now)
        assertEquals("Proximity source unavailable", fixture.status().getString("message"))
        fixture.advance(100, 100f)
        assertEquals("healthy", fixture.status().getString("health"))
        assertEquals("Calibrated proximity · ranged", fixture.status().getString("message"))
        assertEquals(fixture.status().getString("message"), fixture.status().getJSONObject("session").getString("message"))
        assertEquals(0, fixture.backing.writes)
    }

    @Test fun setupRevokesOperationalPresenceOnceAndCancelRestoresIt() {
        val fixture = Fixture()
        fixture.settlePresence(100f)
        assertTrue(fixture.runtime.isPresenceReady())
        assertTrue(fixture.runtime.start())
        val during = fixture.advance(100, 100f)
        assertEquals(ProximityReportGate.BOTH, during.reportMask)
        assertNull(during.near)
        assertFalse(during.presenceApproach)
        assertFalse(during.deliberateGesture)
        assertEquals(ProximityReportGate.NONE, fixture.advance(100, 100f).reportMask)
        assertTrue(fixture.runtime.cancel(fixture.status().getString("sessionId")))
        val restored = fixture.settlePresence(100f)
        assertTrue(fixture.runtime.isPresenceReady())
        assertEquals(false, restored.near)
        assertEquals(0, fixture.backing.writes)
    }

    @Test fun ambientSamplesGesturesTickAndCloseNeverWriteCalibration() {
        val fixture = Fixture()
        repeat(10) { fixture.operationalWave(legacy().effectiveWave()!!) }
        fixture.runtime.close()
        assertEquals(0, fixture.backing.writes)
        assertEquals(0, fixture.backing.clears)
        assertNull(fixture.backing.row)
        assertEquals(1, fixture.backing.closes)
    }

    @Test fun completedWizardCommitsOnceAndRestartRestoresSameFingerprint() {
        for ((clear, near) in listOf(0f to 1f, 1f to 0f, 100f to 50f)) {
            val backing = Backing()
            val fixture = Fixture(backing, pattern = WavePattern.DOUBLE)
            fixture.toReview(clear, near, near)
            assertEquals(0, backing.writes)
            assertFalse(fixture.runtime.isWaveReady())
            assertTrue(fixture.status().getBoolean("canSave"))
            fixture.advance(1)
            assertTrue(fixture.runtime.localAction("save"))
            assertEquals("saved", fixture.status().getString("stage"))
            assertEquals("user", fixture.status().getString("calibrationSource"))
            assertEquals(1, backing.writes)
            val committed = assertRow(backing)
            assertEquals(ProximityCalibrationRuntime.STORAGE_VERSION, committed.algorithmVersion)
            val model = assertDecoded(committed.snapshotJson)
            assertEquals(2, model.version)
            assertEquals(WavePattern.DOUBLE, model.wave!!.pattern)
            assertFalse(fixture.runtime.localAction("save"))
            fixture.runtime.close()
            val restarted = Fixture(backing)
            assertEquals("user", restarted.status().getString("calibrationSource"))
            assertFalse(restarted.runtime.isWaveReady())
            restarted.operationalWave(model.wave!!)
            assertEquals(committed, backing.row)
            assertEquals(1, backing.writes)
        }
    }

    @Test fun legacyCalibrationIsReadWithoutMigrationWriteAndRemainsOperational() {
        val previous = row(legacy(), ProximityCalibrationRuntime.LEGACY_STORAGE_VERSION)
        val fixture = Fixture(Backing(previous))
        assertEquals("user", fixture.status().getString("calibrationSource"))
        fixture.operationalWave(legacy().effectiveWave()!!)
        fixture.runtime.close()
        assertEquals(previous, fixture.backing.row)
        assertEquals(0, fixture.backing.writes)
        assertEquals(0, fixture.backing.clears)
    }

    @Test fun anotherProfileOrSensorIdentityExcludesPreviousOverride() {
        for ((source, profile) in listOf(SOURCE to "other-profile", "new-source" to PROFILE)) {
            val previous = row(binaryLegacy())
            val fixture = Fixture(Backing(previous), source = source, profile = profile)
            assertEquals("profile", fixture.status().getString("calibrationSource"))
            fixture.operationalWave(legacy().effectiveWave()!!)
            assertEquals(previous, fixture.backing.row)
            assertEquals(0, fixture.backing.writes)
        }
    }

    /** An edit to the profile's brightness or buttons, or a release revising the bundled file, changes its
     *  revision and nothing the sensor reports: the saved calibration and its ready state stay. */
    @Test fun aNewProfileRevisionKeepsTheSavedCalibration() {
        val backing = Backing()
        val first = Fixture(backing, pattern = WavePattern.DOUBLE, revision = "rev-1")
        first.toReview(100f, 50f, 50f)
        first.advance(1)
        assertTrue(first.runtime.localAction("save"))
        first.runtime.close()
        for (revision in listOf("rev-1", "rev-2", null)) {
            val updated = Fixture(backing, revision = revision)
            assertEquals("user", updated.status().getString("calibrationSource"))
            updated.sample(100f)
            updated.advance(200, 100f)
            assertEquals("ready", JSONObject(updated.runtime.statusJson()).getString("state"))
            updated.runtime.close()
        }
        assertEquals(1, backing.writes)
    }

    /** A calibration saved before the key stopped hashing the revision is found under that old key, carried to
     *  the current one, and then survives the next revision too. */
    @Test fun aCalibrationSavedUnderTheRevisionKeyIsCarriedForward() {
        val old = row(presenceOnly()).copy(fingerprint = ProximityCalibrationRuntime.fingerprint("$PROFILE|rev-1|$SOURCE"))
        val backing = Backing(old)
        val migrated = Fixture(backing, revision = "rev-1")
        assertEquals("user", migrated.status().getString("calibrationSource"))
        assertEquals(old.copy(fingerprint = key()), backing.inserted[key()])
        assertEquals(old, backing.row)
        assertEquals(0, backing.writes)
        migrated.runtime.close()
        backing.row = null
        assertEquals("user", Fixture(backing, revision = "rev-2").status().getString("calibrationSource"))
        // The old key under another revision proves nothing about this one, so it is not taken up.
        val other = Backing(old)
        assertEquals("profile", Fixture(other, revision = "rev-2").status().getString("calibrationSource"))
        assertTrue(other.inserted.isEmpty())
    }

    /** A reset after the carry clears the old key too, so the next start does not carry the calibration back. */
    @Test fun aResetAfterTheCarryStaysReset() {
        val old = row(presenceOnly()).copy(fingerprint = ProximityCalibrationRuntime.fingerprint("$PROFILE|rev-1|$SOURCE"))
        val backing = Backing(old)
        val carried = Fixture(backing, revision = "rev-1")
        assertEquals("user", carried.status().getString("calibrationSource"))
        assertTrue(carried.runtime.resetToProfile())
        carried.runtime.close()
        assertNull(backing.row)
        assertTrue(backing.inserted.isEmpty())
        assertEquals("profile", Fixture(backing, revision = "rev-1").status().getString("calibrationSource"))
    }

    /** Unreadable storage is not a missing calibration: Panel Assistant must not be told to raise its setup
     *  Repair, the panel must not invite setup, and nothing stored is touched. */
    @Test fun anUnreadableStoreIsNotReportedAsCalibrationRequired() {
        val backing = Backing(row(presenceOnly())).apply { failRead = true }
        var now = 1_000L
        val runtime = ProximityCalibrationRuntime(SOURCE, PROFILE, null, Store(backing),
            elapsed = { now }, wall = { 1_800_000_000_000L + now }, profileRevision = "rev-1")
        runtime.observe(100f, now, true)
        assertEquals("source_unavailable", JSONObject(runtime.statusJson()).getString("state"))
        val status = JSONObject(runtime.json())
        assertTrue(status.getBoolean("calibrationReadFailed"))
        assertEquals(ProximityCalibrationRuntime.STORAGE_UNREADABLE_MESSAGE, status.getString("message"))
        assertEquals(0, backing.writes)
        assertEquals(0, backing.clears)
        assertTrue(backing.inserted.isEmpty())
        val empty = ProximityCalibrationRuntime(SOURCE, PROFILE, null, Store(Backing()),
            elapsed = { now }, wall = { 1_800_000_000_000L + now })
        empty.observe(100f, now, true)
        assertEquals("calibration_required", JSONObject(empty.statusJson()).getString("state"))
    }

    /** Presence anchors survive the old fixed band; a reset still removes the person's override. */
    @Test fun resetRemovesAWeakStoredPresenceCalibration() {
        val backing = Backing(row(Calibration(mode = Mode.RANGED, clearRaw = 100f, nearRaw = 95f)))
        val fixture = Fixture(backing, driftMargin = 10f)
        assertFalse(fixture.status().getBoolean("calibrationDriftUnsafe"))
        assertEquals("user", fixture.status().getString("calibrationSource"))
        assertTrue(fixture.runtime.resetToProfile())
        assertFalse(fixture.status().getBoolean("calibrationDriftUnsafe"))
        assertEquals("profile", fixture.status().getString("calibrationSource"))
        assertNull(backing.row)
    }

    /** Carrying the old revision key preserves weak presence without rewriting its measured anchors. */
    @Test fun aCarriedWeakPresenceCalibrationLearnsItsRuntimeBand() {
        val old = row(Calibration(mode = Mode.RANGED, clearRaw = 100f, nearRaw = 95f))
            .copy(fingerprint = ProximityCalibrationRuntime.fingerprint("$PROFILE|rev-1|$SOURCE"))
        val backing = Backing(old)
        val carried = Fixture(backing, revision = "rev-1", driftMargin = 10f)
        assertEquals("user", carried.status().getString("calibrationSource"))
        assertFalse(carried.status().getBoolean("calibrationDriftUnsafe"))
        assertEquals(false, carried.settlePresence(100f).near)
        assertEquals(true, carried.settlePresence(95f).near)
        assertEquals(old, backing.row)
        assertEquals(0, backing.writes)
    }

    @Test fun acceptingWeakStoredPresenceDoesNotEnableAPreviouslyRefusedExplicitWave() {
        val wave = WaveCalibration(clearRaw = 974f, nearRaw = 3_413f)
        val saved = row(Calibration(mode = Mode.RANGED, clearRaw = 974f, nearRaw = 995f, wave = wave))
        val fixture = Fixture(Backing(saved), driftMargin = 100f)
        assertEquals("user", fixture.status().getString("calibrationSource"))
        assertFalse(fixture.status().getBoolean("waveSupported"))
        assertEquals(false, fixture.settlePresence(974f).near)
        assertEquals(true, fixture.settlePresence(995f).near)
        assertTrue(fixture.runtime.isPresenceReady())
        assertFalse(fixture.runtime.isWaveReady())

        fixture.settlePresence(wave.clearRaw)
        assertFalse(fixture.advance(800, wave.nearRaw).deliberateGesture)
        assertFalse(fixture.advance(300).deliberateGesture)
        assertFalse(fixture.sample(wave.clearRaw).deliberateGesture)
        assertFalse(fixture.advance(150).deliberateGesture)
        assertEquals(saved, fixture.backing.row)
        assertEquals(0, fixture.backing.writes)
        assertEquals(0, fixture.backing.clears)
    }

    @Test fun setupCanReplaceWeakPresenceWithoutPersistingItsRuntimeNoise() {
        val backing = Backing(row(Calibration(mode = Mode.RANGED, clearRaw = 974f, nearRaw = 995f)))
        val fixture = Fixture(backing, driftMargin = 100f)
        assertEquals("user", fixture.status().getString("calibrationSource"))
        fixture.toReview(974f, 995f, null)
        fixture.advance(1)
        assertTrue(fixture.runtime.localAction("save"))
        assertEquals("user", fixture.status().getString("calibrationSource"))
        assertFalse(fixture.status().getBoolean("calibrationDriftUnsafe"))
        assertEquals(1, backing.writes)
        val saved = assertDecoded(assertRow(backing).snapshotJson)
        assertEquals(974f, saved.clearRaw, 0f)
        assertEquals(995f, saved.nearRaw, 0f)
        fixture.settlePresence(974f)
        fixture.settlePresence(995f)
        assertEquals(1, backing.writes)
    }

    /** A restore writes the calibration to the store while the runtime runs; the reload takes it up at once,
     *  except during a setup, which keeps the panel. */
    @Test fun reloadTakesUpARestoredCalibrationButNeverInterruptsSetup() {
        var now = 1_000L
        val backing = Backing()
        val runtime = ProximityCalibrationRuntime(SOURCE, PROFILE, null, Store(backing),
            elapsed = { now }, wall = { 1_800_000_000_000L + now })
        runtime.observe(100f, now, true)
        assertEquals("calibration_required", JSONObject(runtime.statusJson()).getString("state"))
        backing.inserted[key()] = row(presenceOnly())
        assertTrue(runtime.reloadStored())
        runtime.observe(100f, now, true)
        assertEquals("ready", JSONObject(runtime.statusJson()).getString("state"))
        assertEquals("user", JSONObject(runtime.json()).getString("calibrationSource"))
        val busy = Fixture()
        busy.begin(100f)
        // A different calibration is waiting in the store, so only the setup itself can hold it off.
        busy.backing.inserted[key()] = row(presenceOnly())
        assertFalse(busy.runtime.reloadStored())
        assertEquals("calibrating", JSONObject(busy.runtime.statusJson()).getString("state"))
        // A restore that brought nothing new, or a store that cannot be read now, leaves the working calibration.
        assertFalse(runtime.reloadStored())
        backing.failRead = true
        assertFalse(runtime.reloadStored())
        runtime.observe(100f, now, true)
        assertEquals("ready", JSONObject(runtime.statusJson()).getString("state"))
        assertEquals("user", JSONObject(runtime.json()).getString("calibrationSource"))
    }

    /** The status endpoint's `proximity` component, from which Panel Assistant raises its calibration Repair. */
    @Test fun statusComponentNamesTheSetupPhaseAndWaveReadiness() {
        fun component(runtime: ProximityCalibrationRuntime) = JSONObject(runtime.statusJson()).also {
            assertEquals(setOf("state", "wake_ready"), it.keys().asSequence().toSet())
            // One definition: the component agrees with the full calibration status.
            val full = JSONObject(runtime.json())
            assertEquals(full.getString("phase"), it.getString("state"))
            assertEquals(full.getBoolean("wakeReady"), it.getBoolean("wake_ready"))
        }

        var now = 1_000L
        val untrained = ProximityCalibrationRuntime(SOURCE, PROFILE, null, Store(Backing()),
            elapsed = { now }, wall = { 1_800_000_000_000L + now })
        untrained.observe(100f, now, true)
        assertEquals("calibration_required", component(untrained).getString("state"))
        assertFalse(component(untrained).getBoolean("wake_ready"))

        val fixture = Fixture()
        fixture.sample(100f)
        fixture.advance(200, 100f)
        assertEquals("ready", component(fixture.runtime).getString("state"))
        fixture.begin(100f)
        assertEquals("calibrating", component(fixture.runtime).getString("state"))
        assertFalse(component(fixture.runtime).getBoolean("wake_ready"))

        val lost = Fixture()
        lost.sample(100f)
        lost.runtime.sourceUnavailable(lost.now)
        assertEquals("source_unavailable", component(lost.runtime).getString("state"))
        assertFalse(component(lost.runtime).getBoolean("wake_ready"))
    }

    @Test fun learnedSignalIsUnknownWhenTheStoredCalibrationCannotBeRead() {
        // A read failure falls back to the profile baseline, so isLearnedSignal() reads false for a panel that is
        // trained. The settled answer must be unknown: a false is stated to Home Assistant as a missing sensor.
        val read = ProximityCalibrationRuntime(SOURCE, PROFILE, null, Store(Backing(row(presenceOnly()))),
            elapsed = { 1_000L }, wall = { 1_800_000_000_000L })
        assertEquals(true, read.learnedSignalState())
        val failed = ProximityCalibrationRuntime(SOURCE, PROFILE, null,
            Store(Backing(row(presenceOnly())).apply { failRead = true }),
            elapsed = { 1_000L }, wall = { 1_800_000_000_000L })
        assertFalse(failed.isLearnedSignal())
        assertEquals(null, failed.learnedSignalState())
        val untrained = ProximityCalibrationRuntime(SOURCE, PROFILE, null, Store(Backing()),
            elapsed = { 1_000L }, wall = { 1_800_000_000_000L })
        assertEquals(false, untrained.learnedSignalState())
        read.close()
        assertEquals(null, read.learnedSignalState())
    }

    @Test fun resetWithoutProfileDefaultPreservesUserCalibration() {
        val previous = row(binaryLegacy())
        val backing = Backing(previous)
        val runtime = ProximityCalibrationRuntime(SOURCE, PROFILE, null, Store(backing),
            elapsed = { 1_000L }, wall = { 1_800_000_000_000L })
        val status = JSONObject(runtime.json())
        assertFalse(status.getBoolean("profileDefaultAvailable"))
        assertEquals("user", status.getString("calibrationSource"))
        assertFalse(runtime.resetToProfile())
        assertEquals(previous, backing.row)
        assertEquals(0, backing.clears)
    }

    @Test fun rawSourceExcludesHalCalibrationAndReportsItsVerifiedRepresentationBeforeCalibration() {
        val previous = row(binaryLegacy())
        val backing = Backing(previous)
        val runtime = ProximityCalibrationRuntime(StkRawProximityReader.SOURCE_IDENTITY, PROFILE,
            null, Store(backing), elapsed = { 1_000L }, wall = { 1_800_000_000_000L },
            observedSourceMode = Mode.RANGED)
        val status = JSONObject(runtime.json())
        assertEquals("driver_raw16", status.getString("source"))
        assertEquals("ranged", status.getString("mode"))
        assertTrue(status.getBoolean("rangedEligible"))
        assertFalse(runtime.isPresenceReady())
        assertFalse(runtime.isWaveReady())
        runtime.observe(1300f, 1_000L, false, false, false)
        runtime.close()
        assertEquals(previous, backing.row)
        assertEquals(0, backing.writes)
        assertEquals(0, backing.clears)
    }

    @Test fun passiveMalformedAndUnreadyRowsCannotOverrideProfileDefaults() {
        val good = row(binaryLegacy())
        val badRows = listOf(good.copy(algorithmVersion = 4), good.copy(snapshotJson = "not-json"),
            good.copy(snapshotJson = "{\"version\":1}"), good.copy(ready = false),
            good.copy(snapshotJson = good.snapshotJson.replace("\"version\":1", "\"version\":3")),
            row(presenceOnly(), ProximityCalibrationRuntime.LEGACY_STORAGE_VERSION))
        for (bad in badRows) {
            val fixture = Fixture(Backing(bad))
            assertEquals("profile", fixture.status().getString("calibrationSource"))
            fixture.operationalWave(legacy().effectiveWave()!!)
            fixture.runtime.close()
            assertEquals(bad, fixture.backing.row)
            assertEquals(0, fixture.backing.writes)
            assertEquals(0, fixture.backing.clears)
        }
    }

    @Test fun malformedBinaryWaveStoredRowFallsBackWithoutStartupFailureOrRewrite() {
        val malformed = JSONObject(ProximityCalibrationRuntime.encode(binaryLegacy().copy(version = 2)))
            .put("wave", JSONObject().apply {
                put("pattern", "SINGLE"); put("clearRaw", 1); put("nearRaw", 2)
                put("nearEnter", .65); put("clearExit", .30)
                put("debounceMs", 150); put("clearArmMs", 700)
                put("minimumNearMs", 200); put("maximumNearMs", 4000)
                put("cooldownMs", 1000); put("maxInterWaveGapMs", 1800)
            }).toString()
        assertNull(ProximityCalibrationRuntime.decode(malformed))
        val previous = row(binaryLegacy()).copy(snapshotJson = malformed)
        val fixture = Fixture(Backing(previous))
        assertEquals("profile", fixture.status().getString("calibrationSource"))
        fixture.operationalWave(legacy().effectiveWave()!!)
        fixture.runtime.close()
        assertEquals(previous, fixture.backing.row)
        assertEquals(0, fixture.backing.writes)
        assertEquals(0, fixture.backing.clears)
    }

    @Test fun cancelBrowserLossPanelLossSourceLossTimeoutAndRestartPreservePreviousRow() {
        for (ending in listOf("cancel", "browser", "panel", "source", "timeout", "restart", "malformed")) {
            val previous = row(binaryLegacy(), ProximityCalibrationRuntime.LEGACY_STORAGE_VERSION)
            val fixture = Fixture(Backing(previous))
            fixture.toReview(100f, 50f, 10f)
            when (ending) {
                "cancel" -> assertTrue(fixture.runtime.cancel(fixture.session))
                "browser" -> {
                    fixture.now += ProximityCalibrationRuntime.BROWSER_LEASE_MS + 1
                    fixture.runtime.visible(); fixture.runtime.tick(fixture.now, true)
                    assertTrue(fixture.status().getString("message").contains("browser disconnected"))
                }
                "panel" -> {
                    fixture.now += ProximityCalibrationRuntime.LOCAL_VISIBILITY_MS + 1
                    fixture.runtime.heartbeat(fixture.session); fixture.runtime.tick(fixture.now, true)
                    assertTrue(fixture.status().getString("message").contains("screen closed"))
                }
                "source" -> fixture.runtime.sourceUnavailable(fixture.now)
                "timeout" -> {
                    fixture.at(1_000 + ProximityCalibrationRuntime.SESSION_TIMEOUT_MS)
                    fixture.runtime.tick(fixture.now, true)
                    assertTrue(fixture.status().getString("message").contains("timed out"))
                }
                "restart" -> fixture.runtime.close()
                "malformed" -> fixture.sample(Float.NaN)
            }
            assertFalse(ending, fixture.runtime.active())
            assertEquals(previous, fixture.backing.row)
            assertEquals(0, fixture.backing.writes)
            assertEquals(0, fixture.backing.clears)
            Fixture(fixture.backing).operationalWave(binaryLegacy().effectiveWave()!!)
        }
    }

    @Test fun writeFailureMaintainsPriorRowAndPriorOperationalInterpretation() {
        val previous = row(binaryLegacy())
        val fixture = Fixture(Backing(previous))
        fixture.toReview(100f, 50f, 10f)
        fixture.backing.failWrite = true
        assertTrue(fixture.runtime.localAction("save"))
        assertEquals("failed", fixture.status().getString("stage"))
        assertEquals(previous, fixture.backing.row)
        assertEquals(1, fixture.backing.writes)
        fixture.operationalWave(binaryLegacy().effectiveWave()!!)
        assertEquals(previous, fixture.backing.row)
    }

    @Test fun unseenPanelCannotBeginAndWrongBrowserCannotOwnOrCancelSession() {
        val fixture = Fixture()
        assertTrue(fixture.runtime.start())
        fixture.session = fixture.status().getString("sessionId")
        assertFalse(fixture.runtime.localAction("begin"))
        assertFalse(fixture.runtime.heartbeat("wrong-session"))
        assertFalse(fixture.runtime.cancel("wrong-session"))
        fixture.runtime.visible()
        assertFalse(fixture.runtime.localAction("begin"))
        fixture.sample(100f, live = false, capture = true)
        assertTrue(fixture.runtime.localAction("begin"))
        assertTrue(fixture.runtime.heartbeat(fixture.session))
    }

    @Test fun introAcquisitionWaitRequiresFreshEvidenceAndPreservesPreviousRow() {
        val previous = row(legacy())
        val fixture = Fixture(Backing(previous))
        fixture.sample(100f); fixture.advance(200)
        assertTrue(fixture.runtime.isWaveReady())
        assertTrue(fixture.runtime.start())
        fixture.session = fixture.status().getString("sessionId")
        fixture.runtime.visible()
        assertFalse(fixture.runtime.localAction("begin"))
        fixture.runtime.sourceUnavailable(fixture.now)
        assertEquals("intro", fixture.stage())
        fixture.sample(100f, live = false, capture = false)
        assertEquals("source_unavailable", fixture.status().getString("health"))
        assertFalse(fixture.runtime.localAction("begin"))
        fixture.sample(100f, live = false, capture = true)
        assertTrue(fixture.runtime.localAction("begin"))
        assertEquals(previous, fixture.backing.row)
        assertEquals(0, fixture.backing.writes)
    }

    @Test fun introWithNoReadingEndsWithAPlainReasonWithinItsBoundAndRetryRestartsIt() {
        val previous = row(legacy())
        val fixture = Fixture(Backing(previous))
        assertTrue(fixture.runtime.start())
        fixture.session = fixture.status().getString("sessionId")
        fixture.runtime.visible()
        fixture.advance(ProximityCalibrationRuntime.NO_READING_TIMEOUT_MS - 1)
        assertEquals("intro", fixture.stage())
        assertTrue(fixture.runtime.active())
        fixture.advance(1)
        assertFalse(fixture.runtime.active())
        assertEquals(ProximityCalibrationRuntime.NO_READING_MESSAGE, fixture.status().getString("message"))
        assertEquals(previous, fixture.backing.row)
        assertTrue(fixture.runtime.localAction("retry"))
        fixture.advance(ProximityCalibrationRuntime.NO_READING_TIMEOUT_MS - 1)
        assertEquals("intro", fixture.stage())
    }

    @Test fun introWithALiveReadingIsNotEndedByTheNoReadingBound() {
        val fixture = Fixture()
        assertTrue(fixture.runtime.start())
        fixture.session = fixture.status().getString("sessionId")
        fixture.runtime.visible()
        fixture.sample(100f, live = false, capture = true)
        fixture.advance(ProximityCalibrationRuntime.NO_READING_TIMEOUT_MS + 1)
        assertEquals("intro", fixture.stage())
        assertTrue(fixture.runtime.localAction("begin"))
    }

    @Test fun retryRequiresFreshProbeBeforeQuietSourceCanBeCapturedAgain() {
        val fixture = Fixture()
        fixture.begin(100f)
        assertTrue(fixture.runtime.localAction("cancel"))
        assertTrue(fixture.runtime.localAction("retry"))
        assertFalse(fixture.runtime.localAction("begin"))
        fixture.advance(4_000)
        assertEquals("intro", fixture.stage())
        fixture.sample(100f, live = false, capture = true)
        assertTrue(fixture.runtime.localAction("begin"))
        fixture.capture(100f)
        assertEquals("near", fixture.stage())
    }

    @Test fun queuedSensorReceiptOlderThanLocalBeginUsesSerializedProcessingTime() {
        val fixture = Fixture()
        fixture.begin(100f)
        fixture.advance(10)
        fixture.runtime.observe(100f, 900, true)
        assertTrue(fixture.runtime.active())
        assertEquals("clear", fixture.stage())
        assertEquals("healthy", fixture.status().getString("health"))
    }

    @Test fun staleAndFreshProbeReadsHaveDifferentCalibrationEligibilityButNeverWake() {
        val fixture = Fixture()
        fixture.begin(100f)
        fixture.sample(100f, live = false, capture = false)
        fixture.advance(ProximityCalibrationEngine.COUNTDOWN_MS + 1_000)
        assertEquals("clear", fixture.stage())
        fixture.sample(100f, live = false, capture = true)
        repeat((ProximityCalibrationEngine.CAPTURE_HOLD_MS / 250).toInt()) {
            fixture.at(fixture.now + 250)
            fixture.sample(100f, live = false, capture = true)
        }
        assertEquals("near", fixture.stage())
        val operational = Fixture(Backing(row(binaryLegacy())))
        operational.sample(0f); operational.advance(200)
        assertFalse(operational.advance(800, 1f).deliberateGesture)
        assertFalse(operational.advance(50).deliberateGesture)
        assertFalse(operational.sample(0f, live = false, capture = true).deliberateGesture)
        assertFalse(operational.advance(200).deliberateGesture)
    }

    @Test fun rejectedCurrentGestureReleasesCooldownButOldCompletionCannotReleaseNewerGesture() {
        val model = binaryLegacy().copy(cooldownMs = 2_000)
        val fixture = Fixture(Backing(row(model)))
        fixture.operationalWave(model.effectiveWave()!!)
        val first = fixture.runtime.gestureToken()
        fixture.runtime.completeGesture(first, false)
        fixture.advance(550, 1f)
        assertTrue(fixture.advance(200).deliberateGesture)
        assertFalse(fixture.advance(150, 0f).deliberateGesture)
        assertFalse(fixture.advance(150).deliberateGesture)
        val second = fixture.runtime.gestureToken()
        assertTrue(second > first)
        fixture.runtime.completeGesture(first, false)
        fixture.advance(550, 1f)
        assertFalse(fixture.advance(150).deliberateGesture)
        assertFalse(fixture.advance(150, 0f).deliberateGesture)
        assertFalse(fixture.advance(150).deliberateGesture)
    }

    @Test fun rejectedApproachCannotRepeatUntilClearAndFreshEntryInEitherBinaryPolarity() {
        for ((clear, near) in listOf(0f to 1f, 1f to 0f)) {
            val model = binaryLegacy().copy(clearRaw = clear, nearRaw = near, cooldownMs = 2_000)
            val fixture = Fixture(Backing(row(model)))
            fixture.sample(clear)
            fixture.advance(800)
            assertFalse(fixture.sample(near).deliberateGesture)
            assertTrue(fixture.advance(200).deliberateGesture)
            val token = fixture.runtime.gestureToken()
            fixture.runtime.completeGesture(token, false)
            assertFalse(fixture.advance(5_000, near).deliberateGesture)
            assertFalse(fixture.advance(200).deliberateGesture)
            assertEquals(token, fixture.runtime.gestureToken())
            assertFalse(fixture.sample(clear).deliberateGesture)
            fixture.advance(800)
            assertFalse(fixture.sample(near).deliberateGesture)
            assertTrue(fixture.advance(200).deliberateGesture)
            assertTrue(fixture.runtime.gestureToken() > token)
            assertEquals(0, fixture.backing.writes)
        }
    }

    @Test fun historicalReadSourceLossAndRestartRevokePendingApproachWithoutChangingCalibration() {
        for (interruption in listOf("historical", "source_loss", "restart")) {
            val saved = row(binaryLegacy())
            val backing = Backing(saved)
            var fixture = Fixture(backing)
            fixture.sample(0f)
            fixture.advance(800)
            assertFalse(fixture.sample(1f).deliberateGesture)
            fixture.at(fixture.now + 50)
            when (interruption) {
                "historical" -> assertFalse(fixture.sample(1f, live = false, capture = false).deliberateGesture)
                "source_loss" -> assertFalse(fixture.runtime.sourceUnavailable(fixture.now).deliberateGesture)
                else -> {
                    fixture.runtime.close()
                    fixture = Fixture(backing)
                }
            }
            assertFalse(fixture.sample(1f).deliberateGesture)
            assertFalse(fixture.advance(200).deliberateGesture)
            assertEquals(0L, fixture.runtime.gestureToken())
            fixture.sample(0f)
            fixture.advance(800)
            fixture.sample(1f)
            assertTrue(fixture.advance(200).deliberateGesture)
            assertEquals(saved, backing.row)
            assertEquals(0, backing.writes)
            assertEquals(0, backing.clears)
        }
    }

    @Test fun presenceOnlyApproachAndWaveOnlyGesturePublishIndependentCapabilities() {
        val presence = Fixture(Backing(row(presenceOnly())))
        presence.settlePresence(100f)
        assertTrue(presence.runtime.isLearnedSignal())
        assertTrue(presence.runtime.isPresenceReady())
        assertFalse(presence.runtime.isWaveReady())
        var approached = false
        val approach = presence.settlePresence(50f) { approached = approached || it.presenceApproach }
        assertTrue(approached)
        assertTrue(presence.runtime.isPresenceNear())
        assertEquals(true, approach.near)
        assertFalse(approach.deliberateGesture)
        assertFalse(presence.status().getBoolean("waveSupported"))

        val waveOnly = presenceOnly().copy(presenceSupported = false, wave = WaveCalibration(clearRaw = 100f, nearRaw = 10f))
        val wave = Fixture(Backing(row(waveOnly)))
        wave.operationalWave(waveOnly.wave!!)
        assertFalse(wave.runtime.isLearnedSignal())
        assertFalse(wave.runtime.isPresenceReady())
        assertFalse(wave.runtime.isPresenceNear())
        assertTrue(wave.runtime.isWaveReady())
        assertFalse(wave.status().getBoolean("presenceSupported"))
        assertTrue(wave.status().isNull("near"))
        assertTrue(wave.status().isNull("level"))
    }

    @Test fun automaticJourneyExposesTimedCuesAndCanSavePresenceWithoutWave() {
        val fixture = Fixture()
        fixture.toReview(100f, 50f, null)
        assertEquals("validated", fixture.status().getString("presenceStatus"))
        assertEquals("not_distinguishable", fixture.status().getString("waveStatus"))
        assertTrue(fixture.status().getBoolean("canSave"))
        assertFalse(fixture.runtime.isPresenceReady())
        assertTrue(fixture.runtime.localAction("save"))
        val saved = assertDecoded(assertRow(fixture.backing).snapshotJson)
        assertTrue(saved.presenceSupported)
        assertNull(saved.wave)
        assertEquals(2, saved.version)
    }

    @Test fun twentyCountCalibrationPublishesOccupiedAndClearFromFreshSustainedSamples() {
        val fixture = Fixture()
        fixture.toReview(220f, 240f, null)
        assertTrue(fixture.runtime.localAction("save"))

        val initialClear = fixture.settlePresence(220f)
        assertTrue(fixture.runtime.isPresenceReady())
        assertTrue(fixture.status().getBoolean("presenceReady"))
        assertEquals(false, initialClear.near)
        assertEquals(0, initialClear.normalizedLevel)

        fixture.sample(240f)
        assertEquals("a timer does not create fresh statistical evidence", false, fixture.advance(200).near)
        val occupied = fixture.settlePresence(240f)
        assertTrue(fixture.runtime.isPresenceReady())
        assertEquals(true, occupied.near)
        assertEquals(100, occupied.normalizedLevel)

        val clear = fixture.settlePresence(220f)
        assertTrue(fixture.runtime.isPresenceReady())
        assertEquals(false, clear.near)
        assertEquals(0, clear.normalizedLevel)
        fixture.runtime.close()
    }

    @Test fun archivedRangedReadingsNeverChangeTheSavedCalibrationOrBecomeWaveWake() {
        val saved = row(keptRanged())
        val fixture = Fixture(Backing(saved), driftMargin = 100f, keepStoredInsideDriftBand = true)
        val start = fixture.now
        val samples = ARCHIVED_RANGED_GAP.trim().split(Regex("""\s+""")).map { sample ->
            val (time, raw) = sample.split(',')
            time.toLong() to raw.toFloat()
        }
        assertEquals("user", fixture.status().getString("calibrationSource"))
        assertFalse(fixture.status().getBoolean("calibrationDriftUnsafe"))
        // The archive has no occupancy labels; it cannot prescribe when the room became clear.
        for ((time, raw) in samples) {
            fixture.at(start + time)
            assertFalse("a raw snapshot must not become a wave at $time", fixture.sample(raw).deliberateGesture)
        }
        assertFalse(fixture.advance(300_000, 145f).deliberateGesture)
        assertTrue(fixture.runtime.isPresenceReady())
        assertFalse(fixture.runtime.isWaveReady())
        assertEquals(saved, fixture.backing.row)
        assertEquals(0, fixture.backing.writes)
        assertEquals(0, fixture.backing.clears)
    }

    @Test fun rangedPresenceUsesSustainedSeparationInTheOldHysteresisGapAcrossSpansAndPolarities() {
        for (model in listOf(keptRanged(), keptRanged().copy(clearRaw = 157f, nearRaw = 131f),
            keptRanged().copy(nearRaw = 1_000f))) {
            val saved = row(model)
            val fixture = Fixture(Backing(saved), driftMargin = 100f, keepStoredInsideDriftBand = true)
            assertEquals(false, fixture.settlePresence(model.clearRaw).near)
            assertEquals(true, fixture.settlePresence(model.nearRaw).near)
            val separated = model.clearRaw + (model.nearRaw - model.clearRaw) * .5f
            repeat((240_000 / 250).toInt()) {
                val held = fixture.advance(250, separated)
                assertEquals("sustained separation for $model", true, held.near)
                assertFalse(held.deliberateGesture)
                assertFalse(held.presenceApproach)
            }
            val clear = fixture.settlePresence(model.clearRaw)
            assertEquals(false, clear.near)
            assertFalse(clear.deliberateGesture)
            assertFalse(clear.presenceApproach)
            assertEquals(saved, fixture.backing.row)
            assertEquals(0, fixture.backing.writes)
            assertEquals(0, fixture.backing.clears)
        }
    }

    @Test fun binaryPresenceKeepsItsDebounceAndOnChangeHoldWithoutRewritingCalibration() {
        val model = binaryLegacy()
        val saved = row(model)
        val fixture = Fixture(Backing(saved), driftMargin = 100f, keepStoredInsideDriftBand = true)
        fixture.sample(model.nearRaw)
        assertEquals(true, fixture.advance(model.debounceMs).near)
        val held = fixture.advance(360_000)
        assertEquals(true, held.near)
        assertFalse(held.deliberateGesture)
        fixture.sample(model.clearRaw)
        assertEquals(false, fixture.advance(model.debounceMs).near)
        assertEquals(saved, fixture.backing.row)
        assertEquals(0, fixture.backing.writes)
        assertEquals(0, fixture.backing.clears)
    }

    @Test fun restoredRangedCalibrationRequiresFreshPresenceEvidenceWithoutChangingTheRestoredRow() {
        val fixture = Fixture(Backing(row(keptRanged())), driftMargin = 100f, keepStoredInsideDriftBand = true)
        fixture.settlePresence(131f)
        assertEquals(true, fixture.settlePresence(157f).near)
        val restored = row(keptRanged().copy(nearRaw = 158f))
        fixture.backing.inserted[key()] = restored
        assertTrue(fixture.runtime.reloadStored())
        assertNull(fixture.sample(131f).near)
        assertFalse(fixture.runtime.isPresenceReady())
        assertNull(fixture.advance(200).near)
        assertEquals(false, fixture.settlePresence(131f).near)
        assertEquals(true, fixture.settlePresence(158f).near)
        assertEquals(restored, fixture.backing.inserted[key()])
        assertEquals(0, fixture.backing.writes)
        assertEquals(0, fixture.backing.clears)
    }

    @Test fun candidatePresenceNeverPublishesBeforeAtomicSave() {
        val previous = row(binaryLegacy())
        val fixture = Fixture(Backing(previous))
        fixture.toReview(100f, 50f, 10f)
        val provisional = fixture.sample(10f)
        assertEquals(ProximityReportGate.NONE, provisional.reportMask)
        assertNull(provisional.near)
        assertNull(provisional.normalizedLevel)
        assertFalse(provisional.presenceApproach)
        assertFalse(provisional.deliberateGesture)
        assertEquals(previous, fixture.backing.row)
        assertEquals(0, fixture.backing.writes)
        assertTrue(fixture.runtime.cancel(fixture.session))
        fixture.operationalWave(binaryLegacy().effectiveWave()!!)
    }

    @Test fun codecRoundTripsLegacyPresenceWaveAndCombinedCapabilitiesWithoutImplicitUpgrade() {
        val wave = WaveCalibration(pattern = WavePattern.DOUBLE, clearRaw = 100f, nearRaw = 10f, maxInterWaveGapMs = 2100)
        val models = listOf(legacy(), presenceOnly(), presenceOnly().copy(wave = wave),
            presenceOnly().copy(presenceSupported = false, wave = wave))
        for (model in models) assertEquals(model, ProximityCalibrationRuntime.decode(ProximityCalibrationRuntime.encode(model)))
        val legacyJson = JSONObject(ProximityCalibrationRuntime.encode(legacy()))
        assertFalse(legacyJson.has("wave"))
        assertFalse(legacyJson.has("presenceSupported"))
        assertNotNull(assertDecoded(legacyJson.toString()).effectiveWave())
        assertNull(assertDecoded(ProximityCalibrationRuntime.encode(presenceOnly())).effectiveWave())
    }

    @Test fun codecRejectsMalformedWaveAndNeitherCapabilityInsteadOfEnablingWake() {
        val good = JSONObject(ProximityCalibrationRuntime.encode(presenceOnly().copy(wave = WaveCalibration(clearRaw = 100f, nearRaw = 10f))))
        fun changed(edit: (JSONObject) -> Unit): String = JSONObject(good.toString()).apply(edit).toString()
        val malformed = listOf(
            changed { it.put("version", 3) }, changed { it.put("version", "2") },
            changed { it.put("presenceSupported", "true") }, changed { it.put("presenceSupported", false); it.put("wave", JSONObject.NULL) },
            changed { it.getJSONObject("wave").put("nearRaw", "10") },
            changed { it.getJSONObject("wave").put("clearRaw", 10); it.getJSONObject("wave").put("nearRaw", 100) },
            changed { it.getJSONObject("wave").put("pattern", "TRIPLE") },
            changed { it.getJSONObject("wave").put("maxInterWaveGapMs", 299) },
            changed { it.getJSONObject("wave").put("debounceMs", 5001) },
            changed { it.getJSONObject("wave").remove("clearRaw") },
            changed { it.put("nearRaw", 1e100) },
        )
        for (raw in malformed) assertNull(raw, ProximityCalibrationRuntime.decode(raw))
    }

    @Test fun profileVersionControlsLegacyWaveAndVersionTwoOptionalCapabilities() {
        val old = ProfileProximityCalibration(1, "ranged", 100f, 50f, "Fixture")
        assertEquals(1, ProximityCalibrationRuntime.fromProfile(old).version)
        assertNotNull(ProximityCalibrationRuntime.fromProfile(old).effectiveWave())
        assertNull(ProximityCalibrationRuntime.fromProfile(old.copy(formatVersion = 2)).effectiveWave())
        val wave = old.copy(formatVersion = 2, presenceSupported = false,
            wave = ProfileWaveCalibration("double", 100f, 10f))
        val mapped = ProximityCalibrationRuntime.fromProfile(wave)
        assertFalse(mapped.presenceSupported)
        assertEquals(WavePattern.DOUBLE, ProximityCalibrationRuntime.profileWavePattern(wave))
        assertEquals(WavePattern.SINGLE, ProximityCalibrationRuntime.profileWavePattern(null))
        assertEquals(WavePattern.SINGLE, ProximityCalibrationRuntime.profileWavePattern(old))
        assertEquals(WavePattern.DOUBLE, mapped.wave!!.pattern)
        assertEquals(10f, mapped.wave!!.nearRaw, 0f)
    }

    private class Fixture(
        val backing: Backing = Backing(), source: String = SOURCE, profile: String = PROFILE,
        private val pattern: WavePattern = WavePattern.SINGLE, revision: String? = null, driftMargin: Float = 0f,
        keepStoredInsideDriftBand: Boolean = false,
    ) {
        var now = 1_000L
        val runtime = ProximityCalibrationRuntime(source, profile, legacy(), Store(backing),
            elapsed = { now }, wall = { 1_800_000_000_000L + now }, requestedWavePattern = pattern,
            profileRevision = revision, driftMargin = driftMargin, keepStoredInsideDriftBand = keepStoredInsideDriftBand)
        var session = ""
        fun status() = JSONObject(runtime.json())
        fun stage() = status().getString("stage")
        fun at(value: Long) {
            now = value
            if (session.isNotEmpty()) { runtime.heartbeat(session); runtime.visible() }
        }
        fun sample(raw: Float, live: Boolean = true, capture: Boolean = live) = runtime.observe(raw, now, true, live, capture)
        fun advance(delta: Long, raw: Float? = null): ProximityCalibrationRuntime.Decision {
            at(now + delta)
            return if (raw == null) runtime.tick(now, true) else sample(raw)
        }
        fun begin(clear: Float) {
            assertTrue(runtime.start())
            session = status().getString("sessionId")
            assertTrue(runtime.visible())
            sample(clear, live = false, capture = true)
            assertTrue(runtime.localAction("begin"))
            assertEquals("clear", stage())
            assertEquals("move_away", status().getString("cue"))
            assertEquals(ProximityCalibrationEngine.COUNTDOWN_MS, status().getLong("cueDurationMs"))
            assertTrue(status().getLong("cueRemainingMs") > 0)
        }
        fun capture(raw: Float) {
            advance(ProximityCalibrationEngine.COUNTDOWN_MS, raw)
            repeat((ProximityCalibrationEngine.CAPTURE_HOLD_MS / 250).toInt()) { advance(250, raw) }
        }
        fun settlePresence(raw: Float, observe: (ProximityCalibrationRuntime.Decision) -> Unit = {}): ProximityCalibrationRuntime.Decision {
            var result = sample(raw)
            repeat(24) { result = advance(250, raw); observe(result) }
            return result
        }
        fun toReview(clear: Float, near: Float, hand: Float?) {
            begin(clear)
            capture(clear); assertEquals("near", stage())
            capture(near); assertEquals("return_clear", stage())
            capture(clear); assertEquals("wave_baseline", stage())
            val waveClear = if (pattern == WavePattern.DOUBLE) clear else near
            capture(waveClear); assertEquals("wave_capture", stage())
            if (hand == null) {
                advance(ProximityCalibrationEngine.WAVE_PHASE_TIMEOUT_MS)
            } else {
                advance(ProximityCalibrationEngine.COUNTDOWN_MS, hand)
                advance(400, waveClear)
                assertEquals("waves", stage())
                advance(ProximityCalibrationEngine.COUNTDOWN_MS, waveClear)
                advance(200)
                repeat(3) {
                    repeat(if (pattern == WavePattern.DOUBLE) 2 else 1) {
                        assertFalse(advance(800, hand).deliberateGesture)
                        assertFalse(advance(400, waveClear).deliberateGesture)
                        assertFalse(advance(150).deliberateGesture)
                    }
                }
            }
            assertEquals("review", stage())
        }
        fun operationalWave(wave: WaveCalibration) {
            assertFalse(advance(1_500, wave.clearRaw).deliberateGesture)
            advance(200)
            assertTrue(runtime.isWaveReady())
            repeat(if (wave.pattern == WavePattern.DOUBLE) 2 else 1) { index ->
                assertFalse(advance(800, wave.nearRaw).deliberateGesture)
                assertEquals(wave.pattern == WavePattern.SINGLE, advance(300).deliberateGesture)
                assertFalse(sample(wave.clearRaw).deliberateGesture)
                assertEquals(wave.pattern == WavePattern.DOUBLE && index == 1, advance(150).deliberateGesture)
            }
            assertFalse(advance(1).deliberateGesture)
        }
    }

    private class Backing(var row: EntityCatalogStore.ProximityModelRow? = null) {
        var writes = 0; var clears = 0; var closes = 0; var failWrite = false; var failRead = false
        /** Rows added by insert-if-absent, keyed as the real table is. */
        val inserted = mutableMapOf<String, EntityCatalogStore.ProximityModelRow>()
    }
    private class Store(private val backing: Backing) : ProximityModelStore {
        // Deliberately return mismatched rows too: the runtime must validate their fingerprint.
        override fun readProximityModel(fingerprint: String) =
            if (backing.failRead) throw IllegalStateException("database locked")
            else backing.inserted[fingerprint] ?: backing.row
        override fun insertProximityModelIfAbsent(model: EntityCatalogStore.ProximityModelRow): Boolean =
            backing.inserted.putIfAbsent(model.fingerprint, model) == null
        override fun writeProximityBatch(model: EntityCatalogStore.ProximityModelRow,
            rollups: List<EntityCatalogStore.ProximityRollupRow>, episodes: List<EntityCatalogStore.ProximityEpisodeRow>, now: Long) {
            backing.writes++
            assertTrue(rollups.isEmpty()); assertTrue(episodes.isEmpty())
            if (backing.failWrite) throw IllegalStateException("atomic transaction failed")
            backing.row = model
        }
        override fun clearProximityLearning(fingerprint: String) {
            backing.clears++
            backing.inserted.remove(fingerprint)
            if (backing.row?.fingerprint == fingerprint) backing.row = null
        }
        override fun close() { backing.closes++ }
    }
    companion object {
        // Anonymized ranged-source capture: elapsed milliseconds since the final enter-level reading,
        // then raw counts. Only consecutive unchanged raw values are omitted.
        private val ARCHIVED_RANGED_GAP = """
            0,148 304,144 912,146 1207,145 1508,143 1799,146 2102,144 2405,145 2698,144 3001,143 3311,144 3903,145
            4205,144 5156,142 5449,141 5748,144 6043,142 6336,145 6639,143 6932,144 9763,142 10055,145 10358,144 10651,145
            11261,146 11604,145 11915,144 12815,146 13119,145 13420,143 13716,146 14021,145 14319,142 14627,144 15226,145 15812,144
            16718,142 17011,143 17326,144 17955,146 18262,145 18570,144 20027,143 20317,145 20623,143 20916,145 21222,144 21828,146
            22122,142 22443,144 22725,145 25301,144 25880,143 26466,144 26758,142 27046,144 27649,143 28286,145 28894,143 29520,144
            29829,145 30127,144 30427,145 30732,144 31368,146 31665,143 31980,144 32283,145 32586,144 32894,143 33206,144 33601,143
            33906,144 34201,143 34500,145 34807,143 35113,144 35725,145 36109,143 36414,145 36717,143 37023,144 37636,142 37953,144
            38283,143 38593,144 38906,143 41478,144 42123,142 42408,143 42693,144 42991,143 44555,146 44861,143 45162,145 45470,144
            45778,142 46422,144 46721,143 47026,142 47341,144 47655,146 47971,142 48284,143 48596,144 48894,143 49203,145 49499,144
            50141,143 50459,146 50754,145 51132,146 51485,145 51775,143 52367,146 52675,143 53001,142 53319,144 53630,143 53931,146
            54260,144 54906,145 55222,143 55516,144 55807,142 56107,143 56723,142 57060,146 57366,143 57972,144 58285,143 59517,145
            62096,143 62414,145 62721,143 63934,144 64242,143 64544,142 64849,146 65195,142 65501,144 65799,143 66400,144 66707,142
            67016,143 67630,142 67932,145 68248,143 69177,142 69466,146 69765,144 70075,145 70365,142 70671,141 70968,144 71268,143
            71585,147 71899,143 72489,144 73073,141 73365,144 73664,143 73963,145 74873,143 75485,145 78027,143 78327,144 78617,143
            78910,144 79202,143 79503,145 79803,143 80100,142 80402,144 80995,145 81336,143 81638,145 81940,143 82232,144 82592,142
            82898,143 83191,142 83495,144 84129,143 84711,144 85008,147 85314,142 85638,143 85932,145 86228,144 86535,145 86833,144
            87441,146 87733,143 88025,142 88317,143 88612,145 89183,140 89556,143 89851,144 90437,143 90729,145 91033,144 91628,145
            91919,143 92504,144 92787,143 93390,141 93695,147 93990,142 94296,143 94889,145 95196,142 95486,144 95786,143 96092,144
            96396,143 96707,145 97015,142 97311,145 97615,141 97912,145 98508,144 99100,143 99396,142 99701,144 100302,143 100590,144
            100901,142 101204,144 101495,143 101797,145 102110,142 102399,143 102696,144 103011,143 103307,144 103901,143 104203,144 104506,145
            104798,143 105098,144 105406,142 105695,145 105999,143 106606,144 107236,143 107540,144 107829,145 108127,143 108440,144 109361,143
            109668,147 109986,145 110292,144 110904,143 111199,144 111514,145 111810,143 112114,144 112414,146 112697,141 113000,145 113310,144
            113603,145 114270,144 114581,141 114915,143 115222,142 115842,146 116138,145 116438,142 116790,145 117080,142 117368,143 118258,145
            118549,143 118839,142 119129,143 119423,141 119719,144 120009,143 120636,144 120934,146 121235,144 121534,143 122114,142 122415,141
            122711,144 123018,143 123635,142 123926,143 124534,146 124825,142 125128,146 125427,143 126025,144 126630,145 126923,143 127519,144
            127817,143 128429,142 129031,146 129329,144 129934,143 130224,140 130525,146 130826,144 131724,141 132317,143 132622,144 133271,145
            133561,144 133859,143 134452,142 134751,145 135054,146 135346,144 135919,142 136217,145 136501,144 136794,143 137076,144 137651,143
            137949,145 138243,144 138545,142 138846,143 139440,144 140382,143 140679,144 140962,145 141244,144 142437,143 143039,144 143650,143
            143945,146 144400,144 146967,142 147267,144 147862,146 148165,145 148481,143 149068,146 149362,144 149655,143 149956,146 150574,144
            151182,143 152408,144 152701,145 152990,144 153294,143 153602,145 153901,143 154200,146 154495,142 154787,145 155071,144 155649,142
            155988,145 156285,144 156917,143 157215,144 157506,146 157842,144 158151,143 158447,144 158752,147 159053,143 159346,144 159644,145
            160245,144 160570,142 160895,145 161492,144 161784,145 162083,143 162365,145 162650,144 162962,143 163261,144 163560,143 163873,145
            164165,144 165066,143 165376,142 165667,144 165951,143 166257,144 166555,143 166855,144 167156,141 167459,143 167767,144 168081,143
            168384,141 168669,143 168975,144 169262,143 169564,144 169874,143 170458,144 170760,143 171659,145 171967,144 172274,143 172564,142
            172867,144 173463,145 173784,144 174072,143 174377,142 174666,145 174962,144 175267,143 175561,144 175879,143 176163,144 176469,143
            176767,144 177072,145 177663,143 177975,144 179321,143 179912,144 180220,146
        """
        private const val SOURCE = "android-hal|same-misleading-range-metadata"
        private const val PROFILE = "profile-r1"
        private fun key(source: String = SOURCE) = ProximityCalibrationRuntime.calibrationKey(PROFILE, source)
        private fun legacy() = Calibration(version = 1, mode = Mode.RANGED, clearRaw = 100f, nearRaw = 50f)
        private fun binaryLegacy() = Calibration(version = 1, mode = Mode.BINARY, clearRaw = 0f, nearRaw = 1f)
        private fun presenceOnly() = Calibration(mode = Mode.RANGED, clearRaw = 100f, nearRaw = 50f)
        private fun keptRanged() = Calibration(mode = Mode.RANGED, clearRaw = 131f, nearRaw = 157f)
        private fun row(value: Calibration, algorithm: Int = ProximityCalibrationRuntime.STORAGE_VERSION) = EntityCatalogStore.ProximityModelRow(
            key(), algorithm,
            "explicit-calibration-v${value.version}", ProximityCalibrationRuntime.encode(value), true, 1_800_000_000_000L)
        private fun assertRow(backing: Backing): EntityCatalogStore.ProximityModelRow {
            assertNotNull(backing.row)
            return backing.row!!
        }
        private fun assertDecoded(raw: String): Calibration {
            val result = ProximityCalibrationRuntime.decode(raw)
            assertNotNull(raw, result)
            return result!!
        }
    }
}
