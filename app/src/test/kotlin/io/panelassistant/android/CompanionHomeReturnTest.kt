package io.panelassistant.android

import io.panelassistant.android.util.CompanionOperationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionHomeReturnTest {
    private val companion = "io.homeassistant.companion.android.minimal"

    @Test fun `a helper transaction blocks the foreground deep link`() {
        assertTrue(CompanionHomeReturn.mayDispatch(CompanionOperationStatus.IDLE, { true }, { false }))
        assertFalse(CompanionHomeReturn.mayDispatch(CompanionOperationStatus.BUSY, { true }, { false }))
        assertFalse(CompanionHomeReturn.mayDispatch(CompanionOperationStatus.UNSUPPORTED, { true }, { false }))
        assertFalse(CompanionHomeReturn.mayDispatch(CompanionOperationStatus.UNAVAILABLE, { true }, { false }))
        assertFalse(CompanionHomeReturn.mayDispatch(CompanionOperationStatus.UNAVAILABLE, { false }, { true }))
        assertTrue(CompanionHomeReturn.mayDispatch(CompanionOperationStatus.UNAVAILABLE, { false }, { false }))
    }

    @Test fun `foreground return delivers the exact panel route once`() {
        var observed = ""
        var recorded = 0
        CompanionHomeReturn.arm(companion, "/kitchen/0?theme=dark", 100, { true }, { recorded++ })
        assertTrue(CompanionHomeReturn.deliver(200) { pkg, path ->
            observed = "$pkg:$path"
            true
        })
        assertEquals("$companion:/kitchen/0?theme=dark", observed)
        assertEquals(1, recorded)
        assertFalse(CompanionHomeReturn.deliver(201) { _, _ -> true })
    }

    @Test fun `stale manual navigation and a failed foreground launch never report home`() {
        var currentRoute = "/old"
        var recorded = 0
        CompanionHomeReturn.arm(companion, "/kitchen", 100, { currentRoute == "/old" }, { recorded++ })
        currentRoute = "/manual"
        assertFalse(CompanionHomeReturn.deliver(200) { _, _ -> true })
        CompanionHomeReturn.arm(companion, "/kitchen", 100, { true }, { recorded++ })
        assertFalse(CompanionHomeReturn.deliver(200) { _, _ -> false })
        assertEquals(0, recorded)
    }

    @Test fun `expired or superseded return does not override a newer launch`() {
        val first = CompanionHomeReturn.arm(companion, "/old", 100, { true }, {})
        CompanionHomeReturn.arm(companion, "/new", 100, { true }, {})
        CompanionHomeReturn.clear(first)
        var path = ""
        assertTrue(CompanionHomeReturn.deliver(200) { _, route -> path = route; true })
        assertEquals("/new", path)
        CompanionHomeReturn.arm(companion, "/new", 100, { true }, {})
        assertFalse(CompanionHomeReturn.deliver(10_101) { _, _ -> true })
    }
}
