package io.panelassistant.android.sensors

import android.hardware.Sensor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProximitySourcePolicyTest {
    @Test fun onlyDeclaredContinuousHalMayWarmAnUnfingerprintedLegacySeed() {
        val continuousHal = proximitySourcePolicy(
            acquisition = ProximityAcquisition.ANDROID_HAL,
            halReportingMode = Sensor.REPORTING_MODE_CONTINUOUS,
        )
        val claimedOnChangeHal = proximitySourcePolicy(
            acquisition = ProximityAcquisition.ANDROID_HAL,
            halReportingMode = Sensor.REPORTING_MODE_ON_CHANGE,
        )

        assertFalse(continuousHal.sparseLearning)
        assertFalse(continuousHal.onChangeHalLiveness)
        assertTrue(continuousHal.legacySeedEligible)
        assertFalse(claimedOnChangeHal.sparseLearning)
        assertTrue(claimedOnChangeHal.onChangeHalLiveness)
        assertFalse(claimedOnChangeHal.legacySeedEligible)

        listOf(
            Sensor.REPORTING_MODE_ONE_SHOT,
            Sensor.REPORTING_MODE_SPECIAL_TRIGGER,
            null,
        ).forEach { reportingMode ->
            val policy = proximitySourcePolicy(ProximityAcquisition.ANDROID_HAL, reportingMode)
            assertEquals(false, policy.sparseLearning)
            assertEquals(false, policy.onChangeHalLiveness)
            assertEquals(false, policy.legacySeedEligible)
        }
    }

    @Test fun gpioHelperAloneUsesSparsePolicyAndNeverHalLiveness() {
        listOf(
            Sensor.REPORTING_MODE_CONTINUOUS,
            Sensor.REPORTING_MODE_ON_CHANGE,
            Sensor.REPORTING_MODE_ONE_SHOT,
            Sensor.REPORTING_MODE_SPECIAL_TRIGGER,
            null,
        ).forEach { reportingMode ->
            val gpio = proximitySourcePolicy(ProximityAcquisition.GPIO, reportingMode)
            assertTrue(gpio.sparseLearning)
            assertFalse(gpio.onChangeHalLiveness)
            assertFalse(gpio.legacySeedEligible)
        }
    }

    @Test fun vi530xSelectionOwnsPolicyAndNeverRunsHalRecovery() {
        val acquisition = proximityAcquisition(
            hasVi530x = true,
            proximityGpio = 17,
            hasHal = true,
        )
        val policy = proximitySourcePolicy(acquisition, Sensor.REPORTING_MODE_ON_CHANGE)

        assertEquals(ProximityAcquisition.VI530X, acquisition)
        assertFalse(policy.sparseLearning)
        assertFalse(policy.onChangeHalLiveness)
        assertFalse(policy.legacySeedEligible)
        assertFalse(
            proximityNeedsHalLivenessProbe(
                acquisition = acquisition,
                onChangeHalLiveness = policy.onChangeHalLiveness,
                cadenceClassified = true,
                continuousCadenceConfirmed = false,
            ),
        )
    }

    @Test fun reportingSparsityRequiresGpioOrObservedNonContinuousCadence() {
        assertTrue(
            proximityReportingSparse(
                sparseLearning = true,
                cadenceClassified = true,
                continuousCadenceConfirmed = false,
            ),
        )
        assertFalse(
            proximityReportingSparse(
                sparseLearning = false,
                cadenceClassified = false,
                continuousCadenceConfirmed = false,
            ),
        )
        assertFalse(
            proximityReportingSparse(
                sparseLearning = false,
                cadenceClassified = true,
                continuousCadenceConfirmed = true,
            ),
        )
        assertTrue(
            proximityReportingSparse(
                sparseLearning = false,
                cadenceClassified = true,
                continuousCadenceConfirmed = false,
            ),
        )
    }

    @Test fun cadenceWindowRevokesSparseAdmissionWhenDenseDeliveryResumes() {
        var classified = false
        var continuous = false
        var samples = 0

        fun observe(): Boolean {
            val admittedSparse = proximityReportingSparse(
                sparseLearning = false,
                cadenceClassified = classified,
                continuousCadenceConfirmed = continuous,
            )
            if (!continuous) {
                if (classified) {
                    classified = false
                    samples = 1
                } else {
                    samples++
                }
            }
            return admittedSparse
        }

        fun classifyWindow() {
            classified = true
            continuous = proximityCadenceWindowIsContinuous(samples)
            samples = 0
        }

        // A process starts capped. One initial callback admits sparse reporting after the window.
        assertFalse(observe())
        classifyWindow()
        assertFalse(continuous)
        assertTrue(proximityReportingSparse(false, classified, continuous))

        // Preserve the first held edge. A normal near/far pair stays sparse and valid silence follows.
        assertTrue(observe())
        assertFalse(observe())
        classifyWindow()
        assertFalse(continuous)
        assertTrue(proximityReportingSparse(false, classified, continuous))

        // A resumed dense burst is capped after its first edge and then permanently promoted.
        assertTrue(observe())
        assertFalse(observe())
        assertFalse(observe())
        assertFalse(observe())
        classifyWindow()
        assertTrue(continuous)
        assertFalse(proximityReportingSparse(false, classified, continuous))
        assertFalse(observe())

        // A new process does not inherit the prior empirical sparse admission.
        classified = false
        continuous = false
        samples = 0
        assertFalse(observe())
    }

    @Test fun oneSparseFinalEdgeIsReadmittedAfterEachQuietWindow() {
        var classified = true
        var continuous = false
        var samples = 0

        assertTrue(proximityReportingSparse(false, classified, continuous))
        classified = false
        samples = 1
        continuous = proximityCadenceWindowIsContinuous(samples)
        classified = true

        assertFalse(continuous)
        assertTrue(proximityReportingSparse(false, classified, continuous))
    }

    @Test fun empiricallyContinuousHalDoesNotKeepUsingOnChangeLivenessProbes() {
        assertFalse(
            proximityNeedsHalLivenessProbe(
                acquisition = ProximityAcquisition.ANDROID_HAL,
                onChangeHalLiveness = true,
                cadenceClassified = true,
                continuousCadenceConfirmed = true,
            ),
        )
    }

    @Test fun staleDenseHalReentersImmediateLivenessRecovery() {
        val recovery = proximityCadenceAfterStale()

        assertTrue(recovery.cadenceClassified)
        assertFalse(recovery.continuousCadenceConfirmed)
        assertEquals(0, recovery.sampleCount)
        assertTrue(
            proximityNeedsHalLivenessProbe(
                acquisition = ProximityAcquisition.ANDROID_HAL,
                onChangeHalLiveness = true,
                cadenceClassified = recovery.cadenceClassified,
                continuousCadenceConfirmed = recovery.continuousCadenceConfirmed,
            ),
        )
    }

    @Test fun quietHalStillUsesLivenessProbes() {
        assertTrue(
            proximityNeedsHalLivenessProbe(
                acquisition = ProximityAcquisition.ANDROID_HAL,
                onChangeHalLiveness = true,
                cadenceClassified = false,
                continuousCadenceConfirmed = false,
            ),
        )
        assertTrue(
            proximityNeedsHalLivenessProbe(
                acquisition = ProximityAcquisition.ANDROID_HAL,
                onChangeHalLiveness = false,
                cadenceClassified = true,
                continuousCadenceConfirmed = false,
            ),
        )
    }

    @Test fun gpioSourceNeverUsesHalLivenessProbes() {
        assertFalse(
            proximityNeedsHalLivenessProbe(
                acquisition = ProximityAcquisition.GPIO,
                onChangeHalLiveness = true,
                cadenceClassified = true,
                continuousCadenceConfirmed = false,
            ),
        )
    }
}
