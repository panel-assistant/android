package io.panelassistant.android.migration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReleaseTokenTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun nothingIsStoredUntilATokenIsMinted() {
        val token = ReleaseToken(temp.root)

        assertNull(token.current())
        assertFalse(token.matches("a".repeat(64)))
    }

    @Test fun theMintedTokenIsStableAcrossProcessesSoARedeliveryCarriesTheSameProof() {
        val minted = ReleaseToken(temp.root).ensure()!!

        assertTrue(ReleaseToken.wellFormed(minted))
        assertEquals(minted, ReleaseToken(temp.root).ensure { error("must not mint twice") })
        assertEquals(minted, ReleaseToken(temp.root).current())
    }

    @Test fun mintedTokensComeFromTheRandomSource() {
        val first = ReleaseToken(temp.newFolder("a")).ensure { it.fill(0x11) }
        val second = ReleaseToken(temp.newFolder("b")).ensure { it.fill(0x22) }

        assertEquals("11".repeat(32), first)
        assertNotEquals(first, second)
    }

    @Test fun onlyTheExactMintedTokenMatches() {
        val store = ReleaseToken(temp.root)
        val minted = store.ensure { it.fill(0x5a) }!!

        assertTrue(store.matches(minted))
        assertFalse(store.matches("5a".repeat(31) + "5b"))
        assertFalse(store.matches(minted.uppercase()))
        assertFalse(store.matches(minted + "00"))
        assertFalse(store.matches(""))
        assertFalse(store.matches(null))
    }

    @Test fun theSuccessorKeepsOnlyAWellFormedToken() {
        val store = ReleaseToken(temp.root)

        assertFalse(store.accept(null))
        assertFalse(store.accept("not-a-token"))
        assertFalse(store.accept("A".repeat(64)))
        assertNull(store.current())

        assertTrue(store.accept("c".repeat(64)))
        assertEquals("c".repeat(64), ReleaseToken(temp.root).current())
    }

    @Test fun aCorruptedRecordIsTreatedAsNoToken() {
        temp.root.resolve("identity-migration").mkdirs()
        temp.root.resolve("identity-migration/release-token.v1").writeText("truncated")

        assertNull(ReleaseToken(temp.root).current())
        assertFalse(ReleaseToken(temp.root).matches("truncated"))
    }
}
