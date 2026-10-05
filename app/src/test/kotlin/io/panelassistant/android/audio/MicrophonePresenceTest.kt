package io.panelassistant.android.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class MicrophonePresenceTest {

    @Test fun `a profile's declaration decides, and only an undeclared microphone asks Android`() {
        val expected = mapOf(
            (true to true) to MicrophonePresence.PROVEN,
            (true to false) to MicrophonePresence.PROVEN,
            (false to true) to MicrophonePresence.ABSENT,
            (false to false) to MicrophonePresence.ABSENT,
            (null to true) to MicrophonePresence.UNPROVEN,
            (null to false) to MicrophonePresence.ABSENT,
        )
        expected.forEach { (input, presence) ->
            assertEquals("declared=${input.first} reported=${input.second}", presence, MicrophonePresence.resolve(input.first, input.second))
        }
    }
}
