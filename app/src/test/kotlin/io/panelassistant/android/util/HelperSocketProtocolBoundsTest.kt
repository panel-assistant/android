package io.panelassistant.android.util

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class HelperSocketProtocolBoundsTest {
    @Test fun `line helper reply preserves printable bytes and rejects controls partial EOF and overflow`() {
        fun read(raw: String): String? = HelperSocketProtocol.sendLine(
            "PING",
            ByteArrayInputStream(raw.toByteArray(Charsets.US_ASCII)),
            ByteArrayOutputStream(),
        )

        assertEquals("OK", read("OK\n"))
        assertEquals(" OK", read(" OK\n"))
        assertEquals("OK ", read("OK \n"))
        assertNull(read("OK\r\n"))
        assertNull(read("\tOK\n"))
        assertNull(read("OK"))
        assertNull(read("X".repeat(513) + "\n"))
    }

    @Test fun `binary helper reply enforces its streaming limit`() {
        val command = ByteArrayOutputStream()
        assertThrows(ByteLimitExceeded::class.java) {
            HelperSocketProtocol.sendBytes(
                command = "SCREENCAP",
                input = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)),
                output = command,
                shutdownOutput = {},
                maxBytes = 3,
            )
        }
        assertArrayEquals("SCREENCAP\n".toByteArray(), command.toByteArray())
    }
}
