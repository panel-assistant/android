package io.panelassistant.android.persistence

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The whole point of the section: a panel that had nothing stored and a panel that could not read what it
 * stored must not produce the same archive, and no reader may resolve the difference to silence.
 */
class StateArchiveSectionTest {
    private val entry = "state/app-state.txt"

    @Test fun `an empty panel and an unreadable database no longer write the same manifest`() {
        val empty = StateArchiveSection.manifestFragment(entry, bytes = null, rows = 0, captureFailed = false)
        val failed = StateArchiveSection.manifestFragment(entry, bytes = null, rows = 0, captureFailed = true)

        assertNull("a panel with no stored rows still writes no state section", empty)
        assertEquals("""{"error":"capture-failed"}""", failed)
        assertNotEquals("an unreadable database must be distinguishable from an empty one", empty, failed)
    }

    @Test fun `a captured section declares its entry size and row count`() {
        assertEquals(
            """{"entry":"state/app-state.txt","size":4096,"rows":76}""",
            StateArchiveSection.manifestFragment(entry, bytes = 4096, rows = 76, captureFailed = false),
        )
    }

    @Test fun `an entry name is JSON-escaped rather than concatenated`() {
        assertEquals(
            """{"entry":"state/\"odd\".txt","size":1,"rows":1}""",
            StateArchiveSection.manifestFragment("state/\"odd\".txt", bytes = 1, rows = 1, captureFailed = false),
        )
    }

    @Test fun `a failed capture can never also declare a payload`() {
        assertThrows(IllegalArgumentException::class.java) {
            StateArchiveSection.manifestFragment(entry, bytes = 1, rows = 1, captureFailed = true)
        }
    }

    @Test fun `the marker carries a fixed code and never a failure message`() {
        val fragment = StateArchiveSection.manifestFragment(entry, null, 0, captureFailed = true)

        assertEquals("""{"error":"capture-failed"}""", fragment)
        assertEquals("capture-failed", StateArchiveSection.CAPTURE_FAILED)
    }

    @Test fun `an absent section stays absent and a declared payload stays restorable`() {
        assertEquals(StateArchiveSection.Disposition.ABSENT, StateArchiveSection.restoreStateDisposition(null))
        assertEquals(
            StateArchiveSection.Disposition.RESTORABLE,
            StateArchiveSection.restoreStateDisposition(
                JSONObject("""{"entry":"state/app-state.txt","size":10,"rows":3}"""),
            ),
        )
    }

    @Test fun `a marked section is incomplete rather than nothing to restore`() {
        assertEquals(
            StateArchiveSection.Disposition.INCOMPLETE,
            StateArchiveSection.restoreStateDisposition(JSONObject("""{"error":"capture-failed"}""")),
        )
    }

    @Test fun `an unknown error code is still incomplete so a newer writer is never read as complete`() {
        assertEquals(
            StateArchiveSection.Disposition.INCOMPLETE,
            StateArchiveSection.restoreStateDisposition(JSONObject("""{"error":"some-later-code"}""")),
        )
    }

    @Test fun `a section that claims neither a payload nor a failure is malformed`() {
        assertNull(StateArchiveSection.restoreStateDisposition(JSONObject("{}")))
        assertNull(StateArchiveSection.restoreStateDisposition(JSONObject("""{"size":12}""")))
    }

    @Test fun `a section that claims both a payload and a failure is malformed`() {
        assertNull(
            StateArchiveSection.restoreStateDisposition(
                JSONObject("""{"entry":"state/app-state.txt","size":10,"rows":3,"error":"capture-failed"}"""),
            ),
        )
    }

    @Test fun `an error that is not usable text is malformed`() {
        assertNull(StateArchiveSection.restoreStateDisposition(JSONObject("""{"error":12}""")))
        assertNull(StateArchiveSection.restoreStateDisposition(JSONObject("""{"error":true}""")))
        assertNull(StateArchiveSection.restoreStateDisposition(JSONObject("""{"error":{"code":"x"}}""")))
        assertNull(StateArchiveSection.restoreStateDisposition(JSONObject("""{"error":""}""")))
    }

    @Test fun `an error code is bounded so a manifest cannot smuggle prose through it`() {
        val longest = "e".repeat(64)

        assertEquals(
            StateArchiveSection.Disposition.INCOMPLETE,
            StateArchiveSection.restoreStateDisposition(JSONObject(mapOf("error" to longest))),
        )
        assertNull(StateArchiveSection.restoreStateDisposition(JSONObject(mapOf("error" to longest + "e"))))
    }
}
