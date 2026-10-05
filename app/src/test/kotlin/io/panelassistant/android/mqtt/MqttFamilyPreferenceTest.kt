package io.panelassistant.android.mqtt

import io.panelassistant.android.config.SettingsRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MqttFamilyPreferenceTest {
    @Test fun `ordinary default remains unlearned across process recreation`() {
        var storedBroker: String? = null
        var storedIpv4 = false
        fun preference() = MqttFamilyPreference(
            load = { identity -> storedIpv4.takeIf { storedBroker == identity } },
            persist = { identity, ipv4 ->
                storedBroker = identity
                storedIpv4 = ipv4
                true
            },
            clear = {
                storedBroker = null
                storedIpv4 = false
                true
            },
        )

        val firstProcess = preference()
        assertFalse(firstProcess.select("tcp://broker-a:1883"))
        assertFalse(firstProcess.awaitingProgress)
        assertNull(storedBroker)
        assertTrue(firstProcess.confirmConnectedRoute(
            "tcp://broker-a:1883",
            connectAttempt = 1L,
            selectedPreferIpv4 = false,
        ))
        assertNull(storedBroker)

        val replacementProcess = preference()
        assertFalse(replacementProcess.select("tcp://broker-a:1883"))
        assertFalse(replacementProcess.awaitingProgress)
    }

    @Test fun `learned IPv4 and IPv6 both receive a restored-route grace`() {
        var storedBroker: String? = null
        var storedIpv4 = false
        fun preference() = MqttFamilyPreference(
            load = { identity -> storedIpv4.takeIf { storedBroker == identity } },
            persist = { identity, ipv4 ->
                storedBroker = identity
                storedIpv4 = ipv4
                true
            },
            clear = {
                storedBroker = null
                storedIpv4 = false
                true
            },
        )

        val firstProcess = preference()
        assertEquals(
            MqttFamilyPreference.StageResult(preferIpv4 = true, durable = true, changed = true),
            firstProcess.stageAlternate("tcp://broker-a:1883", baselineConnectAttempt = 1L),
        )

        val restoredIpv4 = preference()
        assertTrue(restoredIpv4.select("tcp://broker-a:1883"))
        assertTrue(restoredIpv4.awaitingProgress)
        restoredIpv4.markBrokerProgress()
        assertFalse(restoredIpv4.awaitingProgress)

        assertEquals(
            MqttFamilyPreference.StageResult(preferIpv4 = false, durable = true, changed = true),
            restoredIpv4.stageAlternate("tcp://broker-a:1883", baselineConnectAttempt = 2L),
        )
        val restoredIpv6 = preference()
        assertFalse(restoredIpv6.select("tcp://broker-a:1883"))
        assertTrue(restoredIpv6.awaitingProgress)
    }

    @Test fun `selecting broker B invalidates learned broker A`() {
        var storedBroker: String? = "tcp://broker-a:1883"
        var storedIpv4 = true
        fun preference() = MqttFamilyPreference(
            load = { identity -> storedIpv4.takeIf { storedBroker == identity } },
            persist = { identity, ipv4 ->
                storedBroker = identity
                storedIpv4 = ipv4
                true
            },
            clear = {
                storedBroker = null
                storedIpv4 = false
                true
            },
        )

        val brokerB = preference()
        assertFalse(brokerB.select("tcp://broker-b:1883"))
        assertFalse(brokerB.awaitingProgress)
        assertNull(storedBroker)

        val laterProcess = preference()
        assertFalse(laterProcess.select("tcp://broker-a:1883"))
        assertFalse(laterProcess.awaitingProgress)
    }

    @Test fun `failed persistence is surfaced and cannot claim survival in a new process`() {
        var storedBroker: String? = null
        var storedIpv4 = false
        val firstProcess = MqttFamilyPreference(
            load = { identity -> storedIpv4.takeIf { storedBroker == identity } },
            persist = { _, _ -> false },
            clear = { true },
        )
        assertFalse(firstProcess.select("tcp://broker-a:1883"))
        assertEquals(
            MqttFamilyPreference.StageResult(preferIpv4 = true, durable = false, changed = true),
            firstProcess.stageAlternate("tcp://broker-a:1883", baselineConnectAttempt = 1L),
        )
        assertTrue(firstProcess.preferIpv4)

        val replacementProcess = MqttFamilyPreference(
            load = { identity -> storedIpv4.takeIf { storedBroker == identity } },
            persist = { _, _ -> true },
            clear = { true },
        )
        assertFalse(replacementProcess.select("tcp://broker-a:1883"))
    }

    @Test fun `rejected owner and automatic reconnect retain one staged family until fresh client start`() {
        var persisted = 0
        val preference = MqttFamilyPreference(
            load = { null },
            persist = { _, _ -> persisted++; true },
            clear = { true },
        )

        assertEquals(
            MqttFamilyPreference.StageResult(preferIpv4 = true, durable = true, changed = true),
            preference.stageAlternate("tcp://broker-a:1883", baselineConnectAttempt = 7L),
        )
        assertEquals(
            MqttFamilyPreference.StageResult(preferIpv4 = true, durable = true, changed = false),
            preference.stageAlternate("tcp://broker-a:1883", baselineConnectAttempt = 7L),
        )
        assertTrue(preference.preferIpv4)
        assertEquals(1, persisted)

        assertEquals(
            MqttFamilyPreference.StageResult(preferIpv4 = false, durable = true, changed = true),
            preference.stageAlternate("tcp://broker-a:1883", baselineConnectAttempt = 8L),
        )
        assertEquals(2, persisted)
    }

    @Test fun `failed durable write retries the same family before owner admission`() {
        var storedBroker: String? = null
        var storedIpv4 = false
        var attempts = 0
        val preference = MqttFamilyPreference(
            load = { identity -> storedIpv4.takeIf { storedBroker == identity } },
            persist = { identity, ipv4 ->
                attempts++
                if (attempts < 2) false else {
                    storedBroker = identity
                    storedIpv4 = ipv4
                    true
                }
            },
            clear = { true },
        )

        assertEquals(
            MqttFamilyPreference.StageResult(preferIpv4 = true, durable = false, changed = true),
            preference.stageAlternate("tcp://broker-a:1883", baselineConnectAttempt = 7L),
        )
        assertEquals(
            MqttFamilyPreference.StageResult(preferIpv4 = true, durable = true, changed = false),
            preference.stageAlternate("tcp://broker-a:1883", baselineConnectAttempt = 7L),
        )
        assertTrue(preference.preferIpv4)
        assertEquals(2, attempts)

        val replacement = MqttFamilyPreference(
            load = { identity -> storedIpv4.takeIf { storedBroker == identity } },
            persist = { _, _ -> true },
            clear = { true },
        )
        assertTrue(replacement.select("tcp://broker-a:1883"))
    }

    @Test fun `recovered current attempt rolls unused staged family back durably`() {
        var storedBroker: String? = null
        var storedIpv4 = false
        val preference = MqttFamilyPreference(
            load = { identity -> storedIpv4.takeIf { storedBroker == identity } },
            persist = { identity, ipv4 ->
                storedBroker = identity
                storedIpv4 = ipv4
                true
            },
            clear = { true },
        )

        assertFalse(preference.selectForConnect("tcp://broker-a:1883", connectAttempt = 7L))
        assertTrue(preference.stageAlternate("tcp://broker-a:1883", 7L).preferIpv4)
        assertTrue(preference.cancelStaged("tcp://broker-a:1883", 7L))
        assertFalse(preference.preferIpv4)
        assertEquals(false, storedIpv4)
    }

    @Test fun `newer fresh client consumes staged family and old ticket cannot roll it back`() {
        var storedIpv4 = false
        val preference = MqttFamilyPreference(
            load = { null },
            persist = { _, ipv4 -> storedIpv4 = ipv4; true },
            clear = { true },
        )

        assertFalse(preference.selectForConnect("tcp://broker-a:1883", connectAttempt = 7L))
        assertTrue(preference.stageAlternate("tcp://broker-a:1883", 7L).preferIpv4)
        assertTrue(preference.selectForConnect("tcp://broker-a:1883", connectAttempt = 8L))
        assertTrue(preference.cancelStaged("tcp://broker-a:1883", 7L))
        assertTrue(preference.preferIpv4)
        assertTrue(storedIpv4)
    }

    @Test fun `old session readiness restores actual route when owner callback never entered`() {
        var storedIpv4 = false
        val preference = MqttFamilyPreference(
            load = { null },
            persist = { _, ipv4 -> storedIpv4 = ipv4; true },
            clear = { true },
        )

        val selectedByAttempt = preference.selectForConnect("tcp://broker-a:1883", connectAttempt = 7L)
        assertFalse(selectedByAttempt)
        assertTrue(preference.stageAlternate("tcp://broker-a:1883", 7L).preferIpv4)
        // Models owner saturation: reconnect(ticket) never ran, but Hive auto-reconnected the old route.
        assertTrue(preference.confirmConnectedRoute(
            "tcp://broker-a:1883",
            connectAttempt = 7L,
            selectedPreferIpv4 = selectedByAttempt,
        ))

        assertFalse(preference.preferIpv4)
        assertFalse(storedIpv4)
    }

    @Test fun `external fresh start confirms a failed staged write only at readiness`() {
        var attempts = 0
        var storedIpv4 = false
        val preference = MqttFamilyPreference(
            load = { null },
            persist = { _, ipv4 ->
                attempts++
                if (attempts == 1) false else { storedIpv4 = ipv4; true }
            },
            clear = { true },
        )

        assertFalse(preference.selectForConnect("tcp://broker-a:1883", connectAttempt = 7L))
        assertFalse(preference.stageAlternate("tcp://broker-a:1883", 7L).durable)
        val consumed = preference.selectForConnect("tcp://broker-a:1883", connectAttempt = 8L)
        assertTrue(consumed)
        assertTrue(preference.confirmConnectedRoute("tcp://broker-a:1883", 8L, consumed))
        assertTrue(storedIpv4)
        assertEquals(2, attempts)
    }

    @Test fun `same-client family failover is learned only when application readiness confirms it`() {
        var storedIpv4 = false
        val preference = MqttFamilyPreference(
            load = { null },
            persist = { _, ipv4 -> storedIpv4 = ipv4; true },
            clear = { true },
        )

        assertFalse(preference.selectForConnect("tcp://broker-a:1883", connectAttempt = 7L))
        assertTrue(
            preference.confirmConnectedRoute(
                "tcp://broker-a:1883",
                connectAttempt = 7L,
                selectedPreferIpv4 = true,
            ),
        )
        assertTrue(preference.preferIpv4)
        assertTrue(storedIpv4)
    }

    @Test fun `failed stale-broker invalidation is surfaced`() {
        var failures = 0
        val preference = MqttFamilyPreference(
            load = { null },
            persist = { _, _ -> true },
            clear = { false },
            onClearFailure = { failures++ },
        )

        assertFalse(preference.select("tcp://broker-b:1883"))
        assertEquals(1, failures)
    }

    @Test fun `broker identity canonicalises equivalent schemes without credentials`() {
        assertEquals("tcp://broker.example:1883", mqttFamilyBrokerIdentity("MQTT://Broker.Example"))
        assertEquals("tcp://broker.example:1883", mqttFamilyBrokerIdentity("tcp://broker.example:1883/"))
        assertEquals("tls://broker.example:8883", mqttFamilyBrokerIdentity("ssl://Broker.Example"))
        assertEquals("tls://broker.example:1884", mqttFamilyBrokerIdentity("mqtts://broker.example:1884"))
        assertEquals("tcp://[2001:db8::1]:1883", mqttFamilyBrokerIdentity("tcp://[2001:DB8::1]"))
        assertNull(mqttFamilyBrokerIdentity("https://broker.example"))
        // Equivalent TLS schemes with the default port collapse to one identity (absorbed from the
        // former reconfigure-offline broker-identity helper, now converged onto this one canonicalizer).
        assertEquals(
            mqttFamilyBrokerIdentity("ssl://broker:8883"),
            mqttFamilyBrokerIdentity("mqtts://BROKER"),
        )
    }

    @Test fun `device-local family tuple is absent from user configuration registry`() {
        val keys = SettingsRegistry.SPECS.mapTo(mutableSetOf()) { it.key }
        assertFalse("device_local_mqtt_family_broker" in keys)
        assertFalse("device_local_mqtt_family_ipv4" in keys)
        assertFalse("device_local_mqtt_announcement_boundary_consumed" in keys)
    }
}
