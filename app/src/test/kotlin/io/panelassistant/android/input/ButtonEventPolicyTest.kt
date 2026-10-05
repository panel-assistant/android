package io.panelassistant.android.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ButtonEventPolicyTest {
    @Test fun accessibilityEmitsExactlyOncePerPhysicalPress() {
        assertEquals("KEYCODE_F1", ButtonEventPolicy.accessibility(true, 0, "KEYCODE_F1"))
        assertNull(ButtonEventPolicy.accessibility(true, 1, "KEYCODE_F1"))
        assertNull(ButtonEventPolicy.accessibility(false, 0, "KEYCODE_F1"))
    }
}
