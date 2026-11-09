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

    private fun typedExchange(header: String, reply: ByteArray): ByteArray? = HelperSocketProtocol.adbRecoveryExchange(
        header, byteArrayOf(), ByteArrayInputStream(reply), 1_000, { it(); true }, {}, {},
    )

    @Test fun aTypedCommandReadsExactlyItsDeclaredOkBody() {
        val body = "model=X\nsdk=27\n"
        val reply = "OK ${body.length}\n$body".toByteArray() + "trailing".toByteArray()
        assertArrayEquals("OK ${body.length}\n$body".toByteArray(), typedExchange("PA_IDENTITY", reply))
        assertArrayEquals("OK 0\n".toByteArray(), typedExchange("PA_ADMIN", "OK 0\n".toByteArray()))
    }

    @Test fun aTypedCommandAcceptsTheLargestBodyAndRefusesOneByteMore() {
        val largest = ByteArray(4096) { 'a'.code.toByte() }
        assertEquals("OK 4096\n".length + 4096, typedExchange("PA_REPAIR 1 0", "OK 4096\n".toByteArray() + largest)!!.size)
        assertThrows(IllegalStateException::class.java) { typedExchange("PA_REPAIR 1 0", "OK 4097\n".toByteArray() + largest + byteArrayOf(97)) }
        assertThrows(IllegalStateException::class.java) { typedExchange("PA_REPAIR 1 0", "OK 01\na".toByteArray()) }
        assertThrows(IllegalStateException::class.java) { typedExchange("PA_REPAIR 1 0", "OK 5\nab".toByteArray()) }
    }

    @Test fun aTypedCommandRefusalOrBusyIsOneLineAndAnythingElseIsRefused() {
        assertArrayEquals("ERR caller\n".toByteArray(), typedExchange("PA_HOME 1 repair", "ERR caller\nextra".toByteArray()))
        assertArrayEquals("BUSY\n".toByteArray(), typedExchange("PA_HOME 1 repair", "BUSY\n".toByteArray()))
        assertThrows(IllegalStateException::class.java) { typedExchange("PA_HOME 1 repair", "RESULT 0 0 0 0\n".toByteArray()) }
        assertThrows(IllegalStateException::class.java) { typedExchange("PA_HOME 1 repair", "ERR\n".toByteArray()) }
    }
}
