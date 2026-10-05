package io.panelassistant.android.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidInputTest {
    @Test fun packageGrammarMatchesPrivilegedBoundary() {
        listOf("io.homeassistant.companion.android", "com.vendor_App.2").forEach {
            assertTrue(it, AndroidInput.isPackage(it))
        }
        listOf("", ".", "..", ".com.example", "com..example", "com.example.", "com.example app", "com.example;reboot", "com/example", "com.example\nreboot").forEach {
            assertFalse(it, AndroidInput.isPackage(it))
        }
    }

    @Test fun componentRequiresOneSafeSeparator() {
        assertTrue(AndroidInput.isComponent("com.example/.MainActivity"))
        assertTrue(AndroidInput.isComponent("com.example/com.example.MainActivity"))
        listOf("com.example", "com.example/", "/.Main", "com.example/.Main;reboot", "com.example/.Main/Extra").forEach {
            assertFalse(it, AndroidInput.isComponent(it))
        }
    }

    @Test fun dashboardTargetAllowsOnlySentinelsOrPackages() {
        assertTrue(AndroidInput.isDashboardTarget(""))
        assertTrue(AndroidInput.isDashboardTarget("builtin"))
        assertTrue(AndroidInput.isDashboardTarget("com.example.dashboard"))
        assertFalse(AndroidInput.isDashboardTarget("com.example;id"))
    }
}
