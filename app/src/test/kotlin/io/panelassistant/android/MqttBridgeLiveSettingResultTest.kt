package io.panelassistant.android

import org.junit.Assert.assertEquals
import org.junit.Test

class MqttBridgeLiveSettingResultTest {
    @Test fun `successful execution maps to applied`() {
        assertEquals(
            LiveSettingApplyResult.APPLIED,
            liveSettingApplyResult(
                MqttCommandDispatcher.RunResult(
                    MqttCommandDispatcher.Admission.ACCEPTED,
                    MqttCommandDispatcher.Execution.SUCCEEDED,
                ),
            ),
        )
    }

    @Test fun `closed or rejected admission maps to deferred`() {
        listOf(
            MqttCommandDispatcher.Admission.CLOSED,
            MqttCommandDispatcher.Admission.REJECTED,
        ).forEach { admission ->
            assertEquals(
                LiveSettingApplyResult.DEFERRED,
                liveSettingApplyResult(
                    MqttCommandDispatcher.RunResult(
                        admission,
                        MqttCommandDispatcher.Execution.NOT_ADMITTED,
                    ),
                ),
            )
        }
    }

    @Test fun `admitted work still pending at the response deadline maps to deferred`() {
        assertEquals(
            LiveSettingApplyResult.DEFERRED,
            liveSettingApplyResult(
                MqttCommandDispatcher.RunResult(
                    MqttCommandDispatcher.Admission.ACCEPTED,
                    MqttCommandDispatcher.Execution.PENDING,
                ),
            ),
        )
    }

    @Test fun `admitted execution failure is not misclassified as deferred`() {
        assertEquals(
            LiveSettingApplyResult.FAILED,
            liveSettingApplyResult(
                MqttCommandDispatcher.RunResult(
                    MqttCommandDispatcher.Admission.ACCEPTED,
                    MqttCommandDispatcher.Execution.FAILED,
                ),
            ),
        )
    }

    @Test fun `admitted work cancelled after admission is a failure`() {
        assertEquals(
            LiveSettingApplyResult.FAILED,
            liveSettingApplyResult(
                MqttCommandDispatcher.RunResult(
                    MqttCommandDispatcher.Admission.ACCEPTED,
                    MqttCommandDispatcher.Execution.NOT_ADMITTED,
                ),
            ),
        )
    }
}
