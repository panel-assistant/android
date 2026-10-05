package io.panelassistant.android.http

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltinRendererAuthAdmissionTest {
    @Test fun `new built in selection requires a configured Home Assistant URL`() {
        assertTrue(builtinRendererNeedsConnection("", "", false, "builtin", null, false))
        assertTrue(builtinRendererNeedsConnection("", "", false, "builtin", "  ", true))
        assertTrue(builtinRendererNeedsConnection("", "", false, "builtin", "https://ha.example", false))
        assertFalse(builtinRendererNeedsConnection("", "", false, "builtin", "https://ha.example", true))
    }

    @Test fun `legacy state may boot but cannot create another blank built in commit`() {
        assertTrue(builtinRendererNeedsConnection("builtin", "", false, null, null, false))
        assertFalse(builtinRendererNeedsConnection("builtin", "", false, "com.example.dashboard", null, false))
        assertFalse(builtinRendererNeedsConnection("builtin", "https://ha.example", true, null, null, false))
    }
}
