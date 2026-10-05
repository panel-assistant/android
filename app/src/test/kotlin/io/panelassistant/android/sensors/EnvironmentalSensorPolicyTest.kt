package io.panelassistant.android.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvironmentalSensorPolicyTest {
    @Test fun cht8305SensorsAreActivatedWithoutPublishingLegacyValues() {
        assertEquals(
            EnvironmentalSensorUse.ACTIVATE_ONLY,
            environmentalSensorUse(hasCht8305 = true, sensorPresent = true),
        )
        assertFalse(environmentalSensorPublishes(EnvironmentalSensorUse.ACTIVATE_ONLY))
        assertTrue(environmentalSensorPublishes(EnvironmentalSensorUse.PUBLISH))
    }

    @Test fun helperClimateIsRenderedTruthfullyWhileLegacyAndroidValuesStaySuppressed() {
        val json = environmentalEndpointJson(
            EnvironmentalSensorUse.ACTIVATE_ONLY,
            androidValue = 99f,
            androidAgeSeconds = 1L,
            helperValue = 23.84,
            activationState = ActivationRegistrationState.REGISTERED,
        )

        assertEquals(
            "\"present\":true,\"value\":23.84,\"source\":\"helper\",\"activation\":\"registered\"",
            json,
        )
        assertFalse("frozen Android value must not leak into the endpoint", json.contains("99"))
    }

    @Test fun helperUnavailabilityIsExplicitAndNeverFallsBackToFrozenAndroidData() {
        val json = environmentalEndpointJson(
            EnvironmentalSensorUse.ACTIVATE_ONLY,
            androidValue = 99f,
            androidAgeSeconds = 1L,
            helperValue = null,
            activationState = ActivationRegistrationState.RETRYING,
        )

        assertEquals(
            "\"present\":true,\"value\":null,\"source\":\"helper\",\"activation\":\"retrying\"",
            json,
        )
    }

    @Test fun standardEnvironmentalSensorsRemainPublished() {
        assertEquals(
            EnvironmentalSensorUse.PUBLISH,
            environmentalSensorUse(hasCht8305 = false, sensorPresent = true),
        )
    }

    @Test fun absentEnvironmentalSensorsRemainAbsent() {
        listOf(false, true).forEach { hasCht8305 ->
            assertEquals(
                EnvironmentalSensorUse.ABSENT,
                environmentalSensorUse(hasCht8305 = hasCht8305, sensorPresent = false),
            )
        }
    }
}
