package io.panelassistant.android.util

import io.panelassistant.android.control.AndroidZigbeeGatewayHealthSource
import io.panelassistant.android.control.FakeRootShell
import io.panelassistant.android.control.ZigbeeController
import io.panelassistant.android.control.ZigbeeGatewayLayout
import io.panelassistant.android.control.ZigbeeHealthPolicy
import io.panelassistant.android.control.ZigbeeHealthState
import io.panelassistant.android.control.fakeProfile
import io.panelassistant.android.platform.DaemonLongResult
import io.panelassistant.android.platform.DaemonStreamResult
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/** Uses only APIs present at the exact pre-Zigbee base, so this file is also its behavioral red proof. */
class ZigbeeHelperReadBehaviorTest {
    private val dir = "/vendor/bin/siliconlabs_host"

    @Test fun profileWithoutGatewayIsKnownAbsentWithoutAnyPrivilegedRead() {
        val wire = ZigbeeReadFixture()
        val root = FakeRootShell()
        val controller = ZigbeeController(fakeProfile(zigbeeGatewayDir = null), root, wire.client)
        assertEquals("none", controller.observe(includeRole = true).status)
        assertFalse(controller.present())
        assertNull(controller.role())
        assertEquals(0, wire.opened)
        assertNoZigbeeRootReads(root)
    }

    @Test fun managementAndControlReadsUseTheVersionedHelperWithoutStartingSu() {
        val wire = ZigbeeReadFixture()
        wire.replies["ZIGBEESTATE"] = zigbeeStateWire(running = true, packageVersion = "sonoff-v3.5.4:sonoff-3.5.0")
        wire.replies["ZIGBEEROLE"] = zigbeeRoleWire("""{"role":"Repeater"}""")
        // These root replies deliberately disagree with the helper; an accidental fallback is observable.
        val root = FakeRootShell(mapOf("pidof zgateway" to "", "for f in run_guard_process.sh" to ""))
        val controller = ZigbeeController(fakeProfile(zigbeeGatewayDir = dir), root, wire.client)
        repeat(3) {
            val observed = controller.observe(includeRole = true)
            assertTrue(observed.probeSucceeded)
            assertTrue(observed.present)
            assertTrue(observed.running)
            assertEquals(ZigbeeGatewayLayout.MANAGED, observed.layout)
            assertEquals("sonoff 3.5.0", observed.driver)
            assertEquals("Repeater", observed.role)
            assertTrue(controller.present())
            assertEquals(true, controller.running())
            assertEquals("Repeater", controller.role())
        }
        assertNoZigbeeRootReads(root)
        assertTrue(root.ran.isEmpty())
        assertTrue(wire.bytes.isNotEmpty())
    }

    @Test fun missingOldRefusedAndMalformedHelpersRemainUnknownWithoutSuOrReconcileActions() {
        for (mode in listOf("missing", "old", "refused", "malformed")) {
            val wire = ZigbeeReadFixture()
            when (mode) {
                "missing" -> wire.missing = true
                "old" -> wire.version = "HELPER version=1.5.0 proto=1.5"
                "refused" -> wire.replies["ZIGBEESTATE"] = "ERR\n".toByteArray()
                "malformed" -> wire.replies["ZIGBEESTATE"] = "ZIGBEESTATE 1\nrunning=0\nEND\n".toByteArray()
            }
            val root = FakeRootShell(mapOf("pidof zgateway" to "1234", "siliconlabs_host/zgateway" to "zgateway"))
            val controller = ZigbeeController(fakeProfile(zigbeeGatewayDir = dir), root, wire.client)
            repeat(3) {
                val observed = controller.observe(includeRole = true)
                assertFalse(observed.probeSucceeded, mode)
                assertEquals("gateway · status unavailable", observed.status)
                assertFalse(controller.present(), mode)
                assertNull(controller.running(), mode)
                assertFalse(controller.reconcile(false), mode)
                assertFalse(controller.reconcile(true), mode)
            }
            assertNoZigbeeRootReads(root)
            assertTrue(root.ran.isEmpty(), mode)
        }
    }

    @Test fun unavailableHealthNeverBecomesOffAndDoesNotFallBackToSu() {
        val wire = ZigbeeReadFixture().apply { missing = true }
        val root = FakeRootShell(mapOf("for f in run_guard_process.sh" to "guard_process.sh\nzgateway\n"))
        val controller = ZigbeeController(fakeProfile(zigbeeGatewayDir = dir), root, wire.client)
        val source = AndroidZigbeeGatewayHealthSource(dir, controller, root, wire.client) { null }
        repeat(3) {
            val decision = ZigbeeHealthPolicy(startupGraceMs = 0).evaluate(100L + it, false, source.observe())
            assertEquals(ZigbeeHealthState.UNKNOWN, decision.state)
            assertFalse(decision.shouldContain)
        }
        assertNoZigbeeRootReads(root)
        assertTrue(root.ran.isEmpty())
    }

