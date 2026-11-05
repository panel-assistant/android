package io.panelassistant.android.util

import android.net.LocalSocket
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

class HelperClientTransportLogTest {
    @Test
    fun unreachableHelperLogsOncePerChange() {
        val lines = mutableListOf<String>()
        var up = false
        val transport = LocalSocketHelperTransport(
            connect = { if (up) LocalSocket() else throw java.io.IOException("refused") },
            tag = "t",
            reachability = HelperReachabilityLog("t") { _, m -> lines += m },
        )
        repeat(100) { assertNull(transport.open()) }
        assertEquals(1, lines.size)
        up = true
        transport.open()
        up = false
        repeat(5) { assertNull(transport.open()) }
        assertEquals(2, lines.size)
    }
}
