package io.panelassistant.android.config

import io.panelassistant.android.http.projectConfigSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TameVendorPackagesSpecTest {
    private val spec = requireNotNull(SettingsRegistry.spec("tame_vendor_packages"))

    @Test fun `selection is hidden device state owned by the vendor packages card`() {
        assertEquals(SettingType.STRING, spec.type)
        assertEquals("System", spec.group)
        assertEquals("", spec.default)
        assertEquals(Scope.DEVICE, spec.scope)
        assertTrue(spec.hidden)
        assertFalse(spec.secret)
        assertFalse(spec.transient)
        assertNull(spec.ha)
        assertTrue(spec in SettingsRegistry.settable())
    }

    @Test fun `package list validation is canonical and shared by direct and bundle inputs`() {
        val value = SettingValue.validate(
            spec,
            " com.vendor.one, com.vendor.two\ncom.vendor.one ",
        ) as Validation.Ok

        assertEquals("com.vendor.one com.vendor.two", value.normalized)
        val protected = SettingValue.validate(
            spec,
            "com.vendor.one com.android.systemui android com.vendor.two",
        ) as Validation.Ok
        assertEquals(
            "preview must exactly match the safely persisted selection",
            "com.vendor.one com.vendor.two",
            protected.normalized,
        )
        listOf(
            "com.good;reboot",
            "com.good/package",
            "com.good package-with-dash",
            "x".repeat(256),
        ).forEach { raw ->
            assertTrue(raw, SettingValue.validate(spec, raw) is Validation.Bad)
        }
        val tooMany = (0..128).joinToString(" ") { "com.vendor.pkg$it" }
        assertTrue(SettingValue.validate(spec, tooMany) is Validation.Bad)
    }

    @Test fun `package list has an explicit utf8 byte bound`() {
        val overByteLimitButUnderCharacterLimit = "é".repeat(4_097)
        val rejected = SettingValue.validate(spec, overByteLimitButUnderCharacterLimit)

        assertTrue(rejected is Validation.Bad)
        assertTrue((rejected as Validation.Bad).reason.contains("8192 bytes"))
    }

    @Test fun `full snapshots and revisions include the selection while fleet imports exclude it`() {
        val snapshot = projectConfigSnapshot(
            specs = listOf(spec),
            zigbeeRouterConfigured = false,
            effectiveValue = { "com.vendor.one com.vendor.two" },
        )

        assertEquals("com.vendor.one com.vendor.two", snapshot["tame_vendor_packages"])
        assertTrue(
            "a change must participate in revision hashes",
            ConfigHash.of(snapshot) != ConfigHash.of(snapshot + ("tame_vendor_packages" to "com.vendor.one")),
        )
        assertFalse("DEVICE values are not eligible for fleet import", spec.scope == Scope.PORTABLE)
    }
}
