package io.panelassistant.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.NoSuchFileException

class AccessDenialMemoTest {
    private var now = 0L
    private val logs = mutableListOf<String>()
    private val memo = AccessDenialMemo(nowMs = { now }, ttlMs = 1_000L, log = { logs += it })

    private var reads = 0
    private var probes = 0

    private fun poll(value: String?, denied: Boolean, suspect: (String?) -> Boolean = { it.isNullOrEmpty() }) =
        memo.read(
            key = "property:service.adb.tls.port",
            what = "Android property service.adb.tls.port",
            suspect = suspect,
            probeDenied = { probes++; denied },
        ) { reads++; value }

    @Test
    fun aDeniedReadIsIssuedOnceAndLoggedOnceAcrossRepeatedPolls() {
        repeat(10) { assertNull(poll(value = "", denied = true)); now += 30 }

        assertEquals(1, reads)
        assertEquals(1, probes)
        assertEquals(1, logs.size)
        assertTrue(logs.single().startsWith("Android property service.adb.tls.port: access denied"))
    }

    @Test
    fun aCapabilityChangeSignalReprobesARememberedDenial() {
        memo.onCapabilitySignal(listOf(false, false, false))
        repeat(3) { poll(value = "", denied = true) }
        memo.onCapabilitySignal(listOf(false, false, false))
        poll(value = "", denied = true)
        assertEquals(1, reads)

        memo.onCapabilitySignal(listOf(false, true, false))
        assertEquals("5555", poll(value = "5555", denied = false))

        assertEquals(2, reads)
        assertEquals(1, logs.size)
    }

    @Test
    fun aRememberedDenialExpiresAfterItsTtl() {
        repeat(3) { poll(value = "", denied = true) }
        now += 1_000L
        repeat(3) { poll(value = "", denied = true) }

        assertEquals(2, reads)
        assertEquals(2, probes)
        assertEquals(1, logs.size)
    }

    @Test
    fun anUnsetReadableValueIsStillReadEveryPollAndALaterValueIsSeen() {
        repeat(5) { assertEquals("", poll(value = "", denied = false)) }
        assertEquals("1", poll(value = "1", denied = false))

        assertEquals(6, reads)
        assertEquals(1, probes)
        assertTrue(logs.isEmpty())
    }

    @Test
    fun aSuccessfulReadNeverProbes() {
        repeat(5) { assertEquals("0", poll(value = "0", denied = true)) }

        assertEquals(5, reads)
        assertEquals(0, probes)
    }

    @Test
    fun aThrowingProbeIsNotADenial() {
        repeat(3) {
            memo.read(key = "file:/proc/net/tcp", what = "t", probeDenied = { probes++; error("io") }) { reads++; null }
        }

        assertEquals(3, reads)
        assertEquals(1, probes)
        assertTrue(logs.isEmpty())
    }

    @Test
    fun openAndListProbesReportReadablePathsAsPermittedAndNeverCallAMissingPathDenied() {
        val dir = Files.createTempDirectory("denial").toFile()
        val file = java.io.File(dir, "f").apply { writeText("x") }
        try {
            assertEquals(false, AccessDenialMemo.openDenied(file.path))
            assertEquals(false, AccessDenialMemo.listDenied(dir.path))
            assertThrows(NoSuchFileException::class.java) { AccessDenialMemo.openDenied("${dir.path}/absent") }
            assertThrows(NoSuchFileException::class.java) { AccessDenialMemo.listDenied("${dir.path}/absent") }
        } finally {
            dir.deleteRecursively()
        }
    }
}
