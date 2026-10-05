package io.panelassistant.android.http

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DirectConfigMutationPlanTest {
    @Test fun `touch change excludes unchanged navbar and auto sleep`() {
        val plan = planDirectConfigMutation(
            posted = linkedMapOf(
                "auto_sleep" to "true",
                "navbar_mode" to "Swipe reveal",
                "touch_sound" to "true",
            ),
            before = mapOf(
                "auto_sleep" to "true",
                "navbar_mode" to "Swipe reveal",
                "touch_sound" to "false",
            ),
        )

        assertEquals(setOf("touch_sound"), plan.changedKeys)
        assertEquals(listOf("touch_sound" to "true"), plan.changedLive)
        assertFalse(plan.requiresReconfigure)
    }

    @Test fun `touch only execution cannot acquire any broad replay owner or restore itself`() {
        val original = linkedMapOf(
            "touch_sound" to "true",
            "navbar_mode" to "Off",
            "auto_sleep" to "true",
        )
        val plan = planDirectConfigMutation(
            posted = mapOf("touch_sound" to "false"),
            before = original,
        )
        val forbiddenOwners = setOf(
            "tame_vendor_packages",
            "kiosk_lock",
            "watchdog_enabled",
            "silence_boot_chime",
            "brightness",
            "prevent_idle_dim",
            "dashboard_package",
            "home_dashboard",
            "auto_sleep",
            "navbar_mode",
        )
        val applied = original.toMutableMap()
        val dispatches = ArrayList<String>()
        plan.changedLive.forEach { (key, value) ->
            dispatches += key
            applied[key] = value
        }

        assertEquals(listOf("touch_sound"), dispatches)
        assertTrue(plan.changedKeys.intersect(forbiddenOwners).isEmpty())
        assertFalse(plan.requiresReconfigure)
        assertEquals("false", applied["touch_sound"])
        assertEquals("Off", applied["navbar_mode"])
        assertEquals("true", applied["auto_sleep"])
    }

    @Test fun `unchanged auto sleep is a true no-op with no broad reconfigure`() {
        val plan = planDirectConfigMutation(
            posted = mapOf("auto_sleep" to "false"),
            before = mapOf("auto_sleep" to "false"),
        )

        assertTrue(plan.isNoOp)
        assertTrue(plan.changedLive.isEmpty())
        assertFalse(plan.requiresReconfigure)
    }

    @Test fun `ordinary or exposure change requests reconfigure while local live change does not`() {
        assertTrue(planDirectConfigMutation(
            posted = mapOf("friendly_name" to "Alpha"),
            before = mapOf("friendly_name" to "Panel"),
        ).requiresReconfigure)
        assertTrue(planDirectConfigMutation(
            posted = mapOf("ha_expose_touch_sound" to "false"),
            before = mapOf("ha_expose_touch_sound" to "true"),
        ).requiresReconfigure)
        assertFalse(planDirectConfigMutation(
            posted = mapOf("touch_sound" to "true"),
            before = mapOf("touch_sound" to "false"),
        ).requiresReconfigure)
    }

    @Test fun `secret placeholder and an already canonical dashboard path are unchanged`() {
        val plan = planDirectConfigMutation(
            posted = mapOf("ha_token" to "", "home_dashboard" to "/lovelace/home"),
            before = mapOf("ha_token" to "secret", "home_dashboard" to "/lovelace/home"),
        )

        assertTrue(plan.isNoOp)
    }

    @Test fun `a stored dashboard path that is not canonical is rewritten rather than skipped`() {
        // Previously this compared RENDERER semantics, so any spelling that resolved the same way was
        // discarded as a no-op and the stored value could never be canonicalized. "Unchanged" now means
        // the write would not alter stored state.
        val plan = planDirectConfigMutation(
            posted = mapOf("home_dashboard" to "/lovelace/home/"),
            before = mapOf("home_dashboard" to "lovelace/home"),
        )

        assertFalse(plan.isNoOp)
        assertTrue("home_dashboard" in plan.changedKeys)
    }

    @Test fun `choosing the account default over a legacy root spelling reaches storage`() {
        // The defect this pins: `/` and blank resolve identically, so the blank write was skipped and
        // the stale root survived — reopening the picker in Custom after the user had chosen and saved
        // Auto. Every legacy spelling of "follow the account default" must plan a real write.
        for (stored in listOf("/", "//", "/?kiosk", "/#view")) {
            val plan = planDirectConfigMutation(
                posted = mapOf("home_dashboard" to ""),
                before = mapOf("home_dashboard" to stored),
            )

            assertFalse(plan.isNoOp, "stored $stored was treated as already blank")
            assertTrue("home_dashboard" in plan.changedKeys)
        }
    }

    @Test fun `pending desired is idempotent and can be explicitly cancelled`() {
        val desiredBaseline = mapOf("touch_sound" to "true")
        assertTrue(planDirectConfigMutation(
            posted = mapOf("touch_sound" to "true"),
            before = desiredBaseline,
        ).isNoOp)
        assertEquals(
            listOf("touch_sound" to "false"),
            planDirectConfigMutation(
                posted = mapOf("touch_sound" to "false"),
                before = desiredBaseline,
            ).changedLive,
        )
    }

    @Test fun `complete effective baseline keeps transient and untouched hardware values no-op`() {
        val plan = planDirectConfigMutation(
            posted = mapOf("cpu_governor" to "schedutil", "zigbee_router" to "false"),
            before = mapOf("cpu_governor" to "schedutil", "zigbee_router" to "false"),
        )
        assertTrue(plan.isNoOp)
    }

    @Test fun `json API and html form negotiation are deterministic`() {
        assertTrue(configMutationWantsJson("application/json", "application/x-www-form-urlencoded"))
        assertTrue(configMutationWantsJson("*/*", "application/json; charset=utf-8"))
        assertFalse(configMutationWantsJson("text/html", "application/x-www-form-urlencoded"))
        assertEquals(
            "<!doctype html><base href=\"/\"><meta charset=utf-8><meta http-equiv=refresh content='2;url=configure'>" +
                "<body style='font-family:system-ui;background:#111;color:#eee;padding:20px'>" +
                "Saved &amp; pending &lt;touch&gt;</body>",
            configMutationHtml("Saved & pending <touch>"),
        )
    }
}
