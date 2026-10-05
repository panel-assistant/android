package io.panelassistant.android

import io.panelassistant.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProximityWizardPresentationTest {
    @Test
    fun rawValueIsAReadableFocalPointOnEverySupportedPanelShape() {
        val square = proximityWizardLayoutSpec(480, 432)
        assertEquals(ProximityWizardLayoutMode.COMPACT_SQUARE, square.mode)
        assertTrue(square.rawValueSp >= 84f)

        val portrait = proximityWizardLayoutSpec(500, 889)
        assertEquals(ProximityWizardLayoutMode.PORTRAIT, portrait.mode)
        assertTrue(portrait.rawValueSp >= 96f)

        val landscape = proximityWizardLayoutSpec(1449, 906)
        assertEquals(ProximityWizardLayoutMode.LANDSCAPE, landscape.mode)
        assertTrue(landscape.rawValueSp >= 96f)
    }

    @Test
    fun compactSquareIsNotMisclassifiedByItsNavigationBarReducedHeight() {
        assertEquals(
            ProximityWizardLayoutMode.COMPACT_SQUARE,
            proximityWizardLayoutSpec(480, 432).mode,
        )
        assertEquals(
            ProximityWizardLayoutMode.LANDSCAPE,
            proximityWizardLayoutSpec(600, 432).mode,
        )
    }

    @Test
    fun spokenInstructionsAreStableAcrossVisualCueChangesAndNeverJoinScreenCopy() {
        val speech = proximityWizardSpeech(
            stage = "clear",
            awaitingReading = false,
            mode = "ranged",
            waveCount = 1,
            capabilities = ProximityWizardCapabilities.NEITHER,
            acceptedGestures = 0,
            requiredGestures = 3,
        )
        assertEquals("clear", speech?.prompt)
        assertEquals(R.string.proximity_wizard_speech_clear, speech?.textRes)

        val cancelled = proximityWizardSpeech(
            stage = "cancelled",
            awaitingReading = false,
            mode = "ranged",
            waveCount = 1,
            capabilities = ProximityWizardCapabilities.NEITHER,
            acceptedGestures = 0,
            requiredGestures = 3,
        )
        assertEquals("cancelled", cancelled?.prompt)
        assertEquals(R.string.proximity_wizard_speech_cancelled, cancelled?.textRes)
    }

    @Test
    fun narrationSetsBinaryExpectationsAndReportsGestureProgress() {
        val binary = proximityWizardSpeech(
            stage = "intro",
            awaitingReading = false,
            mode = "binary",
            waveCount = 1,
            capabilities = ProximityWizardCapabilities.NEITHER,
            acceptedGestures = 0,
            requiredGestures = 3,
        )
        assertEquals(R.string.proximity_wizard_speech_intro_binary, binary?.textRes)

        val progress = proximityWizardSpeech(
            stage = "waves",
            awaitingReading = false,
            mode = "ranged",
            waveCount = 1,
            capabilities = ProximityWizardCapabilities.BOTH,
            acceptedGestures = 2,
            requiredGestures = 3,
        )
        assertEquals("waves|2|3", progress?.prompt)
        assertEquals(listOf(2, 3), progress?.formatArgs)
    }

    @Test
    fun liveRawValueAppearsThroughoutObservationButNotOnReviewOrTerminalScreens() {
        for (stage in listOf("intro", "clear", "near", "return_clear", "wave_baseline", "wave_capture", "waves", "review")) {
            assertTrue(stage, proximityWizardShowsRawValue(stage))
        }
        for (stage in listOf("saving", "saved", "cancelled", "timed_out", "failed", "unavailable")) {
            assertFalse(stage, proximityWizardShowsRawValue(stage))
        }
    }

    @Test
    fun clearCaptureKeepsPersonAwayWhileNearCaptureKeepsPersonAtPanel() {
        assertFalse(proximityWizardHoldsNearPanel("clear", "single"))
        assertFalse(proximityWizardHoldsNearPanel("return_clear", "single"))
        assertTrue(proximityWizardHoldsNearPanel("near", "single"))
        assertTrue(proximityWizardHoldsNearPanel("wave_baseline", "single"))
        assertFalse(proximityWizardHoldsNearPanel("wave_baseline", "double"))
    }

    @Test
    fun doubleWaveInstructionsRequireExplicitPattern() {
        assertEquals(1, proximityWizardWaveCount("single"))
        assertEquals(1, proximityWizardWaveCount(""))
        assertEquals(1, proximityWizardWaveCount("unknown"))
        assertEquals(2, proximityWizardWaveCount("double"))
    }

    @Test
    fun reviewKeepsPresenceAndWaveCapabilitiesIndependent() {
        assertEquals(ProximityWizardCapabilities.BOTH, proximityWizardCapabilities(true, true))
        assertEquals(ProximityWizardCapabilities.PRESENCE_ONLY, proximityWizardCapabilities(true, false))
        assertEquals(ProximityWizardCapabilities.WAVE_ONLY, proximityWizardCapabilities(false, true))
        assertEquals(ProximityWizardCapabilities.NEITHER, proximityWizardCapabilities(false, false))
    }

    @Test
    fun countdownRoundsUpWithoutInventingTimeAfterExpiry() {
        assertEquals(3, proximityWizardCountdownSeconds(3000, 3000))
        assertEquals(3, proximityWizardCountdownSeconds(2001, 3000))
        assertEquals(2, proximityWizardCountdownSeconds(2000, 3000))
        assertEquals(1, proximityWizardCountdownSeconds(1, 3000))
        assertNull(proximityWizardCountdownSeconds(0, 3000))
    }

    @Test
    fun missingOrInvalidCueDurationShowsWaitingInsteadOfFalseCountdown() {
        assertNull(proximityWizardCountdownSeconds(0, 0))
        assertNull(proximityWizardCountdownSeconds(2500, 0))
        assertNull(proximityWizardCountdownSeconds(3001, 3000))
        assertNull(proximityWizardCountdownSeconds(-1, 3000))
        assertNull(proximityWizardCountdownSeconds(3000, -1))
        assertEquals(Int.MAX_VALUE, proximityWizardCountdownSeconds(Long.MAX_VALUE, Long.MAX_VALUE))
    }

    @Test
    fun presenceAndWaveBaselinesUseDifferentPhysicalIllustrations() {
        for (stage in listOf("clear", "near", "return_clear", "wave_baseline")) {
            assertFalse(proximityWizardUsesHand(stage, ProximityWizardCue.HOLD))
            assertFalse(proximityWizardUsesHand(stage, ProximityWizardCue.APPROACH))
        }
        for (stage in listOf("wave_capture", "waves")) {
            assertTrue(proximityWizardUsesHand(stage, ProximityWizardCue.HOLD))
            assertTrue(proximityWizardUsesHand(stage, ProximityWizardCue.PREPARE))
        }
        assertTrue(proximityWizardUsesHand("intro", ProximityWizardCue.WAVE))
    }

    @Test
    fun observationsAndSavingNeverOfferAnIntermediateAdvanceButton() {
        for (stage in listOf("clear", "near", "return_clear", "wave_baseline", "wave_capture", "waves", "saving")) {
            assertFalse(stage, proximityWizardHasLocalStepAction(stage))
        }
        assertTrue(proximityWizardHasLocalStepAction("intro"))
        assertTrue(proximityWizardHasLocalStepAction("review"))
        assertTrue(proximityWizardHasLocalStepAction("failed"))
        assertTrue(proximityWizardHasLocalStepAction("saved"))
    }

    @Test
    fun cueWireContractKeepsPreparationSeparateFromSensorCapture() {
        assertEquals(ProximityWizardCue.PREPARE, ProximityWizardCue.fromWire("prepare"))
        assertEquals(ProximityWizardCue.APPROACH, ProximityWizardCue.fromWire("approach"))
        assertEquals(ProximityWizardCue.HOLD, ProximityWizardCue.fromWire("hold"))
        assertEquals(ProximityWizardCue.MOVE_AWAY, ProximityWizardCue.fromWire("move_away"))
        assertEquals(ProximityWizardCue.WAIT_CLEAR, ProximityWizardCue.fromWire("wait_clear"))
        assertEquals(ProximityWizardCue.WAVE, ProximityWizardCue.fromWire("wave"))
        assertEquals(ProximityWizardCue.NONE, ProximityWizardCue.fromWire("unknown"))
    }
}
