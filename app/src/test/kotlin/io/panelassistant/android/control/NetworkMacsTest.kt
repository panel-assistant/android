package io.panelassistant.android.control

import io.panelassistant.android.util.PanelAssistantDevice
import java.io.File
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** What the status's hardware block says about the panel's network hardware, read from a sysfs tree. */
class NetworkMacsTest {
    private fun sysfs(vararg interfaces: Pair<String, String?>): File {
        val root = Files.createTempDirectory("net").toFile().apply { deleteOnExit() }
        for ((name, address) in interfaces) {
            val iface = File(root, name).apply { mkdirs() }
            if (address != null) File(iface, "address").writeText("$address\n")
        }
        return root
    }

    private fun hardwareBlock(root: File) = hardwareBlock(NetworkMacs(root))

    private fun hardwareBlock(macs: NetworkMacs) = JSONObject(
        PanelAssistantDevice.hardwareJson("fw", "11", null, "0123456789abcdef", macs.read()),
    )

    /** A Shelly Wall Display's own wlan0, the address the Shelly integration registers. */
    @Test fun theWifiAddressReachesTheHardwareBlock() {
        val block = hardwareBlock(sysfs("wlan0" to "00:1A:2B:3C:4D:5E", "lo" to "00:00:00:00:00:00"))
        assertEquals("00:1a:2b:3c:4d:5e", block.getJSONArray("network_macs").getString(0))
        assertEquals(1, block.getJSONArray("network_macs").length())
    }

    @Test fun wiredAndWirelessAddressesAreBothSentInOrder() {
        val macs = NetworkMacs(sysfs("wlan0" to "00:1a:2b:3c:4d:60", "eth0" to "00:1a:2b:3c:4d:5d")).read()
        assertEquals(listOf("00:1a:2b:3c:4d:5d", "00:1a:2b:3c:4d:60"), macs)
    }

    /** Placeholder, randomised, multicast, virtual and unreadable addresses name no shared hardware. */
    @Test fun addressesThatNameNoHardwareAreLeftOut() {
        val root = sysfs(
            "wlan0" to "02:00:00:00:00:00",
            "wlan1" to "da:a1:19:00:00:01",
            "eth0" to "01:00:5e:00:00:01",
            "eth1" to "00:00:00:00:00:00",
            "eth2" to "not-a-mac",
            "en0" to null,
            "dummy0" to "00:11:22:33:44:55",
            "rmnet0" to "00:11:22:33:44:56",
        )
        assertEquals(emptyList<String>(), NetworkMacs(root).read())
        assertFalse(hardwareBlock(root).has("network_macs"))
    }

    @Test fun aPermissionDenialIsNotRepeatedOnLaterStatuses() {
        var listings = 0
        val macs = NetworkMacs(File("/sys/class/net")) { root ->
            listings++
            throw AccessDeniedException(root.path)
        }
        repeat(3) { assertFalse(hardwareBlock(macs).has("network_macs")) }
        assertEquals(1, listings)
    }

    @Test fun aMissingInterfaceDirectoryCanRecoverAndReportLaterInterfaces() {
        val root = File(sysfs(), "net")
        val macs = NetworkMacs(root)
        assertFalse(hardwareBlock(macs).has("network_macs"))
        File(root, "wlan0").apply { mkdirs() }.resolve("address").writeText("00:1a:2b:3c:4d:5e\n")
        assertEquals("00:1a:2b:3c:4d:5e", hardwareBlock(macs).getJSONArray("network_macs").getString(0))
        File(root, "eth0").apply { mkdirs() }.resolve("address").writeText("00:1a:2b:3c:4d:5d\n")
        assertEquals(2, hardwareBlock(macs).getJSONArray("network_macs").length())
    }

    @Test fun aPanelThatCannotListItsInterfacesSendsNone() {
        assertEquals(emptyList<String>(), NetworkMacs(File("/nonexistent/sys/class/net")).read())
    }
}
