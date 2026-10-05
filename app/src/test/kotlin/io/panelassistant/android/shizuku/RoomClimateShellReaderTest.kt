package io.panelassistant.android.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoomClimateShellReaderTest {
    @Test fun readsTheExactZxNamesAndAxesWithoutCallerSuppliedPaths() {
        val requested = mutableListOf<String>()
        val result = RoomClimateShellReader.read(ZX_INVENTORY) { node ->
            requested += node
            when (node) {
                "/dev/input/event7" -> THS_PROPERTIES
                "/dev/input/event8" -> HUM_PROPERTIES
                else -> null
            }
        }

        assertEquals("T=2384 H=5895", result)
        assertEquals(listOf("/dev/input/event7", "/dev/input/event8"), requested)
    }

    @Test fun retainsTheExistingTpa10CompatibleLayout() {
        val result = RoomClimateShellReader.read(TPA_INVENTORY) { node ->
            when (node) {
                "/dev/input/event4" -> THS_PROPERTIES
                "/dev/input/event5" -> HUM_PROPERTIES.replace("001d", "ABS_THROTTLE")
                else -> null
            }
        }

        assertEquals("T=2384 H=5895", result)
    }

    @Test fun duplicateNamesAndWrongAxesFailClosed() {
        assertNull(RoomClimateShellReader.read(ZX_INVENTORY + "\n\n" + ZX_INVENTORY) { THS_PROPERTIES })
        assertNull(RoomClimateShellReader.read(ZX_INVENTORY) { THS_PROPERTIES })
    }

    @Test fun completeLayoutWithPartialSecondLayoutFailsClosedBeforeOpeningNodes() {
        val inventory = TPA_INVENTORY + "\n\n" + """
            N: Name="sun-ths"
            H: Handlers=event7
        """.trimIndent()
        var calls = 0

        assertNull(RoomClimateShellReader.read(inventory) { calls++; THS_PROPERTIES })
        assertEquals(0, calls)
    }

    @Test fun twoCompleteLayoutsFailClosedBeforeOpeningNodes() {
        var calls = 0

        assertNull(RoomClimateShellReader.read(TPA_INVENTORY + "\n\n" + ZX_INVENTORY) {
            calls++
            THS_PROPERTIES
        })
        assertEquals(0, calls)
    }

    @Test fun nonEventHandlersAndLookalikeNamesAreNeverOpened() {
        val inventory = """
            N: Name="sun-ths-extra"
            H: Handlers=event7

            N: Name="sun-hum"
            H: Handlers=mouse0 js0
        """.trimIndent()
        var calls = 0
        assertNull(RoomClimateShellReader.read(inventory) { calls++; THS_PROPERTIES })
        assertEquals(0, calls)
    }

    private companion object {
        val ZX_INVENTORY = """
            I: Bus=0019 Vendor=0001 Product=0001 Version=0100
            N: Name="sun-ths"
            H: Handlers=event7 dmcfreq

            I: Bus=0019 Vendor=0001 Product=0001 Version=0100
            N: Name="sun-hum"
            H: Handlers=event8
        """.trimIndent()

        val TPA_INVENTORY = """
            N: Name="temperature"
            H: Handlers=event4

            N: Name="humidity"
            H: Handlers=event5
        """.trimIndent()

        val THS_PROPERTIES = """
              events:
                ABS (0003): ABS_THROTTLE          : value 2384, min -4000, max 12500
        """.trimIndent()

        val HUM_PROPERTIES = """
              events:
                ABS (0003): 001d                  : value 5895, min 0, max 10000
        """.trimIndent()
    }
}
