package io.panelassistant.android.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The close idiom the backup bundle's configuration capture depends on.
 *
 * `SQLiteOpenHelper` only implements `AutoCloseable` from API 29, so `use { }` on the catalog store
 * compiles against the current compileSdk yet throws `ClassCastException` at runtime on Android 8.1
 * (API 27). The throw lands behind the capture's best-effort guard, so its visible effect is a backup
 * with no configuration entry and nothing reported. [readThenClose] is the one place the safe idiom
 * lives; these tests pin its behaviour.
 */
class CatalogStoreCloseTest {

    private class FakeStore {
        var closed = 0
        var closedBeforeRead = false
    }

    @Test fun theResultSurvivesACloseThatThrows() {
        val store = FakeStore()
        // A store that read successfully but failed to close has still produced a valid result.
        // Discarding it over the close would silently cost an Android 8.1 backup its configuration —
        // the exact silent-empty outcome this idiom exists to prevent.
        val rows = readThenClose(store, close = { throw IllegalStateException("close blew up") }) {
            listOf("k=v")
        }
        assertEquals(listOf("k=v"), rows)
    }

    @Test fun aFailedReadStillClosesAndKeepsItsOwnException() {
        val store = FakeStore()
        var thrown: Throwable? = null
        try {
            readThenClose(store, close = { it.closed++ }) { throw java.io.IOException("unreadable") }
        } catch (error: Throwable) {
            thrown = error
        }
        // The read's own failure is what the caller's best-effort guard must see — not a close
        // failure, and not a success.
        assertTrue("the read's exception must propagate", thrown is java.io.IOException)
        assertEquals("the store must still be closed", 1, store.closed)
    }

    @Test fun theStoreIsClosedExactlyOnceAndOnlyAfterTheRead() {
        val store = FakeStore()
        readThenClose(store, close = { it.closed++ }) {
            it.closedBeforeRead = store.closed > 0
            "result"
        }
        assertEquals(1, store.closed)
        assertFalse("close must not run before the read", store.closedBeforeRead)
    }
}
