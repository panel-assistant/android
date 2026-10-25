package io.panelassistant.android

import org.junit.Assert.assertEquals
import org.junit.Test

class PanelFontTest {
    // Each fixture is a panel's own /system/etc/fonts.xml, read as the app reads it.
    private fun fonts(name: String) = javaClass.getResource("/fixtures/system-fonts/$name")!!.readText()

    @Test fun `only a system font that replaces Roboto takes the bundled face`() {
        assertEquals(true, PanelFont.replacesRoboto(fonts("meta-portal-android10.xml")))
        assertEquals(false, PanelFont.replacesRoboto(fonts("nspanel86-android8.xml")))
        assertEquals(false, PanelFont.replacesRoboto(fonts("android14.xml")))
        assertEquals("an unreadable file keeps the system faces", false, PanelFont.replacesRoboto(null))
    }
}
