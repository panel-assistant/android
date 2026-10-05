package io.panelassistant.android.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxBridgeProbeTest {
    @Test fun runningCubePayloadIsDetectedThroughHelperProcessSnapshot() {
        val dump = "@STAT\ncpu 1 2 3 4\n@PROC\n12\t0\trunsv\t10\n34\t0\t/data/data/com.termux/files/usr/bin/node\t12000\n@BRIDGE 1\n@REND\n@END\n"
        assertEquals(TermuxBridgeProbe.State.RUNNING, TermuxBridgeProbe.observe(true, 10001, dump, null, null))
        assertEquals("[panel-zigbee-bridge] state=running",
            TermuxBridgeProbe.diagnosticLine(TermuxBridgeProbe.observe(true, 10001, dump, null, null)))
    }

    @Test fun stoppedSupervisorAndOrdinaryTermuxAreAbsent() {
        val dump = "@STAT\ncpu 1 2 3 4\n@PROC\n12\t0\trunsv\t10\n35\t0\t/data/data/com.termux/files/usr/bin/bash\t100\n@BRIDGE 0\n@REND\n@END\n"
        assertEquals(TermuxBridgeProbe.State.ABSENT, TermuxBridgeProbe.observe(true, 10001, dump, null, null))
        assertEquals("[panel-zigbee-bridge] state=absent",
            TermuxBridgeProbe.diagnosticLine(TermuxBridgeProbe.observe(true, 10001, dump, null, null)))
        assertEquals(TermuxBridgeProbe.State.NOT_APPLICABLE, TermuxBridgeProbe.observe(false, 10001, dump, null, null))
        assertNull(TermuxBridgeProbe.diagnosticLine(TermuxBridgeProbe.observe(false, 10001, dump, null, null)))
    }

    @Test fun rootProcessListFindsZigbee2mqttAndNoRouteIsUnknown() {
        val ps = "  222 /data/data/com.termux/files/usr/bin/node /data/data/com.termux/files/home/zigbee2mqtt/index.js\n"
        assertEquals(TermuxBridgeProbe.State.RUNNING,
            TermuxBridgeProbe.observe(true, 10001, null, ps, "222\t10001\t/data/data/com.termux/files/home/zigbee2mqtt\n"))
        assertEquals(TermuxBridgeProbe.State.UNKNOWN, TermuxBridgeProbe.observe(true, 10001, null, null, null))
        assertEquals("[panel-zigbee-bridge] state=unknown",
            TermuxBridgeProbe.diagnosticLine(TermuxBridgeProbe.observe(true, 10001, null, null, null)))
        assertEquals(TermuxBridgeProbe.State.UNKNOWN,
            TermuxBridgeProbe.observe(true, 10001, "@PROC\n34\t0\tnode\t12000\n@REND\n@END\n", null, null))
    }

    @Test fun relativeCubeScriptIsRunningButMentionsAndForeignOwnersAreNot() {
        val ps = "222 /data/data/com.termux/files/usr/bin/node dist/index.js\n" +
            "223 tail /data/data/com.termux/files/home/zigbee2mqtt/log/current\n"
        assertEquals(TermuxBridgeProbe.State.RUNNING, TermuxBridgeProbe.observe(true, 10001, null, ps,
            "222\t10001\t/data/data/com.termux/files/home/05cube-ts\n"))
        assertEquals(TermuxBridgeProbe.State.ABSENT, TermuxBridgeProbe.observe(true, 10002, null, ps,
            "222\t10001\t/data/data/com.termux/files/home/05cube-ts\n"))
        assertEquals(TermuxBridgeProbe.State.ABSENT, TermuxBridgeProbe.observe(true, 10001, null,
            "223 tail /data/data/com.termux/files/home/zigbee2mqtt/log/current\n", null))
        assertEquals(TermuxBridgeProbe.State.ABSENT, TermuxBridgeProbe.observe(true, 10001, null,
            "224 node /data/data/com.termux/files/home/zigbee2mqtt/tools/backup.js\n", "224\t10001\t/data/data/com.termux/files/home/zigbee2mqtt\n"))
        assertEquals(TermuxBridgeProbe.State.ABSENT, TermuxBridgeProbe.observe(true, 10001, null,
            "224 node /data/data/com.termux/files/home/zigbee2mqtt/tools/index.js\n", "224\t10001\t/data/data/com.termux/files/home/zigbee2mqtt\n"))
        assertEquals(TermuxBridgeProbe.State.UNKNOWN, TermuxBridgeProbe.observe(true, 10001, null, ps,
            "222\t10001\t\n"))
    }

    @Test fun productionRootRouteCollectsOwnerAndCwdBeforeWarning() {
        val commands = mutableListOf<String>()
        val result = TermuxBridgeProbe.collect(Result.success(10001), { true to false }, { command, _, _ ->
            commands += command
            if (command.startsWith("ps ")) "222 node dist/index.js\n" else "222\t10001\t/data/data/com.termux/files/home/05cube-ts\n"
        }, { error("helper must not be called") })
        assertEquals(TermuxBridgeProbe.State.RUNNING, result)
        assertEquals(2, commands.size)
        assertEquals("ps -A -o PID=,ARGS=", commands[0])
        assertTrue(commands[1].contains("stat -c %u /proc/\$p"))
        assertTrue(commands[1].contains("readlink /proc/\$p/cwd"))
        assertTrue(commands[1].startsWith("for p in 222;"))
    }

    @Test fun productionHelperRouteIsUidScopedAndNoTermuxMakesNoCalls() {
        val calls = mutableListOf<String>()
        val helper = { command: String -> calls += command; "@PROC\n@BRIDGE 0\n@END\n" }
        assertEquals(TermuxBridgeProbe.State.ABSENT,
            TermuxBridgeProbe.collect(Result.success(10001), { false to true }, { _, _, _ -> error("root unavailable") }, helper))
        assertEquals(listOf("PERFDUMP BRIDGE 10001"), calls)
        assertEquals(TermuxBridgeProbe.State.NOT_APPLICABLE,
            TermuxBridgeProbe.collect(Result.success(null), { error("routes must not be probed") },
                { _, _, _ -> error("root must not be called") }, { error("helper must not be called") }))
        assertEquals(TermuxBridgeProbe.State.UNKNOWN,
            TermuxBridgeProbe.collect(Result.failure(IllegalStateException("package manager unavailable")),
                { error("routes must not be probed") }, { _, _, _ -> error("root must not be called") },
                { error("helper must not be called") }))
        assertEquals(TermuxBridgeProbe.State.UNKNOWN,
            TermuxBridgeProbe.collect(Result.success(10001), { false to false }, { _, _, _ -> null }, { null }))
    }
}
