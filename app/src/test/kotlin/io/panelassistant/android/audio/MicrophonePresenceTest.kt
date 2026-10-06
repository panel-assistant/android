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

    @Test fun `the first reading is published as initial, then once per press, and an unreadable mute reads as unmuted`() {
        var reading: () -> Boolean = { false }
        val published = mutableListOf<Pair<Boolean, Boolean>>()
        val mute = MicrophoneMute(read = { reading() }, publish = { muted, initial -> published += muted to initial })
        mute.refresh()
        mute.refresh()
        reading = { true }
        mute.refresh()
        mute.refresh()
        reading = { error("audio service gone") }
        mute.refresh()
        assertEquals(listOf(false to true, true to false, false to false), published)
        assertEquals(false, mute.muted)
    }

    @Test fun `a refresh that reads later publishes later, so an old mute never lands after a newer unmute`() {
        // Thread A reads "muted"; while A is publishing, thread B reads "unmuted". A slow publish of A's
        // reading must not land after B's, or the chip would say muted on an unmuted panel indefinitely.
        var reading = true
        val published = java.util.Collections.synchronizedList(mutableListOf<Boolean>())
        var other: Thread? = null
        lateinit var mute: MicrophoneMute
        mute = MicrophoneMute(read = { reading }, publish = { value, _ ->
            if (value && other == null) {
                reading = false
                other = Thread { mute.refresh() }.also { it.start() }
                Thread.sleep(300) // B runs now if nothing holds it back
            }
            published += value
        })
        mute.refresh()
        other!!.join(5_000)
        assertEquals(listOf(true, false), published.toList())
        assertEquals(false, mute.muted)
    }

    @Test fun `nothing is published once closed`() {
        var reading = false
        val published = mutableListOf<Boolean>()
        val mute = MicrophoneMute(read = { reading }, publish = { muted, _ -> published += muted })
        mute.close()
        reading = true
        mute.refresh()
        assertEquals(emptyList<Boolean>(), published)
    }
}
