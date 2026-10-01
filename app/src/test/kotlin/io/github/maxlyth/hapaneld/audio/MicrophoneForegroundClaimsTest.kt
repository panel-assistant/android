package io.github.maxlyth.hapaneld.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MicrophoneForegroundClaimsTest {
    @Test fun aVoiceStopPreservesStreamingForegroundAndLastStreamStopReleasesIt() {
        val transitions = ArrayList<Boolean>()
        val claims = MicrophoneForegroundClaims { transitions += it; true }
        assertTrue(claims.set("voice", true))
        assertTrue(claims.set("camera", true))
        assertTrue(claims.set("voice", false))
        assertEquals(listOf(true), transitions)
        assertTrue(claims.set("camera", false))
        assertEquals(listOf(true, false), transitions)
        assertTrue(claims.set("camera", false))
        assertEquals(listOf(true, false), transitions)
    }

    @Test fun refusedAdmissionDoesNotCreateAClaimAndCanBeRetried() {
        var accepts = false
        val transitions = ArrayList<Boolean>()
        val claims = MicrophoneForegroundClaims { transitions += it; accepts }
        assertFalse(claims.set("camera", true))
        assertTrue(claims.set("camera", false))
        accepts = true
        assertTrue(claims.set("voice", true))
        assertTrue(claims.set("voice", false))
        assertEquals(listOf(true, true, false), transitions)
    }
}
