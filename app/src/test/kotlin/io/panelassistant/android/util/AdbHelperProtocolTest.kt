package io.panelassistant.android.util

import android.content.Context
import android.content.ContextWrapper
import io.panelassistant.android.control.*
import io.panelassistant.android.platform.HelperAdbState
import io.panelassistant.android.testsupport.PanelRuntimeRig
import io.panelassistant.android.testsupport.scratchDirectory
import org.junit.Assert.*
import org.junit.Test

internal class AdbHelperProtocolTest : PanelRuntimeRig() {
    @Test fun admissionUsesTheCarryingConnectionAndRequiresMinorTen() {
        val wire = wire("PROPS=0 LISTEN=1\n")
        wire.afterVersion = { wire.version = "HELPER version=2.0.0 proto=2.0" }
        assertEquals(HelperAdbState(false, true), wire.client.adbState())
        assertNull(wire.client.adbState())
        assertEquals(listOf(Triple(1, "ADBSTATE", 17L)), wire.bytes)
        assertEquals(2, wire.closed)
        for (version in listOf("ERR", "HELPER version=1.9.0 proto=1.9", "HELPER version=1.10.0 proto=1.10 proto=1.10")) {
            val old = wire("PROPS=0 LISTEN=0\n").apply { this.version = version }
            assertNull(old.client.adbState()); assertTrue(old.bytes.isEmpty()); assertTrue(old.rawCommands.isEmpty())
        }
    }

    @Test fun parserRequiresExactlySeventeenBytesAndPreservesIndependentUnknownFields() {
        for ((raw, expected) in listOf(
            "PROPS=0 LISTEN=0\n" to HelperAdbState(false, false),
            "PROPS=1 LISTEN=?\n" to HelperAdbState(true, null),
            "PROPS=? LISTEN=1\n" to HelperAdbState(null, true),
            "PROPS=? LISTEN=?\n" to HelperAdbState(null, null),
        )) assertEquals(expected, wire(raw).client.adbState())
        for (raw in listOf(null, "ERR\n", "PROPS=0 LISTEN=0", "PROPS=0 LISTEN=0\n\n", "PROPS=2 LISTEN=0\n", "PROPS=0 LISTEN=-\n", "LISTEN=0 PROPS=0\n", "PROPS=0\tLISTEN=0\n", "PROPS=0 LISTEN=0\r", "PROPS=0 LISTEN=0\u0000")) {
            assertNull(raw, wire(raw).client.adbState())
        }
    }

    @Test fun controllerUsesOneFreshAggregateAndDirectPositiveWinsWithoutHelper() {
        val rig = rig(); val directory = scratchDirectory("adb-controller-helper").toFile()
        val root = FakeRootShell()
        val wire = wire("PROPS=0 LISTEN=0\n")
        var directProperty: String? = null
        var directListener: String? = null
        val controller = AdbController(rig.config, NetworkAdbDisableTransitionMarker(directory.resolve("off")), root,
            daemon = wire.client, directPropertyRead = { if (it == SERVICE_ADB_TCP_PORT_PROPERTY) directProperty else null }, directListenerRead = { directListener })
        try {
            assertEquals(false, controller.activeState()); assertEquals(1, wire.bytes.size)
            wire.replies["ADBSTATE"] = "PROPS=? LISTEN=0\n".toByteArray()
            assertNull(controller.activeState()); assertEquals(2, wire.bytes.size)
            directProperty = "5555"
            assertEquals(true, controller.activeState()); assertEquals(2, wire.bytes.size)
            directProperty = null
            directListener = "sl local_address rem_address st\n0: 00000000:15B3 00000000:0000 0A\n"
            assertEquals(true, controller.activeState()); assertEquals(2, wire.bytes.size)
            directListener = null
            repeat(3) { wire.missing = true; assertNull(controller.activeState()); assertNull(controller.listenerState()) }
            assertTrue(root.ran.isEmpty()); assertTrue(root.outputRan.isEmpty()); assertTrue(root.isolatedOutputRan.isEmpty()); assertEquals(0, root.availabilityChecks)
        } finally { directory.deleteRecursively(); rig.close() }
    }

    @Test fun databaseFreeProofAcquiresFiveFreshHelperObservationsAndRejectsRepublish() {
        val directory = scratchDirectory("adb-dbfree-proof").toFile()
        val context = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir() = directory
        }
        val wire = wire("PROPS=0 LISTEN=0\n")
        var now = 0L
        fun prove() = AdbController.proveMaintenanceRemoteDebugOff(context, wire.client,
            directPropertyRead = { null }, directListenerRead = { null }, managedOff = { false },
            monotonicMs = { now }, waitMs = { now += it; true })
        try {
            assertTrue(prove()); assertEquals(5, wire.bytes.size); assertEquals(3_000L, now)
            wire.bytes.clear(); now = 0L
            wire.afterVersion = { if (wire.bytes.size == 1) wire.replies["ADBSTATE"] = "PROPS=1 LISTEN=0\n".toByteArray() }
            assertFalse(prove()); assertTrue(wire.bytes.size >= 2)
            wire.afterVersion = null
            for (raw in listOf("PROPS=? LISTEN=0\n", "PROPS=0 LISTEN=?\n", "ERR\n")) {
                wire.replies["ADBSTATE"] = raw.toByteArray(); now = 0; assertFalse(prove())
            }
            directory.resolve("network-adb-disable.v1").writeText("pending")
            wire.bytes.clear(); assertFalse(prove()); assertTrue(wire.bytes.isEmpty())
        } finally { directory.deleteRecursively() }
    }

    private fun wire(raw: String?) = ZigbeeReadFixture().apply {
        version = "HELPER version=1.10.0 proto=1.10"
        replies["ADBSTATE"] = raw?.toByteArray()
    }
}
