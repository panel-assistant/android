package io.panelassistant.android.http

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareCharacterizationTest {
    @Test fun inputSummaryKeepsOnlyNamesAndHandlers() {
        val raw = """
            I: Bus=0019 Vendor=0001 Product=0001 Version=0100
            N: Name="gpio-keys"
            P: Phys=gpio-keys/input0
            S: Sysfs=/devices/platform/gpio-keys/input/input1
            U: Uniq=private-serial
            H: Handlers=kbd event1

            N: Name="CHT8305 temperature"
            H: Handlers=event7
        """.trimIndent()

        assertEquals(
            listOf("gpio-keys (kbd event1)", "CHT8305 temperature (event7)"),
            HardwareCharacterization.inputDevices(raw),
        )
    }

    @Test fun namedDevicesAreSortedSanitizedAndBounded() {
        val root = Files.createTempDirectory("hardware-names").toFile()
        repeat(30) { i ->
            val node = File(root, "%02d".format(i)).apply { mkdirs() }
            File(node, "name").writeText(" sensor\n$i ")
        }

        val result = HardwareCharacterization.namedDevices(root, "name")

        assertEquals(24, result.size)
        assertEquals("00:sensor 0", result.first())
        assertTrue(result.none { '\n' in it })
    }

    @Test fun missingSourcesAreEmpty() {
        assertTrue(HardwareCharacterization.inputDevices(null).isEmpty())
        assertTrue(HardwareCharacterization.namedDevices(File("/does/not/exist"), "name").isEmpty())
    }
}
