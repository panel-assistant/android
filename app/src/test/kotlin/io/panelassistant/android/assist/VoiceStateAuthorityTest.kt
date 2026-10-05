package io.panelassistant.android.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceStateAuthorityTest {
    @Test fun `defaults to off and reports the wire value lowercase`() {
        val authority = VoiceStateAuthority()
        assertEquals(VoiceState.OFF, authority.current())
        assertEquals("off", VoiceState.OFF.wireValue)
        assertEquals("listening", VoiceState.LISTENING.wireValue)
    }

    @Test fun `set notifies the change listener only on an actual change`() {
        val authority = VoiceStateAuthority()
        var notifications = 0
        authority.setChangeListener { notifications++ }

        authority.set(VoiceState.LISTENING)
        assertEquals(VoiceState.LISTENING, authority.current())
        assertEquals(1, notifications)

        authority.set(VoiceState.LISTENING)
        assertEquals("re-asserting the same phase must not re-notify", 1, notifications)

        authority.set(VoiceState.IDLE)
        assertEquals(2, notifications)
    }

    @Test fun `set is a no-op with no listener attached`() {
        val authority = VoiceStateAuthority()
        authority.set(VoiceState.ERROR)
        assertEquals(VoiceState.ERROR, authority.current())
    }

    @Test fun `AssistPipelineDirectory NOT_WIRED reports not configured`() {
        val result = kotlinx.coroutines.runBlocking { AssistPipelineDirectory.NOT_WIRED.list() }
        assertTrue(result is AssistPipelineDirectory.Result.NotConfigured)
    }

    @Test fun `VoiceTestTrigger NOT_WIRED reports unavailable`() {
        val result = VoiceTestTrigger.NOT_WIRED.trigger()
        assertTrue(result is VoiceTestTrigger.Result.Unavailable)
        assertFalse((result as VoiceTestTrigger.Result.Unavailable).reason.isBlank())
    }
}
