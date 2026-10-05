package io.panelassistant.android.control

import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbControllerPolicyTest {
    private val emptyTcp = "  sl  local_address rem_address   st tx_queue rx_queue\n"

    @Test fun `captured unavailable root route preserves direct truth without a cross-check`() {
        assertEquals(
            true,
            networkAdbActiveState(
                directRead = { property ->
                    if (property == SERVICE_ADB_TCP_PORT_PROPERTY) "5555" else null
                },
                rootRead = null,
            ),
        )
        assertNull(networkAdbActiveState(directRead = { "" }, rootRead = null))
    }

    @Test fun `unprivileged property read detects externally active network adb without root`() {
        var rootCalled = false

        val active = networkAdbActiveState(
            directRead = { property ->
                when (property) {
                    SERVICE_ADB_TCP_PORT_PROPERTY -> "43210\n"
                    SERVICE_ADB_TLS_PORT_PROPERTY, PERSIST_ADB_TLS_SERVER_ENABLE_PROPERTY -> "0"
                    else -> null
                }
            },
            rootRead = { rootCalled = true; null },
        )

        assertEquals(true, active)
        assertFalse(rootCalled)
    }

    @Test fun `empty unprivileged property read falls back to root`() {
        val rootReads = mutableListOf<String>()

        val active = networkAdbActiveState(
            directRead = { property ->
                when (property) {
                    SERVICE_ADB_TCP_PORT_PROPERTY -> "\n"
                    SERVICE_ADB_TLS_PORT_PROPERTY, PERSIST_ADB_TLS_SERVER_ENABLE_PROPERTY -> "0"
                    else -> null
                }
            },
            rootRead = { property ->
                rootReads += property
                if (property == SERVICE_ADB_TCP_PORT_PROPERTY) "5037" else null
            },
        )

        assertEquals(true, active)
        assertEquals(
            listOf(SERVICE_ADB_LISTEN_ADDRS_PROPERTY, SERVICE_ADB_TCP_PORT_PROPERTY),
            rootReads,
        )
    }

    @Test fun `persistent classic TCP port is active even when service port is disabled`() {
        assertEquals(
            true,
            networkAdbActiveState(
                directRead = { property ->
                    when (property) {
                        SERVICE_ADB_TCP_PORT_PROPERTY -> "-1"
                        PERSIST_ADB_TCP_PORT_PROPERTY -> "5555"
                        else -> null
                    }
                },
                rootRead = { null },
            ),
        )
    }

    @Test fun `root-only persistent TCP port is active`() {
        val rootReads = mutableListOf<String>()
        assertEquals(
            true,
            networkAdbActiveState(
                directRead = { "" },
                rootRead = { property ->
                    rootReads += property
                    when (property) {
                        SERVICE_ADB_LISTEN_ADDRS_PROPERTY -> ""
                        SERVICE_ADB_TCP_PORT_PROPERTY -> "-1"
                        PERSIST_ADB_TCP_PORT_PROPERTY -> "43210"
                        else -> null
                    }
                },
            ),
        )
        assertEquals(
            listOf(
                SERVICE_ADB_LISTEN_ADDRS_PROPERTY,
                SERVICE_ADB_TCP_PORT_PROPERTY,
                PERSIST_ADB_TCP_PORT_PROPERTY,
            ),
            rootReads,
        )
    }

    @Test fun `explicit ADB listen addresses are active`() {
        listOf(
            "tcp:5555",
            "tcp:localhost:5555",
            "tcp:5555,vsock:5555",
            "malformed-fails-closed",
        ).forEach { addresses ->
            assertEquals(
                true,
                networkAdbActiveState(
                    directRead = { property ->
                        if (property == SERVICE_ADB_LISTEN_ADDRS_PROPERTY) addresses else null
                    },
                    rootRead = { null },
                ),
            )
        }
    }

    @Test fun `arbitrary positive classic TCP ports are active`() {
        listOf("1", "5037", "43210", "65535").forEach { port ->
            assertEquals(
                true,
                networkAdbActiveState(
                    directRead = { property ->
                        when (property) {
                            SERVICE_ADB_TCP_PORT_PROPERTY -> port
                            SERVICE_ADB_TLS_PORT_PROPERTY, PERSIST_ADB_TLS_SERVER_ENABLE_PROPERTY -> "0"
                            else -> null
                        }
                    },
                    rootRead = { null },
                ),
            )
        }
    }

    @Test fun `wireless debugging TLS port is active`() {
        assertEquals(
            true,
            networkAdbActiveState(
                directRead = { property ->
                    when (property) {
                        SERVICE_ADB_TCP_PORT_PROPERTY -> "-1"
                        SERVICE_ADB_TLS_PORT_PROPERTY -> "37123"
                        PERSIST_ADB_TLS_SERVER_ENABLE_PROPERTY -> "0"
                        else -> null
                    }
                },
                rootRead = { null },
            ),
        )
    }

    @Test fun `wireless debugging enabled intent is active before its TLS port is published`() {
        assertEquals(
            true,
            networkAdbActiveState(
                directRead = { property ->
                    when (property) {
                        SERVICE_ADB_TCP_PORT_PROPERTY -> "-1"
                        SERVICE_ADB_TLS_PORT_PROPERTY -> "0"
                        PERSIST_ADB_TLS_SERVER_ENABLE_PROPERTY -> "1"
                        else -> null
                    }
                },
                rootRead = { null },
            ),
        )
    }

    @Test fun `disabled port values and authoritative empty listen addresses are inactive`() {
        val rootReads = mutableListOf<String>()

        val active = networkAdbActiveState(
            directRead = { property ->
                when (property) {
                    SERVICE_ADB_LISTEN_ADDRS_PROPERTY -> ""
                    SERVICE_ADB_TCP_PORT_PROPERTY -> "-1"
                    PERSIST_ADB_TCP_PORT_PROPERTY -> "-1"
                    SERVICE_ADB_TLS_PORT_PROPERTY -> "0"
                    PERSIST_ADB_TLS_SERVER_ENABLE_PROPERTY -> "0"
                    else -> null
                }
            },
            rootRead = { property -> rootReads += property; "" },
        )

        assertEquals(false, active)
        assertEquals(listOf(SERVICE_ADB_LISTEN_ADDRS_PROPERTY), rootReads)
    }

    @Test fun `empty root values authoritatively establish disabled properties`() {
        val rootReads = mutableListOf<String>()

        val active = networkAdbActiveState(
            directRead = { "" },
            rootRead = { property -> rootReads += property; "" },
        )

        assertEquals(false, active)
        assertEquals(
            listOf(
                SERVICE_ADB_LISTEN_ADDRS_PROPERTY,
                SERVICE_ADB_TCP_PORT_PROPERTY,
                PERSIST_ADB_TCP_PORT_PROPERTY,
                SERVICE_ADB_TLS_PORT_PROPERTY,
                PERSIST_ADB_TLS_SERVER_ENABLE_PROPERTY,
            ),
            rootReads,
        )
    }

    @Test fun `malformed and overflow ports fail closed`() {
        listOf("not-a-port", "65536", "999999999999999999999999").forEach { value ->
            assertNull(
                networkAdbActiveState(
                    directRead = { property ->
                        when (property) {
                            SERVICE_ADB_LISTEN_ADDRS_PROPERTY -> ""
                            SERVICE_ADB_TCP_PORT_PROPERTY -> "-1"
                            PERSIST_ADB_TCP_PORT_PROPERTY -> value
                            SERVICE_ADB_TLS_PORT_PROPERTY -> "0"
                            PERSIST_ADB_TLS_SERVER_ENABLE_PROPERTY -> "0"
                            else -> null
                        }
                    },
                    rootRead = { property ->
                        if (property == SERVICE_ADB_LISTEN_ADDRS_PROPERTY) "" else null
                    },
                ),
            )
        }
    }

    @Test fun `root property commands are fixed and reject unlisted input`() {
        listOf(
            SERVICE_ADB_LISTEN_ADDRS_PROPERTY,
            SERVICE_ADB_TCP_PORT_PROPERTY,
            PERSIST_ADB_TCP_PORT_PROPERTY,
            SERVICE_ADB_TLS_PORT_PROPERTY,
            PERSIST_ADB_TLS_SERVER_ENABLE_PROPERTY,
        ).forEach { property ->
            assertEquals("/system/bin/getprop $property", networkAdbRootReadCommand(property))
        }
        assertNull(networkAdbRootReadCommand("service.adb.tcp.port; id"))
        assertEquals("/system/bin/cat /proc/net/tcp", networkAdbRootListenerReadCommand("/proc/net/tcp"))
        assertEquals("/system/bin/cat /proc/net/tcp6", networkAdbRootListenerReadCommand("/proc/net/tcp6"))
        assertNull(networkAdbRootListenerReadCommand("/proc/net/tcp; id"))
    }

    @Test fun `classic adb listener is detected in IPv4 or IPv6 kernel inventory`() {
        val listen = emptyTcp + "   0: 00000000:15B3 00000000:0000 0A 00000000:00000000\n"
        assertEquals(true, parseTcpListenerInventory(listen))
        assertEquals(
            true,
            networkAdbListenerActiveState(
                directRead = { path -> if (path == "/proc/net/tcp6") listen else emptyTcp },
                rootRead = null,
            ),
        )
        val nearby = emptyTcp + "   0: 00000000:15B4 00000000:0000 0A 00000000:00000000\n"
        assertEquals(false, parseTcpListenerInventory(nearby))
    }

    @Test fun `reassert follows the live listener, not ha-paneld's own persisted property`() {
        // Vendor init (service.adb.tcp.port=0 at boot) or an adbd restart leaves persist.adb.tcp.port=5555
        // set while nothing listens; that is exactly when the persisted intent must bring TCP back.
        val noListener = networkAdbListenerActiveState(directRead = { emptyTcp }, rootRead = null)
        assertTrue(shouldReassertNetworkAdb(persisted = true, disablePending = false, rootAvailable = true, listening = noListener))
        // Already listening: restarting adbd would only drop live sessions.
        assertFalse(shouldReassertNetworkAdb(persisted = true, disablePending = false, rootAvailable = true, listening = true))
        // Unreadable listener: never restart adbd on a guess.
        assertFalse(shouldReassertNetworkAdb(persisted = true, disablePending = false, rootAvailable = true, listening = null))
        assertFalse(shouldReassertNetworkAdb(persisted = false, disablePending = false, rootAvailable = true, listening = false))
        assertFalse(shouldReassertNetworkAdb(persisted = true, disablePending = false, rootAvailable = false, listening = false))
    }

    @Test fun `diagnostics report whether a persisted network adb really listens`() {
        assertEquals("persistent (5555) · listening", networkAdbDiagnostic(persisted = true, active = true))
        assertTrue(networkAdbDiagnostic(persisted = true, active = false).startsWith("persistent (5555) · NOT listening"))
        assertEquals("persistent (5555) · listener unreadable", networkAdbDiagnostic(persisted = true, active = null))
        assertEquals("active (5555) · external — not persisted by ha-paneld", networkAdbDiagnostic(persisted = false, active = true))
        assertEquals("off", networkAdbDiagnostic(persisted = false, active = false))
        assertEquals("off", networkAdbDiagnostic(persisted = false, active = null))
    }

    @Test fun `listener proof fails closed on unreadable truncated or malformed inventory`() {
        assertNull(parseTcpListenerInventory(null))
        assertNull(parseTcpListenerInventory(""))
        assertNull(parseTcpListenerInventory(emptyTcp + "broken\n"))
        assertNull(networkAdbListenerActiveState(directRead = { null }, rootRead = { null }))
    }

    @Test fun `remote adb is inactive only when properties and both listener inventories are inactive`() {
        assertEquals(false, remoteAdbActiveState(propertyState = false, listenerState = false))
        assertEquals(true, remoteAdbActiveState(propertyState = false, listenerState = true))
        assertNull(remoteAdbActiveState(propertyState = false, listenerState = null))
        assertNull(remoteAdbActiveState(propertyState = null, listenerState = false))
    }

    @Test fun `settled inactive proof rejects delayed listener republish`() {
        var now = 0L
        var samples = 0
        val settled = proveSettledNetworkAdbInactive(
            sample = {
                samples++
                if (now >= 2_000L) true else false
            },
            monotonicMs = { now },
            waitMs = { delay -> now += delay; true },
        )

        assertFalse(settled)
        assertTrue(samples >= 2)
    }

    @Test fun `settled inactive proof requires repeated complete samples across stabilization`() {
        var now = 0L
        var samples = 0
        assertTrue(proveSettledNetworkAdbInactive(
            sample = { samples++; false },
            monotonicMs = { now },
            waitMs = { delay -> now += delay; true },
        ))
        assertEquals(5, samples)
        assertEquals(3_000L, now)
    }

    @Test fun `settled inactive proof fails on unknown sample and interrupted wait`() {
        var now = 0L
        assertFalse(proveSettledNetworkAdbInactive(
            sample = { null },
            monotonicMs = { now },
            waitMs = { delay -> now += delay; true },
        ))
        now = 0L
        assertFalse(proveSettledNetworkAdbInactive(
            sample = { false },
            monotonicMs = { now },
            waitMs = { false },
        ))
    }

    @Test fun `unknown property state fails closed`() {
        assertNull(networkAdbActiveState(directRead = { "" }, rootRead = { null }))
        assertNull(
            networkAdbActiveState(
                directRead = { property ->
                    when (property) {
                        SERVICE_ADB_TCP_PORT_PROPERTY -> "-1"
                        SERVICE_ADB_TLS_PORT_PROPERTY -> "not-a-port"
                        PERSIST_ADB_TLS_SERVER_ENABLE_PROPERTY -> "0"
                        else -> null
                    }
                },
                rootRead = { null },
            ),
        )
    }

    @Test fun `network adb transitions stop at the first failed privileged step`() {
        assertEquals(
            "setprop persist.adb.tcp.port 5555 && " +
                "setprop service.adb.tcp.port 5555 && " +
                "setprop ctl.restart adbd",
            networkAdbEnableCommand(),
        )
        assertEquals(
            "setprop service.adb.listen_addrs \"\" && " +
                "setprop persist.adb.tcp.port \"\" && " +
                "setprop service.adb.tcp.port \"\" && " +
                "setprop persist.adb.tls_server.enable 0 && " +
                "setprop service.adb.tls.port \"\" && " +
                "setprop ctl.restart adbd",
            networkAdbDisableCommand(),
        )
        assertFalse(networkAdbEnableCommand().contains(';'))
        assertFalse(networkAdbDisableCommand().contains(';'))
    }

    @Test fun `disable transition publishes marker before teardown and clears it last`() {
        val model = DisableTransitionModel()

        assertTrue(model.run())

        assertEquals(
            listOf("arm", "clear-properties", "restart-adbd", "settled-samples", "ownership-false", "clear-marker"),
            model.events,
        )
        assertFalse(model.owned)
        assertFalse(model.marker)
    }

    @Test fun `shared security transition gate permits ordered reentrant compositions`() {
        var entered = 0
        assertTrue(RemoteDebugSecurityTransitionGate.withLock {
            entered++
            RemoteDebugSecurityTransitionGate.withLock {
                entered++
                true
            }
        })
        assertEquals(2, entered)
    }

    @Test fun `security mutations advance epoch and only exact current replay can commit`() {
        val before = RemoteDebugSecurityTransitionGate.authorityEpoch()
        assertEquals(
            RemoteDebugAuthorityResult.Value("before"),
            RemoteDebugSecurityTransitionGate.withEpoch(before) { "before" },
        )

        RemoteDebugSecurityTransitionGate.mutate { Unit }
        val after = RemoteDebugSecurityTransitionGate.authorityEpoch()
        assertFalse(before == after)
        assertEquals(
            RemoteDebugAuthorityResult.Changed,
            RemoteDebugSecurityTransitionGate.withEpoch(before) { "stale" },
        )
        assertEquals(
            RemoteDebugAuthorityResult.Value("current"),
            RemoteDebugSecurityTransitionGate.withEpoch(after) { "current" },
        )
    }

    @Test fun `every process cut resumes disable without re-enabling TCP`() {
        ProcessCut.values().forEach { cut ->
            val model = DisableTransitionModel()

            assertFalse("first attempt must stop at $cut", model.run(cut))
            assertFalse(
                "startup must never re-enable at $cut",
                shouldReassertNetworkAdb(
                    persisted = model.owned,
                    disablePending = model.marker,
                    rootAvailable = true,
                    listening = false,
                ),
            )
            assertTrue("startup recovery must settle $cut", model.run())
            assertFalse(model.owned)
            assertFalse(model.marker)
            assertFalse(model.propertiesActive)
            assertFalse(model.listenerActive)
        }
    }

    @Test fun `corrupt marker remains a fail closed disable instruction`() {
        val directory = Files.createTempDirectory("network-adb-disable-marker").toFile()
        try {
            val file = directory.resolve("network-adb-disable.v1")
            val marker = NetworkAdbDisableTransitionMarker(file)

            assertFalse(marker.isPending())
            assertTrue("fresh marker publication must complete before teardown", marker.arm())
            assertTrue(marker.isPending())
            assertTrue(marker.clear())

            file.writeText("unexpected marker contents")
            assertTrue(marker.isPending())
            assertTrue(marker.arm())
            assertFalse(shouldReassertNetworkAdb(true, marker.isPending(), true, false))
            assertTrue(marker.clear())
            assertFalse(marker.isPending())

            assertTrue(file.mkdir())
            assertTrue(marker.isPending())
            assertTrue("a corrupt entry still permits OFF recovery", marker.arm())
            assertFalse(shouldReassertNetworkAdb(true, marker.isPending(), true, false))
            assertTrue("an empty corrupt entry is removable only by final cleanup", marker.clear())
            assertFalse(marker.isPending())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun `ownership commit failure retains marker after settled listener proof`() {
        var marker = false
        var owned = true

        assertFalse(completeNetworkAdbDisableTransition(
            owned = { owned },
            disablePending = { marker },
            armDisable = { marker = true; true },
            teardown = { true },
            inactiveReadback = { true },
            clearOwnership = { false },
            clearDisable = { marker = false; true },
        ))

        assertTrue(owned)
        assertTrue(marker)
    }

    @Test fun `Hardened commits only after settled disable ownership and marker cleanup`() {
        val events = mutableListOf<String>()
        var owned = true
        var marker = true

        val result = completeHardenedNetworkAdbAdmission(
            disableRequired = true,
            finishDisable = {
                events += "settled-samples"
                owned = false
                events += "ownership-false"
                marker = false
                events += "clear-marker"
                true
            },
            proveInactive = { error("completed disable already supplied the settled proof") },
            activeReadback = { error("completed disable needs no diagnostic sample") },
            ownershipEnabled = { owned },
            disableAbsentDurably = { !marker },
            commitHardened = { events += "commit-hardened"; true },
        )

        assertEquals(HardenedNetworkAdbAdmission.APPLIED, result)
        assertEquals(
            listOf("settled-samples", "ownership-false", "clear-marker", "commit-hardened"),
            events,
        )
    }

    @Test fun `Hardened commit cut leaves safe state and retries from fresh inactive proof`() {
        var owned = false
        var marker = false
        var commits = 0

        assertEquals(
            HardenedNetworkAdbAdmission.COMMIT_FAILED,
            completeHardenedNetworkAdbAdmission(
                disableRequired = true,
                finishDisable = { true },
                proveInactive = { error("disable supplied proof") },
                activeReadback = { null },
                ownershipEnabled = { owned },
                disableAbsentDurably = { !marker },
                commitHardened = { commits++; false },
            ),
        )
        assertFalse(owned)
        assertFalse(marker)
        assertEquals(
            HardenedNetworkAdbAdmission.APPLIED,
            completeHardenedNetworkAdbAdmission(
                disableRequired = false,
                finishDisable = { error("there is no pending disable") },
                proveInactive = { true },
                activeReadback = { null },
                ownershipEnabled = { owned },
                disableAbsentDurably = { !marker },
                commitHardened = { commits++; true },
            ),
        )
        assertEquals(2, commits)
    }

    @Test fun `one shot false sample cannot admit Hardened after unsettled proof`() {
        var committed = false
        assertEquals(
            HardenedNetworkAdbAdmission.UNVERIFIED,
            completeHardenedNetworkAdbAdmission(
                disableRequired = false,
                finishDisable = { error("there is no pending disable") },
                proveInactive = { false },
                activeReadback = { false },
                ownershipEnabled = { false },
                disableAbsentDurably = { true },
                commitHardened = { committed = true; true },
            ),
        )
        assertFalse(committed)
    }

    private enum class ProcessCut {
        BEFORE_PROPERTY_CLEARS,
        AFTER_PROPERTY_CLEARS,
        AFTER_ADBD_RESTART,
        AFTER_SETTLED_SAMPLES,
        AFTER_OWNERSHIP_COMMIT,
        AFTER_MARKER_REMOVAL,
    }

    private class DisableTransitionModel {
        var owned = true
        var marker = false
        var propertiesActive = true
        var listenerActive = true
        val events = mutableListOf<String>()

        fun run(cut: ProcessCut? = null): Boolean = completeNetworkAdbDisableTransition(
            owned = { owned },
            disablePending = { marker },
            armDisable = {
                marker = true
                events += "arm"
                true
            },
            teardown = teardown@{
                if (cut == ProcessCut.BEFORE_PROPERTY_CLEARS) return@teardown false
                propertiesActive = false
                events += "clear-properties"
                if (cut == ProcessCut.AFTER_PROPERTY_CLEARS) return@teardown false
                listenerActive = false
                events += "restart-adbd"
                cut != ProcessCut.AFTER_ADBD_RESTART
            },
            inactiveReadback = {
                events += "settled-samples"
                !propertiesActive && !listenerActive && cut != ProcessCut.AFTER_SETTLED_SAMPLES
            },
            clearOwnership = {
                owned = false
                events += "ownership-false"
                cut != ProcessCut.AFTER_OWNERSHIP_COMMIT
            },
            clearDisable = {
                marker = false
                events += "clear-marker"
                cut != ProcessCut.AFTER_MARKER_REMOVAL
            },
        )
    }
}
