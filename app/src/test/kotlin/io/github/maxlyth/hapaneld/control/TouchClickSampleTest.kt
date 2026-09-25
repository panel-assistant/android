package io.github.maxlyth.hapaneld.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchClickSampleTest {
    @Test fun `owned click is a non-silent mono PCM wav`() {
        val wav = touchClickWav()

        assertEquals("RIFF", wav.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        assertEquals("WAVE", wav.copyOfRange(8, 12).toString(Charsets.US_ASCII))
        assertEquals("data", wav.copyOfRange(36, 40).toString(Charsets.US_ASCII))
        assertTrue(wav.size > 44)
        assertTrue(wav.copyOfRange(44, wav.size).any { it.toInt() != 0 })
    }

}
