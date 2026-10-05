package io.panelassistant.android.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupBannerTest {
    @Test fun connectedAndConfigured_noNeeds() {
        assertTrue(SetupBanner.needs("mqtt.example · connected", brokerConfigured = true).isEmpty())
    }

    /** Regression: a CONFIGURED broker that is merely mid-(re)connect must NOT be reported as missing. */
    @Test fun configuredButConnecting_noBrokerNeed() {
        assertTrue(SetupBanner.needs("host · connecting…", brokerConfigured = true).isEmpty())
    }

    @Test fun configuredButConnecting_reportsVerificationProgress() {
        val progress = SetupBanner.progress("host · connecting…", brokerConfigured = true, dashboardStepPending = true)
        assertEquals(
            "MQTT settings saved — verifying the broker connection. This can take a short while after saving. The dashboard setup step appears next.",
            progress,
        )
    }

    @Test fun authRetrying_isVerificationProgressNotFailedSetup() {
        assertTrue(SetupBanner.needs("host · auth retrying…", brokerConfigured = true).isEmpty())
        assertEquals(
            "MQTT settings saved — verifying the broker connection. This can take a short while after saving. The dashboard setup step appears next.",
            SetupBanner.progress("host · auth retrying…", brokerConfigured = true, dashboardStepPending = true),
        )
    }

    @Test fun aLiveConnectedStateClearsTheProgressBannerWhateverTheSnapshotSays() {
        // The status string is a stale-while-revalidate snapshot; the bridge flips announcing→connected on
        // the discovery PUBACK, but the snapshot can lag and keep narrating a finished publish. The live
        // canonical state is authoritative for CLEARING.
        assertEquals(
            null,
            SetupBanner.progress(
                "host · connected, announcing…", brokerConfigured = true,
                dashboardStepPending = true, liveState = "connected",
            ),
        )
        assertEquals(
            null,
            SetupBanner.progress(
                "host · connecting…", brokerConfigured = true,
                dashboardStepPending = false, liveState = "connected",
            ),
        )
        // A live state that is genuinely still announcing keeps the banner.
        assertEquals(
            true,
            SetupBanner.progress(
                "host · connected, announcing…", brokerConfigured = true,
                dashboardStepPending = false, liveState = "announcing",
            )?.contains("publishing Home Assistant discovery"),
        )
        // And a caller with no live reading (blank) trusts the snapshot exactly as before.
        assertEquals(
            true,
            SetupBanner.progress("host · connected, announcing…", brokerConfigured = true)
                ?.contains("publishing Home Assistant discovery"),
        )
    }

    @Test fun connectedAnnouncing_reportsDiscoveryProgress() {
        assertTrue(SetupBanner.needs("host · connected, announcing…", brokerConfigured = true).isEmpty())
        assertEquals(
            "MQTT connected — publishing Home Assistant discovery. The dashboard setup step appears next.",
            SetupBanner.progress("host · connected, announcing…", brokerConfigured = true, dashboardStepPending = true),
        )
    }

    /**
     * Regression for the reported config-change race symptom: while the bridge rebuilds, its status is
     * transient/blank ("disabled"); with a broker CONFIGURED that must not surface "needs the MQTT broker".
     */
    @Test fun configuredButBlankStatus_doesNotClaimMissingBroker() {
        val needs = SetupBanner.needs("disabled", brokerConfigured = true)
        assertFalse("must not falsely claim the broker is missing", needs.contains("the MQTT broker"))
        assertTrue(needs.isEmpty())
    }

    @Test fun unconfiguredAndBlank_needsBroker() {
        assertEquals(listOf("MQTT configuration"), SetupBanner.needs("disabled", brokerConfigured = false))
    }

    @Test fun authRejected_needsCredentials() {
        assertEquals(
            listOf("valid MQTT credentials (the broker rejected them)"),
            SetupBanner.needs(
                "host · reachable, auth rejected — check username/password",
                brokerConfigured = true,
                mqttUserConfigured = true,
            ),
        )
    }

    @Test fun authRejectedWithoutUserConfigured_needsCredentialsWithoutBrokerDiagnosis() {
        assertEquals(
            listOf("valid MQTT credentials"),
            SetupBanner.needs(
                "host · reachable, auth rejected — check username/password",
                brokerConfigured = true,
                mqttUserConfigured = false,
            ),
        )
    }

    @Test fun unreachable_needsReachableBroker() {
        assertEquals(
            listOf("a reachable MQTT broker"),
            SetupBanner.needs("host · unreachable", brokerConfigured = true),
        )
    }

    @Test fun invalidBrokerUrl_needsValidUrl() {
        assertEquals(
            listOf("a valid MQTT broker URL"),
            SetupBanner.needs("host · invalid or unsupported broker URL", brokerConfigured = true),
        )
    }

    @Test fun generatedPanelIdentity_isNotListedAsMissing() {
        assertFalse(SetupBanner.needs("host · connected", brokerConfigured = true).contains("a panel id"))
    }

    /**
     * Reported from a configured panel straight after upgrading to versionCode 464: the Dashboard tab
     * said "MQTT connected — publishing Home Assistant discovery. The dashboard setup step appears next." on a
     * panel whose dashboard was already configured and persisted through the upgrade.
     *
     * Every MQTT (re)connect re-announces discovery, including the one after an ordinary app update, so the
     * promise fired for a step that did not exist and would never arrive. The state is still worth explaining
     * — MQTT genuinely is not connected yet — so only the promise is dropped.
     */
    @Test fun aConfiguredPanelIsNeverPromisedADashboardStepItHasAlreadyDone() {
        listOf("host · connected, announcing…", "host · connecting…", "host · auth retrying…").forEach { status ->
            val progress = SetupBanner.progress(status, brokerConfigured = true, dashboardStepPending = false)
            assertNotNull("the MQTT state itself must still be explained: $status", progress)
            assertFalse(
                "must not promise a dashboard step on a configured panel: $status",
                progress!!.contains("dashboard setup step"),
            )
        }
        assertEquals(
            "MQTT connected — publishing Home Assistant discovery.",
            SetupBanner.progress("host · connected, announcing…", brokerConfigured = true, dashboardStepPending = false),
        )
        // Default is the safe one: callers that do not know must not invent the promise.
        assertEquals(
            SetupBanner.progress("host · connected, announcing…", brokerConfigured = true, dashboardStepPending = false),
            SetupBanner.progress("host · connected, announcing…", brokerConfigured = true),
        )
    }

    /** Panel Assistant carries a natively run panel, so no broker is not a setup need. */
    @Test fun panelAssistantNative_withoutBroker_needsNothing() {
        assertTrue(SetupBanner.needs("disabled", brokerConfigured = false, mqttSetupRequired = false).isEmpty())
        assertEquals(listOf("MQTT configuration"), SetupBanner.needs("disabled", brokerConfigured = false))
    }
    @Test fun migratedPanelIgnoresSavedBrokerFailuresAndProgress() {
        for (status in listOf("host · unreachable", "host · auth rejected", "host · connecting…", "host · connected, announcing…")) {
            assertTrue(SetupBanner.needs(status, brokerConfigured = true, mqttSetupRequired = false).isEmpty())
            assertEquals(null, SetupBanner.progress(status, brokerConfigured = true, mqttSetupRequired = false))
        }
    }
}
