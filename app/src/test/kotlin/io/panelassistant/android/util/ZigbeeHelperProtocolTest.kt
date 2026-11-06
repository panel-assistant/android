package io.panelassistant.android.util

import io.panelassistant.android.control.AndroidZigbeeGatewayHealthSource
import io.panelassistant.android.control.FakeRootShell
import io.panelassistant.android.control.ZigbeeController
import io.panelassistant.android.control.ZigbeeHealthPolicy
import io.panelassistant.android.control.ZigbeeHealthState
import io.panelassistant.android.control.fakeProfile
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class ZigbeeHelperProtocolTest {
    @Test fun eachReadAdmitsItsOwnConnectionAndClosesItWithoutRawReconnect() {
        for ((command, bound) in listOf("ZIGBEESTATE" to 512L, "ZIGBEEHEALTH" to 9216L, "ZIGBEEROLE" to 8204L)) {
            val wire = ZigbeeReadFixture()
            wire.replies[command] = when (command) {
                "ZIGBEESTATE" -> zigbeeStateWire()
                "ZIGBEEHEALTH" -> zigbeeHealthWire()
                else -> zigbeeRoleWire("""{"role":"Repeater"}""")
            }
            wire.afterVersion = { wire.version = "HELPER version=2.0.0 proto=2.8" }
            assertNotNull(read(wire, command))
            assertNull(read(wire, command)) // replacement is admitted anew
            assertEquals(listOf(Triple(1, command, bound)), wire.bytes)
            assertEquals(listOf(1 to "VERSION", 2 to "VERSION"), wire.bootstraps)
            assertEquals(2, wire.closed)
            assertTrue(wire.rawCommands.isEmpty())
        }
    }

    @Test fun capabilityMinorsAreIndependentAndRejectUnverifiedIdentities() {
        for ((command, minimum) in listOf("ZIGBEESTATE" to 6, "ZIGBEEHEALTH" to 7, "ZIGBEEROLE" to 8)) {
            for (version in listOf("ERR", "HELPER version=1.0.0 proto=1.${minimum - 1}", "HELPER version=2.0.0 proto=2.8", "HELPER version=1.8.0 proto=1.8 proto=1.8")) {
                val wire = ZigbeeReadFixture().apply { this.version = version }
                assertNull(read(wire, command))
                assertTrue(wire.bytes.isEmpty(), "$command at $version")
                assertTrue(wire.rawCommands.isEmpty())
                assertEquals(1, wire.closed)
            }
            val wire = ZigbeeReadFixture().apply { version = "HELPER version=1.99.0 proto=1.99" }
            wire.replies[command] = when (command) {
                "ZIGBEESTATE" -> zigbeeStateWire()
                "ZIGBEEHEALTH" -> zigbeeHealthWire()
                else -> zigbeeRoleWire("""{"role":"Vendor β"}""")
            }
            assertNotNull(read(wire, command))
        }
    }

    @Test fun stateParserRefusesMalformedFlagsOrderHexUtf8AndBounds() {
        val valid = String(zigbeeStateWire(packageVersion = "vendor β"))
        val replies = listOf(
            null, "ERR\n", valid.removeSuffix("\n"), valid + "extra\n",
            valid.replace("managed=1", "managed=2"), valid.replace("guard=1", "managed=1"),
            valid.replace("package=", "extra=0\npackage="),
            valid.replace(Regex("package=[^\n]*"), "package=f"),
            valid.replace(Regex("package=[^\n]*"), "package=FF"),
            valid.replace(Regex("package=[^\n]*"), "package=c080"),
            valid.replace(Regex("package=[^\n]*"), "package=00"),
            valid.replace(Regex("package=[^\n]*"), "package=${"a".repeat(121).zigbeeHex()}"),
            "x".repeat(513),
        )
        for (reply in replies) {
            val wire = ZigbeeReadFixture()
            wire.replies["ZIGBEESTATE"] = reply?.toByteArray()
            assertNull(wire.client.zigbeeState(), reply?.take(100))
        }
        val wire = ZigbeeReadFixture()
        wire.replies["ZIGBEESTATE"] = zigbeeStateWire(packageVersion = "a".repeat(120))
        assertEquals("a".repeat(120), wire.client.zigbeeState()?.packageVersion)
    }

    @Test fun healthParserRejectsPartialOrInconsistentProcessEvidenceAndOversizedNetinfo() {
        val good = String(zigbeeHealthWire())
        val invalid = listOf(
            good.removeSuffix("END\n"), good + "extra\n",
            good.replace("gateway_pid=10", "gateway_pid=0"),
            good.replace("gateway_pid=10", "gateway_pid=01"),
            good.replace("gateway_start=100", "gateway_start=-"),
            good.replace("gateway_jiffies=50", "gateway_jiffies=9223372036854775808"),
            good.replace("running=1\n", "running=0\n"),
            good.replace("guard_pid=-", "guard_pid=10").replace("guard_start=-", "guard_start=1").replace("guard_jiffies=-", "guard_jiffies=1").replace("guard_running=0", "guard_running=1"),
            good.replace("total_jiffies=1000", "total_jiffies=-1"),
            good.replace("recursive=0", "recursive=2"),
            good.replace(Regex("netinfo=[^\n]*"), "netinfo=${"a".repeat(4097).zigbeeHex()}"),
            good.replace(Regex("netinfo=[^\n]*"), "netinfo=ff"),
            "x".repeat(9217),
        )
        for (reply in invalid) {
            val wire = ZigbeeReadFixture()
            wire.replies["ZIGBEEHEALTH"] = reply.toByteArray()
            assertNull(wire.client.zigbeeHealth(), reply.take(100))
        }
        val wire = ZigbeeReadFixture()
        wire.replies["ZIGBEEHEALTH"] = zigbeeHealthWire(pid = null, netinfo = " ".repeat(4096))
        assertEquals(4096, wire.client.zigbeeHealth()?.netInfo?.length)
        wire.replies["ZIGBEEHEALTH"] = zigbeeHealthWire(pid = null, netinfo = null)
        assertNull(wire.client.zigbeeHealth()?.gateway)
    }

    @Test fun roleParserKeepsExistingJsonValidationWithinTheWireLimit() {
        for (raw in listOf("", "{", """{"role":7}""", """{"role":""}""", """{"role":"bad\nrole"}""", """{"role":"${"a".repeat(65)}"}""", " ".repeat(4097))) {
            val wire = ZigbeeReadFixture()
            wire.replies["ZIGBEEROLE"] = zigbeeRoleWire(raw)
            assertNull(wire.client.zigbeeRole())
        }
        val wire = ZigbeeReadFixture()
        for (reply in listOf("ERR\n", "ZIGBEEROLE ff\n", "ZIGBEEROLE 7B7D\n", "ZIGBEEROLE 7\n", String(zigbeeRoleWire("""{"role":"Repeater"}""")).removeSuffix("\n"))) {
            wire.replies["ZIGBEEROLE"] = reply.toByteArray()
            assertNull(wire.client.zigbeeRole())
        }
        wire.replies["ZIGBEEROLE"] = zigbeeRoleWire("""{"role":"${"a".repeat(64)}"}""")
        assertEquals("a".repeat(64), wire.client.zigbeeRole())
    }

    @Test fun healthCpuBaselineResetsOnHelperLossMalformedReadsAbsenceAndPidReuse() {
        val wire = ZigbeeReadFixture()
        val root = FakeRootShell()
        val dir = "/vendor/bin/siliconlabs_host"
        val controller = ZigbeeController(fakeProfile(zigbeeGatewayDir = dir), root, wire.client)
        val source = AndroidZigbeeGatewayHealthSource(dir, controller, root, wire.client) { "3.8.0" }
        wire.replies["ZIGBEEROLE"] = zigbeeRoleWire("""{"role":"Repeater"}""")
        fun sample(total: Long, jiffies: Long, start: Long = 100) = run {
            wire.replies["ZIGBEEHEALTH"] = zigbeeHealthWire(total = total, jiffies = jiffies, start = start)
            source.observe()
        }
        val first = sample(1000, 50)
        assertNull(first.gatewayCpu)
        assertEquals("Repeater", first.role)
        assertEquals(true, first.netInfo?.explicitlyJoined)
        assertEquals(10, first.gatewayPid)
        assertEquals("3.8.0", first.productVersion)
        assertNotNull(sample(1100, 60).gatewayCpu)
        wire.missing = true
        val unavailable = source.observe()
        assertEquals(ZigbeeHealthState.UNKNOWN, ZigbeeHealthPolicy(startupGraceMs = 0).evaluate(1000, false, unavailable).state)
        wire.missing = false
        assertNull(sample(1200, 70).gatewayCpu)
        assertNotNull(sample(1300, 80).gatewayCpu)
        wire.replies["ZIGBEEHEALTH"] = "ERR\n".toByteArray()
        assertFalse(source.observe().probeSucceeded)
        assertNull(sample(1400, 90).gatewayCpu)
        assertNull(sample(1500, 100, start = 999).gatewayCpu)
        assertNotNull(sample(1600, 110, start = 999).gatewayCpu)
        wire.replies["ZIGBEEHEALTH"] = zigbeeHealthWire(pid = null)
        val stopped = source.observe()
        assertEquals(ZigbeeHealthState.OFF, ZigbeeHealthPolicy(startupGraceMs = 0).evaluate(2000, false, stopped).state)
        assertNull(sample(1700, 120, start = 999).gatewayCpu)
        assertNoZigbeeRootReads(root)
    }

    @Test fun healthProjectionRetainsOnlyParsedNetworkHealthFields() {
        val wire = ZigbeeReadFixture()
        val dir = "/vendor/bin/siliconlabs_host"
        val root = FakeRootShell()
        val controller = ZigbeeController(fakeProfile(zigbeeGatewayDir = dir), root, wire.client)
        val source = AndroidZigbeeGatewayHealthSource(dir, controller, root, wire.client) { null }
        wire.replies["ZIGBEEHEALTH"] = zigbeeHealthWire(netinfo = """{"networkKey":"secret","radioMac":"private","netinfo":{"roleType":7,"nodeId":123,"panId":4660}}""")
        val observed = source.observe()
        assertTrue(observed.probeSucceeded)
        assertTrue(observed.netInfo!!.explicitlyJoined)
        assertFalse(observed.toString().contains("secret"))
        assertFalse(observed.toString().contains("private"))
        assertNoZigbeeRootReads(root)
    }

    private fun read(wire: ZigbeeReadFixture, command: String): Any? = when (command) {
        "ZIGBEESTATE" -> wire.client.zigbeeState()
        "ZIGBEEHEALTH" -> wire.client.zigbeeHealth()
        else -> wire.client.zigbeeRole()
    }
}