    @Test fun knownStoppedGatewayReconcilesOffWithoutAnyRootReadOrMutation() {
        val wire = ZigbeeReadFixture()
        wire.replies["ZIGBEESTATE"] = zigbeeStateWire()
        val root = FakeRootShell(mapOf("pidof zgateway" to "1234"))
        val controller = ZigbeeController(fakeProfile(zigbeeGatewayDir = dir), root, wire.client)
        assertEquals(false, controller.running())
        assertTrue(controller.reconcile(false))
        assertNoZigbeeRootReads(root)
        assertTrue(root.ran.isEmpty())
    }
}

internal fun assertNoZigbeeRootReads(root: FakeRootShell) {
    assertTrue(root.outputRan.isEmpty(), "unexpected root reads: ${root.outputRan}")
    assertTrue(root.isolatedOutputRan.isEmpty(), "unexpected isolated root reads: ${root.isolatedOutputRan}")
    assertEquals(0, root.availabilityChecks)
}

internal val managedZigbeeFiles = setOf("run_guard_process.sh", "guard_process.sh", "run.sh", "zgateway", "mosquitto", "package_version")
internal fun String.zigbeeHex() = toByteArray().joinToString("") { "%02x".format(it.toInt() and 255) }
internal fun zigbeeStateWire(
    files: Set<String> = managedZigbeeFiles,
    running: Boolean = false,
    guardRunning: Boolean = false,
    packageVersion: String = "",
): ByteArray = buildString {
    appendLine("ZIGBEESTATE 1")
    listOf("managed" to "run_guard_process.sh", "guard" to "guard_process.sh", "run" to "run.sh", "binary" to "zgateway", "broker" to "mosquitto", "package_marker" to "package_version")
        .forEach { (key, file) -> appendLine("$key=${if (file in files) 1 else 0}") }
    appendLine("running=${if (running) 1 else 0}")
    appendLine("guard_running=${if (guardRunning) 1 else 0}")
    appendLine("package=${packageVersion.zigbeeHex()}")
    appendLine("END")
}.toByteArray()
internal fun zigbeeRoleWire(raw: String) = "ZIGBEEROLE ${raw.zigbeeHex()}\n".toByteArray()
internal fun zigbeeHealthWire(
    files: Set<String> = managedZigbeeFiles,
    pid: Int? = 10,
    start: Long = 100,
    jiffies: Long = 50,
    guardPid: Int? = null,
    total: Long = 1000,
    netinfo: String? = """{"roleType":7,"nodeId":123,"panId":4660}""",
    recursive: Boolean = false,
): ByteArray = buildString {
    append(String(zigbeeStateWire(files, pid != null, guardPid != null)).replace("ZIGBEESTATE 1", "ZIGBEEHEALTH 1").removeSuffix("END\n"))
    appendLine("gateway_pid=${pid ?: "-"}")
    appendLine("gateway_start=${if (pid == null) "-" else start}")
    appendLine("gateway_jiffies=${if (pid == null) "-" else jiffies}")
    appendLine("guard_pid=${guardPid ?: "-"}")
    appendLine("guard_start=${if (guardPid == null) "-" else 90}")
    appendLine("guard_jiffies=${if (guardPid == null) "-" else 1}")
    appendLine("total_jiffies=$total")
    appendLine("recursive=${if (recursive) 1 else 0}")
    appendLine("netinfo=${netinfo?.zigbeeHex() ?: "-"}")
    appendLine("END")
}.toByteArray()

internal class ZigbeeReadFixture : HelperCommandTransport {
    var version = "HELPER version=1.8.0 proto=1.8"
    var missing = false
    var afterVersion: (() -> Unit)? = null
    val replies = mutableMapOf<String, ByteArray?>()
    val bytes = mutableListOf<Triple<Int, String, Long>>()
    val bootstraps = mutableListOf<Pair<Int, String>>()
    val rawCommands = mutableListOf<String>()
    var opened = 0
    var closed = 0
    val client = IdentityAdmittingHelperClient(this)
    override fun open(): HelperCommandSession? {
        if (missing) return null
        val session = ++opened
        val identity = version
        val snapshot = replies.toMap()
        return object : HelperCommandSession {
            override fun bootstrap(command: String, deadline: MonotonicDeadline): HelperBootstrapReply {
                bootstraps += session to command
                afterVersion?.invoke()
                return HelperBootstrapReply.Line(identity)
            }
            override fun sendBytes(cmd: String, maxBytes: Long): ByteArray? {
                bytes += Triple(session, cmd, maxBytes)
                return snapshot[cmd]
            }
            override fun send(cmd: String): String? { rawCommands += cmd; return null }
            override fun sendLong(cmd: String, timeoutMs: Long) = DaemonLongResult.NotSubmitted
            override fun sendFile(cmd: String, source: File, timeoutMs: Long) = DaemonStreamResult.NotSubmitted
            override fun readLogcatLine(): String? = null
            override fun backupCompanion(packageName: String, cacheDir: File, timeoutMs: Long, hold: Boolean): CompanionHelperProtocol.BackupResult = error("unexpected backup")
            override fun restoreCompanion(packageName: String, files: Map<String, File>, timeoutMs: Long, expected: CompanionHelperProtocol.LiveDigest?): CompanionHelperProtocol.RestoreResult = error("unexpected restore")
            override fun close() { closed++ }
        }
    }
}
