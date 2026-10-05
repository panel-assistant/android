package io.panelassistant.android

import io.panelassistant.android.dashboard.EntityFilterProtocol
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardEntityFilterPreparationTest {
    @Test fun largeFilterPreparesTheExactRendererStateAwayFromItsCaller() = runBlocking {
        val caller = Thread.currentThread()
        val ids = (0 until EntityFilterProtocol.MAX_ENTITY_IDS).map { "sensor.entity_$it" }.reversed()
        var readThread: Thread? = null
        var hashThread: Thread? = null

        val prepared = prepareEntityFilterOffMain(
            read = {
                readThread = Thread.currentThread()
                EntityFilterPreparationInput(
                    enabled = true,
                    learningEnabled = true,
                    ids = ids,
                    haUrl = "https://ha.example",
                    origins = setOf("https://ha.example"),
                )
            },
            hash = { normalized ->
                hashThread = Thread.currentThread()
                EntityFilterProtocol.hash(normalized)
            },
        )

        assertNotEquals(caller, readThread)
        assertEquals(readThread, hashThread)
        assertEquals(EntityFilterProtocol.MAX_ENTITY_IDS, prepared.ids.size)
        assertTrue(prepared.signature.startsWith("enabled:${prepared.hash}:https://ha.example:learning=true"))
        assertTrue(prepared.script!!.getOrThrow().contains("sensor.entity_9999"))
    }
}
