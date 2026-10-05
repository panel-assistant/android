package io.panelassistant.android.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure halves of the Companion sign-in borrow (switching to the built-in renderer reuses the
 *  Companion's login): the sqlite line parse, the active-server xml parse, and the pick rules. */
class CompanionLoginBorrowTest {
    private fun line(vararg f: String) = f.joinToString("\u001f")

    @Test fun parsesLoginRows() {
        val out = line("1", "http://ha.local:8123", "https://ha.example", "RT1", "AT1", "1783670000") + "\n" +
            line("2", "", "https://two.example", "RT2", "", "")
        val rows = CompanionDb.parseLogins(out)
        assertEquals(2, rows.size)
        assertEquals("http://ha.local:8123", rows[0].url)          // internal preferred
        assertEquals(1783670000L, rows[0].expirySec)
        assertEquals("https://two.example", rows[1].url)           // external fallback
        assertEquals(0L, rows[1].expirySec)                        // blank expiry tolerated
    }

    @Test fun activeServerParsedFromSessionXml() {
        assertEquals("2", CompanionDb.parseActiveServer("""<int name="active_server" value="2" />"""))
        assertNull(CompanionDb.parseActiveServer("<map></map>"))
        assertNull(CompanionDb.parseActiveServer(null))
    }

    @Test fun pickPrefersActiveThenFirstSignedIn() {
        val rows = CompanionDb.parseLogins(
            line("1", "http://one", "", "", "AT", "0") + "\n" +      // no refresh token — not borrowable
                line("2", "http://two", "", "RT2", "", "0") + "\n" +
                line("3", "http://three", "", "RT3", "", "0"),
        )
        assertEquals("3", CompanionDb.pickLogin(rows, "3")?.id)     // active wins
        assertEquals("2", CompanionDb.pickLogin(rows, "9")?.id)     // unknown active -> first signed-in
        assertEquals("2", CompanionDb.pickLogin(rows, null)?.id)
        assertNull(CompanionDb.pickLogin(rows.take(1), null))       // token-less rows never borrowed
    }
}
